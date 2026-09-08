package watch.rist.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import java.io.File

object VoicemailPlayback {

    private const val TAG = "RistVmPlay"

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var onStop: (() -> Unit)? = null

    private var appCtx: Context? = null

    fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun play(ctx: Context, file: File, onFinished: () -> Unit) {
        stop()
        val am = ctx.getSystemService(AudioManager::class.java) ?: run { onFinished(); return }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) stop()
            }
            .build()
        focusRequest = req

        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "audio focus refused; not playing")
            focusRequest = null
            onFinished()
            return
        }

        appCtx = ctx.applicationContext
        onStop = onFinished
        val ok = runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "playback error what=$what extra=$extra")
                    stop(); true
                }
                prepare()
                start()
            }
            true
        }.onFailure { Log.w(TAG, "could not play voicemail", it) }.getOrDefault(false)

        if (!ok) stop()
    }

    fun stop() {
        val finished = onStop
        onStop = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        // Abandon focus in the same step as releasing the player, or media stays paused.
        val req = focusRequest
        val ctx = appCtx
        focusRequest = null
        appCtx = null
        if (req != null && ctx != null) {
            runCatching { ctx.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req) }
        }
        finished?.let { cb ->
            runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post { cb() } }
        }
    }
}
