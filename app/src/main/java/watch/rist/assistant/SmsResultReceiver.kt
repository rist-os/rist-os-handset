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
        when (resultCode) {
            Activity.RESULT_OK -> {
                Log.i(TAG, "sms to $who: sent")
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
                Log.w(TAG, "sms to $who FAILED: $why")
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

        private var registered: SmsResultReceiver? = null

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
