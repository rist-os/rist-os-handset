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
        val fireAtEpochS: Long,
        val label: String,
        val sound: Boolean,
        val vibrate: Boolean,
        /** Passthrough from the backend ("", "daily", "weekdays"). Stored, not yet acted on. */
        val recurrence: String,
    )

    @Synchronized
    fun held(ctx: Context): List<Armed> = runCatching {
        val arr = JSONArray(store.read(ctx).ifBlank { "[]" })
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) return@mapNotNull null
            Armed(
                id = id,
                fireAtEpochS = o.optLong("at"),
                label = o.optString("label"),
                sound = o.optBoolean("sound", true),
                vibrate = o.optBoolean("vibrate", true),
                recurrence = o.optString("recurrence"),
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
     * Re-arms every alarm still in the future and drops the ones whose moment has passed.
     *
     * An alarm whose time passed while the phone was off does NOT fire late. Waking someone
     * at 09:00 for an alarm they set for 07:00 is worse than not waking them at all, and the
     * backend still holds the record either way.
     */
    @Synchronized
    fun reschedule(ctx: Context, nowEpochS: Long = System.currentTimeMillis() / 1000) {
        val all = held(ctx)
        if (all.isEmpty()) return

        val keep = ArrayList<Armed>(all.size)
        var dropped = 0
        var rolled = 0
        for (a in all) {
            val due = when {
                a.fireAtEpochS > nowEpochS -> a
                // Its moment passed while the phone was off. A repeating alarm moves to its next
                // occurrence; a one-shot is simply gone.
                else -> nextOccurrence(a.fireAtEpochS, a.recurrence, nowEpochS)
                    ?.let { rolled++; a.copy(fireAtEpochS = it) }
            }
            if (due == null) {
                dropped++
                continue
            }
            keep += due
            DeviceCommands.rearm(ctx, due)
        }
        if (dropped > 0 || rolled > 0) save(ctx, keep)
        Log.i(TAG, "boot: re-armed ${keep.size} (rolled $rolled forward), dropped $dropped")
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

    /**
     * The next time [recurrence] comes round after [afterEpochS], keeping the local time of day
     * of [fireAtEpochS]. Null for a one-shot or a rule we do not recognise.
     *
     * Days are added in the phone's own zone rather than by adding 86400 seconds, so a 07:00
     * alarm stays at 07:00 across a daylight-saving change instead of drifting to 06:00 or 08:00.
     */
    fun nextOccurrence(
        fireAtEpochS: Long,
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
        var at = java.time.Instant.ofEpochSecond(fireAtEpochS).atZone(zone)
        // A fortnight of days is more than enough to reach any member of the enumeration, even
        // starting from an alarm whose moment passed while the phone was switched off.
        repeat(400) {
            at = at.plusDays(1)
            if (at.toEpochSecond() > afterEpochS && matches(at.dayOfWeek, rule)) {
                return at.toEpochSecond()
            }
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
        val next = nextOccurrence(alarm.fireAtEpochS, alarm.recurrence, nowEpochS)
        if (next == null) {
            forget(ctx, id)
            return
        }
        val moved = alarm.copy(fireAtEpochS = next)
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
            )
        }
        store.write(ctx, arr.toString())
    }
}
