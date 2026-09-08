package watch.rist.assistant

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object StreamingCancel {

    private const val TAG = "RistCancel"

    const val CANCEL_PATH = "/v1/cancel"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun cancelUrlFrom(backendUrl: String): String {
        val base = backendUrl.trim()
        return when {
            base.isEmpty() -> ""
            base.endsWith("/v1/device") -> base.removeSuffix("/v1/device") + CANCEL_PATH
            else -> base.trimEnd('/') + CANCEL_PATH
        }
    }

    // Not org.json: the JVM unit tests run against the stubbed android.jar, where it returns defaults.
    fun cancelBody(requestId: String): String = "{\"request_id\":\"${escapeJson(requestId)}\"}"

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    @Volatile private var inFlight: String = ""
    @Volatile private var inFlightCall: okhttp3.Call? = null

    @Volatile private var cancelled: String = ""

    @Volatile private var cancelledAndUnreported = false

    fun begin(requestId: String, call: okhttp3.Call? = null) {
        inFlight = requestId
        inFlightCall = call
        if (cancelled != requestId) cancelled = ""
    }

    fun end(requestId: String) {
        if (inFlight == requestId) {
            inFlight = ""
            inFlightCall = null
        }
    }

    fun inFlightId(): String = inFlight

    fun isCancelled(requestId: String): Boolean =
        requestId.isNotEmpty() && cancelled == requestId

    fun markCancelled(): String {
        val id = inFlight
        if (id.isEmpty()) return ""
        cancelled = id
        cancelledAndUnreported = true
        return id
    }

    fun takeCancelledFlag(): Boolean {
        val was = cancelledAndUnreported
        cancelledAndUnreported = false
        return was
    }

    internal fun resetForTest() {
        inFlight = ""
        inFlightCall = null
        cancelled = ""
        cancelledAndUnreported = false
    }

    fun cancelInFlight(): String {
        val id = markCancelled()
        if (id.isEmpty()) return ""
        runCatching { inFlightCall?.cancel() }
            .onFailure { Log.w(TAG, "could not abort the in-flight call", it) }
        Log.i(TAG, "cancelled request in flight")
        return id
    }

    fun notifyBackend(ctx: Context, requestId: String) {
        if (requestId.isEmpty()) return
        val url = cancelUrlFrom(Config.backendUrl(ctx))
        if (url.isEmpty() || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            Log.w(TAG, "no usable cancel endpoint; skipping the backend notify")
            return
        }
        runCatching {
            val request = Request.Builder()
                .url(url)
                .post(cancelBody(requestId).toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("X-Rist-Device", Config.deviceId(ctx))
                .apply { Uploader.bearer(ctx)?.let { header("Authorization", it) } }
                .build()
            Uploader.sharedClient().newBuilder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
                .newCall(request).execute().use { resp ->
                    Log.i(TAG, "cancel notify -> HTTP ${resp.code}")
                }
        }.onFailure {
            Log.w(TAG, "cancel notify failed: ${it.javaClass.simpleName}")
        }
    }
}
