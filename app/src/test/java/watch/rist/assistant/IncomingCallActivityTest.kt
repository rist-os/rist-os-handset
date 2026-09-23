package watch.rist.assistant

import android.app.Application
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The answer screen had no tests at all, which is a strange gap for the only code in the product that
 * decides whether a phone call is picked up. These cover the two presses and the two ways the screen
 * is supposed to close itself.
 */
@RunWith(RobolectricTestRunner::class)
class IncomingCallActivityTest {

    private lateinit var app: Application

    private fun intentFor(number: String) =
        Intent(app, IncomingCallActivity::class.java)
            .putExtra(IncomingCallActivity.EXTRA_NUMBER, number)

    private fun setCallState(state: Int) {
        shadowOf(app.getSystemService(TelephonyManager::class.java)).setCallState(state)
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        IncomingCall.clear()
    }

    @After
    fun tearDown() {
        IncomingCall.clear()
    }

    @Test
    fun aRingingCallGetsAScreenAndAdoptsTheRing() {
        // Telephony is the authority, so a screen raised while it reports RINGING must stay up even
        // though this process has no memory of the ring -- that is the post-restart case.
        setCallState(TelephonyManager.CALL_STATE_RINGING)

        val controller = Robolectric.buildActivity(
            IncomingCallActivity::class.java, intentFor("5551234567")
        ).create()

        assertFalse("the screen must not close itself during a live ring", controller.get().isFinishing)
        assertTrue("a live ring must be adopted even with no memory of it", IncomingCall.ringing)
    }

    @Test
    fun aStaleScreenClosesItselfImmediately() {
        // Before this, a relaunch with no call behind it sat on top of the launcher until the next
        // watchdog tick, up to 400ms of full-screen "INCOMING CALL" over the home screen.
        setCallState(TelephonyManager.CALL_STATE_IDLE)

        val controller = Robolectric.buildActivity(
            IncomingCallActivity::class.java, intentFor("5551234567")
        ).create()

        assertTrue("no call means the screen must close in onCreate", controller.get().isFinishing)
        assertFalse(IncomingCall.ringing)
    }

    @Test
    fun anActiveCallDoesNotGetAnIncomingScreen() {
        setCallState(TelephonyManager.CALL_STATE_OFFHOOK)

        val controller = Robolectric.buildActivity(
            IncomingCallActivity::class.java, intentFor("5551234567")
        ).create()

        assertTrue("there is nothing to answer during a live call", controller.get().isFinishing)
    }

    @Test
    fun decliningClearsTheRingSoTheNextCallCanRing() {
        // The bug this pins: neither press used to clear the flag, so if the IDLE broadcast went
        // missing the caller's number stayed latched and their call back showed no screen at all.
        setCallState(TelephonyManager.CALL_STATE_RINGING)
        val controller = Robolectric.buildActivity(
            IncomingCallActivity::class.java, intentFor("5551234567")
        ).create()
        assertTrue(IncomingCall.ringing)

        tap(controller.get(), "DECLINE")

        assertFalse("declining must clear the ring, not wait for a broadcast", IncomingCall.ringing)
    }

    @Test
    fun answeringClearsTheRing() {
        setCallState(TelephonyManager.CALL_STATE_RINGING)
        val controller = Robolectric.buildActivity(
            IncomingCallActivity::class.java, intentFor("5551234567")
        ).create()

        tap(controller.get(), "ANSWER")

        assertFalse("answering must clear the ring", IncomingCall.ringing)
    }

    /** Finds a button by its label and clicks it, so the test exercises the real listener. */
    private fun tap(activity: IncomingCallActivity, label: String) {
        val root = activity.window.decorView
        val button = findByText(root, label)
            ?: throw AssertionError("no '$label' button on the call screen")
        button.performClick()
    }

    private fun findByText(v: android.view.View, label: String): android.view.View? {
        if (v is android.widget.TextView && v.text?.toString() == label && v.isClickable) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) findByText(v.getChildAt(i), label)?.let { return it }
        }
        return null
    }
}
