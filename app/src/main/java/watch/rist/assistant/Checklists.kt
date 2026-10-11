package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import rist.v1.HomeBox
import rist.v1.ItemCheck
import rist.v1.ItemCheckBatch
import rist.v1.ItemEditBatch
import rist.v1.ItemEditReply
import rist.v1.ItemOutcome
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Ticks on to-do and shopping list items.
 *
 * A tap is the whole action: the box changes at once and the state wanted is written to a queue
 * in storage before anything is sent, so it outlives the app being closed. The queue keeps the
 * latest state per item, each with its own check id, minted once and sent unchanged on every
 * retry. While a tick is queued it is laid over every list the backend sends: a refresh never
 * undoes it.
 *
 * Ticks go to `POST /v1/device/items/edit` (schema v33), which answers each one: saved,
 * unchanged, stale (the item changed after the tap, elsewhere: the box follows the backend and
 * says so), gone, refused or failed. A backend that answers nothing for them is an older one: the
 * ticks go to `POST /v1/device/items`, whose 204 means applied.
 *
 * A tick that cannot be sent is retried with a growing wait, and on every wake. After
 * [MAX_TRIES] tries the row says that saving is unavailable, stays as the user set it, and the
 * tick keeps being retried. A tick the backend refuses for good is dropped and the box goes back.
 *
 * An accepted tick is still laid over the lists for a short while, until a list arrives that
 * agrees with it: a tile refreshes a second or two after the tick, and a list sent before then
 * would otherwise flip the box back.
 */
object Checklists {

    private const val TAG = "RistChecklist"

    /** Whether this build declares `checklist_v1`. Without it the backend sends no checklists. */
    const val SHIPPED = true

    const val COMPONENT = "checklist_v1"

    /** The most ticks one request carries; the backend refuses more. */
    const val BATCH_MAX = 50

    /** How long an accepted tick is laid over the lists while waiting for one that agrees. */
    const val HOLD_MS = 120_000L

    /** Tries before a row says saving is unavailable. The tick is still retried after. */
    const val MAX_TRIES = 5

    /** The first retry's wait; each later one doubles, up to [RETRY_MAX_MS]. */
    const val RETRY_BASE_MS = 2_000L
    const val RETRY_MAX_MS = 300_000L

    @Volatile internal var shippedForTest: Boolean? = null

    fun declared(): Boolean = shippedForTest ?: SHIPPED

    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Retries scheduled on their own; off in tests unless a test turns it on. */
    @Volatile internal var autoRetry = true

    /**
     * One item's wanted state, not yet accepted. [before] is what the item showed before the
     * first of its taps still queued, and what it goes back to if the tick is refused. [tries]
     * counts sends that had to wait; [failed] is set once they reach [MAX_TRIES].
     */
    data class Tick(
        val checkId: String, val itemId: String, val checked: Boolean, val atMs: Long, val before: Boolean,
        val tries: Int = 0, val failed: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().put("check_id", checkId).put("item_id", itemId)
            .put("checked", checked).put("at", atMs).put("before", before).put("tries", tries).put("failed", failed)

        companion object {
            fun fromJson(o: JSONObject) = Tick(
                o.optString("check_id"), o.optString("item_id"), o.optBoolean("checked"),
                o.optLong("at"), o.optBoolean("before"), o.optInt("tries"), o.optBoolean("failed"),
            )
        }
    }

    // ---- the queue ----

