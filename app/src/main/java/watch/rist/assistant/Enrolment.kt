package watch.rist.assistant

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

object Enrolment {

    private const val TAG = "RistEnrol"

    private fun target(ctx: Context) = Config.ristNumber(ctx)

    // Matches the backend's 10-minute nonce TTL.
    private const val NONCE_TTL_MS = 10L * 60L * 1000L

    private const val POLL_INTERVAL_MS = 3_000L
    private const val POLL_MAX_ATTEMPTS = 20

    // The first few texts go as fast as the nonce allows; after that each wait doubles, up to a day.
    internal const val QUICK_ATTEMPTS = 3
    internal const val BACKOFF_BASE_MS = 30L * 60L * 1000L
    internal const val BACKOFF_MAX_MS = 24L * 60L * 60L * 1000L

    enum class Readiness { READY, NO_SIM, NO_SERVICE, NOT_CONFIGURED, BACKING_OFF, ALREADY_ENROLLED }

    fun needed(ctx: Context): Boolean = Config.authToken(ctx).isBlank()

    /** A revoked device holds a token that no longer works, so it pairs again like a new one. */
    fun canPair(ctx: Context): Boolean = needed(ctx) || Config.enrolRevoked(ctx)

    internal fun retryDelayMs(attempts: Int): Long {
        if (attempts < QUICK_ATTEMPTS) return 0L
        val doublings = (attempts - QUICK_ATTEMPTS).coerceAtMost(16)
        return (BACKOFF_BASE_MS shl doublings).coerceAtMost(BACKOFF_MAX_MS)
    }

    internal fun nextAttemptAtMs(attempts: Int, lastSentAtMs: Long): Long =
        if (attempts < QUICK_ATTEMPTS) 0L else lastSentAtMs + retryDelayMs(attempts)

    fun newNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun readiness(ctx: Context): Readiness {
        if (!canPair(ctx)) return Readiness.ALREADY_ENROLLED
        if (target(ctx).isBlank()) return Readiness.NOT_CONFIGURED
        val now = System.currentTimeMillis()
        // A send stamped in the future (the clock was wrong then) must not hold the phone off for years.
        val sentAt = Config.enrolSentAtMs(ctx).let { if (it > now) 0L else it }
        if (now < nextAttemptAtMs(Config.enrolAttempts(ctx), sentAt)) {
            return Readiness.BACKING_OFF
        }
        val tm = runCatching { ctx.getSystemService(TelephonyManager::class.java) }.getOrNull()
            ?: return Readiness.NO_SIM
        return when (tm.simState) {
            TelephonyManager.SIM_STATE_READY -> Readiness.READY
            TelephonyManager.SIM_STATE_ABSENT, TelephonyManager.SIM_STATE_UNKNOWN -> Readiness.NO_SIM
            else -> Readiness.NO_SERVICE
        }
    }

    fun explain(r: Readiness): String = when (r) {
        Readiness.NO_SIM ->
            "Rist needs a SIM to set up. This device has no SIM card, so it can't enroll on its own."
        Readiness.NO_SERVICE ->
            "Waiting for mobile service. Rist needs to send one text to finish setting up."
        Readiness.NOT_CONFIGURED ->
            "Enter the enrolment number your assistant service gave you."
        Readiness.BACKING_OFF ->
            "Setup hasn't finished yet. This phone will try again on its own a little later."
        Readiness.ALREADY_ENROLLED -> "Already set up."
        Readiness.READY -> "Setting up…"
    }

