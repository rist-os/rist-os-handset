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

    /**
     * The name for [number], from this phone alone: the system address book first (which holds the
     * owner's own contacts and the Rist mirror of the backend's), then Rist's own copy of the
     * backend's contacts. Never asks the network, so it works with no signal.
     */
    fun nameFor(ctx: Context, number: String): String? {
        if (number.isBlank()) return null
        return providerName(ctx, number) ?: runCatching { ContactIndex.nameFor(ctx, number) }
            .onFailure { Log.w(TAG, "contact index lookup failed", it) }.getOrNull()
    }

    private fun providerName(ctx: Context, number: String): String? = runCatching {
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)
        )
        ctx.contentResolver.query(
            uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }.onFailure {
        Log.w(TAG, "caller-ID lookup failed; showing the number instead", it)
    }.getOrNull()

    /**
     * Who a message or call is going to, for a toast: "Name · (206) 555-0100", or just the number
     * when no name is known. [hint] is a name the backend sent with the command.
     */
    fun label(ctx: Context, number: String, hint: String = ""): String {
        val shown = pretty(number).ifBlank { number }
        // A hint with no letters is the number again in some other form, not a name.
        val name = hint.trim().takeIf { h -> h.any { it.isLetter() } }
            ?: nameFor(ctx, number)
        return if (name.isNullOrBlank()) shown else "$name · $shown"
    }

    /** Drops cached names after the address book changed. */
    fun forget() = CommsFeedView.forgetNames()

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
