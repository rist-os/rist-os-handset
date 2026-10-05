package watch.rist.assistant

import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.CookieJar
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
    /**
     * Loaded from a url rather than sent as bytes (a Brave image-search picture). Held in memory
     * only, never kept or saved, nothing on it is tappable, and a failed one shows nothing at all.
     */
    val remote: Boolean = false,
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
            remote == other.remote &&
            (if (bytes == null) other.bytes == null else other.bytes != null && bytes.contentEquals(other.bytes))
    }

    override fun hashCode(): Int {
        var h = kind.hashCode()
        h = 31 * h + mime.hashCode()
        h = 31 * h + title.hashCode()
        h = 31 * h + text.hashCode()
        h = 31 * h + toolId.hashCode()
        h = 31 * h + (error?.hashCode() ?: 0)
        h = 31 * h + remote.hashCode()
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

    /**
     * The one host a picture is ever loaded from: Brave's image proxy. Exactly this name, https,
     * port 443, no user-info. Every other `uri`, on any attachment, is refused and shows nothing.
     */
    internal const val REMOTE_IMAGE_HOST = "imgs.search.brave.com"

    /** The whole load, every redirect hop and the body included. */
    internal const val REMOTE_LOAD_TIMEOUT_MS = 10_000L

    // Redirects are followed by hand in [fetch]; this is the hop budget.
    private const val MAX_REDIRECTS = 3

    // Test seam; nothing in the app writes it.
    internal var loadTimeoutMs = REMOTE_LOAD_TIMEOUT_MS

    /**
     * Test seam; nothing in the app writes it. Lets a test serve the allowed url from a local
     * server. The policy check has already passed on the real url before this is applied.
     */
    internal var dialForTest: ((HttpUrl) -> HttpUrl)? = null

    // No cookies, no cache, no authenticator, redirects by hand; newBuilder() in [fetch] inherits all.
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .build()
    }

    /**
     * Never throws; a failed attachment comes back carrying [RistAttachment.error]. [stillWanted]
     * is checked between attachments only, and the list stays 1:1 with the input (up to the cap).
     */
    fun resolve(
        protoList: List<rist.v1.Attachment>,
        stillWanted: () -> Boolean = { true },
    ): List<RistAttachment> {
        if (protoList.isEmpty()) return emptyList()

        val considered = protoList.take(MAX_PER_RESPONSE)
        if (protoList.size > considered.size) {
            Log.w(TAG, "dropping ${protoList.size - considered.size} attachment(s) over the cap of $MAX_PER_RESPONSE")
        }

        var budget = MAX_BYTES_PER_RESPONSE

        return considered.map { a ->
            if (!stillWanted()) return@map refuse(a, "abandoned", remote = isUrlOnly(a))

            // Charged for everything that crossed the wire, kept or not.
            val spend = Spend()
            val resolved = runCatching { resolveOne(a, budget, spend) }
                .getOrElse { t ->
                    // Throwable messages can carry the uri, so they are not logged.
                    Log.w(TAG, "attachment failed: ${t.javaClass.simpleName}")
                    refuse(a, "could not be loaded", remote = isUrlOnly(a))
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

    /** A picture whose content would come from `uri`: a failure on it shows nothing, never a card. */
    fun isUrlOnly(a: rist.v1.Attachment): Boolean =
        a.data.isEmpty && a.uri.isNotBlank() && normaliseKind(a.kind) == "image"

    private fun refuse(a: rist.v1.Attachment, why: String, remote: Boolean = false) = RistAttachment(
        kind = normaliseKind(a.kind),
        mime = a.mime,
        title = a.title,
        text = "",
        bytes = null,
        toolId = a.toolId,
        error = why,
        remote = remote,
    )

    private fun resolveOne(
        a: rist.v1.Attachment,
        budget: Int,
        spend: Spend,
    ): RistAttachment {
        val kind = normaliseKind(a.kind)
        val cap = minOf(MAX_BYTES_PER_ATTACHMENT, budget)
        if (cap <= 0) return refuse(a, "no room left in this response", remote = isUrlOnly(a))

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

        // A file sent as a link (a short-lived storage link, say) is never fetched: the phone has
        // nothing to open it with, so it gets the usual file card, size unknown, and no request.
        if (kind == "data") return RistAttachment(kind, a.mime, a.title, "", null, a.toolId, null)
        if (kind != "image") {
            Log.w(TAG, "refused a link attachment of kind '$kind'")
            return refuse(a, "refused a link")
        }
        // Only from Brave's image proxy. Any other picture link is skipped without a request.
        if (!isAllowedImageUrl(uri)) {
            Log.w(TAG, "refused a picture link")
            return refuse(a, "refused a link", remote = true)
        }
        // The credit must be shown whenever the picture is; without one the picture is not shown.
        if (a.title.isBlank()) return refuse(a, "no credit", remote = true)

        return when (val f = fetch(uri, cap, spend)) {
            is Fetched.Err -> refuse(a, f.why, remote = true)
            is Fetched.Ok ->
                if (!looksLikeWebImage(f.bytes) || !decodesAsImage(f.bytes)) {
                    Log.w(TAG, "refusing a ${f.bytes.size}-byte loaded picture: not a jpeg, png, webp or gif")
                    refuse(a, "not a valid image", remote = true)
                } else {
                    RistAttachment(kind, a.mime, a.title, "", f.bytes, a.toolId, null, remote = true)
                }
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
        fun ascii(i: Int, s: String) = s.indices.all { b[i + it] == s[it].code.toByte() }
        return when {
            looksLikeWebImage(b) -> true
            ascii(0, "BM") -> true                                                    // BMP
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") ||
                ascii(8, "mif1") || ascii(8, "avif")) -> true                         // HEIF / AVIF
            else -> false
        }
    }

    /** The four formats a loaded picture may be: jpeg, png, webp or gif. */
    internal fun looksLikeWebImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        fun at(i: Int, vararg v: Int) = v.indices.all { b[i + it] == v[it].toByte() }
        fun ascii(i: Int, s: String) = s.indices.all { b[i + it] == s[it].code.toByte() }
        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> true            // PNG
            at(0, 0xFF, 0xD8, 0xFF) -> true                                          // JPEG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> true                          // GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> true                              // WebP
            else -> false
        }
    }

    /**
     * True only for https on exactly [REMOTE_IMAGE_HOST], port 443, no user-info. Parsed twice,
     * strictly by java.net.URI and again by OkHttp, which is what dials; both must agree.
     */
    internal fun isAllowedImageUrl(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() || it.code < 0x20 || it == '\\' }) return false
        val u = runCatching { java.net.URI(s) }.getOrNull() ?: return false
        if (!u.isAbsolute || u.isOpaque) return false
        if (!"https".equals(u.scheme, ignoreCase = true)) return false
        if (u.rawUserInfo != null) return false
        if (!REMOTE_IMAGE_HOST.equals(u.host, ignoreCase = true)) return false
        if (u.port != -1 && u.port != 443) return false
        val h = s.toHttpUrlOrNull() ?: return false
        return h.isHttps && h.host == REMOTE_IMAGE_HOST && h.port == 443 &&
            h.username.isEmpty() && h.password.isEmpty()
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

    /**
     * A plain GET: no cookies, no credential of any kind, no Referer. Every hop, the first one
     * included, goes back through [isAllowedImageUrl]; one deadline covers the whole load.
     */
    private fun fetch(rawUri: String, cap: Int, spend: Spend): Fetched {
        val startedAt = System.nanoTime()
        var current = rawUri.trim()
        var hops = 0
        while (true) {
            if (!isAllowedImageUrl(current)) return Fetched.Err("refused a link")
            val real = current.toHttpUrlOrNull() ?: return Fetched.Err("refused a link")
            val dial = dialForTest?.invoke(real) ?: real

            val leftMs = loadTimeoutMs - (System.nanoTime() - startedAt) / 1_000_000
            if (leftMs <= 0) return Fetched.Err("timed out")
            val client = sharedClient.newBuilder()
                .connectTimeout(leftMs, TimeUnit.MILLISECONDS)
                .readTimeout(leftMs, TimeUnit.MILLISECONDS)
                // callTimeout bounds the whole hop, body included, against a server that dribbles.
                .callTimeout(leftMs, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(dial)
                .get()
                .header("Accept", "image/jpeg,image/png,image/webp,image/gif")
                .build()

            val resp = runCatching { client.newCall(request).execute() }
                .getOrElse { t ->
                    Log.w(TAG, "picture load failed: ${t.javaClass.simpleName}")
                    return Fetched.Err("could not be loaded")
                }
            try {
                if (resp.code in 300..399) {
                    if (++hops > MAX_REDIRECTS) return Fetched.Err("too many redirects")
                    val loc = resp.header("Location")?.trim().orEmpty()
                    if (loc.isEmpty()) return Fetched.Err("could not be loaded")
                    current = runCatching { java.net.URI(current).resolve(loc).toString() }
                        .getOrElse { return Fetched.Err("refused a link") }
                    continue
                }
                if (resp.code != 200) {
                    Log.w(TAG, "picture load returned ${resp.code}")
                    return Fetched.Err("could not be loaded (${resp.code})")
                }

                // Content-Length is only a hint; the streaming cap below still applies.
                val body = resp.body ?: return Fetched.Err("could not be loaded")
                val declared = body.contentLength()
                if (declared > cap) {
                    Log.w(TAG, "picture refused: declared $declared bytes over the ${cap}-byte cap")
                    return Fetched.Err("too large (${declared / 1024} KiB)")
                }

                val bytes = runCatching { readBounded(body.byteStream(), cap) }
                    .getOrElse { t ->
                        Log.w(TAG, "picture load failed mid-body: ${t.javaClass.simpleName}")
                        return Fetched.Err("could not be loaded")
                    }
                if (bytes == null) {
                    spend.charge(cap)
                    Log.w(TAG, "picture refused: body exceeded the ${cap}-byte cap")
                    return Fetched.Err("too large")
                }
                spend.charge(bytes.size)
                Log.i(TAG, "picture loaded: ${bytes.size} bytes")
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

