package watch.rist.assistant

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object OtaCheck {

    private const val TIMEOUT_SECONDS = 20L

    private const val PREFLIGHT_BYTES = 4L

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // Retries go through OtaRetry.backoffSeconds so Retry-After is honoured.
        .retryOnConnectionFailure(false)
        .build()

    private val SEGMENT_RE = Regex("^[A-Za-z0-9_-]+$")

    fun manifestUrl(base: String, device: String, channel: String, currentBuild: String): String {
        require(SEGMENT_RE.matches(device)) { "device is not a valid path segment: '$device'" }
        require(SEGMENT_RE.matches(channel)) { "channel is not a valid path segment: '$channel'" }
        val root = base.trimEnd('/')
        val q = URLEncoder.encode(currentBuild, "UTF-8")
        return "$root/v1/ota/$device/$channel?build=$q"
    }

    fun fetchManifest(
        client: OkHttpClient,
        base: String,
        device: String,
        channel: String,
        currentBuild: String,
        nowMillis: Long,
        token: String? = null,
    ): OtaRetry.Outcome {
        val url = try {
            manifestUrl(base, device, channel, currentBuild)
        } catch (e: IllegalArgumentException) {
            return OtaRetry.Outcome.Transient(e.message ?: "bad manifest URL")
        }
        val req = Request.Builder().url(url).get().apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.build()
        return try {
            client.newCall(req).execute().use { r ->
                // peekBody truncates rather than throws; an over-long manifest then fails its signature.
                val body = if (r.code == 200) r.peekBody(MAX_MANIFEST_BYTES).string() else null
                OtaRetry.classify(r.code, r.header("Retry-After"), body, nowMillis)
            }
        } catch (e: IOException) {
            OtaRetry.Outcome.Transient(e.javaClass.simpleName + ": " + (e.message ?: ""))
        } catch (e: IllegalStateException) {
            OtaRetry.Outcome.Transient(e.message ?: "bad request")
        }
    }

    fun signatureUrl(base: String, device: String, channel: String): String {
        require(SEGMENT_RE.matches(device)) { "device is not a valid path segment: '$device'" }
        require(SEGMENT_RE.matches(channel)) { "channel is not a valid path segment: '$channel'" }
        return "${base.trimEnd('/')}/v1/ota/$device/$channel.minisig"
    }

    fun fetchSignature(
        client: OkHttpClient,
        base: String,
        device: String,
        channel: String,
        token: String? = null,
    ): String? {
        val url = try {
            signatureUrl(base, device, channel)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val req = Request.Builder().url(url).get().apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.build()
        return try {
            client.newCall(req).execute().use { r ->
                if (r.code == 200) r.peekBody(MAX_SIGNATURE_BYTES).string() else null
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
    }

    private const val MAX_SIGNATURE_BYTES = 8L * 1024

    internal const val MAX_MANIFEST_BYTES = 64L * 1024

    sealed class Preflight {
        data class Ready(val zipSize: Long) : Preflight()
        data class BackOff(val seconds: Long) : Preflight()
        object Missing : Preflight()
        data class RangeUnsupported(val detail: String) : Preflight()
        object WrongOffset : Preflight()
        data class Transient(val detail: String) : Preflight()
    }

    fun preflight(
        client: OkHttpClient,
        packageUrl: String,
        payloadOffset: Long,
        payloadSize: Long,
        nowMillis: Long,
        token: String? = null,
    ): Preflight {
        val range = OtaRange.rangeHeader(payloadOffset, payloadSize, 0L, PREFLIGHT_BYTES)
            ?: return Preflight.Transient("manifest describes no fetchable payload")

        fun build(method: String) = Request.Builder().url(packageUrl)
            .header("Range", range)
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .let { if (method == "HEAD") it.head() else it.get() }
            .build()

        // HEAD first: it costs the server no stream slot and no byte budget.
        val head = try {
            client.newCall(build("HEAD")).execute().use { r ->
                Triple(r.code, r.header("Content-Range"), r.header("Retry-After"))
            }
        } catch (e: IOException) {
            return Preflight.Transient(e.javaClass.simpleName + ": " + (e.message ?: ""))
        }

        when (head.first) {
            404 -> return Preflight.Missing
            429, 502, 503, 504 -> return Preflight.BackOff(
                OtaRetry.retryAfterSeconds(head.third, nowMillis) ?: OtaRetry.BASE_BACKOFF_SECONDS)
        }
        val verdict = OtaRange.verifyRanged(head.first, head.second, payloadOffset)
        val zipSize = when (verdict) {
            is OtaRange.Ranged.Honoured -> verdict.range.total
            OtaRange.Ranged.Ignored ->
                return Preflight.RangeUnsupported("HEAD answered 200 for a ranged request")
            OtaRange.Ranged.Unsatisfiable ->
                return Preflight.RangeUnsupported("416: payload_offset is past the end of the object")
            is OtaRange.Ranged.Wrong -> return Preflight.RangeUnsupported(verdict.detail)
        }

        val magic = try {
            client.newCall(build("GET")).execute().use { r ->
                when (r.code) {
                    429, 502, 503, 504 -> return Preflight.BackOff(
                        OtaRetry.retryAfterSeconds(r.header("Retry-After"), nowMillis)
                            ?: OtaRetry.BASE_BACKOFF_SECONDS)
                    206 -> r.body?.bytes()
                    // 200 means Range was ignored on GET; the bytes would be the zip head, not the payload.
                    200 -> return Preflight.RangeUnsupported("GET answered 200 for a ranged request")
                    else -> return Preflight.Transient("HTTP ${r.code} on the ranged probe")
                }
            }
        } catch (e: IOException) {
            return Preflight.Transient(e.javaClass.simpleName + ": " + (e.message ?: ""))
        }

        if (!OtaRange.looksLikePayload(magic)) return Preflight.WrongOffset
        return Preflight.Ready(zipSize)
    }
}
