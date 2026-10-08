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
 * Every row is written as the account's sync adapter, so a pull never marks a row changed and is
 * never sent back. SOURCE_ID is the backend's contact id; RawContacts.SYNC1 its clock, SYNC2 the
 * method ids written, SYNC3 the key of a person made on the phone whose id has not come back yet;
 * Data.SYNC1 a method's id, so an edit in the Contacts app updates that method rather than adding
 * one. The dialer, the messages app and [CallerId] all find these names through the provider's
 * own number matching.
 *
 * The account is writable in the Contacts app (res/xml/contacts.xml). A row the owner made there
 * has no SOURCE_ID and is never removed by a pull; [ContactsPush] sends it first.
 */
object ContactsMirror {

    private const val TAG = "RistContacts"

    /** Must match res/xml/authenticator.xml and res/xml/syncadapter.xml. */
    const val ACCOUNT_TYPE = "watch.rist.assistant"
    const val ACCOUNT_NAME = "Rist"

    /** Operations per applyBatch; a contact's rows always go in one batch. */
    private const val BATCH_OPS = 300

    /** The data kinds a record carries. Anything else on a row (a photo) is left alone. */
    internal val CARRIED_MIMES = listOf(
        StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE,
        Nickname.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE,
    )

    internal val CARRIED_SELECTION =
        "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${CARRIED_MIMES.joinToString(",") { "?" }})"

    val account: Account get() = Account(ACCOUNT_NAME, ACCOUNT_TYPE)

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun hasAccount(ctx: Context): Boolean = runCatching {
        AccountManager.get(ctx).getAccountsByType(ACCOUNT_TYPE).any { it.name == ACCOUNT_NAME }
    }.getOrDefault(true)

    /** Adds the Rist account if it is missing. The provider drops the rows of an account that is gone. */
    fun ensureAccount(ctx: Context): Boolean = runCatching {
        val am = AccountManager.get(ctx)
        if (hasAccount(ctx)) return@runCatching true
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
    }.onFailure { Log.w(TAG, "could not add the Rist contacts account: ${it.javaClass.simpleName}") }.getOrDefault(false)

    internal fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
        .build()

    /** What the account holds, split by how a pull may treat each row. */
    internal data class Held(
        /** SOURCE_ID to raw contact id. A row with no id, or a second row for one person, is keyed "#rawId" so a full pull removes it. */
        val bySource: Map<String, Long>,
        /** Rows made on the phone and already sent: the key they were sent under, to raw contact id. */
        val byKey: Map<String, Long>,
        /** Rows the owner changed on the phone that no push has settled yet. A pull leaves them be. */
        val dirty: Set<Long> = emptySet(),
    )

