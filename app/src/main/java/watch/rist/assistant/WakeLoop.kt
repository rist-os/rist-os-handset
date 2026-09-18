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
 * The wake channel (push_notifications.md §3): a GET the backend holds for up to 55 seconds and
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

    sealed class Outcome {
        data class Signal(val signal: WakeSignal, val acked: List<String>) : Outcome()
        /** 401: the credential is dead. Enrolment clears it; the loop waits for a new one. */
        object Unauthorised : Outcome()
        /** 403: revoked. Never poll again on this token. */
        object Revoked : Outcome()
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
        if (Config.enrolRevoked(ctx)) return Outcome.Revoked
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

    /** The whole client (§3 "Your loop"). Runs until its coroutine is cancelled. */
    suspend fun run(ctx: Context) {
        var backoff = BACKOFF_MIN_MS
        var refusedToken: String? = null
        while (true) {
            val token = Uploader.bearer(ctx)
            if (token != null && token == refusedToken) {
                waitOrKick(NO_TOKEN_RECHECK_MS)
                continue
            }
            // A kick asks for a poll, and this is it. Left queued, it would cut short the wait
            // after this poll: registering the network callback reports the current network at
            // once, which on the phone turned the idle 240s into back-to-back holds.
            kicks.tryReceive()
            when (val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { poll(ctx) }) {
                is Outcome.Signal -> {
                    runCatching { apply(ctx, out.signal, out.acked) }
                        .onFailure { Log.w(TAG, "could not take a signal in", it) }
                    backoff = BACKOFF_MIN_MS
                    // Ours to set and we honour it: 0 while draining, 240 when idle.
                    waitOrKick(out.signal.pollAfterS.toLong() * 1000)
                }
                Outcome.Unauthorised -> {
                    Log.w(TAG, "401: stopping until the phone has a new credential")
                    runCatching { Enrolment.onCredentialDead(ctx) }
                    refusedToken = token
                }
                Outcome.Revoked -> {
                    Log.w(TAG, "403: this device is revoked; not polling")
                    runCatching { Enrolment.onRevoked(ctx) }
                    refusedToken = token
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
