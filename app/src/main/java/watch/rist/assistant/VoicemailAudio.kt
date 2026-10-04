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

    /** Ids come from the backend and name a file and a URL path; nothing that could climb out of either. */
    internal fun safeId(id: String): Boolean = ID_PATTERN.matches(id)
    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")

    internal const val MAX_AUDIO_BYTES = 25L * 1024 * 1024

    /** Copies at most [cap] bytes; false, and nothing kept, when the body runs past it. */
    internal fun copyBounded(input: java.io.InputStream, out: File, cap: Long): Long? {
        var total = 0L
        val ok = input.use { src ->
            out.outputStream().use { dst ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > cap) return@use false
                    dst.write(buf, 0, n)
                }
                true
            }
        }
        if (!ok) { out.delete(); return null }
        return total
    }

    fun fetch(ctx: Context, id: String): Result {
        if (!safeId(id)) return Result.NotFound
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
                        val body = resp.body
                        val tmp = File(cacheDir(ctx), "$id.part")
                        val size = when {
                            body == null -> 0L
                            body.contentLength() > MAX_AUDIO_BYTES -> null
                            else -> copyBounded(body.byteStream(), tmp, MAX_AUDIO_BYTES)
                        }
                        when {
                            size == null -> Result.Failed("the recording was too large")
                            size == 0L -> { tmp.delete(); Result.Failed("the recording was empty") }
                            else -> {
                                tmp.renameTo(cached)
                                Log.i(TAG, "fetched $size bytes for $id")
                                Result.Ready(cached)
                            }
                        }
                    }
                    resp.code == 410 -> { Log.i(TAG, "audio expired for $id"); Result.Expired }
                    resp.code == 404 -> { Log.i(TAG, "no audio for $id"); Result.NotFound }
                    resp.code == 401 -> { Enrolment.onCredentialDead(ctx); Result.Failed("not authorised") }
                    resp.code == 403 -> Result.Failed("access refused")
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