    internal fun held(ctx: Context): Held {
        val bySource = HashMap<String, Long>()
        val byKey = HashMap<String, Long>()
        val dirtyIds = HashSet<Long>()
        ctx.contentResolver.query(
            asSyncAdapter(RawContacts.CONTENT_URI),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.DIRTY, RawContacts.SYNC3),
            "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?",
            arrayOf(ACCOUNT_TYPE, ACCOUNT_NAME), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val sid = c.getString(1)
                val dirty = !c.isNull(2) && c.getInt(2) != 0
                val key = c.getString(3)
                if (dirty) dirtyIds += id
                when {
                    !sid.isNullOrBlank() && sid !in bySource -> bySource[sid] = id
                    !sid.isNullOrBlank() -> bySource["#$id"] = id
                    // Made on the phone and sent; the pull carrying its id claims it.
                    !key.isNullOrBlank() -> byKey[key] = id
                    // Made on the phone and not sent yet: never a pull's to remove.
                    dirty -> Unit
                    else -> bySource["#$id"] = id
                }
            }
        }
        return Held(bySource, byKey, dirtyIds)
    }

    /** SOURCE_ID to raw contact id, for every row a pull may replace or remove. */
    internal fun existing(ctx: Context): Map<String, Long> = held(ctx).bySource

    /**
     * Writes one pull. On [full] the account ends up holding exactly [records], plus anything made
     * on the phone and not yet sent; otherwise they are upserted on SOURCE_ID and [deletedIds]
     * removed. A record that carries the key a phone-made row was sent under takes that row over.
     * Returns false if anything could not be written, or a row was held back for an unsent edit; the
     * next sync is then a full one.
     */
    fun apply(ctx: Context, full: Boolean, records: Collection<ContactRecord>, deletedIds: Collection<String>): Boolean {
        if (!canWrite(ctx)) {
            Log.w(TAG, "no contacts permission; the system address book was not updated")
            return false
        }
        if (!ensureAccount(ctx)) return false
        return runCatching {
            val held = held(ctx)
            val have = HashMap(held.bySource)
            val claims = HashMap<String, Long>()
            val dropped = ArrayList<Long>()
            for (r in records) {
                if (r.id.isBlank() || r.externalKey.isBlank()) continue
                val born = held.byKey[r.externalKey] ?: continue
                // Changed again on the phone since it was sent: the next push names it.
                if (born in held.dirty) continue
                // The backend may have folded a phone-made person into somebody already here; then
                // the phone's row is the second copy.
                if (r.id in have) dropped += born else claims[r.id] = born
            }
            val keep = records.mapTo(HashSet()) { it.id }
            val gone = if (full) have.keys.filter { it !in keep } else deletedIds.filter { it in have }
            // Each group is built for the position it lands at, since back-references are absolute.
            val groups = ArrayList<(Int) -> List<ContentProviderOperation>>()
            for (id in gone.mapNotNull { have[it] } + dropped) {
                groups += { _ ->
                    listOf(
                        ContentProviderOperation.newDelete(
                            asSyncAdapter(android.content.ContentUris.withAppendedId(RawContacts.CONTENT_URI, id))
                        ).build()
                    )
                }
            }
            var heldBack = 0
            for (r in records) {
                if (r.id.isBlank()) continue
                val claimed = claims[r.id]
                val rawId = have[r.id] ?: claimed
                // An edit on the phone that no push has settled (it failed, was refused, or was made
                // while the push was on the way) is not written over; the next push sends it.
                if (rawId != null && rawId in held.dirty) { heldBack++; continue }
                groups += { base -> upsertOps(r, rawId, base, claim = claimed != null && have[r.id] == null) }
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
            Log.i(TAG, "address book: ${records.size} written, ${gone.size + dropped.size} removed, " +
                "${claims.size} made here now named, $heldBack held for the next push (full=$full)")
            // Held back: the next pull is a full one, after the push, so the two sides meet again.
            heldBack == 0
        }.onFailure {
            // The class only: a provider error can quote the row it refused.
            Log.w(TAG, "could not write the system address book: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    /** Ops for one person; a new raw contact's insert sits at [base] in its batch. [claim]: stamp the id on a row made on the phone. */
    internal fun upsertOps(r: ContactRecord, rawId: Long?, base: Int, claim: Boolean = false): List<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        val dataUri = asSyncAdapter(Data.CONTENT_URI)
        fun data(mime: String): ContentProviderOperation.Builder =
            ContentProviderOperation.newInsert(dataUri).withValue(Data.MIMETYPE, mime).also {
                if (rawId != null) it.withValue(Data.RAW_CONTACT_ID, rawId)
                else it.withValueBackReference(Data.RAW_CONTACT_ID, base)
            }
        val methodIds = r.methodsList.filter { it.value.isNotBlank() && it.id.isNotBlank() }.joinToString(",") { it.id }
        if (rawId == null) {
            ops += ContentProviderOperation.newInsert(asSyncAdapter(RawContacts.CONTENT_URI))
                .withValue(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .withValue(RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                .withValue(RawContacts.SOURCE_ID, r.id)
                .withValue(RawContacts.SYNC1, r.updatedAtMs.toString())
                .withValue(RawContacts.SYNC2, methodIds)
                .build()
        } else {
            ops += ContentProviderOperation.newUpdate(
                asSyncAdapter(android.content.ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId))
            ).withValue(RawContacts.SYNC1, r.updatedAtMs.toString())
                .withValue(RawContacts.SYNC2, methodIds)
                .apply { if (claim) withValue(RawContacts.SOURCE_ID, r.id).withValue(RawContacts.SYNC3, null) }
                .build()
            // Each record arrives complete, so the kinds it carries are replaced rather than
            // merged. Kinds it does not carry (a photo set on the phone) stay.
            ops += ContentProviderOperation.newDelete(dataUri)
                .withSelection(CARRIED_SELECTION, arrayOf(rawId.toString()) + CARRIED_MIMES).build()
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
                    .withValue(Data.SYNC1, m.id)
                    .build()
                "email" -> ops += data(Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, m.value)
                    .withValue(Email.TYPE, emailType(m))
                    .apply { if (emailType(m) == Email.TYPE_CUSTOM) withValue(Email.LABEL, m.label) }
                    .withValue(Data.IS_PRIMARY, if (m.isPrimary) 1 else 0)
                    .withValue(Data.SYNC1, m.id)
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
