package watch.rist.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rist.v1.MediaProgress

@UnstableApi
class ProgressReporter(
    private val appContext: Context,
    private val player: Player,
) {

    companion object {
        private const val TAG = "RistProgress"

        private const val HEARTBEAT_MS = 15_000L

        const val EXTRA_SECTION = "rist_section"

        private const val A_PLAY = "play"
        private const val A_PAUSE = "pause"
        private const val A_END = "end"
        private const val A_SEEK = "seek"
        private const val A_STOP = "stop"
    }

    private val io = CoroutineScope(Dispatchers.IO + Job())
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var stopping = false

    // Armed before a card-driven seek so its DISCONTINUITY_REASON_SEEK is swallowed; the card reports the seek itself.
    @Volatile private var drivenSeek = false

    // Values at the last report. On auto-advance currentMediaItem has already moved on, so 'end' uses these.
    private var lastItemId = ""
    private var lastSection = 0
    private var lastPositionS = 0
    private var lastTitle = ""

    private val heartbeat = object : Runnable {
        override fun run() {
            if (player.isPlaying) {
                report(A_PLAY)
                handler.postDelayed(this, HEARTBEAT_MS)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                report(A_PLAY)
                handler.removeCallbacks(heartbeat)
                handler.postDelayed(heartbeat, HEARTBEAT_MS)
            } else {
                handler.removeCallbacks(heartbeat)
                if (stopping) { stopping = false; report(A_STOP) } else report(A_PAUSE)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                if (lastItemId.isNotEmpty()) send(A_END, lastItemId, lastSection, lastPositionS, lastTitle)
                report(A_PLAY)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                handler.removeCallbacks(heartbeat)
                report(A_END)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                if (drivenSeek) drivenSeek = false else report(A_SEEK)
            }
        }
    }

    fun start() {
        player.addListener(listener)
    }

    fun stop() {
        handler.removeCallbacks(heartbeat)
        runCatching { player.removeListener(listener) }
        io.cancel()
    }

    fun reportStopping() {
        stopping = true
    }

    fun reportCloseAsPause() {
        report(A_PAUSE)
    }

    fun armDrivenSeek() {
        drivenSeek = true
    }

    fun reportDrivenSeek() {
        drivenSeek = false
        report(A_SEEK)
    }

    private fun report(action: String) {
        val item = player.currentMediaItem
        val itemId = item?.mediaId.orEmpty()
        val section = item?.mediaMetadata?.extras?.getInt(EXTRA_SECTION, 0) ?: 0
        val positionS = (player.currentPosition.coerceAtLeast(0L) / 1000L).toInt()
        val title = item?.mediaMetadata?.title?.toString().orEmpty()
        lastItemId = itemId; lastSection = section; lastPositionS = positionS; lastTitle = title
        send(action, itemId, section, positionS, title)
    }

    private fun send(action: String, itemId: String, section: Int, positionS: Int, title: String) {
        if (itemId.startsWith(PlaybackService.VOICEMAIL_ITEM_PREFIX)) {
            Log.d(TAG, "not reporting progress for voicemail '$itemId'")
            return
        }
        val report = MediaProgress.newBuilder()
            .setUserId(Config.deviceId(appContext))
            .setSessionId(Config.sessionId(appContext))
            .setItemId(itemId)
            .setPositionS(positionS)
            .setSection(section)
            .setAction(action)
            .setTimestamp(System.currentTimeMillis())
            .setTitle(title)
            .build()

        Log.d(TAG, "progress '$action' item='$itemId' section=$section @${positionS}s title='$title'")
        io.launch { Uploader.sendProgress(appContext, report) }
    }
}
