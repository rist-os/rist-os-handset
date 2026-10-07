package watch.rist.assistant

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.NetworkErrorException
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder

/**
 * The Rist account exists only to own the mirrored contacts ([ContactsMirror]). It has no
 * password and no sign-in: the phone's pairing token is what authorises a pull. Adding one by hand
 * from Settings is refused; the app adds its own.
 */
class RistAuthenticator(ctx: Context) : AbstractAccountAuthenticator(ctx) {
    override fun editProperties(r: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null
    override fun addAccount(
        r: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?,
        requiredFeatures: Array<out String>?, options: Bundle?,
    ): Bundle = Bundle().apply {
        putInt(android.accounts.AccountManager.KEY_ERROR_CODE, android.accounts.AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(android.accounts.AccountManager.KEY_ERROR_MESSAGE, "Rist adds this account itself")
    }
    override fun confirmCredentials(r: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null
    @Throws(NetworkErrorException::class)
    override fun getAuthToken(r: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle? = null
    override fun getAuthTokenLabel(authTokenType: String?): String? = null
    override fun updateCredentials(r: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle? = null
    override fun hasFeatures(r: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false) }
}

class RistAuthenticatorService : Service() {
    private val authenticator by lazy { RistAuthenticator(this) }
    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}

/** "Sync now" from the system's account screen runs the same pull the app runs. */
class RistSyncAdapter(ctx: Context) : AbstractThreadedSyncAdapter(ctx, true) {
    override fun onPerformSync(
        account: Account?, extras: Bundle?, authority: String?,
        provider: ContentProviderClient?, syncResult: SyncResult,
    ) {
        // An exception escaping here would take the whole app down with the sync thread.
        when (runCatching { ContactsSync.syncBlocking(context.applicationContext) }.getOrNull()) {
            is ContactsSync.Outcome.Failed, null -> syncResult.stats.numIoExceptions++
            else -> Unit
        }
    }
}

class RistSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = adapter(this).syncAdapterBinder

    companion object {
        @Volatile private var held: RistSyncAdapter? = null
        @Synchronized private fun adapter(ctx: Context): RistSyncAdapter =
            held ?: RistSyncAdapter(ctx.applicationContext).also { held = it }
    }
}
