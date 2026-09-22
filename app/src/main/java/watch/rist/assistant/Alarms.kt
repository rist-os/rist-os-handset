package watch.rist.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The device's durable record of which alarms are armed.
 *
 * Android drops every AlarmManager alarm on reboot. Before this store existed the armed
 * PendingIntent was the ONLY record an alarm had ever been set, so a restart silently
 * cancelled it and nothing on the phone knew it was gone.
 *
 * The backend is the source of truth for what alarms exist, but it cannot be the thing the
 * phone recovers from at boot: there is no device-callable route that lists alarms, and the
 * phone frequently has no network for the first minutes after a restart. An alarm that only
 * survives a reboot when the network is up is not an alarm. So the phone keeps its own copy
 * and re-arms from it immediately, with no round trip.
 */
object Alarms {

    private const val TAG = "RistAlarms"

    /**
     * Where the list is kept.
     *
     * Production always uses the encrypted preference store. The seam exists because the
     * alarms key is a SECRET_KEY, and a unit test has no Android keystore to unlock that
     * store with, so writes there are dropped by design and the reboot behaviour could not
     * otherwise be tested at all.
     */
    internal interface Store {
        fun read(ctx: Context): String
        fun write(ctx: Context, json: String)
    }

    private object EncryptedPrefs : Store {
        override fun read(ctx: Context) = Config.alarms(ctx)
        override fun write(ctx: Context, json: String) = Config.setAlarms(ctx, json)
    }

    internal var store: Store = EncryptedPrefs

    data class Armed(
        val id: String,
        /** When it will next ring. Differs from [scheduledEpochS] only while snoozed. */
        val fireAtEpochS: Long,
        val label: String,
        val sound: Boolean,
        val vibrate: Boolean,
        /** The backend's closed vocabulary: "", daily, weekdays, weekends, weekly:<dow>. */
        val recurrence: String,
        /**
         * The time on the schedule, which a snooze must not move: a daily 07:00 alarm snoozed
         * to 07:09 still returns to 07:00 tomorrow, not 07:09 and then 07:18.
         */
        val scheduledEpochS: Long = fireAtEpochS,
        /**
         * Seconds past local midnight the schedule was set for. Kept separately because it is
         * the one thing a daylight-saving gap must not be allowed to rewrite: deriving it from
         * an epoch that landed on a gap day would turn 02:30 into 03:30 permanently.
         */
        val todSec: Int = Alarms.timeOfDay(scheduledEpochS),
    )

