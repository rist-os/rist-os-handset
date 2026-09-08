package watch.rist.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

object OtaScheduler {

    private const val TAG = "RistOta"

    internal const val ACTION_TICK = "watch.rist.assistant.OTA_TICK"

    internal enum class Wake {
        POLL,
        RESCHEDULE,
        IGNORE,
    }

    internal fun wakeFor(action: String): Wake = when (action) {
        ACTION_TICK -> Wake.POLL
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Wake.RESCHEDULE
        else -> Wake.IGNORE
    }

    const val POLL_INTERVAL_SECONDS = 6L * 3600

    const val UNAVAILABLE_INTERVAL_SECONDS = POLL_INTERVAL_SECONDS

    enum class Stage {
        SIGNATURE,
        MANIFEST,
        POLICY,
    }

    sealed class Step {
        data class Apply(val manifest: OtaManifest) : Step()
        object UpToDate : Step()
        data class Refused(val stage: Stage, val detail: String) : Step()
    }

    fun evaluate(
        body: String?,
        signature: String?,
        nowSeconds: Long,
        local: OtaPolicy.LocalBuild,
        askedChannel: String,
        publicKey: String = OtaSignature.PUBLIC_KEY,
    ): Step {
        when (val v = OtaSignature.verify(body, signature, nowSeconds, publicKey)) {
            is OtaSignature.Verdict.Rejected ->
                return Step.Refused(Stage.SIGNATURE, "${v.fault}: ${v.detail}")
            is OtaSignature.Verdict.Trusted -> Unit
        }
        // Must run after signature verification.
        val parsed = when (val p = OtaManifest.parse(body)) {
            is OtaManifest.Companion.Parsed.Bad ->
                return Step.Refused(Stage.MANIFEST, "${p.fault}: ${p.detail}")
            is OtaManifest.Companion.Parsed.Ok -> p.manifest
        }
        return when (val d = OtaPolicy.decide(parsed, local, askedChannel)) {
            is OtaPolicy.Decision.Apply -> Step.Apply(d.manifest)
            OtaPolicy.Decision.UpToDate -> Step.UpToDate
            is OtaPolicy.Decision.Refuse -> Step.Refused(Stage.POLICY, "${d.reason}: ${d.detail}")
        }
    }

    fun trustedNowSeconds(): Long =
        maxOf(System.currentTimeMillis() / 1000L, android.os.Build.TIME / 1000L)

    private fun alarms(ctx: Context): AlarmManager? =
        ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        0,
        Intent(ctx, OtaAlarmReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(ctx: Context, delaySeconds: Long) {
        val delay = delaySeconds.coerceIn(OtaRetry.MIN_SECONDS, OtaRetry.MAX_BACKOFF_SECONDS)
        val am = alarms(ctx) ?: run {
            Log.w(TAG, "no AlarmManager; OTA polling is not scheduled")
            return
        }
        runCatching {
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay * 1000L,
                tickIntent(ctx),
            )
        }.onFailure { Log.w(TAG, "could not arm the OTA wake", it) }
        runCatching {
            OtaState.setNextCheckAtSeconds(ctx.applicationContext, trustedNowSeconds() + delay)
        }
        Log.i(TAG, "next OTA check in ${delay}s")
    }

    fun reschedule(ctx: Context) {
        val now = trustedNowSeconds()
        val notBefore = OtaState.notBeforeSeconds(ctx)
        val wait = if (notBefore > now) (notBefore - now) else POLL_INTERVAL_SECONDS
        schedule(ctx, wait)
    }

    fun rescheduleAfterRestart(ctx: Context) {
        val running = runCatching { OtaPolicy.LocalBuild.current().build }.getOrDefault("")
        runCatching { OtaState.clearInFlightOnRestart(ctx.applicationContext, running) }
            .onFailure { Log.w(TAG, "could not reconcile OTA state after a restart", it) }
        reschedule(ctx)
    }

    fun requestApprovedDownloadNow(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { OtaState.setLastNudgeAtSeconds(app, trustedNowSeconds()) }
        schedule(app, NUDGE_DELAY_SECONDS)
        Log.i(TAG, "approved download: poll armed without the nudge limit")
    }

    fun requestCheckNow(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val now = trustedNowSeconds()
        val last = runCatching { OtaState.lastNudgeAtSeconds(app) }.getOrDefault(0L)
        return when (val d = nudgeDecision(last, now)) {
            is Nudge.Honour -> {
                runCatching { OtaState.setLastNudgeAtSeconds(app, now) }
                schedule(app, NUDGE_DELAY_SECONDS)
                true
            }
            is Nudge.Defer -> {
                Log.i(TAG, "nudge refused; a check is already due in ${d.seconds}s")
                schedule(app, d.seconds)
                false
            }
        }
    }

