package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import rist.v1.BoxEdit
import rist.v1.BoxEditReply
import rist.v1.BoxSet
import rist.v1.HomeBox
import java.util.concurrent.Executors

/**
 * Home boxes: the row of boxes under the clock.
 *
 * A box is one of two kinds, never both. A display box shows a short value the backend keeps
 * fresh, and tapping it opens its full text, which is already on the phone; it never sends
 * anything. A command box holds the user's words, and tapping it sends them as an ordinary turn,
 * exactly as if they had been typed.
 *
 * The backend sends the whole list ([BoxSet]) on a turn's reply and on the wake. The phone keeps
 * the last one and draws it at boot and offline. Touch edits (reorder, delete, undo, rename) need
 * no judgement, so they go to their own endpoint rather than being turns: they are applied here at
 * once, queued, and sent; the list the backend answers with replaces what the phone holds.
 */
object HomeBoxes {

    private const val TAG = "RistBoxes"

    /**
     * Whether requests declare the `home_boxes` component and the home screen shows boxes. The
     * backend sends no box list to a phone that does not declare it.
     */
    const val SHIPPED = false

    const val COMPONENT = "home_boxes"

    /** The tool the add and change sheets address directly. A command-box tap never uses it. */
    const val TOOL_ID = "boxes"

    const val ACTION_CHANGED = "watch.rist.assistant.BOXES_CHANGED"

    // Lengths are clamped on receipt; there is no limit on how many boxes there are.
    const val TITLE_MAX = 24
    const val VALUE_MAX = 12
    const val DETAIL_MAX = 32
    const val COMMAND_MAX = 500
    const val BODY_MAX_BYTES = 32 * 1024

    /** How long "Box removed · Undo" stays up. */
    const val UNDO_MS = 5_000L

    enum class Kind(val wire: String) { DISPLAY("display"), COMMAND("command") }

    enum class State(val wire: String) {
        OK("ok"), PENDING("pending"), ERROR("error"), PAUSED("paused"), OFF("off"),
    }

    /** Anything but "command" is a display box, so an unknown kind can never send words. */
    fun kindOf(b: HomeBox): Kind =
        if (b.kind.trim().lowercase() == Kind.COMMAND.wire) Kind.COMMAND else Kind.DISPLAY

    /** An unknown state gets the error look: a value is never shown as current unless it is. */
    fun stateOf(b: HomeBox): State =
        State.values().firstOrNull { it.wire == b.state.trim().lowercase() } ?: State.ERROR

    /** Lets a test exercise the shipped behaviour while [SHIPPED] is false. */
    @Volatile internal var shippedForTest = false

    /** Whether this build declares boxes to the backend at all. */
    fun declared(): Boolean = SHIPPED || shippedForTest

    /** Whether the home screen shows the row: shipped, and the account has the feature. */
    fun shown(ctx: Context): Boolean = declared() && Features.isOn(ctx, Features.Id.BOXES)

    // ---- clamps ----

    /** At most [max] characters, never splitting a character made of two UTF-16 units. */
    internal fun clip(s: String, max: Int): String {
        if (s.length <= max || s.codePointCount(0, s.length) <= max) return s
        return s.substring(0, s.offsetByCodePoints(0, max))
    }

    /** At most [maxBytes] bytes of UTF-8, cut on a character boundary. */
    internal fun clipUtf8(s: String, maxBytes: Int): String {
        if (s.length * 3 <= maxBytes || s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > maxBytes) break
            bytes += n
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    fun clamp(b: HomeBox): HomeBox {
        val c = b.toBuilder()
            .setTitle(clip(b.title.trim(), TITLE_MAX))
            .setValue(clip(b.value.trim(), VALUE_MAX))
            .setDetail(clip(b.detail.trim(), DETAIL_MAX))
            .setCommand(clip(b.command.trim(), COMMAND_MAX))
            .setBody(clipUtf8(b.body, BODY_MAX_BYTES))
        // An oversized or malformed custom icon is not kept at all: the box simply has no icon.
        if (!b.iconImage.isEmpty && !BoxIcons.imageAcceptable(b.iconImage.toByteArray())) c.clearIconImage()
        return c.build()
    }

    /** Clamps every box and drops any without an id, which nothing could act on. */
    fun clamp(set: BoxSet): BoxSet =
        set.toBuilder().clearBoxes()
            .addAllBoxes(set.boxesList.filter { it.id.isNotBlank() }.map { clamp(it) })
            .build()

    // ---- the held set ----

    @Volatile private var cache: BoxSet? = null

    @Synchronized
    fun held(ctx: Context): BoxSet {
        cache?.let { return it }
        val raw = Config.homeBoxes(ctx)
        val set = if (raw.isBlank()) BoxSet.getDefaultInstance()
        else runCatching { BoxSet.parseFrom(Base64.decode(raw, Base64.NO_WRAP)) }
            .onFailure { Log.w(TAG, "the stored box list could not be read; starting empty", it) }
            .getOrDefault(BoxSet.getDefaultInstance())
        cache = set
        return set
    }

    fun boxes(ctx: Context): List<HomeBox> = held(ctx).boxesList

    fun version(ctx: Context): Long = held(ctx).version

    fun find(ctx: Context, id: String): HomeBox? = boxes(ctx).firstOrNull { it.id == id }

    @Synchronized
    private fun store(ctx: Context, set: BoxSet) {
        cache = set
        Config.setHomeBoxes(ctx, Base64.encodeToString(set.toByteArray(), Base64.NO_WRAP))
    }

    private fun announce(ctx: Context) = runCatching {
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx.applicationContext)
            .sendBroadcast(Intent(ACTION_CHANGED))
    }

