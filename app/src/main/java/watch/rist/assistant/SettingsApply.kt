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

    // How long answers to direct requests stay on the home screen. Texts, calls and
    // notifications are a separate regime and this does not touch them.
    private const val KEY_MESSAGE_HISTORY = "assistant.message_history"

    // Applied once DesignSync ships (it carries settings versions and the wipe restore).
    internal const val KEY_VOICE_LEVEL = "assistant.voice_level"
    internal const val KEY_HAPTICS = "device.haptics"
    internal const val KEY_AUTO_ZONE = "time.auto_zone"
    internal const val KEY_CONTACTS_SYNC = "consent.contacts_sync"
    internal const val KEY_NETWORK_LOCATION = "consent.network_location"
    internal const val KEY_PLACE_TRIGGERS = "consent.place_triggers"

    internal val CONSENTS = setOf(KEY_CONTACTS_SYNC, KEY_NETWORK_LOCATION, KEY_PLACE_TRIGGERS)

    /**
     * Settings that never change from outside the phone, with the reason given back. Only Rist's
     * own settings change remotely; the phone's Android settings, pairing, secrets, software
     * updates and emergency calling are the user's, on the phone.
     */
    internal fun localOnlyReason(key: String): String? {
        val k = key.trim().lowercase()
        return when {
            k.startsWith("android.") || k.startsWith("system.") ->
                "the phone's own system settings are changed only on the phone"
            k in setOf("device.backend_url", "device.push_url", "device.endpoint") ->
                "the service address is changed only on the phone"
            k in setOf("device.pairing", "device.auth_token", "device.enrolment") ->
                "pairing is changed only on the phone"
            k == "voicemail.pin" -> "the voicemail PIN is a secret kept on the phone"
            k.startsWith("ota.") || k == "device.updates" || k == "device.update_consent" ->
                "software updates are chosen only on the phone"
            k == "device.kiosk" || k == "device.owner" || k == "device.lock_task" ->
                "the phone's lock settings are changed only on the phone"
            k.startsWith("emergency.") -> "emergency calling is never changed remotely"
            k == "comms.mode" -> "call handling is changed through the call policy, not settings"
            else -> null
        }
    }

    private data class Result(val key: String, val value: String, val outcome: Int, val detail: String)

    fun handle(ctx: Context, cmd: rist.v1.SettingsCommand) {
        val results = mutableListOf<Result>()
        val v2 = DesignSync.declared()

        cmd.writesList.forEach { w ->
            results += if (v2) applyV2(ctx, w, full = cmd.full) ?: applyOne(ctx, w) else applyOne(ctx, w)
        }
        cmd.readList.forEach { key ->
            val v = read(ctx, key)
            results += if (v == null) Result(key, "", OUTCOME_UNKNOWN_KEY, "not available on this device")
            else Result(key, v, OUTCOME_REPORTED, "")
        }

        // Every write above was answered, so the phone now holds this version. Only a full
        // command sets it: a single change does not mean the phone holds everything else.
        if (v2 && cmd.full && cmd.version > Config.settingsVersion(ctx)) Config.setSettingsVersion(ctx, cmd.version)
        if (results.isEmpty()) return
        queue(ctx, cmd.commandId, results)
        Log.i(TAG, "command ${cmd.commandId}${if (cmd.full) " (full)" else ""}: " +
            results.joinToString { "${it.key}=${it.value}/${it.outcome}" })
    }

    /** The newer keys and refusals; null hands the write to [applyOne]. */
    private fun applyV2(ctx: Context, w: rist.v1.SettingsWrite, full: Boolean): Result? {
        localOnlyReason(w.key)?.let { return Result(w.key, "", OUTCOME_REFUSED, it) }
        if (w.key in CONSENTS && full) {
            // A restore after a wipe is not the user asking: consents wait for them.
            return Result(w.key, current(ctx, w.key), OUTCOME_REFUSED, "consents are not restored after a reset")
        }
        fun toggle(apply: (Boolean) -> Boolean): Result {
            val wanted = parseToggle(w.value)
                ?: return Result(w.key, current(ctx, w.key), OUTCOME_INVALID_VALUE, "expected on or off")
            val ok = apply(wanted)
            return if (ok) Result(w.key, current(ctx, w.key), OUTCOME_APPLIED, "")
            else Result(w.key, current(ctx, w.key), OUTCOME_REFUSED, "the phone did not accept it")
        }
        return when (w.key) {
            KEY_VOICE_LEVEL -> {
                val n = w.value.trim().toIntOrNull()
                if (n == null || n !in 0..Config.VOICE_LEVEL_MAX) {
                    Result(w.key, current(ctx, w.key), OUTCOME_INVALID_VALUE,
                        "expected a whole number from 0 to ${Config.VOICE_LEVEL_MAX}")
                } else {
                    Config.setVoiceLevel(ctx, n)
                    Result(w.key, current(ctx, w.key), OUTCOME_APPLIED, "")
                }
            }
            KEY_HAPTICS -> toggle { Config.setHapticsEnabled(ctx, it); true }
            KEY_AUTO_ZONE -> toggle { AutoTimeZone.setEnabled(ctx, it); true }
            KEY_CONTACTS_SYNC -> toggle { Config.setContactsSyncOff(ctx, !it); true }
            KEY_NETWORK_LOCATION -> toggle {
                NetworkLocationConsent.apply(ctx, it) == NetworkLocationConsent.Outcome.APPLIED
            }
            KEY_PLACE_TRIGGERS -> toggle {
                GeofenceConsent.apply(ctx, it) == GeofenceConsent.Outcome.APPLIED
            }
            KEY_VOICE, KEY_ALARM_VOLUME, KEY_MESSAGE_HISTORY -> null
            else -> Result(w.key, "", OUTCOME_UNKNOWN_KEY, "not a setting this phone applies")
        }
    }

    /**
     * Reports a setting the user changed on the phone, so the backend's copy (the one a wiped
     * phone gets back) stays true. Rides the next turn with an empty command id.
     */
    fun reportLocal(ctx: Context, key: String) {
        if (!DesignSync.declared()) return
        val v = read(ctx, key) ?: return
        queue(ctx, "", listOf(Result(key, v, OUTCOME_REPORTED, "")))
    }

    /** Every key this phone applies, in the order a snapshot reports them. */
    internal val SNAPSHOT_KEYS = listOf(
        KEY_VOICE, KEY_ALARM_VOLUME, KEY_MESSAGE_HISTORY, KEY_VOICE_LEVEL, KEY_HAPTICS, KEY_AUTO_ZONE,
        KEY_CONTACTS_SYNC, KEY_NETWORK_LOCATION, KEY_PLACE_TRIGGERS,
    )

    /**
     * Reports every value the phone holds, unsolicited. Sent once, on the first run of a build that
     * takes settings versions: until then changes made on the phone were never reported, so the
     * backend's copy may be older than the phone, and a later full restore would push it back.
     */
    fun reportSnapshot(ctx: Context) {
        if (!DesignSync.declared()) return
        val results = SNAPSHOT_KEYS.mapNotNull { k -> read(ctx, k)?.let { Result(k, it, OUTCOME_REPORTED, "") } }
        if (results.isNotEmpty()) queue(ctx, "", results)
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
        KEY_MESSAGE_HISTORY -> {
            val choice = Retention.byId(w.value)
            if (choice == null) {
                Result(w.key, current(ctx, KEY_MESSAGE_HISTORY), OUTCOME_INVALID_VALUE,
                    "expected one of " + Retention.ids().joinToString(", "))
            } else {
                Config.setTranscriptMaxAgeMs(ctx, choice.ms)
                Result(w.key, current(ctx, KEY_MESSAGE_HISTORY), OUTCOME_APPLIED, "")
            }
        }
        else -> Result(w.key, "", OUTCOME_REFUSED, "not supported on this device")
    }

    private fun read(ctx: Context, key: String): String? = when (key) {
        KEY_VOICE, KEY_ALARM_VOLUME, KEY_MESSAGE_HISTORY -> current(ctx, key)
        KEY_VOICE_LEVEL, KEY_HAPTICS, KEY_AUTO_ZONE, KEY_CONTACTS_SYNC, KEY_NETWORK_LOCATION,
        KEY_PLACE_TRIGGERS -> if (DesignSync.declared()) current(ctx, key) else null
        else -> null
    }

    private fun current(ctx: Context, key: String): String = when (key) {
        KEY_VOICE -> if (Config.isReplyVoiceEnabled(ctx)) "on" else "off"
        KEY_ALARM_VOLUME -> Config.alarmVolumePercent(ctx).toString()
        KEY_MESSAGE_HISTORY -> Retention.byMs(Config.transcriptMaxAgeMs(ctx)).id
        KEY_VOICE_LEVEL -> Config.voiceLevel(ctx).toString()
        KEY_HAPTICS -> onOff(Config.isHapticsEnabled(ctx))
        KEY_AUTO_ZONE -> onOff(Config.isAutoTimeZone(ctx))
        KEY_CONTACTS_SYNC -> onOff(!Config.contactsSyncOff(ctx))
        KEY_NETWORK_LOCATION -> onOff(Config.networkLocationChoice(ctx) == NetworkLocationConsent.Choice.ON)
        KEY_PLACE_TRIGGERS -> onOff(GeofenceConsent.choice(ctx) == GeofenceConsent.Choice.ON)
        else -> ""
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"


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

    /**
     * The values the next turn carries: those of the oldest command still queued (one
     * SettingsState answers one command id; unsolicited reports have an empty one).
     */
    fun pending(ctx: Context): Pair<String, List<rist.v1.SettingsValue>> {
        val out = mutableListOf<rist.v1.SettingsValue>()
        var commandId: String? = null
        runCatching {
            val arr = JSONArray(Config.settingsState(ctx).ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (commandId == null) commandId = o.optString("cmd")
                if (o.optString("cmd") != commandId) continue
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
        return (commandId ?: "") to out
    }

    fun clear(ctx: Context) = Config.setSettingsState(ctx, "")

    /** Drops what was carried for [commandId]; anything queued for another command stays. */
    fun clear(ctx: Context, commandId: String) = runCatching {
        val arr = JSONArray(Config.settingsState(ctx).ifBlank { "[]" })
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("cmd") != commandId) keep.put(o)
        }
        Config.setSettingsState(ctx, if (keep.length() == 0) "" else keep.toString())
    }.onFailure { Config.setSettingsState(ctx, "") }.let { }

    private const val OUTCOME_REPORTED = 0
    // Mirrors rist.v1.SettingsValue.Outcome.
    private const val OUTCOME_APPLIED = 1
    private const val OUTCOME_REFUSED = 2
    private const val OUTCOME_UNKNOWN_KEY = 3
    private const val OUTCOME_INVALID_VALUE = 4
}
