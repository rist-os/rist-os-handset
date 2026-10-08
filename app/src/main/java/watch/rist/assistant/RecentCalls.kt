package watch.rist.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log

/** One call that reached the SIM, for the backend's `inbound_sms_request` answer. Metadata only. */
data class RecentCall(val id: String, val number: String, val atMs: Long, val kind: String, val durationS: Int)

/**
 * The last day of incoming, missed and declined calls, read from the call log only when the backend
 * asks during a turn the owner started (texts on request, schema v26). Nothing is kept or queued.
 */
object RecentCalls {

    private const val TAG = "RistCalls"

    /** Same window as the texts ([SmsInbox]): a day. */
    internal const val WINDOW_MS = 24L * 60L * 60L * 1000L
    internal const val MAX_CALLS = 30

    fun canRead(ctx: Context): Boolean = runCatching {
        ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    internal fun kindOf(type: Int): String? = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        else -> null      // outgoing, voicemail, blocked: not a call that reached the owner
    }

    internal data class Row(val number: String, val atMs: Long, val type: Int, val durationS: Int)

    internal fun fromLog(rows: List<Row>, nowMs: Long): List<RecentCall> =
        rows.asSequence()
            .filter { it.number.isNotBlank() && nowMs - it.atMs < WINDOW_MS }
            .mapNotNull { r -> kindOf(r.type)?.let { RecentCall(SmsInbox.arrivalId(r.number, r.atMs), r.number, r.atMs, it, r.durationS) } }
            .sortedByDescending { it.atMs }
            .take(MAX_CALLS)
            .toList()

    /** The calls of the window, newest first; empty when the log cannot be read. Never throws. */
    fun read(ctx: Context, nowMs: Long = System.currentTimeMillis()): List<RecentCall> {
        if (!canRead(ctx)) return emptyList()
        val rows = ArrayList<Row>()
        runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} > ?", arrayOf((nowMs - WINDOW_MS).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && rows.size < MAX_CALLS * 3) {
                    rows += Row(c.getString(0).orEmpty(), c.getLong(1), c.getInt(2), c.getInt(3))
                }
            }
        }.onFailure { Log.w(TAG, "could not read the call log: ${it.javaClass.simpleName}") }
        return fromLog(rows, nowMs)
    }
}
