package watch.rist.assistant

import android.content.Context
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import rist.v1.WakeSignal
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The wake channel: a GET the backend holds for up to 55 seconds and
 * answers the moment it has something, so mail reaches the phone within seconds instead of on
 * the next turn.
 *
 * The phone still opens every exchange; nothing can be sent to it. A notification here is inert:
 * it is stored, shown on the feed and may buzz, and it never speaks, never opens a turn and never
 * acts. Acks go out on the next poll and only for ids already written to disk, as on the turn.
 */
object WakeLoop {

    private const val TAG = "RistWake"

    /** The backend holds for 55s; anything shorter aborts every normal hold. */
    internal const val READ_TIMEOUT_S = 90L
    internal const val BACKOFF_MIN_MS = 1_000L
    internal const val BACKOFF_MAX_MS = 60_000L
    /** How often to look again when there is no token to poll with. */
    internal const val NO_TOKEN_RECHECK_MS = 5L * 60 * 1000
    /** A revoked token is asked again this rarely, so a reinstatement is noticed without a reset. */
    internal const val REVOKED_RECHECK_MS = 60L * 60 * 1000
    /** A 402 here is not in the contract; if one comes, ask again at this pace until it stops. */
    internal const val LAPSED_RECHECK_MS = 5L * 60 * 1000

    /** Floor and ceiling on the server's poll_after_s: 0 must not become a tight loop. */
    internal const val POLL_GAP_MIN_MS = 1_000L
    internal const val POLL_GAP_MAX_MS = 60L * 60 * 1000

    /** poll_after_s is a uint32, which arrives as a signed Int; read it unsigned, then bound it. */
    internal fun pollGapMs(pollAfterS: Int): Long =
        ((pollAfterS.toLong() and 0xFFFF_FFFFL) * 1000).coerceIn(POLL_GAP_MIN_MS, POLL_GAP_MAX_MS)

    sealed class Outcome {
        data class Signal(val signal: WakeSignal, val acked: List<String>) : Outcome()
        /** 401: the credential is dead. Enrolment clears it; the loop waits for a new one. */
        object Unauthorised : Outcome()
        /** 403: revoked. Asked again only every [REVOKED_RECHECK_MS]. */
        object Revoked : Outcome()
        /** 402: the subscription lapsed. The token is fine; nothing is cleared. */
        data class Lapsed(val lapse: Billing.Lapse) : Outcome()
        /** 503 or a transport failure: back off and try again. */
        data class Retry(val why: String) : Outcome()
        /** No token, or no backend address: nothing to poll with yet. */
        object NotReady : Outcome()
    }

    /** `…/v1/device` becomes `…/v1/device/wake`, carrying the acks and the real card count. */
    internal fun wakeUrl(backendUrl: String, acks: List<String>, maxNotifications: Int): String? {
        val base = backendUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val wake = if (base.endsWith("/v1/device")) "$base/wake" else "$base/v1/device/wake"
        val url = wake.toHttpUrlOrNull() ?: return null
        return url.newBuilder()
            .apply { if (acks.isNotEmpty()) addQueryParameter("ack", acks.joinToString(",")) }
            // Always explicit: absent, the wake endpoint picks 5, not the 8 this phone shows.
            .addQueryParameter("max_notifications", maxNotifications.toString())
            .build().toString()
    }

    /** Exponential, 1s to 60s, with jitter so a fleet does not come back in step. */
    internal fun nextBackoff(current: Long, random: Random = Random): Long {
        val doubled = (current * 2).coerceIn(BACKOFF_MIN_MS, BACKOFF_MAX_MS)
        return (doubled / 2 + random.nextLong(doubled / 2 + 1)).coerceIn(BACKOFF_MIN_MS, BACKOFF_MAX_MS)
    }

