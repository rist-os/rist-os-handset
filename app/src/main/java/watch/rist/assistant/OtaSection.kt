package watch.rist.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

object OtaSection {

    private const val TAG = "rist_ota_section"

    internal const val BUTTON_TAG = "ota_update_button"

    private var open: AlertDialog? = null

    private const val REFRESH_MS = 4_000L

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private var ticker: Runnable? = null

    fun build(a: Activity, host: LinearLayout?, anchor: View?) {
        // Must run before the early return: a surviving post would rebuild into a dead view.
        stopRefresh()
        host ?: return
        val old = host.findViewWithTag<View>(TAG)
        old?.let { host.removeView(it) }
        val idx = (anchor?.let { host.indexOfChild(it) } ?: -1).coerceAtLeast(0)

        val t = Themes.byId(Config.themeId(a))
        val ink = t.ink
        val muted = Themes.readableMuted(t)
        val pixelTf: Typeface? =
            runCatching { androidx.core.content.res.ResourcesCompat.getFont(a, R.font.pixel) }
                .getOrNull()
        val bodyTf: Typeface? = ThemePaint.typefaceOf(a, t)
        fun px(v: Float): Int = (v * a.resources.displayMetrics.density).toInt()

        val v = snapshot(a)

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(12f), 0, px(6f))
            tag = TAG
        }
        box.addView(TextView(a).apply {
            text = a.getString(R.string.ota_section_heading)
            setTextColor(ink); textSize = 14f; typeface = pixelTf; isAllCaps = true
            setPadding(0, px(6f), 0, px(4f))
        })

        val headline = otaHeadline(v)
        if (headline.isNotBlank()) {
            box.addView(TextView(a).apply {
                text = headline
                setTextColor(ink); textSize = 12.5f; typeface = bodyTf
                setPadding(0, px(4f), 0, px(8f))
            })
        }

        if (otaAwaitingRestart(v)) {
            box.addView(TextView(a).apply {
                text = a.getString(R.string.ota_ready_body)
                setTextColor(muted); textSize = 11f; typeface = bodyTf
                setPadding(0, 0, 0, px(8f))
            })
        }

        box.addView(TextView(a).apply {
            text = otaBuildLine(v)
            setTextColor(muted); textSize = 11f; typeface = bodyTf
            setPadding(0, 0, 0, px(2f))
        })
        box.addView(TextView(a).apply {
            text = otaLastCheckLine(v)
            setTextColor(muted); textSize = 11f; typeface = bodyTf
            setPadding(0, 0, 0, px(10f))
        })

        val inFlight = otaApplyInFlight(v)
        if (inFlight) {
            val pct = otaProgressPercent(v)
            box.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = pct < 0
                if (pct >= 0) { max = 100; progress = pct }
                progressTintList = ColorStateList.valueOf(t.accent)
                indeterminateTintList = ColorStateList.valueOf(t.accent)
                progressBackgroundTintList = ColorStateList.valueOf(muted)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, px(10f)
                ).apply { topMargin = px(2f) }
            })
            box.addView(TextView(a).apply {
                text = otaProgressLine(v)
                setTextColor(ink); textSize = 12f; typeface = pixelTf; isAllCaps = true
                setPadding(0, px(8f), 0, px(2f))
            })
            box.addView(TextView(a).apply {
                text = a.getString(R.string.ota_progress_caption)
                setTextColor(muted); textSize = 10.5f; typeface = bodyTf
                setPadding(0, 0, 0, px(4f))
            })
        }

        val label = otaButton(v)
        if (label != OtaButton.NONE) {
            val caption = otaCaption(v, label)
            if (caption != 0) box.addView(TextView(a).apply {
                text = a.getString(caption)
                setTextColor(muted); textSize = 11f; typeface = bodyTf
                setPadding(0, 0, 0, px(10f))
            })
            val d = a.resources.displayMetrics.density
            val repaint = { build(a, host, anchor) }
            box.addView(
                button(a, t, ink, pixelTf, d, a.getString(otaButtonLabel(label))) { pressed ->
                    when (label) {
                        OtaButton.UPDATE ->
                            onTap(a, t, bodyTf, d, pressed, v.offeredBuild, v.offeredSize, repaint)
                        OtaButton.STOP_WAITING -> stopWaiting(a, t, bodyTf, d, repaint)
                        OtaButton.NONE -> Unit
                    }
                }
            )
        }

        host.addView(box, idx)

        if (inFlight || otaCheckJustArmed(v)) scheduleRefresh(a, host, anchor, box)
    }

    private fun scheduleRefresh(a: Activity, host: LinearLayout, anchor: View?, box: View) {
        val r = object : Runnable {
            override fun run() {
                // A superseded post already dequeued cannot be removed; it must decline itself.
                if (ticker !== this) return
                ticker = null
                if (a.isFinishing || a.isDestroyed || box.parent == null) return
                runCatching { build(a, host, anchor) }
                    .onFailure { android.util.Log.e("RistSettings", "ota refresh failed", it) }
            }
        }
        ticker = r
        ui.postDelayed(r, REFRESH_MS)
    }

    fun checkOnOpen(a: Activity) = runCatching {
        val app = a.applicationContext
        if (OtaState.applyingBuild(app).isNotBlank()) return@runCatching
        OtaScheduler.requestCheckNow(app)
    }.onFailure { android.util.Log.e("RistSettings", "ota check-on-open failed", it) }

    fun stopRefresh() {
        ticker?.let { ui.removeCallbacks(it) }
        ticker = null
    }

    private fun button(
        a: Activity,
        t: RistTheme,
        ink: Int,
        tf: Typeface?,
        d: Float,
        label: String,
        onClick: (View) -> Unit,
    ): View = TextView(a).apply {
        tag = BUTTON_TAG
        text = label
        setTextColor(ink); textSize = 12f; typeface = tf; isAllCaps = true
        gravity = Gravity.CENTER
        minHeight = (48 * d).toInt()
        setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
        background = GradientDrawable().apply {
            setColor(t.tileFill)
            setStroke((1.5f * d).toInt(), t.accent)
            cornerRadius = 14f * d
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { v ->
            if (questionIsUp(a)) return@setOnClickListener
            onClick(v)
        }
    }

    private fun questionIsUp(a: Activity): Boolean {
        val dialog = open ?: return false
        if (dialog.isShowing && dialog.ownerActivity === a && !a.isFinishing && !a.isDestroyed) {
            return true
        }
        open = null
        return false
    }

    private fun onTap(
        a: Activity,
        t: RistTheme,
        tf: Typeface?,
        d: Float,
        pressed: View,
        build: String,
        size: String,
        repaint: () -> Unit,
    ) {
        when (OtaConsent.networkSuitability(a)) {
            is OtaNetwork.Suitability.Unmetered ->
                commit(a, t, tf, d, pressed, build, allowMetered = false, repaint = repaint)

            is OtaNetwork.Suitability.Metered ->
                askAboutMobileData(a, t, tf, d, pressed, build, size, repaint)

            is OtaNetwork.Suitability.None -> ask(
                a, t, tf, d,
                title = a.getString(R.string.ota_offline_title),
                message = a.getString(R.string.ota_offline_body),
                positive = a.getString(R.string.ota_ok),
            )
        }
    }

    private fun askAboutMobileData(
        a: Activity, t: RistTheme, tf: Typeface?, d: Float, pressed: View,
        build: String, size: String, repaint: () -> Unit,
    ) {
        ask(
            a, t, tf, d,
            title = a.getString(R.string.ota_metered_title),
            message = a.getString(R.string.ota_metered_body, size),
            positive = a.getString(R.string.ota_metered_yes),
            onPositive = {
                commit(a, t, tf, d, pressed, build, allowMetered = true, repaint = repaint)
            },
            negative = a.getString(R.string.ota_metered_wait),
        )
    }

    private fun stopWaiting(a: Activity, t: RistTheme, tf: Typeface?, d: Float, repaint: () -> Unit) {
        ask(
            a, t, tf, d,
            title = a.getString(R.string.ota_downloading_title),
            message = a.getString(R.string.ota_downloading_body),
            positive = a.getString(R.string.ota_downloading_stop),
            onPositive = {
                OtaState.clearApprovals(a)
                runCatching { repaint() }
                ask(
                    a, t, tf, d,
                    title = a.getString(R.string.ota_downloading_title),
                    message = a.getString(R.string.ota_downloading_stopped),
                    positive = a.getString(R.string.ota_ok),
                )
            },
            negative = a.getString(R.string.ota_downloading_keep),
        )
    }

    private fun commit(
        a: Activity, t: RistTheme, tf: Typeface?, d: Float, pressed: View,
        build: String, allowMetered: Boolean, repaint: () -> Unit,
    ) {
        OtaConsent.approve(a, build, allowMetered)
        pressed.isClickable = false
        val started = OtaConsent.startApprovedDownload(a, build)
        runCatching { repaint() }
        report(a, t, tf, d, started)
    }

    private fun report(a: Activity, t: RistTheme, tf: Typeface?, d: Float, r: OtaConsent.Start) {
        val msg = when (r) {
            is OtaConsent.Start.Scheduled -> a.getString(R.string.ota_offer_started)
            is OtaConsent.Start.Refused -> a.getString(R.string.ota_offer_refused)
        }
        ask(
            a, t, tf, d,
            title = null,
            message = msg,
            positive = a.getString(R.string.ota_ok),
        )
        if (r is OtaConsent.Start.Refused) {
            android.util.Log.w("RistOtaSection", "start refused after approval: ${r.reason}")
        }
    }

    private fun ask(
        a: Activity,
        t: RistTheme,
        tf: Typeface?,
        d: Float,
        title: String?,
        message: CharSequence,
        positive: String,
        onPositive: (() -> Unit)? = null,
        negative: String? = null,
    ): AlertDialog {
        val dialog = RistDialog.ask(a, t, tf, d, title, message, positive, onPositive, negative)
        // Identity, not null: `open` may already be the dialog this one's positive button raised.
        dialog.setOnDismissListener { if (open === dialog) open = null }
        open = dialog
        return dialog
    }

    private fun snapshot(a: Activity): OtaStatusView {
        val app = a.applicationContext
        val local = runCatching { OtaPolicy.LocalBuild.current() }
            .getOrElse { OtaPolicy.LocalBuild("", "", 0L) }
        val net = runCatching { OtaConsent.networkSuitability(app) }
            .getOrElse { OtaNetwork.Suitability.None }
        return OtaStatusView(
            build = local.build,
            device = local.device,
            channel = OtaState.channel(app),
            baseUrl = OtaState.baseUrl(app),
            lastCheckAtSeconds = OtaState.lastCheckAtSeconds(app),
            lastResult = OtaState.lastResult(app),
            nextCheckAtSeconds = OtaState.nextCheckAtSeconds(app),
            readyBuild = OtaState.readyBuild(app),
            lastNudgeAtSeconds = OtaState.lastNudgeAtSeconds(app),
            applyingBuild = OtaState.applyingBuild(app),
            applyingSinceSeconds = OtaState.applyingSinceSeconds(app),
            applyingPercent = OtaState.applyingPercent(app),
            applyingStatus = OtaState.applyingStatus(app),
            offeredBuild = OtaState.offeredBuild(app),
            approvedBuild = OtaState.approvedBuild(app),
            offeredSize = OtaState.offeredBytes(app).takeIf { it > 0L }
                ?.let { OtaConsent.formatBytes(it) } ?: "",
            network = net,
            hold = (OtaConsent.decide(
                build = OtaState.offeredBuild(app),
                approvedBuild = OtaState.approvedBuild(app),
                meteredApprovedBuild = OtaState.meteredApprovedBuild(app),
                net = net,
            ) as? OtaConsent.Gate.Wait)?.reason,
            failures = OtaState.failures(app),
            lastSuccessAtSeconds = OtaState.lastSuccessAtSeconds(app),
            // Classes only: probeService is denied to the main process by SELinux.
            engineFault = runCatching { OtaEngine.probeClasses()?.reason }
                .getOrElse { OtaEngine.Reason.SERVICE_NOT_VISIBLE },
            nowSeconds = runCatching { OtaScheduler.trustedNowSeconds() }
                .getOrElse { System.currentTimeMillis() / 1000L },
        )
    }
}

