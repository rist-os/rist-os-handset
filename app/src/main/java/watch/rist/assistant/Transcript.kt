package watch.rist.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class EntryState { RECORDING, SENT, WAITING, ANSWERED, FAILED }

data class TranscriptEntry(
    val localId: Long,
    val at: Long,
    var prompt: String,
    var state: EntryState,
    var answer: String = "",
    var requestId: String = "",
    var error: String = "",
    /** Pinned entries survive the age sweep and the count cap until they are unpinned. */
    var pinned: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", localId).put("at", at).put("prompt", prompt)
        .put("state", state.name).put("answer", answer)
        .put("requestId", requestId).put("error", error).put("pinned", pinned)

    companion object {
        fun fromJson(o: JSONObject) = TranscriptEntry(
            localId = o.optLong("id"),
            at = o.optLong("at"),
            prompt = o.optString("prompt"),
            state = runCatching { EntryState.valueOf(o.optString("state")) }.getOrDefault(EntryState.FAILED),
            answer = o.optString("answer"),
            requestId = o.optString("requestId"),
            error = o.optString("error"),
            pinned = o.optBoolean("pinned", false),
        )
    }
}

object Transcript {

    private const val TAG = "RistTranscript"
    private const val FILE = "transcript.json"


    private val entries = ArrayList<TranscriptEntry>()
    private var loaded = false
    private var nextId = 1L

    @Synchronized
    fun all(ctx: Context): List<TranscriptEntry> {
        ensureLoaded(ctx)
        if (prune(ctx)) save(ctx)
        return entries.toList()
    }

    private const val IN_FLIGHT_TIMEOUT_MS = 90_000L

    @Synchronized
    fun nextStaleAtMs(ctx: Context): Long {
        ensureLoaded(ctx)
        return entries.filter {
            it.state == EntryState.RECORDING || it.state == EntryState.WAITING ||
                it.state == EntryState.SENT
        }.minOfOrNull { it.at + IN_FLIGHT_TIMEOUT_MS } ?: 0L
    }

    private fun prune(ctx: Context): Boolean {
        val before = entries.size
        val now = System.currentTimeMillis()
        var changed = false
        entries.forEach { e ->
            val inFlight = e.state == EntryState.RECORDING || e.state == EntryState.WAITING ||
                e.state == EntryState.SENT
            if (inFlight && now - e.at > IN_FLIGHT_TIMEOUT_MS) {
                e.state = EntryState.FAILED
                if (e.error.isBlank()) e.error = "no answer"
                changed = true
            }
        }
        val maxAge = Config.transcriptMaxAgeMs(ctx)
        if (maxAge > 0L) {
            val cutoff = System.currentTimeMillis() - maxAge
            entries.removeAll {
                !it.pinned && it.at < cutoff &&
                    it.state != EntryState.RECORDING && it.state != EntryState.WAITING &&
                    it.state != EntryState.SENT
            }
        }
        // The cap counts UNPINNED entries only. Counting pinned ones against the budget
        // meant that once enough were pinned, each new answer was evicted the moment it
        // arrived -- the newest thing on screen disappearing instead of the oldest.
        val maxN = Config.transcriptMaxEntries(ctx)
        var over = entries.count { !it.pinned } - maxN
        if (over > 0) {
            val walk = entries.iterator()   // oldest first
            while (walk.hasNext() && over > 0) {
                if (!walk.next().pinned) { walk.remove(); over-- }
            }
        }
        return changed || entries.size != before
    }

    @Synchronized
    fun begin(ctx: Context, prompt: String, state: EntryState = EntryState.RECORDING): Long {
        ensureLoaded(ctx)
        val e = TranscriptEntry(localId = nextId++, at = System.currentTimeMillis(), prompt = prompt, state = state)
        entries.add(e)
        prune(ctx)
        save(ctx)
        return e.localId
    }

    @Synchronized
    fun update(
        ctx: Context, localId: Long,
        state: EntryState? = null, prompt: String? = null,
        answer: String? = null, requestId: String? = null, error: String? = null,
    ) {
        ensureLoaded(ctx)
        val e = entries.firstOrNull { it.localId == localId } ?: return
        state?.let { e.state = it }
        prompt?.let { e.prompt = it }
        answer?.let { e.answer = it }
        requestId?.let { e.requestId = it }
        error?.let { e.error = it }
        save(ctx)
    }

    @Synchronized
    fun discard(ctx: Context, localId: Long) {
        ensureLoaded(ctx)
        if (entries.removeAll { it.localId == localId }) save(ctx)
    }

    /** Clearing spares pinned entries; unpin one to be rid of it. */
    @Synchronized
    fun clear(ctx: Context) {
        ensureLoaded(ctx)
        entries.retainAll { it.pinned }
        save(ctx)
    }

    @Synchronized
    fun setPinned(ctx: Context, localId: Long, pinned: Boolean) {
        ensureLoaded(ctx)
        val e = entries.firstOrNull { it.localId == localId } ?: return
        if (e.pinned == pinned) return
        e.pinned = pinned
        save(ctx)
    }

    // ---- test seams ----
    // Same pattern as Config.setDeployDefaultsForTest: this is an object with process-wide
    // state, and a test cannot otherwise wipe it, age an entry, or force a reload from disk.

    @Synchronized
    internal fun clearForTest(ctx: Context) {
        ensureLoaded(ctx)
        entries.clear()
        save(ctx)
    }

    /** Moves an entry [byMs] further into the past, so the age sweep can be exercised. */
    @Synchronized
    internal fun ageForTest(ctx: Context, localId: Long, byMs: Long) {
        ensureLoaded(ctx)
        val i = entries.indexOfFirst { it.localId == localId }
        if (i < 0) return
        entries[i] = entries[i].copy(at = entries[i].at - byMs)
        save(ctx)
    }

    /** Waits for the pending asynchronous [save]; the executor is single-threaded FIFO. */
    internal fun flushForTest() {
        runCatching { io.submit { }.get() }
    }

    @Synchronized
    internal fun reloadForTest(ctx: Context) {
        flushForTest()
        entries.clear()
        loaded = false
        ensureLoaded(ctx)
    }

    @Synchronized
    fun isPinned(ctx: Context, localId: Long): Boolean {
        ensureLoaded(ctx)
        return entries.firstOrNull { it.localId == localId }?.pinned == true
    }

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return@runCatching
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) entries.add(TranscriptEntry.fromJson(arr.getJSONObject(i)))
            entries.forEach {
                if (it.state == EntryState.RECORDING || it.state == EntryState.SENT || it.state == EntryState.WAITING) {
                    it.state = EntryState.FAILED
                    if (it.error.isBlank()) it.error = "interrupted"
                }
            }
            nextId = (entries.maxOfOrNull { it.localId } ?: 0L) + 1L
            prune(ctx)
        }.onFailure { Log.w(TAG, "transcript load failed; starting empty", it) }
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "rist-transcript").apply { isDaemon = true }
    }

    private fun save(ctx: Context) {
        // Copy the entries under the lock, serialise on the io thread. With retention at
        // "Forever" the list can reach the count cap, and building ~150 KB of JSON on the UI
        // thread for every pin, dismiss and update was a stall waiting to happen.
        val snapshot = entries.map { it.copy() }
        val app = ctx.applicationContext
        io.execute {
            runCatching {
                val json = JSONArray().also { arr -> snapshot.forEach { arr.put(it.toJson()) } }
                File(app.filesDir, FILE).writeText(json.toString())
            }.onFailure { Log.w(TAG, "transcript save failed", it) }
        }
    }
}