    @Synchronized
    fun queued(ctx: Context): List<Tick> {
        val raw = Config.itemCheckQueue(ctx)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Tick.fromJson(arr.getJSONObject(it)) }.filter { it.itemId.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(ctx: Context, ticks: List<Tick>) {
        val arr = JSONArray()
        ticks.forEach { arr.put(it.toJson()) }
        Config.setItemCheckQueue(ctx, if (ticks.isEmpty()) "" else arr.toString())
    }

    /** Accepted ticks still laid over the lists: item id to (checked, when accepted). */
    private val held = HashMap<String, Pair<Boolean, Long>>()

    /** What the backend said about an item's last tick, when it was not what was asked. */
    private val lines = HashMap<String, String>()

    /** The backend answered nothing for ticks on the edit route: it predates v33. */
    @Volatile private var legacyRoute = false

    /**
     * The user tapped [itemId] to [checked]; it showed [shown] before. Queued at once and sent
     * soon; reply cards holding the item keep the new state.
     */
    fun tap(ctx: Context, itemId: String, checked: Boolean, shown: Boolean) {
        if (itemId.isBlank()) return
        synchronized(this) {
            val q = queued(ctx)
            val prior = q.firstOrNull { it.itemId == itemId }
            val tick = Tick(java.util.UUID.randomUUID().toString(), itemId, checked, clock(), prior?.before ?: shown)
            save(ctx, q.filterNot { it.itemId == itemId } + tick)
            held.remove(itemId)
            lines.remove(itemId)
        }
        runCatching { Transcript.setItemChecked(ctx, itemId, checked) }
        runCatching { ChecklistView.reflect(mapOf(itemId to checked)) }
        flushSoon(ctx, fromTap = true)
    }

    /** What [itemId] shows: a tick still queued, else an accepted one still held, else [serverChecked]. */
    fun shown(ctx: Context, itemId: String, serverChecked: Boolean): Boolean {
        queued(ctx).firstOrNull { it.itemId == itemId }?.let { return it.checked }
        synchronized(this) {
            val h = held[itemId] ?: return serverChecked
            if (clock() - h.second > HOLD_MS) { held.remove(itemId); return serverChecked }
            return h.first
        }
    }

    /**
     * The line under [itemId]'s row, or null: saving unavailable while a tick has failed its
     * tries, else the backend's own sentence for the last tick when it was not what was asked.
     */
    fun lineFor(ctx: Context, itemId: String): String? {
        if (queued(ctx).any { it.itemId == itemId && it.failed }) return ctx.getString(R.string.checklist_save_failed)
        return synchronized(this) { lines[itemId] }
    }

    /**
     * The box list version the wake asks with. A phone that has just started declaring
     * checklists may already hold the current version, without the tiles' checklists; until a
     * list has arrived since, it asks with 0 so the backend sends the whole list at once. The
     * same holds for a phone that has just started declaring tile blocks.
     */
    fun boxesVersionToAsk(ctx: Context, held: Long): Long =
        if ((declared() && !Config.checklistBoxesSeen(ctx)) || TileBlocks.needsWholeList(ctx)) 0L else held

    /**
     * A box list arrived: from now on it was asked for with checklists declared, and an accepted
     * tick it agrees with is no longer held.
     */
    fun observe(ctx: Context, boxes: List<HomeBox>) {
        if (declared() && !Config.checklistBoxesSeen(ctx)) Config.setChecklistBoxesSeen(ctx, true)
        observe(boxes)
        learn(ctx, boxes.flatMap { it.checklistsList })
    }

    /**
     * Lists newer than what older reply cards hold (a box list, or a reply just in): each item's
     * state is written into every kept reply card holding it, and every row on screen holding it
     * shows it, with a tick not yet accepted still laid over.
     */
    fun learn(ctx: Context, lists: List<rist.v1.Checklist>, exceptEntry: Long = 0L) {
        val states = LinkedHashMap<String, Boolean>()
        for (cl in lists) for (item in cl.itemsList) if (item.id.isNotBlank()) states[item.id] = item.checked
        if (states.isEmpty()) return
        runCatching { Transcript.setItemsChecked(ctx, states, exceptEntry) }
        runCatching { ChecklistView.reflect(states.mapValues { (id, c) -> shown(ctx, id, c) }) }
    }

    @Synchronized
    internal fun observe(boxes: List<HomeBox>) {
        if (held.isEmpty()) return
        val now = clock()
        held.entries.removeAll { now - it.value.second > HOLD_MS }
        for (b in boxes) {
            for (cl in b.checklistsList) for (item in cl.itemsList) {
                if (held[item.id]?.first == item.checked) held.remove(item.id)
            }
            for (block in b.blocksList) for (row in block.rowsList) {
                if (row.checkable && held[row.id]?.first == row.checked) held.remove(row.id)
            }
        }
    }

    // ---- sending ----

    /** `…/v1/device` becomes `…/v1/device/items`. */
    internal fun itemsUrl(backendUrl: String): String? {
        val base = backendUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val url = if (base.endsWith("/v1/device")) "$base/items" else "$base/v1/device/items"
        return url.toHttpUrlOrNull()?.toString()
    }

    sealed class Sent {
        object Accepted : Sent()
        /** The edit route's answer, one outcome per tick it knew (v33). */
        data class Answered(val reply: ItemEditReply) : Sent()
        /** Offline, the credential, or the backend briefly unable: keep the ticks. */
        data class Later(val why: String) : Sent()
        /** 402: kept, and sent once the subscription is active again. */
        data class Lapsed(val lapse: Billing.Lapse) : Sent()
        /** 409 (notes off), a malformed batch, an endpoint this backend does not have: drop and revert. */
        data class Refused(val code: Int) : Sent()
    }

    private val PROTOBUF = "application/x-protobuf".toMediaType()

    internal fun checkOf(t: Tick): ItemCheck = ItemCheck.newBuilder().setCheckId(t.checkId).setItemId(t.itemId)
        .setChecked(t.checked).setAtEpochMs(t.atMs).build()

    internal fun batchOf(ticks: List<Tick>): ItemCheckBatch =
        ItemCheckBatch.newBuilder().addAllChecks(ticks.map(::checkOf)).build()

    internal fun editBatchOf(ticks: List<Tick>): ItemEditBatch =
        ItemEditBatch.newBuilder().addAllChecks(ticks.map(::checkOf)).build()

    private fun post(http: OkHttpClient, url: String, bearer: String?, device: String, body: ByteArray, answered: Boolean): Sent {
        val request = Request.Builder().url(url)
            .post(body.toRequestBody(PROTOBUF))
            .header("Content-Type", "application/x-protobuf")
            .apply { if (answered) header("Accept", "application/x-protobuf") }
            .apply { if (bearer != null) header("Authorization", bearer) }
            .header("X-Rist-Device", device)
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful && answered -> {
                        val reply = runCatching { ItemEditReply.parseFrom(resp.body?.bytes() ?: ByteArray(0)) }.getOrNull()
                        // A 200 that cannot be read says nothing about what happened: ask again.
                        if (reply == null) Sent.Later("unreadable reply") else Sent.Answered(reply)
                    }
                    resp.isSuccessful -> Sent.Accepted
                    resp.code == Billing.PAYMENT_REQUIRED -> Sent.Lapsed(Billing.lapseWithLine(resp))
                    resp.code in setOf(401, 403, 408, 429) || resp.code >= 500 -> Sent.Later("HTTP ${resp.code}")
                    else -> Sent.Refused(resp.code)
                }
            }
        } catch (t: Throwable) {
            Sent.Later(t.javaClass.simpleName)
        }
    }

    /** The older route: 204 and no body. */
    internal fun send(http: OkHttpClient, url: String, bearer: String?, device: String, batch: ItemCheckBatch): Sent =
        post(http, url, bearer, device, batch.toByteArray(), answered = false)

    private val flushLock = Any()

    /**
     * Sends the queued ticks, [BATCH_MAX] at a time, until a batch has to wait. Blocking; call off
     * the main thread. [fromTap]: a lapse is said aloud only when the user just tapped, not on
     * every wake. Returns how many ticks left the queue.
     */
    fun flush(ctx: Context, http: OkHttpClient = Uploader.sharedClient(), fromTap: Boolean = false): Int {
        if (queued(ctx).isEmpty()) return 0
        return synchronized(flushLock) { flushOneAtATime(ctx, http, fromTap) }
    }

    private fun flushOneAtATime(ctx: Context, http: OkHttpClient, fromTap: Boolean): Int {
        val legacyUrl = itemsUrl(Config.backendUrl(ctx)) ?: return 0
        val editUrl = ItemEdits.editUrl(Config.backendUrl(ctx)) ?: return 0
        var done = 0
        while (true) {
            val batch = queued(ctx).take(BATCH_MAX)
            if (batch.isEmpty()) break
            val bearer = Uploader.bearer(ctx)
            val device = Config.deviceId(ctx)
            var legacy = batch
            if (!legacyRoute) {
                when (val out = post(http, editUrl, bearer, device, editBatchOf(batch).toByteArray(), answered = true)) {
                    is Sent.Answered -> {
                        val byOp = out.reply.outcomesList.associateBy { it.opId }
                        val (known, unknown) = batch.partition { it.checkId in byOp }
                        val (failed, answered) = known.partition { byOp[it.checkId]!!.outcome.trim().lowercase() == "failed" }
                        val gone = dequeue(ctx, answered)
                        gone.forEach { answer(ctx, it, byOp[it.checkId]!!) }
                        done += gone.size
                        if (unknown.isNotEmpty()) {
                            // An older backend drops ticks it cannot read and answers nothing for them.
                            Log.i(TAG, "the backend answered no tick; using the older route")
                            legacyRoute = true
                        }
                        if (failed.isNotEmpty()) {
                            Log.i(TAG, "${failed.size} tick(s) wait (failed)")
                            tried(ctx, failed)
                            if (unknown.isEmpty()) return done
                        }
                        if (unknown.isEmpty()) continue
                        legacy = unknown
                    }
                    is Sent.Refused -> {
                        // No such route, or it cannot read ticks: the older route takes them.
                        Log.i(TAG, "the edit route refused ticks (HTTP ${out.code}); using the older route")
                        legacyRoute = true
                    }
                    is Sent.Later -> {
                        Log.i(TAG, "${batch.size} tick(s) wait (${out.why})")
                        tried(ctx, batch)
                        return done
                    }
                    is Sent.Lapsed -> {
                        lapsed(ctx, batch, out, fromTap)
                        return done
                    }
                    is Sent.Accepted -> Unit
                }
            }
            when (val out = send(http, legacyUrl, bearer, device, batchOf(legacy))) {
                is Sent.Later -> {
                    Log.i(TAG, "${legacy.size} tick(s) wait (${out.why})")
                    tried(ctx, legacy)
                    return done
                }
                is Sent.Lapsed -> {
                    lapsed(ctx, legacy, out, fromTap)
                    return done
                }
                is Sent.Accepted, is Sent.Answered -> {
                    val gone = dequeue(ctx, legacy)
                    synchronized(this) { gone.forEach { held[it.itemId] = it.checked to clock() } }
                    if (gone.isNotEmpty()) announce(ctx)
                    done += legacy.size
                }
                is Sent.Refused -> {
                    Log.w(TAG, "${legacy.size} tick(s) refused with HTTP ${out.code}; dropped")
                    val gone = dequeue(ctx, legacy)
                    gone.forEach { revert(ctx, it, "") }
                    if (gone.isNotEmpty()) toast(ctx, ctx.getString(R.string.checklist_save_failed))
                    done += legacy.size
                }
            }
        }
        return done
    }

    private fun lapsed(ctx: Context, ticks: List<Tick>, out: Sent.Lapsed, fromTap: Boolean) {
        Log.i(TAG, "${ticks.size} tick(s) wait for the subscription (${out.lapse.reason})")
        runCatching { Billing.onLapsed(ctx, out.lapse) }
        if (fromTap) toast(ctx, Billing.lineFor(out.lapse))
    }

    /**
     * What happened to [t], as the backend says. saved/unchanged: held until a list agrees;
     * stale: the box follows the backend's state and says why; gone: says so; refused (or
     * anything new): the box goes back and says why.
     */
    internal fun answer(ctx: Context, t: Tick, o: ItemOutcome) {
        val outcome = o.outcome.trim().lowercase()
        val said = o.note.trim()
        Log.i(TAG, "tick ${t.checkId.take(8)}: $outcome")
        when (outcome) {
            "saved", "unchanged" -> synchronized(this) {
                held[t.itemId] = o.checked to clock()
                lines.remove(t.itemId)
            }
            "stale" -> {
                synchronized(this) {
                    held[t.itemId] = o.checked to clock()
                    lines[t.itemId] = said.ifEmpty { ctx.getString(R.string.checklist_save_failed) }
                }
                runCatching { Transcript.setItemChecked(ctx, t.itemId, o.checked) }
                ChecklistView.reflect(mapOf(t.itemId to o.checked))
                if (watching() == 0) toast(ctx, said)
            }
            "gone" -> {
                synchronized(this) {
                    held.remove(t.itemId)
                    lines[t.itemId] = said.ifEmpty { ctx.getString(R.string.checklist_save_failed) }
                }
                if (watching() == 0) toast(ctx, said)
            }
            else -> revert(ctx, t, said)
        }
        announce(ctx)
    }

    private fun revert(ctx: Context, t: Tick, said: String) {
        synchronized(this) {
            held.remove(t.itemId)
            lines[t.itemId] = said.ifEmpty { ctx.getString(R.string.checklist_save_failed) }
        }
        runCatching { Transcript.setItemChecked(ctx, t.itemId, t.before) }
        ChecklistView.reverted(t.itemId, t.before)
        if (said.isNotBlank()) toast(ctx, said)
        announce(ctx)
    }

    /**
     * [ticks] had to wait: one more try each. One that reaches [MAX_TRIES] says saving is
     * unavailable and stays as the user set it; all are retried after a growing wait.
     */
    private fun tried(ctx: Context, ticks: List<Tick>) {
        val ids = ticks.map { it.checkId }.toSet()
        var newlyFailed = false
        synchronized(this) {
            save(ctx, queued(ctx).map {
                if (it.checkId !in ids) it else {
                    val tries = it.tries + 1
                    val failed = tries >= MAX_TRIES
                    if (failed && !it.failed) newlyFailed = true
                    it.copy(tries = tries, failed = failed)
                }
            })
        }
        if (newlyFailed) {
            if (watching() == 0) toast(ctx, ctx.getString(R.string.checklist_save_failed))
            announce(ctx)
        }
        scheduleRetry(ctx)
    }

    /** How long to wait before the next try, after [tries] tries. */
    internal fun retryDelayMs(tries: Int): Long {
        val shift = (tries - 1).coerceIn(0, 20)
        return (RETRY_BASE_MS shl shift).coerceAtMost(RETRY_MAX_MS)
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "rist-checklist-retry").apply { isDaemon = true }
    }
    private var retry: ScheduledFuture<*>? = null

    @Synchronized
    private fun scheduleRetry(ctx: Context) {
        if (!autoRetry) return
        val q = queued(ctx)
        if (q.isEmpty()) return
        if (retry?.isDone == false) return
        val app = ctx.applicationContext
        val wait = retryDelayMs(q.minOf { it.tries })
        retry = scheduler.schedule({ flushSoon(app) }, wait, TimeUnit.MILLISECONDS)
    }

    /**
     * Takes [sent] off the queue and returns the ticks that went. A tick tapped again while it was
     * in flight has a new check id and stays: the newer tap is still to be sent.
     */
    @Synchronized
    private fun dequeue(ctx: Context, sent: List<Tick>): List<Tick> {
        if (sent.isEmpty()) return emptyList()
        val ids = sent.map { it.checkId }.toSet()
        val (gone, kept) = queued(ctx).partition { it.checkId in ids }
        save(ctx, kept)
        return gone
    }

    private fun watching(): Int = ItemEdits.watchers()

    /** An open tile view redraws: the lines under rows changed. */
    private fun announce(ctx: Context) = runCatching {
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx.applicationContext)
            .sendBroadcast(Intent(ItemEdits.ACTION_CHANGED))
    }

    private fun toast(ctx: Context, line: String) {
        if (line.isBlank()) return
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post { runCatching { Toast.makeText(app, line, Toast.LENGTH_SHORT).show() } }
    }

    private val flusher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rist-checklist").apply { isDaemon = true }
    }

    /** [flush] on a background thread; a later call waits behind an earlier one. */
    fun flushSoon(ctx: Context, fromTap: Boolean = false) {
        val app = ctx.applicationContext
        flusher.execute { runCatching { flush(app, fromTap = fromTap) }.onFailure { Log.w(TAG, "flush failed", it) } }
    }

    // ---- test seams ----

    @Synchronized
    internal fun resetForTest(ctx: Context) {
        shippedForTest = null
        clock = { System.currentTimeMillis() }
        autoRetry = false
        retry?.cancel(false)
        retry = null
        legacyRoute = false
        held.clear()
        lines.clear()
        Config.setItemCheckQueue(ctx, "")
        Config.setChecklistBoxesSeen(ctx, false)
    }

    /** What a new process starts with: memory gone, the queue in storage kept. */
    @Synchronized
    internal fun forgetForTest() {
        retry?.cancel(false)
        retry = null
        legacyRoute = false
        held.clear()
        lines.clear()
    }

    internal fun awaitFlushForTest() {
        runCatching { flusher.submit { }.get() }
    }

    /** Waits for a scheduled retry to run (and the flush it starts). */
    internal fun awaitRetryForTest(): Boolean {
        val r = synchronized(this) { retry } ?: return false
        runCatching { r.get(10, TimeUnit.SECONDS) }
        awaitFlushForTest()
        return true
    }
}
