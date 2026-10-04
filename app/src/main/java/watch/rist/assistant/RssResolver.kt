package watch.rist.assistant

import android.util.Log
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit

object RssResolver {
    private const val TAG = "RistRss"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Big enough for a long-running show's full feed; an endless or audio body stops here. */
    internal const val MAX_FEED_BYTES = 10 * 1024 * 1024
    private const val SNIFF_BYTES = 512L

    fun resolveEnclosure(feedUrl: String, section: Int = 0): String? = runCatching {
        val body = fetch(feedUrl) ?: return null
        parseEnclosure(body, section)
    }.onFailure { Log.w(TAG, "rss resolve failed for ${host(feedUrl)}", it) }.getOrNull()

    fun resolveIfFeed(url: String, section: Int = 0): String? = runCatching {
        val body = fetch(url) ?: return null
        parseEnclosure(body, section)
    }.onFailure { Log.w(TAG, "feed sniff failed for ${host(url)}", it) }.getOrNull()

    internal fun mediaType(contentType: String?): Boolean {
        val ct = contentType.orEmpty().lowercase()
        return ct.startsWith("audio/") || ct.startsWith("video/") || ct.startsWith("application/octet-stream")
    }

    internal fun looksLikeFeed(contentType: String?, head: String): Boolean {
        val ct = contentType.orEmpty().lowercase()
        val h = head.trimStart().removePrefix("\uFEFF").trimStart()
        return ct.contains("xml") || ct.contains("rss") ||
            h.startsWith("<?xml") || h.startsWith("<rss") || h.startsWith("<feed")
    }

    /** Headers and the first bytes decide; only something that looks like a feed is read, and only so far. */
    private fun fetch(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "RIST/1.0")
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w(TAG, "rss HTTP ${resp.code} for ${host(url)}"); return null }
            val ct = resp.header("Content-Type")
            if (mediaType(ct)) return null
            val declared = resp.body?.contentLength() ?: -1L
            if (declared > MAX_FEED_BYTES) { Log.w(TAG, "feed too large ($declared bytes)"); return null }
            val head = runCatching { resp.peekBody(SNIFF_BYTES).string() }.getOrDefault("")
            if (!looksLikeFeed(ct, head)) return null
            val body = resp.body ?: return null
            val bytes = Attachments.readBounded(body.byteStream(), MAX_FEED_BYTES)
                ?: run { Log.w(TAG, "feed passed $MAX_FEED_BYTES bytes; not reading the rest"); return null }
            if (bytes.isEmpty()) { Log.w(TAG, "empty rss body for ${host(url)}"); return null }
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun host(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"

    internal fun parseEnclosure(xml: String, section: Int): String? {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())

        val enclosures = ArrayList<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("enclosure", ignoreCase = true)) {
                val u = parser.getAttributeValue(null, "url")
                if (!u.isNullOrBlank()) enclosures.add(u.trim())
            }
            event = parser.next()
        }

        if (enclosures.isEmpty()) { Log.w(TAG, "feed had no <enclosure>"); return null }
        val chosen = if (section in 1..enclosures.size) enclosures[section - 1] else enclosures.first()
        Log.i(TAG, "resolved episode (section=$section) on ${host(chosen)} (${enclosures.size} in feed)")
        return chosen
    }
}
