package watch.rist.assistant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast

class SmsResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT) return
        val who = intent.getStringExtra(EXTRA_WHO).orEmpty().ifBlank { "them" }
        val cid = intent.getStringExtra(EXTRA_CID).orEmpty()
        val part = intent.getIntExtra(EXTRA_PART, 0)
        val parts = intent.getIntExtra(EXTRA_PARTS, 1).coerceAtLeast(1)
        when (resultCode) {
            Activity.RESULT_OK -> {
                // A long text is sent when its last part is; one part out is not the message out.
                if (part < parts - 1) {
                    Log.i(TAG, "sms: part ${part + 1} of $parts sent")
                    return
                }
                Log.i(TAG, "sms: sent")
                // A part that failed earlier keeps the text failed: half a message is not sent.
                if (failedEarlier(context, cid)) return
                toast(context, "Sent to $who")
                CommsResults.record(context, cid, "send_sms", true)
            }
            else -> {
                val why = when (resultCode) {
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "the radio is off"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "the message was malformed"
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "the network rejected it"
                    else -> "error $resultCode"
                }
                Log.w(TAG, "sms FAILED: $why")
                // Every part after a failed one fails too; the person is told once.
                if (failedEarlier(context, cid)) return
                rememberFailed(cid)
                CommsResults.record(context, cid, "send_sms", false, why)
                toast(context, "Could not text $who — $why")
            }
        }
    }

    private fun toast(ctx: Context, msg: String) = runCatching {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }.let { }

    companion object {
        private const val TAG = "RistCmd"
        const val ACTION_SENT = "watch.rist.assistant.SMS_SENT"
        const val EXTRA_WHO = "who"
        const val EXTRA_CID = "cid"
        const val EXTRA_PART = "part"
        const val EXTRA_PARTS = "parts"

        /** The sent-result intent for part [part] of [parts] of the text [cid]. */
        fun sentIntent(ctx: Context, cid: String, who: String, part: Int, parts: Int): android.app.PendingIntent =
            android.app.PendingIntent.getBroadcast(
                ctx.applicationContext, "sms:$cid:$part:${System.nanoTime()}".hashCode(),
                Intent(ACTION_SENT)
                    .setPackage(ctx.packageName)
                    .putExtra(EXTRA_WHO, who)
                    .putExtra(EXTRA_CID, cid)
                    .putExtra(EXTRA_PART, part)
                    .putExtra(EXTRA_PARTS, parts),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )

        private var registered: SmsResultReceiver? = null

        /**
         * Texts with a failed part, kept here as well as in [CommsResults]: a turn between two
         * parts can carry the failure to the backend, whose acknowledgement clears it there, and
         * the last part's success must still not turn it into "sent".
         */
        private val failedCids = LinkedHashSet<String>()
        private const val FAILED_MEMORY = 32

        @Synchronized
        private fun rememberFailed(cid: String) {
            if (cid.isBlank()) return
            failedCids.add(cid)
            while (failedCids.size > FAILED_MEMORY) failedCids.remove(failedCids.first())
        }

        @Synchronized
        private fun failedEarlier(ctx: Context, cid: String): Boolean =
            cid.isNotBlank() && (cid in failedCids || CommsResults.failed(ctx, cid))

        fun register(ctx: Context) = runCatching {
            if (registered != null) return@runCatching
            val r = SmsResultReceiver()
            androidx.core.content.ContextCompat.registerReceiver(
                ctx.applicationContext, r, android.content.IntentFilter(ACTION_SENT),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = r
        }.onFailure { Log.w(TAG, "sms result receiver registration failed", it) }.let { }
    }
}
