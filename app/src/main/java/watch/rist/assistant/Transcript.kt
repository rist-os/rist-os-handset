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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", localId).put("at", at).put("prompt", prompt)
        .put("state", state.name).put("answer", answer)
        .put("requestId", requestId).put("error", error)

    companion object {
        fun fromJson(o: JSONObject) = TranscriptEntry(
            localId = o.optLong("id"),
            at = o.optLong("at"),
            prompt = o.optString("prompt"),
            state = runCatching { EntryState.valueOf(o.optString("state")) }.getOrDefault(EntryState.FAILED),
            answer = o.optString("answer"),
            requestId = o.optString("requestId"),
            error = o.optString("error"),
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
            entries.removeAll { it.at < cutoff && it.state != EntryState.RECORDING && it.state != EntryState.WAITING && it.state != EntryState.SENT }
        }
        val maxN = Config.transcriptMaxEntries(ctx)
        while (entries.size > maxN) entries.removeAt(0)
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

    @Synchronized
    fun clear(ctx: Context) { ensureLoaded(ctx); entries.clear(); save(ctx) }

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
        val snapshot = JSONArray().also { arr -> entries.forEach { arr.put(it.toJson()) } }.toString()
        val app = ctx.applicationContext
        io.execute {
            runCatching { File(app.filesDir, FILE).writeText(snapshot) }
                .onFailure { Log.w(TAG, "transcript save failed", it) }
        }
    }
}
