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

    fun show(ctx: Context, number: String?) {
        val n = number.orEmpty()
        if (ringing && n.isBlank()) return
        if (ringing && n == best) return
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
            Log.i(TAG, "showing incoming call from ${mask(best)}")
        }.onFailure {
            Log.e(TAG, "could not show the incoming-call screen; the call will ring unanswerable", it)
        }
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
