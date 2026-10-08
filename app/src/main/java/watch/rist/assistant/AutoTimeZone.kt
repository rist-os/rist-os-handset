package watch.rist.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import java.util.TimeZone

/**
 * Keeps the phone's time zone where the phone is, from its own location fix.
 *
 * The OS's automatic zone comes from the cell network's time signal (NITZ). Carriers often do
 * not send it, roaming networks frequently do not, and without it the OS falls back to the
 * country, which only works for a country with one zone. Mexico has several, so a phone arriving
 * there kept its home zone. A location fix does not depend on any of that, and it works with no
 * signal at all, because the lookup ([TimeZoneIndex]) is on the phone.
 *
 * RIST sets the zone as device owner. The OS will not accept a zone from an app while its own
 * automatic detection is on, so turning this on turns that off, and turning this off hands the
 * clock back to it. Where RIST is not device owner nothing is changed and Settings says so.
 */
object AutoTimeZone {

    private const val TAG = "RistAutoTz"

    /** A fix older than this may be from before a flight; it says nothing about where we are now. */
    internal const val MAX_FIX_AGE_MS = 2L * 60 * 60 * 1000

    /** Coarser than this cannot tell neighbouring zones apart. 0 means the provider did not say. */
    internal const val MAX_ACCURACY_M = 20_000f

    /** A pending same-offset zone is forgotten after this, so two sightings must be near in time. */
    internal const val PENDING_TTL_MS = 6L * 60 * 60 * 1000

    /** How stale the cached fix may be before the hourly check asks for a new one. */
    private const val REFRESH_AFTER_MS = 30L * 60 * 1000

    internal const val ACTION_CHECK = "watch.rist.assistant.action.TIME_ZONE_CHECK"

    private const val MIN_CHECK_INTERVAL_MS = 10L * 60 * 1000

    /** The parts that touch the OS, replaceable in tests. */
    internal interface SystemZone {
        fun current(): String
        fun canSet(ctx: Context): Boolean
        /** Turns the OS's own detection off if needed, then sets [zone]. True when it took. */
        fun set(ctx: Context, zone: String): Boolean
        fun handBack(ctx: Context)
    }

    private object DeviceOwnerZone : SystemZone {
        override fun current(): String = TimeZone.getDefault().id

        override fun canSet(ctx: Context): Boolean = KioskManager.isDeviceOwner(ctx)

        override fun set(ctx: Context, zone: String): Boolean = runCatching {
            val dpm = KioskManager.dpm(ctx)
            val admin = KioskManager.admin(ctx)
            @Suppress("DEPRECATION")
            if (dpm.getAutoTimeZoneEnabled(admin)) dpm.setAutoTimeZoneEnabled(admin, false)
            dpm.setTimeZone(admin, zone)
        }.onFailure { Log.w(TAG, "could not set the time zone to $zone", it) }.getOrDefault(false)

        override fun handBack(ctx: Context) {
            runCatching {
                @Suppress("DEPRECATION")
                KioskManager.dpm(ctx).setAutoTimeZoneEnabled(KioskManager.admin(ctx), true)
            }.onFailure { Log.w(TAG, "could not hand the time zone back to the phone", it) }
        }
    }

    internal var system: SystemZone = DeviceOwnerZone

    sealed class Decision {
        object Keep : Decision()
        data class Wait(val zone: String) : Decision()
        data class Switch(val zone: String) : Decision()
    }

    /**
     * What to do about finding [found] while the phone is set to [current].
     *
     * A zone with a different offset right now is switched to at once: the clock is visibly
     * wrong. A zone with the SAME offset (El Paso and Ciudad Juarez, say, a river apart) is only
     * switched to when seen twice, so standing on a border does not flip the zone back and forth
     * with every fix while the clock shows the same time either way.
     */
    internal fun decide(
        current: String,
        found: String?,
        pending: Pair<String, Long>?,
        nowMs: Long,
    ): Decision {
        if (found == null || found == current || !settable(found)) return Decision.Keep
        val now = TimeZone.getTimeZone(found).getOffset(nowMs)
        val was = TimeZone.getTimeZone(current).getOffset(nowMs)
        if (now != was) return Decision.Switch(found)
        val seenBefore = pending != null && pending.first == found && nowMs - pending.second <= PENDING_TTL_MS
        return if (seenBefore) Decision.Switch(found) else Decision.Wait(found)
    }

