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
        val (live, stale) = all.partition { it.fireAtEpochS > nowEpochS }
        live.forEach { DeviceCommands.rearm(ctx, it) }
        if (stale.isNotEmpty()) save(ctx, live)
        Log.i(TAG, "boot: re-armed ${live.size}, dropped ${stale.size} already past")
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
