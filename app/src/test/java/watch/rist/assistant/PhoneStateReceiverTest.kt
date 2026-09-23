package watch.rist.assistant

import android.app.Application
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The regression this guards: 2026090701 shipped a PhoneStateReceiver that put Rist's own
 * incoming-call screen up. It was compiled from BridgeAnswer.kt, which was withheld when the
 * repository was reduced, so the receiver went with it. From then on IncomingCall.show() had zero
 * callers, IncomingCallActivity was unreachable dead code, and an incoming call rang, vibrated and
 * showed nothing.
 *
 * Nothing in the suite could catch it: ManifestComponentsExistTest only fails a manifest entry with
 * no class, and here the class and its declaration went together.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneStateReceiverTest {

    private lateinit var app: Application

    private fun stateIntent(state: String?, number: String? = null) =
        Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            state?.let { putExtra(TelephonyManager.EXTRA_STATE, it) }
            number?.let { putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, it) }
        }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        IncomingCall.clear()
        shadowOf(app).clearNextStartedActivities()
    }

    @After
    fun tearDown() {
        IncomingCall.clear()
    }

    @Test
    fun ringingPutsOurOwnCallScreenUp() {
        PhoneStateReceiver().onReceive(app, stateIntent(TelephonyManager.EXTRA_STATE_RINGING, "5551234567"))

        assertTrue("the app must know a call is ringing", IncomingCall.ringing)
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("an incoming call must start an activity, not merely ring", started)
        assertEquals(
            "it must be OUR screen: the stock dialer's is suppressed by lock task",
            IncomingCallActivity::class.java.name,
            started!!.component?.className
        )
        assertEquals("5551234567", started.getStringExtra(IncomingCallActivity.EXTRA_NUMBER))
    }

    @Test
    fun aWithheldNumberStillShowsTheScreen() {
        // EXTRA_INCOMING_NUMBER is absent without READ_CALL_LOG. An unanswerable call is worse than
        // an unnamed one, so the screen must still appear.
        PhoneStateReceiver().onReceive(app, stateIntent(TelephonyManager.EXTRA_STATE_RINGING))

        assertTrue(IncomingCall.ringing)
        assertNotNull("a missing caller ID must not cost us the screen", shadowOf(app).nextStartedActivity)
    }

    @Test
    fun answeringOrHangingUpTakesTheScreenDown() {
        for (end in listOf(TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE)) {
            PhoneStateReceiver().onReceive(app, stateIntent(TelephonyManager.EXTRA_STATE_RINGING, "5551234567"))
            assertTrue(IncomingCall.ringing)

            PhoneStateReceiver().onReceive(app, stateIntent(end))
            assertFalse("state $end must clear the ringing flag", IncomingCall.ringing)
        }
    }

    @Test
    fun anUnrelatedBroadcastIsIgnored() {
        PhoneStateReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertFalse(IncomingCall.ringing)
        assertNull("only PHONE_STATE may raise the call screen", shadowOf(app).nextStartedActivity)
    }

    @Test
    fun anUnrecognisedStateChangesNothing() {
        PhoneStateReceiver().onReceive(app, stateIntent(TelephonyManager.EXTRA_STATE_RINGING, "5551234567"))
        shadowOf(app).clearNextStartedActivities()

        PhoneStateReceiver().onReceive(app, stateIntent("SOMETHING_NEW"))

        assertTrue("an unknown state must not tear down a live ring", IncomingCall.ringing)
    }

    @Test
    fun aSecondRingingBroadcastDoesNotStackScreens() {
        // The platform repeats PHONE_STATE. singleInstance covers the task, but re-showing for the
        // same number should not even be attempted.
        PhoneStateReceiver().onReceive(app, stateIntent(TelephonyManager.EXTRA_STATE_RINGING, "5551234567"))
        shadowOf(app).clearNextStartedActivities()

        PhoneStateReceiver().onReceive(app, stateIntent(TelephonyManager.EXTRA_STATE_RINGING, "5551234567"))

        assertNull("the same ringing number must not start the screen twice",
            shadowOf(app).nextStartedActivity)
    }
}
