package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import rist.v1.HomeBox

/**
 * A tile asked for from the add sheet, shown as a placeholder until the backend's list has it.
 *
 * Nothing extra is sent: the add turn goes as it always did, and the wait is ended only by what
 * comes back. A list holding a box that was not there at the submit ends it (the newest such box);
 * a turn that failed, or whose reply carried a list without a new box, removes it with a word; so
 * does [CAP_MS] passing. Long-pressing the placeholder drops it here only.
 *
 * The waits belong to the app, like [BoxRefresh], so leaving and reopening home finds them still
 * there; they are not kept across a restart.
 */
object BoxCreate {

    /** How long a placeholder waits for its box before saying it could not be made. */
    const val CAP_MS = 90_000L

    /** When the wake is polled while a placeholder waits: at once, 3, 6, 10, 20 s, then every 30 s. */
    val POLLS_MS: LongArray = longArrayOf(0L, 3_000L, 6_000L, 10_000L, 20_000L) +
        generateSequence(50_000L) { it + 30_000L }.takeWhile { it < CAP_MS }.toList()

    /** Words of the request shown under "New tile". */
    const val DETAIL_WORDS = 4

    class Pending internal constructor(
        val key: Long,
        val words: String,
        val known: Set<String>,
        val startedUptimeMs: Long,
    ) {
        val deadlineUptimeMs: Long = startedUptimeMs + (capMsForTest ?: CAP_MS)
    }

    private var nextKey = 1L
    private val pending = ArrayList<Pending>()
    private var handler: Handler? = null

    /** The main thread's handler; made again if the main looper is a new one (as between tests). */
    private val main: Handler
        @Synchronized get() = handler?.takeIf { it.looper === Looper.getMainLooper() }
            ?: Handler(Looper.getMainLooper()).also { handler = it }
    @Volatile private var appCtx: Context? = null

    /** Lets a test wait less than [CAP_MS]; null = [CAP_MS]. */
    @Volatile internal var capMsForTest: Long? = null

    /** Lets a test see the wake asked for; null = poll the wake now. */
    @Volatile internal var kickForTest: (() -> Unit)? = null

    /** Lets a test see the word shown when a placeholder goes without its box. */
    @Volatile internal var saidForTest: ((Int) -> Unit)? = null

    @Synchronized fun waiting(): List<Pending> = pending.toList()

    @Synchronized fun isWaiting(p: Pending): Boolean = pending.any { it === p }

    /** The first few words of the request, for the placeholder's detail line. */
    fun detailOf(words: String): String {
        val w = words.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val head = w.take(DETAIL_WORDS).joinToString(" ")
        return if (w.size > DETAIL_WORDS) "$head…" else head
    }

    /** Puts up a placeholder for a tile asked for in [words], and starts watching for its box. */
    fun start(ctx: Context, words: String): Pending {
        val app = ctx.applicationContext
        appCtx = app
        val p = synchronized(this) {
            Pending(nextKey++, words.trim(), HomeBoxes.boxes(app).map { it.id }.toSet(), SystemClock.uptimeMillis())
                .also { pending += it }
        }
        main.postAtTime({ fail(p) }, p, p.deadlineUptimeMs)
        for (at in POLLS_MS) {
            if (p.startedUptimeMs + at >= p.deadlineUptimeMs) break
            val poll = Runnable { if (isWaiting(p)) pollWakeNow() }
            if (at == 0L) poll.run() else main.postAtTime(poll, p, p.startedUptimeMs + at)
        }
        announce()
        return p
    }

    private fun pollWakeNow() { kickForTest?.invoke() ?: WakeLoop.kick() }

    /**
     * Ends each placeholder whose box has arrived. Oldest first, each takes the newest box that was
     * not there at its submit and no earlier placeholder took. Called wherever a list is seen.
     */
    fun observe(boxes: List<HomeBox>) {
        val ended = synchronized(this) {
            if (pending.isEmpty()) return
            val taken = HashSet<String>()
            pending.filter { p ->
                val fresh = boxes.lastOrNull { it.id !in p.known && it.id !in taken } ?: return@filter false
                taken += fresh.id
                true
            }.also { pending.removeAll(it.toSet()) }
        }
        if (ended.isEmpty()) return
        ended.forEach { main.removeCallbacksAndMessages(it) }
        announce()
    }

    /**
     * The add turn for [p] ended. [replied] false = no reply at all. A reply that carried a box list
     * already passed through [observe]; if [p] still waits, that list had no new box, so it failed,
     * unless the reply asks the user something back, when the box may still come after the answer.
     */
    fun turnEnded(ctx: Context, p: Pending, replied: Boolean, carriedBoxes: Boolean, expectsReply: Boolean) {
        observe(HomeBoxes.boxes(ctx))
        if (!isWaiting(p)) return
        if (!replied || (carriedBoxes && !expectsReply)) fail(p)
    }

    /** Removes [p] without a word: the user dismissed it. */
    fun cancel(p: Pending) {
        if (!drop(p)) return
        announce()
    }

    private fun fail(p: Pending) {
        if (!drop(p)) return
        announce()
        val tell = Runnable {
            saidForTest?.invoke(R.string.boxes_create_failed) ?: appCtx?.let {
                runCatching { Toast.makeText(it, R.string.boxes_create_failed, Toast.LENGTH_SHORT).show() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) tell.run() else main.post(tell)
    }

    private fun drop(p: Pending): Boolean {
        synchronized(this) { if (!pending.remove(p)) return false }
        main.removeCallbacksAndMessages(p)
        return true
    }

    /** Tells the home row and the All grid to redraw. */
    private fun announce() {
        val ctx = appCtx ?: return
        val send = Runnable {
            runCatching {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx)
                    .sendBroadcast(Intent(HomeBoxes.ACTION_CHANGED))
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) send.run() else main.post(send)
    }

    @Synchronized internal fun resetForTest() {
        pending.forEach { main.removeCallbacksAndMessages(it) }
        pending.clear()
        kickForTest = null
        capMsForTest = null
        saidForTest = null
    }
}
