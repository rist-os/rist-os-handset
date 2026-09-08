package watch.rist.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object LocationProvider {
    @Volatile var debugFix: Fix? = null

    private const val TAG = "RistLocation"

    private val CACHE_PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )

    data class Fix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Float,
        val timeMs: Long,
        val altitudeM: Double?,
        val speedMps: Float?,
        val bearingDeg: Float? = null
    )

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun lm(ctx: Context): LocationManager? =
        ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun toFix(loc: Location): Fix = Fix(
        lat = loc.latitude,
        lon = loc.longitude,
        accuracyM = if (loc.hasAccuracy()) loc.accuracy else 0f,
        timeMs = loc.time,
        altitudeM = if (loc.hasAltitude()) loc.altitude else null,
        speedMps = if (loc.hasSpeed()) loc.speed else null,
        bearingDeg = if (loc.hasBearing()) loc.bearing else null
    )

    fun cached(ctx: Context): Fix? {
        debugFix?.let { return it.copy(timeMs = System.currentTimeMillis()) }
        if (!hasPermission(ctx)) return null
        val manager = lm(ctx) ?: return null
        var best: Location? = null
        try {
            for (provider in CACHE_PROVIDERS) {
                val loc = try {
                    if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
                } catch (e: IllegalArgumentException) {
                    null
                } ?: continue
                if (best == null || loc.time > best.time) best = loc
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "cached(): permission revoked mid-call", e)
            return null
        }
        return best?.let(::toFix)
    }

    private fun meets(fix: Fix, maxAgeS: Int, minAccuracyM: Float, now: Long): Boolean {
        val ageOk = now - fix.timeMs <= maxAgeS.toLong() * 1000L
        val accOk = minAccuracyM == 0f || (fix.accuracyM > 0f && fix.accuracyM <= minAccuracyM)
        return ageOk && accOk
    }

    fun freshBlocking(
        ctx: Context,
        maxAgeS: Int,
        minAccuracyM: Float,
        timeoutMs: Long = 12_000
    ): Fix? {
        if (!hasPermission(ctx)) return null
        val manager = lm(ctx) ?: return cached(ctx)

        var best: Fix? = cached(ctx)
        if (best != null && meets(best, maxAgeS, minAccuracyM, System.currentTimeMillis())) return best

        repeat(2) {
            val got = requestOneShot(manager, LocationManager.GPS_PROVIDER, timeoutMs)
            if (got != null) {
                if (best == null || got.timeMs > best!!.timeMs) best = got
                if (meets(got, maxAgeS, minAccuracyM, System.currentTimeMillis())) return got
            }
        }

        if (networkFallbackPermitted(NetworkLocationConsent.status(ctx))) {
            val got = requestOneShot(manager, LocationManager.NETWORK_PROVIDER, NETWORK_TIMEOUT_MS)
            if (got != null) {
                if (best == null || got.timeMs > best!!.timeMs) best = got
                if (meets(got, maxAgeS, minAccuracyM, System.currentTimeMillis())) return got
            }
        }

        return best
    }

    internal const val NETWORK_TIMEOUT_MS = 4_000L

    internal fun networkFallbackPermitted(status: NetworkLocationConsent.Status): Boolean =
        status == NetworkLocationConsent.Status.ON

    private fun requestOneShot(manager: LocationManager, provider: String, timeoutMs: Long): Fix? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Location?>(null)

        val enabled = try {
            manager.isProviderEnabled(provider)
        } catch (e: IllegalArgumentException) {
            false
        }
        if (!enabled) {
            return null
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val executor = Executors.newSingleThreadExecutor()
                val cancel = CancellationSignal()
                try {
                    manager.getCurrentLocation(
                        provider,
                        cancel,
                        executor
                    ) { loc ->
                        result.set(loc)
                        latch.countDown()
                    }
                    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                        cancel.cancel()
                    }
                } finally {
                    executor.shutdownNow()
                }
            } else {
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        result.set(loc)
                        latch.countDown()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, null)
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    manager.removeUpdates(listener)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "freshBlocking(): permission revoked mid-call", e)
            return null
        } catch (t: Throwable) {
            Log.e(TAG, "freshBlocking(): acquisition failed", t)
            return null
        }
        return result.get()?.let(::toFix)
    }
}