    private val client: OkHttpClient by lazy {
        Uploader.sharedClient().newBuilder()
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** One held poll. Blocking; call off the main thread. */
    internal fun poll(ctx: Context, http: OkHttpClient = client): Outcome {
        val bearer = Uploader.bearer(ctx) ?: return Outcome.NotReady
        val acks = NotificationQueue.pendingAcks(ctx)
        val url = wakeUrl(Config.backendUrl(ctx), acks, CommsFeed.MAX_NOTIFICATIONS) ?: return Outcome.NotReady
        return exchange(http, url, bearer, Config.deviceId(ctx), acks)
    }

    /** The HTTP half of [poll], apart from the stores so it can be tested on its own. */
    internal fun exchange(http: OkHttpClient, url: String, bearer: String, device: String, acks: List<String>): Outcome {
        val request = Request.Builder().url(url).get()
            .header("Accept", StreamingWire.UNARY_MEDIA_TYPE)
            .header("Authorization", bearer)
            .header("X-Rist-Device", device)
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> Outcome.Signal(WakeSignal.parseFrom(resp.body?.bytes() ?: ByteArray(0)), acks)
                    401 -> Outcome.Unauthorised
                    403 -> Outcome.Revoked
                    Billing.PAYMENT_REQUIRED -> Outcome.Lapsed(Billing.lapseFrom(resp))
                    else -> Outcome.Retry("HTTP ${resp.code}")
                }
            }
        } catch (t: Throwable) {
            Outcome.Retry(t.javaClass.simpleName)
        }
    }

    /**
     * Takes a signal in exactly as a turn's reply is taken in: the acks it carried are cleared,
     * new notifications are stored (upsert on id), badges are set, including to zero.
     */
    internal fun apply(ctx: Context, signal: WakeSignal, acked: List<String>) {
        if (acked.isNotEmpty()) NotificationQueue.markAcked(ctx, acked)
        val fresh = NotificationQueue.unheldIds(ctx, signal.notificationsList)
        if (signal.notificationsCount > 0) NotificationQueue.store(ctx, signal.notificationsList)
        NotificationQueue.setMailUnread(ctx, signal.mailUnread)
        if (signal.hasFeatures()) runCatching { Features.apply(ctx, signal.features) }
        if (Config.voicemailCount(ctx) != signal.voicemailUnheard) {
            Config.setVoicemailCount(ctx, signal.voicemailUnheard)
            NotificationQueue.countsChanged(ctx)
        }
        // A redelivery is our lost ack, not news: only a notification the phone did not hold buzzes.
        val buzzes = signal.notificationsList.any { it.id.trim() in fresh && interrupts(it.urgency) }
        if (buzzes) buzz(ctx)
        Log.i(TAG, "signal: ${signal.notificationsCount} notification(s), ${fresh.size} new, " +
            "pending=${signal.pending}, acked=${acked.size}, next in ${signal.pollAfterS}s")
    }

    /** "passive" never interrupts; every other tier does, with a short buzz and nothing spoken. */
    internal fun interrupts(urgency: String): Boolean = urgency.trim().lowercase() != "passive"

    private fun buzz(ctx: Context) {
        if (!Config.isHapticsEnabled(ctx)) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am?.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        runCatching {
            val v = (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        }
    }

    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /** Poll now rather than after the current wait: the network came back, or the token did. */
    fun kick() { kicks.trySend(Unit) }

    private suspend fun waitOrKick(ms: Long) {
        if (ms <= 0) return
        withTimeoutOrNull(ms) { kicks.receive() }
    }

    /** Whether the loop sits out this token: it is the one last refused, and the refusal still holds. */
    internal fun sitsOut(token: String?, refusedToken: String?, refusedUntilMs: Long, nowMs: Long): Boolean =
        token != null && token == refusedToken && nowMs < refusedUntilMs

    /** When a token refused with 403 at [nowMs] is asked again. */
    internal fun revokedUntil(nowMs: Long): Long = nowMs + REVOKED_RECHECK_MS

    /** The whole client (§3 "Your loop"). Runs until its coroutine is cancelled. */
    suspend fun run(ctx: Context) {
        var backoff = BACKOFF_MIN_MS
        var refusedToken: String? = null
        var refusedUntil = Long.MAX_VALUE
        while (true) {
            val token = Uploader.bearer(ctx)
            val now = android.os.SystemClock.elapsedRealtime()
            if (sitsOut(token, refusedToken, refusedUntil, now)) {
                // Short waits, so a new token (a re-pair) is polled with soon; a kick only re-checks.
                waitOrKick(minOf(NO_TOKEN_RECHECK_MS, refusedUntil - now))
                continue
            }
            // A kick asks for a poll, and this is it. Left queued, it would cut short the wait
            // after this poll: registering the network callback reports the current network at
            // once, which on the phone turned the idle 240s into back-to-back holds.
            // Kicks during the poll are dropped too: the poll that answered is fresher than they
            // are, and a network lost mid-poll fails it, which retries in a second anyway.
            kicks.tryReceive()
            val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { poll(ctx) }
            kicks.tryReceive()
            when (out) {
                is Outcome.Signal -> {
                    refusedToken = null
                    runCatching { Enrolment.onReinstated(ctx) }
                    runCatching { apply(ctx, out.signal, out.acked) }
                        .onFailure { Log.w(TAG, "could not take a signal in", it) }
                    backoff = BACKOFF_MIN_MS
                    // Ours to set and we honour it (0 while draining, 240 when idle), but never
                    // quicker than a second: an empty 200 parses as 0 and would spin.
                    waitOrKick(pollGapMs(out.signal.pollAfterS))
                }
                Outcome.Unauthorised -> {
                    Log.w(TAG, "401: stopping until the phone has a new credential")
                    runCatching { Enrolment.onCredentialDead(ctx) }
                    refusedToken = token
                    refusedUntil = Long.MAX_VALUE
                }
                Outcome.Revoked -> {
                    Log.w(TAG, "403: this device is revoked; asking again in ${REVOKED_RECHECK_MS / 60_000} min")
                    runCatching { Enrolment.onRevoked(ctx) }
                    backoff = BACKOFF_MIN_MS
                    // Refuse this token for the hour rather than sleeping it: a kick must not
                    // re-ask a revoked token, and a new one from a re-pair must not wait the hour.
                    refusedToken = token
                    refusedUntil = revokedUntil(android.os.SystemClock.elapsedRealtime())
                }
                is Outcome.Lapsed -> {
                    Log.w(TAG, "402: subscription ${out.lapse.reason}; keeping the token, asking again later")
                    runCatching { Billing.onLapsed(ctx, out.lapse) }
                    backoff = BACKOFF_MIN_MS
                    waitOrKick(LAPSED_RECHECK_MS)
                }
                is Outcome.Retry -> {
                    Log.i(TAG, "retry in ${backoff}ms (${out.why})")
                    waitOrKick(backoff)
                    backoff = nextBackoff(backoff)
                }
                Outcome.NotReady -> waitOrKick(NO_TOKEN_RECHECK_MS)
            }
        }
    }
}
