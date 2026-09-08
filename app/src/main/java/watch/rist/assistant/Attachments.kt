package watch.rist.assistant

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class RistAttachment(
    /** "text" | "image" | "data" — normalised lowercase; an unrecognised kind becomes "data". */
    val kind: String,
    val mime: String,
    val title: String,
    /** Populated for kind=text. Empty otherwise. */
    val text: String,
    /** Resolved bytes for image/data; null if unresolved or refused. */
    val bytes: ByteArray?,
    val toolId: String,
    val error: String?,
    /** Pre-decoded, sampled-down bitmap; null is valid and the viewer then decodes inline. */
    val bitmap: android.graphics.Bitmap? = null,
) {
    // A ByteArray field gets identity equality in a data class; compare content instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RistAttachment) return false
        return kind == other.kind &&
            mime == other.mime &&
            title == other.title &&
            text == other.text &&
            toolId == other.toolId &&
            error == other.error &&
            (if (bytes == null) other.bytes == null else other.bytes != null && bytes.contentEquals(other.bytes))
    }

    override fun hashCode(): Int {
        var h = kind.hashCode()
        h = 31 * h + mime.hashCode()
        h = 31 * h + title.hashCode()
        h = 31 * h + text.hashCode()
        h = 31 * h + toolId.hashCode()
        h = 31 * h + (error?.hashCode() ?: 0)
        h = 31 * h + (bytes?.contentHashCode() ?: 0)
        return h
    }
}

/** Blocking: [resolve] performs network I/O and must be called off the main thread. */
object Attachments {

    private const val TAG = "RistAttach"

    internal const val MAX_BYTES_PER_ATTACHMENT = 8 * 1024 * 1024

    // Spent in arrival order.
    internal const val MAX_BYTES_PER_RESPONSE = 24 * 1024 * 1024

    internal const val MAX_PER_RESPONSE = 8

    // Redirects are followed by hand in [fetch]; this is the hop budget.
    private const val MAX_REDIRECTS = 3

    // Test seam; nothing in the app writes them.
    internal var connectTimeoutS = 10L
    internal var readTimeoutS = 15L
    internal var callTimeoutS = 30L

    // Redirects are followed by hand per hop in [fetch] and there is deliberately no CookieJar;
    // newBuilder() in [fetch] inherits both.
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** Test seam; production reads [Config.authToken]. */
    internal var bearerSource: (Context) -> String = { Config.authToken(it) }

    /**
     * Never throws; a failed attachment comes back carrying [RistAttachment.error]. [stillWanted]
     * is checked between attachments only, and the list stays 1:1 with the input (up to the cap).
     */
    fun resolve(
        ctx: Context,
        protoList: List<rist.v1.Attachment>,
        stillWanted: () -> Boolean = { true },
    ): List<RistAttachment> {
        if (protoList.isEmpty()) return emptyList()

        val considered = protoList.take(MAX_PER_RESPONSE)
        if (protoList.size > considered.size) {
            Log.w(TAG, "dropping ${protoList.size - considered.size} attachment(s) over the cap of $MAX_PER_RESPONSE")
        }

        val allowHttp = Config.isDebugBuild(ctx)
        var budget = MAX_BYTES_PER_RESPONSE

        return considered.map { a ->
            if (!stillWanted()) return@map refuse(a, "abandoned")

            // Charged for everything that crossed the wire, kept or not.
            val spend = Spend()
            val resolved = runCatching { resolveOne(ctx, a, allowHttp, budget, spend) }
                .getOrElse { t ->
                    // Throwable messages can carry the uri, so they are not logged.
                    Log.w(TAG, "attachment failed: ${t.javaClass.simpleName}")
                    refuse(a, "could not be loaded")
                }
            budget -= spend.wire
            resolved
        }
    }

    internal fun normaliseKind(raw: String): String =
        when (val k = raw.trim().lowercase()) {
            "text", "image", "data" -> k
            else -> "data"
        }

