package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.json.JSONObject

/** One server-sent notice. Inert by construction: no command field, and [title] is rendered as it stands. */
data class Notice(
    /** Opaque, server-minted. THE dedupe key and the thing `notification_ack` names. */
    val id: String,
    /** Grouped and badged by kind; an unknown kind is shown, never dropped. */
    val kind: String,
    /** Already phrased for a human. Rendered verbatim. Never parsed. */
    val title: String,
    /** "passive" | "active" | "time_sensitive" | "critical". The backend ranks; we decide the effect. */
    val urgency: String,
    /** Server decision time, unix seconds; 0 when unset. */
    val createdAtEpochS: Long,
    /** When this handset first wrote it down. The fallback clock when [createdAtEpochS] is unset. */
    val receivedAtMs: Long,
    /** Named in `notification_ack` on a request the backend answered; not "the user read it". */
    val acked: Boolean,
)

/**
 * PERSIST, THEN ACK: [pendingAcks] only reads ids back off the disk, and a repeat of an id resets
 * [Notice.acked] because the backend re-sends only what it still owes.
 */
object NotificationQueue {

    private const val TAG = "RistNotice"

    // Measured from the server's created_at; expired notices are dropped in [renderable].
    const val MAX_AGE_MS = CommsFeed.MAX_AGE_MS

    // Must stay above [CommsFeed.MAX_NOTIFICATIONS]; eviction is oldest-first.
    const val MAX_HELD = 64

    /** Feed ids are namespaced so a server id can never collide with a derived call/SMS id. */
    private const val FEED_PREFIX = "notice:"

    internal fun feedId(noticeId: String): String = FEED_PREFIX + noticeId

    // The server's stamp; falls back to receive time so a zero created_at does not make the
    // notice instantly expired.
    internal fun atMs(n: Notice): Long =
        if (n.createdAtEpochS > 0L) n.createdAtEpochS * 1000L else n.receivedAtMs

