package watch.rist.assistant

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import rist.v1.WakeSignal
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The wake channel: a GET the backend holds open and answers the moment it has something, so mail
 * reaches the phone within seconds instead of on the next turn.
 *
 * The held connection (`wake_hold_v2`): the backend holds for up to 25 minutes and an idle answer
 * says "ask again now", so there is no gap in which the phone cannot hear anything, and one radio
 * wake-up per event or per hold. A backend that does not know it holds for 55 s and says wait
 * 240 s, which this loop follows as before. `next_due_at_epoch_ms` sets an exact alarm for the next
 * scheduled instruction ([DueAlarm]), which drops the hold and polls on a fresh connection.
 *
 * The phone still opens every exchange; nothing can be sent to it. A notification here is inert:
 * it is stored, shown on the feed and may buzz, and it never speaks, never opens a turn and never
 * acts. Acks go out on the next poll and only for ids already written to disk, as on the turn.
 */
object WakeLoop {

    private const val TAG = "RistWake"

    /** The legacy backend holds for 55s; anything shorter aborts every normal hold. */
    internal const val READ_TIMEOUT_S = 90L

    /** Declared on every poll: hold me until there is something to say (backend v27). */
    internal const val HOLD_V2_COMPONENT = "wake_hold_v2"
    /** The hold asked for, at most. The backend caps it too. */
    internal const val HOLD_MAX_S = 1500L
    /** Never asked for less: below this a hold costs as much radio as the legacy cycle. */
    internal const val HOLD_MIN_S = 240L
    /** Read timeout beyond the hold asked for, before the hold counts as silently dropped. */
    internal const val HOLD_GRACE_S = 45L
    /** Holds that ran their full length before the hold asked for grows again. */
    internal const val HOLD_GROW_AFTER = 3
    /** The CPU stays awake this long after an answer, so the next poll goes out before sleep. */
    internal const val WAKELOCK_MS = 10_000L

    /**
     * The hold to ask for. A NAT or firewall that forgets idle connections loses an answer without
     * any error; the read then times out after the hold. Each such drop halves the hold asked
     * for (not below [HOLD_MIN_S]); [HOLD_GROW_AFTER] holds that ran their full length grow it by
     * half again, up to [HOLD_MAX_S].
     */
    internal class HoldTuner(var holdS: Long = HOLD_MAX_S) {
        private var fullHolds = 0
        fun onSilentDrop() { holdS = maxOf(HOLD_MIN_S, holdS / 2); fullHolds = 0 }
        fun onAnswered(elapsedMs: Long) {
            if (elapsedMs < holdS * 900) return            // answered early: says nothing about drops
            fullHolds++
            if (fullHolds >= HOLD_GROW_AFTER) {
                holdS = minOf(HOLD_MAX_S, holdS * 3 / 2)
                fullHolds = 0
            }
        }
    }

    internal val tuner = HoldTuner()

