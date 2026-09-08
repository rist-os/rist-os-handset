package watch.rist.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.max

object GeofenceWatcher {

    private const val TAG = "RistGeofence"

    internal const val ACTION_TICK = "watch.rist.assistant.GEOFENCE_TICK"

    private const val FIX_TIMEOUT_MS = 8_000L

    private const val RETRY_FLOOR_MS = 5L * 60_000L

    @Volatile private var lastReportAttemptMs = 0L

    private fun alarms(ctx: Context): AlarmManager? =
        ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        0,
        Intent(ctx, GeofenceAlarmReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun charging(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging ?: false
    }.getOrDefault(false)

    fun reschedule(ctx: Context) {
        val fix = if (LocationProvider.hasPermission(ctx)) LocationProvider.cached(ctx) else null
        val prev = Geofences.lastFix(ctx)
        val still = fix != null && Geofences.stationary(prev?.first, prev?.second, fix.lat, fix.lon)
        schedule(ctx, fix?.lat, fix?.lon, still)
    }

    private fun schedule(ctx: Context, lat: Double?, lon: Double?, stationary: Boolean) {
        val am = alarms(ctx) ?: return
        val fences = Geofences.held(ctx)
        val delayMs = Geofences.nextPollDelayMs(fences, lat, lon, charging(ctx), stationary)
        if (delayMs <= 0L) {
            runCatching { am.cancel(tickIntent(ctx)) }
            Log.i(TAG, "no fences armed; wake cancelled")
            return
        }
        runCatching {
            // Inexact on purpose. AndAllowWhileIdle is still required: without it Doze
            // defers the wake to the next maintenance window.
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                tickIntent(ctx)
            )
        }.onFailure { Log.w(TAG, "could not arm the geofence wake", it) }
        Log.i(TAG, "next geofence check in ${delayMs / 1000}s (${fences.size} armed, " +
            "charging=${charging(ctx)} stationary=$stationary)")
    }

    // BLOCKING (GPS + network): call from GeofenceAlarmReceiver's goAsync worker, never the main thread.
    fun tick(ctx: Context) {
        if (!LocationProvider.hasPermission(ctx)) {
            Geofences.dropAll(ctx, "the OS location permission is not granted")
            schedule(ctx, null, null, stationary = false)
            return
        }
        val consent = GeofenceConsent.state(ctx)
        if (!GeofenceConsent.evaluationAllowed(consent)) {
            Geofences.dropAll(ctx, GeofenceConsent.refusalReason(consent))
            schedule(ctx, null, null, stationary = false)
            return
        }
        val fences = Geofences.held(ctx)
        if (fences.isEmpty()) {
            schedule(ctx, null, null, stationary = false)
            reportIfOwed(ctx)
            return
        }

        val floorS = max(Geofences.MIN_POLL_S, fences.minOf { it.pollS })
        val tightestM = fences.minOf { it.radiusM }.toFloat()
        val fix = LocationProvider.freshBlocking(ctx, floorS, tightestM, FIX_TIMEOUT_MS)

        var stationary = false
        var crossings = emptyList<GeofenceCrossing>()
        if (fix != null) {
            val prev = Geofences.lastFix(ctx)
            stationary = Geofences.stationary(prev?.first, prev?.second, fix.lat, fix.lon)
            crossings = Geofences.evaluate(ctx, fix.lat, fix.lon, fix.accuracyM, System.currentTimeMillis())
            Geofences.rememberFix(ctx, fix.lat, fix.lon)
        } else {
            Log.i(TAG, "no fix this pass; nothing decided")
        }

        // Reschedule BEFORE reporting: the report can block for the whole OkHttp timeout,
        // and a throw there must not leave the watcher with no next wake.
        schedule(ctx, fix?.lat, fix?.lon, stationary)
        reportIfOwed(ctx, force = crossings.isNotEmpty())
    }

    private fun reportIfOwed(ctx: Context, force: Boolean = false) {
        if (Geofences.pendingCrossings(ctx).isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReportAttemptMs < RETRY_FLOOR_MS) return
        lastReportAttemptMs = now
        runCatching { Uploader(ctx).reportGeofenceCrossings() }
            .onFailure { Log.w(TAG, "crossing report failed; the queue is unchanged", it) }
    }
}

class GeofenceAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val action = intent.action.orEmpty()
        val pending = goAsync()
        Thread {
            try {
                when (action) {
                    Intent.ACTION_POWER_CONNECTED,
                    Intent.ACTION_POWER_DISCONNECTED,
                    // An app update cancels every PendingIntent we hold, so the wake is re-armed here.
                    Intent.ACTION_MY_PACKAGE_REPLACED -> GeofenceWatcher.reschedule(app)
                    else -> GeofenceWatcher.tick(app)
                }
            } catch (t: Throwable) {
                Log.w("RistGeofence", "geofence pass failed for '$action'", t)
            } finally {
                pending.finish()
            }
        }.apply { name = "rist-geofence" }.start()
    }
}
