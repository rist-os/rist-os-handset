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
    private const val KEY_AUTO_TZ = "auto_time_zone_from_location"
    private const val KEY_VOICE_LEVEL = "voice_level"
    private const val KEY_AUTO_TZ_PENDING = "auto_time_zone_pending"
    private const val KEY_AUTO_TZ_PENDING_AT = "auto_time_zone_pending_at"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_CARRIER_VM_WAITING = "carrier_vm_waiting"
    private const val KEY_VM_DISMISSED_AT = "carrier_vm_dismissed_at"
    private const val KEY_VM_DISMISSED_MS = "carrier_vm_dismissed_ms"
    private const val KEY_VOICEMAIL_PIN = "voicemail_pin"
    private const val KEY_SETUP_DONE = "setup_complete"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_THEME = "theme_id"
    private const val KEY_BRIGHTNESS = "screen_brightness"
    private const val KEY_TRANSCRIPT_MAX = "transcript_max_entries"
    private const val KEY_TRANSCRIPT_AGE_MS = "transcript_max_age_ms"
    private const val KEY_SESSION_ID = "session_id"
    /** Older builds kept an idle clock and a reply flag beside the id; removed on first use. */
    private const val LEGACY_SESSION_AT = "session_last_at"
    private const val LEGACY_AWAITING_REPLY = "awaiting_reply"
    /** The account the conversation belongs to: the `user_id` the enroll answer named. */
    private const val KEY_SESSION_ACCOUNT = "session_account"
    /** The session id a user-started new conversation belongs to, until a reply has been had. */
    private const val KEY_NEW_CONVERSATION_FOR = "new_conversation_for"
    private const val KEY_KIOSK_STAMP = "kiosk_provisioned_vc"
    private const val KEY_TIMERS = "running_timers"
    private const val KEY_SMS_QUEUE = "sms_queue"
    private const val KEY_SEEN_COMMS = "seen_comms_ids"
    private const val KEY_ENROL_NONCE = "enrol_nonce"
    private const val KEY_ENROL_SENT_AT = "enrol_sent_at"
    private const val KEY_ENROL_ATTEMPTS = "enrol_attempts"
    private const val KEY_RIST_NUMBER = "rist_number"
    // Renamed when only an explicit signal could latch it: the old key was set by any 403.
    private const val KEY_ENROL_REVOKED = "enrol_revoked_explicit"
    private const val KEY_CREDENTIAL_REJECTED = "credential_rejected"
    private const val KEY_BILLING_LAPSE = "billing_lapse"
    private const val KEY_BILLING_RENEW = "billing_renew_url"
    private const val KEY_BILLING_PORTAL = "billing_portal_path"
    private const val KEY_BILLING_NO_PORTAL = "billing_no_portal"
    private const val KEY_BILLING_ACCOUNT_URL = "billing_account_url"
    private const val KEY_BILLING_LINE = "billing_line"
    private const val KEY_FEATURES = "features"
    private const val KEY_HOME_BOXES = "home_boxes"
    private const val KEY_DESIGN_SPEC = "design_spec"
    private const val KEY_DESIGN_PREVIOUS = "design_previous"
    private const val KEY_DESIGN_STATE = "design_state"
    private const val KEY_DESIGN_POST = "design_post"
    private const val KEY_SETTINGS_VERSION = "settings_version"
    private const val KEY_DESIGN_MIGRATED = "design_migrated"
    private const val KEY_BOX_EDIT_QUEUE = "box_edit_queue"
    private const val KEY_ITEM_CHECK_QUEUE = "item_check_queue"
    private const val KEY_CHECKLIST_BOXES_SEEN = "checklist_boxes_seen"
    private const val KEY_NOTE_EDIT_QUEUE = "note_edit_queue"
    private const val KEY_CONTACTS_SYNC_OFF = "contacts_sync_off"
    private const val KEY_CONTACTS_REFUSED = "contacts_refused"
    private const val KEY_CONTACTS_CURSOR = "contacts_cursor"
    private const val KEY_CONTACTS_NEEDS_FULL = "contacts_needs_full"
    private const val KEY_CONTACTS_SYNCED_AT = "contacts_synced_at"
    private const val KEY_CONTACTS_PUSH_TAG = "contacts_push_tag"
    private const val KEY_CONTACTS_ADOPT = "contacts_adopt"
    private const val KEY_TEXTS_ON_ASK_OFF = "texts_on_ask_off"
    private const val KEY_REMOVED_NOTICE_SHOWN = "removed_notice_shown"
    private const val KEY_COMMS_RESULTS = "comms_results"
    private const val KEY_VOICEMAILS = "voicemails"
    private const val KEY_SETTINGS_STATE = "settings_state"
    private const val KEY_ALARM_VOLUME = "alarm_volume_pct"
    private const val KEY_VM_COUNT = "voicemail_count"
    private const val KEY_NOTIFICATIONS = "notification_queue"
    private const val KEY_MAIL_UNREAD = "mail_unread"
    private const val KEY_MAIL_ACK = "mail_acknowledged"
    private const val KEY_ALARMS = "alarms"
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

    /**
     * Robolectric has no AndroidKeyStore; this lets a test hold the secret keys in plain prefs.
     * Refuses to run anywhere but Robolectric, so no code path can put secrets in plain prefs on a phone.
     */
    internal fun usePlainPrefsForTest(ctx: Context) {
        check(isRobolectric()) { "plain prefs are for Robolectric tests only" }
        cached = ctx.applicationContext.getSharedPreferences("$PREFS.test", Context.MODE_PRIVATE)
    }

    internal fun forgetPrefsForTest() { cached = null }

    internal fun isRobolectric(fingerprint: String? = android.os.Build.FINGERPRINT): Boolean =
        fingerprint == "robolectric"

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

    /** The turn path. Every other path (wake, items, enroll, ...) is derived from the host. */
    internal const val DEVICE_PATH = "/v1/device"

    /**
     * The endpoint in its one canonical form, `<scheme>://<host>[:port]<path>`, where a bare host
     * or a URL with no path (or only "/") gets [DEVICE_PATH]. A URL already carrying a path keeps it,
     * less any trailing slash, so `/v1/device` is never doubled. Null when it is not an http(s) URL
     * with a host; a scheme is never guessed.
     */
    internal fun normalizeEndpoint(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val uri = runCatching { java.net.URI(t) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "$scheme://$authority" + path.ifEmpty { DEVICE_PATH }
    }

    /** Normalized when it parses; otherwise as stored, so an odd value is never silently dropped. */
    internal fun canonical(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() }?.let { normalizeEndpoint(it) ?: it.trim() }

    fun backendUrl(ctx: Context): String {
        val stored = canonical(prefs(ctx).getString(KEY_BACKEND, null))
        val default = canonical(deploy(ctx).first).orEmpty()
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
        val next = canonical(url).orEmpty()
        if (next != canonical(prefs(ctx).getString(KEY_BACKEND, "")).orEmpty()) clearAuthTokenForHostChange(ctx)
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
        // A revocation or a lapse was one backend's word about this device, not the next one's.
        prefs(ctx).edit().remove(KEY_AUTH_TOKEN).remove(KEY_ENROL_REVOKED).remove(KEY_BILLING_LAPSE)
            .remove(KEY_BILLING_RENEW).remove(KEY_BILLING_PORTAL).remove(KEY_BILLING_NO_PORTAL).remove(KEY_BILLING_ACCOUNT_URL).remove(KEY_BILLING_LINE)
            .remove(KEY_FEATURES).remove(KEY_CONTACTS_SYNC_OFF).remove(KEY_REMOVED_NOTICE_SHOWN)
            .remove(KEY_CONTACTS_REFUSED).remove(KEY_CONTACTS_CURSOR).apply()
        if (had > 0) {
            android.util.Log.i("RistConfig", "backend endpoint changed; cleared the device token ($had chars)")
        }
    }

    fun defaultBackendUrl(ctx: Context): String = deploy(ctx).first
    fun defaultPushUrl(ctx: Context): String = deploy(ctx).second

    // A count cap as well as the age one, so the transcript file stays a size the app can
    // rewrite on every message. It was 200, which made "Forever" untrue the moment somebody
    // crossed it; 1000 is roughly 150 KB of JSON and still bounded.
    const val DEFAULT_TRANSCRIPT_MAX_ENTRIES = 1000

    // The age limit, in ms; 0 means keep them until cleared. See [Retention] for the choices.
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

    fun billingLapse(ctx: Context): String = prefs(ctx).getString(KEY_BILLING_LAPSE, "") ?: ""
    fun billingRenewUrl(ctx: Context): String = prefs(ctx).getString(KEY_BILLING_RENEW, "") ?: ""
    fun billingPortalPath(ctx: Context): String = prefs(ctx).getString(KEY_BILLING_PORTAL, "") ?: ""
    fun billingNoPortal(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BILLING_NO_PORTAL, false)
    fun setBillingNoPortal(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_BILLING_NO_PORTAL, v).apply() }

    fun billingAccountUrl(ctx: Context): String = prefs(ctx).getString(KEY_BILLING_ACCOUNT_URL, "") ?: ""

    fun billingLine(ctx: Context): String = prefs(ctx).getString(KEY_BILLING_LINE, "") ?: ""

    fun setBillingLapse(
        ctx: Context, reason: String, renewUrl: String, portalPath: String, accountUrl: String = "", line: String = "",
    ) {
        prefs(ctx).edit().putString(KEY_BILLING_LAPSE, reason).putString(KEY_BILLING_RENEW, renewUrl)
            .putString(KEY_BILLING_PORTAL, portalPath).putString(KEY_BILLING_ACCOUNT_URL, accountUrl)
            .putString(KEY_BILLING_LINE, line).apply()
    }

    fun clearBillingLapse(ctx: Context) {
        prefs(ctx).edit().remove(KEY_BILLING_LAPSE).remove(KEY_BILLING_RENEW)
            .remove(KEY_BILLING_PORTAL).remove(KEY_BILLING_NO_PORTAL).remove(KEY_BILLING_ACCOUNT_URL)
            .remove(KEY_BILLING_LINE).apply()
    }

    /** The last feature set the backend sent, as JSON; empty when none has ever arrived. */
    fun features(ctx: Context): String = prefs(ctx).getString(KEY_FEATURES, "") ?: ""
    fun setFeatures(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_FEATURES, json).apply() }

    /** The last home box set, as base64 protobuf bytes; empty when none has ever arrived. */
    fun homeBoxes(ctx: Context): String = prefs(ctx).getString(KEY_HOME_BOXES, "") ?: ""
    fun setHomeBoxes(ctx: Context, b64: String) { prefs(ctx).edit().putString(KEY_HOME_BOXES, b64).apply() }

    /** The applied DesignSpec, base64 protobuf; empty for the factory look. */
    fun designSpec(ctx: Context): String = prefs(ctx).getString(KEY_DESIGN_SPEC, "") ?: ""
    fun setDesignSpec(ctx: Context, b64: String) { prefs(ctx).edit().putString(KEY_DESIGN_SPEC, b64).apply() }
    /** The spec before it, kept so a design that will not draw can be undone on the phone. */
    fun designPrevious(ctx: Context): String = prefs(ctx).getString(KEY_DESIGN_PREVIOUS, "") ?: ""
    fun setDesignPrevious(ctx: Context, b64: String) { prefs(ctx).edit().putString(KEY_DESIGN_PREVIOUS, b64).apply() }
    /** The DesignState the next turn carries, base64 protobuf. */
    fun designState(ctx: Context): String = prefs(ctx).getString(KEY_DESIGN_STATE, "") ?: ""
    fun setDesignState(ctx: Context, b64: String) { prefs(ctx).edit().putString(KEY_DESIGN_STATE, b64).apply() }
    /** A look changed on the phone and not yet accepted by the backend, base64 protobuf. */
    fun designPost(ctx: Context): String = prefs(ctx).getString(KEY_DESIGN_POST, "") ?: ""
    fun setDesignPost(ctx: Context, b64: String) { prefs(ctx).edit().putString(KEY_DESIGN_POST, b64).apply() }
    /** The SettingsCommand.version last applied; 0 after a wipe. */
    fun settingsVersion(ctx: Context): Long = prefs(ctx).getLong(KEY_SETTINGS_VERSION, 0L)
    fun setSettingsVersion(ctx: Context, v: Long) { prefs(ctx).edit().putLong(KEY_SETTINGS_VERSION, v).apply() }
    /** Whether the theme picked before designs came from the backend was handed over. */
    fun designMigrated(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DESIGN_MIGRATED, false)
    fun setDesignMigrated(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_DESIGN_MIGRATED, v).apply() }

    /** Touch edits to the boxes not yet accepted by the backend, oldest first, as a JSON array. */
    fun boxEditQueue(ctx: Context): String = prefs(ctx).getString(KEY_BOX_EDIT_QUEUE, "") ?: ""
    fun setBoxEditQueue(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_BOX_EDIT_QUEUE, json).apply() }

    /** List ticks not yet accepted by the backend, latest state per item, as a JSON array. */
    fun itemCheckQueue(ctx: Context): String = prefs(ctx).getString(KEY_ITEM_CHECK_QUEUE, "") ?: ""
    fun setItemCheckQueue(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_ITEM_CHECK_QUEUE, json).apply() }

    /** Note edits not yet accepted by the backend, latest text per note, as a JSON array. */
    fun noteEditQueue(ctx: Context): String = prefs(ctx).getString(KEY_NOTE_EDIT_QUEUE, "") ?: ""
    fun setNoteEditQueue(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_NOTE_EDIT_QUEUE, json).apply() }

    /** Whether a box list has arrived since this phone first declared checklists. */
    fun checklistBoxesSeen(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CHECKLIST_BOXES_SEEN, false)
    fun setChecklistBoxesSeen(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_CHECKLIST_BOXES_SEEN, v).apply() }

    fun contactsSyncOff(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CONTACTS_SYNC_OFF, false)
    fun setContactsSyncOff(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_CONTACTS_SYNC_OFF, v).apply() }

    /** The backend answered a contacts route with "contacts off for this account". */
    fun contactsRefused(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CONTACTS_REFUSED, false)
    fun setContactsRefused(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_CONTACTS_REFUSED, v).apply() }
    /** The contacts cursor last applied to the mirror; empty = never synced. */
    fun contactsCursor(ctx: Context): String = prefs(ctx).getString(KEY_CONTACTS_CURSOR, "") ?: ""
    fun setContactsCursor(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_CONTACTS_CURSOR, v).apply() }
    /** A pull applied: its cursor and whether the next pull must be full, in one write. */
    fun setContactsApplied(ctx: Context, cursor: String, needsFull: Boolean) {
        prefs(ctx).edit().putString(KEY_CONTACTS_CURSOR, cursor).putBoolean(KEY_CONTACTS_NEEDS_FULL, needsFull).apply()
    }
    /** The next pull must be a full one: "Sync now", or the address book write did not land. */
    fun contactsNeedsFull(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CONTACTS_NEEDS_FULL, false)
    fun setContactsNeedsFull(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_CONTACTS_NEEDS_FULL, v).apply() }
    /** Wall-clock ms of the last pull applied; 0 = never. */
    fun contactsSyncedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_CONTACTS_SYNCED_AT, 0L)
    fun setContactsSyncedAt(ctx: Context, v: Long) { prefs(ctx).edit().putLong(KEY_CONTACTS_SYNCED_AT, v).apply() }

    /**
     * This install's tag for the keys of people made on the phone. Raw-contact ids restart on a
     * wiped or different phone, so a bare id could name somebody else's record on the backend.
     */
    fun contactsPushTag(ctx: Context): String {
        prefs(ctx).getString(KEY_CONTACTS_PUSH_TAG, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val tag = UUID.randomUUID().toString().replace("-", "").take(12)
        prefs(ctx).edit().putString(KEY_CONTACTS_PUSH_TAG, tag).apply()
        return tag
    }

    /** Device-account contacts the backend has taken, as "rawId:version" entries, until the Rist copy replaces them. */
    fun contactsAdopt(ctx: Context): String = prefs(ctx).getString(KEY_CONTACTS_ADOPT, "") ?: ""
    fun setContactsAdopt(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_CONTACTS_ADOPT, v).apply() }

    /**
     * The owner's "Let Rist read recent texts and calls when you ask" switch. On by default: the
     * texts go only when the backend asks during a turn the owner started, never on their own.
     */
    fun textsOnAsk(ctx: Context): Boolean = !prefs(ctx).getBoolean(KEY_TEXTS_ON_ASK_OFF, false)
    fun setTextsOnAsk(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(KEY_TEXTS_ON_ASK_OFF, !on).apply() }

    fun removedNoticeShown(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_REMOVED_NOTICE_SHOWN, false)
    fun setRemovedNoticeShown(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_REMOVED_NOTICE_SHOWN, v).apply() }

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

    fun alarms(ctx: Context): String = prefs(ctx).getString(KEY_ALARMS, "") ?: ""
    fun setAlarms(ctx: Context, json: String) { prefs(ctx).edit().putString(KEY_ALARMS, json).apply() }

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

    /** The carrier's message count when the voicemail row was dismissed; null = not dismissed. */
    fun voicemailDismissedAt(ctx: Context): Int? =
        prefs(ctx).takeIf { it.contains(KEY_VM_DISMISSED_AT) }?.getInt(KEY_VM_DISMISSED_AT, -1)

    /** When the voicemail row was dismissed, or 0 when it is not. */
    fun voicemailDismissedMs(ctx: Context): Long = prefs(ctx).getLong(KEY_VM_DISMISSED_MS, 0L)

    fun setVoicemailDismissedAt(ctx: Context, count: Int?) {
        prefs(ctx).edit().apply {
            if (count == null) {
                remove(KEY_VM_DISMISSED_AT); remove(KEY_VM_DISMISSED_MS)
            } else {
                putInt(KEY_VM_DISMISSED_AT, count); putLong(KEY_VM_DISMISSED_MS, System.currentTimeMillis())
            }
        }.apply()
    }

    fun isReplyVoiceEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_REPLY_VOICE, true)

    fun isHapticsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HAPTICS, true)

    fun setHapticsEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HAPTICS, enabled).apply()
    }

    fun setReplyVoiceEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_REPLY_VOICE, enabled).apply()
    }

    // On by default: a phone that keeps its home time zone abroad is wrong in a way nobody asked for.
    fun isAutoTimeZone(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_TZ, true)

    /**
     * RIST's own loudness for spoken replies, 0..[VOICE_LEVEL_MAX], used where Android will not
     * let it set the assistant volume. Replies then play as media, scaled by this.
     */
    const val VOICE_LEVEL_MAX = 15
    fun voiceLevel(ctx: Context): Int =
        prefs(ctx).getInt(KEY_VOICE_LEVEL, VOICE_LEVEL_MAX).coerceIn(0, VOICE_LEVEL_MAX)

    fun setVoiceLevel(ctx: Context, level: Int) {
        prefs(ctx).edit().putInt(KEY_VOICE_LEVEL, level.coerceIn(0, VOICE_LEVEL_MAX)).apply()
    }

    fun setAutoTimeZone(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_TZ, enabled).apply()
    }

    /** A zone seen once with the same offset as the current one; switched to if seen again. */
    fun autoTimeZonePending(ctx: Context): Pair<String, Long>? {
        val zone = prefs(ctx).getString(KEY_AUTO_TZ_PENDING, null) ?: return null
        return zone to prefs(ctx).getLong(KEY_AUTO_TZ_PENDING_AT, 0L)
    }

    fun setAutoTimeZonePending(ctx: Context, zone: String?, atMs: Long) {
        prefs(ctx).edit().apply {
            if (zone == null) remove(KEY_AUTO_TZ_PENDING).remove(KEY_AUTO_TZ_PENDING_AT)
            else putString(KEY_AUTO_TZ_PENDING, zone).putLong(KEY_AUTO_TZ_PENDING_AT, atMs)
        }.apply()
    }

    fun isSetupComplete(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupComplete(ctx: Context, done: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SETUP_DONE, done).apply()
    }

    fun deviceId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    /** Characters of the device id shown on the settings screen. */
    internal const val SHORT_DEVICE_ID_LEN = 12

    /** The first [SHORT_DEVICE_ID_LEN] characters, with an ellipsis when the id is longer. */
    internal fun shortDeviceId(id: String): String =
        if (id.length <= SHORT_DEVICE_ID_LEN) id else id.take(SHORT_DEVICE_ID_LEN) + "…"

    fun authToken(ctx: Context): String =
        prefs(ctx).getString(KEY_AUTH_TOKEN, "") ?: ""

    fun setAuthToken(ctx: Context, token: String) {
        val t = token.trim()
        val e = prefs(ctx).edit().putString(KEY_AUTH_TOKEN, t)
        // A new pairing may be a different account: its first contact pull must replace the
        // address book, never a delta on top of the last owner's people.
        if (t.isNotEmpty() && t != authToken(ctx)) e.remove(KEY_CONTACTS_CURSOR).putBoolean(KEY_CONTACTS_NEEDS_FULL, true)
        e.apply()
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

    /**
     * The conversation id. It has no time limit: it survives idle time, app restarts, reboots, app
     * updates, endpoint changes and re-pairing to the same account. Only [newSession] (the user's
     * "New conversation") and pairing to a different account ([onPairedAccount]) replace it.
     */
    fun sessionId(ctx: Context): String = synchronized(this) {
        val p = prefs(ctx)
        if (p.contains(LEGACY_SESSION_AT) || p.contains(LEGACY_AWAITING_REPLY)) {
            p.edit().remove(LEGACY_SESSION_AT).remove(LEGACY_AWAITING_REPLY).apply()
        }
        p.getString(KEY_SESSION_ID, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY_SESSION_ID, id).apply()
        return id
    }

    fun currentSessionId(ctx: Context): String =
        prefs(ctx).getString(KEY_SESSION_ID, null)?.takeIf { it.isNotEmpty() } ?: ""

    /**
     * A new session id. The server keeps one conversation per account whatever the id, so this
     * alone does not end it; [startNewConversation] is what the user's control calls. Used on its
     * own only for a change of account, whose conversation is a different one anyway.
     */
    fun newSession(ctx: Context): String = synchronized(this) {
        val id = UUID.randomUUID().toString()
        prefs(ctx).edit().putString(KEY_SESSION_ID, id).apply()
        return id
    }

    /**
     * The user's "New conversation": a new session id, and `new_conversation = true` on the next
     * request (Uploader.post) until a reply to it arrives. Nothing is sent now; with nothing said,
     * the flag waits for the next request.
     */
    fun startNewConversation(ctx: Context): String = synchronized(this) {
        val id = newSession(ctx)
        prefs(ctx).edit().putString(KEY_NEW_CONVERSATION_FOR, id).apply()
        return id
    }

    /**
     * Whether a request in [sessionId] must carry `new_conversation`. Tied to the id so a later
     * change of account (which rotates the id) drops the flag rather than ending the new account's
     * conversation.
     */
    internal fun newConversationPendingFor(ctx: Context, sessionId: String): Boolean =
        sessionId.isNotEmpty() && prefs(ctx).getString(KEY_NEW_CONVERSATION_FOR, null) == sessionId

    /** A request carrying the flag got its reply. A newer tap (a different id) stays pending. */
    internal fun clearNewConversation(ctx: Context, sessionId: String) = synchronized(this) {
        if (newConversationPendingFor(ctx, sessionId)) prefs(ctx).edit().remove(KEY_NEW_CONVERSATION_FOR).apply()
    }

    internal fun sessionAccount(ctx: Context): String =
        prefs(ctx).getString(KEY_SESSION_ACCOUNT, null).orEmpty()

    /**
     * A pairing succeeded for [userId]. The conversation carries over when it is the account it was
     * held with, and is replaced otherwise, including when either side is unknown (a phone paired by
     * an older build, or an answer without `user_id`): one account's conversation must never
     * continue under another. Returns whether a new conversation was started.
     */
    internal fun onPairedAccount(ctx: Context, userId: String): Boolean = synchronized(this) {
        val now = userId.trim()
        val before = sessionAccount(ctx)
        val same = now.isNotEmpty() && now == before
        if (!same) {
            newSession(ctx)
            Log.i(TAG, "paired to ${if (before.isEmpty()) "an unrecorded" else "a different"} account; new conversation")
        }
        prefs(ctx).edit().putString(KEY_SESSION_ACCOUNT, now).apply()
        return !same
    }

    // Keys that must never reach the plaintext fallback store.
    internal val SECRET_KEYS = setOf(
        KEY_AUTH_TOKEN,
        KEY_VOICEMAIL_PIN,
        KEY_ENROL_NONCE,
        KEY_SMS_QUEUE,
        KEY_VOICEMAILS,
        KEY_COMMS_RESULTS,
        KEY_ALARMS,
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