    /**
     * Ocean zones are skipped: a fix at sea is rare, usually a bad fix, and a phone on a ship is
     * better left on the zone it had than set to "GMT-7". An id this phone's time zone data does
     * not know is skipped rather than set, which would fail anyway.
     */
    internal fun settable(zone: String): Boolean =
        !zone.startsWith("Etc/") && zone in TimeZone.getAvailableIDs()

    /** Acts on one fix. Cheap after the first call in a process; safe to call on any thread but main. */
    @Synchronized
    fun consider(ctx: Context, fix: LocationProvider.Fix?, nowMs: Long = System.currentTimeMillis()) {
        // Debug level: this runs on every request, and the reasons matter only when it misbehaves.
        if (fix == null) { Log.d(TAG, "skip: no fix"); return }
        if (LocationSwitch.isOff(ctx)) { Log.d(TAG, "skip: the account's location switch is off"); return }
        if (!Config.isAutoTimeZone(ctx)) { Log.d(TAG, "skip: set to the phone's own setting"); return }
        if (nowMs - fix.timeMs > MAX_FIX_AGE_MS) { Log.d(TAG, "skip: fix is ${(nowMs - fix.timeMs) / 60_000} min old"); return }
        if (fix.accuracyM > MAX_ACCURACY_M) { Log.d(TAG, "skip: fix accuracy ${fix.accuracyM} m"); return }
        if (!system.canSet(ctx)) { Log.d(TAG, "skip: not device owner"); return }

        val found = TimeZoneIndex.zoneAt(ctx, fix.lat, fix.lon)
        val current = system.current()
        when (val d = decide(current, found, Config.autoTimeZonePending(ctx), nowMs)) {
            Decision.Keep -> {
                Log.d(TAG, "keep $current (location says ${found ?: "nothing"})")
                if (found == current) Config.setAutoTimeZonePending(ctx, null, 0L)
            }
            is Decision.Wait -> {
                Config.setAutoTimeZonePending(ctx, d.zone, nowMs)
                Log.i(TAG, "saw ${d.zone} (same offset as $current); switching if seen again")
            }
            is Decision.Switch -> {
                val ok = system.set(ctx, d.zone)
                Config.setAutoTimeZonePending(ctx, null, 0L)
                Log.i(TAG, "time zone $current -> ${d.zone}: ${if (ok) "set" else "REFUSED by the OS"}")
            }
        }
    }

