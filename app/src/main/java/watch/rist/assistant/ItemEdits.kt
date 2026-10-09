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
import rist.v1.ItemAdd
import rist.v1.ItemDelete
import rist.v1.ItemEdit
import rist.v1.ItemEditBatch
import rist.v1.ItemEditReply
import rist.v1.ItemOutcome
import rist.v1.TileRow
import java.util.concurrent.Executors

/**
 * Edits, adds and deletes made on a tile's rows.
 *
 * Each goes to `POST /v1/device/items/edit`, which answers every op with what actually happened.
 * The phone shows the user's text at once, marked as saving, and says it was saved only when the
 * answer says so. When the answer is anything else, the backend's own sentence is shown as sent,
 * and the user's words stay available in the editor. A delete waits [UNDO_MS] on the phone, with
 * Undo, before it is sent. Ops wait in a queue while offline; every op is safe to send again.
 */
object ItemEdits {

    private const val TAG = "RistItemEdit"

    /** Whether this build declares `item_edit_v1`. Without it the backend sends no edit actions. */
    const val SHIPPED = true

    const val COMPONENT = "item_edit_v1"

    const val ACTION_CHANGED = "watch.rist.assistant.ITEM_EDITS_CHANGED"

    /** The most ops one request carries; the backend refuses more. */
    const val BATCH_MAX = 20

    /** How long a deleted row offers Undo before the delete is sent. */
    const val UNDO_MS = 5_000L

    /** How long "Saved" stays under a row. */
    const val SAVED_MS = 3_000L

    /** How long saved text is laid over the rows while waiting for a list that holds it. */
    const val HOLD_MS = 120_000L

    const val EDIT = "edit"
    const val ADD = "add"
    const val DELETE = "delete"

    @Volatile internal var shippedForTest: Boolean? = null

    /** Edits are drawn only on blocks, so they are declared only with them. */
    fun declared(): Boolean = (shippedForTest ?: SHIPPED) && TileBlocks.declared()

    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /** The longest text each kind of record takes, as the backend holds it. Longer is refused, never cut. */
    fun maxText(targetOrList: String): Int = when (targetOrList) {
        "note", "notes" -> 20_000
        "event" -> 500
        else -> 1_000
    }

    /** One op, as queued. */
    data class Op(
        val kind: String, val opId: String, val target: String = "", val itemId: String = "",
        val baseText: String = "", val text: String = "", val addTo: String = "", val atMs: Long = 0,
    ) {
        fun toJson(): JSONObject = JSONObject().put("kind", kind).put("op", opId).put("target", target)
            .put("item", itemId).put("base", baseText).put("text", text).put("add_to", addTo).put("at", atMs)

        companion object {
            fun fromJson(o: JSONObject) = Op(
                o.optString("kind"), o.optString("op"), o.optString("target"), o.optString("item"),
                o.optString("base"), o.optString("text"), o.optString("add_to"), o.optLong("at"),
            )
        }
    }

    /** What a row (or an add) says under it. */
    data class Status(val kind: Kind, val line: String = "") {
        enum class Kind { UNDO, SAVING, SAVED, SAID }
    }

    /** A new item or note, drawn at the end of its block until a list holds it. */
    data class Placeholder(val opId: String, val addTo: String, val text: String, val itemId: String = "", val savedAt: Long = 0)

    // ---- what the screen shows ----

    private val status = HashMap<String, Status>()
    private val texts = HashMap<String, Pair<String, Long>>()
    private val hidden = HashMap<String, Long>()
    private val undoing = HashMap<String, Pair<Op, Runnable>>()
    private val adds = LinkedHashMap<String, Placeholder>()
    private val drafts = HashMap<String, String>()
    @Volatile private var watching = 0

    fun rowKey(itemId: String) = "row:$itemId"
    fun addKey(addTo: String) = "add:$addTo"
    fun placeholderKey(opId: String) = "new:$opId"

    @Synchronized fun statusOf(key: String): Status? = status[key]

