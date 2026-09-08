package watch.rist.assistant

import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import rist.v1.MediaCommand

@UnstableApi
class MediaCommandExecutor(
    private val player: Player,
    private val reporter: ProgressReporter,
) {

    companion object {
        private const val TAG = "RistMediaExec"

        private const val MIN_RATE = 0.5f
        private const val MAX_RATE = 3.0f

        const val ACTION_PLAY = "play"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        const val ACTION_SEEK = "seek"
        const val ACTION_SET_SPEED = "set_speed"
        const val ACTION_NEXT = "next"
        const val ACTION_PREVIOUS = "previous"
    }

    // Must be called on the player's application (main) thread.
    fun execute(cmd: MediaCommand): String? {
        val action = cmd.action.trim().lowercase()
        Log.i(TAG, "media command: '$action'")
        return when (action) {
            ACTION_PLAY -> doPlay(cmd)
            ACTION_PAUSE -> { player.pause(); "paused" }
            ACTION_RESUME -> { player.play(); "resumed" }
            ACTION_STOP -> doStop()
            ACTION_SEEK -> doSeek(cmd)
            ACTION_SET_SPEED -> doSetSpeed(cmd)
            ACTION_NEXT -> { if (player.hasNextMediaItem()) player.seekToNextMediaItem(); "next" }
            ACTION_PREVIOUS -> { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem(); "previous" }
            "" -> { Log.w(TAG, "empty media action — ignored"); null }
            else -> { Log.w(TAG, "unknown media action '$action' — ignored"); null }
        }
    }

    private fun doPlay(cmd: MediaCommand): String {
        val head = toMediaItem(cmd.itemId, cmd.streamUrl, cmd.section, cmd.title, cmd.author)
        val tail = cmd.playlistList.map { url ->
            toMediaItem(itemId = url, streamUrl = url, section = cmd.section, title = cmd.title, author = cmd.author)
        }
        val items: List<MediaItem> = listOf(head) + tail

        // start_position_s is uint32 seconds.
        val startMs = (cmd.startPositionS.toLong().coerceAtLeast(0L)) * 1000L
        player.setMediaItems(items,  0,  startMs)
        player.prepare()
        player.playWhenReady = true
        return "playing ${items.size} item(s)" + if (startMs > 0) " @${startMs / 1000}s" else ""
    }

    private fun doStop(): String {
        reporter.reportStopping()
        player.stop()
        return "stopped"
    }

    private fun doSeek(cmd: MediaCommand): String {
        val duration = player.duration  // ms, or C.TIME_UNSET
        return if (cmd.seekByS != 0) {
            val targetMs = (player.currentPosition + cmd.seekByS.toLong() * 1000L)
                .coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
            player.seekTo(targetMs)
            "seek ${if (cmd.seekByS >= 0) "+" else ""}${cmd.seekByS}s"
        } else {
            val targetMs = (cmd.seekToS.toLong() * 1000L)
                .coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
            player.seekTo(targetMs)
            "seek @${targetMs / 1000}s"
        }
    }

    private fun doSetSpeed(cmd: MediaCommand): String {
        val rate = cmd.rate.coerceIn(MIN_RATE, MAX_RATE)
        player.playbackParameters = PlaybackParameters(rate)
        return "speed ${rate}x"
    }

    private fun toMediaItem(itemId: String, streamUrl: String, section: Int, title: String, author: String): MediaItem {
        val extras = Bundle().apply { putInt(ProgressReporter.EXTRA_SECTION, section) }
        val metadata = MediaMetadata.Builder()
            .apply {
                if (title.isNotEmpty()) setTitle(title)
                if (author.isNotEmpty()) setArtist(author)
            }
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(itemId.ifEmpty { streamUrl })
            .apply { if (streamUrl.isNotEmpty()) setUri(streamUrl) }
            .setMediaMetadata(metadata)
            .build()
    }
}
