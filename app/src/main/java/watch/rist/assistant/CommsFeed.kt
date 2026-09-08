package watch.rist.assistant

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class FeedKind {
    MISSED_CALL,

    TEXT,

    NOTIFICATION,
}

data class FeedItem(
    val id: String,
    val kind: FeedKind,
    val number: String,
    val contactName: String?,
    // Always "" for a call.
    val body: String,
    val atMs: Long,
    val unread: Boolean,

    val title: String = "",

    // NOTIFICATION only: the wire's `kind`.
    val noticeKind: String = "",

    // NOTIFICATION only: "passive" | "active" | "time_sensitive" | "critical".
    val urgency: String = "",
)

object CommsFeed {

    // Also the retention bound for inbound text bodies; SmsInbox prunes to this.
    const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    const val MAX_READ = 6

    const val HARD_CAP = 25

    const val PREVIEW_CHARS = 90

    const val MAX_SEEN_IDS = 300

    // Advertised in caps.max_notifications; keep above MAX_READ and below HARD_CAP. Never 0.
    const val MAX_NOTIFICATIONS = 8

    fun assemble(
        items: List<FeedItem>,
        nowMs: Long,
        maxAgeMs: Long = MAX_AGE_MS,
        maxRead: Int = MAX_READ,
        hardCap: Int = HARD_CAP,
    ): List<FeedItem> {
        val unique = LinkedHashMap<String, FeedItem>()
        for (i in items.sortedByDescending { it.atMs }) unique.putIfAbsent(i.id, i)
        val ordered = unique.values.toList()

        val unread = ordered.filter { it.unread }
        val read = ordered.filter { !it.unread }
            .filter { nowMs - it.atMs < maxAgeMs }
            .take(maxRead.coerceAtLeast(0))

        val keptUnread = unread.take(hardCap.coerceAtLeast(0))
        val keptRead = read.take((hardCap - keptUnread.size).coerceAtLeast(0))
        return (keptUnread + keptRead).sortedByDescending { it.atMs }
    }

    fun unreadCount(items: List<FeedItem>): Int = items.count { it.unread }

    fun waitingCount(
        items: List<FeedItem>,
        voicemailWaiting: Boolean,
        mailUnread: Int = 0,
    ): Int = waiting(items, voicemailWaiting, mailUnread).total

    data class Waiting(
        val sms: Int,
        val calls: Int,
        val notices: Int,
        val voicemail: Int,
        val mail: Int,
    ) {
        val total: Int get() = sms + calls + notices + voicemail + mail
    }

    fun waiting(
        items: List<FeedItem>,
        voicemailWaiting: Boolean,
        mailUnread: Int = 0,
    ): Waiting = Waiting(
        sms = items.count { it.kind == FeedKind.TEXT && it.unread },
        calls = items.count { it.kind == FeedKind.MISSED_CALL && it.unread },
        notices = items.count { it.kind == FeedKind.NOTIFICATION && it.unread },
        voicemail = if (voicemailWaiting) 1 else 0,
        mail = unbadgedMail(items, mailUnread),
    )

    internal fun unbadgedMail(items: List<FeedItem>, mailUnread: Int): Int {
        val announced = items.count {
            it.kind == FeedKind.NOTIFICATION && it.noticeKind == KIND_MAIL && it.unread
        }
        return (mailUnread - announced).coerceAtLeast(0)
    }

    // The wire's Notification.kind for inbound email.
    const val KIND_MAIL = "mail"

    fun isUnknownSender(item: FeedItem): Boolean =
        item.kind != FeedKind.NOTIFICATION && item.contactName.isNullOrBlank()

    fun canCallBack(item: FeedItem): Boolean =
        item.kind == FeedKind.MISSED_CALL && item.number.isNotBlank()

    fun callBackLabel(item: FeedItem, pretty: (String) -> String): String =
        "CALL BACK " + senderLine(item, pretty).uppercase()

    fun senderLine(item: FeedItem, pretty: (String) -> String): String {
        // Rendered verbatim; never parsed.
        if (item.kind == FeedKind.NOTIFICATION) {
            return item.title.takeIf { it.isNotBlank() } ?: "Notice from Rist"
        }
        item.contactName?.takeIf { it.isNotBlank() }?.let { return it }
        return item.number.takeIf { it.isNotBlank() }?.let(pretty) ?: "Number withheld"
    }

    fun kindLine(item: FeedItem): String {
        val kind = when (item.kind) {
            FeedKind.MISSED_CALL -> "MISSED CALL"
            FeedKind.TEXT -> "TEXT MESSAGE"
            FeedKind.NOTIFICATION -> noticeKindLine(item)
        }
        return if (isUnknownSender(item)) "$kind · UNKNOWN NUMBER" else kind
    }

    internal fun noticeKindLine(item: FeedItem): String {
        val base = if (item.noticeKind == KIND_MAIL) "NEW MAIL" else "NOTICE"
        return when (item.urgency) {
            "critical" -> "$base · URGENT"
            "time_sensitive" -> "$base · TIME SENSITIVE"
            else -> base
        }
    }

    fun voicemailKindLine(): String = "VOICEMAIL"

    fun voicemailSenderLine(): String = "You have a voicemail"

    fun voicemailActionLine(oneTapReady: Boolean): String =
        if (oneTapReady) "Tap to listen — Rist signs in for you"
        else "Tap to call your mailbox"

    fun voicemailCallLabel(oneTapReady: Boolean): String =
        if (oneTapReady) "LISTEN NOW" else "CALL MY MAILBOX"

    fun preview(body: String, maxChars: Int = PREVIEW_CHARS): String {
        val flat = body.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxChars) return flat
        val cut = flat.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        val body2 = if (lastSpace > maxChars / 2) cut.take(lastSpace) else cut
        return body2.trimEnd() + "…"
    }

    fun relativeTime(
        atMs: Long,
        nowMs: Long,
        tz: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val delta = nowMs - atMs
        if (delta < 0L) return "Just now"
        val minutes = delta / 60_000L
        if (minutes < 1L) return "Just now"
        if (minutes == 1L) return "1 minute ago"
        if (minutes < 60L) return "$minutes minutes ago"
        val hours = minutes / 60L
        if (hours == 1L) return "1 hour ago"
        if (hours < 12L) return "$hours hours ago"

        val timeFmt = SimpleDateFormat("h:mm a", locale).apply { timeZone = tz }
        val at = timeFmt.format(Date(atMs))
        when (calendarDaysBetween(atMs, nowMs, tz)) {
            0 -> return "Today at $at"
            1 -> return "Yesterday at $at"
            in 2..6 -> {
                val day = SimpleDateFormat("EEEE", locale).apply { timeZone = tz }.format(Date(atMs))
                return "$day at $at"
            }
        }
        return SimpleDateFormat("d MMMM", locale).apply { timeZone = tz }.format(Date(atMs)) +
            " at $at"
    }

    private fun calendarDaysBetween(atMs: Long, nowMs: Long, tz: TimeZone): Int {
        fun midnight(ms: Long): Long = Calendar.getInstance(tz).apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val days = (midnight(nowMs) - midnight(atMs)).toDouble() / 86_400_000.0
        return Math.round(days).toInt()
    }

    fun seenIdsAfterAdding(
        existing: List<String>,
        added: Collection<String>,
        cap: Int = MAX_SEEN_IDS,
    ): List<String> {
        if (cap <= 0) return emptyList()
        val out = LinkedHashSet(existing)
        for (id in added) { out.remove(id); out.add(id) }
        val list = out.toList()
        return if (list.size <= cap) list else list.takeLast(cap)
    }
}
