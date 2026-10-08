package watch.rist.assistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import rist.v1.DeviceRequest
import rist.v1.Location

/**
 * The account's location switch, set on the website and told to the phone on every turn and every
 * wake (`location_off`, schema v24). While it is off nothing about where the phone is leaves it:
 * no fix on a request (the time zone name still goes), no time zone taken from a fix (the phone's
 * own automatic zone has the clock), no place reminders watched or crossings reported, and no
 * network location lookup made for the assistant.
 *
 * Three states on the wire: present true (off), present false (on again), absent (keep what was
 * last said). The last state is kept across restarts; a phone never told assumes on.
 */
object LocationSwitch {

    private const val TAG = "RistLocationSwitch"
    private const val PREFS = "rist.location.switch"
    private const val KEY_OFF = "location_off"

    /** Local broadcast: the switch just went off, so anything holding the receiver stops now. */
    const val ACTION_LOCATION_OFF = "watch.rist.assistant.LOCATION_SWITCH_OFF"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True while the account's location switch is off. */
    fun isOff(ctx: Context): Boolean =
        runCatching { prefs(ctx).getBoolean(KEY_OFF, false) }.getOrDefault(false)

    /** The state after one message: [present] false means nothing was said, so [current] stays. */
    internal fun next(current: Boolean, present: Boolean, value: Boolean): Boolean =
        if (present) value else current

    /** From a turn's final response. */
    fun onResponse(ctx: Context, present: Boolean, value: Boolean) = take(ctx, present, value, "turn")

    /** From a wake answer. */
    fun onWake(ctx: Context, present: Boolean, value: Boolean) = take(ctx, present, value, "wake")

    private fun take(ctx: Context, present: Boolean, value: Boolean, from: String) {
        val was = isOff(ctx)
        val now = next(was, present, value)
        if (now == was) {
            // Stopped stays stopped: a fence armed by an older path is dropped again.
            if (now) stopWatching(ctx)
            return
        }
        runCatching { prefs(ctx).edit().putBoolean(KEY_OFF, now).commit() }
            .onFailure { Log.w(TAG, "could not store the location switch", it) }
        Log.i(TAG, "location ${if (now) "OFF: sharing nothing" else "ON again: sharing resumes"} (from the $from)")
        if (now) {
            stopWatching(ctx)
            runCatching { AutoTimeZone.onLocationSwitchOff(ctx) }
                .onFailure { Log.w(TAG, "could not hand the time zone back", it) }
            // Navigation GPS runs in the home screen and stops itself only on its next fix,
            // which never comes while the phone stands still: tell it now.
            runCatching {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx.applicationContext)
                    .sendBroadcast(android.content.Intent(ACTION_LOCATION_OFF))
            }
        } else {
            // Fences come back from the backend on the next turn; the zone check can run now.
            runCatching { AutoTimeZone.checkInBackground(ctx, force = true) }
        }
    }

    /** Drops every place reminder and every crossing not yet reported, and cancels the fence wake. */
    private fun stopWatching(ctx: Context) {
        runCatching {
            Geofences.dropAll(ctx, "the account's location switch is off")
            Geofences.dropCrossings(ctx, "the account's location switch is off")
            GeofenceWatcher.reschedule(ctx)
        }.onFailure { Log.w(TAG, "could not stop watching place reminders", it) }
    }

    /**
     * The request as it may leave the phone while location is off: the time zone name only in
     * `location`, and no `geofence_events` (each carries a fix). `geofence_state` is kept.
     */
    internal fun scrub(req: DeviceRequest, timezone: String): DeviceRequest {
        val tz = req.location.timezone.ifBlank { timezone }
        return req.toBuilder()
            .setLocation(Location.newBuilder().setTimezone(tz))
            .clearGeofenceEvents()
            .build()
    }
}
