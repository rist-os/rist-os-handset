package watch.rist.assistant

import android.content.Context
import android.content.SharedPreferences

/** Only the main process writes here: SharedPreferences is not multi-process safe. */
object OtaState {

    private const val PREFS = "rist.ota"

    private const val KEY_BASE_URL = "base_url"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_LAST_CHECK_AT = "last_check_at"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_FAILURES = "consecutive_failures"
    private const val KEY_NOT_BEFORE = "not_before"
    private const val KEY_REFUSED_BUILD = "refused_build"
    private const val KEY_READY_BUILD = "ready_build"
    private const val KEY_OFFER_BUILD = "offer_build"
    private const val KEY_OFFER_BYTES = "offer_bytes"
    private const val KEY_APPROVED_BUILD = "approved_build"
    private const val KEY_APPROVED_METERED_BUILD = "approved_metered_build"
    private const val KEY_NEXT_CHECK_AT = "next_check_at"
    private const val KEY_APPLYING_BUILD = "applying_build"
    private const val KEY_APPLYING_SINCE = "applying_since"
    private const val KEY_APPLYING_PERCENT = "applying_percent"
    private const val KEY_APPLYING_STATUS = "applying_status"
    private const val KEY_LAST_OK_AT = "last_ok_at"
    private const val KEY_LAST_NUDGE_AT = "last_nudge_at"

    const val DEFAULT_CHANNEL = "stable"

    const val DEFAULT_BASE_URL = "https://ota.ristos.org"

    /** Display bound only; nothing is cancelled or retried when it passes. */
    const val APPLYING_STALE_SECONDS = 24L * 3600

    /** Distinct from 0, which is a real reading (update_engine reports DOWNLOADING 0.0). */
    const val PERCENT_UNKNOWN = -1

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().trim()

