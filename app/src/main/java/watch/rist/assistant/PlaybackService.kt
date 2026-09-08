package watch.rist.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rist.v1.MediaCommand

@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "RistPlayback-Svc"

        const val ACTION_MEDIA_COMMAND = "watch.rist.assistant.action.MEDIA_COMMAND"
        const val EXTRA_MEDIA_COMMAND = "media_command_proto"
        const val EXTRA_TOOL_ID = "media_tool_id"

        const val ACTION_MEDIA_STATUS = "watch.rist.assistant.action.MEDIA_STATUS"
        const val EXTRA_MEDIA_STATUS = "media_status"

        const val ACTION_NOW_PLAYING = "watch.rist.assistant.action.NOW_PLAYING"
        const val EXTRA_NP_ACTIVE = "np_active"  // boolean
        const val EXTRA_NP_TITLE = "np_title"  // string
        const val EXTRA_NP_AUTHOR = "np_author"  // string
        const val EXTRA_NP_SECTION = "np_section"  // int (0 = none)
        const val EXTRA_NP_POSITION_MS = "np_position_ms"  // long
        const val EXTRA_NP_DURATION_MS = "np_duration_ms"  // long (0 = unknown)
        const val EXTRA_NP_PLAYING = "np_playing"  // boolean
        const val EXTRA_NP_BUFFERING = "np_buffering"  // boolean
        const val EXTRA_NP_PODCAST = "np_podcast"  // boolean

        const val ACTION_LOCAL_CONTROL = "watch.rist.assistant.action.LOCAL_CONTROL"
        const val EXTRA_CONTROL = "control"

        // The service is exported for Media3; our own two actions require this per-process token.
        private const val EXTRA_CALLER_TOKEN = "caller_token"
        private val PROCESS_TOKEN: String = java.util.UUID.randomUUID().toString()
        const val EXTRA_SEEK_MS = "seek_ms"
        const val CONTROL_TOGGLE = "toggle"
        const val CONTROL_NEXT = "next"
        const val CONTROL_PREVIOUS = "previous"
        const val CONTROL_BACK_10 = "back10"
        const val CONTROL_FORWARD_10 = "forward10"
        const val CONTROL_SEEK_TO = "seek_to"
        const val CONTROL_CLOSE = "close"

        private const val RELATIVE_SKIP_MS = 10_000L

        const val TOOL_PODCASTS = "podcasts"
        const val TOOL_AUDIOBOOKS = "audiobooks"

        const val VOICEMAIL_ITEM_PREFIX = "voicemail:"

        private const val MAX_RETRIES = 3
        private const val NOW_PLAYING_TICK_MS = 1000L

        fun sendCommand(ctx: Context, command: MediaCommand, toolId: String = "") {
            val intent = Intent(ctx, PlaybackService::class.java)
                .setAction(ACTION_MEDIA_COMMAND)
                .putExtra(EXTRA_MEDIA_COMMAND, command.toByteArray())
                .putExtra(EXTRA_TOOL_ID, toolId)
                .putExtra(EXTRA_CALLER_TOKEN, PROCESS_TOKEN)
            ctx.startService(intent)
        }

        fun togglePlayPause(ctx: Context) = sendControl(ctx, CONTROL_TOGGLE)

        fun closePlayer(ctx: Context) = sendControl(ctx, CONTROL_CLOSE)

        fun skipNext(ctx: Context) = sendControl(ctx, CONTROL_NEXT)

        fun skipPrevious(ctx: Context) = sendControl(ctx, CONTROL_PREVIOUS)

        fun skipBack10(ctx: Context) = sendControl(ctx, CONTROL_BACK_10)

        fun skipForward10(ctx: Context) = sendControl(ctx, CONTROL_FORWARD_10)

        fun seekToSeconds(ctx: Context, seconds: Int) {
            val intent = Intent(ctx, PlaybackService::class.java)
                .setAction(ACTION_LOCAL_CONTROL)
                .putExtra(EXTRA_CONTROL, CONTROL_SEEK_TO)
                .putExtra(EXTRA_SEEK_MS, seconds.toLong() * 1000L)
                .putExtra(EXTRA_CALLER_TOKEN, PROCESS_TOKEN)
            ctx.startService(intent)
        }

        private fun sendControl(ctx: Context, control: String) {
            val intent = Intent(ctx, PlaybackService::class.java)
                .setAction(ACTION_LOCAL_CONTROL)
                .putExtra(EXTRA_CONTROL, control)
                .putExtra(EXTRA_CALLER_TOKEN, PROCESS_TOKEN)
            ctx.startService(intent)
        }
    }

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var executor: MediaCommandExecutor? = null
    private var reporter: ProgressReporter? = null
    private var retries = 0

    private val resolverIo = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var lastIsPodcast = false

    private val npHandler = Handler(Looper.getMainLooper())
    private var npRunning = false

    override fun onCreate() {
        super.onCreate()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(playerListener)
        player = exo

        val rep = ProgressReporter(applicationContext, exo).also { it.start() }
        reporter = rep
        executor = MediaCommandExecutor(exo, rep)

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(sessionActivity)
            .build()

        Log.i(TAG, "PlaybackService created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onStartCommand runs on the main thread, which is the player's thread.
        val action = intent?.action
        if (action == ACTION_MEDIA_COMMAND || action == ACTION_LOCAL_CONTROL) {
            if (intent?.getStringExtra(EXTRA_CALLER_TOKEN) != PROCESS_TOKEN) {
                Log.w(TAG, "refused an out-of-process media command")
                return START_STICKY
            }
        }
        when (intent?.action) {
            ACTION_MEDIA_COMMAND -> {
                val bytes = intent.getByteArrayExtra(EXTRA_MEDIA_COMMAND)
                val toolId = intent.getStringExtra(EXTRA_TOOL_ID).orEmpty()
                if (bytes != null) {
                    runCatching {
                        val cmd = MediaCommand.parseFrom(bytes)
                        if (cmd.action.trim().lowercase() == MediaCommandExecutor.ACTION_PLAY) {
                            handlePlay(cmd, toolId)
                        } else {
                            val summary = executor?.execute(cmd)
                            if (summary != null) broadcastStatus("♪ $summary")
                        }
                    }.onFailure { Log.e(TAG, "media command parse/exec failed", it) }
                }
                return START_STICKY
            }
            ACTION_LOCAL_CONTROL -> {
                handleLocalControl(
                    intent.getStringExtra(EXTRA_CONTROL),
                    intent.getLongExtra(EXTRA_SEEK_MS, -1L)
                )
                return START_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handlePlay(cmd: MediaCommand, toolId: String) {
        when (toolId.trim().lowercase()) {
            TOOL_AUDIOBOOKS -> { lastIsPodcast = false; executePlay(cmd) }
            TOOL_PODCASTS -> { lastIsPodcast = true; resolveThenPlay(cmd, forcePodcast = true) }
            else -> resolveThenPlay(cmd, forcePodcast = false)
        }
    }

    private fun resolveThenPlay(cmd: MediaCommand, forcePodcast: Boolean) {
        val url = cmd.streamUrl
        if (url.isBlank()) { executePlay(cmd); return }
        broadcastStatus("♪ resolving…")
        resolverIo.launch {
            val resolved = if (forcePodcast) RssResolver.resolveEnclosure(url, cmd.section)
                           else RssResolver.resolveIfFeed(url, cmd.section)
            val playCmd: MediaCommand? = when {
                resolved != null -> {
                    lastIsPodcast = true
                    cmd.toBuilder().setStreamUrl(resolved).build()
                }
                forcePodcast -> {
                    Log.w(TAG, "podcast RSS resolve failed for $url — nothing to play")
                    null
                }
                else -> { lastIsPodcast = false; cmd }
            }
            withContext(Dispatchers.Main) {
                if (playCmd == null) broadcastStatus("♪ couldn't resolve podcast episode")
                else executePlay(playCmd)
            }
        }
    }

    private fun executePlay(cmd: MediaCommand) {
        val summary = runCatching { executor?.execute(cmd) }
            .getOrElse { Log.e(TAG, "play exec failed", it); null }
        if (summary != null) broadcastStatus("♪ $summary")
        startNowPlayingTicker()
    }

    private fun handleLocalControl(control: String?, seekMs: Long = -1L) {
        val p = player ?: return
        when (control) {
            CONTROL_TOGGLE -> if (p.isPlaying) p.pause() else p.play()
            CONTROL_NEXT -> if (p.hasNextMediaItem()) seekAndReport { p.seekToNextMediaItem() }
            CONTROL_PREVIOUS -> seekAndReport {
                if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(0)
            }
            CONTROL_BACK_10 -> seekAndReport { relativeSeek(p, -RELATIVE_SKIP_MS) }
            CONTROL_FORWARD_10 -> seekAndReport { relativeSeek(p, RELATIVE_SKIP_MS) }
            CONTROL_CLOSE -> {
                // Pause first so the position is final, then report explicitly:
                // an already-paused player fires no state change and would bank nothing.
                if (p.isPlaying) p.pause()
                reporter?.reportCloseAsPause()
                p.clearMediaItems()
                broadcastNowPlaying(false)
            }
            CONTROL_SEEK_TO -> {
                val duration = p.duration
                if (seekMs >= 0 && duration > 0) {
                    seekAndReport { p.seekTo(seekMs.coerceIn(0L, duration)) }
                }
            }
        }
    }

    private fun relativeSeek(p: ExoPlayer, deltaMs: Long) {
        val duration = p.duration
        val max = if (duration > 0) duration else Long.MAX_VALUE
        val target = (p.currentPosition + deltaMs).coerceIn(0L, max)
        p.seekTo(target)
    }

    private fun seekAndReport(seek: () -> Unit) {
        val rep = reporter
        // arm before the seek swallows its auto-report; reportDrivenSeek after posts the single one.
        rep?.armDrivenSeek()
        seek()
        rep?.reportDrivenSeek()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> { broadcastStatus("♪ buffering…"); startNowPlayingTicker() }
                Player.STATE_READY -> { retries = 0; broadcastStatus(nowPlaying()); startNowPlayingTicker() }
                Player.STATE_ENDED -> { broadcastStatus("♪ finished"); broadcastNowPlaying(active = false) }
                Player.STATE_IDLE -> {  broadcastNowPlaying(active = false) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (retries < MAX_RETRIES) {
                retries++
                Log.w(TAG, "player error (${error.errorCodeName}); retry $retries/$MAX_RETRIES", error)
                broadcastStatus("♪ reconnecting… ($retries)")
                player?.prepare()
            } else {
                Log.e(TAG, "player error — giving up after $MAX_RETRIES retries", error)
                broadcastStatus("♪ playback error")
            }
        }
    }

    private fun nowPlaying(): String {
        val title = player?.currentMediaItem?.mediaMetadata?.title?.toString()
        return if (!title.isNullOrBlank()) "▶ $title" else "▶ playing"
    }

    private fun broadcastStatus(msg: String) {
        val intent = Intent(ACTION_MEDIA_STATUS).putExtra(EXTRA_MEDIA_STATUS, msg)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private val npTick = object : Runnable {
        override fun run() {
            val active = broadcastNowPlaying(active = null)
            if (active) npHandler.postDelayed(this, NOW_PLAYING_TICK_MS) else npRunning = false
        }
    }

    private fun startNowPlayingTicker() {
        if (npRunning) return
        npRunning = true
        npHandler.post(npTick)
    }

    private fun broadcastNowPlaying(active: Boolean?): Boolean {
        val p = player
        val state = p?.playbackState ?: Player.STATE_IDLE
        val hasMedia = (p?.mediaItemCount ?: 0) > 0
        val resolvedActive = active ?: (hasMedia &&
            (state == Player.STATE_READY || state == Player.STATE_BUFFERING))

        val md = p?.currentMediaItem?.mediaMetadata
        val duration = p?.duration ?: C.TIME_UNSET
        val intent = Intent(ACTION_NOW_PLAYING)
            .putExtra(EXTRA_NP_ACTIVE, resolvedActive)
            .putExtra(EXTRA_NP_TITLE, md?.title?.toString().orEmpty())
            .putExtra(EXTRA_NP_AUTHOR, md?.artist?.toString().orEmpty())
            .putExtra(EXTRA_NP_SECTION, md?.extras?.getInt(ProgressReporter.EXTRA_SECTION, 0) ?: 0)
            .putExtra(EXTRA_NP_POSITION_MS, p?.currentPosition?.coerceAtLeast(0L) ?: 0L)
            .putExtra(EXTRA_NP_DURATION_MS, if (duration > 0) duration else 0L)
            .putExtra(EXTRA_NP_PLAYING, p?.isPlaying == true)
            .putExtra(EXTRA_NP_BUFFERING, state == Player.STATE_BUFFERING)
            .putExtra(EXTRA_NP_PODCAST, lastIsPodcast)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        return resolvedActive
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        npHandler.removeCallbacks(npTick)
        npRunning = false
        resolverIo.cancel()
        reporter?.stop()
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        mediaSession = null
        player = null
        Log.i(TAG, "PlaybackService destroyed")
        super.onDestroy()
    }
}
