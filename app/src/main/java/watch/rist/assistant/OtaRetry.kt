package watch.rist.assistant

import java.time.format.DateTimeParseException

object OtaRetry {

    const val MIN_SECONDS = 1L

    const val MAX_RETRY_AFTER_SECONDS = 6 * 3600L

    const val BASE_BACKOFF_SECONDS = 60L

    const val MAX_BACKOFF_SECONDS = 6 * 3600L

    sealed class Outcome {
        data class Offered(val body: String) : Outcome()
        object UpToDate : Outcome()
        data class NoBuild(val detail: String) : Outcome()
        data class BackOff(val seconds: Long) : Outcome()
        object Unauthorised : Outcome()
        data class Transient(val detail: String) : Outcome()
    }

    fun classify(code: Int, retryAfter: String?, body: String?, nowMillis: Long): Outcome =
        when (code) {
            200 -> Outcome.Offered(body ?: "")
            204 -> Outcome.UpToDate
            401, 403 -> Outcome.Unauthorised
            404 -> Outcome.NoBuild(body?.take(200) ?: "")
            429, 502, 503, 504 -> Outcome.BackOff(retryAfterSeconds(retryAfter, nowMillis)
                ?: BASE_BACKOFF_SECONDS)
            else -> Outcome.Transient("HTTP $code")
        }

    fun retryAfterSeconds(header: String?, nowMillis: Long): Long? {
        val h = header?.trim().orEmpty()
        if (h.isEmpty()) return null

        h.toLongOrNull()?.let { return clamp(it) }

        return try {
            val at = java.time.ZonedDateTime
                .parse(h, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
            clamp((at - nowMillis + 999L) / 1000L)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    fun backoffSeconds(attempt: Int, retryAfterSeconds: Long?, jitter: Double): Long {
        val steps = attempt.coerceIn(0, 30)
        // shl on a Long: 1 shl 31 as an Int is negative.
        val exponential = (BASE_BACKOFF_SECONDS shl steps)
            .coerceIn(BASE_BACKOFF_SECONDS, MAX_BACKOFF_SECONDS)
        // Jitter applies only to our own schedule; the max() below must never shorten a server Retry-After.
        val jittered = exponential + (exponential * 0.2 * jitter.coerceIn(0.0, 1.0)).toLong()
        val floor = retryAfterSeconds ?: 0L
        return maxOf(jittered, floor).coerceIn(MIN_SECONDS, MAX_BACKOFF_SECONDS)
    }

    private fun clamp(seconds: Long): Long =
        seconds.coerceIn(MIN_SECONDS, MAX_RETRY_AFTER_SECONDS)
}
