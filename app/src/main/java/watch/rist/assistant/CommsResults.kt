package watch.rist.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object CommsResults {

    private const val TAG = "RistCommsRes"

    private const val MAX_QUEUED = 100

    data class Result(
        val correlationId: String,
        val action: String,
        val performed: Boolean,
        val error: String,
    )

    private fun load(ctx: Context): MutableList<Result> {
        val out = mutableListOf<Result>()
        runCatching {
            val arr = JSONArray(Config.commsResults(ctx).ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Result(
                        correlationId = o.optString("cid"),
                        action = o.optString("action"),
                        performed = o.optBoolean("performed"),
                        error = o.optString("error"),
                    )
                )
            }
        }.onFailure { Log.w(TAG, "queue unreadable; starting empty", it) }
        return out
    }

    private fun save(ctx: Context, list: List<Result>) = runCatching {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("cid", it.correlationId); put("action", it.action)
                put("performed", it.performed); put("error", it.error)
            })
        }
        Config.setCommsResults(ctx, arr.toString())
    }.onFailure { Log.w(TAG, "queue not saved", it) }.let { }

    fun record(ctx: Context, correlationId: String, action: String, performed: Boolean, error: String = "") {
        if (correlationId.isBlank()) {
            Log.i(TAG, "no correlation_id on '$action'; not reporting")
            return
        }
        val list = load(ctx)
        list.removeAll { it.correlationId == correlationId }
        list.add(Result(correlationId, action, performed, error))
        save(ctx, list.takeLast(MAX_QUEUED))
        Log.i(TAG, "result $correlationId $action performed=$performed${if (error.isBlank()) "" else " ($error)"}")
    }

    fun pending(ctx: Context): List<Result> = load(ctx)

    fun ack(ctx: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val keep = load(ctx).filterNot { it.correlationId in ids }
        save(ctx, keep)
        Log.i(TAG, "acked ${ids.size}; ${keep.size} still pending")
    }
}
