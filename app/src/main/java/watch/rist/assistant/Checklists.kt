package watch.rist.assistant

import android.content.Context
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
import java.util.concurrent.Executors

/**
 * Ticks on to-do and shopping list items.
 *
 * A tap is the whole action: the box changes at once and the state wanted is queued for
 * `POST /v1/device/items`, which answers with a status and nothing else. The queue keeps the
 * latest state per item, so several taps on one item send one tick. A tick the backend refuses
 * for good is dropped and the box goes back to what it showed before the tap.
 *
 * A tick the backend accepted is still laid over the lists for a short while, until a list
 * arrives that agrees with it: a tile refreshes a second or two after the tick, and a list sent
 * before then would otherwise flip the box back.
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

    @Volatile internal var shippedForTest: Boolean? = null

    fun declared(): Boolean = shippedForTest ?: SHIPPED

    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * One item's wanted state, not yet accepted. [before] is what the item showed before the
     * first of its taps still queued, and what it goes back to if the tick is refused.
     */
    data class Tick(val checkId: String, val itemId: String, val checked: Boolean, val atMs: Long, val before: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("check_id", checkId).put("item_id", itemId)
            .put("checked", checked).put("at", atMs).put("before", before)

        companion object {
            fun fromJson(o: JSONObject) = Tick(
                o.optString("check_id"), o.optString("item_id"), o.optBoolean("checked"),
                o.optLong("at"), o.optBoolean("before"),
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
        }
        runCatching { Transcript.setItemChecked(ctx, itemId, checked) }
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
     * The box list version the wake asks with. A phone that has just started declaring
     * checklists may already hold the current version, without the tiles' checklists; until a
     * list has arrived since, it asks with 0 so the backend sends the whole list at once.
     */
    fun boxesVersionToAsk(ctx: Context, held: Long): Long =
        if (declared() && !Config.checklistBoxesSeen(ctx)) 0L else held

    /**
     * A box list arrived: from now on it was asked for with checklists declared, and an accepted
     * tick it agrees with is no longer held.
     */
    fun observe(ctx: Context, boxes: List<HomeBox>) {
        if (declared() && !Config.checklistBoxesSeen(ctx)) Config.setChecklistBoxesSeen(ctx, true)
        observe(boxes)
    }

    @Synchronized
    internal fun observe(boxes: List<HomeBox>) {
        if (held.isEmpty()) return
        val now = clock()
        held.entries.removeAll { now - it.value.second > HOLD_MS }
        for (b in boxes) for (cl in b.checklistsList) for (item in cl.itemsList) {
            if (held[item.id]?.first == item.checked) held.remove(item.id)
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
        /** Offline, the credential, or the backend briefly unable: keep the ticks. */
        data class Later(val why: String) : Sent()
        /** 402: kept, and sent once the subscription is active again. */
        data class Lapsed(val lapse: Billing.Lapse) : Sent()
        /** 409 (notes off), a malformed batch, an endpoint this backend does not have: drop and revert. */
        data class Refused(val code: Int) : Sent()
    }

    private val PROTOBUF = "application/x-protobuf".toMediaType()

    internal fun batchOf(ticks: List<Tick>): ItemCheckBatch =
        ItemCheckBatch.newBuilder().addAllChecks(ticks.map {
            ItemCheck.newBuilder().setCheckId(it.checkId).setItemId(it.itemId)
                .setChecked(it.checked).setAtEpochMs(it.atMs).build()
        }).build()

    internal fun send(http: OkHttpClient, url: String, bearer: String?, device: String, batch: ItemCheckBatch): Sent {
        val request = Request.Builder().url(url)
            .post(batch.toByteArray().toRequestBody(PROTOBUF))
            .header("Content-Type", "application/x-protobuf")
            .apply { if (bearer != null) header("Authorization", bearer) }
            .header("X-Rist-Device", device)
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                when {
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
        val url = itemsUrl(Config.backendUrl(ctx)) ?: return 0
        var done = 0
        while (true) {
            val batch = queued(ctx).take(BATCH_MAX)
            if (batch.isEmpty()) break
            when (val out = send(http, url, Uploader.bearer(ctx), Config.deviceId(ctx), batchOf(batch))) {
                is Sent.Later -> {
                    Log.i(TAG, "${batch.size} tick(s) wait (${out.why})")
                    return done
                }
                is Sent.Lapsed -> {
                    Log.i(TAG, "${batch.size} tick(s) wait for the subscription (${out.lapse.reason})")
                    runCatching { Billing.onLapsed(ctx, out.lapse) }
                    if (fromTap) note(ctx, Billing.lineFor(out.lapse))
                    return done
                }
                is Sent.Accepted -> {
                    val gone = dequeue(ctx, batch)
                    synchronized(this) { gone.forEach { held[it.itemId] = it.checked to clock() } }
                    done += batch.size
                }
                is Sent.Refused -> {
                    Log.w(TAG, "${batch.size} tick(s) refused with HTTP ${out.code}; dropped")
                    val gone = dequeue(ctx, batch)
                    for (t in gone) {
                        runCatching { Transcript.setItemChecked(ctx, t.itemId, t.before) }
                        ChecklistView.reverted(t.itemId, t.before)
                    }
                    if (gone.isNotEmpty()) note(ctx, ctx.getString(R.string.checklist_save_failed))
                    done += batch.size
                }
            }
        }
        return done
    }

    /**
     * Takes [sent] off the queue and returns the ticks that went. A tick tapped again while it was
     * in flight has a new check id and stays: the newer tap is still to be sent.
     */
    @Synchronized
    private fun dequeue(ctx: Context, sent: List<Tick>): List<Tick> {
        val ids = sent.map { it.checkId }.toSet()
        val (gone, kept) = queued(ctx).partition { it.checkId in ids }
        save(ctx, kept)
        return gone
    }

    private fun note(ctx: Context, line: String) {
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
        held.clear()
        Config.setItemCheckQueue(ctx, "")
        Config.setChecklistBoxesSeen(ctx, false)
    }

    internal fun awaitFlushForTest() {
        runCatching { flusher.submit { }.get() }
    }
}