internal data class OtaStatusView(
    val build: String,
    val device: String,
    val channel: String,
    val baseUrl: String,
    val lastCheckAtSeconds: Long,
    val lastResult: String,
    val nextCheckAtSeconds: Long,
    val readyBuild: String,
    val applyingBuild: String,
    val engineFault: OtaEngine.Reason?,
    val nowSeconds: Long,
    val lastNudgeAtSeconds: Long = 0L,
    val applyingSinceSeconds: Long = 0L,
    val applyingPercent: Int = OtaState.PERCENT_UNKNOWN,
    val applyingStatus: Int = OtaApply.Status.IDLE,
    val offeredBuild: String = "",
    val approvedBuild: String = "",
    val offeredSize: String = "",
    val network: OtaNetwork.Suitability = OtaNetwork.Suitability.None,
    val hold: OtaConsent.Hold? = null,
    val failures: Int = 0,
    val lastSuccessAtSeconds: Long = 0L,
)

internal const val OTA_PERSISTENT_FAILURES = 3

internal fun otaOfferOpen(v: OtaStatusView): Boolean =
    v.offeredBuild.isNotBlank() &&
        v.offeredBuild != v.readyBuild &&
        v.offeredBuild != v.applyingBuild

internal fun otaOfferAwaitingAnswer(v: OtaStatusView): Boolean =
    otaOfferOpen(v) && v.approvedBuild != v.offeredBuild

