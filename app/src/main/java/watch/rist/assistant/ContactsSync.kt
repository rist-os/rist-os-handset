package watch.rist.assistant

import android.content.Context
import android.util.Log

/**
 * The rules contact sync must follow. The sync itself (GET/POST /v1/contacts, the backend's
 * contacts_sync.md) is not built on this phone yet; whatever builds it asks [allowed] before every
 * pull or push and passes every refusal through [onRefused].
 *
 * Contacts off stops syncing and nothing else: the phone's address book stays as it is, because
 * caller ID has to work with no network, and turning the feature off does not delete contacts.
 */
object ContactsSync {

    private const val TAG = "RistContacts"

    /**
     * PENDING: the answer proposed for a sync route when the account has contacts off. It is not
     * 403, because 403 means this phone was removed from the account and nothing else.
     */
    const val FEATURE_OFF_STATUS = 409

    enum class Refusal { FEATURE_OFF, CREDENTIAL_DEAD, REVOKED, RETRY }

    internal fun classify(code: Int): Refusal? = when {
        code in 200..299 -> null
        code == FEATURE_OFF_STATUS -> Refusal.FEATURE_OFF
        code == 401 -> Refusal.CREDENTIAL_DEAD
        code == 403 -> Refusal.REVOKED
        else -> Refusal.RETRY
    }

    fun allowed(ctx: Context): Boolean =
        Features.isOn(ctx, Features.Id.CONTACTS) && !Config.contactsSyncOff(ctx)

    fun onRefused(ctx: Context, code: Int) {
        when (classify(code)) {
            Refusal.FEATURE_OFF -> onFeatureOff(ctx)
            Refusal.CREDENTIAL_DEAD -> Enrolment.onCredentialDead(ctx)
            Refusal.REVOKED -> Enrolment.onRevoked(ctx)
            Refusal.RETRY, null -> Unit
        }
    }

    fun onFeatureOff(ctx: Context) {
        if (!Config.contactsSyncOff(ctx)) Log.i(TAG, "contacts is off for this account; syncing stops, the address book stays")
        Config.setContactsSyncOff(ctx, true)
    }

    fun onFeatureOn(ctx: Context) {
        if (Config.contactsSyncOff(ctx)) Log.i(TAG, "contacts is on again; syncing may resume")
        Config.setContactsSyncOff(ctx, false)
    }
}
