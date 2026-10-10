package watch.rist.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log

/** A short tone, played as the assistant's own sound, to [out] or wherever sound goes now. */
internal object CueTone {

    /** Plays the tone; false when it could not. Replaced in tests. */
    internal var play: (hz: Int, ms: Int, out: AudioDeviceInfo?) -> Boolean = ::tone

    private val main = Handler(Looper.getMainLooper())

    private fun tone(hz: Int, ms: Int, out: AudioDeviceInfo?): Boolean = runCatching {
        val rate = 16_000
        val n = rate * ms / 1000
        val pcm = ShortArray(n) { i ->
            val fade = minOf(1.0, minOf(i, n - i) / (rate * 0.01))
            (kotlin.math.sin(2 * Math.PI * hz * i / rate) * 6000 * fade).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(n * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        if (out != null) track.setPreferredDevice(out)
        track.write(pcm, 0, n)
        track.play()
        main.postDelayed({ runCatching { track.release() } }, ms + 400L)
        true
    }.getOrDefault(false)
}

/**
 * A recording the person started was dropped as silent or too short. Nothing is sent; they feel
 * the refusal tap, hear a low tone where the listening cue played, and the feed says so.
 */
internal object NothingHeard {

    const val LINE = "I didn't hear anything. Try again."

    internal const val TONE_HZ = 330
    internal const val TONE_MS = 180

    private const val TAG = "RistRecord"

    /**
     * Closes [entryId] with [LINE], or adds an entry saying it when the recording had none (a
     * physical button or a headset press). Returns the entry. [turn], a headset turn, keeps its
     * route until the tone has played in the headset.
     */
    fun tell(ctx: Context, entryId: Long, turn: HeadsetTurn?): Long {
        Haptics.reject(ctx)
        if (turn != null) turn.nothingHeard() else CueTone.play(TONE_HZ, TONE_MS, null)
        val id = runCatching {
            if (entryId != 0L) {
                Transcript.update(ctx, entryId, state = EntryState.FAILED, error = LINE)
                entryId
            } else Transcript.begin(ctx, "(voice)", EntryState.RECORDING).also {
                Transcript.update(ctx, it, state = EntryState.FAILED, error = LINE)
            }
        }.onFailure { Log.w(TAG, "could not note the unheard recording", it) }.getOrDefault(0L)
        return id
    }
}
