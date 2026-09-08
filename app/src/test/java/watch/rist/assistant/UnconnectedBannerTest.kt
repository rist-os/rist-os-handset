package watch.rist.assistant

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class UnconnectedBannerTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clean() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.READ_SMS)
        Config.setAuthToken(ctx(), "")
        Config.setSetupComplete(ctx(), false)
        Config.setCarrierVoicemailWaiting(ctx(), false)
        Config.setEnrolRevoked(ctx(), false)
        Config.setCredentialRejected(ctx(), true)
    }

    @After
    fun tidy() {
        Config.setCredentialRejected(ctx(), false)
        Config.setSetupComplete(ctx(), false)
        Config.setCarrierVoicemailWaiting(ctx(), false)
    }

    private fun homeWithFeedPainted(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).create().get()
        assertTrue(
            "this device must have no credential or there is no banner to test — see the " +
                "keystore test below for why this is structural here, not a fixture choice",
            Enrolment.needed(a)
        )
        CommsFeedView.render(a)
        return a
    }

    private fun feed(a: MainActivity): LinearLayout =
        requireNotNull(a.findViewById<LinearLayout>(R.id.commsFeed)) {
            "R.id.commsFeed is missing from activity_main.xml — the feed has no host at all"
        }

    private fun children(g: ViewGroup): List<View> = (0 until g.childCount).map { g.getChildAt(it) }

    private fun banner(a: MainActivity, g: ViewGroup): TextView? {
        val never = a.getString(R.string.status_never_connected)
        val lost = a.getString(R.string.status_connection_lost)
        return children(g).filterIsInstance<TextView>()
            .firstOrNull { it.text.toString() == never || it.text.toString() == lost }
    }

    @Test
    fun `an unpaired device is told so, in a container that is actually visible`() {
        val a = homeWithFeedPainted()
        val host = feed(a)
        val row = banner(a, host)
        assertNotNull(
            "no row in R.id.commsFeed carries the not-connected copy — the banner is unreachable, " +
                "which is exactly how the statusText version failed",
            row
        )
        assertEquals(
            "an unpaired device must be told it has never connected",
            a.getString(R.string.status_never_connected), row!!.text.toString()
        )
        assertEquals(
            "the feed host is still GONE, so the banner was drawn into an invisible container",
            View.VISIBLE, host.visibility
        )
        assertEquals(View.VISIBLE, row.visibility)
        assertTrue("the banner must say something", row.text.isNotBlank())
    }

    @Test
    fun `the banner is the tap target, and is big enough to be hit`() {
        val a = homeWithFeedPainted()
        val row = requireNotNull(banner(a, feed(a))) { "no banner to press" }
        assertTrue(
            "the banner is not clickable — it tells the user to tap something that ignores taps",
            row.isClickable
        )
        assertTrue(
            "the banner is not focusable, so it cannot be reached without a touchscreen",
            row.isFocusable
        )
        val floor = (48 * a.resources.displayMetrics.density).toInt()
        assertTrue(
            "the banner's minHeight is ${row.minHeight}px, below the ${floor}px (48dp) minimum " +
                "touch target — the only control that can pair this phone must be hittable",
            row.minHeight >= floor
        )
    }

    @Test
    fun `the banner is drawn above every other row in the feed`() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .denyPermissions(Manifest.permission.READ_SMS)
        Config.setCarrierVoicemailWaiting(ctx(), true)

        val a = homeWithFeedPainted()
        val host = feed(a)
        assertTrue(
            "this test needs the unreadable-texts notice present to have something to outrank",
            !SmsInbox.canRead(a)
        )
        assertTrue(
            "this test needs the voicemail row present to have something to outrank",
            CarrierVoicemail.waiting(a)
        )

        val kids = children(host)
        val bannerIndex = kids.indexOf(requireNotNull(banner(a, host)) { "no banner to order" })
        assertTrue("the banner is not in the feed at all", bannerIndex >= 0)

        val noticeIndex = kids.indexOfFirst {
            it is TextView && it.text.toString().startsWith("Texts are not shown")
        }
        val voicemailIndex = kids.indexOfFirst { it is ViewGroup && describes(it, "VOICEMAIL") }
        assertTrue("the unreadable-texts notice was not drawn", noticeIndex >= 0)
        assertTrue("the voicemail row was not drawn", voicemailIndex >= 0)

        assertTrue(
            "the banner (index $bannerIndex) is below the unreadable-texts notice " +
                "(index $noticeIndex) — a device that cannot talk to the backend at all is being " +
                "shown a permission problem first",
            bannerIndex < noticeIndex
        )
        assertTrue(
            "the banner (index $bannerIndex) is below the voicemail row (index $voicemailIndex)",
            bannerIndex < voicemailIndex
        )
        assertEquals(
            "something is drawn above the banner — children above it are ${kids.take(bannerIndex)}",
            0, bannerIndex
        )
    }

    private fun describes(v: View, needle: String): Boolean =
        v.contentDescription?.toString()?.contains(needle) == true

    @Test
    fun `a device that once had a credential is told the connection was lost, not that it never had one`() {
        Config.setSetupComplete(ctx(), true)
        assertTrue(
            "the setup-complete flag did not stick; the two states cannot be told apart",
            Config.isSetupComplete(ctx())
        )
        val a = homeWithFeedPainted()
        val text = requireNotNull(banner(a, feed(a))) { "no banner" }.text.toString()
        assertEquals(a.getString(R.string.status_connection_lost), text)
        assertNotEquals(
            "a phone that has been paired before must not be told it never was",
            a.getString(R.string.status_never_connected), text
        )
    }

    @Test
    fun `a device that never had a credential is told how to connect, not that something broke`() {
        val a = homeWithFeedPainted()
        val text = requireNotNull(banner(a, feed(a))) { "no banner" }.text.toString()
        assertEquals(a.getString(R.string.status_never_connected), text)
        assertNotEquals(
            "a brand-new phone must not be told its connection was lost",
            a.getString(R.string.status_connection_lost), text
        )
    }

    @Test
    fun `the feed is shown with nothing in it but the unconnected banner`() {
        val a = homeWithFeedPainted()
        val host = feed(a)

        assertTrue("a text row exists; this is no longer the empty case", SmsInbox.canRead(a))
        assertTrue("a voicemail is waiting; this is no longer the empty case", !CarrierVoicemail.waiting(a))
        assertTrue(
            "the feed has arrivals in it; this is no longer the empty case",
            CommsFeedView.candidates(a).isEmpty()
        )

        assertEquals(
            "the feed is hidden on a device whose only news IS the banner — the exact state the " +
                "banner was written for",
            View.VISIBLE, host.visibility
        )
        assertNotNull("nothing was drawn", banner(a, host))
        assertEquals(
            "expected the banner alone, with no section header over an empty section, " +
                "got ${children(host)}",
            1, host.childCount
        )
    }

    @Test
    fun `a credential cannot be stored in this environment, so connected is unreachable`() {
        Config.setAuthToken(ctx(), "ristd_test.secret")
        assertEquals(
            "the token stuck, which means the secret-filtering fallback is no longer in play — " +
                "the negative case is now testable and this file should grow a test for it",
            "", Config.authToken(ctx())
        )
        assertTrue(
            "Enrolment.needed() is false without a stored token, which cannot be right",
            Enrolment.needed(ctx())
        )
    }

    @Test
    fun `a device the backend has not refused shows no banner at all`() {
        Config.setCredentialRejected(ctx(), false)
        Config.setEnrolRevoked(ctx(), false)
        val a = Robolectric.buildActivity(MainActivity::class.java).create().get()
        CommsFeedView.render(a)
        val host = a.findViewById<LinearLayout>(R.id.commsFeed)
        assertNull(
            "a working device is being told it is not connected — this is the state of every " +
                "handset the backend has not refused",
            banner(a, host)
        )
        assertEquals(
            "the feed host must stay hidden when there is nothing whatever to say",
            View.GONE, host.visibility
        )
    }

    @Test
    fun `a revoked device gets its own message, not an offer to reconnect`() {
        Config.setCredentialRejected(ctx(), false)
        Config.setEnrolRevoked(ctx(), true)
        val a = Robolectric.buildActivity(MainActivity::class.java).create().get()
        CommsFeedView.render(a)
        val host = a.findViewById<LinearLayout>(R.id.commsFeed)
        assertEquals(
            "a revoked device must still be told something on the home screen",
            View.VISIBLE, host.visibility
        )
        val texts = children(host).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(
            "expected the revoked copy, got $texts",
            texts.any { it == a.getString(R.string.status_revoked) }
        )
        assertFalse(
            "re-pairing cannot lift a revocation, so the row must not offer to reconnect",
            texts.any { it == a.getString(R.string.status_connection_lost) }
        )
    }

    @Test
    fun `tapping the banner opens Settings, scrolled to the BACKEND section`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).create().get()
        CommsFeedView.render(a)
        val host = a.findViewById<LinearLayout>(R.id.commsFeed)
        val row = requireNotNull(banner(a, host)) { "no banner to tap" }

        assertTrue("the banner must be tappable", row.performClick())

        val started = requireNotNull(shadowOf(a).nextStartedActivity) {
            "tapping the banner started nothing — it is the only route to the pairing field"
        }
        assertEquals(
            SettingsActivity::class.java.name,
            started.component?.className
        )
        assertTrue(
            "the destination must land on the BACKEND section, not the top of a long screen",
            started.getBooleanExtra(SettingsActivity.EXTRA_SHOW_BACKEND, false)
        )
        assertTrue(
            "arriving from the home feed, the apps row is redundant",
            started.getBooleanExtra(SettingsActivity.EXTRA_HIDE_APPS, false)
        )
    }
}
