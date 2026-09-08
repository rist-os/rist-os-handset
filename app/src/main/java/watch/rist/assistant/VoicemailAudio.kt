package watch.rist.assistant

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object VoicemailAudio {

    private const val TAG = "RistVmAudio"

    sealed class Result {
        data class Ready(val file: File) : Result()
        object Expired : Result()
        object NotFound : Result()
        data class Failed(val why: String) : Result()
    }

    fun fetch(ctx: Context, id: String): Result {
        if (id.isBlank()) return Result.NotFound
        val cached = File(cacheDir(ctx), "$id.audio")
        if (cached.exists() && cached.length() > 0) return Result.Ready(cached)

        val base = Config.backendUrl(ctx)
        if (!base.startsWith("http")) return Result.Failed("no assistant service configured")
        val url = (if (base.endsWith("/v1/device")) base.removeSuffix("/v1/device") else base.trimEnd('/')) +
            "/v1/voicemail/$id/audio"
        val bearer = Config.authToken(ctx).takeIf { it.isNotBlank() }
            ?: return Result.Failed("this device isn't set up yet")

        return runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(url).header("Authorization", "Bearer $bearer").get().build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val bytes = resp.body?.bytes()
                        if (bytes == null || bytes.isEmpty()) Result.Failed("the recording was empty")
                        else {
                            val tmp = File(cacheDir(ctx), "$id.part")
                            tmp.writeBytes(bytes)
                            tmp.renameTo(cached)
                            Log.i(TAG, "fetched ${bytes.size} bytes for $id")
                            Result.Ready(cached)
                        }
                    }
                    resp.code == 410 -> { Log.i(TAG, "audio expired for $id"); Result.Expired }
                    resp.code == 404 -> { Log.i(TAG, "no audio for $id"); Result.NotFound }
                    resp.code == 401 -> { Enrolment.onCredentialDead(ctx); Result.Failed("not authorised") }
                    resp.code == 403 -> { Enrolment.onRevoked(ctx); Result.Failed("access turned off") }
                    else -> Result.Failed("couldn't fetch it (${resp.code})")
                }
            }
        }.onFailure { Log.w(TAG, "audio fetch failed", it) }
            .getOrElse { Result.Failed("couldn't reach Rist") }
    }

    private fun cacheDir(ctx: Context): File =
        File(ctx.filesDir, "voicemail").apply { if (!exists()) mkdirs() }

    fun pruneTo(ctx: Context, keepIds: Set<String>) = runCatching {
        cacheDir(ctx).listFiles()?.forEach { f ->
            val id = f.name.substringBeforeLast('.')
            if (id !in keepIds) f.delete()
        }
    }.let { }
}
