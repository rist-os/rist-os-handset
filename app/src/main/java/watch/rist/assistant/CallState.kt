package watch.rist.assistant

import android.content.Context
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Whether a call is live, and the way back to it.
 *
 * The kiosk deliberately withholds `LOCK_TASK_FEATURE_NOTIFICATIONS`, so there is no shade, no
 * heads-up notification and no ongoing-call chip, and recents is off too. `LOCK_TASK_FEATURE_HOME`
 * is on, though, and Rist is HOME -- so one press leaves a live call and nothing brings it back. The
 * End button lives on the dialer's in-call screen, which is now unreachable. One outgoing call ran
 * 3m14s and stopped only when the far end hung up; there is no disconnect request for it in the log,
 * because there was no way to ask for one.
 *
 * The fix is not to open the shade for the sake of one chip. Rist already has everywhere it needs to
 * put a button; it only needed to know a call was live.
 */
object CallState {

    private const val TAG = "RistCall"

    /**
     * Set from [PhoneStateReceiver]. A process-global static, so it is empty again after a restart --
     * which is why [inCall] asks telephony rather than trusting this alone.
     */
    @Volatile
    private var offHook: Boolean = false

    fun onOffHook() {
        if (!offHook) Log.i(TAG, "a call went live")
        offHook = true
    }

    fun onIdle() {
        if (offHook) Log.i(TAG, "the call ended")
        offHook = false
    }

    /**
     * True when a call is actually up.
     *
     * Telephony is asked first and believed: `AudioManager.mode` is the wrong signal for this and is
     * what the volume panel used to use. `mode` is audio-policy state owned by whoever last called
     * `setMode`, it reads `MODE_IN_COMMUNICATION` for a VoIP call with no telephony call at all
     * (including Rist's own video calls), it lags the modem on an outgoing call, and a VoIP app killed
     * without restoring `MODE_NORMAL` leaves it lying indefinitely -- a phone that says it is in a call
     * when it is not.
     */
    fun inCall(ctx: Context): Boolean {
        val state = runCatching {
            ctx.getSystemService(TelephonyManager::class.java)?.callState
        }.onFailure { Log.w(TAG, "could not read the call state", it) }.getOrNull()
        return when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> true
            TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_IDLE -> false
            // No reading available: fall back to what the broadcast told us.
            else -> offHook
        }
    }

    /**
     * Brings the dialer's own in-call screen forward. `com.android.dialer` is lock-task allowlisted,
     * so this lands inside the kiosk, and `showInCallScreen` needs no permission.
     */
    fun returnToCall(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(TelecomManager::class.java)?.showInCallScreen(false)
        true
    }.onFailure { Log.w(TAG, "could not bring the in-call screen forward", it) }.getOrDefault(false)

    /** Hangs up. Needs ANSWER_PHONE_CALLS, which the Device Owner grants itself. */
    fun endCall(ctx: Context): Boolean {
        val ended = runCatching {
            ctx.getSystemService(TelecomManager::class.java)?.endCall() ?: false
        }.onFailure { Log.w(TAG, "endCall failed", it) }.getOrDefault(false)
        if (!ended) Log.w(TAG, "endCall refused; the call may still be up")
        return ended
    }
}
