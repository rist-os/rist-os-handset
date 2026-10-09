package watch.rist.assistant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * The radio's verdict on each part of a text the assistant sent.
 *
 * Declared in the manifest, never registered at runtime: the verdict can land after the process
 * that sent the text has died, and a receiver that only lived in that process dropped it for good.
 */
class SmsResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT) return
        val ctx = context.applicationContext
        val who = intent.getStringExtra(EXTRA_WHO).orEmpty().ifBlank { "them" }
        val cid = intent.getStringExtra(EXTRA_CID).orEmpty()
        val part = intent.getIntExtra(EXTRA_PART, 0)
        val parts = intent.getIntExtra(EXTRA_PARTS, 1).coerceAtLeast(1)
        val ok = resultCode == Activity.RESULT_OK
        val why = if (ok) "" else reason(resultCode)
        if (!ok) Log.w(TAG, "sms part ${part + 1} of $parts FAILED: $why (radio error ${intent.getIntExtra("errorCode", -1)})")
        when (Parts.settle(ctx, cid, part, parts, ok)) {
            Verdict.PENDING -> Log.i(TAG, "sms: part ${part + 1} of $parts sent")
            Verdict.ALREADY_TOLD -> Unit
            Verdict.SENT -> {
                Log.i(TAG, "sms: sent")
                toast(ctx, "Sent to $who")
                CommsResults.record(ctx, cid, "send_sms", true)
                deliver(ctx)
            }
            Verdict.FAILED -> {
                CommsResults.record(ctx, cid, "send_sms", false, why)
                toast(ctx, notSentLine(who, resultCode))
                deliver(ctx)
            }
        }
    }

    /** Hands the result to the backend now, not with whatever the person says next. */
    private fun deliver(ctx: Context) {
        val pending = runCatching { goAsync() }.getOrNull()
        runCatching { checkIn(ctx) { pending?.finish() } }
            .onFailure { Log.w(TAG, "could not start the result check-in", it); pending?.finish() }
    }

    private fun toast(ctx: Context, msg: String) = runCatching {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }.let { }

    enum class Verdict { PENDING, SENT, FAILED, ALREADY_TOLD }

    /**
     * What each text's parts have said so far, on disk: a part that failed keeps the whole text
     * failed through the backend's acknowledgement and a restart, so a later part that went can
     * never turn half a message into "sent".
     */
    object Parts {
        private const val PREFS = "sms_parts"
        private const val KEY = "texts"
        private const val KEEP = 64

        @Synchronized
        fun settle(ctx: Context, cid: String, part: Int, parts: Int, ok: Boolean): Verdict {
            // Without an id there is nothing to report against: the last part decides, as before.
            if (cid.isBlank()) return if (!ok) Verdict.FAILED else if (part >= parts - 1) Verdict.SENT else Verdict.PENDING
            val all = load(ctx)
            val t = all.optJSONObject(cid) ?: JSONObject().put("total", parts).put("ok", JSONArray())
            all.remove(cid)
            val verdict = when {
                t.optBoolean("told") -> Verdict.ALREADY_TOLD
                !ok -> { t.put("told", true); t.put("failed", true); Verdict.FAILED }
                else -> {
                    val done = t.getJSONArray("ok")
                    if ((0 until done.length()).none { done.getInt(it) == part }) done.put(part)
                    if (done.length() >= t.optInt("total", parts)) { t.put("told", true); Verdict.SENT }
                    else Verdict.PENDING
                }
            }
            all.put(cid, t)
            save(ctx, all)
            return verdict
        }

        private fun prefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun load(ctx: Context): JSONObject =
            runCatching { JSONObject(prefs(ctx).getString(KEY, "") ?: "") }.getOrElse { JSONObject() }

        private fun save(ctx: Context, all: JSONObject) {
            // Oldest first, as inserted: keep the newest.
            val names = all.keys().asSequence().toList()
            names.take((names.size - KEEP).coerceAtLeast(0)).forEach { all.remove(it) }
            // commit, not apply: the process may be gone the moment this receiver returns.
            prefs(ctx).edit().putString(KEY, all.toString()).commit()
        }

        @Synchronized
        fun clearForTest(ctx: Context) { prefs(ctx).edit().clear().commit() }
    }

    companion object {
        private const val TAG = "RistCmd"
        const val ACTION_SENT = "watch.rist.assistant.SMS_SENT"
        const val EXTRA_WHO = "who"
        const val EXTRA_CID = "cid"
        const val EXTRA_PART = "part"
        const val EXTRA_PARTS = "parts"

        /** What the person sees for an unsent text: the fix when it is theirs, else unavailable. */
        internal fun notSentLine(who: String, resultCode: Int): String =
            if (resultCode == SmsManager.RESULT_ERROR_RADIO_OFF)
                "Not sent to $who. Turn off airplane mode and try again."
            else "Not sent to $who. ${Unavailable.TEXTING}"

        /**
         * Sends what [CommsResults] holds to the backend, then calls `done`. Replaced in tests.
         * A failed check-in loses nothing: the results stay queued for the next request.
         */
        @Volatile
        internal var checkIn: (Context, () -> Unit) -> Unit = { ctx, done ->
            Thread {
                try {
                    Uploader(ctx).reportCommsResults()
                } catch (t: Throwable) {
                    Log.w(TAG, "result check-in failed; the results stay queued", t)
                } finally {
                    done()
                }
            }.apply { name = "sms-result-check-in" }.start()
        }

        // "the radio is off" is matched by the backend, which tells the person to leave airplane mode.
        internal fun reason(code: Int): String = when (code) {
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "the radio is off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "the message was malformed"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "the network rejected it"
            else -> "error $code"
        }

        /**
         * The sent-result intent for part [part] of [parts] of the text [cid]. Explicit, so it
         * reaches the manifest receiver whether or not the app is running. Mutable so the radio
         * can add its `errorCode`; the component is fixed, so a fill-in cannot redirect it.
         */
        fun sentIntent(ctx: Context, cid: String, who: String, part: Int, parts: Int): android.app.PendingIntent =
            android.app.PendingIntent.getBroadcast(
                ctx.applicationContext, "sms:$cid:$part:${System.nanoTime()}".hashCode(),
                Intent(ctx.applicationContext, SmsResultReceiver::class.java)
                    .setAction(ACTION_SENT)
                    .putExtra(EXTRA_WHO, who)
                    .putExtra(EXTRA_CID, cid)
                    .putExtra(EXTRA_PART, part)
                    .putExtra(EXTRA_PARTS, parts),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
    }
}
