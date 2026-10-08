package watch.rist.assistant

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts

/**
 * Just enough of the Contacts provider for contact sync: raw contacts and data rows, by id and by
 * account, with the provider's bookkeeping a sync adapter relies on. A write that is not the sync
 * adapter's marks the raw contact DIRTY and moves its VERSION, and such a delete on a synced
 * account only marks it DELETED. It does no number matching, so a name found in a test came from
 * Rist's own index.
 */
class FakeContactsProvider : ContentProvider() {
    val raw = LinkedHashMap<Long, ContentValues>()
    val data = LinkedHashMap<Long, ContentValues>()
    private var next = 1L

    override fun onCreate() = true

    fun sourceIds(): Set<String> = raw.values.mapNotNull { it.getAsString(RawContacts.SOURCE_ID) }.toSet()

    private fun rawIdOf(sid: String): Long = raw.entries.first { it.value.getAsString(RawContacts.SOURCE_ID) == sid }.key

    private fun rows(sid: String, mime: String) =
        data.values.filter { it.getAsLong(Data.RAW_CONTACT_ID) == rawIdOf(sid) && it.getAsString(Data.MIMETYPE) == mime }

    fun nameOf(sid: String): String? = rows(sid, StructuredName.CONTENT_ITEM_TYPE).firstOrNull()?.getAsString(StructuredName.DISPLAY_NAME)
    fun phonesOf(sid: String): List<String> = rows(sid, Phone.CONTENT_ITEM_TYPE).map { it.getAsString(Phone.NUMBER) }
    fun dataOf(rawId: Long): List<ContentValues> = data.values.filter { it.getAsLong(Data.RAW_CONTACT_ID) == rawId }

    // ---- what the owner does in the Contacts app (never as the sync adapter) ----

    /** A contact saved on the phone; [accountType] null is the phone's own account. Returns the raw id. */
    fun ownerCreates(accountType: String?, name: String, vararg phones: String, extraMime: String? = null): Long {
        val id = insertRaw(ContentValues().apply {
            put(RawContacts.ACCOUNT_TYPE, accountType)
            put(RawContacts.ACCOUNT_NAME, if (accountType == null) null else ContactsMirror.ACCOUNT_NAME)
        }, syncAdapter = false)
        insertData(id, StructuredName.CONTENT_ITEM_TYPE, name, syncAdapter = false)
        phones.forEach { insertData(id, Phone.CONTENT_ITEM_TYPE, it, type = Phone.TYPE_MOBILE, syncAdapter = false) }
        if (extraMime != null) insertData(id, extraMime, "kept", syncAdapter = false)
        return id
    }

    fun ownerRenames(rawId: Long, name: String) {
        data.values.first { it.getAsLong(Data.RAW_CONTACT_ID) == rawId && it.getAsString(Data.MIMETYPE) == StructuredName.CONTENT_ITEM_TYPE }
            .put(StructuredName.DISPLAY_NAME, name)
        touch(rawId)
    }

    fun ownerRemovesPhone(rawId: Long, number: String) {
        data.entries.removeAll { it.value.getAsLong(Data.RAW_CONTACT_ID) == rawId && it.value.getAsString(Phone.NUMBER) == number }
        touch(rawId)
    }