    /** Settings: on takes the clock from location now; off gives it back to the phone. */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        Config.setAutoTimeZone(ctx, enabled)
        Config.setAutoTimeZonePending(ctx, null, 0L)
        if (enabled) checkInBackground(ctx, force = true) else if (system.canSet(ctx)) system.handBack(ctx)
    }

    fun canSet(ctx: Context): Boolean = system.canSet(ctx)

    /** "Mexico City · GMT-06:00": the zone's place name and its offset at [nowMs]. */
    internal fun describeZone(tz: TimeZone, nowMs: Long): String {
        val place = tz.id.substringAfterLast('/').replace('_', ' ')
        val offsetMin = tz.getOffset(nowMs) / 60_000
        val sign = if (offsetMin < 0) "-" else "+"
        val abs = kotlin.math.abs(offsetMin)
        return "%s · GMT%s%02d:%02d".format(java.util.Locale.US, place, sign, abs / 60, abs % 60)
    }

    /**
     * Least time between two GPS attempts made for the time zone alone. Between them the check
     * uses the cached fix (from any app, a turn, navigation) and the network provider when the
     * owner turned network location on. A zone is a region; it does not need a satellite fix
     * every hour, and an hourly cold GPS start indoors runs the receiver for ten seconds to learn
     * nothing.
     */
    internal const val GPS_MIN_GAP_MS = 6L * 60 * 60 * 1000

    @Volatile private var lastGpsAttemptMs = 0L

    /** Whether this check may wake GPS: forced checks (boot, the setting turned on) always may. */
    internal fun gpsAllowed(force: Boolean, lastGpsAttemptMs: Long, nowElapsedMs: Long): Boolean =
        force || lastGpsAttemptMs == 0L || nowElapsedMs - lastGpsAttemptMs >= GPS_MIN_GAP_MS

    /**
     * Looks up the zone with the freshest fix it can get cheaply. May block for a location
     * request of up to about fifteen seconds, so never on the main thread.
     */
    fun checkBlocking(ctx: Context, force: Boolean = false) {
        Log.i(TAG, "check: from location=${Config.isAutoTimeZone(ctx)} owner=${system.canSet(ctx)} " +
            "location permission=${LocationProvider.hasPermission(ctx)} zone=${system.current()}")
        if (!Config.isAutoTimeZone(ctx) || !system.canSet(ctx)) return
        if (!LocationProvider.hasPermission(ctx)) return
        // The zone stays as it is while the account's location switch is off.
        if (LocationSwitch.isOff(ctx)) return
        val now = System.currentTimeMillis()
        var fix = LocationProvider.cached(ctx)
        if (fix == null || now - fix.timeMs > REFRESH_AFTER_MS) {
            val elapsed = SystemClock.elapsedRealtime()
            val gps = gpsAllowed(force, lastGpsAttemptMs, elapsed)
            // Counted only when GPS is really asked: a network fix that answered first must not
            // use up the allowance a later check (abroad, no data for network location) needs.
            fix = LocationProvider.freshBlocking(ctx, maxAgeS = (REFRESH_AFTER_MS / 1000).toInt(),
                minAccuracyM = MAX_ACCURACY_M, timeoutMs = 5_000, allowGps = gps,
                onGps = { lastGpsAttemptMs = elapsed }) ?: fix
        }
        consider(ctx, fix, now)
    }

    /**
     * The same travel sign again within this long is not acted on, so a flapping signal at a
     * border cannot hold GPS on. A different sign (landing, then a new country) always is.
     */
    internal const val TRAVEL_REPEAT_GAP_MS = 10L * 60 * 1000

    private val lastTravelSign = HashMap<String, Long>()

    /**
     * What a broadcast says about a possible change of place, as a key, or null when it says
     * nothing: airplane mode turned off (landed), or the network country changed to a known one
     * (crossed a border, or came back into service abroad). Losing service reports a blank
     * country; that says nothing. Two signs with the same key are repeats of one another.
     */
    internal fun travelSign(action: String?, airplaneOn: Boolean?, country: String?): String? = when (action) {
        Intent.ACTION_AIRPLANE_MODE_CHANGED -> if (airplaneOn == false) "airplane-off" else null
        TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED ->
            country?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "country:$it" }
        else -> null
    }

    /** The first sign with this key, or the same one [TRAVEL_REPEAT_GAP_MS] after it was last acted on. */
    internal fun travelSignDue(lastMs: Long?, nowElapsedMs: Long): Boolean =
        lastMs == null || nowElapsedMs - lastMs >= TRAVEL_REPEAT_GAP_MS

    /**
     * The zone a network country alone decides: every place in it keeps the same time now, so
     * the country is enough to put the clock right before any fix. Null for a country whose
     * zones differ (Mexico, the US), which only a location can settle. Android gives apps the
     * network country but not the carrier's own zone (NITZ), so this is the nearest to it.
     */
    internal fun zoneForCountry(country: String, nowMs: Long): String? {
        val ids = runCatching {
            android.icu.util.TimeZone.getAvailableIDs(
                android.icu.util.TimeZone.SystemTimeZoneType.CANONICAL_LOCATION,
                country.trim().uppercase(java.util.Locale.ROOT), null,
            ).toList()
        }.getOrDefault(emptyList()).filter { settable(it) }
        if (ids.isEmpty()) return null
        val offsets = ids.map { TimeZone.getTimeZone(it).getOffset(nowMs) }.toSet()
        return if (offsets.size == 1) ids.first() else null
    }

    /**
     * A travel sign: puts the clock right from the network country when that alone decides the
     * zone, then starts a full check at once (GPS allowed, inside the six-hour allowance). The
     * hourly check alone could leave the clock, and alarms set by it, on the old zone for hours
     * after a flight that never switched the phone off. Returns whether anything was started.
     */
    fun onTravelSign(ctx: Context, intent: Intent): Boolean {
        val airplaneOn = if (intent.hasExtra("state")) intent.getBooleanExtra("state", false) else null
        val country = intent.getStringExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY)
        val key = travelSign(intent.action, airplaneOn, country) ?: return false
        val now = SystemClock.elapsedRealtime()
        synchronized(lastTravelSign) {
            val last = lastTravelSign[key]
            if (!travelSignDue(last, now)) {
                Log.d(TAG, "travel sign $key ignored: the same one came ${(now - (last ?: now)) / 1000}s ago")
                return false
            }
            lastTravelSign[key] = now
        }
        Log.i(TAG, "travel sign $key; checking the zone now")
        val app = ctx.applicationContext
        Thread({
            runCatching {
                if (country != null && key.startsWith("country:")) setFromCountry(app, country)
                checkBlocking(app, force = true)
            }.onFailure { Log.w(TAG, "travel check failed", it) }
        }, "RistAutoTzTravel").start()
        return true
    }

    /** Sets the country's zone when it alone decides and the clock shows a different time now. */
    internal fun setFromCountry(ctx: Context, country: String, nowMs: Long = System.currentTimeMillis()) {
        if (!Config.isAutoTimeZone(ctx) || !system.canSet(ctx)) return
        val zone = zoneForCountry(country, nowMs) ?: return
        val current = system.current()
        if (TimeZone.getTimeZone(zone).getOffset(nowMs) == TimeZone.getTimeZone(current).getOffset(nowMs)) return
        val ok = system.set(ctx, zone)
        Config.setAutoTimeZonePending(ctx, null, 0L)
        Log.i(TAG, "time zone $current -> $zone from the network country: ${if (ok) "set" else "REFUSED by the OS"}")
    }

    internal fun resetTravelForTest() { synchronized(lastTravelSign) { lastTravelSign.clear() } }

    @Volatile private var lastBackgroundCheckMs = 0L

    /**
     * [checkBlocking] off the calling thread. The home screen resumes constantly, and each check
     * may wake GPS, so unforced calls within [MIN_CHECK_INTERVAL_MS] of the last are dropped.
     */
    fun checkInBackground(ctx: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastBackgroundCheckMs != 0L && now - lastBackgroundCheckMs < MIN_CHECK_INTERVAL_MS) {
            Log.d(TAG, "check skipped: the last one was ${(now - lastBackgroundCheckMs) / 1000}s ago")
            return
        }
        lastBackgroundCheckMs = now
        val app = ctx.applicationContext
        Thread({ runCatching { checkBlocking(app, force) }.onFailure { Log.w(TAG, "check failed", it) } },
            "RistAutoTz").start()
    }

    /**
     * An hourly, inexact check, so a phone that lands somewhere new corrects itself without
     * waiting for someone to use it. Idempotent: the same PendingIntent replaces itself.
     */
    fun schedule(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, TimeZoneCheckReceiver::class.java).setAction(ACTION_CHECK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_HOUR, pi,
            )
        }.onFailure { Log.w(TAG, "could not schedule the time zone check", it) }
    }
}

class TimeZoneCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutoTimeZone.ACTION_CHECK) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread({
            try {
                AutoTimeZone.checkBlocking(app)
            } catch (t: Throwable) {
                Log.w("RistAutoTz", "hourly check failed", t)
            } finally {
                pending.finish()
            }
        }, "RistAutoTzCheck").start()
    }
}