internal fun otaAwaitingRestart(v: OtaStatusView): Boolean =
    v.readyBuild.isNotBlank() && v.readyBuild != v.build

internal enum class OtaButton { NONE, UPDATE, STOP_WAITING }

internal fun otaButton(v: OtaStatusView): OtaButton = when {
    otaApplyInFlight(v) -> OtaButton.NONE
    !otaOfferOpen(v) -> OtaButton.NONE
    otaOfferAwaitingAnswer(v) -> OtaButton.UPDATE
    v.hold == OtaConsent.Hold.METERED_NOT_APPROVED -> OtaButton.UPDATE
    else -> OtaButton.STOP_WAITING
}

internal fun otaButtonLabel(b: OtaButton): Int = when (b) {
    OtaButton.STOP_WAITING -> R.string.ota_downloading_stop
    else -> R.string.ota_update_button
}

internal fun otaCaption(v: OtaStatusView, b: OtaButton): Int = when {
    b != OtaButton.UPDATE -> 0
    v.network is OtaNetwork.Suitability.None -> R.string.ota_offline_body
    else -> R.string.ota_wifi_hint
}

internal fun otaApplyLooksStale(v: OtaStatusView): Boolean {
    if (v.applyingBuild.isBlank()) return false
    if (v.applyingSinceSeconds <= 0L) return true
    if (v.nowSeconds < v.applyingSinceSeconds) return false
    return v.nowSeconds - v.applyingSinceSeconds > OtaState.APPLYING_STALE_SECONDS
}