    @Synchronized
    fun held(ctx: Context): List<Armed> = runCatching {
        val arr = JSONArray(store.read(ctx).ifBlank { "[]" })
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val at = o.optLong("at")
            val sched = if (o.has("sched")) o.optLong("sched") else at
            Armed(
                id = id,
                fireAtEpochS = at,
                label = o.optString("label"),
                sound = o.optBoolean("sound", true),
                vibrate = o.optBoolean("vibrate", true),
                recurrence = o.optString("recurrence"),
                scheduledEpochS = sched,
                todSec = if (o.has("tod")) o.optInt("tod") else timeOfDay(sched),
            )
        }
    }.getOrElse {
        Log.w(TAG, "alarm store unreadable; treating as empty", it)
        emptyList()
    }

    @Synchronized
    fun remember(ctx: Context, alarm: Armed) {
        // Re-arming an existing id replaces it rather than adding a duplicate, which matches
        // AlarmManager: the same PendingIntent request code overwrites the previous alarm.
        save(ctx, held(ctx).filterNot { it.id == alarm.id } + alarm)
        Log.i(TAG, "remembered alarm id='${alarm.id}' at=${alarm.fireAtEpochS}")
    }

    @Synchronized
    fun forget(ctx: Context, id: String) {
        val before = held(ctx)
        val after = before.filterNot { it.id == id }
        if (after.size != before.size) {
            save(ctx, after)
            Log.i(TAG, "forgot alarm id='$id'")
        }
    }

    /**
     * An alarm missed by this much still rings, immediately. Reboots and app updates take
     * seconds, and BOOT_COMPLETED can land a minute after the moment; an alarm set for 07:00
     * that the phone reached at 07:00:20 must not be silently discarded.
     */
    private const val LATE_GRACE_S = 5L * 60L

    /**
     * Re-arms every alarm still in the future, rings the ones missed by moments, rolls
     * repeating ones forward, and drops one-shots whose moment is truly gone.
     *
     * An alarm hours late does NOT fire. Waking someone at 09:00 for an alarm they set for
     * 07:00 is worse than not waking them at all, and the backend still holds the record.
     */
    @Synchronized
    fun reschedule(ctx: Context, nowEpochS: Long = System.currentTimeMillis() / 1000) {
        val all = held(ctx)
        if (all.isEmpty()) return

        val keep = ArrayList<Armed>(all.size)
        var dropped = 0
        var rolled = 0
        var late = 0
        for (a in all) {
            val missedBy = nowEpochS - a.fireAtEpochS
            val due = when {
                missedBy < 0 -> a
                missedBy <= LATE_GRACE_S -> { late++; a.copy(fireAtEpochS = nowEpochS + 1) }
                repeats(a.recurrence) ->
                    nextOccurrence(a.scheduledEpochS, a.todSec, a.recurrence, nowEpochS)
                        ?.let { rolled++; a.copy(fireAtEpochS = it, scheduledEpochS = it) }
                else -> null
            }
            if (due == null) {
                dropped++
                continue
            }
            keep += due
            DeviceCommands.rearm(ctx, due)
        }
        if (dropped > 0 || rolled > 0 || late > 0) save(ctx, keep)
        Log.i(TAG, "boot: re-armed ${keep.size} ($late rung late, $rolled rolled forward), dropped $dropped")
    }

    // ---- recurrence ----
    //
    // A closed enumeration agreed with the backend, which refuses anything it cannot map rather
    // than passing a phrase through: "daily", "weekdays", "weekends", "weekly:<dow>", or "" for a
    // one-shot. The device must keep the same promise. An unrecognised rule is treated as a
    // one-shot and logged, never guessed at — guessing here changes when someone's alarm goes off.
    //
    // Until this existed the backend listed a recurring alarm forever, because its recurrence
    // string was non-empty, while the handset had already forgotten it after the first ring.

    private const val WEEKLY_PREFIX = "weekly:"

    private val DOW = mapOf(
        "mon" to java.time.DayOfWeek.MONDAY,
        "tue" to java.time.DayOfWeek.TUESDAY,
        "wed" to java.time.DayOfWeek.WEDNESDAY,
        "thu" to java.time.DayOfWeek.THURSDAY,
        "fri" to java.time.DayOfWeek.FRIDAY,
        "sat" to java.time.DayOfWeek.SATURDAY,
        "sun" to java.time.DayOfWeek.SUNDAY,
    )

    fun repeats(recurrence: String): Boolean = recurrence.trim().isNotEmpty()

    /** Seconds past local midnight of [epochS] in [zone]. */
    internal fun timeOfDay(epochS: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Int =
        java.time.Instant.ofEpochSecond(epochS).atZone(zone).toLocalTime().toSecondOfDay()

    /** Convenience for callers that hold only an instant; derives the time of day from it. */
    fun nextOccurrence(
        fireAtEpochS: Long,
        recurrence: String,
        afterEpochS: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Long? = nextOccurrence(fireAtEpochS, timeOfDay(fireAtEpochS, zone), recurrence, afterEpochS, zone)

    /**
     * The next time [recurrence] comes round after [afterEpochS], at [todSec] past midnight,
     * stepping days from the date of [anchorEpochS]. Null for a one-shot or an unknown rule.
     *
     * Each candidate is built fresh from a date and a wall-clock time rather than by advancing
     * one cursor. A cursor that lands on a daylight-saving gap is shifted an hour — 02:30
     * becomes 03:30 — and then carries that shift into every day after it. Rebuilding from the
     * stored time of day means the gap day alone is shifted, which is what a clock does.
     */
    fun nextOccurrence(
        anchorEpochS: Long,
        todSec: Int,
        recurrence: String,
        afterEpochS: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Long? {
        val rule = recurrence.trim().lowercase()
        if (rule.isEmpty()) return null
        if (!recognised(rule)) {
            Log.w(TAG, "unrecognised recurrence '$recurrence'; treating the alarm as one-shot")
            return null
        }
        val tod = java.time.LocalTime.ofSecondOfDay(todSec.toLong().coerceIn(0L, 86_399L))
        var date = java.time.Instant.ofEpochSecond(anchorEpochS).atZone(zone).toLocalDate()
        // A fortnight of days is more than enough to reach any member of the enumeration, even
        // starting from an alarm whose moment passed while the phone was switched off.
        repeat(400) {
            date = date.plusDays(1)
            val at = date.atTime(tod).atZone(zone).toEpochSecond()
            if (at > afterEpochS && matches(date.dayOfWeek, rule)) return at
        }
        return null
    }

    private fun recognised(rule: String): Boolean = when {
        rule == "daily" || rule == "weekdays" || rule == "weekends" -> true
        rule.startsWith(WEEKLY_PREFIX) -> rule.removePrefix(WEEKLY_PREFIX) in DOW
        else -> false
    }

    private fun matches(day: java.time.DayOfWeek, rule: String): Boolean = when {
        rule == "daily" -> true
        rule == "weekdays" -> day != java.time.DayOfWeek.SATURDAY && day != java.time.DayOfWeek.SUNDAY
        rule == "weekends" -> day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY
        rule.startsWith(WEEKLY_PREFIX) -> DOW[rule.removePrefix(WEEKLY_PREFIX)] == day
        else -> false
    }

    /**
     * Called when an alarm has just gone off. A repeating alarm is moved to its next occurrence
     * and armed again; a one-shot is forgotten.
     */
    @Synchronized
    fun onFired(ctx: Context, id: String, nowEpochS: Long = System.currentTimeMillis() / 1000) {
        val alarm = held(ctx).firstOrNull { it.id == id } ?: return
        if (!repeats(alarm.recurrence)) {
            forget(ctx, id)
            return
        }
        // A snooze ring returns to the schedule; a scheduled ring moves the schedule on. The
        // two are told apart by whether the ring time IS the schedule time — not by the clock,
        // because AlarmManager may deliver a scheduled ring a moment early and a comparison
        // against now would then mistake it for a snooze and never advance.
        val snoozeRing = alarm.fireAtEpochS != alarm.scheduledEpochS
        val next = if (snoozeRing && alarm.scheduledEpochS > nowEpochS) alarm.scheduledEpochS
        else nextOccurrence(
            alarm.scheduledEpochS, alarm.todSec, alarm.recurrence,
            afterEpochS = maxOf(nowEpochS, alarm.scheduledEpochS),
        )
        if (next == null) {
            forget(ctx, id)
            return
        }
        val moved = alarm.copy(fireAtEpochS = next, scheduledEpochS = next)
        remember(ctx, moved)
        DeviceCommands.rearm(ctx, moved)
        Log.i(TAG, "recurring alarm id='$id' (${alarm.recurrence}) moved to $next")
    }

    private fun save(ctx: Context, alarms: List<Armed>) {
        val arr = JSONArray()
        alarms.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("at", it.fireAtEpochS)
                    .put("label", it.label)
                    .put("sound", it.sound)
                    .put("vibrate", it.vibrate)
                    .put("recurrence", it.recurrence)
                    .put("sched", it.scheduledEpochS)
                    .put("tod", it.todSec)
            )
        }
        store.write(ctx, arr.toString())
    }
}
