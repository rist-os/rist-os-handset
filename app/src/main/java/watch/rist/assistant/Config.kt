package watch.rist.assistant

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

object Config {

    private const val TAG = "RistConfig"
    private const val PREFS = "rist.cfg"

    private const val KEY_BACKEND = "backend_url"
    private const val KEY_PUSH = "push_url"
    private const val KEY_PROGRESS = "progress_url"
    private const val KEY_REPLY_VOICE = "reply_voice_enabled"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_CARRIER_VM_WAITING = "carrier_vm_waiting"
    private const val KEY_VOICEMAIL_PIN = "voicemail_pin"
    private const val KEY_SETUP_DONE = "setup_complete"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_THEME = "theme_id"
    private const val KEY_BRIGHTNESS = "screen_brightness"
    private const val KEY_TRANSCRIPT_MAX = "transcript_max_entries"
    private const val KEY_TRANSCRIPT_AGE_MS = "transcript_max_age_ms"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_SESSION_AT = "session_last_at"
    private const val KEY_AWAITING_REPLY = "awaiting_reply"
    private const val KEY_KIOSK_STAMP = "kiosk_provisioned_vc"
    private const val KEY_TIMERS = "running_timers"
    private const val KEY_SMS_QUEUE = "sms_queue"
    private const val KEY_SEEN_COMMS = "seen_comms_ids"
    private const val KEY_ENROL_NONCE = "enrol_nonce"
    private const val KEY_ENROL_SENT_AT = "enrol_sent_at"
    private const val KEY_ENROL_ATTEMPTS = "enrol_attempts"
    private const val KEY_RIST_NUMBER = "rist_number"
    private const val KEY_ENROL_REVOKED = "enrol_revoked"
    private const val KEY_CREDENTIAL_REJECTED = "credential_rejected"
    private const val KEY_COMMS_RESULTS = "comms_results"
    private const val KEY_VOICEMAILS = "voicemails"
    private const val KEY_SETTINGS_STATE = "settings_state"
    private const val KEY_ALARM_VOLUME = "alarm_volume_pct"
    private const val KEY_VM_COUNT = "voicemail_count"
    private const val KEY_NOTIFICATIONS = "notification_queue"
    private const val KEY_MAIL_UNREAD = "mail_unread"
    private const val KEY_MAIL_ACK = "mail_acknowledged"
    private const val KEY_GEOFENCES = "geofences"
    private const val KEY_GEOFENCE_QUEUE = "geofence_queue"
    private const val KEY_GEOFENCE_LAST_FIX = "geofence_last_fix"
    private const val KEY_VM_TRANSCRIPTS_PURGED = "voicemail_transcripts_purged"
    private const val KEY_NETLOC_CHOICE = "network_location_choice"
    private const val KEY_NETLOC_ASKS = "network_location_asks"
    private const val KEY_NETLOC_ASKED_AT = "network_location_asked_at"

    // No BuildConfig: Soong does not generate it, and this file must compile under both build systems.
    @Volatile private var deployDefaults: Pair<String, String>? = null
    private fun deploy(ctx: Context): Pair<String, String> = deployDefaults ?: runCatching {
        ctx.assets.open("deploy.properties").use { s ->
            parseDeploy(java.util.Properties().apply { load(s) })
        }
    }.getOrDefault("" to "").also { deployDefaults = it }

    internal fun parseDeploy(p: java.util.Properties): Pair<String, String> =
        p.getProperty("backendUrl").orEmpty().trim() to p.getProperty("pushUrl").orEmpty().trim()

    internal fun setDeployDefaultsForTest(backend: String, push: String) {
        deployDefaults = backend to push
    }

