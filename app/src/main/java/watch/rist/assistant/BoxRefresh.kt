package watch.rist.assistant

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import rist.v1.BoxEdit
import rist.v1.HomeBox
import java.util.concurrent.Executors

/**
 * Asking for one display box to be brought up to date now, from its expanded view.
 *
 * Unlike the other touch edits a refresh is never queued: it means "now" or nothing, so offline it
 * is not sent at all. The backend answers the request at once and later sends a list in which the
 * box's update time has risen above what it was at the tap; that is what ends the wait.
 */
object BoxRefresh {

    private const val TAG = "RistBoxRefresh"

    /** How long to wait for the box's update time to move before saying it could not update. */
    const val TIMEOUT_MS = 30_000L

    /** After a refresh that worked, the button rests this long: the backend allows one a minute. */
    const val COOLDOWN_MS = 60_000L

    /** When the wake is asked for a second time, if the box has not come back yet. */
    const val REPOLL_MS = 10_000L

    sealed class Outcome {
        /** Accepted: wait for the box's update time to move. */
        object Asked : Outcome()
        object Offline : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    /** Only a display box that is running can be refreshed: not a command box, nor one off or paused. */
    fun offered(b: HomeBox): Boolean {
        if (HomeBoxes.kindOf(b) != HomeBoxes.Kind.DISPLAY) return false
        val s = HomeBoxes.stateOf(b)
        return s != HomeBoxes.State.OFF && s != HomeBoxes.State.PAUSED
    }

    fun edit(ctx: Context, id: String): BoxEdit =
        BoxEdit.newBuilder().setEditId(HomeBoxes.newEditId()).setBaseVersion(HomeBoxes.version(ctx))
            .addRefreshIds(id).build()

    /**
     * Whether the box was brought up to date: its update time rose above what it was at the tap.
     * The backend always moves it on a refresh, even when the value is the same; comparing with the
     * box's own earlier time, not the phone's clock, keeps a clock that is off from mattering.
     */
    fun done(b: HomeBox?, beforeS: Long): Boolean = b != null && b.updatedAtEpochS.toLong() > beforeS

    /** Lets a test see the wake asked for; null = poll the wake now. */
    @Volatile internal var kickForTest: (() -> Unit)? = null

    /**
     * The refreshed box comes back on the wake, so poll it now: the gap between polls when idle is
     * far longer than the wait. A hold already open is ended by the backend when the box is done.
     */
    fun pollWakeNow() { kickForTest?.invoke() ?: WakeLoop.kick() }

    /** Lets a test say whether there is a network; null = ask the phone. */
    @Volatile internal var onlineForTest: Boolean? = null

    private fun online(ctx: Context): Boolean =
        onlineForTest ?: (OtaNetwork.current(ctx) != OtaNetwork.Suitability.None)

    /** Lets a test turn motion off or on; null = the phone's own setting. */
    @Volatile internal var motionForTest: Boolean? = null

    /** False when the user has turned animations off: then nothing spins. */
    fun motion(): Boolean = motionForTest ?: android.animation.ValueAnimator.areAnimatorsEnabled()

    // ---- the rest after a refresh that worked ----

    private val lastDone = HashMap<String, Long>()

    @Synchronized fun markDone(id: String, nowMs: Long) { lastDone[id] = nowMs }

    /** How long until the box can be refreshed again; 0 when it can be now. */
    @Synchronized fun restingMs(id: String, nowMs: Long): Long {
        val at = lastDone[id] ?: return 0
        return (at + COOLDOWN_MS - nowMs).coerceAtLeast(0)
    }

    /**
     * Sends the refresh. Blocking; call off the main thread. A list in the reply is taken in, and
     * nothing is ever left in the edit queue.
     */
    fun request(ctx: Context, id: String, http: OkHttpClient = Uploader.sharedClient()): Outcome {
        if (!online(ctx)) return Outcome.Offline
        val url = HomeBoxes.boxesUrl(Config.backendUrl(ctx)) ?: return Outcome.Failed("no backend")
        val edit = edit(ctx, id)
        return when (val out = HomeBoxes.send(http, url, Uploader.bearer(ctx), Config.deviceId(ctx), edit)) {
            is HomeBoxes.Sent.Accepted -> {
                if (out.reply.hasBoxes()) HomeBoxes.apply(ctx, out.reply.boxes)
                Outcome.Asked
            }
            is HomeBoxes.Sent.Later -> {
                Log.i(TAG, "refresh of $id not sent (${out.why})")
                if (out.why.startsWith("HTTP")) Outcome.Failed(out.why) else Outcome.Offline
            }
            is HomeBoxes.Sent.Refused -> {
                Log.w(TAG, "refresh of $id refused with HTTP ${out.code}")
                Outcome.Failed("HTTP ${out.code}")
            }
        }
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rist-box-refresh").apply { isDaemon = true }
    }

    /** [request] on a background thread; [then] gets the outcome on that thread. */
    fun requestSoon(ctx: Context, id: String, then: (Outcome) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val out = runCatching { request(app, id) }
                .getOrElse { Log.w(TAG, "refresh failed", it); Outcome.Failed(it.javaClass.simpleName) }
            then(out)
        }
    }

    @Synchronized internal fun resetForTest() {
        lastDone.clear()
        onlineForTest = null
        motionForTest = null
        kickForTest = null
    }

    internal fun awaitForTest() { runCatching { worker.submit { }.get() } }
}
