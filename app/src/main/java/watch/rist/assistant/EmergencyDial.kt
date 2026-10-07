package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The emergency dialer, opened from the home screen's EMERGENCY button.
 *
 * It must work when nothing else does: no network, no backend, no pairing, a lapsed or removed
 * account, no SIM. So it asks nothing of any of them. It opens the system's own emergency dialer
 * (the one the lock screen uses), which is part of the telephony package already allowed under
 * lock task; if that cannot be started it falls back to the ordinary dial pad, which can also
 * place an emergency call.
 */
object EmergencyDial {

    private const val TAG = "RistEmergency"

    /** The system emergency dialer's action; its activity lives in the telephony package. */
    internal const val ACTION_EMERGENCY_DIAL = "com.android.phone.EmergencyDialer.DIAL"
    internal const val PKG_TELEPHONY = "com.android.phone"

    internal fun emergencyIntent(): Intent =
        Intent(ACTION_EMERGENCY_DIAL)
            .setPackage(PKG_TELEPHONY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    internal fun dialPadIntent(): Intent =
        Intent(Intent.ACTION_DIAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Returns false only when neither dialer could be started. */
    fun open(ctx: Context): Boolean {
        for (intent in listOf(emergencyIntent(), dialPadIntent())) {
            val started = runCatching { ctx.startActivity(intent); true }
                .onFailure { Log.w(TAG, "could not open ${intent.action}", it) }
                .getOrDefault(false)
            if (started) {
                Log.i(TAG, "opened ${intent.action}")
                return true
            }
        }
        return false
    }
}
