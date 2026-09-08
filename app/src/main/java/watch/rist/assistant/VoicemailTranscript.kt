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

    fun fetch(ctx: Context, id: String): Result {
        if (id.isBlank()) return Result.NotFound

        val base = Config.backendUrl(ctx)
        if (!base.startsWith("http")) return Result.Failed("no assistant service configured")
        val url = (if (base.endsWith("/v1/device")) base.removeSuffix("/v1/device") else base.trimEnd('/')) +
            "/v1/voicemail/$id/transcript"
        val bearer = Config.authToken(ctx).takeIf { it.isNotBlank() }
            ?: return Result.Failed("this device isn't set up yet")

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
                    resp.isSuccessful -> parse(resp.body?.string().orEmpty(), id)
                    resp.code == 410 -> { Log.i(TAG, "audio expired for $id; nothing to read"); Result.Expired }
                    resp.code == 404 -> { Log.i(TAG, "no transcript route or no such message: $id"); Result.NotFound }
                    resp.code == 401 -> { Enrolment.onCredentialDead(ctx); Result.Failed("not authorised") }
                    resp.code == 403 -> { Enrolment.onRevoked(ctx); Result.Failed("access turned off") }
                    else -> Result.Failed("couldn't read that one (${resp.code})")
                }
            }
        }.onFailure { Log.w(TAG, "transcript fetch failed", it) }
            .getOrElse { Result.Failed("couldn't reach Rist") }
    }

    private fun parse(body: String, id: String): Result {
        val text = runCatching { JSONObject(body).optString("transcript") }
            .onFailure { Log.w(TAG, "unparseable transcript body for $id", it) }
            .getOrNull()
            ?: return Result.Failed("Rist couldn't read that reply")
        if (text.isBlank()) return Result.Failed("there were no words in that one")
        Log.i(TAG, "transcribed $id on request (${text.length} chars)")
        return Result.Ready(text)
    }
}