    /**
     * Takes in a list the backend sent, from a turn's reply or the wake. Edits made on the phone
     * that the backend has not yet accepted are laid back over it, so a list that crossed an
     * offline edit does not undo it on screen. Returns true when what is shown changed.
     */
    fun apply(ctx: Context, incoming: BoxSet): Boolean {
        val next = synchronized(this) {
            var set = clamp(incoming)
            for (edit in queued(ctx)) set = applyLocally(set, edit)
            val before = held(ctx)
            if (before == set) return false
            store(ctx, set)
            set
        }
        Log.i(TAG, "box list now v${next.version}: ${next.boxesCount} box(es)")
        announce(ctx)
        return true
    }

    // ---- command boxes in flight ----

    private val sending = HashSet<String>()

    @Synchronized fun isSending(id: String): Boolean = id in sending

    /** False when this box's turn is already in flight: a second tap is ignored, not sent. */
    @Synchronized fun beginSend(id: String): Boolean = sending.add(id)

    @Synchronized fun endSend(id: String) { sending.remove(id) }

    // ---- turns from boxes ----

    /** A turn a box starts. [prompt] is the user's line in the transcript. */
    data class Turn(val text: String, val targetToolId: String, val boxId: String, val prompt: String)

    /** Exactly the stored words, as typed text: no tool is addressed. Null for a display box. */
    fun commandTurn(b: HomeBox): Turn? {
        if (kindOf(b) != Kind.COMMAND || b.command.isBlank()) return null
        return Turn(text = b.command, targetToolId = "", boxId = b.id, prompt = "${b.command} · from a box")
    }

    fun addTurn(kind: Kind, words: String): Turn {
        val w = words.trim()
        val text = if (kind == Kind.COMMAND) "Add a command box: $w" else "Add a display box: $w"
        return Turn(text = text, targetToolId = TOOL_ID, boxId = "", prompt = text)
    }

    fun changeTurn(b: HomeBox, words: String): Turn {
        val text = "Change this box: ${words.trim()}"
        return Turn(text = text, targetToolId = TOOL_ID, boxId = b.id, prompt = text)
    }

    // ---- touch edits ----

    internal fun newEditId(): String = java.util.UUID.randomUUID().toString()

    /** Boxes deleted on this phone, kept so an undo can put them back at once. */
    private val removed = HashMap<String, Pair<HomeBox, Int>>()

    fun reorderEdit(ctx: Context, order: List<String>): BoxEdit =
        BoxEdit.newBuilder().setEditId(newEditId()).setBaseVersion(version(ctx)).addAllOrder(order).build()

    fun deleteEdit(ctx: Context, id: String): BoxEdit =
        BoxEdit.newBuilder().setEditId(newEditId()).setBaseVersion(version(ctx)).addDeleteIds(id).build()

    /** The undo of a delete: the box back, and the order as it was before. */
    fun restoreEdit(ctx: Context, id: String, previousOrder: List<String>): BoxEdit =
        BoxEdit.newBuilder().setEditId(newEditId()).setBaseVersion(version(ctx))
            .addRestoreIds(id).addAllOrder(previousOrder).build()

    fun renameEdit(ctx: Context, id: String, title: String): BoxEdit =
        BoxEdit.newBuilder().setEditId(newEditId()).setBaseVersion(version(ctx))
            .setRenameId(id).setRenameTitle(clip(title.trim(), TITLE_MAX)).build()

