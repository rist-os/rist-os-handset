package watch.rist.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Puts Rist's own incoming-call screen up when the phone rings.
 *
 * This is the caller [IncomingCall.show] lost. A receiver of this name shipped in 2026090701
 * (versionCode 471, class present in classes3.dex, compiled from BridgeAnswer.kt) and did not
 * survive into the public tree when the repository was reduced on 2026-09-08, because the file it
 * lived in also held the call-bridging policy that was deliberately withheld. The declaration went
 * with it, so from 2026092200 nothing told the app a call was ringing:
 * [IncomingCallActivity] was still in the image, and [IncomingCall.show] still had no callers.
 *
 * The visible result was that an incoming call rang and vibrated and showed nothing. Without a
 * screen of our own the job falls to the stock dialer, which surfaces a call as a notification
 * carrying a full-screen intent, and lock task suppresses exactly that:
 *
 *   VisualInterruptionDecisionProvider: FSI suppressed: no HUN or keyguard
 *
 * "no HUN or keyguard" is why a handset with a PIN could still take calls and the same handset
 * without one could not. That asymmetry disappears here: [IncomingCallActivity] is ours, in a
 * lock-task-allowlisted package, and declares showWhenLocked/showOnLockScreen/turnScreenOn, so it
 * comes up locked or unlocked without needing notifications enabled inside the kiosk.
 *
 * Deliberately minimal: this decides nothing about the call. It maps RINGING to "show the screen"
 * and every other state to "take it down". Answering is [IncomingCallActivity]'s business, and no
 * call is ever accepted without a press.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // EXTRA_INCOMING_NUMBER needs READ_CALL_LOG, which the Device Owner grants to
                // itself in KioskManager. Absent it the extra is null and the screen shows
                // "Unknown" rather than not appearing -- an unanswerable call is the worse failure.
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                Log.i(TAG, "phone is ringing; showing the incoming-call screen")
                IncomingCall.show(context, number)
            }
            // OFFHOOK covers both "we answered" and "an outgoing call started"; IDLE covers hung up,
            // missed and rejected. In every one of those the screen must go.
            TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE ->
                IncomingCall.clear()
            else -> Log.w(TAG, "unrecognised phone state '$state'; leaving the screen as it is")
        }
    }

    private companion object {
        const val TAG = "RistIncoming"
    }
}
