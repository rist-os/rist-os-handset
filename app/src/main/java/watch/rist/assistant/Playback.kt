package watch.rist.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream

object Playback {
    private const val TAG = "RistPlayback"

    private const val RATE = 24_000

    @Volatile private var activeUntil = 0L
    private val main = Handler(Looper.getMainLooper())

    fun isActive(): Boolean = SystemClock.elapsedRealtime() < activeUntil

    internal fun isOpus(codec: String?): Boolean = codec?.trim().equals(Uploader.CODEC_OPUS, ignoreCase = true)

    @Suppress("UNUSED_PARAMETER")
    fun play(ctx: Context, audio: ByteArray, codec: String = "", onDone: (() -> Unit)? = null) {
        if (audio.isEmpty()) return
        if (isOpus(codec)) playOpus(audio, onDone) else playPcm(audio, RATE, 1, onDone)
    }

    private fun playPcm(pcm: ByteArray, sampleRate: Int, channels: Int, onDone: (() -> Unit)? = null) {
        if (pcm.isEmpty()) return
        runCatching {
            val channelMask =
                if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            val frames = pcm.size / (2 * maxOf(channels, 1))
            val durationMs = if (sampleRate > 0) frames * 1000L / sampleRate else 0L
            activeUntil = maxOf(activeUntil, SystemClock.elapsedRealtime() + durationMs)

            if (frames > 0) {
                track.setNotificationMarkerPosition(frames)
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) {
                        runCatching { t?.release() }
                        if (onDone != null) main.post(onDone)
                    }
                    override fun onPeriodicNotification(t: AudioTrack?) {}
                })
            }

            track.write(pcm, 0, pcm.size)
            track.play()
        }.onFailure {
            Log.e(TAG, "playback failed", it)
            if (onDone != null) main.post(onDone)
        }
    }

    private fun playOpus(ogg: ByteArray, onDone: (() -> Unit)? = null) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position < 0 || position >= ogg.size) return -1
                    val n = minOf(size, ogg.size - position.toInt())
                    System.arraycopy(ogg, position.toInt(), buffer, offset, n)
                    return n
                }

                override fun getSize(): Long = ogg.size.toLong()
                override fun close() {}
            })

            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (trackIndex == null) { Log.w(TAG, "no audio track in Opus stream"); return }
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_AUDIO_OPUS
            var sampleRate =
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else RATE
            var channels =
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(chunk, 0, info.size)
                            pcm.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }

            val decoded = pcm.toByteArray()
            if (decoded.isNotEmpty()) playPcm(decoded, sampleRate, channels, onDone)
            else { Log.w(TAG, "Opus decode produced no PCM"); if (onDone != null) main.post(onDone) }
        } catch (t: Throwable) {
            Log.e(TAG, "Opus decode failed", t)
            if (onDone != null) main.post(onDone)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }
}
