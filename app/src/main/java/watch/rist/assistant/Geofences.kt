package watch.rist.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class GeofenceSide { INSIDE, OUTSIDE }

data class Geofence(
    val id: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val direction: String,
    val dwellS: Int,
    val pollS: Int,
    val expiresEpochS: Long,
    val requireExitFirst: Boolean,
    val label: String,
    val side: GeofenceSide?,
    val pendingSide: GeofenceSide?,
    // Unix ms; becomes the crossing's at_ms.
    val pendingSinceMs: Long,
)

data class GeofenceCrossing(
    val id: String,
    val fenceId: String,
    val direction: String,
    // Unix ms on the device wall clock, the same clock as DeviceRequest.timestamp.
    val atMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Int,
    // Acquisition time of the fix, for Location.timestamp.
    val fixTimeMs: Long,
)

data class GeofenceStep(
    val fence: Geofence,
    val crossing: GeofenceCrossing?,
    val rejected: String,
)

object Geofences {

    private const val TAG = "RistGeofence"

    // Advertised in caps.max_geofences. Never 0.
    const val MAX_FENCES = 16

    internal const val RADIUS_FLOOR_M = 100

    // Applied when the backend sends 0 (protobuf cannot distinguish unset).
    internal const val DEFAULT_RADIUS_M = 150
    internal const val DEFAULT_DWELL_S = 60
    internal const val DEFAULT_POLL_S = 120

    internal const val HYSTERESIS_FACTOR = 1.3
    internal const val HYSTERESIS_ADD_M = 50.0

    // Backend acks but does not run crossings older than this.
    internal const val STALE_AFTER_MS = 30L * 60_000L

    internal const val MAX_QUEUED = 200

    // poll_s may be lengthened, never shortened.
    internal const val MIN_POLL_S = 60

    internal const val HARD_CEIL_MS = 30L * 60_000L

    internal const val STILL_FLOOR_MS = 5L * 60_000L

    internal const val CHARGING_STILL_FLOOR_MS = 30L * 60_000L

    internal const val STATIONARY_M = 40.0

    internal const val SPEED_MOVING_MPS = 30.0

    internal const val SPEED_STILL_MPS = 1.4

    private const val EARTH_RADIUS_M = 6_371_000.0

    internal fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    internal fun enterRadiusM(f: Geofence): Double = f.radiusM.toDouble()

    internal fun exitRadiusM(f: Geofence): Double =
        max(f.radiusM * HYSTERESIS_FACTOR, f.radiusM + HYSTERESIS_ADD_M)

    internal fun arming(
        id: String,
        lat: Double,
        lon: Double,
        radiusM: Int,
        direction: String,
        dwellS: Int,
        pollS: Int,
        expiresEpochS: Long,
        requireExitFirst: Boolean,
        label: String,
        nowLat: Double? = null,
        nowLon: Double? = null,
        nowAccuracyM: Float = 0f,
    ): Geofence {
        val base = Geofence(
            id = id,
            lat = lat,
            lon = lon,
            radiusM = (if (radiusM <= 0) DEFAULT_RADIUS_M else radiusM).coerceAtLeast(RADIUS_FLOOR_M),
            direction = direction.trim().lowercase().ifBlank { "enter" },
            dwellS = if (dwellS <= 0) DEFAULT_DWELL_S else dwellS,
            pollS = if (pollS <= 0) DEFAULT_POLL_S else pollS,
            expiresEpochS = expiresEpochS,
            requireExitFirst = requireExitFirst,
            label = label,
            side = null,
            pendingSide = null,
            pendingSinceMs = 0L,
        )
        if (requireExitFirst) return base.copy(side = GeofenceSide.INSIDE)
        if (nowLat == null || nowLon == null || !accuracyUsable(nowAccuracyM, base.radiusM)) return base
        return base.copy(side = observedSide(base, distanceM(nowLat, nowLon, lat, lon)))
    }

    // accuracy 0 means unknown, not perfect.
    internal fun accuracyUsable(accuracyM: Float, radiusM: Int): Boolean =
        accuracyM > 0f && accuracyM <= radiusM.toFloat()

