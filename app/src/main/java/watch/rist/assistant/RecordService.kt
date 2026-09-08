package watch.rist.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import rist.v1.DeviceResponse
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RecordService : Service() {

    companion object {
        const val ACTION_START = "watch.rist.assistant.RECORD_START"
        const val ACTION_STOP = "watch.rist.assistant.RECORD_STOP"
        const val ACTION_CANCEL = "watch.rist.assistant.RECORD_CANCEL"

        const val ACTION_ASSISTANT_REPLY = "watch.rist.assistant.action.ASSISTANT_REPLY"
        const val ACTION_CAPTURE_WARNING = "watch.rist.assistant.action.CAPTURE_WARNING"
        const val EXTRA_SECS_LEFT = "secs_left"
        const val ACTION_CAPTURE_DISCARDED = "watch.rist.assistant.action.CAPTURE_DISCARDED"
        const val EXTRA_REPLY_TEXT = "reply_text"
        const val EXTRA_REPLY_STATUS = "reply_status"
        const val EXTRA_REPLY_PROTO = "reply_proto"

        private const val TAG = "RistRecord"
        private const val CHANNEL_ID = "rist_record"
        private const val NOTIF_ID = 1001

        private const val SAMPLE_RATE = 16_000
        private val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // true = Ogg-Opus via MediaRecorder; false = raw PCM16 AudioRecord fallback.
        private const val USE_OPUS_UPLINK = true

        // Opus encode bitrate, bps.
        private const val OPUS_BITRATE = 32_000
        // Hard capture ceiling; the backend rejects longer audio with 413.
        private const val MAX_CAPTURE_MS = 60_000L
        private const val CAPTURE_WARN_MS = 10_000L
        private const val MIN_CAPTURE_MS = 300L
        // MediaRecorder amplitude scale (0..32767).
        private const val SPEECH_RMS_MIN = 250
        private const val AMPLITUDE_POLL_MS = 100L
        private const val DUPLICATE_WINDOW_MS = 1_000L
        @Volatile private var lastSentAtMs = 0L
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var captureJob: Job? = null
    private var recorder: AudioRecord? = null
    private var mediaRecorder: MediaRecorder? = null
    private var opusFile: File? = null
    @Volatile private var recording = false
    @Volatile private var captureStartedAtMs = 0L
    @Volatile private var peakAmplitude = 0
    // Energy proxy from periodic maxAmplitude samples, not a true signal RMS.
    @Volatile private var ampSumSq = 0.0
    @Volatile private var ampSamples = 0
    @Volatile private var clipId = ""
    private val capHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start arrives via startForegroundService(); startForeground() must run on every path.
        startForegroundCompat()
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_CANCEL -> cancelRecording()
            else -> { stopForegroundCompat(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recording) return
        if (USE_OPUS_UPLINK) startRecordingOpus() else startRecordingPcm()
    }

    private fun startRecordingOpus() {
        startForegroundCompat()
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
                  else @Suppress("DEPRECATION") MediaRecorder()
        val started = runCatching {
            val f = File.createTempFile("rist_ptt_", ".ogg", cacheDir)
            opusFile = f
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)
                setAudioEncodingBitRate(OPUS_BITRATE)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
            true
        }.getOrElse {
            Log.e(TAG, "MediaRecorder/Opus start failed", it)
            runCatching { rec.release() }
            opusFile?.delete(); opusFile = null
            false
        }
        if (!started) {
            runCatching {
                LocalBroadcastManager.getInstance(applicationContext)
                    .sendBroadcast(Intent(ACTION_CAPTURE_DISCARDED))
            }
            stopForegroundCompat(); stopSelf(); return
        }
        mediaRecorder = rec
        recording = true
        captureStartedAtMs = SystemClock.elapsedRealtime()
        armCaptureLimit()
        Log.i(TAG, "recording started (opus)")
    }

    private fun startRecordingPcm() {
        startForegroundCompat()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, SAMPLE_RATE)
        @Suppress("MissingPermission")
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING, bufSize
        ).also { it.startRecording() }

        recording = true
        val uploader = Uploader(applicationContext)
        captureJob = scope.launch {
            uploader.beginStream()
            val buf = ShortArray(bufSize / 2)
            while (isActive && recording) {
                val n = recorder?.read(buf, 0, buf.size) ?: 0
                if (n > 0) uploader.sendFrame(buf, n)
            }
            val reply = uploader.endStream()
            handleResponse(reply, uploader.lastFailure)
        }
        Log.i(TAG, "recording started")
    }

    private fun handleResponse(reply: DeviceResponse?, failure: String = "") {
        if (reply == null) {
            broadcastReply(text = "", status = failure.ifBlank { "no reply" }, proto = null)
            return
        }

        val speech = reply.speech
        val text = speech?.text.orEmpty()
        val audio = speech?.audio
        val hasAudio = audio != null && !audio.isEmpty
        val hasView = reply.hasView()
        val proto = reply.toByteArray()

        // Applied here as well as in MainActivity; handle() is idempotent per response.
        runCatching { DeviceCommands.handle(applicationContext, reply) }
            .onFailure { Log.w(TAG, "device command handling failed", it) }

        if (reply.actionsCount > 0) Log.i(TAG, "response carries ${reply.actionsCount} action(s)")
        if (reply.hasConfirm()) Log.i(TAG, "response proposes action_id='${reply.confirm.actionId}': ${reply.confirm.prompt}")

        val voiceOn = Config.isReplyVoiceEnabled(applicationContext)
        Log.i("RistReply", "speech: audio=${audio?.size() ?: 0}B codec='${speech.audioCodec.orEmpty()}' " +
            "text=${text.length}c voiceOn=$voiceOn")

        if (voiceOn && hasAudio) Playback.play(applicationContext, audio!!.toByteArray(), speech.audioCodec)

        val parts = buildList {
            when {
                voiceOn && hasAudio -> add("▶ audio ${audio!!.size()}B")
                text.isNotBlank() -> add("◀ text")
            }
            if (hasView) add("🖼 view v${reply.view.schemaVersion}")
        }
        val status = if (parts.isEmpty()) "reply had no speech" else parts.joinToString("  +  ")
        broadcastReply(text = text, status = status, proto = proto)
    }

    private fun broadcastReply(text: String, status: String, proto: ByteArray?) {
        val intent = Intent(ACTION_ASSISTANT_REPLY)
            .putExtra(EXTRA_REPLY_TEXT, text)
            .putExtra(EXTRA_REPLY_STATUS, status)
        if (proto != null) intent.putExtra(EXTRA_REPLY_PROTO, proto)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun pollAmplitude() {
        if (!recording) return
        runCatching { mediaRecorder?.maxAmplitude }.getOrNull()?.let { amp ->
            if (amp > peakAmplitude) peakAmplitude = amp
            if (amp > 0) { ampSumSq += amp.toDouble() * amp; ampSamples++ }
        }
        capHandler.postDelayed(::pollAmplitude, AMPLITUDE_POLL_MS)
    }

    private fun armCaptureLimit() {
        capHandler.removeCallbacksAndMessages(null)
        peakAmplitude = 0; ampSumSq = 0.0; ampSamples = 0
        clipId = "c" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        capHandler.postDelayed(::pollAmplitude, AMPLITUDE_POLL_MS)
        capHandler.postDelayed({
            if (recording) {
                LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
                    Intent(ACTION_CAPTURE_WARNING).putExtra(EXTRA_SECS_LEFT, (CAPTURE_WARN_MS / 1000L).toInt()))
                vibrate(120)
            }
        }, MAX_CAPTURE_MS - CAPTURE_WARN_MS)
        capHandler.postDelayed({
            if (recording) {
                Log.i(TAG, "capture hit the ${MAX_CAPTURE_MS / 1000}s cap — stopping and sending")
                vibrate(220)
                stopRecording()
            }
        }, MAX_CAPTURE_MS)
    }

    private fun vibrate(ms: Long) = runCatching {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun rmsAmplitude(): Int =
        if (ampSamples == 0) 0 else kotlin.math.sqrt(ampSumSq / ampSamples).toInt()

    private fun logClip(sent: Boolean, ms: Long, bytes: Int, reqId: String = "") {
        // Log shape the backend joins on: RistClip req=<id> peak=<int> rms=<int> ms=<int> sent=yes|no
        Log.i("RistClip", "req=${reqId.ifBlank { clipId }} peak=$peakAmplitude rms=${rmsAmplitude()} " +
            "ms=$ms sent=${if (sent) "yes" else "no"} bytes=$bytes codec=opus")
    }

    private fun captureNotSpeech(): Boolean {
        val heldMs = SystemClock.elapsedRealtime() - captureStartedAtMs
        val rms = rmsAmplitude()
        if (captureStartedAtMs > 0L && heldMs < MIN_CAPTURE_MS) {
            Log.i(TAG, "discard: ${heldMs}ms < ${MIN_CAPTURE_MS}ms")
            logClip(sent = false, ms = heldMs, bytes = 0)
            return true
        }
        val sinceLastSend = captureStartedAtMs - lastSentAtMs
        if (lastSentAtMs > 0L && sinceLastSend in 0..DUPLICATE_WINDOW_MS && rms < SPEECH_RMS_MIN) {
            Log.i(TAG, "discard: silent repeat ${sinceLastSend}ms after the last send (rms=$rms)")
            logClip(sent = false, ms = heldMs, bytes = 0)
            return true
        }
        if (rms in 1 until SPEECH_RMS_MIN) {
            if (rms > SPEECH_RMS_MIN / 2) {
                Log.w(TAG, "discard: rms=$rms is JUST under the $SPEECH_RMS_MIN floor — if this was speech, the floor is too high")
            }
            Log.i(TAG, "discard: rms=$rms < $SPEECH_RMS_MIN (silence)")
            logClip(sent = false, ms = heldMs, bytes = 0)
            return true
        }
        Log.i(TAG, "capture accepted: ${heldMs}ms rms=$rms peak=$peakAmplitude")
        return false
    }

    private fun stopRecording() {
        capHandler.removeCallbacksAndMessages(null)
        if (captureNotSpeech()) {
            cancelRecording()
            return
        }
        if (USE_OPUS_UPLINK) stopRecordingOpus() else stopRecordingPcm()
    }

    private fun cancelRecording() {
        capHandler.removeCallbacksAndMessages(null)
        if (!recording) { stopForegroundCompat(); stopSelf(); return }
        recording = false
        captureJob?.cancel()
        val rec = mediaRecorder; mediaRecorder = null
        val f = opusFile; opusFile = null
        // stop() throws when the clip is too short; expected.
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        recorder?.run { runCatching { stop() }; runCatching { release() } }
        recorder = null
        f?.delete()
        stopForegroundCompat()
        LocalBroadcastManager.getInstance(applicationContext)
            .sendBroadcast(Intent(ACTION_CAPTURE_DISCARDED))
        Log.i(TAG, "recording cancelled — nothing uploaded")
        stopSelf()
    }

    // stopSelf() must run after the POST completes so the service outlives the round-trip.
    private fun stopRecordingOpus() {
        if (!recording) { stopForegroundCompat(); stopSelf(); return }
        recording = false
        val rec = mediaRecorder; mediaRecorder = null
        val f = opusFile; opusFile = null

        val bytes = runCatching {
            rec?.stop()
            rec?.release()
            f?.readBytes() ?: ByteArray(0)
        }.getOrElse {
            // stop() throws when the clip is too short; treat as empty.
            Log.e(TAG, "opus stop/read failed", it)
            runCatching { rec?.release() }
            ByteArray(0)
        }
        f?.delete()
        stopForegroundCompat()
        lastSentAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "recording stopped (opus, ${bytes.size}B)")

        val sentMs = SystemClock.elapsedRealtime() - captureStartedAtMs
        val uploader = Uploader(applicationContext)
        captureJob = scope.launch {
            val reply = if (bytes.isEmpty()) null else uploader.sendOpus(bytes, sentMs.toInt())
            logClip(sent = true, ms = sentMs, bytes = bytes.size, reqId = reply?.requestId.orEmpty())
            handleResponse(reply, uploader.lastFailure)
            stopSelf()
        }
    }

    private fun stopRecordingPcm() {
        recording = false
        captureJob?.cancel()
        recorder?.run { runCatching { stop() }; release() }
        recorder = null
        stopForegroundCompat()
        stopSelf()
        Log.i(TAG, "recording stopped")
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Rist voice", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Rist is listening")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    // onDestroy can arrive mid-capture; release the MediaRecorder or the mic stays held for the process lifetime.
    override fun onDestroy() {
        capHandler.removeCallbacksAndMessages(null)
        recording = false
        mediaRecorder?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        mediaRecorder = null
        recorder?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        recorder = null
        runCatching { opusFile?.delete() }
        opusFile = null
        scope.cancel()
        super.onDestroy()
    }
}
