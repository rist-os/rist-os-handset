package watch.rist.assistant

import android.location.Location

/**
 * Decides when being off the route is real enough to ask for a new one.
 *
 * GPS indoors wanders tens of metres with an accuracy to match, and a phone sitting still can
 * drift off the route line that way. A fix only counts when it is fresh, its accuracy is good and
 * smaller than its distance from the route; a reroute needs [STREAK] such fixes in a row and the
 * phone actually moving. A fix back on the route starts the count again.
 */
internal class RerouteGate {

    private var streak = 0
    private var first: LocationProvider.Fix? = null

    fun reset() {
        streak = 0
        first = null
    }

    /**
     * [offRouteM]: the fix's distance from the route; [limitM]: how far off still counts as on it.
     * True when a reroute should go now (the count then starts again).
     */
    fun onFix(fix: LocationProvider.Fix, offRouteM: Double, limitM: Double, nowMs: Long): Boolean {
        if (offRouteM <= limitM) { reset(); return false }
        // A fix that cannot tell where it is neither counts nor clears the count.
        if (!trustworthy(fix, offRouteM, nowMs)) return false
        if (first == null) first = fix
        streak++
        if (streak < STREAK || !moving(first!!, fix)) return false
        reset()
        return true
    }

    companion object {
        /** Consecutive good off-route fixes before a reroute (about six seconds at nav GPS rate). */
        internal const val STREAK = 4
        /** Worse than this, a fix says nothing about which street the phone is on. */
        internal const val MAX_ACCURACY_M = 25f
        /** Older than this, the fix is a cached one replayed, not where the phone is now. */
        internal const val MAX_AGE_MS = 10_000L
        /** Slower than this and not displaced, the phone is standing still. */
        internal const val MOVING_MPS = 1.0f
        internal const val MOVED_M = 30f

        internal fun trustworthy(fix: LocationProvider.Fix, offRouteM: Double, nowMs: Long): Boolean =
            fix.accuracyM > 0f && fix.accuracyM <= MAX_ACCURACY_M && fix.accuracyM < offRouteM &&
                nowMs - fix.timeMs <= MAX_AGE_MS

        internal fun moving(from: LocationProvider.Fix, to: LocationProvider.Fix): Boolean {
            if ((to.speedMps ?: 0f) >= MOVING_MPS) return true
            val out = FloatArray(1)
            Location.distanceBetween(from.lat, from.lon, to.lat, to.lon, out)
            return out[0] > maxOf(MOVED_M, from.accuracyM + to.accuracyM)
        }
    }
}