    fun ownerDeletes(rawId: Long) {
        delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), null, null)
    }

    fun rawIdOfSource(sid: String): Long = rawIdOf(sid)

    private fun touch(rawId: Long) {
        val v = raw[rawId] ?: return
        v.put(RawContacts.DIRTY, 1)
        v.put(RawContacts.VERSION, (v.getAsLong(RawContacts.VERSION) ?: 1L) + 1)
    }

    private fun insertRaw(values: ContentValues, syncAdapter: Boolean): Long {
        val id = next++
        raw[id] = ContentValues(values).apply {
            if (!containsKey(RawContacts.VERSION)) put(RawContacts.VERSION, 1L)
            if (!containsKey(RawContacts.DELETED)) put(RawContacts.DELETED, 0)
            put(RawContacts.DIRTY, if (syncAdapter) (values.getAsInteger(RawContacts.DIRTY) ?: 0) else 1)
        }
        return id
    }

    private fun insertData(rawId: Long, mime: String, d1: String, type: Int? = null, syncAdapter: Boolean) {
        data[next++] = ContentValues().apply {
            put(Data.RAW_CONTACT_ID, rawId); put(Data.MIMETYPE, mime); put(Data.DATA1, d1)
            if (type != null) put(Data.DATA2, type.toString())
        }
        if (!syncAdapter) touch(rawId)
    }

    private fun table(uri: Uri) = uri.pathSegments.firstOrNull()
    private fun isSyncAdapter(uri: Uri) = uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER) == "true"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val v = values ?: ContentValues()
        val id = when (table(uri)) {
            "raw_contacts" -> insertRaw(v, isSyncAdapter(uri))
            "data" -> {
                val id = next++
                data[id] = ContentValues(v)
                if (!isSyncAdapter(uri)) v.getAsLong(Data.RAW_CONTACT_ID)?.let { touch(it) }
                id
            }
            else -> return null
        }
        return ContentUris.withAppendedId(uri.buildUpon().clearQuery().build(), id)
    }

    private fun versionMatches(id: Long, s: String?, a: Array<out String>?): Boolean =
        s == null || (s == "${RawContacts.VERSION} = ?" && raw[id]?.getAsLong(RawContacts.VERSION) == a!![0].toLong())

    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int {
        val segs = uri.pathSegments
        return when {
            segs.firstOrNull() == "raw_contacts" && segs.size == 2 -> {
                val id = segs[1].toLong()
                val v = raw[id] ?: return 0
                if (!versionMatches(id, s, a)) return 0
                if (isSyncAdapter(uri) || v.getAsString(RawContacts.ACCOUNT_TYPE) == null) {
                    data.entries.removeAll { it.value.getAsLong(Data.RAW_CONTACT_ID) == id }
                    raw.remove(id)
                } else {
                    v.put(RawContacts.DELETED, 1); touch(id)
                }
                1
            }
            segs.firstOrNull() == "data" && s == "${Data.RAW_CONTACT_ID} = ?" -> {
                val id = a!![0].toLong()
                val before = data.size
                data.entries.removeAll { it.value.getAsLong(Data.RAW_CONTACT_ID) == id }
                before - data.size
            }
            segs.firstOrNull() == "data" && s == ContactsMirror.CARRIED_SELECTION -> {
                val id = a!![0].toLong()
                val mimes = a.drop(1).toSet()
                val before = data.size
                data.entries.removeAll { it.value.getAsLong(Data.RAW_CONTACT_ID) == id && it.value.getAsString(Data.MIMETYPE) in mimes }
                before - data.size
            }
            else -> 0
        }
    }

    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int {
        val segs = uri.pathSegments
        if (segs.firstOrNull() != "raw_contacts" || segs.size != 2) return 0
        val id = segs[1].toLong()
        val row = raw[id] ?: return 0
        if (!versionMatches(id, s, a)) return 0
        row.putAll(v)
        row.put(RawContacts.VERSION, (row.getAsLong(RawContacts.VERSION) ?: 1L) + 1)
        if (!isSyncAdapter(uri)) row.put(RawContacts.DIRTY, 1)
        return 1
    }

    private fun rawMatches(v: ContentValues, s: String?, a: Array<out String>?): Boolean = when (s) {
        null -> true
        "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?" ->
            v.getAsString(RawContacts.ACCOUNT_TYPE) == a!![0] && v.getAsString(RawContacts.ACCOUNT_NAME) == a[1]
        ContactsPush.RIST_CHANGED ->
            v.getAsString(RawContacts.ACCOUNT_TYPE) == a!![0] && v.getAsString(RawContacts.ACCOUNT_NAME) == a[1] &&
                (v.getAsInteger(RawContacts.DIRTY) ?: 0) == 1
        ContactsPush.deviceSelection(null).first ->
            v.getAsString(RawContacts.ACCOUNT_TYPE) == null && (v.getAsInteger(RawContacts.DELETED) ?: 0) == 0
        else -> error("unexpected raw_contacts selection: $s")
    }

    private fun cursorOf(p: Array<out String>?, rows: List<Pair<Long, ContentValues>>, idColumn: String): Cursor {
        val cols = p ?: arrayOf(idColumn)
        val c = MatrixCursor(cols)
        for ((id, v) in rows) c.addRow(cols.map { if (it == idColumn) id else v.get(it) }.toTypedArray())
        return c
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val segs = uri.pathSegments
        return when (table(uri)) {
            "raw_contacts" -> {
                val rows = if (segs.size == 2) listOfNotNull(raw[segs[1].toLong()]?.let { segs[1].toLong() to it })
                else raw.entries.filter { rawMatches(it.value, s, a) }.map { it.key to it.value }
                cursorOf(p, rows, RawContacts._ID)
            }
            "data" -> {
                check(s == "${Data.RAW_CONTACT_ID} = ?") { "unexpected data selection: $s" }
                val id = a!![0].toLong()
                cursorOf(p, data.entries.filter { it.value.getAsLong(Data.RAW_CONTACT_ID) == id }.map { it.key to it.value }, Data._ID)
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = null
}
