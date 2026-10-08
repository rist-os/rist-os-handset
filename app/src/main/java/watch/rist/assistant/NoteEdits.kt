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
import rist.v1.NoteEdit
import rist.v1.NoteEditBatch
import rist.v1.NoteEditReply
import java.util.concurrent.Executors

/**
 * Notes edited in place on a reply card.
 *
 * The tick is the save: the card shows the new text at once and the whole text is queued for
 * `POST /v1/device/notes`. The queue keeps the latest text per note. The words are never thrown
 * away: if the note changed since the card was drawn, the backend keeps it and saves this text as
 * a new note beside it, and a refused edit leaves the user's text on the card.
 */
object NoteEdits {

    private const val TAG = "RistNoteEdit"

    /** Whether this build declares `note_edit_v1`. Without it the backend sends no note cards. */
    const val SHIPPED = true

    const val COMPONENT = "note_edit_v1"

    /** The most edits one request carries; the backend refuses more. */
    const val BATCH_MAX = 20

    /** The longest note the backend takes, in characters. */
    const val MAX_TEXT = 20_000

    @Volatile internal var shippedForTest: Boolean? = null

    fun declared(): Boolean = shippedForTest ?: SHIPPED

    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /** One note's new text, not yet accepted. [baseVersion] is the version the user started from. */
    data class Edit(val editId: String, val noteId: String, val baseVersion: String, val text: String, val atMs: Long) {
        fun toJson(): JSONObject = JSONObject().put("edit_id", editId).put("note_id", noteId)
            .put("base", baseVersion).put("text", text).put("at", atMs)

        companion object {
            fun fromJson(o: JSONObject) = Edit(
                o.optString("edit_id"), o.optString("note_id"), o.optString("base"),
                o.optString("text"), o.optLong("at"),
            )
        }
    }

    // ---- the queue ----