    internal fun clearDeployDefaultsForTest() {
        deployDefaults = null
    }

    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val p = runCatching { openEncrypted(ctx) }.getOrElse {
                Log.e(TAG, "EncryptedSharedPreferences unavailable; falling back to plaintext with " +
                    "secrets DISABLED (device will behave as unenrolled)", it)
                SecretFilteringPrefs(
                    ctx.applicationContext.getSharedPreferences("$PREFS.plain", Context.MODE_PRIVATE)
                )
            }
            cached = p
            return p
        }
    }

    private fun openEncrypted(ctx: Context): SharedPreferences {
        val app = ctx.applicationContext
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            app,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    internal fun isDebuggable(flags: Int): Boolean =
        (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    internal fun isDebugBuild(ctx: Context): Boolean =
        isDebuggable(ctx.applicationContext.applicationInfo.flags)

    fun isEndpointEditable(ctx: Context): Boolean =
        isDebugBuild(ctx) || deploy(ctx).first.isBlank()

    internal enum class Override {
        KEEP,
        DROP_KEEPING_TOKEN,
        DROP_CLEARING_TOKEN,
    }

    internal data class BackendChoice(val url: String, val action: Override)

    internal fun resolveBackend(stored: String?, default: String, debuggable: Boolean): BackendChoice {
        val override = stored?.takeIf { it.isNotBlank() } ?: return BackendChoice(default, Override.KEEP)
        // Blank default = bring-your-own-backend build: the stored value is the only endpoint, never cleared.
        if (default.isBlank()) return BackendChoice(override, Override.KEEP)
        if (!debuggable) {
            val action =
                if (override == default) Override.DROP_KEEPING_TOKEN else Override.DROP_CLEARING_TOKEN
            return BackendChoice(default, action)
        }
        // A persisted cleartext override never beats a TLS default.
        if (override.startsWith("http://") && default.startsWith("https://")) {
            return BackendChoice(default, Override.DROP_KEEPING_TOKEN)
        }
        return BackendChoice(override, Override.KEEP)
    }

    fun backendUrl(ctx: Context): String {
        val stored = prefs(ctx).getString(KEY_BACKEND, null)
        val default = deploy(ctx).first
        val choice = resolveBackend(stored, default, isDebugBuild(ctx))
        when (choice.action) {
            Override.KEEP -> Unit
            Override.DROP_KEEPING_TOKEN -> {
                Log.w(TAG, "dropping persisted endpoint override in favour of the compiled-in default")
                prefs(ctx).edit().remove(KEY_BACKEND).apply()
            }
            Override.DROP_CLEARING_TOKEN -> {
                Log.w(TAG, "endpoint override is not permitted in this build; dropping it and deauthing")
                clearBackendOverride(ctx)
            }
        }
        return choice.url
    }

    fun pushUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_PUSH, null)?.takeIf { it.isNotBlank() } ?: deploy(ctx).second

    fun progressUrl(ctx: Context): String {
        prefs(ctx).getString(KEY_PROGRESS, null)?.let { if (it.isNotEmpty()) return it }
        val base = backendUrl(ctx)
        return when {
            base.endsWith("/v1/device") -> base.removeSuffix("/v1/device") + "/v1/media/progress"
            else -> base.trimEnd('/') + "/v1/media/progress"
        }
    }

    internal fun setProgressEndpoint(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_PROGRESS, url.trim()).apply()
    }

    // Changing the host drops the token: a credential must not follow to a new host.
    fun setBackendEndpoint(ctx: Context, url: String) {
        val next = url.trim()
        if (next != prefs(ctx).getString(KEY_BACKEND, "").orEmpty()) clearAuthTokenForHostChange(ctx)
        prefs(ctx).edit().putString(KEY_BACKEND, next).apply()
    }

    fun clearBackendOverride(ctx: Context) {
        if (prefs(ctx).getString(KEY_BACKEND, "").orEmpty().isNotBlank()) {
            clearAuthTokenForHostChange(ctx)
        }
        prefs(ctx).edit().remove(KEY_BACKEND).apply()
    }

    private fun clearAuthTokenForHostChange(ctx: Context) {
        val had = authToken(ctx).length
        prefs(ctx).edit().remove(KEY_AUTH_TOKEN).apply()
        if (had > 0) {
            android.util.Log.i("RistConfig", "backend endpoint changed; cleared the device token ($had chars)")
        }
    }

    fun defaultBackendUrl(ctx: Context): String = deploy(ctx).first
    fun defaultPushUrl(ctx: Context): String = deploy(ctx).second

    const val DEFAULT_TRANSCRIPT_MAX_ENTRIES = 200
    const val DEFAULT_TRANSCRIPT_MAX_AGE_MS = 2L * 60L * 1000L

    fun transcriptMaxEntries(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TRANSCRIPT_MAX, DEFAULT_TRANSCRIPT_MAX_ENTRIES)
    fun setTranscriptMaxEntries(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt(KEY_TRANSCRIPT_MAX, n.coerceAtLeast(1)).apply()
    }

    fun transcriptMaxAgeMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_TRANSCRIPT_AGE_MS, DEFAULT_TRANSCRIPT_MAX_AGE_MS)
    fun setTranscriptMaxAgeMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(KEY_TRANSCRIPT_AGE_MS, ms.coerceAtLeast(0L)).apply()
    }

    fun brightness(ctx: Context): Float = prefs(ctx).getFloat(KEY_BRIGHTNESS, -1f)

    fun timers(ctx: Context): String = prefs(ctx).getString(KEY_TIMERS, "") ?: ""
    fun setTimers(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_TIMERS, json).apply() }

    fun smsQueue(ctx: Context): String = prefs(ctx).getString(KEY_SMS_QUEUE, "") ?: ""
    fun setSmsQueue(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_SMS_QUEUE, json).apply() }

    fun seenCommsIds(ctx: Context): List<String> = runCatching {
        val arr = org.json.JSONArray((prefs(ctx).getString(KEY_SEEN_COMMS, "") ?: "").ifBlank { "[]" })
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun setSeenCommsIds(ctx: Context, ids: List<String>) {
        val arr = org.json.JSONArray().also { a -> ids.forEach { a.put(it) } }
        prefs(ctx).edit().putString(KEY_SEEN_COMMS, arr.toString()).apply()
    }

    fun enrolNonce(ctx: Context): String = prefs(ctx).getString(KEY_ENROL_NONCE, "") ?: ""
    fun setEnrolNonce(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_ENROL_NONCE, v).apply() }
    fun enrolSentAtMs(ctx: Context): Long = prefs(ctx).getLong(KEY_ENROL_SENT_AT, 0L)
    fun setEnrolSentAtMs(ctx: Context, v: Long) { prefs(ctx).edit().putLong(KEY_ENROL_SENT_AT, v).apply() }
    fun ristNumber(ctx: Context): String = prefs(ctx).getString(KEY_RIST_NUMBER, "") ?: ""
    fun setRistNumber(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_RIST_NUMBER, v.trim()).apply() }

    fun enrolRevoked(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENROL_REVOKED, false)
    fun setEnrolRevoked(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_ENROL_REVOKED, v).apply() }

    fun credentialRejected(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CREDENTIAL_REJECTED, false)

    fun setCredentialRejected(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CREDENTIAL_REJECTED, v).apply()
    }

    fun voicemailCount(ctx: Context): Int = prefs(ctx).getInt(KEY_VM_COUNT, 0)
    fun setVoicemailCount(ctx: Context, n: Int) { prefs(ctx).edit().putInt(KEY_VM_COUNT, n).apply() }

    fun notifications(ctx: Context): String = prefs(ctx).getString(KEY_NOTIFICATIONS, "") ?: ""
    fun setNotifications(ctx: Context, json: String) {
        // commit(), not apply(): must be on disk before the notification_ack that names it leaves the device.
        prefs(ctx).edit().putString(KEY_NOTIFICATIONS, json).commit()
    }

    fun mailUnread(ctx: Context): Int = prefs(ctx).getInt(KEY_MAIL_UNREAD, 0)
    // Sent on every response including 0 (proto3 cannot omit-vs-zero); store unconditionally.
    // The dismiss mark must be lowered here, not only clamped on read, or a refilled mailbox stays silent.
    fun setMailUnread(ctx: Context, n: Int) {
        val fresh = n.coerceAtLeast(0)
        prefs(ctx).edit().putInt(KEY_MAIL_UNREAD, fresh).apply()
        if (mailAcknowledged(ctx) > fresh) setMailAcknowledged(ctx, fresh)
    }

    fun mailAcknowledged(ctx: Context): Int = prefs(ctx).getInt(KEY_MAIL_ACK, 0)
    fun setMailAcknowledged(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt(KEY_MAIL_ACK, n.coerceAtLeast(0)).apply()
    }

    fun pendingMail(ctx: Context): Int {
        val unread = mailUnread(ctx)
        val ack = minOf(mailAcknowledged(ctx), unread)
        return (unread - ack).coerceAtLeast(0)
    }

    fun networkLocationChoice(ctx: Context): NetworkLocationConsent.Choice =
        NetworkLocationConsent.Choice.parse(prefs(ctx).getString(KEY_NETLOC_CHOICE, ""))

    fun setNetworkLocationChoice(ctx: Context, c: NetworkLocationConsent.Choice) {
        prefs(ctx).edit().putString(KEY_NETLOC_CHOICE, c.stored).apply()
    }

    fun networkLocationAsks(ctx: Context): Int = prefs(ctx).getInt(KEY_NETLOC_ASKS, 0)
    fun setNetworkLocationAsks(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt(KEY_NETLOC_ASKS, n).apply()
    }

    fun networkLocationAskedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_NETLOC_ASKED_AT, 0L)
    fun setNetworkLocationAskedAt(ctx: Context, at: Long) {
        prefs(ctx).edit().putLong(KEY_NETLOC_ASKED_AT, at).apply()
    }

    fun alarmVolumePercent(ctx: Context): Int = prefs(ctx).getInt(KEY_ALARM_VOLUME, 100).coerceIn(0, 100)
    fun setAlarmVolumePercent(ctx: Context, pct: Int) {
        prefs(ctx).edit().putInt(KEY_ALARM_VOLUME, pct.coerceIn(0, 100)).apply()
    }

    fun settingsState(ctx: Context): String = prefs(ctx).getString(KEY_SETTINGS_STATE, "") ?: ""
    fun setSettingsState(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_SETTINGS_STATE, json).apply() }

    fun voicemails(ctx: Context): String = prefs(ctx).getString(KEY_VOICEMAILS, "") ?: ""
    fun setVoicemails(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_VOICEMAILS, json).apply() }

    fun voicemailTranscriptsPurged(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VM_TRANSCRIPTS_PURGED, false)

    fun setVoicemailTranscriptsPurged(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VM_TRANSCRIPTS_PURGED, v).apply()
    }

    fun commsResults(ctx: Context): String = prefs(ctx).getString(KEY_COMMS_RESULTS, "") ?: ""
    fun setCommsResults(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_COMMS_RESULTS, json).apply() }

    fun geofences(ctx: Context): String = prefs(ctx).getString(KEY_GEOFENCES, "") ?: ""
    fun setGeofences(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_GEOFENCES, json).apply() }

    fun geofenceQueue(ctx: Context): String = prefs(ctx).getString(KEY_GEOFENCE_QUEUE, "") ?: ""
    fun setGeofenceQueue(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_GEOFENCE_QUEUE, json).apply() }

    fun geofenceLastFix(ctx: Context): String = prefs(ctx).getString(KEY_GEOFENCE_LAST_FIX, "") ?: ""
    fun setGeofenceLastFix(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_GEOFENCE_LAST_FIX, v).apply()
    }

    fun enrolAttempts(ctx: Context): Int = prefs(ctx).getInt(KEY_ENROL_ATTEMPTS, 0)
    fun setEnrolAttempts(ctx: Context, v: Int) { prefs(ctx).edit().putInt(KEY_ENROL_ATTEMPTS, v).apply() }

    fun kioskProvisionedFor(ctx: Context): Long = prefs(ctx).getLong(KEY_KIOSK_STAMP, 0L)
    fun setKioskProvisionedFor(ctx: Context, vc: Long) {
        prefs(ctx).edit().putLong(KEY_KIOSK_STAMP, vc).apply()
    }
    fun setBrightness(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_BRIGHTNESS, v).apply()
    }

    // Must agree with Themes.byId()'s fallback.
    fun themeId(ctx: Context): String = prefs(ctx).getString(KEY_THEME, "ledger") ?: "ledger"
    fun setThemeId(ctx: Context, id: String) { prefs(ctx).edit().putString(KEY_THEME, id).apply() }


    fun voicemailPin(ctx: Context): String = prefs(ctx).getString(KEY_VOICEMAIL_PIN, "").orEmpty()

    fun setVoicemailPin(ctx: Context, pin: String) {
        prefs(ctx).edit().putString(KEY_VOICEMAIL_PIN, pin.filter { it.isDigit() }).apply()
    }

    fun carrierVoicemailWaiting(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CARRIER_VM_WAITING, false)

    fun setCarrierVoicemailWaiting(ctx: Context, waiting: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CARRIER_VM_WAITING, waiting).apply()
    }

    fun isReplyVoiceEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_REPLY_VOICE, true)

    fun isHapticsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HAPTICS, true)

    fun setHapticsEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HAPTICS, enabled).apply()
    }

    fun setReplyVoiceEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_REPLY_VOICE, enabled).apply()
    }

    fun isSetupComplete(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupComplete(ctx: Context, done: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SETUP_DONE, done).apply()
    }

    fun deviceId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    fun authToken(ctx: Context): String =
        prefs(ctx).getString(KEY_AUTH_TOKEN, "") ?: ""

    fun setAuthToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_AUTH_TOKEN, token.trim()).apply()
    }

    private const val TOKEN_IMPORT_FILE = "rist-token.txt"

    fun importTokenFileIfPresent(ctx: Context) {
        runCatching {
            val f = java.io.File(ctx.getExternalFilesDir(null), TOKEN_IMPORT_FILE)
            if (!f.exists()) return
            val token = f.readText().trim()
            val len = f.length().toInt()
            if (token.isBlank()) {
                runCatching {
                    java.io.RandomAccessFile(f, "rws").use { r -> r.write(ByteArray(len)); r.fd.sync() }
                }
                f.delete()
                Log.w(TAG, "token import: file was empty; nothing stored")
                return
            }
            if (authToken(ctx).isNotBlank()) {
                runCatching {
                    java.io.RandomAccessFile(f, "rws").use { r -> r.write(ByteArray(len)); r.fd.sync() }
                }
                f.delete()
                Log.w(TAG, "token import: a token is already provisioned; REFUSED and file removed")
                return
            }
            // Store first, destroy after, only if the store took: on a faulted Keystore the write is dropped silently.
            setAuthToken(ctx, token)
            if (authToken(ctx) != token) {
                Log.e(TAG, "token import: token did not persist; leaving the import file for a retry")
                return
            }
            runCatching {
                java.io.RandomAccessFile(f, "rws").use { r -> r.write(ByteArray(len)); r.fd.sync() }
            }
            f.delete()
            Log.i(TAG, "token import: stored a ${token.length}-char token; import file removed")
        }.onFailure { Log.w(TAG, "token import failed", it) }
    }

    private const val SESSION_IDLE_MS = 5L * 60L * 1000L

    private const val SESSION_IDLE_AWAITING_MS = 20L * 60L * 1000L

    fun setAwaitingReply(ctx: Context, awaiting: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AWAITING_REPLY, awaiting).apply()
    }

    fun sessionId(ctx: Context): String = synchronized(this) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val existing = p.getString(KEY_SESSION_ID, null)?.takeIf { it.isNotEmpty() }
        val last = p.getLong(KEY_SESSION_AT, 0L)
        val window = if (p.getBoolean(KEY_AWAITING_REPLY, false)) SESSION_IDLE_AWAITING_MS
                     else SESSION_IDLE_MS
        val stale = last > 0L && now - last > window
        if (existing == null || stale) {
            val id = UUID.randomUUID().toString()
            p.edit().putString(KEY_SESSION_ID, id).putLong(KEY_SESSION_AT, now).apply()
            if (stale) Log.i("RistCfg", "session rotated after ${(now - last) / 1000}s idle")
            return id
        }
        p.edit().putLong(KEY_SESSION_AT, now).apply()
        return existing
    }

    fun currentSessionId(ctx: Context): String =
        prefs(ctx).getString(KEY_SESSION_ID, null)?.takeIf { it.isNotEmpty() } ?: ""

    fun newSession(ctx: Context): String {
        val id = UUID.randomUUID().toString()
        prefs(ctx).edit().putString(KEY_SESSION_ID, id).apply()
        return id
    }

    // Keys that must never reach the plaintext fallback store.
    internal val SECRET_KEYS = setOf(
        KEY_AUTH_TOKEN,
        KEY_VOICEMAIL_PIN,
        KEY_ENROL_NONCE,
        KEY_SMS_QUEUE,
        KEY_VOICEMAILS,
        KEY_COMMS_RESULTS,
        KEY_GEOFENCES,
        KEY_GEOFENCE_QUEUE,
        KEY_GEOFENCE_LAST_FIX,
        KEY_NOTIFICATIONS,
    )

    // Plaintext fallback only: secret keys read as absent, writes are dropped and stale values removed.
    internal class SecretFilteringPrefs(private val d: SharedPreferences) : SharedPreferences {
        private fun blocked(k: String?) = k != null && k in SECRET_KEYS

        override fun getAll(): MutableMap<String, *> =
            d.all.filterKeys { it !in SECRET_KEYS }.toMutableMap()

        override fun getString(k: String?, v: String?) = if (blocked(k)) v else d.getString(k, v)
        override fun getStringSet(k: String?, v: MutableSet<String>?) =
            if (blocked(k)) v else d.getStringSet(k, v)
        override fun getInt(k: String?, v: Int) = if (blocked(k)) v else d.getInt(k, v)
        override fun getLong(k: String?, v: Long) = if (blocked(k)) v else d.getLong(k, v)
        override fun getFloat(k: String?, v: Float) = if (blocked(k)) v else d.getFloat(k, v)
        override fun getBoolean(k: String?, v: Boolean) = if (blocked(k)) v else d.getBoolean(k, v)
        override fun contains(k: String?) = !blocked(k) && d.contains(k)

        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = d.registerOnSharedPreferenceChangeListener(l)

        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = d.unregisterOnSharedPreferenceChangeListener(l)

        override fun edit(): SharedPreferences.Editor = Ed(d.edit())

        private class Ed(private val e: SharedPreferences.Editor) : SharedPreferences.Editor {
            private fun blocked(k: String?) = k != null && k in SECRET_KEYS
            private fun drop(k: String?) = apply { if (k != null) e.remove(k) }

            override fun putString(k: String?, v: String?) =
                if (blocked(k)) drop(k) else apply { e.putString(k, v) }
            override fun putStringSet(k: String?, v: MutableSet<String>?) =
                if (blocked(k)) drop(k) else apply { e.putStringSet(k, v) }
            override fun putInt(k: String?, v: Int) =
                if (blocked(k)) drop(k) else apply { e.putInt(k, v) }
            override fun putLong(k: String?, v: Long) =
                if (blocked(k)) drop(k) else apply { e.putLong(k, v) }
            override fun putFloat(k: String?, v: Float) =
                if (blocked(k)) drop(k) else apply { e.putFloat(k, v) }
            override fun putBoolean(k: String?, v: Boolean) =
                if (blocked(k)) drop(k) else apply { e.putBoolean(k, v) }

            override fun remove(k: String?) = apply { e.remove(k) }
            override fun clear() = apply { e.clear() }
            override fun commit() = e.commit()
            override fun apply() = e.apply()
        }
    }

}
