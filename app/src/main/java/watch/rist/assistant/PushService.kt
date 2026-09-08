package watch.rist.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class PushService : Service() {

    companion object {
        private const val TAG = "RistPush"
        private const val CHANNEL_ID = "rist_push"
        private const val NOTIF_ID = 1002
        private const val MSG_NOTIF_ID = 1003

        const val ACTION_PUSH_MESSAGE = "watch.rist.assistant.action.PUSH_MESSAGE"
        const val EXTRA_MESSAGE = "message"

        private const val PING_INTERVAL_SEC = 4L * 60

        internal const val BACKOFF_MIN_MS = 2_000L
        internal const val BACKOFF_MAX_MS = 60_000L

        internal const val NEVER_CONNECTED_MAX_MS = 30L * 60 * 1_000
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // no read timeout on a long-lived socket
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var webSocket: WebSocket? = null

    @Volatile private var everConnected = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        scope.launch { connectLoop() }
        return START_STICKY
    }

    private fun isPushUrlValid(url: String): Boolean =
        url.startsWith("ws://") || url.startsWith("wss://") ||
        url.startsWith("http://") || url.startsWith("https://")

    private suspend fun connectLoop() {
        var backoff = BACKOFF_MIN_MS
        while (scope.isActive) {
            val closed = CompletableDeferred<Unit>()
            val url = Config.pushUrl(applicationContext).trim()
            if (!isPushUrlValid(url)) {
                Log.w(TAG, "push endpoint not configured yet; retry in ${backoff}ms")
                setOngoingText("No push endpoint")
                delay(backoff)
                backoff = nextPushBackoffMs(backoff, everConnected)
                continue
            }
            Log.i(TAG, "push channel connecting to $url")

            // Request.Builder throws IllegalArgumentException on a blank/invalid URL; it must not escape this coroutine.
            val request = runCatching {
                Request.Builder().url(url)
                    .header("X-Rist-Device", Config.deviceId(applicationContext))
                    // Bearer as a header, not a query parameter, so it stays out of access logs; never logged.
                    .apply { Uploader.bearer(applicationContext)?.let { header("Authorization", it) } }
                    .build()
            }.getOrNull()
            if (request == null) {
                Log.w(TAG, "bad push url '$url'; retry in ${backoff}ms")
                delay(backoff)
                backoff = nextPushBackoffMs(backoff, everConnected)
                continue
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "push channel open")
                    everConnected = true
                    backoff = BACKOFF_MIN_MS
                    setOngoingText("Connected")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.i(TAG, "push message (text)")
                    dispatch(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.i(TAG, "push message (binary ${bytes.size}B)")
                    dispatch(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "push channel closing: $code $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "push channel closed: $code $reason")
                    setOngoingText("Reconnecting")
                    if (!closed.isCompleted) closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "push channel failed: ${t.message}")
                    setOngoingText("Reconnecting")
                    if (!closed.isCompleted) closed.complete(Unit)
                }
            }

            webSocket = client.newWebSocket(request, listener)

            closed.await()
            webSocket = null
            if (!scope.isActive) break

            Log.i(TAG, "reconnecting in ${backoff}ms")
            delay(backoff)
            backoff = nextPushBackoffMs(backoff, everConnected)
        }
    }

    private fun dispatch(message: String) {
        // A wake is a device instruction: it returns here and must never reach the broadcast or the shade.
        if (isOtaCheckWake(message)) {
            Log.i(TAG, "push wake: check for updates")
            OtaScheduler.requestCheckNow(applicationContext)
            return
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(ACTION_PUSH_MESSAGE).putExtra(EXTRA_MESSAGE, message)
        )
        notifyMessage(message)
    }

    private fun notifyMessage(message: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Rist")
            .setContentText(message.take(120))
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .build()
        nm.notify(MSG_NOTIF_ID, notif)
    }

    private fun ongoing(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Rist")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun setOngoingText(text: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                // notify on NOTIF_ID updates the foreground notification in place; no second startForeground.
                .notify(NOTIF_ID, ongoing(text))
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Rist connection", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notif: Notification = ongoing("Starting")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        runCatching { webSocket?.close(1000, "service destroyed") }
        webSocket = null
        scope.cancel()
        super.onDestroy()
    }
}

internal fun nextPushBackoffMs(current: Long, everConnected: Boolean): Long {
    val ceiling = if (everConnected) PushService.BACKOFF_MAX_MS else PushService.NEVER_CONNECTED_MAX_MS
    // coerceIn, not coerceAtMost: an input of 0 must not park the loop in a tight spin.
    return (current * 2).coerceIn(PushService.BACKOFF_MIN_MS, ceiling)
}

internal fun isOtaCheckWake(message: String): Boolean {
    val trimmed = message.trim()
    if (trimmed.equals(PUSH_WAKE_OTA_CHECK, ignoreCase = true)) return true
    if (!trimmed.startsWith("{")) return false
    val type = try {
        org.json.JSONObject(trimmed).optString("type")
    // Throwable, not JSONException: org.json recursion on deep nesting raises StackOverflowError.
    } catch (t: Throwable) {
        return false
    }
    return type.equals(PUSH_WAKE_OTA_CHECK, ignoreCase = true)
}

internal const val PUSH_WAKE_OTA_CHECK = "ota_check"
