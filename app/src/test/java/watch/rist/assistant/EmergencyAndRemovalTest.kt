package watch.rist.assistant

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The emergency button is on the home screen whatever state the phone is in, and a phone removed
 * from its account is told so plainly, with the way back, and is never reset.
 */
@RunWith(RobolectricTestRunner::class)
class EmergencyAndRemovalTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @Before
    fun clean() {
        Config.setEnrolRevoked(ctx, false)
        Config.setRemovedNoticeShown(ctx, false)
        Config.setCredentialRejected(ctx, false)
        Config.clearBillingLapse(ctx)
        Config.setFeatures(ctx, "")
        StreamingCancel.resetForTest()
    }

    @After
    fun tidy() = clean()

    private fun home(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).create().get()

    private fun nextStarted(): Intent? = shadowOf(app).nextStartedActivity

    @Test
    fun `the emergency button opens the system emergency dialer`() {
        assertTrue(EmergencyDial.open(ctx))
        val started = nextStarted()
        assertNotNull(started)
        assertEquals(EmergencyDial.ACTION_EMERGENCY_DIAL, started!!.action)
        assertEquals(EmergencyDial.PKG_TELEPHONY, started.`package`)
    }

    @Test
    fun `the emergency dialer's package is allowed under lock task`() {
        val allowed = KioskManager.buildAllowlist(ctx).toSet()
        assertTrue(EmergencyDial.PKG_TELEPHONY in allowed)
        assertTrue("com.android.server.telecom" in allowed)
    }

    private fun assertEmergencyOnHome(a: MainActivity) {
        val b = a.findViewById<TextView>(R.id.emergencyButton)
        assertNotNull("no emergency button on the home screen", b)
        assertEquals(View.VISIBLE, b.visibility)
        assertTrue(b.isClickable)
        while (nextStarted() != null) Unit
        b.performClick()
        assertEquals(EmergencyDial.ACTION_EMERGENCY_DIAL, nextStarted()?.action)
    }

    @Test
    fun `the emergency button is on the home screen of a phone that was never paired`() {
        assertEmergencyOnHome(home())
    }

    @Test
    fun `the emergency button is on the home screen of a removed, lapsed phone with nothing launched`() {
        Config.setEnrolRevoked(ctx, true)
        Config.setRemovedNoticeShown(ctx, true)
        Billing.onLapsed(ctx, Billing.lapseFrom("lapsed", null, null))
        Features.apply(ctx, rist.v1.FeatureSet.getDefaultInstance())
        assertEmergencyOnHome(home())
    }

    @Test
    fun `a removal is told once, on its own screen`() {
        Enrolment.onRevoked(ctx)
        assertTrue(Config.enrolRevoked(ctx))
        val a = home()
        assertTrue(RemovedActivity.isDue(a))
        assertTrue(RemovedActivity.showIfDue(a))
        assertEquals(RemovedActivity::class.java.name, nextStarted()?.component?.className)

        val screen = Robolectric.buildActivity(RemovedActivity::class.java).create().get()
        val root = screen.window.decorView
        assertEquals(screen.getString(R.string.removed_title),
            root.findViewWithTag<TextView>(RemovedActivity.TAG_TITLE).text.toString())
        assertNotNull(root.findViewWithTag<View>(RemovedActivity.TAG_PAIR))
        assertNotNull(root.findViewWithTag<View>(RemovedActivity.TAG_EMERGENCY))
        assertFalse("shown once per removal", RemovedActivity.isDue(a))
    }

    @Test
    fun `a turn refused with 403 while the home screen is open shows the removed screen at once`() {
        val a = home()
        Config.setRemovedNoticeShown(ctx, true)
        while (shadowOf(a).nextStartedActivity != null) Unit
        Enrolment.onRevoked(ctx)
        a.announceFailure("this phone was removed from your account — pair it again in Settings")
        assertEquals(RemovedActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
    }

    @Test
    fun `the removed screen leads to pairing, not to a reset`() {
        Enrolment.onRevoked(ctx)
        val screen = Robolectric.buildActivity(RemovedActivity::class.java).create().get()
        screen.window.decorView.findViewWithTag<View>(RemovedActivity.TAG_PAIR).performClick()
        val next = shadowOf(screen).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, next?.component?.className)
        assertTrue(next!!.getBooleanExtra(SettingsActivity.EXTRA_SHOW_BACKEND, false))
        assertTrue("pairing is open again", Enrolment.canPair(ctx))
    }

    @Test
    fun `a second removal after coming back is told again`() {
        Enrolment.onRevoked(ctx)
        Config.setRemovedNoticeShown(ctx, true)
        Enrolment.onReinstated(ctx)
        Enrolment.onRevoked(ctx)
        assertFalse(Config.removedNoticeShown(ctx))
    }

    @Test
    fun `a phone still held by another account is told how to free it`() {
        assertEquals(Enrolment.PairResult.HELD_ELSEWHERE, Enrolment.classifyPair(409, tokenBlank = true))
        assertTrue(Enrolment.explainPair(Enrolment.PairResult.HELD_ELSEWHERE).contains("Phones page"))
    }
}
