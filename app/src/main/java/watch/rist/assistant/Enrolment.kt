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

    private const val MAX_ATTEMPTS = 3

    enum class Readiness { READY, NO_SIM, NO_SERVICE, NOT_CONFIGURED, EXHAUSTED, REVOKED, ALREADY_ENROLLED }

    fun needed(ctx: Context): Boolean = Config.authToken(ctx).isBlank()

    fun newNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun readiness(ctx: Context): Readiness {
        if (!needed(ctx)) return Readiness.ALREADY_ENROLLED
        if (Config.enrolRevoked(ctx)) return Readiness.REVOKED
        if (target(ctx).isBlank()) return Readiness.NOT_CONFIGURED
        if (Config.enrolAttempts(ctx) >= MAX_ATTEMPTS) return Readiness.EXHAUSTED
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
        Readiness.REVOKED ->
            "This device's access was turned off by the assistant service. Setting it up again won't help."
        Readiness.EXHAUSTED ->
            "Setup couldn't be completed. Retrying will not help; check with whoever runs your assistant service."
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

    fun claim(ctx: Context, nonce: String): Boolean {
        val url = claimUrl(ctx) ?: run { Log.w(TAG, "no usable enrol endpoint"); return false }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val payload = JSONObject().apply {
            put("nonce", nonce)
            put("device_id", Config.deviceId(ctx))
            put("label", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        }.toString()

        repeat(POLL_MAX_ATTEMPTS) { attempt ->
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
            val done = runCatching {
                val req = Request.Builder().url(url)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful -> {
                            val token = JSONObject(resp.body?.string().orEmpty()).optString("token").trim()
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
    }

    // Backend floor: nonce min_length=8.
    internal const val MIN_CODE_LEN = 8

    internal fun isPlausibleCode(raw: String): Boolean = raw.trim().length >= MIN_CODE_LEN

    internal fun classifyPair(code: Int, tokenBlank: Boolean): PairResult = when {
        code in 200..299 && tokenBlank -> PairResult.NOT_GRANTED
        code in 200..299 -> PairResult.OK
        code == 404 -> PairResult.NOT_RECOGNISED
        code == 403 -> PairResult.REFUSED
        code == 400 || code == 422 -> PairResult.MALFORMED
        code == 429 -> PairResult.LOCKED_OUT
        else -> PairResult.NETWORK
    }

    fun pair(ctx: Context, code: String): PairResult {
        // Pairing codes are uppercase-only (ABCDEFGHJKMNPQRSTUVWXYZ23456789) and compared byte-exact.
        // uppercase() with no argument is locale-invariant.
        val trimmed = code.trim().uppercase()
        if (!isPlausibleCode(trimmed)) return PairResult.MALFORMED
        val url = claimUrl(ctx) ?: run { Log.w(TAG, "no usable enrol endpoint"); return PairResult.NO_ENDPOINT }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val payload = JSONObject().apply {
            put("nonce", trimmed)
            put("device_id", Config.deviceId(ctx))
            put("label", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        }.toString()
        return runCatching {
            val req = Request.Builder().url(url)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val token = if (resp.isSuccessful) {
                    runCatching { JSONObject(resp.body?.string().orEmpty()).optString("token").trim() }
                        .getOrDefault("")
                } else ""
                var verdict = classifyPair(resp.code, token.isBlank())
                if (verdict == PairResult.OK) {
                    Config.setAuthToken(ctx, token)
                    if (Config.authToken(ctx) != token) {
                        Log.e(TAG, "pair: the token did not persist; refusing to report success")
                        verdict = PairResult.STORE_FAILED
                    } else {
                        clear(ctx)
                        Config.setCredentialRejected(ctx, false)
                        // Never log the code or the token.
                        Log.i(TAG, "paired: stored a ${token.length}-char token")
                    }
                }
                if (verdict != PairResult.OK) {
                    Log.i(TAG, "pair not granted (HTTP ${resp.code} -> $verdict)")
                }
                verdict
            }
        }.onFailure { Log.i(TAG, "pair failed: ${it.message}") }.getOrDefault(PairResult.NETWORK)
    }

    fun explainPair(r: PairResult): String = when (r) {
        PairResult.OK -> "This device is now connected."
        PairResult.NOT_RECOGNISED ->
            "That code wasn't recognized. Check the characters and try again."
        PairResult.REFUSED ->
            "That code has already been used or has expired. Get a new one and try again."
        PairResult.MALFORMED ->
            "Codes must be at least $MIN_CODE_LEN characters."
        PairResult.NO_ENDPOINT ->
            "No assistant service is set. Fill in the address just above, press Save endpoint, then connect."
        PairResult.NETWORK ->
            "Couldn't reach the assistant service. Check the connection and try again — your code has not been used."
        PairResult.NOT_GRANTED ->
            "The assistant service answered but didn't connect this device. Get a new code and try again."
        PairResult.LOCKED_OUT ->
            "Too many attempts. Wait a few minutes, then get a new code and try again."
        PairResult.STORE_FAILED ->
            "This device couldn't save the connection securely, so it isn't connected. " +
                "Restart the phone and try a new code; if it keeps happening, report it."
    }

    private fun claimUrl(ctx: Context): String? {
        val base = Config.backendUrl(ctx)
        if (!base.startsWith("https://") && !base.startsWith("http://")) return null
        return if (base.endsWith("/v1/device")) base.removeSuffix("/v1/device") + "/v1/enroll"
        else base.trimEnd('/') + "/v1/enroll"
    }

    // Only a 401 may reach here; never 503 or 403.
    fun onCredentialDead(ctx: Context) {
        if (Config.enrolRevoked(ctx)) return
        // Must be set before the clear below.
        Config.setCredentialRejected(ctx, true)
        if (Config.authToken(ctx).isNotBlank()) {
            Log.w(TAG, "backend rejected our credential; clearing it")
            Config.setAuthToken(ctx, "")
        }
    }

    fun onRevoked(ctx: Context) {
        if (!Config.enrolRevoked(ctx)) Log.w(TAG, "this device has been revoked; enrolment disabled")
        Config.setEnrolRevoked(ctx, true)
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
