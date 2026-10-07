package watch.rist.assistant

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/** statusText is GONE on this layout, so a failure written only there is never seen. */
@RunWith(RobolectricTestRunner::class)
class FailureVisibilityTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clean() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.READ_SMS)
        Config.setEnrolRevoked(ctx(), false)
        Config.setCredentialRejected(ctx(), false)
        Config.clearBillingLapse(ctx())
        StreamingCancel.resetForTest()
    }

    @After
    fun tidy() {
        Config.clearBillingLapse(ctx())
        Config.setCredentialRejected(ctx(), false)
    }

    private fun home(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).create().get()

    @Test
    fun `our own reasons get an apology, the backend's sentence is shown as it is`() {
        assertEquals("Sorry — the assistant is busy — try again in a moment.",
            MainActivity.failureLine("the assistant is busy — try again in a moment"))
        assertEquals("Sorry — I can't reach the network.", MainActivity.failureLine("I can't reach the network"))
        val renew = Billing.fallbackLine("ristmobile.com")
        assertEquals(renew, MainActivity.failureLine(renew))
        assertEquals("Sorry — something went wrong reaching the network.", MainActivity.failureLine(" "))
    }

    @Test
    fun `a 402, 403, 429 or 503 failure is put on screen, not only in the hidden status line`() {
        val a = home()
        for (reason in listOf(
            Billing.fallbackLine("ristmobile.com"),
            "this device's access has been turned off — pair it again in Settings",
            "the assistant is busy — try again in a moment",
            "the assistant is briefly unavailable — trying again shortly",
        )) {
            a.announceFailure(reason)
            assertEquals(MainActivity.failureLine(reason), ShadowToast.getTextOfLatestToast())
        }
    }

    private fun children(g: ViewGroup): List<View> = (0 until g.childCount).map { g.getChildAt(it) }

    @Test
    fun `a lapse shows the renew line and an Update payment button in the feed`() {
        Billing.onLapsed(ctx(), Billing.lapseFrom("lapsed", "ristmobile.com", null))
        val a = home()
        CommsFeedView.render(a)
        val host = a.findViewById<LinearLayout>(R.id.commsFeed)
        assertEquals(View.VISIBLE, host.visibility)
        val row = host.findViewWithTag<ViewGroup>(CommsFeedView.BILLING_ROW_TAG)
        assertNotNull("no billing row in the feed", row)
        val line = children(row).filterIsInstance<TextView>().first()
        assertEquals(Billing.fallbackLine("ristmobile.com"), line.text.toString())
        val button = row.findViewWithTag<TextView>(CommsFeedView.BILLING_BUTTON_TAG)
        assertNotNull("no Update payment button", button)
        assertEquals(a.getString(R.string.billing_update_payment), button.text.toString())
        assertEquals(true, button.isClickable)
    }

    @Test
    fun `an account with no subscription page gets the line but no button`() {
        Billing.onLapsed(ctx(), Billing.lapseFrom("lapsed", null, null))
        Config.setBillingNoPortal(ctx(), true)
        val a = home()
        CommsFeedView.render(a)
        val row = a.findViewById<LinearLayout>(R.id.commsFeed).findViewWithTag<ViewGroup>(CommsFeedView.BILLING_ROW_TAG)
        assertNotNull(row)
        assertNull(row.findViewWithTag<TextView>(CommsFeedView.BILLING_BUTTON_TAG))
    }

    @Test
    fun `once served again the billing row is gone`() {
        Billing.onLapsed(ctx(), Billing.lapseFrom("lapsed", null, null))
        Billing.onServed(ctx())
        val a = home()
        CommsFeedView.render(a)
        assertNull(a.findViewById<LinearLayout>(R.id.commsFeed).findViewWithTag<View>(CommsFeedView.BILLING_ROW_TAG))
    }
}