internal fun otaHeadline(v: OtaStatusView): String = when {
    otaAwaitingRestart(v) ->
        "Update ${v.readyBuild} is installed and waiting. Restart the phone to finish."
    v.applyingBuild.isNotBlank() && !otaApplyLooksStale(v) -> "Installing ${v.applyingBuild}."
    otaOfferAwaitingAnswer(v) -> "Update ${v.offeredBuild} is ready to install${otaSizeTail(v)}."
    otaOfferOpen(v) && v.hold == OtaConsent.Hold.METERED_NOT_APPROVED ->
        "Update ${v.offeredBuild} needs mobile data to continue${otaSizeTail(v)}."
    otaOfferOpen(v) && v.hold == OtaConsent.Hold.NO_NETWORK ->
        "Update ${v.offeredBuild} will download when this phone is back online."
    otaOfferOpen(v) -> "Update ${v.offeredBuild} is downloading. You can keep using the phone."
    v.baseUrl.isBlank() ->
        "Automatic updates are OFF. No update server is set, so this phone will never check."
    v.engineFault != null ->
        "This phone can look for updates but cannot install them: ${otaEngineFaultText(v.engineFault)}."
    v.failures >= OTA_PERSISTENT_FAILURES -> otaPersistentFailureText(v)
    else -> ""
}

private fun otaSizeTail(v: OtaStatusView): String =
    if (v.offeredSize.isBlank()) "" else " — about ${v.offeredSize}"

