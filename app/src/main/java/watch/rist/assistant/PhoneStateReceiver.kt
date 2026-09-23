package watch.rist.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

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
            // missed and rejected. In every one of those the incoming-call screen must go.
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                IncomingCall.clear()
                CallState.onOffHook()
                // The transition to active is a separate moment from the accept, and the in-call
                // screen only reliably survives the second one -- so raise it here as well as at the
                // press. Without this a call can go live with Rist still in front and no way back to
                // it: the kiosk has no shade, no ongoing-call chip and no recents.
                CallState.returnToCall(context)
                notifyHomeScreen(context)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                IncomingCall.clear()
                CallState.onIdle()
                notifyHomeScreen(context)
            }
            // A state we do not recognise, or a broadcast with no state at all, must not be able to
            // strand the call screen on top of the launcher. Guessing either way is wrong, so ask
            // telephony what is actually happening.
            else -> {
                val live = runCatching {
                    context.getSystemService(TelephonyManager::class.java)?.callState
                }.onFailure { Log.w(TAG, "could not read the call state", it) }.getOrNull()
                if (live == TelephonyManager.CALL_STATE_RINGING) {
                    Log.w(TAG, "unrecognised phone state '$state' but still ringing; keeping the screen")
                } else {
                    Log.w(TAG, "unrecognised phone state '$state' and nothing ringing; taking the screen down")
                    IncomingCall.clear()
                }
            }
        }
    }

    /**
     * Nudges the home screen so its in-call row appears or disappears while it is already on screen.
     * Not the only path: MainActivity also re-renders on resume, which covers the ordinary case of
     * pressing HOME during a call.
     */
    private fun notifyHomeScreen(context: Context) {
        runCatching {
            LocalBroadcastManager.getInstance(context.applicationContext)
                .sendBroadcast(Intent(DeviceCommands.ACTION_STATE_CHANGED))
        }.onFailure { Log.w(TAG, "could not refresh the home screen", it) }
    }

    private companion object {
        const val TAG = "RistIncoming"
    }
}
