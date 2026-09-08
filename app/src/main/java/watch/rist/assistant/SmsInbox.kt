package watch.rist.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import java.security.MessageDigest

data class InboundSms(
    val id: String,
    val from: String,
    val body: String,
    // Carrier send time, not the time this handset stored it.
    val sentAtMs: Long,
)

sealed interface SmsRead {
    data class Held(val messages: List<InboundSms>) : SmsRead

    data class Unreadable(val why: String) : SmsRead
}

object SmsInbox {

    private const val TAG = "RistSms"

    // Compile-time constant: inlined, so this file gains no bytecode dependency on CommsFeed.
    private const val MAX_AGE_MS = CommsFeed.MAX_AGE_MS

    private const val MAX_MESSAGES = 100

    fun stableId(from: String, sentAtMs: Long, body: String): String =
        sha256("$from|$sentAtMs|$body")

    fun arrivalId(from: String, atMs: Long): String = sha256("$from|$atMs")

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32)

    fun canRead(ctx: Context): Boolean =
        runCatching {
            ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    internal data class StoreRow(val from: String, val body: String, val sentAtMs: Long)

    internal fun fromStore(rows: List<StoreRow>, nowMs: Long): List<InboundSms> =
        rows.asSequence()
            .filter { it.from.isNotBlank() }
            .filter { nowMs - it.sentAtMs < MAX_AGE_MS }
            .sortedByDescending { it.sentAtMs }
            .take(MAX_MESSAGES)
            .map { InboundSms(stableId(it.from, it.sentAtMs, it.body), it.from, it.body, it.sentAtMs) }
            .toList()

    fun read(ctx: Context): SmsRead {
        purgeLegacyStore(ctx)
        if (!canRead(ctx)) {
            Log.e(TAG, "READ_SMS is not granted; the ask cannot be answered")
            return SmsRead.Unreadable(
                "this phone has not given Rist permission to read your messages"
            )
        }
        val now = System.currentTimeMillis()
        val cutoff = now - MAX_AGE_MS
        val rows = ArrayList<StoreRow>()
        val answered = runCatching {
            val cursor = ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.DATE_SENT,
                ),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(cutoff.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT $MAX_MESSAGES"
            ) ?: return@runCatching false
            cursor.use {
                while (it.moveToNext()) {
                    val sent = it.getLong(3)
                    rows.add(
                        StoreRow(
                            from = it.getString(0).orEmpty(),
                            body = it.getString(1).orEmpty(),
                            // DATE_SENT is the carrier's clock; some senders leave it 0.
                            sentAtMs = if (sent > 0L) sent else it.getLong(2),
                        )
                    )
                }
            }
            true
        }
        answered.onFailure {
            Log.e(TAG, "message store refused the read", it)
            return SmsRead.Unreadable("this phone refused to open its message store")
        }
        if (answered.getOrDefault(false) != true) {
            Log.e(TAG, "message store returned no cursor")
            return SmsRead.Unreadable("this phone's message store did not answer")
        }
        val held = fromStore(rows, now)
        Log.i(TAG, "ask-time read: ${held.size} message(s) inside the window")
        return SmsRead.Held(held)
    }

    data class SmsArrival(val id: String, val from: String, val atMs: Long)

    fun arrivals(ctx: Context): List<SmsArrival>? {
        purgeLegacyStore(ctx)
        if (!canRead(ctx)) return null
        val now = System.currentTimeMillis()
        val cutoff = now - MAX_AGE_MS
        val out = ArrayList<SmsArrival>()
        return runCatching {
            val cursor = ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                // No BODY column: this projection is the privacy boundary.
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(cutoff.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT ${CommsFeed.HARD_CAP}"
            ) ?: return@runCatching null
            cursor.use {
                while (it.moveToNext()) {
                    val from = it.getString(0).orEmpty()
                    if (from.isBlank()) continue
                    val sent = it.getLong(2)
                    val at = if (sent > 0L) sent else it.getLong(1)
                    out.add(SmsArrival(arrivalId(from, at), from, at))
                }
            }
            out.toList()
        }.onFailure { Log.w(TAG, "could not read arrival metadata", it) }.getOrNull()
    }

    fun purgeLegacyStore(ctx: Context) = runCatching {
        val app = ctx.applicationContext
        if (Config.smsQueue(app).isBlank()) return@runCatching
        Config.setSmsQueue(app, "")
        Log.i(TAG, "purged the legacy on-device SMS queue; Rist now holds no message bodies")
    }.onFailure { Log.w(TAG, "legacy SMS queue not purged", it) }.let { }

}