    /**
     * What [edit] does to [set], as the backend will apply it: deletes and restores first, then a
     * rename, then the order. An order lists ids; ids that no longer exist are skipped and boxes
     * it does not mention keep their place after the ones it does.
     */
    @Synchronized
    internal fun applyLocally(set: BoxSet, edit: BoxEdit): BoxSet {
        val list = set.boxesList.toMutableList()
        for (id in edit.deleteIdsList) {
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) removed[id] = list[i] to i
            list.removeAll { it.id == id }
        }
        for (id in edit.restoreIdsList) {
            if (list.any { it.id == id }) continue
            val (box, at) = removed[id] ?: continue
            list.add(at.coerceIn(0, list.size), box)
        }
        if (edit.renameId.isNotBlank()) {
            val i = list.indexOfFirst { it.id == edit.renameId }
            if (i >= 0) list[i] = list[i].toBuilder().setTitle(clip(edit.renameTitle.trim(), TITLE_MAX)).build()
        }
        val ordered = if (edit.orderCount == 0) list else {
            val byId = list.associateBy { it.id }
            val first = edit.orderList.distinct().mapNotNull { byId[it] }
            val named = first.map { it.id }.toSet()
            first + list.filter { it.id !in named }
        }
        return set.toBuilder().clearBoxes().addAllBoxes(ordered).build()
    }

    /** Applies [edit] to the held list at once and queues it for the backend. */
    fun edit(ctx: Context, edit: BoxEdit) {
        synchronized(this) {
            store(ctx, applyLocally(held(ctx), edit))
            saveQueue(ctx, queued(ctx) + edit)
        }
        Log.i(TAG, "edit ${edit.editId.take(8)} queued")
        announce(ctx)
    }

    @Synchronized
    fun queued(ctx: Context): List<BoxEdit> {
        val raw = Config.boxEditQueue(ctx)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { BoxEdit.parseFrom(Base64.decode(arr.getString(it), Base64.NO_WRAP)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveQueue(ctx: Context, edits: List<BoxEdit>) {
        val arr = JSONArray()
        edits.forEach { arr.put(Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)) }
        Config.setBoxEditQueue(ctx, if (edits.isEmpty()) "" else arr.toString())
    }

    /** `…/v1/device` becomes `…/v1/device/boxes`. */
    internal fun boxesUrl(backendUrl: String): String? {
        val base = backendUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val url = if (base.endsWith("/v1/device")) "$base/boxes" else "$base/v1/device/boxes"
        return url.toHttpUrlOrNull()?.toString()
    }

    sealed class Sent {
        data class Accepted(val reply: BoxEditReply) : Sent()
        /** Offline, or the backend briefly unable: keep the edit and send it later. */
        data class Later(val why: String) : Sent()
        /** Refused for good (a malformed edit, an endpoint that does not exist): drop it. */
        data class Refused(val code: Int) : Sent()
    }

    private val PROTOBUF = "application/x-protobuf".toMediaType()

    // [bearer] null sends no Authorization header, as a turn does with no token provisioned.
    internal fun send(http: OkHttpClient, url: String, bearer: String?, device: String, edit: BoxEdit): Sent {
        val request = Request.Builder().url(url)
            .post(edit.toByteArray().toRequestBody(PROTOBUF))
            .header("Content-Type", "application/x-protobuf")
            .header("Accept", "application/x-protobuf")
            .apply { if (bearer != null) header("Authorization", bearer) }
            .header("X-Rist-Device", device)
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful ->
                        Sent.Accepted(BoxEditReply.parseFrom(resp.body?.bytes() ?: ByteArray(0)))
                    // Not this edit's fault: the credential, the subscription, a busy or broken
                    // backend. Kept for the next try.
                    resp.code in setOf(401, 402, 403, 408, 429) || resp.code >= 500 -> Sent.Later("HTTP ${resp.code}")
                    else -> Sent.Refused(resp.code)
                }
            }
        } catch (t: Throwable) {
            Sent.Later(t.javaClass.simpleName)
        }
    }

    /**
     * Sends the queued edits in order, until one has to wait. Each accepted edit's reply replaces
     * the held list, with the edits still queued laid over it. Blocking; call off the main thread.
     * Returns how many edits left the queue.
     */
    fun flush(ctx: Context, http: OkHttpClient = Uploader.sharedClient()): Int {
        if (queued(ctx).isEmpty()) return 0
        val bearer = Uploader.bearer(ctx)
        val url = boxesUrl(Config.backendUrl(ctx)) ?: return 0
        var done = 0
        while (true) {
            val edit = queued(ctx).firstOrNull() ?: break
            when (val out = send(http, url, bearer, Config.deviceId(ctx), edit)) {
                is Sent.Later -> {
                    Log.i(TAG, "edit ${edit.editId.take(8)} waits (${out.why})")
                    return done
                }
                is Sent.Refused -> {
                    Log.w(TAG, "edit ${edit.editId.take(8)} refused with HTTP ${out.code}; dropped")
                    dequeue(ctx, edit.editId)
                }
                is Sent.Accepted -> {
                    dequeue(ctx, edit.editId)
                    if (out.reply.hasBoxes()) apply(ctx, out.reply.boxes)
                }
            }
            done++
        }
        return done
    }

    @Synchronized
    private fun dequeue(ctx: Context, editId: String) =
        saveQueue(ctx, queued(ctx).filterNot { it.editId == editId })

    private val flusher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rist-boxes").apply { isDaemon = true }
    }

    /** [flush] on a background thread; a later call waits behind an earlier one. */
    fun flushSoon(ctx: Context) {
        val app = ctx.applicationContext
        flusher.execute { runCatching { flush(app) }.onFailure { Log.w(TAG, "flush failed", it) } }
    }

    // ---- what a box shows ----

    /** "now", "8m", "3h", "2d": how long ago, as the box prints it. */
    fun age(nowS: Long, thenS: Long): String {
        val s = (nowS - thenS).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            else -> "${s / 86_400}d"
        }
    }

    /** The same, in words for a screen reader. */
    fun spokenAge(nowS: Long, thenS: Long): String {
        val s = (nowS - thenS).coerceAtLeast(0)
        fun n(v: Long, unit: String) = "$v $unit${if (v == 1L) "" else "s"} ago"
        return when {
            s < 60 -> "just now"
            s < 3600 -> n(s / 60, "minute")
            s < 86_400 -> n(s / 3600, "hour")
            else -> n(s / 86_400, "day")
        }
    }

    /** Everything a tile draws, worked out apart from any view so it can be tested. */
    data class Face(
        val kind: Kind,
        val state: State,
        val label: String,
        val value: String,
        val detail: String,
        val dimmed: Boolean,
        val sending: Boolean,
        val description: String,
    )

    fun isStale(b: HomeBox, nowS: Long): Boolean =
        b.staleAfterEpochS > 0 && nowS > b.staleAfterEpochS

    fun face(b: HomeBox, nowS: Long, sending: Boolean = false): Face {
        val kind = kindOf(b)
        val state = stateOf(b)
        if (kind == Kind.COMMAND) {
            val words = b.title.ifBlank { b.command }
            val note = if (state == State.OFF || state == State.PAUSED) b.note else ""
            val desc = "Command box: $words." +
                (if (note.isNotBlank()) " $note." else "") +
                (if (sending) " Sending." else " Double tap to send.")
            return Face(kind, state, "", words, if (sending) "Sending…" else note, false, sending, desc)
        }
        val label = b.title
        val updated = if (b.updatedAtEpochS > 0) age(nowS, b.updatedAtEpochS.toLong()) else ""
        val ago = if (updated.isBlank() || updated == "now") updated else "$updated ago"
        return when (state) {
            State.OK -> {
                val stale = isStale(b, nowS)
                val parts = listOf(b.detail, ago, if (stale) "stale" else "").filter { it.isNotBlank() }
                val spoken = buildList {
                    add(label); add(b.value)
                    if (b.detail.isNotBlank()) add(b.detail)
                    if (b.updatedAtEpochS > 0) add("updated " + spokenAge(nowS, b.updatedAtEpochS.toLong()))
                    if (stale) add("out of date")
                }.filter { it.isNotBlank() }.joinToString(", ")
                Face(kind, state, label, b.value, parts.joinToString(" · "), stale, false, "$spoken. Double tap to open.")
            }
            State.PENDING -> Face(kind, state, label, "…", b.note.ifBlank { "Getting it" }, false, false,
                "$label, ${b.note.ifBlank { "getting it" }}. Double tap to open.")
            State.ERROR -> {
                val why = b.note.ifBlank { "Couldn't update" }
                Face(kind, state, label, "—", why, false, false, "$label, $why. Double tap to open.")
            }
            State.PAUSED, State.OFF -> {
                val why = b.note.ifBlank { if (state == State.OFF) "Off" else "Paused" }
                Face(kind, state, label, "", why, true, false, "$label, $why. Double tap to open.")
            }
        }
    }

    // ---- test seams ----

    @Synchronized
    internal fun resetForTest(ctx: Context) {
        cache = null
        shippedForTest = false
        sending.clear()
        removed.clear()
        Config.setHomeBoxes(ctx, "")
        Config.setBoxEditQueue(ctx, "")
    }

    /** Drops the in-memory copy only, as a restart does, so the next read comes from storage. */
    @Synchronized
    internal fun forgetCacheForTest() {
        cache = null
        removed.clear()
    }

    /** Waits for every [flushSoon] queued so far. */
    internal fun awaitFlushForTest() {
        runCatching { flusher.submit { }.get() }
    }
}