    const val NUDGE_DELAY_SECONDS = 5L

    const val NUDGE_MIN_GAP_SECONDS = 60L

    internal sealed class Nudge {
        object Honour : Nudge()
        data class Defer(val seconds: Long) : Nudge()
    }

    internal fun nudgeDecision(lastNudgeSeconds: Long, nowSeconds: Long): Nudge {
        if (lastNudgeSeconds <= 0L) return Nudge.Honour
        val since = nowSeconds - lastNudgeSeconds
        if (since < 0L || since >= NUDGE_MIN_GAP_SECONDS) return Nudge.Honour
        return Nudge.Defer(NUDGE_MIN_GAP_SECONDS - since)
    }

    // Blocking network call; never on the main thread. Every exit must reach schedule() in finally.
    fun runCheck(ctx: Context) {
        val app = ctx.applicationContext
        val now = trustedNowSeconds()
        var nextDelay = POLL_INTERVAL_SECONDS
        try {
            nextDelay = poll(app, now)
        } catch (t: Throwable) {
            Log.e(TAG, "OTA check failed", t)
            OtaState.noteFailure(app)
            OtaState.recordCheck(app, now, "check failed: ${t.javaClass.simpleName}")
            nextDelay = OtaRetry.backoffSeconds(OtaState.failures(app), null, Math.random())
        } finally {
            schedule(app, nextDelay)
        }
    }

    private fun poll(app: Context, now: Long): Long {
        val base = OtaState.baseUrl(app)
        if (base.isBlank()) {
            OtaState.recordCheck(app, now, "no OTA endpoint configured")
            return POLL_INTERVAL_SECONDS
        }

        val notBefore = OtaState.notBeforeSeconds(app)
        if (now < notBefore) {
            Log.i(TAG, "backing off until $notBefore")
            return notBefore - now
        }

        // probeClasses, not probe: the update_engine service lookup is SELinux-denied in the main
        // process (only :ota runs as rist_app), so the full probe would refuse every update here.
        val unavailable = OtaEngine.probeClasses()
        if (unavailable != null) {
            Log.e(TAG, "update_engine unreachable [${unavailable.reason}]: ${unavailable.detail}")
        }

        val local = OtaPolicy.LocalBuild.current()

        if (OtaState.readyBuild(app).isNotBlank() && OtaState.readyBuild(app) == local.build) {
            OtaState.setReadyBuild(app, "")
            Log.i(TAG, "running the staged build; cleared the restart prompt")
        }
        val channel = OtaState.channel(app)
        val client = OtaCheck.defaultClient()

        val outcome = OtaCheck.fetchManifest(client, base, local.device, channel, local.build, now * 1000L)
        when (outcome) {
            is OtaRetry.Outcome.UpToDate -> {
                OtaState.noteSuccess(app, now)
                forgetStaleConsent(app, stillOffered = "")
                OtaState.recordCheck(app, now, "up to date (${local.build})")
                return POLL_INTERVAL_SECONDS
            }
            is OtaRetry.Outcome.BackOff -> {
                OtaState.setNotBeforeSeconds(app, now + outcome.seconds)
                OtaState.recordCheck(app, now, "server asked for ${outcome.seconds}s")
                return outcome.seconds
            }
            is OtaRetry.Outcome.NoBuild -> {
                OtaState.noteSuccess(app, now)
                forgetStaleConsent(app, stillOffered = "")
                OtaState.recordCheck(app, now, "no build published for $channel")
                return POLL_INTERVAL_SECONDS
            }
            is OtaRetry.Outcome.Unauthorised -> {
                OtaState.noteFailure(app)
                OtaState.recordCheck(app, now, "unauthorised: RIST_OTA_TOKEN mismatch")
                return OtaRetry.backoffSeconds(OtaState.failures(app), null, Math.random())
            }
            is OtaRetry.Outcome.Transient -> {
                OtaState.noteFailure(app)
                OtaState.recordCheck(app, now, "unreachable: ${outcome.detail}")
                return OtaRetry.backoffSeconds(OtaState.failures(app), null, Math.random())
            }
            is OtaRetry.Outcome.Offered -> Unit
        }

        val body = outcome.body
        val signature = OtaCheck.fetchSignature(client, base, local.device, channel)

        return when (val step = evaluate(body, signature, now, local, channel)) {
            is Step.Refused -> {
                // Log the fault, never the unverified body.
                Log.w(TAG, "manifest refused at ${step.stage}: ${step.detail}")
                OtaState.noteFailure(app)
                OtaState.recordCheck(app, now, "refused (${step.stage}): ${step.detail}")
                OtaRetry.backoffSeconds(OtaState.failures(app), null, Math.random())
            }
            Step.UpToDate -> {
                OtaState.noteSuccess(app, now)
                forgetStaleConsent(app, stillOffered = "")
                OtaState.recordCheck(app, now, "up to date (${local.build})")
                POLL_INTERVAL_SECONDS
            }
            is Step.Apply -> {
                OtaState.noteSuccess(app, now)
                offer(app, client, step.manifest, now, unavailable)
            }
        }
    }