    internal fun observedSide(f: Geofence, d: Double): GeofenceSide = when (f.side) {
        GeofenceSide.INSIDE -> if (d > exitRadiusM(f)) GeofenceSide.OUTSIDE else GeofenceSide.INSIDE
        GeofenceSide.OUTSIDE -> if (d <= enterRadiusM(f)) GeofenceSide.INSIDE else GeofenceSide.OUTSIDE
        null ->
            if (f.direction == "exit") {
                if (d <= enterRadiusM(f)) GeofenceSide.INSIDE else GeofenceSide.OUTSIDE
            } else {
                if (d <= exitRadiusM(f)) GeofenceSide.INSIDE else GeofenceSide.OUTSIDE
            }
    }

    internal fun step(f: Geofence, lat: Double, lon: Double, accuracyM: Float, nowMs: Long): GeofenceStep {
        if (!accuracyUsable(accuracyM, f.radiusM)) {
            return GeofenceStep(f, null, "fix ±${accuracyM.roundToInt()}m is not evidence for a ${f.radiusM}m fence")
        }
        val d = distanceM(lat, lon, f.lat, f.lon)
        val observed = observedSide(f, d)

        if (f.side == null) {
            return GeofenceStep(f.copy(side = observed, pendingSide = null, pendingSinceMs = 0L), null, "")
        }
        if (observed == f.side) {
            val cleared = if (f.pendingSide == null) f else f.copy(pendingSide = null, pendingSinceMs = 0L)
            return GeofenceStep(cleared, null, "")
        }
        if (f.pendingSide != observed) {
            return GeofenceStep(f.copy(pendingSide = observed, pendingSinceMs = nowMs), null, "")
        }
        if (nowMs - f.pendingSinceMs < f.dwellS * 1000L) return GeofenceStep(f, null, "")

        val committed = f.copy(side = observed, pendingSide = null, pendingSinceMs = 0L)
        val happened = if (observed == GeofenceSide.INSIDE) "enter" else "exit"
        if (happened != f.direction) {
            return GeofenceStep(committed, null, "")
        }
        val crossing = GeofenceCrossing(
            id = crossingId(f.id, f.pendingSinceMs),
            fenceId = f.id,
            direction = happened,
            atMs = f.pendingSinceMs,
            lat = lat,
            lon = lon,
            accuracyM = accuracyM.roundToInt().coerceAtLeast(0),
            fixTimeMs = nowMs,
        )
        return GeofenceStep(committed, crossing, "")
    }

    internal fun crossingId(fenceId: String, atMs: Long): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$fenceId|$atMs".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(32)

    internal fun pruneExpired(fences: List<Geofence>, nowMs: Long): List<Geofence> =
        fences.filter { it.expiresEpochS <= 0L || it.expiresEpochS * 1000L > nowMs }

    internal fun mergeArm(existing: List<Geofence>, incoming: List<Geofence>): List<Geofence> {
        val byId = existing.associateBy { it.id }
        return incoming.map { fresh ->
            val old = byId[fresh.id] ?: return@map fresh
            val sameGeometry = old.lat == fresh.lat && old.lon == fresh.lon &&
                old.radiusM == fresh.radiusM && old.direction == fresh.direction &&
                old.dwellS == fresh.dwellS && old.requireExitFirst == fresh.requireExitFirst
            if (!sameGeometry) fresh
            else fresh.copy(side = old.side, pendingSide = old.pendingSide, pendingSinceMs = old.pendingSinceMs)
        }
    }

    // Returns ms; 0 means nothing to watch, cancel the wake.
    internal fun nextPollDelayMs(
        fences: List<Geofence>,
        lat: Double?,
        lon: Double?,
        charging: Boolean,
        stationary: Boolean,
    ): Long {
        if (fences.isEmpty()) return 0L
        val floorMs = max(MIN_POLL_S, fences.minOf { it.pollS }).toLong() * 1000L
        if (lat == null || lon == null) return floorMs

        val marginM = fences.minOf { f ->
            val d = distanceM(lat, lon, f.lat, f.lon)
            when (f.side) {
                GeofenceSide.INSIDE -> max(0.0, exitRadiusM(f) - d)
                else -> max(0.0, d - enterRadiusM(f))
            }
        }
        val speed = if (stationary) SPEED_STILL_MPS else SPEED_MOVING_MPS
        var delayMs = max(floorMs, (marginM / speed * 1000.0).toLong())
        if (stationary) delayMs = max(delayMs, if (charging) CHARGING_STILL_FLOOR_MS else STILL_FLOOR_MS)
        return min(delayMs, max(floorMs, HARD_CEIL_MS))
    }

