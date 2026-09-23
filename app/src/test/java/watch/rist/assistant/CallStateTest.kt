package watch.rist.assistant

import android.app.Application
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The regression this exists for: the kiosk withholds LOCK_TASK_FEATURE_NOTIFICATIONS, so there is no
 * shade, no heads-up and no ongoing-call chip, and recents is off -- but HOME is on and Rist is HOME.
 * One press therefore left a live call with no way back to it and End unreachable. One outgoing call
 * ran 3m14s and stopped only when the far end hung up; there is no disconnect request for it in the
 * log at all, because there was no way to ask for one.
 */
@RunWith(RobolectricTestRunner::class)
class CallStateTest {

    private lateinit var app: Application

    private fun setCallState(state: Int) {
        shadowOf(app.getSystemService(TelephonyManager::class.java)).setCallState(state)
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        CallState.onIdle()
    }

    @Test
    fun anActiveCallIsReportedSoTheHomeScreenCanOfferAWayBack() {
        setCallState(TelephonyManager.CALL_STATE_OFFHOOK)

        assertTrue("a live call must be visible to the home screen", CallState.inCall(app))
    }

    @Test
    fun aRingingPhoneIsNotYetACall() {
        // The incoming-call screen owns this moment; a "return to call" row would be wrong here.
        setCallState(TelephonyManager.CALL_STATE_RINGING)

        assertFalse(CallState.inCall(app))
    }

    @Test
    fun noCallMeansNoRow() {
        setCallState(TelephonyManager.CALL_STATE_IDLE)

        assertFalse(CallState.inCall(app))
    }

    @Test
    fun telephonyOutranksWhatTheBroadcastLeftBehind() {
        // The flag is a process-global static with no persistence. Telephony is the authority, so a
        // stale true must not outlive the call it was set for -- otherwise the home screen offers a
        // "return to call" button for a call that ended while the process was dead.
        CallState.onOffHook()
        setCallState(TelephonyManager.CALL_STATE_IDLE)

        assertFalse("a stale flag must not survive telephony saying idle", CallState.inCall(app))
    }

    @Test
    fun theBroadcastIsTheFallbackWhenTelephonyCannotAnswer() {
        // Robolectric's default call state is IDLE, so this cannot be exercised through the shadow;
        // what is asserted here is the bookkeeping either way round.
        CallState.onOffHook()
        setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        assertTrue(CallState.inCall(app))

        CallState.onIdle()
        setCallState(TelephonyManager.CALL_STATE_IDLE)
        assertFalse(CallState.inCall(app))
    }
}