    private fun forgetStaleConsent(app: Context, stillOffered: String) {
        val offered = OtaState.offeredBuild(app)
        if (offered.isNotBlank() && offered != stillOffered) {
            OtaState.clearOffer(app)
            OtaConsent.cancelOfferNotification(app)
            Log.i(TAG, "offer for '" + offered + "' withdrawn; cleared")
        }
        val approved = OtaState.approvedBuild(app)
        if (approved.isNotBlank() && approved != stillOffered) {
            OtaState.clearApprovals(app)
            Log.i(TAG, "approval for '" + approved + "' no longer offered; cleared")
        }
    }

    internal fun offer(
        app: Context,
        client: okhttp3.OkHttpClient,
        m: OtaManifest,
        now: Long,
        unavailable: OtaEngine.Availability.Unavailable?,
    ): Long {
        if (m.build == OtaState.readyBuild(app)) {
            OtaState.recordCheck(app, now, "${m.build} is staged; restart to finish updating")
            return POLL_INTERVAL_SECONDS
        }
        // Must stay above the consent gate: this branch can only return, never start a download.
        val applying = OtaState.applyingBuild(app)
        val recordedForThisBuild = applying == m.build
        if (recordedForThisBuild) {
            val since = OtaState.applyingSinceSeconds(app)
            // now >= since guards a backward clock step.
            val fresh = since > 0L && now >= since && now - since <= OtaState.APPLYING_STALE_SECONDS
            if (fresh) {
                OtaState.recordCheck(app, now, "installing ${m.build}")
                return POLL_INTERVAL_SECONDS
            }
            Log.w(TAG, "apply of ${m.build} claims to be in flight since $since; too old to " +
                "believe, handing off again")
        }

        if (m.build == OtaState.refusedBuild(app)) {
            OtaState.recordCheck(app, now, "${m.build} failed permanently; not retrying")
            return POLL_INTERVAL_SECONDS
        }
        if (unavailable != null) {
            OtaState.recordCheck(app, now,
                "${m.build} available; cannot apply (${unavailable.reason})")
            return UNAVAILABLE_INTERVAL_SECONDS
        }

        // Consent gate: after the staged/refused/unavailable checks, before the preflight. Network
        // suitability is read here at hand-off time, never from the stored approval.
        forgetStaleConsent(app, stillOffered = m.build)

        val net = OtaNetwork.current(app)
        val gate = OtaConsent.decide(
            build = m.build,
            approvedBuild = OtaState.approvedBuild(app),
            meteredApprovedBuild = OtaState.meteredApprovedBuild(app),
            net = net,
        )
        if (gate is OtaConsent.Gate.Wait) {
            OtaState.recordOffer(app, m.build, m.payloadSize)
            OtaConsent.cancelOfferNotification(app)
            OtaState.recordCheck(app, now,
                "${m.build} available (${OtaConsent.formatBytes(m.payloadSize)})")
            Log.i(TAG, "holding ${m.build}: ${gate.reason} on ${OtaNetwork.describe(net)}")
            return POLL_INTERVAL_SECONDS
        }

        // update_engine treats any 4xx/5xx as terminal and never reads Retry-After
        // (libcurl_http_fetcher.cc), so the URL must be preflighted immediately before hand-off.
        return when (val p = OtaCheck.preflight(client, m.url, m.payloadOffset, m.payloadSize, now * 1000L)) {
            is OtaCheck.Preflight.Ready -> {
                Log.i(TAG, "handing ${m.build} to update_engine (zip=${p.zipSize})")
                OtaConsent.cancelOfferNotification(app)
                OtaState.recordCheck(app, now, "applying ${m.build}")
                // Set before the hand-off; cleared by OtaResultReceiver on every verdict and by a restart.
                OtaState.setApplyingBuild(app, m.build, now)
                OtaService.start(
                    app, OtaApply.handoff(m, attachIfRunning = recordedForThisBuild), m.build)
                // Not shorter: a poll mid-apply would re-hand-off and update_engine rejects it (65).
                POLL_INTERVAL_SECONDS
            }
            is OtaCheck.Preflight.BackOff -> {
                OtaState.setNotBeforeSeconds(app, now + p.seconds)
                OtaState.recordCheck(app, now, "package busy; retrying in ${p.seconds}s")
                p.seconds
            }
            OtaCheck.Preflight.Missing -> {
                forgetStaleConsent(app, stillOffered = "")
                OtaState.recordCheck(app, now, "package missing: half-finished publish")
                POLL_INTERVAL_SECONDS
            }
            is OtaCheck.Preflight.RangeUnsupported -> {
                forgetStaleConsent(app, stillOffered = "")
                OtaState.recordCheck(app, now, "host does not honour Range: ${p.detail}")
                POLL_INTERVAL_SECONDS
            }
            OtaCheck.Preflight.WrongOffset -> {
                forgetStaleConsent(app, stillOffered = "")
                OtaState.recordCheck(app, now, "payload_offset does not point at a payload")
                POLL_INTERVAL_SECONDS
            }
            is OtaCheck.Preflight.Transient -> {
                OtaState.noteFailure(app)
                OtaState.recordCheck(app, now, "preflight failed: ${p.detail}")
                OtaRetry.backoffSeconds(OtaState.failures(app), null, Math.random())
            }
        }
    }
}

class OtaAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val action = intent.action.orEmpty()
        val wake = OtaScheduler.wakeFor(action)
        if (wake == OtaScheduler.Wake.IGNORE) {
            // Before goAsync: an ignored delivery must cost no wakelock, thread or network.
            Log.w("RistOta", "ignoring an OTA wake for '$action'")
            return
        }
        val pending = goAsync()
        Thread {
            try {
                when (wake) {
                    OtaScheduler.Wake.RESCHEDULE -> OtaScheduler.rescheduleAfterRestart(app)
                    OtaScheduler.Wake.POLL -> OtaScheduler.runCheck(app)
                    OtaScheduler.Wake.IGNORE -> {}
                }
            } catch (t: Throwable) {
                Log.w("RistOta", "OTA wake failed for '$action'", t)
            } finally {
                pending.finish()
            }
        }.apply { name = "rist-ota" }.start()
    }
}

/** Runs in the main process: SharedPreferences is not multi-process safe, so `:ota` reports here by Intent. */
class OtaResultReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        if (intent.action == OtaService.ACTION_PROGRESS) {
            OtaState.setApplyingProgressFor(
                app,
                intent.getStringExtra(OtaService.EXTRA_BUILD).orEmpty(),
                intent.getIntExtra(OtaService.EXTRA_STATUS, OtaApply.Status.IDLE),
                intent.getIntExtra(OtaService.EXTRA_PERCENT, 0),
            )
            return
        }
        if (intent.action != OtaService.ACTION_RESULT) return
        val now = OtaScheduler.trustedNowSeconds()
        val build = intent.getStringExtra(OtaService.EXTRA_BUILD).orEmpty()
        val verdict = intent.getStringExtra(OtaService.EXTRA_VERDICT).orEmpty()
        val detail = intent.getStringExtra(OtaService.EXTRA_DETAIL).orEmpty()

        Log.i("RistOta", "apply of '$build' finished: $verdict ($detail)")
        OtaState.clearApplyingBuildFor(app, build)
        when (verdict) {
            OtaService.VERDICT_APPLIED -> {
                OtaState.setReadyBuild(app, build)
                OtaState.noteSuccess(app, now)
                OtaState.clearApprovals(app)
                OtaState.clearOffer(app)
                OtaConsent.cancelOfferNotification(app)
                OtaState.recordCheck(app, now, "$build installed; restart to finish updating")
            }
            OtaService.VERDICT_PERMANENT -> {
                OtaState.setRefusedBuild(app, build)
                OtaState.clearApprovals(app)
                OtaState.clearOffer(app)
                OtaConsent.cancelOfferNotification(app)
                OtaState.recordCheck(app, now, "$build refused permanently: $detail")
            }
            OtaService.VERDICT_RETRY -> {
                OtaState.noteFailure(app)
                OtaState.recordCheck(app, now, "$build failed: $detail")
            }
            OtaService.VERDICT_NEEDS_USER, OtaService.VERDICT_CORRUPTED -> {
                OtaState.noteFailure(app)
                OtaState.clearApprovals(app)
                OtaState.clearOffer(app)
                OtaConsent.cancelOfferNotification(app)
                OtaState.recordCheck(app, now, "$build could not be applied: $verdict $detail")
            }
            else -> OtaState.recordCheck(app, now, "$build: $verdict $detail")
        }
        OtaScheduler.reschedule(app)
    }
}
