package watch.rist.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import rist.v1.BoxEdit
import rist.v1.HomeBox
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Asking for one display box to be brought up to date now, from its expanded view.
 *
 * Unlike the other touch edits a refresh is never queued: it means "now" or nothing, so offline it
 * is not sent at all. The backend answers the request at once and later sends a list in which the
 * box's update time has risen above what it was at the tap; that is what ends the wait.
 *
 * The wait belongs to the app, not to the expanded view: closing the view and opening it again
 * finds the same wait still spinning (or the same rest after one), and the wait still times out
 * when no view is open.
 */
object BoxRefresh {

    private const val TAG = "RistBoxRefresh"

    /** How long to wait for the box's update time to move before saying it could not update. */
    const val TIMEOUT_MS = 30_000L

    /** After a refresh that worked, the button rests this long: the backend allows one a minute. */
    const val COOLDOWN_MS = 60_000L

    /** When the wake is polled after the backend took the refresh, while the box has not come back. */
    val POLLS_MS = longArrayOf(0L, 3_000L, 6_000L, 10_000L, 20_000L)

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

    // ---- the waits, one per box, for the whole app ----

    /** A refresh asked for [id] while its update time was [beforeS], with its timers keyed on itself. */
    class Pending internal constructor(val id: String, val beforeS: Long, val startedUptimeMs: Long) {
        val deadlineUptimeMs: Long get() = startedUptimeMs + TIMEOUT_MS
    }

    /** Told on the main thread when a box's wait ends; [said] is a word to show, or null when it worked. */
    fun interface Watcher { fun ended(id: String, said: Int?) }

    private val pending = HashMap<String, Pending>()
    private val watchers = CopyOnWriteArraySet<Watcher>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun watch(w: Watcher) { watchers += w }
    fun unwatch(w: Watcher) { watchers -= w }

    @Synchronized fun pendingFor(id: String): Pending? = pending[id]

    fun isPending(id: String): Boolean = pendingFor(id) != null

    /**
     * Starts a refresh of [b]: sends it, and waits for the box to come back newer. False when one
     * is already waiting or the box is still resting after the last one.
     */
    fun start(ctx: Context, b: HomeBox): Boolean {
        val id = b.id
        val p = synchronized(this) {
            if (!offered(b) || pending.containsKey(id) || restingMs(id, System.currentTimeMillis()) > 0) return false
            Pending(id, b.updatedAtEpochS.toLong(), SystemClock.uptimeMillis()).also { pending[id] = it }
        }
        val app = ctx.applicationContext
        main.postAtTime({ end(p, R.string.boxes_refresh_failed) }, p, p.deadlineUptimeMs)
        requestSoon(app, id) { out ->
            main.post {
                when (out) {
                    is Outcome.Asked -> {
                        observe(HomeBoxes.boxes(app))
                        if (pendingFor(id) === p) pollWhileWaiting(p)
                    }
                    is Outcome.Offline -> end(p, R.string.boxes_refresh_offline)
                    is Outcome.Failed -> end(p, R.string.boxes_refresh_failed)
                }
            }
        }
        return true
    }

    /**
     * Polls the wake now and again at each of [POLLS_MS] before the deadline, while [p] waits. Each
     * one only kicks the one wake loop, which drops kicks that come during a poll, so these never
     * open a second hold beside it.
     */
    private fun pollWhileWaiting(p: Pending) {
        val base = SystemClock.uptimeMillis()
        for (at in POLLS_MS) {
            val whenMs = base + at
            if (whenMs >= p.deadlineUptimeMs) break
            val poll = Runnable { if (pendingFor(p.id) === p) pollWakeNow() }
            if (at == 0L) poll.run() else main.postAtTime(poll, p, whenMs)
        }
    }

    /**
     * Ends each wait whose box came back newer than at its tap, or has gone. Called wherever a list
     * is seen: when one is taken in, and when the home row or the expanded view draws.
     */
    fun observe(boxes: List<HomeBox>) {
        val ended = synchronized(this) {
            if (pending.isEmpty()) return
            pending.values.filter { p ->
                val b = boxes.firstOrNull { it.id == p.id }
                b == null || done(b, p.beforeS)
            }.onEach { p ->
                if (boxes.any { it.id == p.id }) markDone(p.id, System.currentTimeMillis())
            }
        }
        ended.forEach { end(it, null) }
    }

    /** Ends [p] if it is still the box's wait: its timers stop and any open view is told. */
    private fun end(p: Pending, said: Int?) {
        synchronized(this) {
            if (pending[p.id] !== p) return
            pending.remove(p.id)
        }
        main.removeCallbacksAndMessages(p)
        val tell = Runnable { watchers.forEach { it.ended(p.id, said) } }
        if (Looper.myLooper() == Looper.getMainLooper()) tell.run() else main.post(tell)
    }

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
        pending.values.forEach { main.removeCallbacksAndMessages(it) }
        pending.clear()
        watchers.clear()
        lastDone.clear()
        onlineForTest = null
        motionForTest = null
        kickForTest = null
    }

    internal fun awaitForTest() { runCatching { worker.submit { }.get() } }
}