    fun setBaseUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_BASE_URL, url.trim()).apply()
    }

    /** remove(), never putString(""): an empty string is the opt-out that stops polling. */
    fun clearBaseUrl(ctx: Context) {
        prefs(ctx).edit().remove(KEY_BASE_URL).apply()
    }

    fun channel(ctx: Context): String =
        prefs(ctx).getString(KEY_CHANNEL, DEFAULT_CHANNEL).orEmpty().ifBlank { DEFAULT_CHANNEL }

    fun setChannel(ctx: Context, channel: String) {
        prefs(ctx).edit().putString(KEY_CHANNEL, channel.trim()).apply()
    }

    fun recordCheck(ctx: Context, atSeconds: Long, result: String) {
        prefs(ctx).edit()
            .putLong(KEY_LAST_CHECK_AT, atSeconds)
            .putString(KEY_LAST_RESULT, result)
            .apply()
    }

    fun lastCheckAtSeconds(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_CHECK_AT, 0L)

    fun lastResult(ctx: Context): String = prefs(ctx).getString(KEY_LAST_RESULT, "never checked")
        .orEmpty()

    fun failures(ctx: Context): Int = prefs(ctx).getInt(KEY_FAILURES, 0)

    fun noteFailure(ctx: Context) {
        prefs(ctx).edit().putInt(KEY_FAILURES, (failures(ctx) + 1).coerceAtMost(30)).apply()
    }

    fun noteSuccess(ctx: Context, atSeconds: Long) {
        prefs(ctx).edit().putInt(KEY_FAILURES, 0).putLong(KEY_LAST_OK_AT, atSeconds).apply()
    }

    fun lastSuccessAtSeconds(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_OK_AT, 0L)

    fun notBeforeSeconds(ctx: Context): Long = prefs(ctx).getLong(KEY_NOT_BEFORE, 0L)

    fun setNotBeforeSeconds(ctx: Context, atSeconds: Long) {
        prefs(ctx).edit().putLong(KEY_NOT_BEFORE, atSeconds).apply()
    }

    fun refusedBuild(ctx: Context): String = prefs(ctx).getString(KEY_REFUSED_BUILD, "").orEmpty()

    fun setRefusedBuild(ctx: Context, build: String) {
        prefs(ctx).edit().putString(KEY_REFUSED_BUILD, build).apply()
    }

    fun readyBuild(ctx: Context): String = prefs(ctx).getString(KEY_READY_BUILD, "").orEmpty()

    fun setReadyBuild(ctx: Context, build: String) {
        prefs(ctx).edit().putString(KEY_READY_BUILD, build).apply()
    }

    /** [offeredBytes] is the manifest's payload_size, not the zip size. */
    fun offeredBuild(ctx: Context): String = prefs(ctx).getString(KEY_OFFER_BUILD, "").orEmpty()

    fun offeredBytes(ctx: Context): Long = prefs(ctx).getLong(KEY_OFFER_BYTES, 0L)

    fun recordOffer(ctx: Context, build: String, payloadBytes: Long) {
        prefs(ctx).edit()
            .putString(KEY_OFFER_BUILD, build)
            .putLong(KEY_OFFER_BYTES, payloadBytes.coerceAtLeast(0L))
            .apply()
    }

    fun clearOffer(ctx: Context) {
        prefs(ctx).edit().remove(KEY_OFFER_BUILD).remove(KEY_OFFER_BYTES).apply()
    }

    fun approvedBuild(ctx: Context): String =
        prefs(ctx).getString(KEY_APPROVED_BUILD, "").orEmpty()

    fun meteredApprovedBuild(ctx: Context): String =
        prefs(ctx).getString(KEY_APPROVED_METERED_BUILD, "").orEmpty()

    fun approveBuild(ctx: Context, build: String, allowMetered: Boolean) {
        val b = build.trim()
        if (b.isEmpty()) return
        val e = prefs(ctx).edit().putString(KEY_APPROVED_BUILD, b)
        if (allowMetered) e.putString(KEY_APPROVED_METERED_BUILD, b)
        else e.remove(KEY_APPROVED_METERED_BUILD)
        e.apply()
    }

    fun clearApprovals(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_APPROVED_BUILD)
            .remove(KEY_APPROVED_METERED_BUILD)
            .apply()
    }

    fun applyingBuild(ctx: Context): String = prefs(ctx).getString(KEY_APPLYING_BUILD, "").orEmpty()

    fun applyingSinceSeconds(ctx: Context): Long = prefs(ctx).getLong(KEY_APPLYING_SINCE, 0L)

    fun setApplyingBuild(ctx: Context, build: String, atSeconds: Long) {
        prefs(ctx).edit()
            .putString(KEY_APPLYING_BUILD, build)
            .putLong(KEY_APPLYING_SINCE, if (build.isBlank()) 0L else atSeconds)
            .remove(KEY_APPLYING_PERCENT)
            .remove(KEY_APPLYING_STATUS)
            .apply()
    }

    fun applyingPercent(ctx: Context): Int = prefs(ctx).getInt(KEY_APPLYING_PERCENT, PERCENT_UNKNOWN)

    fun applyingStatus(ctx: Context): Int = prefs(ctx).getInt(KEY_APPLYING_STATUS, OtaApply.Status.IDLE)

    fun setApplyingProgressFor(ctx: Context, build: String, status: Int, percent: Int): Boolean {
        val current = applyingBuild(ctx)
        if (current.isBlank() || build.isBlank() || current != build) return false
        prefs(ctx).edit()
            .putInt(KEY_APPLYING_STATUS, status)
            .putInt(KEY_APPLYING_PERCENT, percent.coerceIn(0, 100))
            .apply()
        return true
    }

    fun clearApplyingBuildFor(ctx: Context, build: String): Boolean {
        val current = applyingBuild(ctx)
        if (current.isBlank() || build.isBlank() || current != build) return false
        setApplyingBuild(ctx, "", 0L)
        return true
    }

    fun clearInFlightOnRestart(ctx: Context, runningBuild: String) {
        val p = prefs(ctx)
        val e = p.edit()
        e.putString(KEY_APPLYING_BUILD, "").putLong(KEY_APPLYING_SINCE, 0L)
        e.remove(KEY_APPLYING_PERCENT).remove(KEY_APPLYING_STATUS)
        if (runningBuild.isNotBlank() && readyBuild(ctx) == runningBuild) {
            e.putString(KEY_READY_BUILD, "")
        }
        e.apply()
    }

    fun lastNudgeAtSeconds(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_NUDGE_AT, 0L)

    fun setLastNudgeAtSeconds(ctx: Context, atSeconds: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_NUDGE_AT, atSeconds).apply()
    }

    /** Display value only; never schedule from it (the alarm runs on elapsed realtime). */
    fun nextCheckAtSeconds(ctx: Context): Long = prefs(ctx).getLong(KEY_NEXT_CHECK_AT, 0L)

    fun setNextCheckAtSeconds(ctx: Context, atSeconds: Long) {
        prefs(ctx).edit().putLong(KEY_NEXT_CHECK_AT, atSeconds).apply()
    }
}
