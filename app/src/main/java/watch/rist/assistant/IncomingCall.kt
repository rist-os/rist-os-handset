package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.util.Log

object IncomingCall {

    private const val TAG = "RistIncoming"

    @Volatile var ringing: Boolean = false
        private set

    @Volatile private var best: String = ""

    /**
     * Every RINGING broadcast re-raises the screen, including a repeat for a number already shown.
     *
     * There used to be a guard here that returned early when the number had not changed. It looked
     * like a sensible saving and it was the most harmful line in the file: once [ringing] was true
     * for a number, no later broadcast could raise the screen again. One press of HOME during a ring
     * -- and HOME is live, the kiosk enables LOCK_TASK_FEATURE_HOME -- left the call ringing with the
     * launcher on top and no way back to it, which is the same unanswerable call this screen exists
     * to prevent. Re-raising is cheap and safe instead: [IncomingCallActivity] is singleInstance, so
     * the repeat arrives at onNewIntent rather than stacking a second screen, and that activity
     * repaints only when the number it is showing actually changes.
     *
     * A start that the system refuses does not throw -- ActivityTaskManager returns START_ABORTED --
     * so this cannot confirm the screen appeared. Retrying on every broadcast is what covers that.
     */
    fun show(ctx: Context, number: String?) {
        val n = number.orEmpty()
        if (n.isNotBlank()) best = n
        ringing = true
        val app = ctx.applicationContext
        runCatching {
            app.startActivity(
                Intent(app, IncomingCallActivity::class.java)
                    .putExtra(IncomingCallActivity.EXTRA_NUMBER, best)
                    // NEW_TASK only, never CLEAR_TASK.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i(TAG, "asked for the incoming-call screen for ${mask(best)}")
        }.onFailure {
            Log.e(TAG, "could not show the incoming-call screen; the call will ring unanswerable", it)
        }
    }

    /**
     * Adopts a ring that telephony reports but this process has no memory of.
     *
     * [ringing] and [best] are process-global statics with no persistence. If the app is killed
     * mid-ring and the system recreates [IncomingCallActivity], they start empty, the watchdog sees
     * "not ringing" and closes the screen in the middle of a live ring. Telephony is the authority,
     * so the activity asks it and tells us the answer here.
     */
    fun adoptRing(number: String) {
        if (number.isNotBlank()) best = number
        if (!ringing) Log.i(TAG, "adopting a ring telephony reports but this process had lost")
        ringing = true
    }

    fun clear() {
        if (ringing) Log.i(TAG, "incoming call no longer ringing")
        ringing = false
        best = ""
    }

    private fun mask(n: String): String = maskNumber(n)
}

object CallerId {

    private const val TAG = "RistIncoming"

    fun nameFor(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        return runCatching {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
        }.onFailure {
            Log.w(TAG, "caller-ID lookup failed; showing the number instead", it)
        }.getOrNull()
    }

    fun pretty(number: String): String {
        val d = number.filter { it.isDigit() }
        val ten = when {
            d.length == 10 -> d
            d.length == 11 && d.startsWith("1") -> d.drop(1)
            else -> return number
        }
        return "(${ten.take(3)}) ${ten.drop(3).take(3)}-${ten.takeLast(4)}"
    }
}