    internal fun stationary(prevLat: Double?, prevLon: Double?, lat: Double, lon: Double): Boolean =
        prevLat != null && prevLon != null && distanceM(prevLat, prevLon, lat, lon) <= STATIONARY_M

    internal fun ageMs(c: GeofenceCrossing, requestTimestampMs: Long): Long = requestTimestampMs - c.atMs

    internal fun isStale(c: GeofenceCrossing, requestTimestampMs: Long): Boolean =
        ageMs(c, requestTimestampMs) > STALE_AFTER_MS

    internal fun trimQueue(queue: List<GeofenceCrossing>, nowMs: Long, cap: Int = MAX_QUEUED): List<GeofenceCrossing> {
        if (queue.size <= cap) return queue
        val sorted = queue.sortedBy { it.atMs }
        val overBy = queue.size - cap
        val evictable = sorted.filter { isStale(it, nowMs) }.take(overBy).map { it.id }.toSet()
        val kept = queue.filterNot { it.id in evictable }
        return if (kept.size <= cap) kept else kept.sortedBy { it.atMs }.drop(kept.size - cap)
    }

    private fun loadFences(ctx: Context): List<Geofence> {
        val out = mutableListOf<Geofence>()
        runCatching {
            val arr = JSONArray(Config.geofences(ctx).ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Geofence(
                        id = o.getString("id"),
                        lat = o.getDouble("lat"),
                        lon = o.getDouble("lon"),
                        radiusM = o.optInt("r"),
                        direction = o.optString("dir"),
                        dwellS = o.optInt("dwell"),
                        pollS = o.optInt("poll"),
                        expiresEpochS = o.optLong("exp"),
                        requireExitFirst = o.optBoolean("exit_first"),
                        label = o.optString("label"),
                        side = o.optString("side").toSideOrNull(),
                        pendingSide = o.optString("pend").toSideOrNull(),
                        pendingSinceMs = o.optLong("pend_at"),
                    )
                )
            }
        }.onFailure { Log.w(TAG, "fence store unreadable; holding none", it) }
        return out
    }

    private fun String?.toSideOrNull(): GeofenceSide? = when (this) {
        "in" -> GeofenceSide.INSIDE
        "out" -> GeofenceSide.OUTSIDE
        else -> null
    }

    private fun GeofenceSide?.wire(): String = when (this) {
        GeofenceSide.INSIDE -> "in"
        GeofenceSide.OUTSIDE -> "out"
        null -> ""
    }

    private fun saveFences(ctx: Context, list: List<Geofence>) = runCatching {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("lat", it.lat); put("lon", it.lon)
                put("r", it.radiusM); put("dir", it.direction); put("dwell", it.dwellS)
                put("poll", it.pollS); put("exp", it.expiresEpochS)
                put("exit_first", it.requireExitFirst); put("label", it.label)
                put("side", it.side.wire()); put("pend", it.pendingSide.wire())
                put("pend_at", it.pendingSinceMs)
            })
        }
        Config.setGeofences(ctx, arr.toString())
    }.onFailure { Log.w(TAG, "fence store not saved", it) }.let { }

    private fun loadQueue(ctx: Context): List<GeofenceCrossing> {
        val out = mutableListOf<GeofenceCrossing>()
        runCatching {
            val arr = JSONArray(Config.geofenceQueue(ctx).ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    GeofenceCrossing(
                        id = o.getString("id"),
                        fenceId = o.optString("fence"),
                        direction = o.optString("dir"),
                        atMs = o.optLong("at"),
                        lat = o.optDouble("lat", 0.0),
                        lon = o.optDouble("lon", 0.0),
                        accuracyM = o.optInt("acc"),
                        fixTimeMs = o.optLong("fix_at"),
                    )
                )
            }
        }.onFailure { Log.w(TAG, "crossing queue unreadable; starting empty", it) }
        return out
    }

    private fun saveQueue(ctx: Context, list: List<GeofenceCrossing>) = runCatching {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("fence", it.fenceId); put("dir", it.direction)
                put("at", it.atMs); put("lat", it.lat); put("lon", it.lon)
                put("acc", it.accuracyM); put("fix_at", it.fixTimeMs)
            })
        }
        Config.setGeofenceQueue(ctx, arr.toString())
    }.onFailure { Log.w(TAG, "crossing queue not saved", it) }.let { }

    fun held(ctx: Context): List<Geofence> {
        val now = System.currentTimeMillis()
        val all = loadFences(ctx)
        val keep = pruneExpired(all, now)
        if (keep.size != all.size) {
            saveFences(ctx, keep)
            Log.i(TAG, "expiry dropped ${all.size - keep.size} fence(s); ${keep.size} still armed")
        }
        return keep
    }

    // Sent on every request, including when empty; an empty list triggers a re-arm.
    fun heldIds(ctx: Context): List<String> = held(ctx).map { it.id }

    // Call only when DeviceResponse.geofences was present; an empty list here cancels every fence.
    fun arm(ctx: Context, incoming: List<Geofence>) {
        val now = System.currentTimeMillis()
        GeofenceConsent.noteFenceRequested(ctx)
        val all = pruneExpired(mergeArm(held(ctx), incoming), now)
        val merged = all.take(MAX_FENCES)
        if (all.size > merged.size) {
            Log.w(TAG, "backend armed ${all.size} fences but caps.max_geofences advertises " +
                "$MAX_FENCES; ${all.size - merged.size} DROPPED and will never fire")
        }
        saveFences(ctx, merged)
        // Ids and labels only; never log a fence's centre.
        Log.i(TAG, "armed ${merged.size} fence(s): ${merged.joinToString { "${it.id}(${it.label})" }}")
    }

    fun dropAll(ctx: Context, why: String) {
        if (loadFences(ctx).isEmpty()) return
        saveFences(ctx, emptyList())
        Log.i(TAG, "dropped every fence: $why")
    }

    fun pendingCrossings(ctx: Context): List<GeofenceCrossing> = loadQueue(ctx).sortedBy { it.atMs }

    fun ackCrossings(ctx: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val keep = loadQueue(ctx).filterNot { it.id in ids }
        saveQueue(ctx, keep)
        Log.i(TAG, "acked ${ids.size} crossing(s); ${keep.size} still owed")
    }

    fun evaluate(ctx: Context, lat: Double, lon: Double, accuracyM: Float, nowMs: Long): List<GeofenceCrossing> {
        val fences = held(ctx)
        if (fences.isEmpty()) return emptyList()

        val updated = ArrayList<Geofence>(fences.size)
        val crossings = ArrayList<GeofenceCrossing>()
        for (f in fences) {
            val s = step(f, lat, lon, accuracyM, nowMs)
            updated.add(s.fence)
            if (s.rejected.isNotEmpty()) Log.i(TAG, "fence ${f.id}: ${s.rejected}")
            s.crossing?.let { crossings.add(it) }
        }
        saveFences(ctx, updated)

        if (crossings.isNotEmpty()) {
            val existing = loadQueue(ctx)
            val known = existing.map { it.id }.toSet()
            val merged = existing + crossings.filterNot { it.id in known }
            saveQueue(ctx, trimQueue(merged, nowMs))
            crossings.forEach { Log.i(TAG, "crossing ${it.direction} on fence ${it.fenceId} at ${it.atMs}") }
        }
        return crossings
    }

    fun rememberFix(ctx: Context, lat: Double, lon: Double) = Config.setGeofenceLastFix(ctx, "$lat,$lon")

    fun lastFix(ctx: Context): Pair<Double, Double>? {
        val parts = Config.geofenceLastFix(ctx).split(',')
        if (parts.size != 2) return null
        val la = parts[0].toDoubleOrNull() ?: return null
        val lo = parts[1].toDoubleOrNull() ?: return null
        return la to lo
    }
}