    private fun refuse(a: rist.v1.Attachment, why: String) = RistAttachment(
        kind = normaliseKind(a.kind),
        mime = a.mime,
        title = a.title,
        text = "",
        bytes = null,
        toolId = a.toolId,
        error = why,
    )

    private fun resolveOne(
        ctx: Context,
        a: rist.v1.Attachment,
        allowHttp: Boolean,
        budget: Int,
        spend: Spend,
    ): RistAttachment {
        val kind = normaliseKind(a.kind)
        val cap = minOf(MAX_BYTES_PER_ATTACHMENT, budget)
        if (cap <= 0) return refuse(a, "no room left in this response")

        val inline = a.data
        if (!inline.isEmpty) {
            if (inline.size() > cap) {
                spend.charge(inline.size())
                Log.w(TAG, "inline attachment refused: ${inline.size()} bytes over the ${cap}-byte cap")
                return refuse(a, "too large (${inline.size() / 1024} KiB)")
            }
            spend.charge(inline.size())
            return finish(a, kind, inline.toByteArray())
        }

        if (kind == "text" && (a.text.isNotEmpty() || a.uri.isBlank())) {
            val size = a.text.toByteArray(Charsets.UTF_8).size
            spend.charge(size)
            if (size > cap) return refuse(a, "too large (${size / 1024} KiB)")
            return RistAttachment(kind, a.mime, a.title, a.text, null, a.toolId, null)
        }

        val uri = a.uri.trim()
        if (uri.isEmpty()) return refuse(a, "no content")

        return when (val f = fetch(ctx, uri, cap, allowHttp, spend)) {
            is Fetched.Err -> refuse(a, f.why)
            is Fetched.Ok ->
                if (kind == "text") RistAttachment(kind, a.mime, a.title, String(f.bytes, Charsets.UTF_8), null, a.toolId, null)
                else finish(a, kind, f.bytes)
        }
    }

    // The magic-byte sniff must run before BitmapFactory sees the buffer; inJustDecodeBounds
    // allocates no pixel buffer.
    private fun finish(a: rist.v1.Attachment, kind: String, bytes: ByteArray): RistAttachment {
        if (kind == "image" && !decodesAsImage(bytes)) {
            Log.w(TAG, "refusing a ${bytes.size}-byte attachment declared '${a.mime}': not a decodable image")
            return refuse(a, "not a valid image")
        }
        return RistAttachment(kind, a.mime, a.title, "", bytes, a.toolId, null)
    }

    internal fun decodesAsImage(bytes: ByteArray): Boolean {
        if (!looksLikeImage(bytes)) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
        return opts.outWidth > 0 && opts.outHeight > 0
    }

