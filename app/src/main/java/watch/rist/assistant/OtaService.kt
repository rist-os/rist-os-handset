package watch.rist.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class OtaService : android.app.Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat("Preparing update")

        val build = intent?.getStringExtra(EXTRA_BUILD).orEmpty()
        val handoff = handoffFrom(intent)
        if (handoff == null) {
            report(build, VERDICT_RETRY, "started without a hand-off")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Thread {
            try {
                apply(build, handoff)
            } catch (t: Throwable) {
                Log.e(TAG, "apply failed", t)
                report(build, VERDICT_RETRY, "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                stopSelf(startId)
            }
        }.apply { name = "rist-ota-apply" }.start()

        return START_NOT_STICKY
    }

    private fun apply(build: String, handoff: OtaApply.Handoff) {
        val done = CountDownLatch(1)
        val lastProgressMs = AtomicLong(System.currentTimeMillis())
        // Written on a binder thread, read on this one.
        val completion = java.util.concurrent.atomic.AtomicInteger(NO_CODE)

        val progress = object : OtaEngine.Progress {
            override fun onStatus(status: Int, percent: Double) {
                lastProgressMs.set(System.currentTimeMillis())
                setOngoingText(statusText(status, percent))
                reportProgress(build, status, percent)
            }

            override fun onComplete(errorCode: Int) {
                completion.set(errorCode)
                done.countDown()
            }
        }

        when (val a = OtaEngine.apply(handoff, progress)) {
            is OtaEngine.Availability.Unavailable -> {
                Log.e(TAG, "cannot apply [${a.reason}]: ${a.detail}")
                report(build, VERDICT_RETRY, "update_engine refused the update (${a.reason})")
                return
            }
            OtaEngine.Availability.AlreadyRunning -> {
                Log.i(TAG, "attaching to an update_engine apply that was already running")
            }
            OtaEngine.Availability.AlreadyStaged -> {
                Log.i(TAG, "$build is already staged; recording it as applied")
                report(build, VERDICT_APPLIED, "already staged; restart to finish updating")
                return
            }
            OtaEngine.Availability.Started -> Unit
        }

        setOngoingText("Downloading update")
        var stalled = false
        while (!done.await(POLL_SECONDS, TimeUnit.SECONDS)) {
            val silentMs = System.currentTimeMillis() - lastProgressMs.get()
            if (silentMs > STALL_TIMEOUT_MINUTES * 60_000L) {
                stalled = true
                break
            }
        }

        if (stalled) {
            Log.e(TAG, "update_engine went silent for ${STALL_TIMEOUT_MINUTES}m; giving up on $build")
            report(build, VERDICT_RETRY,
                "no progress for ${STALL_TIMEOUT_MINUTES}m after applyPayload was accepted")
            return
        }

        val code = completion.get()
        when (val outcome = OtaApply.classify(code)) {
            OtaApply.Outcome.Applied -> {
                setOngoingText("Update installed; restart to finish")
                report(build, VERDICT_APPLIED, "error_code=$code")
            }
            OtaApply.Outcome.AppliedNotActive ->
                report(build, VERDICT_APPLIED, "written but not activated (error_code=$code)")
            is OtaApply.Outcome.Retry -> report(build, VERDICT_RETRY, outcome.detail)
            is OtaApply.Outcome.Permanent -> report(build, VERDICT_PERMANENT, outcome.detail)
            is OtaApply.Outcome.NeedsUser -> report(build, VERDICT_NEEDS_USER, outcome.detail)
            OtaApply.Outcome.Corrupted -> {
                Log.e(TAG, "DEVICE_CORRUPTED: no further updates may be applied")
                report(build, VERDICT_CORRUPTED, "device corrupted")
            }
        }
    }

    private fun report(build: String, verdict: String, detail: String) {
        val i = Intent(this, OtaResultReceiver::class.java)
            .setAction(ACTION_RESULT)
            .putExtra(EXTRA_BUILD, build)
            .putExtra(EXTRA_VERDICT, verdict)
            .putExtra(EXTRA_DETAIL, detail)
        runCatching { sendBroadcast(i) }
            .onFailure { Log.e(TAG, "could not report '$verdict' for $build", it) }
    }

    private fun reportProgress(build: String, status: Int, percent: Double) {
        if (build.isBlank()) return
        val pct = (percent.coerceIn(0.0, 1.0) * 100).toInt()
        val now = System.currentTimeMillis()
        if (!otaShouldReportProgress(
                sentPercent.get(), sentStatus.get(), sentAtMs.get(), pct, status, now)) {
            return
        }
        // Stamped before the send so a throwing sendBroadcast cannot hot-loop.
        sentPercent.set(pct)
        sentStatus.set(status)
        sentAtMs.set(now)
        val i = Intent(this, OtaResultReceiver::class.java)
            .setAction(ACTION_PROGRESS)
            .putExtra(EXTRA_BUILD, build)
            .putExtra(EXTRA_STATUS, status)
            .putExtra(EXTRA_PERCENT, pct)
        runCatching { sendBroadcast(i) }
            .onFailure { Log.w(TAG, "could not report progress for $build", it) }
    }

    private val sentPercent = java.util.concurrent.atomic.AtomicInteger(-1)
    private val sentStatus = java.util.concurrent.atomic.AtomicInteger(-1)
    private val sentAtMs = AtomicLong(0L)

    private fun ongoing(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("System update")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun setOngoingText(text: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, ongoing(text))
        }
    }

    private fun startForegroundCompat(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "System updates", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Must match android:foregroundServiceType in the manifest; a mismatch throws at runtime.
            startForeground(
                NOTIF_ID, ongoing(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIF_ID, ongoing(text))
        }
    }

    private fun statusText(status: Int, percent: Double): String {
        val pct = (percent.coerceIn(0.0, 1.0) * 100).toInt()
        return when (status) {
            OtaApply.Status.DOWNLOADING -> "Downloading update ($pct%)"
            OtaApply.Status.VERIFYING -> "Verifying update ($pct%)"
            OtaApply.Status.FINALIZING -> "Finishing update ($pct%)"
            OtaApply.Status.UPDATED_NEED_REBOOT -> "Update installed; restart to finish"
            OtaApply.Status.REPORTING_ERROR_EVENT -> "Update failed"
            else -> "Preparing update"
        }
    }

    private fun handoffFrom(intent: Intent?): OtaApply.Handoff? {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return null
        val props = intent.getStringArrayExtra(EXTRA_PROPERTIES) ?: return null
        val offset = intent.getLongExtra(EXTRA_OFFSET, -1L)
        val size = intent.getLongExtra(EXTRA_SIZE, -1L)
        if (url.isBlank() || offset < 0L || size <= 0L || props.isEmpty()) return null
        return OtaApply.Handoff(url, offset, size, props)
    }

    companion object {
        private const val TAG = "RistOtaService"
        private const val CHANNEL_ID = "rist_ota"

        // Distinct from PushService (1002/1003), RecordService (1001) and AlarmService (0x4A1).
        private const val NOTIF_ID = 1004

        // Must exceed the longest legitimate silence: FINALIZING runs for minutes with no callback.
        const val STALL_TIMEOUT_MINUTES = 15L

        private const val POLL_SECONDS = 30L

        // Not 0 (SUCCESS) and not Int.MIN_VALUE: OtaApply.classify masks with 0x0FFFFFFF,
        // under which 0x80000000 is also 0.
        private const val NO_CODE = -1

        const val ACTION_RESULT = "watch.rist.assistant.OTA_RESULT"

        const val ACTION_PROGRESS = "watch.rist.assistant.OTA_PROGRESS"
        // Whole percent, 0..100.
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_STATUS = "status"

        const val EXTRA_BUILD = "build"
        const val EXTRA_VERDICT = "verdict"
        const val EXTRA_DETAIL = "detail"

        private const val EXTRA_URL = "url"
        private const val EXTRA_OFFSET = "offset"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_PROPERTIES = "properties"

        const val VERDICT_APPLIED = "applied"
        const val VERDICT_RETRY = "retry"
        const val VERDICT_PERMANENT = "permanent"
        const val VERDICT_NEEDS_USER = "needs-user"
        const val VERDICT_CORRUPTED = "corrupted"

        fun start(ctx: Context, handoff: OtaApply.Handoff, build: String) {
            val i = Intent(ctx, OtaService::class.java)
                .putExtra(EXTRA_BUILD, build)
                .putExtra(EXTRA_URL, handoff.url)
                .putExtra(EXTRA_OFFSET, handoff.payloadOffset)
                .putExtra(EXTRA_SIZE, handoff.payloadSize)
                .putExtra(EXTRA_PROPERTIES, handoff.properties)
            runCatching { ctx.startForegroundService(i) }
                .onFailure { Log.e(TAG, "could not start the :ota process for $build", it) }
        }
    }
}

internal const val OTA_PROGRESS_MIN_INTERVAL_MS = 2_000L

internal fun otaShouldReportProgress(
    lastPercent: Int,
    lastStatus: Int,
    lastSentAtMs: Long,
    percent: Int,
    status: Int,
    nowMs: Long,
): Boolean {
    if (lastSentAtMs <= 0L) return true
    if (percent == lastPercent && status == lastStatus) return false
    if (nowMs < lastSentAtMs) return true
    if (status != lastStatus) return true
    return nowMs - lastSentAtMs >= OTA_PROGRESS_MIN_INTERVAL_MS
}