internal fun otaPersistentFailureText(v: OtaStatusView): String {
    val head = if (v.lastSuccessAtSeconds <= 0L)
        "This phone has NEVER successfully checked for updates, after ${v.failures} attempts."
    else
        "This phone has not been able to check for updates since " +
            "${otaAgo(v.lastSuccessAtSeconds, v.nowSeconds)} (${v.failures} failed attempts)."
    return "$head It is not receiving security updates."
}

internal fun otaEngineFaultText(reason: OtaEngine.Reason): String = when (reason) {
    OtaEngine.Reason.NO_SYSTEM_API,
    OtaEngine.Reason.NO_CALLBACK_CLASS ->
        "this copy of Rist was installed over the top of the system, not built into it"
    OtaEngine.Reason.SERVICE_NOT_VISIBLE ->
        "the system's update service will not talk to Rist on this build"
    OtaEngine.Reason.BIND_REFUSED,
    OtaEngine.Reason.APPLY_REFUSED ->
        "the system's update service refused the request"
}

internal fun otaBuildLine(v: OtaStatusView): String {
    val build = v.build.ifBlank { "unknown" }
    val device = v.device.ifBlank { "unknown" }
    return "This phone is running $build ($device, ${v.channel})."
}

internal fun otaLastCheckLine(v: OtaStatusView): String =
    if (v.lastCheckAtSeconds <= 0L) "Last checked: never."
    else "Last checked ${otaAgo(v.lastCheckAtSeconds, v.nowSeconds)}: ${v.lastResult}"

internal fun otaCheckJustArmed(v: OtaStatusView): Boolean {
    if (v.lastNudgeAtSeconds <= 0L) return false
    val since = v.nowSeconds - v.lastNudgeAtSeconds
    return since >= 0L && since < OtaScheduler.NUDGE_MIN_GAP_SECONDS
}

internal fun otaApplyInFlight(v: OtaStatusView): Boolean =
    v.applyingBuild.isNotBlank() && !otaApplyLooksStale(v)

internal fun otaProgressPercent(v: OtaStatusView): Int = when {
    !otaApplyInFlight(v) -> OtaState.PERCENT_UNKNOWN
    v.applyingStatus != OtaApply.Status.DOWNLOADING -> OtaState.PERCENT_UNKNOWN
    else -> v.applyingPercent.let { if (it < 0) OtaState.PERCENT_UNKNOWN else it.coerceIn(0, 100) }
}

internal fun otaProgressLabel(status: Int): String = when (status) {
    OtaApply.Status.DOWNLOADING -> "Downloading"
    OtaApply.Status.VERIFYING -> "Checking the download"
    OtaApply.Status.FINALIZING -> "Finishing up"
    OtaApply.Status.UPDATED_NEED_REBOOT -> "Installed"
    OtaApply.Status.REPORTING_ERROR_EVENT -> "Something went wrong"
    else -> "Preparing"
}

internal fun otaProgressLine(v: OtaStatusView): String {
    val label = otaProgressLabel(v.applyingStatus)
    if (v.applyingStatus != OtaApply.Status.DOWNLOADING) return "$label…"
    val pct = otaProgressPercent(v)
    return if (pct < 0) "$label…" else "$label $pct%"
}

internal fun otaAgo(atSeconds: Long, nowSeconds: Long): String {
    if (atSeconds <= 0L) return "never"
    val d = nowSeconds - atSeconds
    return when {
        d < 60L -> "just now"
        d < 3600L -> plural(d / 60L, "minute") + " ago"
        d < 48L * 3600L -> plural(d / 3600L, "hour") + " ago"
        else -> plural(d / 86400L, "day") + " ago"
    }
}

internal fun otaIn(atSeconds: Long, nowSeconds: Long): String {
    if (atSeconds <= 0L) return "is not scheduled"
    val d = atSeconds - nowSeconds
    return when {
        d <= 0L -> "is due now"
        d < 60L -> "in less than a minute"
        d < 3600L -> "in " + plural(d / 60L, "minute")
        d < 48L * 3600L -> "in about " + plural(d / 3600L, "hour")
        d <= 30L * 86400L -> "in about " + plural(d / 86400L, "day")
        else -> "at an unknown time — this phone's clock looks wrong"
    }
}

private fun plural(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"
