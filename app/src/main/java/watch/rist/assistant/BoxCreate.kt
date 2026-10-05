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
 * A display tile is asked for by an add turn; a one-tap tile by a touch edit ([HomeBoxes.addEdit]),
 * which counts here as that tile's turn. Nothing extra is sent, and the wait is ended only by what
 * comes back. A touch edit that has to wait for a connection takes its placeholder down with a word:
 * the tile comes when the queued edit is sent. A list holding a box that was not there at the submit ends it: the new box whose
 * source words are the request's own, else the newest; a box another placeholder took is never
 * taken again. A turn that failed removes it with a word, unless the connection dropped after the
 * request was sent, when the box may still come; the cap never ends a wait whose turn is still
 * out (up to [TURN_CAP_MS]), since that turn's reply decides. A reply whose list has no new box yet
 * leaves it [GRACE_MS] more, since the backend may make the box just after replying; a reply that
 * asks something back leaves it to the cap and then goes without a word. Otherwise [CAP_MS]
 * passing removes it with a word. Long-pressing the placeholder drops it here only.
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

    /** How long a placeholder still waits after a reply whose list had no new box. */
    const val GRACE_MS = 30_000L

    /** The longest a placeholder waits on a turn still out: its read timeout, then a grace. */
    const val TURN_CAP_MS = Uploader.TURN_READ_TIMEOUT_S * 1_000L + GRACE_MS

    /** When the wake is polled after such a reply. */
    val GRACE_POLLS_MS: LongArray = longArrayOf(0L, 3_000L, 10_000L, 20_000L)

    /** Words of the request shown under "New tile". */
    const val DETAIL_WORDS = 4

    class Pending internal constructor(
        val key: Long,
        val words: String,
        known: Set<String>,
        val startedUptimeMs: Long,
    ) {
        /** Ids that are not this placeholder's box: there at the submit, or taken by another. */
        internal val known: MutableSet<String> = HashSet(known)
        /** The cap from the submit; the deadline moves off it for a turn still out or a grace. */
        val capUptimeMs: Long = startedUptimeMs + (capMsForTest ?: CAP_MS)
        @Volatile var deadlineUptimeMs: Long = capUptimeMs
            internal set
        /** The reply asked the user something: the box waits on them, so the cap says nothing. */
        @Volatile internal var askedBack = false
        /** The add turn has not ended yet. */
        @Volatile internal var turnOpen = true
        /** Token for the cap's callback alone, so it can be moved without dropping the polls. */
        internal val capToken = Any()
        /** The touch edit adding this one-tap tile; null for a tile asked for by a turn. */
        @Volatile var editId: String? = null
            internal set
    }

    /** Box ids there when each add edit was made, kept until the edit is answered. */
    private val submitted = HashMap<String, Set<String>>()

    private var nextKey = 1L
    private val pending = ArrayList<Pending>()
    private var handler: Handler? = null

    /**
     * The main thread's handler; made again if the main looper is a new one (as between tests).
     * Async: the polls and the cap need no frame, so they never queue behind a layout's barrier.
     */
    private val main: Handler
        @Synchronized get() = handler?.takeIf { it.looper === Looper.getMainLooper() }
            ?: Handler.createAsync(Looper.getMainLooper()).also { handler = it }
    @Volatile private var appCtx: Context? = null

    /** Lets a test wait less than [CAP_MS]; null = [CAP_MS]. */
    @Volatile internal var capMsForTest: Long? = null

    /** Lets a test see the wake asked for; null = poll the wake now. */
    @Volatile internal var kickForTest: (() -> Unit)? = null

    /** Lets a test see the word shown when a placeholder goes without its box. */
    @Volatile internal var saidForTest: ((Int) -> Unit)? = null

    /** Lets a test see a sentence shown in place of a word (the 402's). */
    @Volatile internal var toldForTest: ((String) -> Unit)? = null

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
        main.postAtTime({ capped(p) }, p.capToken, p.deadlineUptimeMs)
        schedulePolls(p, p.startedUptimeMs, POLLS_MS)
        announce()
        return p
    }

    /** [start] for a one-tap tile added by the touch edit [editId]; call before queuing it. */
    fun startAdd(ctx: Context, words: String, editId: String): Pending {
        synchronized(this) { submitted[editId] = HomeBoxes.boxes(ctx).map { it.id }.toSet() }
        return start(ctx, words).also { it.editId = editId }
    }

    /** The ids there when [editId] was made, or null when that is not known (since a restart). */
    @Synchronized fun idsAtSubmit(editId: String): Set<String>? = submitted[editId]

    @Synchronized private fun forEdit(editId: String): Pending? = pending.firstOrNull { it.editId == editId }

    /** The add edit [editId] must wait for a connection: its placeholder goes, saying so. */
    fun addWaits(editId: String) {
        val p = forEdit(editId) ?: return
        if (!drop(p)) return
        announce()
        say(R.string.boxes_add_queued)
    }

    /** The add edit [editId] waits for the subscription: its placeholder goes, with the backend's [line]. */
    fun addLapsed(editId: String, line: String) {
        val p = forEdit(editId) ?: return
        if (!drop(p)) return
        announce()
        sayText(line)
    }

    /**
     * The add edit [editId] was accepted, its reply's list taken in. If that list did not end the
     * wait, the box may still come (the backend can make it just after replying): the wait goes on
     * a grace more with the wake polled, and then goes with a word. Nothing is ever sent for it.
     */
    fun addAccepted(ctx: Context, editId: String) {
        synchronized(this) { submitted.remove(editId) }
        val p = forEdit(editId) ?: return
        turnEnded(ctx, p, replied = true, carriedBoxes = true, expectsReply = false)
    }

    /** The add edit [editId] was refused for good: its placeholder goes with a word. */
    fun addRefused(editId: String) {
        synchronized(this) { submitted.remove(editId) }
        val p = forEdit(editId) ?: return
        fail(p)
    }

    private fun schedulePolls(p: Pending, fromUptimeMs: Long, offsets: LongArray) {
        for (at in offsets) {
            if (fromUptimeMs + at >= p.deadlineUptimeMs) break
            val poll = Runnable { if (isWaiting(p)) pollWakeNow() }
            if (at == 0L) poll.run() else main.postAtTime(poll, p, fromUptimeMs + at)
        }
    }

    /**
     * The cap passed. While the add turn is still out its reply decides, so the wait goes on, but
     * only to [TURN_CAP_MS]: a turn whose end is never heard (home closed mid-turn) still ends.
     */
    private fun capped(p: Pending) {
        val hard = p.startedUptimeMs + (turnCapMsForTest ?: TURN_CAP_MS)
        if (p.turnOpen && SystemClock.uptimeMillis() < hard) { rearm(p, hard); return }
        if (p.askedBack) cancel(p) else fail(p)
    }

    /** Lets a test wait less than [TURN_CAP_MS]; null = [TURN_CAP_MS]. */
    @Volatile internal var turnCapMsForTest: Long? = null

    private fun pollWakeNow() { kickForTest?.invoke() ?: WakeLoop.kick() }

    /**
     * Ends each placeholder whose box has arrived. Oldest first, each takes the newest box that was
     * not there at its submit and no earlier placeholder took. Called wherever a list is seen.
     */
    fun observe(boxes: List<HomeBox>) {
        val ended = synchronized(this) {
            if (pending.isEmpty()) return
            val taken = HashSet<String>()
            val mine = HashMap<Pending, HomeBox>()
            // A new box whose source words are a placeholder's own request is that one's first.
            for (p in pending) {
                val own = boxes.lastOrNull {
                    it.id !in p.known && it.id !in taken && sameWords(it.sourceWords, p.words)
                } ?: continue
                taken += own.id
                mine[p] = own
            }
            for (p in pending) {
                if (p in mine) continue
                val fresh = boxes.lastOrNull {
                    it.id !in p.known && it.id !in taken && !ownedByAnother(it, p)
                } ?: continue
                taken += fresh.id
                mine[p] = fresh
            }
            val done = pending.filter { it in mine }
            pending.removeAll(done.toSet())
            // A box one placeholder took is never another's, on this list or a later one.
            pending.forEach { it.known += taken }
            done
        }
        if (ended.isEmpty()) return
        ended.forEach { dropCallbacks(it) }
        announce()
    }

    /** Whether [b]'s source words are those of a placeholder other than [p]. */
    private fun ownedByAnother(b: HomeBox, p: Pending): Boolean =
        b.sourceWords.isNotBlank() && !sameWords(b.sourceWords, p.words) &&
            pending.any { it !== p && sameWords(b.sourceWords, it.words) }

    internal fun sameWords(a: String, b: String): Boolean {
        fun norm(s: String) = s.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val x = norm(a)
        return x.isNotEmpty() && x == norm(b)
    }

    /**
     * The add turn for [p] ended. [replied] false = no reply at all: it failed, unless the request
     * [mayHaveHappened] (it reached the backend, which keeps working after the socket drops), when
     * the wait goes on. A reply that carried a box list already passed through [observe]; if [p]
     * still waits that list had no new box, which is not yet a failure: the backend may make the
     * box just after replying, or the list may be one from before it, so [p] waits [GRACE_MS] more
     * with the wake polled. A reply that asks the user something back waits to the cap, quietly.
     */
    fun turnEnded(
        ctx: Context, p: Pending, replied: Boolean, carriedBoxes: Boolean, expectsReply: Boolean,
        mayHaveHappened: Boolean = false,
    ) {
        p.turnOpen = false
        observe(HomeBoxes.boxes(ctx))
        if (!isWaiting(p)) return
        if (!replied && !mayHaveHappened) { fail(p); return }
        if (replied && expectsReply) p.askedBack = true
        val now = SystemClock.uptimeMillis()
        val grace = now + (graceMsForTest ?: GRACE_MS)
        // A turn may outlast the cap (turns read for minutes); its end then starts a grace of its own.
        val capPassed = now >= p.capUptimeMs
        if (!capPassed && !(replied && carriedBoxes && !expectsReply)) return
        rearm(p, if (capPassed) grace else minOf(p.capUptimeMs, grace))
        schedulePolls(p, now, GRACE_POLLS_MS)
    }

    private fun rearm(p: Pending, atUptimeMs: Long) {
        main.removeCallbacksAndMessages(p.capToken)
        p.deadlineUptimeMs = atUptimeMs
        main.postAtTime({ capped(p) }, p.capToken, atUptimeMs)
    }

    /** Lets a test wait less than [GRACE_MS]; null = [GRACE_MS]. */
    @Volatile internal var graceMsForTest: Long? = null

    /** Removes [p] without a word: the user dismissed it. */
    fun cancel(p: Pending) {
        if (!drop(p)) return
        announce()
    }

    private fun fail(p: Pending) {
        if (!drop(p)) return
        announce()
        say(R.string.boxes_create_failed)
    }

    private fun say(res: Int) {
        val tell = Runnable {
            saidForTest?.invoke(res) ?: appCtx?.let {
                runCatching { Toast.makeText(it, res, Toast.LENGTH_SHORT).show() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) tell.run() else main.post(tell)
    }

    private fun sayText(text: String) {
        val tell = Runnable {
            toldForTest?.invoke(text) ?: appCtx?.let {
                runCatching { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) tell.run() else main.post(tell)
    }

    private fun drop(p: Pending): Boolean {
        synchronized(this) { if (!pending.remove(p)) return false }
        dropCallbacks(p)
        return true
    }

    private fun dropCallbacks(p: Pending) {
        main.removeCallbacksAndMessages(p)
        main.removeCallbacksAndMessages(p.capToken)
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
        pending.forEach { dropCallbacks(it) }
        pending.clear()
        submitted.clear()
        graceMsForTest = null
        turnCapMsForTest = null
        kickForTest = null
        capMsForTest = null
        saidForTest = null
        toldForTest = null
    }
}
