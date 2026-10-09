package watch.rist.assistant

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object VoicemailTranscript {

    private const val TAG = "RistVmText"

    sealed class Result {
        data class Ready(val text: String) : Result()
        object NotReady : Result()
        object Expired : Result()
        object NotFound : Result()
        data class Failed(val why: String) : Result()
    }

    private const val MAX_TRANSCRIPT_BYTES = 256 * 1024

    fun fetch(ctx: Context, id: String): Result {
        if (!VoicemailAudio.safeId(id)) return Result.NotFound

        val base = Config.backendUrl(ctx)
        if (!base.startsWith("http")) return Result.Failed(Unavailable.NOT_PAIRED)
        val url = (if (base.endsWith("/v1/device")) base.removeSuffix("/v1/device") else base.trimEnd('/')) +
            "/v1/voicemail/$id/transcript"
        val bearer = Config.authToken(ctx).takeIf { it.isNotBlank() }
            ?: return Result.Failed(Unavailable.NOT_PAIRED)

        return runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $bearer")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    // 204 must be tested before isSuccessful (2xx).
                    resp.code == 204 -> { Log.i(TAG, "no words yet for $id"); Result.NotReady }
                    resp.code == 202 -> { Log.i(TAG, "transcription still running for $id"); Result.NotReady }
                    resp.isSuccessful -> parse(
                        resp.body?.byteStream()?.let { Attachments.readBounded(it, MAX_TRANSCRIPT_BYTES) }
                            ?.toString(Charsets.UTF_8).orEmpty(), id)
                    resp.code == 410 -> { Log.i(TAG, "audio expired for $id; nothing to read"); Result.Expired }
                    resp.code == 404 -> { Log.i(TAG, "no transcript route or no such message: $id"); Result.NotFound }
                    resp.code == 401 -> { Enrolment.onCredentialDead(ctx); Result.Failed(Unavailable.PAIR_AGAIN) }
                    resp.code == 403 -> if (Enrolment.isExplicitRevocation(403, resp.header(Enrolment.REVOKED_HEADER))) {
                        Enrolment.onRevoked(ctx); Result.Failed(Unavailable.REMOVED)
                    } else Result.Failed(Unavailable.VOICEMAIL)
                    resp.code == Billing.PAYMENT_REQUIRED -> {
                        val lapse = Billing.lapseWithLine(resp)
                        Billing.onLapsed(ctx, lapse)
                        Result.Failed(Unavailable.sentence(Billing.lineFor(lapse)))
                    }
                    else -> run { Log.w(TAG, "transcript HTTP ${resp.code}"); Result.Failed(Unavailable.VOICEMAIL) }
                }
            }
        }.onFailure { Log.w(TAG, "transcript fetch failed", it) }
            .getOrElse { Result.Failed(Unavailable.orOffline(ctx, Unavailable.VOICEMAIL)) }
    }

    private fun parse(body: String, id: String): Result {
        val text = runCatching { JSONObject(body).optString("transcript") }
            .onFailure { Log.w(TAG, "unparseable transcript body for $id", it) }
            .getOrNull()
            ?: return Result.Failed(Unavailable.VOICEMAIL)
        if (text.isBlank()) return Result.Failed("There were no words in that message.")
        Log.i(TAG, "transcribed $id on request (${text.length} chars)")
        return Result.Ready(text)
    }
}
