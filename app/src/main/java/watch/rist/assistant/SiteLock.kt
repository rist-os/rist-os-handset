package watch.rist.assistant

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The rules of the locked browser: a scanned code opens one site, and the page cannot be used
 * to go anywhere else.
 *
 * "Site" is the registrable domain (menu.example.com and pay.example.com are both example.com),
 * taken from OkHttp's bundled public-suffix list so that example.co.uk is a site and co.uk is
 * not. Only what the page itself navigates to is judged. What a page loads inside itself
 * (images, scripts, fonts from other hosts) is left alone, or almost no site would draw.
 */
object SiteLock {


    /**
     * What a scanned code opens, or null when it is not a web link. Always https: the phone
     * permits no cleartext traffic, so an http link is tried over TLS rather than refused.
     */
    fun openable(scanned: String): String? {
        val text = scanned.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() }) return null
        val lower = text.lowercase()
        val candidate = when {
            lower.startsWith("https://") -> text
            lower.startsWith("http://") -> "https://" + text.substring("http://".length)
            // "example.com/menu" printed without a scheme. Anything else with a scheme is not ours.
            "://" !in text && ':' !in text.substringBefore('/') && '.' in text.substringBefore('/') ->
                "https://$text"
            else -> return null
        }
        val url = candidate.toHttpUrlOrNull() ?: return null
        // A link carrying a user name is how "https://bank.com@evil.example" is dressed up.
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return url.toString()
    }

    /** The registrable domain, or the bare host where there is none (an IP, an intranet name). */
    fun siteOf(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        return siteOf(parsed)
    }

    private fun siteOf(url: HttpUrl): String = (url.topPrivateDomain() ?: url.host).lowercase()

    enum class Verdict {
        ALLOW,
        /** Another site. The person may let it through by name. */
        LEAVES_SITE,
        /** Not a web page at all: another app, a phone number, a file. Never offered. */
        NOT_WEB,
    }

    /**
     * One visit. The site is not fixed by the scanned link but by where that link LANDS: codes
     * are routinely short links and trackers that redirect. So until the first page is drawn, or
     * the person touches it, each navigation moves the lock along with it; from then on it is
     * fixed, and nothing the page does later (an ad, a script) can carry the lock elsewhere.
     *
     * Synchronized: the page's own requests are judged on WebView's network thread.
     */
    class Session(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {

        private var landed = false
        private var firstDrawnAt = -1L

        /** The site the visit is locked to; null until the first page has been reached. */
        @get:Synchronized
        var site: String? = null
            private set

        val locked: Boolean @Synchronized get() = landed || firstDrawnAt >= 0

        @Synchronized fun onTouched() { if (site != null) landed = true }

        @Synchronized fun onPageDrawn() { if (firstDrawnAt < 0) firstDrawnAt = now() }

        /** A main-frame page was reached. Moves the lock only while the landing chain is open. */
        @Synchronized fun onArrived(url: String) {
            val s = siteOf(url) ?: return
            if (!locked || site == null) site = s
        }

        @Synchronized fun decide(url: String, mainFrame: Boolean, gesture: Boolean): Verdict {
            val lower = url.trim().lowercase()
            if (lower == "about:blank" || lower.startsWith("about:srcdoc")) return Verdict.ALLOW
            if (!lower.startsWith("https://")) return Verdict.NOT_WEB
            val target = siteOf(url) ?: return Verdict.NOT_WEB
            // An embed loading by itself is part of the page. A frame the person clicks a link in
            // is a way out of the site, and is judged as one.
            if (!mainFrame && !gesture) return Verdict.ALLOW
            val home = site
            return when {
                home == null || !locked -> Verdict.ALLOW
                target == home -> Verdict.ALLOW
                else -> Verdict.LEAVES_SITE
            }
        }
    }
}
