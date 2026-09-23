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

    private fun setCallState(state: Int) {
        shadowOf(app.getSystemService(TelephonyManager::class.java)).setCallState(state)
    }

    @Test
    fun anUnrecognisedStateDefersToTelephonyAndKeepsALiveRing() {
        setCallState(TelephonyManager.CALL_STATE_RINGING)
        PhoneStateReceiver().onReceive(app, stateIntent(TelephonyManager.EXTRA_STATE_RINGING, "5551234567"))
        shadowOf(app).clearNextStartedActivities()

        PhoneStateReceiver().onReceive(app, stateIntent("SOMETHING_NEW"))

        assertTrue("telephony still says ringing, so the screen must stay", IncomingCall.ringing)
    }

    @Test
    fun anUnrecognisedStateTakesTheScreenDownWhenNothingIsRinging() {
        // The dangerous direction. A malformed or truncated broadcast used to mean "leave the screen
        // as it is", which let a stale full-screen call screen sit on top of the launcher with no
        // further broadcast coming to clear it.
        setCallState(TelephonyManager.CALL_STATE_RINGING)
        PhoneStateReceiver().onReceive(app, stateIntent(TelephonyManager.EXTRA_STATE_RINGING, "5551234567"))
        assertTrue(IncomingCall.ringing)

        setCallState(TelephonyManager.CALL_STATE_IDLE)
        PhoneStateReceiver().onReceive(app, stateIntent(null))

        assertFalse("telephony says idle, so an unknown state must clear", IncomingCall.ringing)
    }

    @Test
    fun aRepeatedRingingBroadcastRaisesTheScreenAgain() {
        // This asserted the opposite until a review caught it. There was a guard that dropped a
        // RINGING broadcast whose number had not changed, which made the screen unrecoverable: the
        // kiosk leaves HOME enabled, so one press during a ring put the launcher on top, and no later
        // broadcast could bring the answer screen back. The call then rang out unanswerable, which is
        // the exact failure this receiver exists to prevent. Re-raising costs nothing -- the activity
        // is singleInstance, so the repeat lands in onNewIntent and only repaints if the number
        // changed.
        setCallState(TelephonyManager.CALL_STATE_RINGING)
        PhoneStateReceiver().onReceive(app, stateIntent(TelephonyManager.EXTRA_STATE_RINGING, "5551234567"))
        shadowOf(app).clearNextStartedActivities()

        PhoneStateReceiver().onReceive(app, stateIntent(TelephonyManager.EXTRA_STATE_RINGING, "5551234567"))

        val again = shadowOf(app).nextStartedActivity
        assertNotNull("a repeat must be able to rescue a screen dropped behind HOME", again)
        assertEquals(
            IncomingCallActivity::class.java.name,
            again!!.component?.className
        )
    }
}
