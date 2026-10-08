package watch.rist.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps [WakeLoop] running. systemExempted, like OtaService: a dataSync service is capped at six
 * hours a day on Android 15+, and this one runs for as long as the phone is on.
 */
class WakeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var powerReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (loop?.isActive != true) {
            loop = scope.launch { WakeLoop.run(applicationContext) }
            watchNetwork()
            watchPower()
            Log.i(TAG, "wake loop started")
        } else {
            // A second start is a nudge (boot, update, the app opening): poll now.
            WakeLoop.kick()
        }
        return START_STICKY
    }

    // A held poll dies with the network it was on, so a new network means a new poll now,
    // not after the backoff runs out.
    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = WakeLoop.kick()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb; networkWatched = true }
            .onFailure { Log.w(TAG, "no network callback; relying on backoff", it) }
    }

    // A wait stretched for Battery Saver with the screen off ends when either stops being true:
    // the screen coming on (lock screen included) or the saver going off (plugged in) polls now.
    private fun watchPower() {
        if (powerReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = WakeLoop.onPowerStateChanged()
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, r, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            powerReceiver = r
        }.onFailure { Log.w(TAG, "no screen/saver receiver; the stretched wait runs out", it) }
    }

    override fun onDestroy() {
        powerReceiver?.let { runCatching { unregisterReceiver(it) } }
        powerReceiver = null
        netCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        netCallback = null
        networkWatched = false
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Staying in touch", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RIST")
            .setContentText("Listening for new messages")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()
        runCatching {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        }.onFailure {
            Log.w(TAG, "systemExempted refused; starting untyped", it)
            runCatching { startForeground(NOTIF_ID, n) }
        }
    }

    companion object {
        private const val TAG = "RistWake"
        private const val CHANNEL_ID = "rist_wake"
        // Distinct from RecordService (1001), PushService (1002/1003), OtaService (1004),
        // the update offer (1005) and AlarmService (0x4A1).
        private const val NOTIF_ID = 1006

        /** True while a network callback will kick the loop when a network appears. */
        @Volatile internal var networkWatched = false

        /** Idempotent: starts the loop, or kicks it into polling now if it already runs. */
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, WakeService::class.java)) }
                .onFailure { Log.w(TAG, "could not start the wake service", it) }
        }
    }
}
