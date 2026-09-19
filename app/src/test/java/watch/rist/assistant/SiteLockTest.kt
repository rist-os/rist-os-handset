package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import watch.rist.assistant.SiteLock.Verdict

/** A scanned code opens one site, and the page cannot be used to reach another. */
class SiteLockTest {

    private var clock = 0L
    private fun session() = SiteLock.Session { clock }

    private fun landedOn(url: String) = session().apply {
        onArrived(url); onPageDrawn(); onTouched()
    }

    @Test
    fun `a scanned web link opens over https, whatever it was printed as`() {
        assertEquals("https://example.com/menu", SiteLock.openable("https://example.com/menu"))
        assertEquals("https://example.com/menu", SiteLock.openable(" http://example.com/menu\n"))
        assertEquals("https://example.com/menu", SiteLock.openable("example.com/menu"))
        assertEquals("https://example.com/", SiteLock.openable("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun `what is not a web link is never opened`() {
        assertNull(SiteLock.openable("WIFI:T:WPA;S:cafe;P:secret;;"))
        assertNull(SiteLock.openable("tel:+15551234567"))
        assertNull(SiteLock.openable("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(SiteLock.openable("javascript:alert(1)"))
        assertNull(SiteLock.openable("file:///data/data/watch.rist.assistant/"))
        assertNull(SiteLock.openable("just some words"))
        assertNull(SiteLock.openable("localhost:8080/admin"))
        assertNull(SiteLock.openable(""))
    }

    @Test
    fun `a link dressed up with a user name is refused`() {
        assertNull(SiteLock.openable("https://bank.com@evil.example/login"))
    }

    @Test
    fun `a site is the registrable domain, not the host and not the suffix`() {
        assertEquals("example.com", SiteLock.siteOf("https://menu.example.com/a"))
        assertEquals("example.co.uk", SiteLock.siteOf("https://pay.example.co.uk/"))
        assertEquals("192.168.1.10", SiteLock.siteOf("https://192.168.1.10/"))
        // Two tenants of one host are two sites.
        assertEquals("alice.github.io", SiteLock.siteOf("https://alice.github.io/x"))
    }

    @Test
    fun `within the site everything opens, on any subdomain`() {
        val s = landedOn("https://menu.example.com/")
        assertEquals(Verdict.ALLOW, s.decide("https://menu.example.com/drinks", true, true))
        assertEquals(Verdict.ALLOW, s.decide("https://order.example.com/cart", true, true))
    }

    @Test
    fun `a link to anywhere else is stopped, and so is a redirect there`() {
        val s = landedOn("https://menu.example.com/")
        assertEquals(Verdict.LEAVES_SITE, s.decide("https://news.other.org/", true, true))
        assertEquals(Verdict.LEAVES_SITE, s.decide("https://news.other.org/", true, false))
        assertEquals(Verdict.LEAVES_SITE, s.decide("https://example.com.evil.net/", true, true))
    }

    @Test
    fun `the lock follows a short link to where it lands, then stays put`() {
        val s = session()
        assertEquals(Verdict.ALLOW, s.decide("https://qr.short.ly/abc", true, false))
        s.onArrived("https://qr.short.ly/abc")
        assertEquals(Verdict.ALLOW, s.decide("https://menu.example.com/", true, false))
        s.onArrived("https://menu.example.com/")
        s.onPageDrawn()
        assertEquals("example.com", s.site)

        s.onTouched()
        assertTrue(s.locked)
        assertEquals(Verdict.LEAVES_SITE, s.decide("https://qr.short.ly/abc", true, true))
        s.onArrived("https://elsewhere.net/")
        assertEquals("example.com", s.site)
    }

    @Test
    fun `an untouched page cannot wander off later either`() {
        val s = session()
        s.onArrived("https://menu.example.com/"); s.onPageDrawn()
        clock = SiteLock.LANDING_GRACE_MS - 1
        assertFalse(s.locked)
        clock = SiteLock.LANDING_GRACE_MS
        assertTrue(s.locked)
        assertEquals(Verdict.LEAVES_SITE, s.decide("https://ads.other.org/", true, false))
    }

    @Test
    fun `a touch before any page has arrived locks nothing`() {
        val s = session()
        s.onTouched()
        assertFalse(s.locked)
    }

    @Test
    fun `a site let through by name stays open for the visit, and only that site`() {
        val s = landedOn("https://menu.example.com/")
        s.allow("pay.stripe.com".let { SiteLock.siteOf("https://$it/")!! })
        assertEquals(Verdict.ALLOW, s.decide("https://pay.stripe.com/checkout", true, false))
        assertEquals(Verdict.ALLOW, s.decide("https://menu.example.com/thanks", true, false))
        assertEquals(Verdict.LEAVES_SITE, s.decide("https://other.org/", true, true))
    }

    @Test
    fun `other apps, phone numbers, files and cleartext are never a way out`() {
        val s = landedOn("https://menu.example.com/")
        for (url in listOf(
            "intent://x#Intent;package=com.android.settings;end", "market://details?id=x",
            "tel:911", "mailto:a@b.c", "sms:123", "file:///sdcard/x", "content://contacts/1",
            "javascript:void(0)", "data:text/html,<h1>x</h1>", "http://menu.example.com/",
        )) assertEquals(url, Verdict.NOT_WEB, s.decide(url, true, true))
    }

    @Test
    fun `an embed loads by itself, but a link clicked inside it is judged like any other`() {
        val s = landedOn("https://menu.example.com/")
        assertEquals(Verdict.ALLOW, s.decide("https://maps.embed.org/frame", false, false))
        assertEquals(Verdict.LEAVES_SITE, s.decide("https://maps.embed.org/elsewhere", false, true))
        assertEquals(Verdict.ALLOW, s.decide("about:blank", false, false))
    }
}
