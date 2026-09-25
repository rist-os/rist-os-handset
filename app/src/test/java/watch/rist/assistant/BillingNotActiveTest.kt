package watch.rist.assistant

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A 402 for a subscription that has not started yet is not the same as one that has ended. */
@RunWith(RobolectricTestRunner::class)
class BillingNotActiveTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        Config.clearBillingLapse(ctx)
        Config.setEnrolRevoked(ctx, false)
        Config.setCredentialRejected(ctx, false)
        StreamingCancel.resetForTest()
    }

    @After
    fun tidy() = clean()

    private fun notActive(accountUrl: String? = null) =
        Billing.lapseFrom(Billing.REASON_NOT_ACTIVE, "ristassistant.com", null, accountUrl)

    @Test
    fun `not active yet and ended say different things`() {
        assertEquals("Your Rist Assistant subscription isn't active yet — finish signing up at ristassistant.com.",
            Billing.lineFor(notActive()))
        assertEquals("Your Rist Assistant subscription has ended — renew at ristmobile.com.",
            Billing.lineFor(Billing.lapseFrom("lapsed", "ristmobile.com", null)))
        assertEquals(Billing.fallbackLine("x.com"), Billing.lineFor(Billing.lapseFrom("a-reason-from-later", "x.com", null)))
    }

    @Test
    fun `not active yet offers the account page, not the payment page`() {
        Billing.onLapsed(ctx, notActive())
        assertFalse(Billing.offersPayment(ctx))
        assertTrue(Billing.offersAccountPage(ctx))
        assertEquals("https://ristassistant.com/", Billing.accountPage(ctx))
    }

    @Test
    fun `an ended subscription still offers the payment page`() {
        Billing.onLapsed(ctx, Billing.lapseFrom("ended", "ristmobile.com", null))
        assertTrue(Billing.offersPayment(ctx))
        assertFalse(Billing.offersAccountPage(ctx))
        Config.setBillingNoPortal(ctx, true)
        assertTrue("no subscription to update: the account page instead", Billing.offersAccountPage(ctx))
    }

    @Test
    fun `the account page opens only on the account site over https`() {
        assertEquals("https://ristassistant.com/account", Billing.accountPage(notActive("https://ristassistant.com/account")))
        assertNull(Billing.accountPage(notActive("http://ristassistant.com/account")))
        assertNull(Billing.accountPage(notActive("https://ristassistant.com.evil.example/account")))
        assertNull(Billing.accountPage(notActive("https://evil.example/ristassistant.com")))
        assertNull(Billing.accountPage(notActive("https://someone@ristassistant.com/")))
        assertNull(Billing.accountPage(notActive("https://ristassistant.com:8443/")))
    }

    @Test
    fun `the feed shows the not-active line and an account page button that opens the locked browser`() {
        Billing.onLapsed(ctx, notActive("https://ristassistant.com/account"))
        val a = Robolectric.buildActivity(MainActivity::class.java).create().get()
        CommsFeedView.render(a)
        val row = a.findViewById<LinearLayout>(R.id.commsFeed).findViewWithTag<ViewGroup>(CommsFeedView.BILLING_ROW_TAG)
        assertNotNull(row)
        assertEquals(Billing.lineFor(notActive()), (row.getChildAt(0) as TextView).text.toString())
        assertNull(row.findViewWithTag<TextView>(CommsFeedView.BILLING_BUTTON_TAG))
        val open = row.findViewWithTag<TextView>(CommsFeedView.BILLING_ACCOUNT_TAG)
        assertNotNull(open)
        open.performClick()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(LockedBrowserActivity::class.java.name, started?.component?.className)
        assertEquals("https://ristassistant.com/account", started!!.getStringExtra(LockedBrowserActivity.EXTRA_URL))
    }

    @Test
    fun `a not-active 402 on a turn keeps the token and records the reason`() {
        val server = MockWebServer()
        server.start()
        try {
            Config.setDeployDefaultsForTest("", "")
            Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
            server.enqueue(MockResponse().setResponseCode(402)
                .addHeader("X-Rist-Billing", Billing.REASON_NOT_ACTIVE)
                .addHeader("X-Rist-Renew-Url", "ristassistant.com")
                .addHeader("X-Rist-Account-Url", "https://ristassistant.com/account"))
            val up = Uploader(ctx)
            assertNull(up.sendText("hello"))
            assertEquals(Billing.notActiveLine("ristassistant.com"), up.lastFailure)
            assertTrue(Billing.lapse(ctx)!!.notActiveYet)
            assertEquals("https://ristassistant.com/account", Billing.accountPage(ctx))
            assertFalse(Config.enrolRevoked(ctx))
            assertFalse(Config.credentialRejected(ctx))
        } finally {
            Config.clearDeployDefaultsForTest()
            runCatching { server.shutdown() }
        }
    }
}
