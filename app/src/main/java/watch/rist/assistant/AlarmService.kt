package watch.rist.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

class AlarmService : Service() {

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val stopHandler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable {
        Log.i(TAG, "alarm timed out after ${TIMEOUT_MS / 1000}s with no dismiss")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(AlarmReceiver.EXTRA_LABEL).orEmpty()
        val isTimer = intent?.getBooleanExtra(EXTRA_IS_TIMER, false) == true
        val title = if (isTimer) "Timer" else "Alarm"
        val text = label.ifBlank { if (isTimer) "Time's up" else "Alarm" }

        // Dismiss before any promotion: re-posting the notification re-alerts the channel and replays the tone.
        if (intent?.action == ACTION_DISMISS) {
            Log.i(TAG, "dismissed by ${intent.getStringExtra(EXTRA_REASON) ?: "unknown caller"}")
            stopNoise()
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching { startForeground(NOTIF_ID, buildNotification(title, text)) }
            .onFailure { Log.w(TAG, "startForeground failed", it) }
        Log.i(TAG, "ringing: $title / $text")
        runCatching { DeviceCommands.setRinging(applicationContext, text) }

        takeAudioFocus()
        // Before startRinging(): the buttons must be listening before the alarm is audible.
        watchSideButtons()

        runCatching {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    // NEW_TASK only, never CLEAR_TASK.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(AlarmActivity.EXTRA_LABEL, text)
            )
        }.onFailure { Log.w(TAG, "could not show the alarm face", it) }

        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rist:alarm").apply {
                setReferenceCounted(false)
                acquire(TIMEOUT_MS + 5_000L)
            }
        }.onFailure { Log.w(TAG, "wakelock failed", it) }

        if (intent?.getBooleanExtra(AlarmReceiver.EXTRA_SOUND, true) != false) startRinging()
        if (intent?.getBooleanExtra(AlarmReceiver.EXTRA_VIBRATE, true) != false) startVibrating()

        stopHandler.removeCallbacks(autoStop)
        stopHandler.postDelayed(autoStop, TIMEOUT_MS)
        return START_NOT_STICKY
    }

    private var focusRequest: android.media.AudioFocusRequest? = null

    private fun takeAudioFocus() = runCatching {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }.onFailure { Log.w(TAG, "could not take audio focus", it) }.let { }

    private fun dropAudioFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        runCatching {
            (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                .abandonAudioFocusRequest(req)
        }
    }

    private fun startRinging() = runCatching {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return@runCatching
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(this@AlarmService, uri)
            isLooping = true
            val pct = Config.alarmVolumePercent(this@AlarmService)
            val gain = (pct / 100f).coerceIn(0f, 1f)
            // Scaled on our player, not the alarm stream: moving the stream persists and changes every other alarm.
            setVolume(gain, gain)
            Log.i(TAG, "alarm volume ${pct}% of the device alarm level")
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "alarm player error what=$what extra=$extra"); true
            }
            prepare()
            start()
        }
    }.onFailure { Log.w(TAG, "could not start the alarm tone", it) }.let { }

    private fun startVibrating() = runCatching {
        val cycle = longArrayOf(0, 600, 400, 600, 1000)  // 2.6s per cycle
        val repeats = ((TIMEOUT_MS / cycle.sum()) + 1).toInt()
        val pattern = LongArray(cycle.size * repeats) { cycle[it % cycle.size] }
        vibrator().vibrate(
            android.os.VibrationEffect.createWaveform(pattern, -1),  // -1 = play once, then stop
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
    }.onFailure { Log.w(TAG, "vibrate failed", it) }.let { }

    private fun vibrator(): android.os.Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator

    private fun buildNotification(title: String, text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LEGACY_CHANNELS.forEach { old -> runCatching { nm.deleteNotificationChannel(old) } }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Alarms & timers", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alarms and timers you asked Rist to set"
                    // Silent: AlarmService plays the tone itself on a player it can stop.
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
        val dismiss = PendingIntent.getService(
            this, 2,
            Intent(this, AlarmService::class.java).setAction(ACTION_DISMISS)
                .putExtra(EXTRA_REASON, "the notification action"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(dismiss)
            .addAction(Notification.Action.Builder(null, "Dismiss", dismiss).build())
            .build()
    }

    private var mediaSession: android.media.session.MediaSession? = null
    private var screenWatcher: BroadcastReceiver? = null

    private fun watchSideButtons() {
        if (mediaSession == null) {
            runCatching {
                val session = android.media.session.MediaSession(this, "rist:alarm")
                session.setPlaybackState(
                    android.media.session.PlaybackState.Builder()
                        .setState(
                            android.media.session.PlaybackState.STATE_PLAYING, 0L, 1.0f
                        )
                        .setActions(android.media.session.PlaybackState.ACTION_STOP)
                        .build()
                )
                session.setPlaybackToRemote(
                    object : android.media.VolumeProvider(
                        VOLUME_CONTROL_RELATIVE, 100, 50
                    ) {
                        override fun onAdjustVolume(direction: Int) {
                            dismissSelf("a volume key")
                        }
                        override fun onSetVolumeTo(volume: Int) = dismissSelf("a volume key")
                    }
                )
                session.isActive = true
                mediaSession = session
            }.onFailure { Log.w(TAG, "could not take volume keys", it) }
        }

        if (screenWatcher == null) {
            val sw = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) = dismissSelf("the power button")
            }
            runCatching {
                // Screen-off means a power press only because AlarmActivity holds FLAG_KEEP_SCREEN_ON.
                registerReceiver(sw, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
                screenWatcher = sw
            }.onFailure { Log.w(TAG, "could not watch the power button", it) }
        }
    }

    private fun unwatchSideButtons() {
        mediaSession?.let {
            runCatching { it.isActive = false }
            // An active session left behind keeps claiming volume keys after the alarm is gone.
            runCatching { it.release() }
        }
        mediaSession = null
        screenWatcher?.let { runCatching { unregisterReceiver(it) } }
        screenWatcher = null
    }

    private fun dismissSelf(reason: String) {
        Log.i(TAG, "silenced by $reason")
        stopNoise()
        stopSelf()
    }

    private fun stopNoise() {
        unwatchSideButtons()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator().cancel() }
        dropAudioFocus()
    }

    override fun onDestroy() {
        runCatching { DeviceCommands.setRinging(applicationContext, "") }
        stopHandler.removeCallbacksAndMessages(null)
        stopNoise()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RistAlarm"
        // Channel sound/importance are immutable after creation; bump the id to change them.
        private const val CHANNEL = "rist_alarms_v3"
        private val LEGACY_CHANNELS = listOf("rist_alarms", "rist_alarms_v2")
        private const val NOTIF_ID = 0x4A1
        private const val TIMEOUT_MS = 30_000L
        const val ACTION_DISMISS = "watch.rist.assistant.ALARM_DISMISS"
        const val EXTRA_REASON = "reason"
        const val EXTRA_IS_TIMER = "is_timer"

        fun dismiss(ctx: Context, reason: String = "unnamed") = runCatching {
            ctx.startService(
                Intent(ctx, AlarmService::class.java).setAction(ACTION_DISMISS)
                    .putExtra(EXTRA_REASON, reason)
            )
        }.let { }
    }
}