    /** Whitelist of container magic bytes; must run before anything native sees the buffer. */
    internal fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        fun at(i: Int, vararg v: Int) = v.indices.all { b[i + it] == v[it].toByte() }
        fun ascii(i: Int, s: String) = s.indices.all { b[i + it] == s[it].code.toByte() }
        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> true            // PNG
            at(0, 0xFF, 0xD8, 0xFF) -> true                                          // JPEG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> true                          // GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> true                              // WebP
            ascii(0, "BM") -> true                                                    // BMP
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") ||
                ascii(8, "mif1") || ascii(8, "avif")) -> true                         // HEIF / AVIF
            else -> false
        }
    }

    // Scheme whitelist; http only in a debuggable build (release also refuses cleartext in
    // res/xml/network_security_config.xml).
    internal fun schemeRefusal(uri: String, allowHttp: Boolean): String? {
        val scheme = runCatching { java.net.URI(uri.trim()).scheme }.getOrNull()?.lowercase()
            ?: return "unusable link"
        return when {
            scheme == "https" -> null
            scheme == "http" && allowHttp -> null
            scheme == "http" -> "refused an insecure link"
            else -> "refused a '$scheme' link"
        }
    }

    // Compared as a full origin: scheme, host and port.
    internal fun isBackendOrigin(backend: HttpUrl?, target: HttpUrl): Boolean =
        backend != null &&
            backend.scheme.equals(target.scheme, ignoreCase = true) &&
            backend.host.equals(target.host, ignoreCase = true) &&
            backend.port == target.port

    // Path and query are never logged; the host only in a debuggable build.
    internal fun originLabel(url: HttpUrl, backend: HttpUrl?, verbose: Boolean): String = when {
        verbose -> "${url.host}:${url.port}"
        isBackendOrigin(backend, url) -> "the backend"
        else -> "a third-party host"
    }

    /** Bytes that crossed the wire, kept or not. */
    private class Spend {
        var wire = 0
        fun charge(n: Int) { wire += n.coerceAtLeast(0) }
    }

    private sealed class Fetched {
        class Ok(val bytes: ByteArray) : Fetched()
        class Err(val why: String) : Fetched()
    }

    // The bearer is attached per hop only when that hop's origin is the backend's own, and
    // every redirect hop goes back through [schemeRefusal].
    private fun fetch(ctx: Context, rawUri: String, cap: Int, allowHttp: Boolean, spend: Spend): Fetched {
        val backend = Config.backendUrl(ctx).toHttpUrlOrNull()
        val client = sharedClient.newBuilder()
            .connectTimeout(connectTimeoutS, TimeUnit.SECONDS)
            .readTimeout(readTimeoutS, TimeUnit.SECONDS)
            // callTimeout bounds the whole call, including a server that dribbles bytes forever.
            .callTimeout(callTimeoutS, TimeUnit.SECONDS)
            .build()

        val verboseLog = Config.isDebugBuild(ctx)

        var current = rawUri.trim()
        var hops = 0
        while (true) {
            schemeRefusal(current, allowHttp)?.let { return Fetched.Err(it) }
            val url = current.toHttpUrlOrNull() ?: return Fetched.Err("unusable link")

            val builder = Request.Builder().url(url)
            if (isBackendOrigin(backend, url)) {
                val token = bearerSource(ctx)
                if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
            }

            val where = originLabel(url, backend, verboseLog)
            val resp = runCatching { client.newCall(builder.build()).execute() }
                .getOrElse { t ->
                    Log.w(TAG, "attachment fetch from $where failed: ${t.javaClass.simpleName}")
                    return Fetched.Err("could not be loaded")
                }
            try {
                if (resp.code in 300..399) {
                    if (++hops > MAX_REDIRECTS) return Fetched.Err("too many redirects")
                    val loc = resp.header("Location")?.trim().orEmpty()
                    if (loc.isEmpty()) return Fetched.Err("could not be loaded")
                    current = runCatching { java.net.URI(current).resolve(loc).toString() }
                        .getOrElse { return Fetched.Err("unusable link") }
                    continue
                }
                if (resp.code !in 200..299) {
                    Log.w(TAG, "attachment fetch from $where returned ${resp.code}")
                    return Fetched.Err("could not be loaded (${resp.code})")
                }

                // Content-Length is only a hint; the streaming cap below still applies.
                val body = resp.body ?: return Fetched.Err("could not be loaded")
                val declared = body.contentLength()
                if (declared > cap) {
                    Log.w(TAG, "attachment from $where refused: declared $declared bytes over the ${cap}-byte cap")
                    return Fetched.Err("too large (${declared / 1024} KiB)")
                }

                val bytes = readBounded(body.byteStream(), cap)
                if (bytes == null) {
                    spend.charge(cap)
                    Log.w(TAG, "attachment from $where refused: body exceeded the ${cap}-byte cap")
                    return Fetched.Err("too large")
                }
                spend.charge(bytes.size)
                Log.i(TAG, "attachment fetched from $where: ${bytes.size} bytes")
                return Fetched.Ok(bytes)
            } finally {
                resp.close()
            }
        }
    }

    // Returns null the moment the stream exceeds [cap], without reading the rest.
    internal fun readBounded(input: InputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(cap, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