    /** The text a row shows in place of the backend's, while an edit of it is saving or just saved. */
    @Synchronized fun shownText(itemId: String): String? = texts[itemId]?.first

    @Synchronized fun isHidden(itemId: String): Boolean = itemId in hidden

    @Synchronized fun isUndoable(itemId: String): Boolean = itemId in undoing

    @Synchronized fun placeholders(addTo: String): List<Placeholder> = adds.values.filter { it.addTo == addTo }

    /** The user's words from an edit or add that did not go through, for the editor to start from. */
    @Synchronized fun draft(key: String): String? = drafts[key]

    @Synchronized fun clearDraft(key: String) { drafts.remove(key) }

    /** An open tile view; while one is, the backend's sentences are shown there, not as a toast. */
    fun watch() { watching++ }
    fun unwatch() { watching = (watching - 1).coerceAtLeast(0) }

    private val main = Handler(Looper.getMainLooper())

    private fun announce(ctx: Context) = runCatching {
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx.applicationContext)
            .sendBroadcast(Intent(ACTION_CHANGED))
    }

    private fun newId() = java.util.UUID.randomUUID().toString()

    // ---- what the user does ----

    /**
     * The user ticked [text] as the new text of [row]. False, and nothing sent, when it is empty,
     * too long, or what the row already holds.
     */
    fun edit(ctx: Context, row: TileRow, text: String): Boolean {
        if (row.id.isBlank() || !row.editable || text.isBlank() || text.length > maxText(row.target)) return false
        if (text == (shownText(row.id) ?: row.editText)) return false
        synchronized(this) {
            val op = Op(EDIT, newId(), row.target, row.id, row.editText, text, atMs = clock())
            // The latest text of a row replaces any not yet sent; one already sent is answered anyway.
            save(ctx, queued(ctx).filterNot { it.kind == EDIT && it.itemId == row.id } + op)
            texts[row.id] = text to clock()
            status[rowKey(row.id)] = Status(Status.Kind.SAVING)
            drafts.remove(rowKey(row.id))
        }
        announce(ctx)
        flushSoon(ctx)
        return true
    }

    /** One new item or note for the list [addTo] names. False when [text] is empty or too long. */
    fun add(ctx: Context, addTo: String, text: String): Boolean {
        val t = text.trim()
        if (addTo.isBlank() || t.isEmpty() || t.length > maxText(addTo)) return false
        synchronized(this) {
            val op = Op(ADD, newId(), text = t, addTo = addTo, atMs = clock())
            save(ctx, queued(ctx) + op)
            adds[op.opId] = Placeholder(op.opId, addTo, t)
            status[placeholderKey(op.opId)] = Status(Status.Kind.SAVING)
            status.remove(addKey(addTo))
            drafts.remove(addKey(addTo))
        }
        announce(ctx)
        flushSoon(ctx)
        return true
    }

    /** The row goes at once; the delete is sent after [UNDO_MS] unless [undo] comes first. */
    fun deleteLater(ctx: Context, row: TileRow) {
        if (row.id.isBlank() || !row.deletable) return
        val app = ctx.applicationContext
        synchronized(this) {
            if (row.id in undoing) return
            val op = Op(DELETE, newId(), row.target, row.id, atMs = clock())
            val commit = Runnable { commitDelete(app, row.id) }
            undoing[row.id] = op to commit
            hidden[row.id] = clock()
            status[rowKey(row.id)] = Status(Status.Kind.UNDO)
            main.postDelayed(commit, UNDO_MS)
        }
        announce(ctx)
    }

    /** Puts a deleted row back, if its delete has not yet been sent. */
    fun undo(ctx: Context, itemId: String): Boolean {
        synchronized(this) {
            val (_, commit) = undoing.remove(itemId) ?: return false
            main.removeCallbacks(commit)
            hidden.remove(itemId)
            status.remove(rowKey(itemId))
        }
        announce(ctx)
        return true
    }

    private fun commitDelete(ctx: Context, itemId: String) {
        synchronized(this) {
            val (op, _) = undoing.remove(itemId) ?: return
            save(ctx, queued(ctx).filterNot { it.kind == DELETE && it.itemId == itemId } + op.copy(atMs = clock()))
            status[rowKey(itemId)] = Status(Status.Kind.SAVING)
        }
        announce(ctx)
        flushSoon(ctx)
    }

    // ---- the queue ----

    @Synchronized
    fun queued(ctx: Context): List<Op> {
        val raw = Config.itemEditQueue(ctx)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Op.fromJson(arr.getJSONObject(it)) }.filter { it.opId.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(ctx: Context, ops: List<Op>) {
        val arr = JSONArray()
        ops.forEach { arr.put(it.toJson()) }
        Config.setItemEditQueue(ctx, if (ops.isEmpty()) "" else arr.toString())
    }

    @Synchronized
    private fun dequeue(ctx: Context, sent: List<Op>) {
        val ids = sent.map { it.opId }.toSet()
        save(ctx, queued(ctx).filterNot { it.opId in ids })
    }

    // ---- sending ----

    /** `…/v1/device` becomes `…/v1/device/items/edit`. */
    internal fun editUrl(backendUrl: String): String? {
        val base = backendUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val url = if (base.endsWith("/v1/device")) "$base/items/edit" else "$base/v1/device/items/edit"
        return url.toHttpUrlOrNull()?.toString()
    }

    internal fun batchOf(ops: List<Op>): ItemEditBatch {
        val b = ItemEditBatch.newBuilder()
        for (op in ops) when (op.kind) {
            EDIT -> b.addEdits(ItemEdit.newBuilder().setEditId(op.opId).setTarget(op.target).setItemId(op.itemId)
                .setBaseText(op.baseText).setText(op.text).setAtEpochMs(op.atMs))
            ADD -> b.addAdds(ItemAdd.newBuilder().setAddId(op.opId).setAddTo(op.addTo).setText(op.text).setAtEpochMs(op.atMs))
            DELETE -> b.addDeletes(ItemDelete.newBuilder().setDeleteId(op.opId).setTarget(op.target)
                .setItemId(op.itemId).setAtEpochMs(op.atMs))
        }
        return b.build()
    }

    sealed class Sent {
        data class Answered(val reply: ItemEditReply) : Sent()
        /** Offline, the credential, or the backend briefly unable (nothing applied): keep the ops. */
        data class Later(val why: String) : Sent()
        /** 402: kept, and sent once the subscription is active again. */
        data class Lapsed(val lapse: Billing.Lapse) : Sent()
        /** A malformed batch, or a backend without this route: drop, and give the words back. */
        data class Refused(val code: Int) : Sent()
    }

    private val PROTOBUF = "application/x-protobuf".toMediaType()

    internal fun send(http: OkHttpClient, url: String, bearer: String?, device: String, batch: ItemEditBatch): Sent {
        val request = Request.Builder().url(url)
            .post(batch.toByteArray().toRequestBody(PROTOBUF))
            .header("Content-Type", "application/x-protobuf")
            .header("Accept", "application/x-protobuf")
            .apply { if (bearer != null) header("Authorization", bearer) }
            .header("X-Rist-Device", device)
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val reply = runCatching { ItemEditReply.parseFrom(resp.body?.bytes() ?: ByteArray(0)) }.getOrNull()
                        // A 200 that cannot be read says nothing about what happened: ask again.
                        if (reply == null) Sent.Later("unreadable reply") else Sent.Answered(reply)
                    }
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

    /** Sends the queued ops, [BATCH_MAX] at a time, until a batch has to wait. Blocking. */
    fun flush(ctx: Context, http: OkHttpClient = Uploader.sharedClient()): Int {
        if (queued(ctx).isEmpty()) return 0
        return synchronized(flushLock) { flushOneAtATime(ctx, http) }
    }

    private fun flushOneAtATime(ctx: Context, http: OkHttpClient): Int {
        val url = editUrl(Config.backendUrl(ctx)) ?: return 0
        var done = 0
        while (true) {
            val batch = queued(ctx).take(BATCH_MAX)
            if (batch.isEmpty()) break
            when (val out = send(http, url, Uploader.bearer(ctx), Config.deviceId(ctx), batchOf(batch))) {
                is Sent.Later -> {
                    Log.i(TAG, "${batch.size} op(s) wait (${out.why})")
                    waiting(ctx, batch, ctx.getString(R.string.tile_edit_unavailable))
                    return done
                }
                is Sent.Lapsed -> {
                    Log.i(TAG, "${batch.size} op(s) wait for the subscription (${out.lapse.reason})")
                    runCatching { Billing.onLapsed(ctx, out.lapse) }
                    waiting(ctx, batch, Billing.lineFor(out.lapse))
                    return done
                }
                is Sent.Refused -> {
                    Log.w(TAG, "${batch.size} op(s) refused with HTTP ${out.code}; dropped")
                    dequeue(ctx, batch)
                    val line = ctx.getString(R.string.tile_edit_unavailable_nothing_changed)
                    batch.forEach { answer(ctx, it, ItemOutcome.newBuilder().setOpId(it.opId).setOutcome("failed").setNote(line).build()) }
                    done += batch.size
                }
                is Sent.Answered -> {
                    dequeue(ctx, batch)
                    val byOp = out.reply.outcomesList.associateBy { it.opId }
                    val missing = ctx.getString(R.string.tile_edit_unavailable_nothing_changed)
                    batch.forEach { op ->
                        answer(ctx, op, byOp[op.opId]
                            ?: ItemOutcome.newBuilder().setOpId(op.opId).setOutcome("failed").setNote(missing).build())
                    }
                    done += batch.size
                }
            }
        }
        announce(ctx)
        return done
    }

    /** The ops stay queued with the user's text on screen; each says why it has not gone. */
    private fun waiting(ctx: Context, ops: List<Op>, line: String) {
        synchronized(this) {
            for (op in ops) {
                val key = if (op.kind == ADD) placeholderKey(op.opId) else rowKey(op.itemId)
                status[key] = Status(Status.Kind.SAID, line)
            }
        }
        announce(ctx)
    }

    /** What happened to [op], as the backend says. Nothing reads as saved before this. */
    internal fun answer(ctx: Context, op: Op, o: ItemOutcome) {
        val outcome = o.outcome.trim().lowercase()
        val note = o.note.trim()
        val fallback by lazy { ctx.getString(R.string.tile_edit_unavailable_nothing_changed) }
        var said: String? = null
        synchronized(this) {
            val now = clock()
            when (op.kind) {
                EDIT -> {
                    val key = rowKey(op.itemId)
                    when (outcome) {
                        "saved", "unchanged" -> {
                            texts[op.itemId] = o.text.ifEmpty { op.text } to now
                            saved(ctx, key)
                        }
                        "stale" -> {
                            // Not renamed: the record's text now goes back on the row.
                            if (o.text.isNotEmpty()) texts[op.itemId] = o.text to now else texts.remove(op.itemId)
                            drafts[key] = op.text
                            said = note.ifEmpty { fallback }; status[key] = Status(Status.Kind.SAID, said!!)
                        }
                        "copied" -> {
                            texts.remove(op.itemId)
                            said = note.ifEmpty { fallback }; status[key] = Status(Status.Kind.SAID, said!!)
                        }
                        else -> {   // gone, refused, failed, anything new
                            texts.remove(op.itemId)
                            drafts[key] = op.text
                            said = note.ifEmpty { fallback }; status[key] = Status(Status.Kind.SAID, said!!)
                        }
                    }
                }
                ADD -> {
                    val p = adds[op.opId]
                    when (outcome) {
                        "saved", "unchanged" -> {
                            if (p != null) adds[op.opId] = p.copy(itemId = o.itemId, text = o.text.ifEmpty { p.text }, savedAt = now)
                            saved(ctx, placeholderKey(op.opId))
                        }
                        else -> {
                            adds.remove(op.opId)
                            status.remove(placeholderKey(op.opId))
                            drafts[addKey(op.addTo)] = op.text
                            said = note.ifEmpty { fallback }; status[addKey(op.addTo)] = Status(Status.Kind.SAID, said!!)
                        }
                    }
                }
                DELETE -> {
                    val key = rowKey(op.itemId)
                    when (outcome) {
                        "deleted" -> { hidden[op.itemId] = now; status.remove(key) }
                        "gone" -> {
                            hidden[op.itemId] = now
                            said = note.ifEmpty { fallback }; status[key] = Status(Status.Kind.SAID, said!!)
                        }
                        else -> {   // refused, failed: the row comes back
                            hidden.remove(op.itemId)
                            said = note.ifEmpty { fallback }; status[key] = Status(Status.Kind.SAID, said!!)
                        }
                    }
                }
                else -> Unit
            }
        }
        Log.i(TAG, "${op.kind} ${op.opId.take(8)}: $outcome")
        said?.let { if (watching == 0) toast(ctx, it) }
        announce(ctx)
    }

    private fun saved(ctx: Context, key: String) {
        val s = Status(Status.Kind.SAVED, ctx.getString(R.string.tile_edit_saved))
        status[key] = s
        val app = ctx.applicationContext
        main.postDelayed({
            synchronized(this) { if (status[key] === s) status.remove(key) }
            announce(app)
        }, SAVED_MS)
    }

    private fun toast(ctx: Context, line: String) {
        val app = ctx.applicationContext
        main.post { runCatching { Toast.makeText(app, line, Toast.LENGTH_LONG).show() } }
    }

    /**
     * A newer box list: text laid over a row goes once the row holds it (or after [HOLD_MS]), a
     * new item's placeholder goes once its row is there, and a deleted row's hold ends once the
     * row is gone from the list.
     */
    fun observe(ctx: Context, boxes: List<HomeBox>) {
        val rows = boxes.flatMap { it.blocksList }.flatMap { it.rowsList }.filter { it.id.isNotBlank() }
        val byId = rows.associateBy { it.id }
        synchronized(this) {
            val now = clock()
            val q = queued(ctx)
            val editing = q.filter { it.kind == EDIT }.map { it.itemId }.toSet()
            val deleting = q.filter { it.kind == DELETE }.map { it.itemId }.toSet()
            texts.entries.removeAll { (id, v) ->
                id !in editing && (byId[id]?.editText == v.first || now - v.second > HOLD_MS)
            }
            adds.entries.removeAll { (_, p) -> p.itemId.isNotEmpty() && (p.itemId in byId || now - p.savedAt > HOLD_MS) }
            hidden.entries.removeAll { (id, since) ->
                id !in undoing && id !in deleting && (id !in byId || now - since > HOLD_MS)
            }
        }
    }

    private val flusher = Executors.newSingleThreadExecutor { r -> Thread(r, "rist-item-edit").apply { isDaemon = true } }

    /** [flush] on a background thread; a later call waits behind an earlier one. */
    fun flushSoon(ctx: Context) {
        val app = ctx.applicationContext
        flusher.execute { runCatching { flush(app) }.onFailure { Log.w(TAG, "flush failed", it) } }
    }

    // ---- test seams ----

    @Synchronized
    internal fun resetForTest(ctx: Context) {
        shippedForTest = null
        clock = { System.currentTimeMillis() }
        undoing.values.forEach { main.removeCallbacks(it.second) }
        status.clear(); texts.clear(); hidden.clear(); undoing.clear(); adds.clear(); drafts.clear()
        watching = 0
        Config.setItemEditQueue(ctx, "")
    }

    internal fun awaitFlushForTest() {
        runCatching { flusher.submit { }.get() }
    }
}