    @Synchronized
    fun queued(ctx: Context): List<Edit> {
        val raw = Config.noteEditQueue(ctx)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Edit.fromJson(arr.getJSONObject(it)) }.filter { it.noteId.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun store(ctx: Context, edits: List<Edit>) {
        val arr = JSONArray()
        edits.forEach { arr.put(it.toJson()) }
        Config.setNoteEditQueue(ctx, if (edits.isEmpty()) "" else arr.toString())
    }

    /**
     * The user ticked [text] as the new text of [noteId], starting from [baseVersion]. Queued at
     * once and sent soon; every card holding the note shows the text. False, and nothing queued,
     * when the text is blank or too long.
     */
    fun save(ctx: Context, noteId: String, baseVersion: String, text: String): Boolean {
        if (noteId.isBlank() || text.isBlank() || text.length > MAX_TEXT) return false
        synchronized(this) {
            val q = queued(ctx)
            // A note edited again before the first edit went keeps the version the first started
            // from: that is still what the backend holds.
            val base = q.firstOrNull { it.noteId == noteId }?.baseVersion ?: baseVersion
            val edit = Edit(java.util.UUID.randomUUID().toString(), noteId, base, text, clock())
            store(ctx, q.filterNot { it.noteId == noteId } + edit)
        }
        runCatching { Transcript.setNoteText(ctx, noteId, text) }
        flushSoon(ctx, fromTap = true)
        return true
    }

    // ---- sending ----

    /** `…/v1/device` becomes `…/v1/device/notes`. */
    internal fun notesUrl(backendUrl: String): String? {
        val base = backendUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val url = if (base.endsWith("/v1/device")) "$base/notes" else "$base/v1/device/notes"
        return url.toHttpUrlOrNull()?.toString()
    }

    sealed class Sent {
        /** Saved. [reply] says where each text now lives; null if the body could not be read. */
        data class Accepted(val reply: NoteEditReply?) : Sent()
        /** Offline, the credential, or the backend briefly unable: keep the edits. */
        data class Later(val why: String) : Sent()
        /** 402: kept, and sent once the subscription is active again. */
        data class Lapsed(val lapse: Billing.Lapse) : Sent()
        /** 409 (notes off), a malformed batch, an endpoint this backend does not have: drop. */
        data class Refused(val code: Int) : Sent()
    }

    private val PROTOBUF = "application/x-protobuf".toMediaType()

    internal fun batchOf(edits: List<Edit>): NoteEditBatch =
        NoteEditBatch.newBuilder().addAllEdits(edits.map {
            NoteEdit.newBuilder().setEditId(it.editId).setNoteId(it.noteId)
                .setBaseVersion(it.baseVersion).setText(it.text).setAtEpochMs(it.atMs).build()
        }).build()

    internal fun send(http: OkHttpClient, url: String, bearer: String?, device: String, batch: NoteEditBatch): Sent {
        val request = Request.Builder().url(url)
            .post(batch.toByteArray().toRequestBody(PROTOBUF))
            .header("Content-Type", "application/x-protobuf")
            .apply { if (bearer != null) header("Authorization", bearer) }
            .header("X-Rist-Device", device)
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> Sent.Accepted(
                        runCatching { NoteEditReply.parseFrom(resp.body?.bytes() ?: ByteArray(0)) }.getOrNull())
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
     * Sends the queued edits, [BATCH_MAX] at a time, until a batch has to wait. Blocking; call off
     * the main thread. [fromTap]: a lapse is said only when the user just ticked. Returns how many
     * edits left the queue.
     */
    fun flush(ctx: Context, http: OkHttpClient = Uploader.sharedClient(), fromTap: Boolean = false): Int {
        if (queued(ctx).isEmpty()) return 0
        return synchronized(flushLock) { flushOneAtATime(ctx, http, fromTap) }
    }

    private fun flushOneAtATime(ctx: Context, http: OkHttpClient, fromTap: Boolean): Int {
        val url = notesUrl(Config.backendUrl(ctx)) ?: return 0
        var done = 0
        while (true) {
            val batch = queued(ctx).take(BATCH_MAX)
            if (batch.isEmpty()) break
            when (val out = send(http, url, Uploader.bearer(ctx), Config.deviceId(ctx), batchOf(batch))) {
                is Sent.Later -> {
                    Log.i(TAG, "${batch.size} edit(s) wait (${out.why})")
                    return done
                }
                is Sent.Lapsed -> {
                    Log.i(TAG, "${batch.size} edit(s) wait for the subscription (${out.lapse.reason})")
                    runCatching { Billing.onLapsed(ctx, out.lapse) }
                    if (fromTap) note(ctx, Billing.lineFor(out.lapse))
                    return done
                }
                is Sent.Accepted -> {
                    dequeue(ctx, batch)
                    var copied = false
                    for (saved in out.reply?.savedList.orEmpty()) {
                        val edit = batch.firstOrNull { it.editId == saved.editId } ?: continue
                        if (saved.noteId.isBlank()) continue
                        copied = copied || saved.copied
                        runCatching { Transcript.noteSaved(ctx, edit.noteId, saved.noteId, saved.version, edit.text) }
                        rebase(ctx, edit.noteId, saved.noteId, saved.version)
                    }
                    if (copied) note(ctx, ctx.getString(R.string.note_edit_saved_as_new))
                    done += batch.size
                }
                is Sent.Refused -> {
                    // The text stays on the card: the user's words are not taken back.
                    Log.w(TAG, "${batch.size} edit(s) refused with HTTP ${out.code}; dropped")
                    if (dequeue(ctx, batch).isNotEmpty()) note(ctx, ctx.getString(R.string.note_edit_save_failed))
                    done += batch.size
                }
            }
        }
        return done
    }

    /**
     * Takes [sent] off the queue and returns the edits that went. A note edited again while its
     * edit was in flight has a new edit id and stays: the newer text is still to be sent.
     */
    @Synchronized
    private fun dequeue(ctx: Context, sent: List<Edit>): List<Edit> {
        val ids = sent.map { it.editId }.toSet()
        val (gone, kept) = queued(ctx).partition { it.editId in ids }
        store(ctx, kept)
        return gone
    }

    /** A newer edit of a note that was just saved starts from what was saved. */
    @Synchronized
    private fun rebase(ctx: Context, oldId: String, newId: String, version: String) {
        val q = queued(ctx)
        if (q.none { it.noteId == oldId }) return
        store(ctx, q.map { if (it.noteId == oldId) it.copy(noteId = newId, baseVersion = version) else it })
    }

    private fun note(ctx: Context, line: String) {
        if (line.isBlank()) return
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post { runCatching { Toast.makeText(app, line, Toast.LENGTH_SHORT).show() } }
    }

    private val flusher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rist-note-edit").apply { isDaemon = true }
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
        Config.setNoteEditQueue(ctx, "")
    }

    internal fun awaitFlushForTest() {
        runCatching { flusher.submit { }.get() }
    }
}
