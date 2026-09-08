package watch.rist.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.telecom.TelecomManager
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

    private const val CALL_LOG_LOOKBACK_MS = 7L * 24L * 60L * 60L * 1000L

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

    fun waiting(ctx: Context): CommsFeed.Waiting =
        CommsFeed.waiting(candidates(ctx), CarrierVoicemail.waiting(ctx), Config.pendingMail(ctx))

    private fun missedCalls(ctx: Context): List<FeedItem> = runCatching {
        val cutoff = System.currentTimeMillis() - CALL_LOG_LOOKBACK_MS
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
            "${android.provider.CallLog.Calls.TYPE} = ? AND ${android.provider.CallLog.Calls.DATE} > ?",
            arrayOf(android.provider.CallLog.Calls.MISSED_TYPE.toString(), cutoff.toString()),
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

    private fun nameFor(ctx: Context, number: String): String? {
        val now = System.currentTimeMillis()
        if (now - nameCacheAtMs > NAME_CACHE_TTL_MS) { nameCache.clear(); nameCacheAtMs = now }
        if (number.isBlank()) return null
        return nameCache.getOrPut(number) { CallerId.nameFor(ctx, number) }
    }

    private fun markSeen(ctx: Context, ids: Collection<String>) = runCatching {
        if (ids.isEmpty()) return@runCatching
        Config.setSeenCommsIds(ctx, CommsFeed.seenIdsAfterAdding(Config.seenCommsIds(ctx), ids))
    }.let { }

    // Return type must be declared: render() is recursive via the mail row's dismiss.
    fun render(activity: Activity): Unit = runCatching {
        val host = activity.findViewById<LinearLayout>(R.id.commsFeed) ?: return@runCatching
        host.removeAllViews()

        val all = candidates(activity)
        val shown = CommsFeed.assemble(all, System.currentTimeMillis())
            // `it.id in expanded` is load-bearing: a tap marks seen AND expands, so an open row stays until closed.
            .filter { it.unread || it.id in expanded }
        val vmWaiting = CarrierVoicemail.waiting(activity)
        val textsUnreadable = !SmsInbox.canRead(activity)
        val unconnected = Config.credentialRejected(activity) || Config.enrolRevoked(activity)

        val mailUnread = Config.pendingMail(activity)
        val unbadgedMail = CommsFeed.unbadgedMail(all, mailUnread)

        if (shown.isEmpty() && !vmWaiting && !textsUnreadable && !unconnected &&
            unbadgedMail <= 0
        ) {
            host.visibility = View.GONE; return@runCatching
        }
        host.visibility = View.VISIBLE

        val t = Themes.byId(Config.themeId(activity))
        val tf = ThemePaint.typefaceOf(activity, t)
        val muted = Themes.readableMuted(t)
        val d = activity.resources.displayMetrics.density
        val waiting = CommsFeed.waitingCount(all, vmWaiting, mailUnread)

        var drawn = 0
        val headerDrawn = !unconnected
        if (headerDrawn) host.addView(header(activity, t, tf, muted, d, waiting, all))

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
            if (shown.isNotEmpty() || vmWaiting || textsUnreadable) {
                divider()
                host.addView(header(activity, t, tf, muted, d, waiting, all))
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
        if (vmWaiting) {
            if (drawn > 0) divider()
            host.addView(voicemailRow(activity, t, tf, muted, d)); drawn++
        }
        if (unbadgedMail > 0) {
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

            // Named locals, not nested apply blocks: addView inside an apply on a receiver under construction breaks type inference.
            val mailDismiss = TextView(activity)
            mailDismiss.text = "\u00d7"
            mailDismiss.setTextColor(muted)
            mailDismiss.typeface = tf
            mailDismiss.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            mailDismiss.gravity = Gravity.CENTER
            mailDismiss.minWidth = (48 * d).toInt()
            mailDismiss.minHeight = (48 * d).toInt()
            mailDismiss.contentDescription = "Dismiss the unread email notice"
            mailDismiss.isClickable = true
            mailDismiss.isFocusable = true
            mailDismiss.setOnClickListener {
                Config.setMailAcknowledged(activity, Config.mailUnread(activity))
                Haptics.ack(activity)
                render(activity)
            }

            val mailRow = LinearLayout(activity)
            mailRow.orientation = LinearLayout.HORIZONTAL
            mailRow.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            mailRow.addView(mailText)
            mailRow.addView(mailDismiss)
            host.addView(mailRow)
            drawn++
        }

        for (item in shown) {
            if (drawn > 0) divider()
            host.addView(row(activity, t, tf, muted, d, item))
            drawn++
        }
        if (all.size > shown.size) host.addView(
            TextView(activity).apply {
                text = "${all.size - shown.size} older, not shown"
                setTextColor(muted); typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
            }
        )
    }.onFailure { Log.w(TAG, "feed render failed", it) }.let { }

    private fun header(
        activity: Activity, t: RistTheme, tf: android.graphics.Typeface?, muted: Int,
        d: Float, waiting: Int, all: List<FeedItem>,
    ): View {
        val acknowledgeable = CommsFeed.unreadCount(all)
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (4 * d).toInt())
        }
        bar.addView(TextView(activity).apply {
            text = if (waiting > 0) "$waiting NEW" else "CALLS & TEXTS"
            setTextColor(if (waiting > 0) t.accent else muted)
            typeface = tf
            isAllCaps = true
            letterSpacing = 0.08f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (waiting > 0) 17f else 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (acknowledgeable > 0) bar.addView(TextView(activity).apply {
            text = "CLEAR ALL"
            contentDescription = "Clear $acknowledgeable new calls and messages"
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
                Config.setMailAcknowledged(activity, Config.mailUnread(activity))
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
            addView(TextView(activity).apply {
                text = "×"
                setTextColor(muted)
                typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                minWidth = (48 * d).toInt()
                minHeight = (48 * d).toInt()
                contentDescription = "Dismiss this " +
                    (if (item.kind == FeedKind.TEXT) "message" else "missed call")
                isClickable = true; isFocusable = true
                setOnClickListener {
                    expanded.remove(item.id)
                    markSeen(activity, listOf(item.id))
                    Haptics.ack(activity)
                    render(activity)
                }
            })
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
            Toast.makeText(activity, "Couldn't place the call", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            activity.getSystemService(TelecomManager::class.java)?.showInCallScreen(false)
        }.onFailure { Log.w(TAG, "could not bring up the in-call screen", it) }
    }

    private fun dialMailbox(activity: Activity) {
        if (!CarrierVoicemail.call(activity)) {
            Toast.makeText(activity, "Couldn't reach your mailbox", Toast.LENGTH_LONG).show()
        }
    }
}
