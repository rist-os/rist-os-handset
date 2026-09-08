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
            .retryOnConnectionFailure(true)
            .build()
    }

    fun resolveEnclosure(feedUrl: String, section: Int = 0): String? = runCatching {
        val body = fetch(feedUrl)?.second ?: return null
        parseEnclosure(body, section)
    }.onFailure { Log.w(TAG, "rss resolve failed for $feedUrl", it) }.getOrNull()

    fun resolveIfFeed(url: String, section: Int = 0): String? = runCatching {
        val (contentType, body) = fetch(url) ?: return null
        val ct = contentType.orEmpty().lowercase()
        val head = body.trimStart()
        val looksXml = ct.contains("xml") || ct.contains("rss") ||
            head.startsWith("<?xml") || head.startsWith("<rss") || head.startsWith("<feed")
        if (looksXml) parseEnclosure(body, section) else null
    }.onFailure { Log.w(TAG, "feed sniff failed for $url", it) }.getOrNull()

    private fun fetch(url: String): Pair<String?, String>? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "RIST/1.0")
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w(TAG, "rss HTTP ${resp.code} for $url"); return null }
            val ct = resp.header("Content-Type")
            val text = resp.body?.string()
            if (text.isNullOrEmpty()) { Log.w(TAG, "empty rss body for $url"); return null }
            return ct to text
        }
    }

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
        Log.i(TAG, "resolved episode (section=$section) → $chosen (${enclosures.size} in feed)")
        return chosen
    }
}