    fun begin(ctx: Context): String? {
        val r = readiness(ctx)
        if (r != Readiness.READY) {
            Log.i(TAG, "not starting enrolment: $r")
            return null
        }
        val now = System.currentTimeMillis()
        val live = Config.enrolNonce(ctx).takeIf { it.isNotBlank() }
        if (live != null && now - Config.enrolSentAtMs(ctx) < NONCE_TTL_MS) {
            Log.i(TAG, "nonce still live; not resending")
            return live
        }
        val nonce = newNonce()
        // Wire body; backend parses ^\s*rist\s+enroll?\s+([A-Za-z0-9_-]{22,64})\s*$ case-insensitively.
        // The anchors are hard: the message must contain nothing else.
        val body = "Rist enroll $nonce"
        val sent = runCatching {
            val sm = ctx.getSystemService(android.telephony.SmsManager::class.java)
            sm.sendTextMessage(target(ctx), null, body, null, null)
            true
        }.onFailure { Log.w(TAG, "enrolment text failed to send", it) }.getOrDefault(false)
        if (!sent) return null

        Config.setEnrolNonce(ctx, nonce)
        Config.setEnrolSentAtMs(ctx, now)
        Config.setEnrolAttempts(ctx, Config.enrolAttempts(ctx) + 1)
        Log.i(TAG, "enrolment text sent (nonce ${nonce.length} chars, attempt ${Config.enrolAttempts(ctx)})")
        return nonce
    }

