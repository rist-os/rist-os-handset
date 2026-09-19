package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

object CarrierVoicemail {

    private const val TAG = "RistVmCarrier"

    fun call(ctx: Context): Boolean {
        val number = mailboxNumber(ctx)
        val pin = Config.voicemailPin(ctx)
        val uri = if (number.isNotBlank() && pin.isNotBlank()) {
            // Each comma is a ~2s post-dial pause; '#' is percent-encoded so it does not start a URI fragment.
            Uri.parse("tel:" + Uri.encode("$number,,,$pin#", ",+*"))
        } else {
            Uri.parse("voicemail:")
        }
        Log.i(TAG, "dialling the mailbox (number=${number.isNotBlank()}, pin=${pin.length} digits)")
        return runCatching {
            ctx.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.onFailure { Log.w(TAG, "could not dial the mailbox", it) }.getOrDefault(false)
    }

    fun mailboxNumber(ctx: Context): String = runCatching {
        ctx.getSystemService(TelephonyManager::class.java)?.voiceMailNumber.orEmpty()
    }.onFailure { Log.w(TAG, "voicemail number unavailable", it) }.getOrDefault("")

    fun oneTapReady(ctx: Context): Boolean =
        mailboxNumber(ctx).isNotBlank() && Config.voicemailPin(ctx).isNotBlank()

    fun waiting(ctx: Context): Boolean =
        Config.carrierVoicemailWaiting(ctx) || NotificationHub.voicemailPosted()

    /**
     * The carrier's flag cannot be cleared from here, only by emptying the mailbox, so a
     * dismissal is ours: it hides the row and the badge until the carrier counts a message
     * more than it did then, and lapses by itself once nothing is waiting.
     */
    fun dismiss(ctx: Context) {
        Config.setVoicemailDismissedAt(ctx, readCount(ctx.applicationContext) ?: COUNT_UNKNOWN)
    }

    /** True while a voicemail is waiting that the person has not dismissed from the feed. */
    fun showing(ctx: Context): Boolean {
        if (!waiting(ctx)) {
            if (Config.voicemailDismissedAt(ctx) != null) Config.setVoicemailDismissedAt(ctx, null)
            return false
        }
        val at = Config.voicemailDismissedAt(ctx) ?: return true
        val age = System.currentTimeMillis() - Config.voicemailDismissedMs(ctx)
        if (staysDismissed(at, readCount(ctx.applicationContext), age)) return false
        Config.setVoicemailDismissedAt(ctx, null)
        return true
    }

    internal const val COUNT_UNKNOWN = -1

    /**
     * With a count known both then and now, only a higher count is a new message. Without one
     * a new message cannot be told from the old, so the dismissal lapses after a day: at worst
     * the row comes back once a day, rather than a new voicemail staying hidden for good.
     */
    internal const val UNKNOWN_DISMISS_MS = 24L * 60 * 60 * 1000

    internal fun staysDismissed(dismissedAt: Int, current: Int?, ageMs: Long = 0L): Boolean =
        if (dismissedAt >= 0 && current != null && current >= 0) current <= dismissedAt
        else ageMs in 0 until UNKNOWN_DISMISS_MS

    fun unacknowledged(ctx: Context): Boolean {
        if (!showing(ctx)) return false
        val present = SystemVoicemail.carrierRowPresent(ctx)
        if (present == true) return true
        android.util.Log.w(
            TAG,
            "carrier says a message is waiting but no row is in the voicemail tab " +
                "(provider unreadable=${present == null}); suppressing the badge rather than " +
                "showing one with nothing behind it"
        )
        return false
    }

    fun listen(ctx: Context) {
        if (registered) return
        val app = ctx.applicationContext
        val tm = runCatching { app.getSystemService(TelephonyManager::class.java) }.getOrNull()
            ?: return
        runCatching {
            val cb = Callback(app)
            tm.registerTelephonyCallback(app.mainExecutor, cb)
            // The framework holds only a WeakReference to the callback; this field is the strong one.
            callback = cb
            registered = true
            Log.i(TAG, "listening for the message-waiting indicator")
        }.onFailure { Log.w(TAG, "could not register for the waiting indicator", it) }
    }

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            val count = readCount(app)
            val waiting = when {
                count == null -> NotificationHub.voicemailPosted()
                count > 0 -> true
                count == 0 -> false
                // -1 = unread messages, count unknown.
                else -> NotificationHub.voicemailPosted()
            }
            Log.i(TAG, "raw voice message count=$count -> waiting=$waiting " +
                "(persisted=${Config.carrierVoicemailWaiting(app)}, " +
                "notif=${NotificationHub.voicemailPosted()})")
            SystemVoicemail.setCarrierWaiting(app, waiting)
            // Nothing waiting ends a dismissal here too, not only at the next render: a flag that
            // clears and sets again while the feed is not drawn is a new voicemail.
            if (!waiting && Config.voicemailDismissedAt(app) != null) Config.setVoicemailDismissedAt(app, null)
            if (Config.carrierVoicemailWaiting(app) == waiting) return
            Config.setCarrierVoicemailWaiting(app, waiting)
            Log.i(TAG, "voice message count=$count -> waiting=$waiting")
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(app)
                .sendBroadcast(android.content.Intent(NotificationHub.ACTION_COUNTS_CHANGED))
        }.onFailure { Log.w(TAG, "could not read the voice message count", it) }
    }

    private fun readCount(app: Context): Int? = runCatching {
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return null
        TelephonyManager::class.java.getMethod("getVoiceMessageCount").invoke(tm) as? Int
    }.onFailure { Log.w(TAG, "could not read the voice message count", it) }.getOrNull()

    @Volatile private var registered = false

    @Volatile private var callback: Callback? = null

    private class Callback(private val app: Context) :
        TelephonyCallback(), TelephonyCallback.MessageWaitingIndicatorListener {
        override fun onMessageWaitingIndicatorChanged(waiting: Boolean) {
            if (Config.carrierVoicemailWaiting(app) == waiting) return
            Config.setCarrierVoicemailWaiting(app, waiting)
            SystemVoicemail.setCarrierWaiting(app, waiting)
            Log.i(TAG, "carrier message-waiting indicator -> $waiting")
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(app)
                .sendBroadcast(android.content.Intent(NotificationHub.ACTION_COUNTS_CHANGED))
        }
    }
}
