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

        internal data class Chapter(val url: String, val section: Int)
        internal data class Queue(val chapters: List<Chapter>, val startIndex: Int)

        /**
         * `stream_url` is the chapter to play now, `section` is its number
         * over the whole book, and `playlist` is the chapters AFTER it, so `playlist[i]` is
         * section `section + 1 + i`. Numbered by position in the list as sent, so a blank entry
         * the backend left in does not shift every chapter after it; blanks are then skipped, and
         * so is a blank `stream_url`, which no player can open.
         */
        internal fun queueFor(streamUrl: String, section: Int, playlist: List<String>): Queue {
            val now = if (streamUrl.isNotBlank()) listOf(Chapter(streamUrl, section)) else emptyList()
            val upcoming = playlist.mapIndexedNotNull { i, url ->
                url.takeIf { it.isNotBlank() }?.let { Chapter(it, section + 1 + i) }
            }
            return Queue(now + upcoming, 0)
        }
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
            // The queue holds the chapter played first and the ones after it, so from that first
            // chapter there is nothing earlier on the phone: it starts again from the top.
            ACTION_PREVIOUS -> if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem(); "previous"
            } else {
                player.seekTo(0L); "previous: from the start of this chapter"
            }
            "" -> { Log.w(TAG, "empty media action — ignored"); null }
            else -> { Log.w(TAG, "unknown media action '$action' — ignored"); null }
        }
    }

    private fun doPlay(cmd: MediaCommand): String {
        val queue = queueFor(cmd.streamUrl, cmd.section, cmd.playlistList)
        // Every chapter is the same book: item_id is the resume key and never changes with the
        // chapter, and each carries its own section so the card and the progress reports follow.
        if (queue.chapters.isEmpty()) {
            Log.w(TAG, "play with nothing to play; ignored")
            return "nothing to play"
        }
        val items = queue.chapters.map {
            toMediaItem(cmd.itemId, it.url, it.section, cmd.title, cmd.author)
        }

        // start_position_s is uint32 seconds.
        val startMs = (cmd.startPositionS.toLong().coerceAtLeast(0L)) * 1000L
        player.setMediaItems(items, queue.startIndex, startMs)
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
