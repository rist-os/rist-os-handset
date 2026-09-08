package watch.rist.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object SettingsApply {

    private const val TAG = "RistSettingsCmd"

    private const val KEY_VOICE = "assistant.voice_playback"

    internal val BACKEND_OWNED = emptySet<String>()

    internal fun isBackendOwned(key: String): Boolean = key in BACKEND_OWNED

    private const val KEY_ALARM_VOLUME = "alarms.volume"

    private data class Result(val key: String, val value: String, val outcome: Int, val detail: String)

    fun handle(ctx: Context, cmd: rist.v1.SettingsCommand) {
        val results = mutableListOf<Result>()

        cmd.writesList.forEach { w -> results += applyOne(ctx, w) }
        cmd.readList.forEach { key ->
            val v = read(ctx, key)
            results += if (v == null) Result(key, "", OUTCOME_UNKNOWN_KEY, "not available on this device")
            else Result(key, v, OUTCOME_REPORTED, "")
        }

        if (results.isEmpty()) return
        queue(ctx, cmd.commandId, results)
        Log.i(TAG, "command ${cmd.commandId}: ${results.joinToString { "${it.key}=${it.value}/${it.outcome}" }}")
    }

    private fun applyOne(ctx: Context, w: rist.v1.SettingsWrite): Result = when (w.key) {
        KEY_VOICE -> {
            val wanted = parseToggle(w.value)
            if (wanted == null) {
                Result(w.key, current(ctx, KEY_VOICE), OUTCOME_INVALID_VALUE, "expected on or off")
            } else {
                Config.setReplyVoiceEnabled(ctx, wanted)
                Result(w.key, current(ctx, KEY_VOICE), OUTCOME_APPLIED, "")
            }
        }
        KEY_ALARM_VOLUME -> {
            val pct = parsePercent(w.value)
            if (pct == null) {
                Result(w.key, current(ctx, KEY_ALARM_VOLUME), OUTCOME_INVALID_VALUE,
                    "expected a whole number from 0 to 100")
            } else {
                Config.setAlarmVolumePercent(ctx, pct)
                Result(w.key, current(ctx, KEY_ALARM_VOLUME), OUTCOME_APPLIED, "")
            }
        }
        else -> Result(w.key, "", OUTCOME_REFUSED, "not supported on this device")
    }

    private fun read(ctx: Context, key: String): String? = when (key) {
        KEY_VOICE, KEY_ALARM_VOLUME -> current(ctx, key)
        else -> null
    }

    private fun current(ctx: Context, key: String): String = when (key) {
        KEY_VOICE -> if (Config.isReplyVoiceEnabled(ctx)) "on" else "off"
        KEY_ALARM_VOLUME -> Config.alarmVolumePercent(ctx).toString()
        else -> ""
    }

    private fun parsePercent(v: String): Int? {
        val n = v.trim().removeSuffix("%").trim().toIntOrNull() ?: return null
        return if (n in 0..100) n else null
    }

    private fun parseToggle(v: String): Boolean? = when (v.trim().lowercase()) {
        "on", "true", "1", "yes", "enabled" -> true
        "off", "false", "0", "no", "disabled" -> false
        else -> null
    }

    private fun queue(ctx: Context, commandId: String, results: List<Result>) = runCatching {
        val arr = JSONArray(Config.settingsState(ctx).ifBlank { "[]" })
        results.forEach {
            arr.put(JSONObject().apply {
                put("cmd", commandId); put("key", it.key); put("value", it.value)
                put("outcome", it.outcome); put("detail", it.detail)
            })
        }
        Config.setSettingsState(ctx, arr.toString())
    }.onFailure { Log.w(TAG, "could not queue settings state", it) }.let { }

    fun pending(ctx: Context): Pair<String, List<rist.v1.SettingsValue>> {
        val out = mutableListOf<rist.v1.SettingsValue>()
        var commandId = ""
        runCatching {
            val arr = JSONArray(Config.settingsState(ctx).ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (commandId.isBlank()) commandId = o.optString("cmd")
                out.add(
                    rist.v1.SettingsValue.newBuilder()
                        .setKey(o.optString("key"))
                        .setValue(o.optString("value"))
                        .setOutcomeValue(o.optInt("outcome"))
                        .setDetail(o.optString("detail"))
                        .build()
                )
            }
        }.onFailure { Log.w(TAG, "settings state unreadable", it) }
        return commandId to out
    }

    fun clear(ctx: Context) = Config.setSettingsState(ctx, "")

    private const val OUTCOME_REPORTED = 0
    // Mirrors rist.v1.SettingsValue.Outcome.
    private const val OUTCOME_APPLIED = 1
    private const val OUTCOME_REFUSED = 2
    private const val OUTCOME_UNKNOWN_KEY = 3
    private const val OUTCOME_INVALID_VALUE = 4
}