    /** A read that timed out after at least the hold asked for: dropped silently on the way. */
    internal fun isSilentDrop(elapsedMs: Long, holdS: Long): Boolean = elapsedMs >= holdS * 1000
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
        /** 403 with the revoked header: revoked. Asked again only every [REVOKED_RECHECK_MS]. */
        object Revoked : Outcome()
        /** 403 without it: refused for now, not revoked. Poll again much later. */
        object Refused : Outcome()
        /** 402: the subscription lapsed. The token is fine; nothing is cleared. */
        data class Lapsed(val lapse: Billing.Lapse) : Outcome()
        /**
         * 503 or a transport failure: back off and try again. [silent]: the read timed out, so
         * the connection was dropped somewhere without a word; ask again at once, for less.
         */
        data class Retry(val why: String, val silent: Boolean = false) : Outcome()
        /** No token, or no backend address: nothing to poll with yet. */
        object NotReady : Outcome()
    }

    /**
     * `…/v1/device` becomes `…/v1/device/wake`, carrying the acks and the real card count.
     * [boxesVersion]: the box list version held, sent only by a phone that declares boxes.
     * [designVersions]: the design and settings versions held, sent only by a phone that takes a
     * design. Every declared component goes in one comma-separated `components`.
     */
    internal fun wakeUrl(
        backendUrl: String,
        acks: List<String>,
        maxNotifications: Int,
        boxesVersion: Long? = null,
        designVersions: Pair<Long, Long>? = null,
        checklists: Boolean = false,
        holdS: Long? = null,
    ): String? {
        val base = backendUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val wake = if (base.endsWith("/v1/device")) "$base/wake" else "$base/v1/device/wake"
        val url = wake.toHttpUrlOrNull() ?: return null
        return url.newBuilder()
            .apply { if (acks.isNotEmpty()) addQueryParameter("ack", acks.joinToString(",")) }
            // Always explicit: absent, the wake endpoint picks 5, not the 8 this phone shows.
            .addQueryParameter("max_notifications", maxNotifications.toString())
            .apply {
                val components = mutableListOf<String>()
                if (boxesVersion != null) {
                    addQueryParameter("boxes", boxesVersion.toString())
                    components += HomeBoxes.COMPONENT
                }
                if (designVersions != null) {
                    addQueryParameter("design", designVersions.first.toString())
                    addQueryParameter("settings", designVersions.second.toString())
                    components += DesignSync.COMPONENT
                }
                if (checklists) components += Checklists.COMPONENT
                if (holdS != null) {
                    addQueryParameter("hold_s", holdS.toString())
                    components += HOLD_V2_COMPONENT
                }
                if (components.isNotEmpty()) addQueryParameter("components", components.joinToString(","))
            }
            .build().toString()
    }

    /** With no network at all, a retry cannot succeed; wait this long, or for the network to come back. */
    internal const val OFFLINE_RETRY_MS = 15L * 60 * 1000

    /**
     * The wait before a retry. Offline, retrying every minute only wakes the phone to fail: the
     * network callback in [WakeService] kicks the loop the moment a network appears, so the wait
     * is long. [hasNetwork] null (unknown) keeps the backoff.
     */
    internal fun retryWaitMs(backoffMs: Long, hasNetwork: Boolean?): Long =
        if (hasNetwork == false) maxOf(backoffMs, OFFLINE_RETRY_MS) else backoffMs

    /** Null unless a network callback is registered to end the long offline wait early. */
    private fun hasNetwork(ctx: Context): Boolean? {
        if (!WakeService.networkWatched) return null
        return runCatching {
            ctx.getSystemService(ConnectivityManager::class.java)?.let { it.activeNetwork != null }
        }.getOrNull()
    }

    /** Exponential, 1s to 60s, with jitter so a fleet does not come back in step. */
    internal fun nextBackoff(current: Long, random: Random = Random): Long {
        val doubled = (current * 2).coerceIn(BACKOFF_MIN_MS, BACKOFF_MAX_MS)
        return (doubled / 2 + random.nextLong(doubled / 2 + 1)).coerceIn(BACKOFF_MIN_MS, BACKOFF_MAX_MS)
    }

    /** Its own pool, so a due alarm can drop a possibly dead connection without touching turns. */
    private val pool = ConnectionPool(1, 5, TimeUnit.MINUTES)

    private val client: OkHttpClient by lazy {
        Uploader.sharedClient().newBuilder()
            .connectionPool(pool)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** The read timeout for a hold of [holdS]: the hold plus a grace, never below the legacy one. */
    internal fun readTimeoutS(holdS: Long): Long = maxOf(READ_TIMEOUT_S, holdS + HOLD_GRACE_S)

    @Volatile private var inFlight: Call? = null
    @Volatile private var refreshing = false

    /**
     * Poll now on a fresh connection: a scheduled instruction is due ([DueAlarm]). The current
     * hold may have died without a word, so it is dropped rather than trusted.
     */
    fun refreshNow(ctx: Context) {
        stayAwake(ctx)
        refreshing = true
        inFlight?.cancel()
        runCatching { pool.evictAll() }
        kick()
    }

    /** Whether a failed poll was the one [refreshNow] dropped; clears the mark. */
    internal fun takeRefresh(): Boolean { val r = refreshing; refreshing = false; return r }

    private var wakeLock: PowerManager.WakeLock? = null

    /** Keeps the CPU up for [WAKELOCK_MS], so deep sleep cannot strand the loop between polls. */
    @Synchronized
    internal fun stayAwake(ctx: Context) {
        runCatching {
            val lock = wakeLock ?: (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rist:wake").apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire(WAKELOCK_MS)
        }.onFailure { Log.w(TAG, "wakelock failed", it) }
    }

    /** One held poll. Blocking; call off the main thread. */
    internal fun poll(ctx: Context, http: OkHttpClient = client): Outcome {
        val bearer = Uploader.bearer(ctx) ?: return Outcome.NotReady
        val acks = NotificationQueue.pendingAcks(ctx)
        // Box edits made offline go first, so the version asked about is the one they produced.
        if (HomeBoxes.declared()) runCatching { HomeBoxes.flush(ctx) }
        if (Checklists.declared()) runCatching { Checklists.flush(ctx) }
        if (NoteEdits.declared()) runCatching { NoteEdits.flush(ctx) }
        if (DesignSync.declared()) {
            DesignSync.migrateLegacyTheme(ctx)
            runCatching { DesignSync.flush(ctx) }
        }
        val hold = tuner.holdS
        val url = wakeUrlFor(ctx, acks, hold) ?: return Outcome.NotReady
        val held = if (http === client) {
            http.newBuilder().readTimeout(readTimeoutS(hold), TimeUnit.SECONDS).build()
        } else http
        return exchange(held, url, bearer, Config.deviceId(ctx), acks)
    }

    /** This phone's wake address: its acks, its card count, and its box version if it has boxes. */
    internal fun wakeUrlFor(ctx: Context, acks: List<String>, holdS: Long? = null): String? =
        wakeUrl(Config.backendUrl(ctx), acks, CommsFeed.MAX_NOTIFICATIONS,
            if (HomeBoxes.declared()) Checklists.boxesVersionToAsk(ctx, HomeBoxes.version(ctx)) else null,
            if (DesignSync.declared()) DesignSync.version(ctx) to Config.settingsVersion(ctx) else null,
            Checklists.declared(), holdS)

    /** The HTTP half of [poll], apart from the stores so it can be tested on its own. */
    internal fun exchange(http: OkHttpClient, url: String, bearer: String, device: String, acks: List<String>): Outcome {
        val request = Request.Builder().url(url).get()
            .header("Accept", StreamingWire.UNARY_MEDIA_TYPE)
            .header("Authorization", bearer)
            .header("X-Rist-Device", device)
            .build()
        val call = http.newCall(request)
        inFlight = call
        return try {
            call.execute().use { resp ->
                when (resp.code) {
                    200 -> Outcome.Signal(WakeSignal.parseFrom(resp.body?.bytes() ?: ByteArray(0)), acks)
                    401 -> Outcome.Unauthorised
                    403 -> if (Enrolment.isExplicitRevocation(403, resp.header(Enrolment.REVOKED_HEADER))) {
                        Outcome.Revoked
                    } else {
                        Outcome.Refused
                    }
                    Billing.PAYMENT_REQUIRED -> Outcome.Lapsed(Billing.lapseWithLine(resp))
                    // A newer poll from this phone replaced this hold (the server read nothing).
                    204 -> Outcome.Retry("HTTP 204 superseded")
                    else -> Outcome.Retry("HTTP ${resp.code}")
                }
            }
        } catch (t: java.net.SocketTimeoutException) {
            Outcome.Retry(t.javaClass.simpleName, silent = true)
        } catch (t: Throwable) {
            Outcome.Retry(t.javaClass.simpleName)
        } finally {
            inFlight = null
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
        if (signal.hasBoxes()) runCatching { HomeBoxes.apply(ctx, signal.boxes) }
        if (signal.hasDesign()) runCatching { DesignSync.apply(ctx, signal.design) }
        if (signal.hasSettings() && DesignSync.declared()) runCatching { SettingsApply.handle(ctx, signal.settings) }
        runCatching { DueAlarm.set(ctx, signal.nextDueAtEpochMs) }
        runCatching { ContactsSync.onCursor(ctx, signal.contactsCursor) }
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

    /** The whole client loop. Runs until its coroutine is cancelled. */
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
            val started = SystemClock.elapsedRealtime()
            val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { poll(ctx) }
            kicks.tryReceive()
            // Awake until the next poll is on the wire: an answer can arrive in deep sleep.
            stayAwake(ctx)
            val elapsed = SystemClock.elapsedRealtime() - started
            when (out) {
                is Outcome.Signal -> {
                    takeRefresh()
                    tuner.onAnswered(elapsed)
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
                Outcome.Refused -> {
                    Log.w(TAG, "403 with no revocation signal; waiting before the next poll")
                    waitOrKick(NO_TOKEN_RECHECK_MS)
                }
                is Outcome.Retry -> {
                    when {
                        // Dropped by a due alarm, or replaced by a newer poll: ask again now.
                        takeRefresh() || out.why.endsWith("superseded") -> {
                            Log.i(TAG, "polling again now (${out.why})")
                        }
                        // The hold died without a word: the network is fine, the mapping was not.
                        // Only a timeout after the whole hold: a connect timeout is a bad network.
                        out.silent && isSilentDrop(elapsed, tuner.holdS) -> {
                            tuner.onSilentDrop()
                            Log.i(TAG, "hold dropped silently; asking for ${tuner.holdS}s now")
                        }
                        else -> {
                            val wait = retryWaitMs(backoff, hasNetwork(ctx))
                            Log.i(TAG, "retry in ${wait}ms (${out.why})")
                            waitOrKick(wait)
                            backoff = nextBackoff(backoff)
                        }
                    }
                }
                Outcome.NotReady -> waitOrKick(NO_TOKEN_RECHECK_MS)
            }
        }
    }
}
