package watch.rist.assistant

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Log
import rist.v1.ContactMethod
import rist.v1.ContactRecord

/**
 * The backend's contacts, mirrored into the system Contacts provider under a Rist account.
 *
 * Every row is written as the account's sync adapter, so the entries are Rist's alone: replaced or
 * removed without ever touching a contact the owner made by hand, and the provider keeps them for
 * as long as the account exists. SOURCE_ID is the backend's contact id. The dialer, the messages
 * app and [CallerId] all find these names through the provider's own number matching.
 */
object ContactsMirror {

    private const val TAG = "RistContacts"

    /** Must match res/xml/authenticator.xml and res/xml/syncadapter.xml. */
    const val ACCOUNT_TYPE = "watch.rist.assistant"
    const val ACCOUNT_NAME = "Rist"

    /** Operations per applyBatch; a contact's rows always go in one batch. */
    private const val BATCH_OPS = 300

    val account: Account get() = Account(ACCOUNT_NAME, ACCOUNT_TYPE)

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Adds the Rist account if it is missing. The provider drops the rows of an account that is gone. */
    fun ensureAccount(ctx: Context): Boolean = runCatching {
        val am = AccountManager.get(ctx)
        if (am.getAccountsByType(ACCOUNT_TYPE).any { it.name == ACCOUNT_NAME }) return@runCatching true
        val added = am.addAccountExplicitly(account, null, null)
        if (added) {
            android.content.ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
            android.content.ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, false)
            runCatching {
                // Show the account's contacts in the Contacts app; there are no groups to pick.
                ctx.contentResolver.insert(
                    asSyncAdapter(ContactsContract.Settings.CONTENT_URI),
                    ContentValues().apply {
                        put(ContactsContract.Settings.ACCOUNT_NAME, ACCOUNT_NAME)
                        put(ContactsContract.Settings.ACCOUNT_TYPE, ACCOUNT_TYPE)
                        put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
                    },
                )
            }
            Log.i(TAG, "added the Rist contacts account")
        } else {
            Log.w(TAG, "the system refused the Rist contacts account")
        }
        added
    }.onFailure { Log.w(TAG, "could not add the Rist contacts account", it) }.getOrDefault(false)

    internal fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
        .build()

    /** SOURCE_ID to raw contact id, for every row this account holds. */
    internal fun existing(ctx: Context): Map<String, Long> {
        val out = HashMap<String, Long>()
        ctx.contentResolver.query(
            asSyncAdapter(RawContacts.CONTENT_URI),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID),
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(ACCOUNT_TYPE, ACCOUNT_NAME), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val sid = c.getString(1)
                if (sid.isNullOrBlank()) out["#" + c.getLong(0)] = c.getLong(0) else out[sid] = c.getLong(0)
            }
        }
        return out
    }

    /**
     * Writes one pull. On [full] the account ends up holding exactly [records]; otherwise they are
     * upserted on SOURCE_ID and [deletedIds] removed. Returns false if anything could not be
     * written, in which case the next sync should be a full one.
     */
    fun apply(ctx: Context, full: Boolean, records: Collection<ContactRecord>, deletedIds: Collection<String>): Boolean {
        if (!canWrite(ctx)) {
            Log.w(TAG, "no contacts permission; the system address book was not updated")
            return false
        }
        if (!ensureAccount(ctx)) return false
        return runCatching {
            val have = existing(ctx)
            val keep = records.mapTo(HashSet()) { it.id }
            val gone = if (full) have.keys.filter { it !in keep } else deletedIds.filter { it in have }
            // Each group is built for the position it lands at, since back-references are absolute.
            val groups = ArrayList<(Int) -> List<ContentProviderOperation>>()
            for (sid in gone) {
                val id = have[sid] ?: continue
                groups += { _ ->
                    listOf(
                        ContentProviderOperation.newDelete(
                            asSyncAdapter(android.content.ContentUris.withAppendedId(RawContacts.CONTENT_URI, id))
                        ).build()
                    )
                }
            }
            for (r in records) {
                if (r.id.isBlank()) continue
                val rawId = have[r.id]
                groups += { base -> upsertOps(r, rawId, base) }
            }
            var batch = ArrayList<ContentProviderOperation>()
            fun flush() {
                if (batch.isEmpty()) return
                ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, batch)
                batch = ArrayList()
            }
            for (g in groups) {
                if (batch.size + g(0).size > BATCH_OPS) flush()
                batch.addAll(g(batch.size))
            }
            flush()
            Log.i(TAG, "address book: ${records.size} written, ${gone.size} removed (full=$full)")
            true
        }.onFailure { Log.w(TAG, "could not write the system address book", it) }.getOrDefault(false)
    }

    /** Ops for one person; a new raw contact's insert sits at [base] in its batch. */
    internal fun upsertOps(r: ContactRecord, rawId: Long?, base: Int): List<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        val dataUri = asSyncAdapter(Data.CONTENT_URI)
        fun data(mime: String): ContentProviderOperation.Builder =
            ContentProviderOperation.newInsert(dataUri).withValue(Data.MIMETYPE, mime).also {
                if (rawId != null) it.withValue(Data.RAW_CONTACT_ID, rawId)
                else it.withValueBackReference(Data.RAW_CONTACT_ID, base)
            }
        if (rawId == null) {
            ops += ContentProviderOperation.newInsert(asSyncAdapter(RawContacts.CONTENT_URI))
                .withValue(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .withValue(RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                .withValue(RawContacts.SOURCE_ID, r.id)
                .withValue(RawContacts.SYNC1, r.updatedAtMs.toString())
                .build()
        } else {
            ops += ContentProviderOperation.newUpdate(
                asSyncAdapter(android.content.ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId))
            ).withValue(RawContacts.SYNC1, r.updatedAtMs.toString()).build()
            // Each record arrives complete, so its rows are replaced rather than merged.
            ops += ContentProviderOperation.newDelete(dataUri)
                .withSelection("${Data.RAW_CONTACT_ID} = ?", arrayOf(rawId.toString())).build()
        }
        if (r.displayName.isNotBlank()) {
            ops += data(StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, r.displayName).build()
        }
        for (m in r.methodsList) {
            if (m.value.isBlank()) continue
            when (m.kind.lowercase()) {
                "phone" -> ops += data(Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, m.value)
                    .apply { if (m.value.startsWith("+")) withValue(Phone.NORMALIZED_NUMBER, m.value) }
                    .withValue(Phone.TYPE, phoneType(m))
                    .apply { if (phoneType(m) == Phone.TYPE_CUSTOM) withValue(Phone.LABEL, m.label) }
                    .withValue(Data.IS_PRIMARY, if (m.isPrimary) 1 else 0)
                    .build()
                "email" -> ops += data(Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, m.value)
                    .withValue(Email.TYPE, emailType(m))
                    .apply { if (emailType(m) == Email.TYPE_CUSTOM) withValue(Email.LABEL, m.label) }
                    .withValue(Data.IS_PRIMARY, if (m.isPrimary) 1 else 0)
                    .build()
                else -> Unit
            }
        }
        for (a in r.aliasesList) {
            if (a.isBlank()) continue
            ops += data(Nickname.CONTENT_ITEM_TYPE).withValue(Nickname.NAME, a).build()
        }
        if (r.description.isNotBlank()) {
            ops += data(Note.CONTENT_ITEM_TYPE).withValue(Note.NOTE, r.description).build()
        }
        return ops
    }

    private fun phoneType(m: ContactMethod): Int = when (m.label.trim().lowercase()) {
        "mobile", "cell", "" -> Phone.TYPE_MOBILE
        "home" -> Phone.TYPE_HOME
        "work" -> Phone.TYPE_WORK
        "main" -> Phone.TYPE_MAIN
        "other" -> Phone.TYPE_OTHER
        else -> Phone.TYPE_CUSTOM
    }

    private fun emailType(m: ContactMethod): Int = when (m.label.trim().lowercase()) {
        "home", "personal", "" -> Email.TYPE_HOME
        "work" -> Email.TYPE_WORK
        "other" -> Email.TYPE_OTHER
        else -> Email.TYPE_CUSTOM
    }
}