    /**
     * The POST /v1/enroll body. `stable_id` is optional: absent when the serial cannot be read. The
     * device_id is unchanged so an enrolled phone's token stays bound to the id it was issued for.
     */
    internal fun enrolPayload(ctx: Context, nonce: String, stableId: String? = StableId.read()): String =
        JSONObject().apply {
            put("nonce", nonce)
            put("device_id", Config.deviceId(ctx))
            put("label", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            if (!stableId.isNullOrBlank()) put("stable_id", stableId)
        }.toString()

    /** What the server said when it refused: a reason token and a sentence for the user. */
    internal data class Refusal(val reason: String, val message: String)

    /** Longest server sentence shown on screen. */
    internal const val MAX_SERVER_MESSAGE = 300

    /**
     * Reads the reason from `X-Rist-Enrol` or the JSON body (`reason`, or `detail.reason`), and the
     * user-facing sentence from `message` (or `detail.message`). Reason tokens are compared with `-`
     * and `_` treated alike, so `device_limit` and `device-limit` are the same token.
     */
    internal fun parseRefusal(header: String?, body: String): Refusal {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val detailObj = json?.optJSONObject("detail")
        fun field(name: String): String =
            (json?.optString(name, "").orEmpty().ifBlank { detailObj?.optString(name, "").orEmpty() }).trim()
        val reason = header?.trim().orEmpty().ifBlank { field("reason") }
        val message = field("message").take(MAX_SERVER_MESSAGE)
        return Refusal(reason.lowercase().replace('_', '-'), message)
    }

    fun claim(ctx: Context, nonce: String): Boolean {
        val url = claimUrl(ctx) ?: run { Log.w(TAG, "no usable enrol endpoint"); return false }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val payload = enrolPayload(ctx, nonce)

        repeat(POLL_MAX_ATTEMPTS) { attempt ->
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
            val done = runCatching {
                val req = Request.Builder().url(url)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful -> {
                            val answer = JSONObject(resp.body?.string().orEmpty())
                            val token = answer.optString("token").trim()
                            if (token.isBlank()) {
                                Log.w(TAG, "claim returned 200 with no token")
                                false
                            } else {
                                Config.setAuthToken(ctx, token)
                                if (Config.authToken(ctx) != token) {
                                    Log.e(TAG, "claim: the token did not persist; not reporting success")
                                    false
                                } else {
                                    clear(ctx)
                                    Config.setEnrolRevoked(ctx, false)
                                    Config.onPairedAccount(ctx, answer.optString("user_id"))
                                    WakeLoop.kick()
                                    Log.i(TAG, "enrolled: stored a ${token.length}-char token")
                                    true
                                }
                            }
                        }
                        resp.code == 404 -> {
                            Log.i(TAG, "no record yet (404), attempt ${attempt + 1}")
                            false
                        }
                        resp.code == 403 -> {
                            Log.w(TAG, "claim refused (403): nonce is spent or expired")
                            Config.setEnrolNonce(ctx, "")
                            null
                        }
                        // A 409 is final: device limit, or held by another account. Polling again
                        // cannot change it and only burns the nonce into a 403.
                        resp.code == 409 -> {
                            val why = parseRefusal(resp.header(ENROL_REASON_HEADER),
                                runCatching { resp.body?.string().orEmpty() }.getOrDefault("")).reason
                            Log.w(TAG, "claim refused (409 ${why.ifBlank { "no reason" }.take(32)}); not retrying")
                            Config.setEnrolNonce(ctx, "")
                            null
                        }
                        resp.code == 422 || resp.code == 400 -> {
                            Log.w(TAG, "claim rejected as malformed (HTTP ${resp.code}); giving up")
                            null
                        }
                        else -> {
                            Log.i(TAG, "not ready yet (HTTP ${resp.code}), attempt ${attempt + 1}")
                            false
                        }
                    }
                }
            }.onFailure { Log.i(TAG, "claim attempt ${attempt + 1} failed: ${it.message}") }
                .getOrDefault(false)

            if (done == null) return false
            if (done == true) return true
        }
        Log.w(TAG, "claim timed out after $POLL_MAX_ATTEMPTS attempts; discarding nonce")
        Config.setEnrolNonce(ctx, "")
        return false
    }

    enum class PairResult {
        OK,
        REFUSED,
        NOT_RECOGNISED,
        MALFORMED,
        NO_ENDPOINT,
        NETWORK,
        NOT_GRANTED,
        STORE_FAILED,
        LOCKED_OUT,
        PAYMENT_REQUIRED,
        HELD_ELSEWHERE,
        DEVICE_LIMIT,
        /** 403: the code was already used. */
        SPENT,
        /** 403: the code lived past its time. */
        EXPIRED,
    }

    /** A pairing answer plus the server's own sentence for the user, when it sent one. */
    data class PairReply(val result: PairResult, val serverMessage: String = "")

    // Backend floor: nonce min_length=8.
    internal const val MIN_CODE_LEN = 8

    internal fun isPlausibleCode(raw: String): Boolean = raw.trim().length >= MIN_CODE_LEN

    /** `X-Rist-Enrol` on a 409: which of the two it is. */
    internal const val ENROL_REASON_HEADER = "X-Rist-Enrol"
    internal const val HELD_DEVICE_LIMIT = "device-limit"
    internal const val HELD_ELSEWHERE = "held-elsewhere"
    /** 403 reasons that tell a used code from an expired one. */
    internal const val REFUSED_SPENT = "spent"
    internal const val REFUSED_EXPIRED = "expired"

    /** Where a customer removes a phone; said on the device-limit 409. */
    internal const val PHONES_PAGE = "ristassist.com/account/phones"

    internal fun classifyPair(code: Int, tokenBlank: Boolean, detail: String = "", reason: String = ""): PairResult = when {
        code in 200..299 && tokenBlank -> PairResult.NOT_GRANTED
        code in 200..299 -> PairResult.OK
        code == 404 -> PairResult.NOT_RECOGNISED
        code == 403 && sameToken(reason, REFUSED_SPENT) -> PairResult.SPENT
        code == 403 && sameToken(reason, REFUSED_EXPIRED) -> PairResult.EXPIRED
        code == 403 -> PairResult.REFUSED
        code == 400 || code == 422 -> PairResult.MALFORMED
        code == 429 -> PairResult.LOCKED_OUT
        // Two 409s: the account is at its device limit, or another account holds this phone.
        // Neither is retried. Told apart by X-Rist-Enrol (or the body's reason), else by the server's detail.
        code == 409 && sameToken(reason, HELD_DEVICE_LIMIT) -> PairResult.DEVICE_LIMIT
        code == 409 && sameToken(reason, HELD_ELSEWHERE) -> PairResult.HELD_ELSEWHERE
        code == 409 && detail.contains("maximum", ignoreCase = true) -> PairResult.DEVICE_LIMIT
        code == 409 -> PairResult.HELD_ELSEWHERE
        code == Billing.PAYMENT_REQUIRED -> PairResult.PAYMENT_REQUIRED
        else -> PairResult.NETWORK
    }

    /** Protocol reason tokens: case-insensitive, `-` and `_` alike. */
    private fun sameToken(got: String, want: String): Boolean =
        got.trim().lowercase().replace('_', '-') == want

    fun pair(ctx: Context, code: String): PairResult = pairWithReply(ctx, code).result

    /** One request, never retried: a refused code is reported, not sent again. */
    fun pairWithReply(ctx: Context, code: String): PairReply {
        // Pairing codes are uppercase-only (ABCDEFGHJKMNPQRSTUVWXYZ23456789) and compared byte-exact.
        // uppercase() with no argument is locale-invariant.
        val trimmed = code.trim().uppercase()
        if (!isPlausibleCode(trimmed)) return PairReply(PairResult.MALFORMED)
        val url = claimUrl(ctx) ?: run { Log.w(TAG, "no usable enrol endpoint"); return PairReply(PairResult.NO_ENDPOINT) }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val payload = enrolPayload(ctx, trimmed)
        return runCatching {
            val req = Request.Builder().url(url)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyText = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                val token = if (resp.isSuccessful) {
                    runCatching { JSONObject(bodyText).optString("token").trim() }.getOrDefault("")
                } else ""
                val detail = if (resp.isSuccessful) "" else
                    runCatching { JSONObject(bodyText).optString("detail").trim() }.getOrDefault("")
                val refusal = if (resp.isSuccessful) Refusal("", "") else
                    parseRefusal(resp.header(ENROL_REASON_HEADER), bodyText)
                var verdict = classifyPair(resp.code, token.isBlank(), detail, refusal.reason)
                if (verdict == PairResult.OK) {
                    Config.setAuthToken(ctx, token)
                    if (Config.authToken(ctx) != token) {
                        Log.e(TAG, "pair: the token did not persist; refusing to report success")
                        verdict = PairResult.STORE_FAILED
                    } else {
                        clear(ctx)
                        Config.setCredentialRejected(ctx, false)
                        Config.setEnrolRevoked(ctx, false)
                        Config.clearBillingLapse(ctx)
                        Config.onPairedAccount(ctx,
                            runCatching { JSONObject(bodyText).optString("user_id") }.getOrDefault(""))
                        // The wake loop may be sitting out a refused token's wait.
                        WakeLoop.kick()
                        // Never log the code or the token.
                        Log.i(TAG, "paired: stored a ${token.length}-char token")
                    }
                }
                if (verdict != PairResult.OK) {
                    Log.i(TAG, "pair not granted (HTTP ${resp.code} -> $verdict)")
                }
                PairReply(verdict, if (verdict == PairResult.OK) "" else refusal.message)
            }
        }.onFailure { Log.i(TAG, "pair failed: ${it.message}") }.getOrDefault(PairReply(PairResult.NETWORK))
    }

    /** Refusals where the server's own sentence is shown, when it sent one. */
    private val SERVER_WORDED = setOf(
        PairResult.DEVICE_LIMIT, PairResult.HELD_ELSEWHERE, PairResult.SPENT, PairResult.EXPIRED,
    )

    fun explainPair(reply: PairReply): String =
        if (reply.result in SERVER_WORDED && reply.serverMessage.isNotBlank()) reply.serverMessage
        else explainPair(reply.result)

    /**
     * Whether the typed code stays in the field after a refusal. At the device limit the server no
     * longer spends the code, so it can be sent again once a phone has been removed.
     */
    fun keepsCode(r: PairResult): Boolean =
        r == PairResult.DEVICE_LIMIT || r == PairResult.NETWORK || r == PairResult.PAYMENT_REQUIRED

    fun explainPair(r: PairResult): String = when (r) {
        PairResult.OK -> "Connected to Rist Assist."
        PairResult.NOT_RECOGNISED ->
            "That code wasn't recognized. Check the characters and try again."
        PairResult.REFUSED ->
            "That code has already been used or has expired. Get a new one and try again."
        PairResult.SPENT ->
            "That code has already been used. Get a new one and try again."
        PairResult.EXPIRED ->
            "That code has expired. Get a new one and try again."
        PairResult.MALFORMED ->
            "Codes must be at least $MIN_CODE_LEN characters."
        PairResult.NO_ENDPOINT ->
            "No assistant service is set. Fill in the address just above, press Save endpoint, then connect."
        PairResult.NETWORK ->
            "${Unavailable.PAIRING} If this phone is offline, connect to Wi-Fi or mobile data and try again."
        PairResult.NOT_GRANTED -> Unavailable.PAIRING
        PairResult.LOCKED_OUT ->
            "Too many attempts. Wait a few minutes, then get a new code and try again."
        PairResult.PAYMENT_REQUIRED ->
            "The assistant service says this account's subscription isn't active. Finish signing up or renew it in your Rist account, then try again — your code has not been used."
        PairResult.HELD_ELSEWHERE ->
            "This phone is still listed on another Rist account. Remove it on that account's Phones page, " +
                "then get a new code and try again. This code has been used."
        PairResult.DEVICE_LIMIT ->
            "This account already has two phones. Remove one on the Phones page at $PHONES_PAGE, " +
                "then enter the code again. If it has expired by then, get a new one."
        PairResult.STORE_FAILED ->
            "${Unavailable.PAIRING} Restart the phone, then try a new code."
    }

    private fun claimUrl(ctx: Context): String? {
        val base = Config.backendUrl(ctx)
        if (!base.startsWith("https://") && !base.startsWith("http://")) return null
        return if (base.endsWith("/v1/device")) base.removeSuffix("/v1/device") + "/v1/enroll"
        else base.trimEnd('/') + "/v1/enroll"
    }

    // Only a 401 may reach here; never 503 or 403. A phone removed on the website gets 401, so
    // this opens the pairing screen once (RemovedActivity); nothing waits on SMS enrolment.
    fun onCredentialDead(ctx: Context) {
        if (Config.enrolRevoked(ctx)) return
        if (!Config.credentialRejected(ctx)) Config.setRemovedNoticeShown(ctx, false)
        // Must be set before the clear below.
        Config.setCredentialRejected(ctx, true)
        if (Config.authToken(ctx).isNotBlank()) {
            Log.w(TAG, "backend rejected our credential; clearing it")
            Config.setAuthToken(ctx, "")
        }
    }

    /** Sent by the backend with the 403 for a revoked device; a bare 403 (proxy, WAF, unknown user) never latches. */
    const val REVOKED_HEADER = "X-Rist-Device-Revoked"

    internal fun isExplicitRevocation(code: Int, header: String?): Boolean =
        code == 403 && header?.trim()?.lowercase() in setOf("1", "true")

    // An explicit-revocation 403 only, never 402. The token is kept so the wake loop can notice a
    // reinstatement, and pairing stays open so a new code can bring the phone back without a reset.
    fun onRevoked(ctx: Context) {
        if (!Config.enrolRevoked(ctx)) {
            Log.w(TAG, "this device has been revoked; pairing is open again")
            // A new removal is told once more, on its own screen (RemovedActivity).
            Config.setRemovedNoticeShown(ctx, false)
        }
        Config.setEnrolRevoked(ctx, true)
    }

    /** The backend served this device again, so whatever revoked it has been undone. */
    fun onReinstated(ctx: Context) {
        if (!Config.enrolRevoked(ctx)) return
        Log.i(TAG, "served again after a revocation; clearing it")
        Config.setEnrolRevoked(ctx, false)
    }

    fun run(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            val nonce = begin(app) ?: return@Thread
            claim(app, nonce)
        }.start()
    }

    fun clear(ctx: Context) {
        Config.setEnrolNonce(ctx, "")
        Config.setEnrolSentAtMs(ctx, 0L)
        Config.setEnrolAttempts(ctx, 0)
    }
}
