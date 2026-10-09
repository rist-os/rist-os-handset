package watch.rist.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.telecom.TelecomManager
import android.text.Spanned
import android.text.TextPaint
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object CommsFeedView {

    private const val TAG = "RistFeed"


    private val expanded = HashSet<String>()

    private const val VOICEMAIL_KEY = "voicemail:carrier"

    private val nameCache = HashMap<String, String?>()
    private var nameCacheAtMs = 0L
    private const val NAME_CACHE_TTL_MS = 10L * 60L * 1000L

    fun candidates(ctx: Context): List<FeedItem> {
        val seen = Config.seenCommsIds(ctx).toHashSet()
        val notices = NotificationQueue.feedItems(ctx, seen)
        return (missedCalls(ctx) + inboundTexts(ctx)).map { it.copy(unread = it.id !in seen) } +
            notices
    }

    fun waitingCount(ctx: Context): Int = waiting(ctx).total

    /** How many rows the feed lists now, read or not: the Notifications tile's number. */
    fun listedCount(ctx: Context): Int {
        val all = candidates(ctx)
        val vm = CarrierVoicemail.showing(ctx)
        return listed(all, vm, CommsFeed.unbadgedMail(all, pendingMail(ctx)))
    }

    private fun listed(all: List<FeedItem>, vmWaiting: Boolean, unbadgedMail: Int): Int =
        CommsFeed.assemble(
            all.filter { it.kind == FeedKind.NOTIFICATION || it.unread || it.id in expanded },
            System.currentTimeMillis(),
        ).size + (if (vmWaiting) 1 else 0) + (if (unbadgedMail > 0) 1 else 0)

    fun waiting(ctx: Context): CommsFeed.Waiting =
        CommsFeed.waiting(candidates(ctx), CarrierVoicemail.showing(ctx), pendingMail(ctx))

    /** Unread mail, counted only while this account has email. */
    internal fun pendingMail(ctx: Context): Int =
        if (Features.isOn(ctx, Features.Id.EMAIL)) Config.pendingMail(ctx) else 0

    private fun missedCalls(ctx: Context): List<FeedItem> = runCatching {
        // No date cutoff, for the same reason as SmsInbox.arrivals: the feed keeps a missed
        // call until it is cleared, and a lookback here was a seven-day expiry in disguise.
        // LIMIT_PARAM_KEY bounds the query instead.
        val out = ArrayList<FeedItem>()
        // Cap via LIMIT_PARAM_KEY in the URI: a LIMIT in sortOrder throws IllegalArgumentException.
        val capped = android.provider.CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(
                android.provider.CallLog.Calls.LIMIT_PARAM_KEY,
                CommsFeed.HARD_CAP.toString()
            )
            .build()
        ctx.contentResolver.query(
            capped,
            arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE),
            "${android.provider.CallLog.Calls.TYPE} = ?",
            arrayOf(android.provider.CallLog.Calls.MISSED_TYPE.toString()),
            "${android.provider.CallLog.Calls.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(0).orEmpty()
                val at = c.getLong(1)
                out.add(
                    FeedItem(
                        id = "call:$number:$at",
                        kind = FeedKind.MISSED_CALL,
                        number = number,
                        contactName = nameFor(ctx, number),
                        body = "",
                        atMs = at,
                        unread = true,
                    )
                )
            }
        }
        out
    }.onFailure {
        Log.w(TAG, "could not read the call log; missed calls will not appear", it)
    }.getOrDefault(emptyList())

    private fun inboundTexts(ctx: Context): List<FeedItem> = runCatching {
        SmsInbox.arrivals(ctx).orEmpty().map { sms ->
            FeedItem(
                id = "sms:${sms.id}",
                kind = FeedKind.TEXT,
                number = sms.from,
                contactName = nameFor(ctx, sms.from),
                body = "",
                atMs = sms.atMs,
                unread = true,
            )
        }
    }.onFailure { Log.w(TAG, "could not read arrivals", it) }.getOrDefault(emptyList())

    /** A contact sync changed the names; look them up again. */
    @Synchronized
    fun forgetNames() { nameCache.clear(); nameCacheAtMs = 0L }

    @Synchronized
    private fun nameFor(ctx: Context, number: String): String? {
        val now = System.currentTimeMillis()
        if (now - nameCacheAtMs > NAME_CACHE_TTL_MS) { nameCache.clear(); nameCacheAtMs = now }
        if (number.isBlank()) return null
        if (nameCache.containsKey(number)) return nameCache[number]
        return CallerId.nameFor(ctx, number).also { nameCache[number] = it }
    }

    private fun markSeen(ctx: Context, ids: Collection<String>) = runCatching {
        if (ids.isEmpty()) return@runCatching
        Config.setSeenCommsIds(ctx, CommsFeed.seenIdsAfterAdding(Config.seenCommsIds(ctx), ids))
        NotificationQueue.markRead(ctx, ids.mapNotNull { NotificationQueue.noticeIdOf(it) })
    }.let { }

    /** Told how many are waiting each time the feed is drawn, so a count elsewhere never lags it. */
    fun interface Watcher {
        fun onFeedWaiting(waiting: Int, listed: Int)
    }

    // Return type must be declared: render() is recursive via the mail row's dismiss.
    fun render(activity: Activity) {
        var counted: Pair<Int, Int>? = null
        draw(activity) { w, l -> counted = w to l }
        counted?.let { (w, l) -> runCatching { (activity as? Watcher)?.onFeedWaiting(w, l) } }
    }

    /**
     * True when the home screen leaves notifications to the Notifications tile: the tile row is
     * on for this account, so calls, texts, voicemail, mail and notices are listed only on the
     * page it opens. Without the row they stay on the home screen, so they are never out of sight.
     * Account and permission problems (billing, not connected, texts unreadable) stay either way.
     */
    internal fun leftToTile(activity: Activity): Boolean =
        activity is MainActivity && HomeBoxes.shown(activity)

    private fun draw(activity: Activity, count: (Int, Int) -> Unit): Unit = runCatching {
        val host = activity.findViewById<LinearLayout>(R.id.commsFeed) ?: return@runCatching
        host.removeAllViews()

        val all = candidates(activity)
        val toTile = leftToTile(activity)
        val assembled = CommsFeed.assemble(
            // `it.id in expanded` is load-bearing: a tap marks seen AND expands, so an open row stays until closed.
            // A notice stays read or unread: NotificationQueue.renderable already chose which to keep.
            all.filter { it.kind == FeedKind.NOTIFICATION || it.unread || it.id in expanded },
            System.currentTimeMillis(),
        )
        val vmWaiting = CarrierVoicemail.showing(activity)
        // The counts below are taken from everything, drawn or not: the tile shows them.
        val shown = if (toTile) emptyList() else assembled
        val vmRow = vmWaiting && !toTile
        val textsUnreadable = !SmsInbox.canRead(activity)
        val unconnected = Config.credentialRejected(activity) || Config.enrolRevoked(activity)
        val lapsed = if (unconnected) null else Billing.notice(activity)

        val mailUnread = pendingMail(activity)
        val unbadgedMail = CommsFeed.unbadgedMail(all, mailUnread)
        val waiting = CommsFeed.waitingCount(all, vmWaiting, mailUnread)
        count(waiting, listed(all, vmWaiting, unbadgedMail))
        val mailRow = unbadgedMail > 0 && !toTile

        if (shown.isEmpty() && !vmRow && !textsUnreadable && !unconnected && lapsed == null && !mailRow) {
            host.visibility = View.GONE; return@runCatching
        }
        host.visibility = View.VISIBLE

        val t = Themes.current(activity)
        val tf = ThemePaint.typefaceOf(activity, t)
        val muted = Themes.readableMuted(t)
        val d = activity.resources.displayMetrics.density

        var drawn = 0
        // Carries its own rule underneath, so what follows is laid out as if it were not there.
        if (lapsed != null) host.addView(billingRow(activity, t, tf, d, lapsed))
        // On the Notifications page with nothing new, the page's own title already says it.
        val headerDrawn = !toTile && !unconnected && !(activity is NotificationsActivity && waiting == 0)
        if (headerDrawn) host.addView(header(activity, t, tf, muted, d, waiting, all, vmWaiting, unbadgedMail))

        fun divider() = host.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (1 * d).toInt())
            )
            setBackgroundColor(t.fieldBorder)
        })

        if (unconnected) {
            host.addView(TextView(activity).apply {
                text = activity.getString(
                    when {
                        Config.enrolRevoked(activity) -> R.string.status_revoked
                        Config.isSetupComplete(activity) -> R.string.status_connection_lost
                        else -> R.string.status_never_connected
                    }
                )
                setTextColor(t.accent); typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                minHeight = (48 * d).toInt()
                isClickable = true; isFocusable = true
                setOnClickListener {
                    activity.startActivity(
                        Intent(activity, SettingsActivity::class.java)
                            .putExtra(SettingsActivity.EXTRA_HIDE_APPS, true)
                            .putExtra(SettingsActivity.EXTRA_SHOW_BACKEND, true)
                    )
                }
            })
            drawn++
            if (!toTile && (shown.isNotEmpty() || vmRow || textsUnreadable)) {
                divider()
                host.addView(header(activity, t, tf, muted, d, waiting, all, vmWaiting, unbadgedMail))
                drawn++
            }
        }
        if (textsUnreadable) {
            if (drawn > 0) divider()
            host.addView(TextView(activity).apply {
                text = "Texts are not shown: this phone has not given Rist permission to read " +
                    "them. Open the Messages app to read your texts."
                setTextColor(t.accent); typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                minHeight = (48 * d).toInt()
                isClickable = true; isFocusable = true
                setOnClickListener { AppLauncher.launchMessaging(activity) }
            })
            drawn++
        }
        if (vmRow) {
            if (drawn > 0) divider()
            host.addView(voicemailRow(activity, t, tf, muted, d)); drawn++
        }
        if (mailRow) {
            if (drawn > 0) divider()
            val mailText = TextView(activity)
            mailText.text = if (unbadgedMail == 1) activity.getString(R.string.mail_unread_one)
                            else activity.getString(R.string.mail_unread_many, unbadgedMail)
            mailText.setTextColor(t.accent)
            mailText.typeface = tf
            mailText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            mailText.setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            mailText.minHeight = (48 * d).toInt()
            mailText.gravity = Gravity.CENTER_VERTICAL
            mailText.layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val mailRow = LinearLayout(activity)
            mailRow.tag = MAIL_ROW_TAG
            mailRow.orientation = LinearLayout.HORIZONTAL
            mailRow.gravity = Gravity.CENTER_VERTICAL
            mailRow.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            mailRow.addView(mailText)
            // Dismissing marks the backend's unread count as seen; a higher count (a new email)
            // brings the row back, and a count that falls to 0 re-arms it (Config.setMailUnread).
            fun dismissMail() = Config.setMailAcknowledged(activity, Config.mailUnread(activity))
            mailRow.addView(ImageView(activity).apply {
                tag = MAIL_CLOSE_TAG
                setImageResource(R.drawable.ic_close)
                setColorFilter(muted)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (14 * d).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt())
                contentDescription = "Dismiss the unread email notice"
                isClickable = true; isFocusable = true
                setOnClickListener {
                    dismissMail()
                    Haptics.ack(activity)
                    render(activity)
                }
            })
            swipeToDismiss(activity, mailRow, "the unread email notice") { dismissMail() }
            // Clickable, so the row takes the touch down and a sideways swipe on it reaches the
            // swipe: a row that ignores the down is never sent the moves that follow it.
            mailRow.isClickable = true; mailRow.isFocusable = true
            mailRow.contentDescription = mailText.text.toString() + " Double tap to have it read."
            mailRow.setOnClickListener { readNewMail(activity) }
            host.addView(mailRow)
            drawn++
        }

        for (item in shown) {
            if (drawn > 0) divider()
            host.addView(
                if (item.kind == FeedKind.NOTIFICATION) noticeCard(activity, t, tf, muted, d, item)
                else row(activity, t, tf, muted, d, item)
            )
            drawn++
        }
        // No "N older, not shown" line: what is left out is calls and texts already seen
        // (still in the Phone and Messages apps) and read notices past MAX_READ. Every unread
        // item is drawn; CommsFeed.assemble never trims one.
    }.onFailure { Log.w(TAG, "feed render failed", it) }.let { }

    private fun header(
        activity: Activity, t: RistTheme, tf: android.graphics.Typeface?, muted: Int,
        d: Float, waiting: Int, all: List<FeedItem>, vmWaiting: Boolean, unbadgedMail: Int,
    ): View {
        // The unread-mail row counts too: with nothing else new, CLEAR ALL is its way off.
        val acknowledgeable = CommsFeed.unreadCount(all) + (if (vmWaiting) 1 else 0) +
            (if (unbadgedMail > 0) 1 else 0)
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (4 * d).toInt())
        }
        bar.addView(TextView(activity).apply {
            text = if (waiting > 0) "$waiting NEW" else activity.getString(R.string.notifications_title)
            setTextColor(if (waiting > 0) t.accent else muted)
            typeface = tf
            isAllCaps = true
            letterSpacing = 0.08f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (waiting > 0) 17f else 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (acknowledgeable > 0) bar.addView(TextView(activity).apply {
            text = "CLEAR ALL"
            // It takes every notice off as well as the new calls, texts and voicemail; say so.
            contentDescription = clearAllSpoken(acknowledgeable)
            setTextColor(t.ink); typeface = tf
            isAllCaps = true
            letterSpacing = 0.06f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            minHeight = (48 * d).toInt()
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke((1.5f * d).toInt(), t.accent)
                cornerRadius = 12f * d
            }
            isClickable = true; isFocusable = true
            setOnClickListener {
                expanded.clear()
                markSeen(activity, all.filter { it.unread }.map { it.id })
                NotificationQueue.dismiss(activity, all.mapNotNull { NotificationQueue.noticeIdOf(it.id) })
                Config.setMailAcknowledged(activity, Config.mailUnread(activity))
                if (vmWaiting) CarrierVoicemail.dismiss(activity)
                Haptics.ack(activity)
                render(activity)
            }
        })
        return bar
    }

    private fun row(
        activity: Activity, t: RistTheme, tf: android.graphics.Typeface?, muted: Int,
        d: Float, item: FeedItem,
    ): View {
        val isOpen = item.id in expanded
        val ink = if (item.unread) t.ink else muted

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        col.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(activity).apply {
                text = CommsFeed.kindLine(item)
                setTextColor(if (item.unread) t.accent else muted)
                typeface = tf
                isAllCaps = true
                letterSpacing = 0.06f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (item.unread) addView(TextView(activity).apply {
                text = "NEW"
                setTextColor(t.accent); typeface = tf
                isAllCaps = true
                letterSpacing = 0.10f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        })

        col.addView(TextView(activity).apply {
            text = CommsFeed.senderLine(item) { CallerId.pretty(it) }
            setTextColor(ink); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, (2 * d).toInt(), 0, 0)
        })

        if (item.kind == FeedKind.TEXT && item.body.isNotBlank()) col.addView(TextView(activity).apply {
            text = if (isOpen) item.body.trim() else CommsFeed.preview(item.body)
            setTextColor(ink); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, (3 * d).toInt(), 0, 0)
        })

        col.addView(TextView(activity).apply {
            text = CommsFeed.relativeTime(item.atMs, System.currentTimeMillis())
            setTextColor(muted); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, (3 * d).toInt(), 0, 0)
        })

        if (isOpen && CommsFeed.canCallBack(item)) col.addView(
            actionButton(
                activity, t, tf, d,
                label = CommsFeed.callBackLabel(item) { CallerId.pretty(it) },
                spoken = "Call back " + CommsFeed.senderLine(item) { CallerId.pretty(it) } +
                    " on " + CallerId.pretty(item.number),
                glyph = R.drawable.ic_app_phone,
            ) { placeCall(activity, item.number) }
        )

        // A meeting link in a text: Join puts up the same join screen a spoken request does,
        // so nothing connects until it is tapped there too.
        val meeting = if (isOpen && item.kind == FeedKind.TEXT)
            VideoCalls.meetingLinkIn(item.body, VideoCalls.ristHost(Config.backendUrl(activity))) else null
        if (meeting != null) col.addView(
            actionButton(
                activity, t, tf, d,
                label = activity.getString(R.string.call_join_from_text),
                spoken = "Join the video call in this text",
                glyph = R.drawable.ic_app_cam,
            ) { VideoCalls.join(activity, meeting, "", "", "") }
        )

        if (isOpen && item.kind == FeedKind.TEXT) col.addView(
            actionButton(
                activity, t, tf, d,
                label = "OPEN IN MESSAGES",
                spoken = "Open the Messages app to read this text",
                glyph = R.drawable.ic_app_msg,
            ) { AppLauncher.launchMessaging(activity) }
        )

        val glyph = ImageView(activity).apply {
            setImageResource(
                if (item.kind == FeedKind.MISSED_CALL) R.drawable.ic_app_phone
                else R.drawable.ic_app_msg
            )
            setColorFilter(ink)
            layoutParams = LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()).apply {
                rightMargin = (12 * d).toInt(); topMargin = (4 * d).toInt()
            }
        }

        val edge = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams((5 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                .apply { rightMargin = (10 * d).toInt() }
            if (item.unread) setBackgroundColor(t.accent)
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = (72 * d).toInt()
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            addView(edge); addView(glyph); addView(col)
            swipeToDismiss(
                activity, this,
                "this " + when (item.kind) {
                    FeedKind.TEXT -> "message"
                    FeedKind.MISSED_CALL -> "missed call"
                    FeedKind.NOTIFICATION -> "notice"
                },
            ) {
                expanded.remove(item.id)
                markSeen(activity, listOf(item.id))
            }
            isClickable = true; isFocusable = true
            contentDescription = buildString {
                append(CommsFeed.kindLine(item)).append(". ")
                append(CommsFeed.senderLine(item) { CallerId.pretty(it) }).append(". ")
                if (item.body.isNotBlank()) append(item.body).append(". ")
                append(CommsFeed.relativeTime(item.atMs, System.currentTimeMillis()))
                if (item.unread) append(". New.")
                if (!isOpen && CommsFeed.canCallBack(item)) append(". Opens a button to call back.")
                if (!isOpen && item.kind == FeedKind.TEXT) append(". Opens a button to read it in Messages.")
            }
            setOnClickListener {
                if (item.id in expanded) expanded.remove(item.id) else expanded.add(item.id)
                markSeen(activity, listOf(item.id))
                Haptics.ack(activity)
                render(activity)
            }
        }
    }

    internal const val NOTICE_CARD_TAG = "notice-card"
    internal const val NOTICE_BAR_TAG = "notice-bar"
    internal const val NOTICE_LABEL_TAG = "notice-label"
    internal const val NOTICE_BODY_TAG = "notice-body"
    internal const val NOTICE_TOGGLE_TAG = "notice-toggle"
    internal const val NOTICE_CLOSE_TAG = "notice-close"
    internal const val MAIL_ROW_TAG = "mail-row"
    internal const val MAIL_CLOSE_TAG = "mail-close"

    /** What a tap on the unread-mail row asks, typed, as if the owner had said it. */
    internal const val READ_MAIL_WORDS = "Read my new email"

    /**
     * Asks the assistant to read the new email, as a typed turn sent from the home screen, so the
     * answer lands where every answer does. From any other screen, home is brought up to send it.
     */
    private fun readNewMail(activity: Activity) {
        // A second tap before this page is gone would bring home up with the turn again.
        if (activity.isFinishing) return
        Haptics.ack(activity)
        val turn = HomeBoxes.Turn(text = READ_MAIL_WORDS, targetToolId = "", boxId = "", prompt = READ_MAIL_WORDS)
        if (activity is MainActivity) { activity.sendBoxTurn(turn); return }
        runCatching { activity.startActivity(MainActivity.turnIntent(activity, turn)) }
            .onFailure { Log.w(TAG, "could not bring home up to read the email", it); return }
        activity.finish()
    }

    /** Lines a long notice shows before "Show more". */
    internal const val NOTICE_PREVIEW_LINES = 4

    internal const val NOTICE_SHOW_MORE = "SHOW MORE"
    internal const val NOTICE_SHOW_LESS = "SHOW LESS"

    /**
     * The leading bar: the accent when it holds 3:1 against the ground (a design that fails the
     * rail is nudged there by DesignSync), else the ink. The label says "Notification" as well,
     * so the colour is never the only sign.
     */
    internal fun noticeBarColor(t: RistTheme): Int =
        if (contrast(t.accent, t.ground) >= 3.0) t.accent else t.ink

    /** Small text in the accent only where the accent reads as text (4.5:1); else the ink. */
    internal fun noticeAccentText(t: RistTheme): Int =
        if (contrast(t.accent, t.ground) >= 4.5) t.accent else t.ink

    /** What CLEAR ALL says to a screen reader: everything goes, not only the new items. */
    internal fun clearAllSpoken(newCount: Int): String =
        if (newCount > 0) "Clear all notifications, $newCount new" else "Clear all notifications"

    /** For tests: forget which rows are open. */
    internal fun resetForTest() = expanded.clear()

    /**
     * A server notice drawn as an answer is drawn (same text, size and markdown), with an accent
     * bar down its leading edge, a "NOTIFICATION · time" label and a close button. A long one shows
     * [NOTICE_PREVIEW_LINES] lines and SHOW MORE; a tap on the card or the link opens it in place.
     * Kept until dismissed (swipe, close, CLEAR ALL); see [NotificationQueue.renderable].
     */
    private fun noticeCard(
        activity: Activity, t: RistTheme, tf: android.graphics.Typeface?, muted: Int,
        d: Float, item: FeedItem,
    ): View {
        val isOpen = item.id in expanded
        val nowMs = System.currentTimeMillis()
        val text = item.title.trim().ifBlank { "Notice from Rist" }
        val noticeId = NotificationQueue.noticeIdOf(item.id)
        val accentText = noticeAccentText(t)
        val dismiss = {
            expanded.remove(item.id)
            markSeen(activity, listOf(item.id))
            if (noticeId != null) NotificationQueue.dismiss(activity, listOf(noticeId))
        }

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        col.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(TextView(activity).apply {
                tag = NOTICE_LABEL_TAG
                this.text = CommsFeed.noticeCardLabel(item, nowMs)
                setTextColor(if (item.unread) accentText else muted); typeface = tf
                isAllCaps = true
                letterSpacing = 0.06f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (item.unread) addView(TextView(activity).apply {
                this.text = "NEW"
                setTextColor(accentText); typeface = tf
                isAllCaps = true
                letterSpacing = 0.10f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPaddingRelative(0, 0, (4 * d).toInt(), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(ImageView(activity).apply {
                tag = NOTICE_CLOSE_TAG
                setImageResource(R.drawable.ic_close)
                setColorFilter(muted)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (14 * d).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt())
                contentDescription = "Dismiss notification"
                isClickable = true; isFocusable = true
                setOnClickListener {
                    dismiss()
                    Haptics.ack(activity)
                    render(activity)
                }
            })
        })

        val rendered = noticeText(text)
        val body = TextView(activity).apply {
            tag = NOTICE_BODY_TAG
            this.text = rendered
            setTextColor(t.ink); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, 0, 0, (2 * d).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (isOpen) {
                maxLines = Int.MAX_VALUE; ellipsize = null
            } else {
                maxLines = NOTICE_PREVIEW_LINES; ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        col.addView(body)

        val toggle = TextView(activity).apply {
            tag = NOTICE_TOGGLE_TAG
            this.text = if (isOpen) NOTICE_SHOW_LESS else NOTICE_SHOW_MORE
            setTextColor(accentText); typeface = tf
            isAllCaps = true
            letterSpacing = 0.06f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (48 * d).toInt()
            setPaddingRelative(0, 0, (16 * d).toInt(), 0)
            // An open card always offers to close; a closed one only once its text is seen to overflow.
            visibility = if (isOpen) View.VISIBLE else View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        col.addView(toggle)

        val bar = View(activity).apply {
            tag = NOTICE_BAR_TAG
            layoutParams = LinearLayout.LayoutParams((5 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                .apply { marginEnd = (12 * d).toInt() }
            setBackgroundColor(noticeBarColor(t))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val spokenText = rendered.toString()
        return LinearLayout(activity).apply {
            tag = NOTICE_CARD_TAG
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            addView(bar); addView(col)
            swipeToDismiss(activity, this, "this notification") { dismiss() }
            isClickable = true; isFocusable = true
            contentDescription = CommsFeed.noticeSpoken(item, nowMs, spokenText)
            val card = this
            fun labelClick(overflows: Boolean) {
                val label = when {
                    isOpen -> "Show less"
                    overflows -> "Show more"
                    else -> "Mark as read"
                }
                androidx.core.view.ViewCompat.replaceAccessibilityAction(
                    card,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    label, null,
                )
            }
            labelClick(false)
            // Whether the preview cut anything is only known once the text is laid out at its width.
            body.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (isOpen) return@addOnLayoutChangeListener
                val overflows = noticeOverflows(v as TextView)
                val want = if (overflows) View.VISIBLE else View.GONE
                if (toggle.visibility != want) v.post { toggle.visibility = want; labelClick(overflows) }
            }
            val flip = {
                if (isOpen || toggle.visibility == View.VISIBLE) {
                    if (isOpen) expanded.remove(item.id) else expanded.add(item.id)
                }
                markSeen(activity, listOf(item.id))
                Haptics.ack(activity)
                render(activity)
            }
            setOnClickListener { flip() }
            toggle.setOnClickListener { flip() }
            toggle.isClickable = true
        }
    }

    /**
     * A notice's text, styled. A first line of the form `**…**` is the person's own words (what
     * they asked for): it is drawn bold exactly as written, with no markdown read inside it. The
     * rest is markdown, as an answer is. Every bold run is a [StrongSpan], so it shows on a design
     * whose body text is already bold.
     */
    internal fun noticeText(src: String): CharSequence {
        val nl = src.indexOf('\n')
        val first = (if (nl < 0) src else src.substring(0, nl)).trim()
        if (first.length > 4 && first.startsWith("**") && first.endsWith("**")) {
            val words = first.substring(2, first.length - 2)
            if (words.isNotBlank()) {
                val out = android.text.SpannableStringBuilder(words)
                out.setSpan(StrongSpan(Typeface.BOLD), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val rest = if (nl < 0) "" else src.substring(nl + 1)
                if (rest.isNotBlank()) out.append("\n").append(strong(Markdown.render(rest)))
                return out
            }
        }
        return strong(Markdown.render(src))
    }

    /** [text] with each bold [StyleSpan] swapped for a [StrongSpan] over the same run. */
    private fun strong(text: CharSequence): CharSequence {
        if (text !is Spanned) return text
        val out = android.text.SpannableStringBuilder(text)
        for (sp in out.getSpans(0, out.length, StyleSpan::class.java)) {
            if (sp is StrongSpan || sp.style and Typeface.BOLD == 0) continue
            val a = out.getSpanStart(sp); val b = out.getSpanEnd(sp); val f = out.getSpanFlags(sp)
            out.removeSpan(sp)
            out.setSpan(StrongSpan(sp.style), a, b, f)
        }
        return out
    }

    /**
     * Bold that stays visible when the face under it is bold already (a design with bold body
     * text), where a plain [StyleSpan] changes nothing: there the glyphs get an outline stroke on
     * top, which thickens them whether the bold was a real weight or a synthetic one.
     */
    internal class StrongSpan(style: Int) : StyleSpan(style) {
        override fun updateDrawState(ds: TextPaint) {
            val alreadyBold = ds.typeface?.isBold == true || ds.isFakeBoldText
            super.updateDrawState(ds)
            if (alreadyBold) {
                ds.style = android.graphics.Paint.Style.FILL_AND_STROKE
                ds.strokeWidth = ds.textSize / STRONG_STROKE_DIVISOR
            }
        }
    }

    // Close to the weight a synthetic bold adds: one twenty-fourth of the text size, all round.
    private const val STRONG_STROKE_DIVISOR = 24f

    /** True when the collapsed preview hides some of the text. */
    internal fun noticeOverflows(body: TextView): Boolean {
        val l = body.layout ?: return false
        if (l.lineCount == 0) return false
        val last = l.lineCount - 1
        // The last laid-out line ending short of the text catches a cut at a line break, where
        // there is nothing to ellipsize.
        return l.lineCount > NOTICE_PREVIEW_LINES || l.getEllipsisCount(last) > 0 ||
            l.getLineEnd(last) < (body.text?.length ?: 0)
    }

    internal const val BILLING_ROW_TAG = "billing-lapse"
    internal const val BILLING_BUTTON_TAG = "billing-update-payment"
    internal const val BILLING_ACCOUNT_TAG = "billing-account-page"
    internal const val BILLING_LINE_TAG = "billing-line"

    /** The backend's renew line and one button; nothing here stands between the person and RECORD. */
    private fun billingRow(
        activity: Activity, t: RistTheme, tf: android.graphics.Typeface?, d: Float, line: String,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        tag = BILLING_ROW_TAG
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val accountPage = Billing.offersAccountPage(activity)
        addView(TextView(activity).apply {
            text = line
            tag = BILLING_LINE_TAG
            setTextColor(t.accent); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            // The line itself opens the account page too, where there is one to open.
            if (accountPage) {
                isClickable = true; isFocusable = true
                setOnClickListener { openAccountPage(activity) }
            }
        })
        if (Billing.offersPayment(activity)) addView(TextView(activity).apply {
            text = activity.getString(R.string.billing_update_payment)
            tag = BILLING_BUTTON_TAG
            contentDescription = activity.getString(R.string.billing_update_payment_desc)
            setTextColor(t.accent); typeface = tf; isAllCaps = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (48 * d).toInt()
            isClickable = true; isFocusable = true
            setOnClickListener { openPortal(activity, this) }
        })
        if (accountPage) addView(TextView(activity).apply {
            text = activity.getString(R.string.billing_open_account)
            tag = BILLING_ACCOUNT_TAG
            contentDescription = activity.getString(R.string.billing_open_account_desc)
            setTextColor(t.accent); typeface = tf; isAllCaps = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (48 * d).toInt()
            isClickable = true; isFocusable = true
            setOnClickListener { openAccountPage(activity) }
        })
        addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (1 * d).toInt())
            )
            setBackgroundColor(t.fieldBorder)
        })
    }

    /** Only an https page on one of [Billing.ACCOUNT_HOSTS], in the locked browser. */
    private fun openAccountPage(activity: Activity) {
        val url = Billing.accountPage(activity) ?: return
        runCatching { activity.startActivity(LockedBrowserActivity.intent(activity, url)) }
            .onFailure { Log.w(TAG, "could not open the account page", it) }
    }

    private fun openPortal(activity: Activity, button: TextView) {
        if (!button.isEnabled) return
        button.isEnabled = false
        Toast.makeText(activity, activity.getString(R.string.billing_opening), Toast.LENGTH_SHORT).show()
        val app = activity.applicationContext
        Thread {
            val out = Billing.fetchPortal(app)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                button.isEnabled = true
                if (out is Billing.Portal.Open) {
                    runCatching { activity.startActivity(LockedBrowserActivity.intent(activity, out.url)) }
                        .onFailure { Log.w(TAG, "could not open the payment page", it) }
                } else {
                    Toast.makeText(activity, Billing.explain(out), Toast.LENGTH_LONG).show()
                    render(activity)
                }
            }
        }.start()
    }

    private class Dismiss(val what: String, val act: () -> Unit)

    /**
     * Swipe the row sideways to dismiss it (SwipeDismiss). Screen readers get the same thing as
     * a "Dismiss" action, since a swipe is not something they can do.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun swipeToDismiss(activity: Activity, row: View, what: String, onDismiss: () -> Unit) {
        val dismiss = Dismiss(what) {
            onDismiss()
            Haptics.ack(activity)
            render(activity)
        }
        row.setTag(R.id.feed_dismiss, dismiss)
        androidx.core.view.ViewCompat.addAccessibilityAction(row, "Dismiss $what") { _, _ ->
            dismiss.act(); true
        }
        val swipe = SwipeDismiss(activity) { dismiss.act() }
        row.setOnTouchListener { v, ev -> swipe.onTouch(v, ev) }
    }

    /** Dismisses a row as its swipe would. For tests; returns false for a row that cannot be. */
    internal fun dismissRow(row: View): Boolean {
        val d = row.getTag(R.id.feed_dismiss) as? Dismiss ?: return false
        d.act()
        return true
    }

    /** The swipeable row for [what] ("the unread email notice", "this message", ...), if shown. */
    internal fun rowFor(host: View, what: String): View? {
        if ((host.getTag(R.id.feed_dismiss) as? Dismiss)?.what == what) return host
        if (host is android.view.ViewGroup) for (i in 0 until host.childCount) {
            rowFor(host.getChildAt(i), what)?.let { return it }
        }
        return null
    }

    private fun voicemailRow(
        activity: Activity, t: RistTheme, tf: android.graphics.Typeface?, muted: Int, d: Float,
    ): View {
        val isOpen = VOICEMAIL_KEY in expanded
        val oneTap = CarrierVoicemail.oneTapReady(activity)

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        col.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(activity).apply {
                text = CommsFeed.voicemailKindLine()
                setTextColor(t.accent); typeface = tf
                isAllCaps = true
                letterSpacing = 0.06f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(activity).apply {
                text = "NEW"
                setTextColor(t.accent); typeface = tf
                isAllCaps = true
                letterSpacing = 0.10f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        })

        col.addView(TextView(activity).apply {
            text = CommsFeed.voicemailSenderLine()
            setTextColor(t.ink); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, (2 * d).toInt(), 0, 0)
        })

        col.addView(TextView(activity).apply {
            text = CommsFeed.voicemailActionLine(oneTap)
            setTextColor(muted); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, (3 * d).toInt(), 0, 0)
        })

        if (isOpen) col.addView(
            actionButton(
                activity, t, tf, d,
                label = CommsFeed.voicemailCallLabel(oneTap),
                spoken = CommsFeed.voicemailActionLine(oneTap),
                glyph = R.drawable.ic_app_voicemail,
            ) { dialMailbox(activity) }
        )

        val glyph = ImageView(activity).apply {
            setImageResource(R.drawable.ic_app_voicemail)
            setColorFilter(t.ink)
            layoutParams = LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()).apply {
                rightMargin = (12 * d).toInt(); topMargin = (4 * d).toInt()
            }
        }

        val edge = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams((5 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                .apply { rightMargin = (10 * d).toInt() }
            setBackgroundColor(t.accent)
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = (72 * d).toInt()
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            addView(edge); addView(glyph); addView(col)
            swipeToDismiss(activity, this, "the voicemail notice") {
                expanded.remove(VOICEMAIL_KEY)
                CarrierVoicemail.dismiss(activity)
            }
            isClickable = true; isFocusable = true
            contentDescription = CommsFeed.voicemailKindLine() + ". " +
                CommsFeed.voicemailSenderLine() + ". " +
                CommsFeed.voicemailActionLine(oneTap) +
                if (isOpen) "" else ". Opens a button to call."
            setOnClickListener {
                if (isOpen) expanded.remove(VOICEMAIL_KEY) else expanded.add(VOICEMAIL_KEY)
                Haptics.ack(activity)
                render(activity)
            }
        }
    }

    private fun actionButton(
        activity: Activity, t: RistTheme, tf: android.graphics.Typeface?, d: Float,
        label: String, spoken: String, glyph: Int, act: () -> Unit,
    ): View {
        val onAccent =
            if (contrast(t.ground, t.accent) >= contrast(t.ink, t.accent)) t.ground else t.ink
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt(); bottomMargin = (2 * d).toInt() }
            minimumHeight = (64 * d).toInt()
            setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                setColor(t.accent)
                cornerRadius = t.fieldRadiusDp * d
                setStroke(Math.max(1, (1 * d).toInt()), onAccent)
            }
            addView(ImageView(activity).apply {
                setImageResource(glyph)
                setColorFilter(onAccent)
                layoutParams = LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt())
                    .apply { rightMargin = (10 * d).toInt() }
            })
            addView(TextView(activity).apply {
                text = label
                setTextColor(onAccent); typeface = tf
                isAllCaps = true
                letterSpacing = 0.06f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
            isClickable = true; isFocusable = true
            contentDescription = spoken
            setOnClickListener {
                if (!claimDial()) return@setOnClickListener
                Haptics.ack(activity)
                runCatching { act() }.onFailure { Log.w(TAG, "feed action failed", it) }
            }
        }
    }

    private var lastDialAtMs = 0L
    private const val DIAL_GUARD_MS = 3_000L

    private fun claimDial(): Boolean {
        // Elapsed-realtime, not wall clock: a clock correction must not unlock the guard.
        val now = SystemClock.elapsedRealtime()
        if (now - lastDialAtMs < DIAL_GUARD_MS) {
            Log.i(TAG, "ignoring a second dial within ${DIAL_GUARD_MS}ms")
            return false
        }
        lastDialAtMs = now
        return true
    }

    private fun placeCall(activity: Activity, number: String) {
        if (number.isBlank()) return
        val uri = Uri.parse("tel:" + Uri.encode(number, "+*#,;"))
        if (DeviceCommands.isEmergency(activity, number)) {
            Log.w(TAG, "feed call-back to an emergency number; opening the dialer instead")
            Toast.makeText(
                activity, "Emergency number — press call yourself", Toast.LENGTH_LONG
            ).show()
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Log.w(TAG, "could not open the dialer", it) }
            return
        }
        Log.i(TAG, "calling back ${maskNumber(number)} from the feed")
        val placed = runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.onFailure { Log.w(TAG, "could not place the call", it) }.getOrDefault(false)

        if (!placed) {
            Toast.makeText(activity, Unavailable.CALLING, Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            activity.getSystemService(TelecomManager::class.java)?.showInCallScreen(false)
        }.onFailure { Log.w(TAG, "could not bring up the in-call screen", it) }
    }

    private fun dialMailbox(activity: Activity) {
        if (!CarrierVoicemail.call(activity)) {
            Toast.makeText(activity, Unavailable.VOICEMAIL, Toast.LENGTH_LONG).show()
        }
    }
}