    internal fun isExpired(n: Notice, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        nowMs - atMs(n) >= maxAgeMs

    // A repeat replaces (one row per id), resets acked to false and keeps the original receivedAtMs.
    // A blank id is dropped: it could never be acked.
    internal fun upsert(existing: List<Notice>, incoming: List<Notice>): List<Notice> {
        val out = LinkedHashMap<String, Notice>(existing.size + incoming.size)
        for (n in existing) out[n.id] = n
        for (n in incoming) {
            if (n.id.isBlank()) {
                Log.w(TAG, "dropping a notification with no id; it could never be acked")
                continue
            }
            val prior = out[n.id]
            out[n.id] = n.copy(
                receivedAtMs = prior?.receivedAtMs ?: n.receivedAtMs,
                acked = false,
            )
        }
        return out.values.toList()
    }

    // Ordered by [atMs], not by insertion.
    internal fun trim(held: List<Notice>, cap: Int = MAX_HELD): List<Notice> =
        if (held.size <= cap) held else held.sortedByDescending { atMs(it) }.take(cap.coerceAtLeast(0))

    // Includes expired ones: the ack says we hold the record, not that we showed it.
    internal fun pendingAcks(held: List<Notice>): List<String> =
        held.filterNot { it.acked }.map { it.id }

    internal fun markAcked(held: List<Notice>, ids: Collection<String>): List<Notice> {
        if (ids.isEmpty()) return held
        val set = ids.toHashSet()
        return held.map { if (it.id in set) it.copy(acked = true) else it }
    }

    // Selection order only; the feed draws newest-first. [seen] is keyed by [feedId].
    internal fun renderable(
        held: List<Notice>,
        nowMs: Long,
        seen: Set<String> = emptySet(),
        maxAgeMs: Long = MAX_AGE_MS,
        limit: Int = CommsFeed.MAX_NOTIFICATIONS,
    ): List<Notice> = held
        .filter { it.title.isNotBlank() && !isExpired(it, nowMs, maxAgeMs) }
        .sortedWith(
            compareByDescending<Notice> { feedId(it.id) !in seen }.thenByDescending { atMs(it) }
        )
        .take(limit.coerceAtLeast(0))

    // number, contactName and body stay empty so [CommsFeed.canCallBack] is false without a special case.
    internal fun toFeedItems(notices: List<Notice>, seen: Set<String>): List<FeedItem> =
        notices.map {
            val fid = feedId(it.id)
            FeedItem(
                id = fid,
                kind = FeedKind.NOTIFICATION,
                number = "",
                contactName = null,
                body = "",
                atMs = atMs(it),
                unread = fid !in seen,
                title = it.title.trim(),
                noticeKind = it.kind.trim().lowercase(),
                urgency = it.urgency.trim().lowercase(),
            )
        }

    internal fun fromWire(wire: List<rist.v1.Notification>, nowMs: Long): List<Notice> =
        wire.map {
            Notice(
                id = it.id.trim(),
                kind = it.kind,
                title = it.title,
                urgency = it.urgency,
                createdAtEpochS = it.createdAtEpochS,
                receivedAtMs = nowMs,
                acked = false,
            )
        }

    // An unreadable row is skipped; an unreadable file yields an empty ledger.
    internal fun decode(json: String): List<Notice> {
        val out = ArrayList<Notice>()
        runCatching {
            val arr = JSONArray(json.ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                runCatching { one(arr.getJSONObject(i)) }.getOrNull()?.let(out::add)
            }
        }.onFailure { Log.w(TAG, "notification ledger unreadable; starting empty", it) }
        return out
    }

    private fun one(o: JSONObject): Notice? {
        val id = o.optString("id").trim()
        if (id.isBlank()) return null
        return Notice(
            id = id,
            kind = o.optString("kind"),
            title = o.optString("title"),
            urgency = o.optString("urgency"),
            createdAtEpochS = o.optLong("at"),
            receivedAtMs = o.optLong("rx"),
            acked = o.optBoolean("acked"),
        )
    }

    internal fun encode(list: List<Notice>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("kind", it.kind); put("title", it.title)
                put("urgency", it.urgency); put("at", it.createdAtEpochS)
                put("rx", it.receivedAtMs); put("acked", it.acked)
            })
        }
        return arr.toString()
    }

    private fun load(ctx: Context): List<Notice> = decode(Config.notifications(ctx))

    private fun save(ctx: Context, list: List<Notice>) {
        Config.setNotifications(ctx, encode(trim(list)))
    }

    // The persist half of "persist, then ack"; nothing is acked here.
    fun store(ctx: Context, wire: List<rist.v1.Notification>) {
        if (wire.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val merged = upsert(load(ctx), fromWire(wire, nowMs))
        save(ctx, merged)
        // Ids and counts only. The TITLE carries a correspondent's name and never goes to logcat.
        Log.i(TAG, "stored ${wire.size} notification(s); ${merged.size} held, " +
            "${pendingAcks(merged).size} awaiting ack")
        notifyUi(ctx)
    }

    /** Ids to put on the next request. Reads from disk, which is the durability guarantee. */
    fun pendingAcks(ctx: Context): List<String> = pendingAcks(load(ctx))

    fun markAcked(ctx: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        save(ctx, markAcked(load(ctx), ids))
    }

    // Called on every response, including zeros; the repaint is gated on the value changing.
    fun setMailUnread(ctx: Context, count: Int) {
        val fresh = count.coerceAtLeast(0)
        if (Config.mailUnread(ctx) == fresh) return
        Config.setMailUnread(ctx, fresh)
        Log.i(TAG, "mail_unread = $fresh")
        notifyUi(ctx)
    }

    fun feedItems(ctx: Context, seen: Set<String>): List<FeedItem> =
        toFeedItems(renderable(load(ctx), System.currentTimeMillis(), seen), seen)

    private fun notifyUi(ctx: Context) = runCatching {
        LocalBroadcastManager.getInstance(ctx.applicationContext)
            .sendBroadcast(Intent(NotificationHub.ACTION_COUNTS_CHANGED))
    }.let { }
}
