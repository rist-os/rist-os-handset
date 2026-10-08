package watch.rist.assistant

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import rist.v1.ContactMethod
import rist.v1.ContactPush
import rist.v1.ContactRecord
import rist.v1.ContactSync
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Contact sync, phone to backend: people added or changed on this phone are sent to the backend,
 * which stays the record of truth (`POST /v1/contacts`, a ContactPush).
 *
 * Two kinds of row are sent, both only while contacts sync is allowed ([ContactsSync.allowed]):
 *
 *  - Rist-account rows the owner created, edited or deleted in the Contacts app. The provider
 *    marks those DIRTY; the mirror writes as the sync adapter, which never does, so a pulled
 *    contact is never sent back and nothing loops.
 *  - Contacts in the phone's own account ("Device"), which is where the Contacts app saves a new
 *    contact unless told otherwise. Once the backend has one and the pull has written the Rist copy,
 *    the device copy is removed so the person is held once, in the account that syncs. A device
 *    contact holding anything a record cannot carry (an address, a birthday, a photo) is kept.
 *
 * A person made here is sent with a key ([ristKey], [deviceKey]) and no id; the backend answers
 * with the id, and sending the same key again updates rather than duplicates. Push runs before
 * every pull ([ContactsSync]), so a pull never writes an older copy over an edit not yet sent.
 */
object ContactsPush {

    private const val TAG = "RistContacts"

    /** Records per push; the backend refuses more (413). */
    internal const val PAGE = 500

    private val PROTOBUF = "application/x-protobuf".toMediaType()

    // ---- keys ----

    fun ristKey(tag: String, rawId: Long) = "phone:$tag:rist:$rawId"
    fun deviceKey(tag: String, rawId: Long) = "phone:$tag:device:$rawId"

    // ---- what a row holds ----

    /** One data row of a raw contact: its kind, DATA1..3, primary flag, and the method id the mirror stamped. */
    internal data class DataRow(
        val mime: String,
        val d1: String = "",
        val d2: String = "",
        val d3: String = "",
        val primary: Boolean = false,
        val methodId: String = "",
        /** When the provider last saw the person change (the aggregate's clock); 0 when unknown. */
        val changedAtMs: Long = 0L,
    )

    /** One raw contact with something to send. */
    internal data class Pending(
        val rawId: Long,
        val version: Long,
        /** The phone's own account, not Rist's. */
        val device: Boolean,
        /** The backend id; "" for a person made on the phone. */
        val sourceId: String = "",
        val deleted: Boolean = false,
        /** Method ids the mirror last wrote (RawContacts.SYNC2). */
        val sentMethodIds: List<String> = emptyList(),
        val rows: List<DataRow> = emptyList(),
    ) {
        /** Everything on the row is something a record carries, so nothing is lost if the row goes. */
        val onlyCarried: Boolean get() = rows.all { it.mime in ContactsMirror.CARRIED_MIMES }
    }

    internal fun nameOf(rows: List<DataRow>): String {
        val n = rows.firstOrNull { it.mime == StructuredName.CONTENT_ITEM_TYPE } ?: return ""
        return n.d1.trim().ifBlank { listOf(n.d2, n.d3).filter { it.isNotBlank() }.joinToString(" ").trim() }
    }

    private fun phoneLabel(type: String, custom: String): String = when (type.toIntOrNull()) {
        Phone.TYPE_MOBILE -> "mobile"
        Phone.TYPE_HOME -> "home"
        Phone.TYPE_WORK -> "work"
        Phone.TYPE_MAIN -> "main"
        Phone.TYPE_OTHER -> "other"
        Phone.TYPE_CUSTOM -> custom.trim().ifBlank { "other" }
        else -> "other"
    }

    private fun emailLabel(type: String, custom: String): String = when (type.toIntOrNull()) {
        Email.TYPE_HOME -> "home"
        Email.TYPE_WORK -> "work"
        Email.TYPE_OTHER -> "other"
        Email.TYPE_CUSTOM -> custom.trim().ifBlank { "other" }
        else -> "other"
    }

    /**
     * The record for one person, or null when there is no name to file them under (a number on its
     * own stays on the phone: the backend files people by name).
     */
    internal fun recordOf(p: Pending, key: String, nowMs: Long): ContactRecord? {
        val name = nameOf(p.rows)
        if (name.isBlank()) return null
        // The conflict clock is when the person changed, not when the push went: an edit made on
        // the phone before a later one on the backend must not win just because it was sent after.
        val changedAt = p.rows.maxOfOrNull { it.changedAtMs }?.takeIf { it in 1..nowMs } ?: nowMs
        val b = ContactRecord.newBuilder().setDisplayName(name).setUpdatedAtMs(changedAt)
        if (p.sourceId.isNotBlank()) b.id = p.sourceId else b.externalKey = key
        for (r in p.rows) {
            when (r.mime) {
                Phone.CONTENT_ITEM_TYPE -> if (r.d1.isNotBlank()) b.addMethods(
                    ContactMethod.newBuilder().setKind("phone").setValue(r.d1.trim())
                        .setLabel(phoneLabel(r.d2, r.d3)).setIsPrimary(r.primary).setId(r.methodId)
                )
                Email.CONTENT_ITEM_TYPE -> if (r.d1.isNotBlank()) b.addMethods(
                    ContactMethod.newBuilder().setKind("email").setValue(r.d1.trim())
                        .setLabel(emailLabel(r.d2, r.d3)).setIsPrimary(r.primary).setId(r.methodId)
                )
                Nickname.CONTENT_ITEM_TYPE -> if (r.d1.isNotBlank()) b.addAliases(r.d1.trim())
                Note.CONTENT_ITEM_TYPE -> if (r.d1.isNotBlank()) b.description = r.d1.trim()
            }
        }
        return b.build()
    }

    /** Methods the mirror wrote that the owner has since removed. */
    internal fun goneMethodIds(p: Pending): List<String> {
        val present = p.rows.mapNotNullTo(HashSet()) { it.methodId.takeIf { id -> id.isNotBlank() } }
        return p.sentMethodIds.filter { it.isNotBlank() && it !in present }
    }

    /** What to send, in pages of at most [PAGE] records. Each page lists the rows it speaks for. */
    internal fun pages(since: String, pending: List<Pending>, tag: String, nowMs: Long): List<Pair<ContactPush, List<Pending>>> {
        val out = ArrayList<Pair<ContactPush, List<Pending>>>()
        var push = ContactPush.newBuilder().setSince(since)
        var rows = ArrayList<Pending>()
        var records = 0
        fun close() {
            if (rows.isEmpty()) return
            out += push.build() to rows
            push = ContactPush.newBuilder().setSince(since)
            rows = ArrayList()
            records = 0
        }
        for (p in pending) {
            if (records >= PAGE) close()
            if (p.deleted) {
                if (p.sourceId.isNotBlank()) push.addDeletedIds(p.sourceId)
                rows += p
                continue
            }
            val key = if (p.device) deviceKey(tag, p.rawId) else ristKey(tag, p.rawId)
            val rec = recordOf(p, key, nowMs) ?: continue
            push.addChanges(rec)
            if (!p.device) push.addAllDeletedMethodIds(goneMethodIds(p))
            rows += p
            records++
        }
        close()
        return out
    }

    // ---- adoption bookkeeping: "rawId:version" entries ----

    internal fun parseAdopt(s: String): Map<Long, Long> = s.split(',').mapNotNull { e ->
        val (a, b) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val id = a.toLongOrNull() ?: return@mapNotNull null
        val v = b.toLongOrNull() ?: return@mapNotNull null
        id to v
    }.toMap()

    internal fun formatAdopt(m: Map<Long, Long>): String = m.entries.joinToString(",") { "${it.key}:${it.value}" }

    // ---- reading the provider ----

    internal const val RIST_CHANGED =
        "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.DIRTY} = 1"

    /** The phone's own account: no account at all, or the one the platform names as local. */
    internal fun deviceSelection(localType: String?): Pair<String, Array<String>> =
        if (localType.isNullOrBlank()) "${RawContacts.ACCOUNT_TYPE} IS NULL AND ${RawContacts.DELETED} = 0" to emptyArray()
        else "(${RawContacts.ACCOUNT_TYPE} IS NULL OR ${RawContacts.ACCOUNT_TYPE} = ?) AND ${RawContacts.DELETED} = 0" to arrayOf(localType)

    private fun localAccountType(ctx: Context): String? =
        if (Build.VERSION.SDK_INT >= 35) runCatching { RawContacts.getLocalAccountType(ctx) }.getOrNull() else null

    private fun rowsOf(ctx: Context, rawId: Long): List<DataRow> {
        val out = ArrayList<DataRow>()
        ctx.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.IS_PRIMARY, Data.SYNC1,
                Data.CONTACT_LAST_UPDATED_TIMESTAMP),
            "${Data.RAW_CONTACT_ID} = ?", arrayOf(rawId.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += DataRow(
                    mime = c.getString(0).orEmpty(),
                    d1 = c.getString(1).orEmpty(), d2 = c.getString(2).orEmpty(), d3 = c.getString(3).orEmpty(),
                    primary = !c.isNull(4) && c.getInt(4) != 0,
                    methodId = c.getString(5).orEmpty(),
                    changedAtMs = if (c.isNull(6)) 0L else c.getLong(6),
                )
            }
        }
        return out
    }

    /** Rist rows the owner changed, and device rows not yet sent at their current version. */
    internal fun collect(ctx: Context): List<Pending> {
        val out = ArrayList<Pending>()
        ctx.contentResolver.query(
            ContactsMirror.asSyncAdapter(RawContacts.CONTENT_URI),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.VERSION, RawContacts.DELETED, RawContacts.SYNC2),
            RIST_CHANGED, arrayOf(ContactsMirror.ACCOUNT_TYPE, ContactsMirror.ACCOUNT_NAME), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val deleted = !c.isNull(3) && c.getInt(3) != 0
                out += Pending(
                    rawId = id, version = c.getLong(2), device = false,
                    sourceId = c.getString(1).orEmpty(), deleted = deleted,
                    sentMethodIds = c.getString(4).orEmpty().split(',').filter { it.isNotBlank() },
                    rows = if (deleted) emptyList() else rowsOf(ctx, id),
                )
            }
        }
        val sent = parseAdopt(Config.contactsAdopt(ctx))
        val seen = HashSet<Long>()
        val (sel, args) = deviceSelection(localAccountType(ctx))
        ctx.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.VERSION), sel, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val version = c.getLong(1)
                seen += id
                if (sent[id] == version) continue
                out += Pending(rawId = id, version = version, device = true, rows = rowsOf(ctx, id))
            }
        }
        // Forget device contacts that are gone (deleted on the phone, or moved into Rist).
        if (sent.keys.any { it !in seen }) Config.setContactsAdopt(ctx, formatAdopt(sent.filterKeys { it in seen }))
        return out
    }

    // ---- sending ----

    sealed class Result {
        data class Sent(val count: Int) : Result()
        data class Refused(val code: Int) : Result()
        data class Failed(val why: String) : Result()
    }

    internal fun pushUrl(backendUrl: String): String? =
        ContactsSync.contactsUrl(backendUrl, "")?.substringBefore('?')

    /**
     * Sends everything changed on the phone, then marks it sent. Blocking; never throws. A refusal
     * that is the account's (401/402/403/409) stops the sync; any other refusal is logged and the
     * pull goes ahead, so one record the backend will not take cannot hold the address book still.
     */
    fun run(
        ctx: Context,
        http: OkHttpClient,
        backendUrl: String,
        bearer: String,
        device: String,
        since: String,
        nowMs: Long = System.currentTimeMillis(),
        onLapse: (Billing.Lapse) -> Unit = {},
        onRevoked: () -> Unit = {},
    ): Result = runCatching {
        val pending = collect(ctx)
        if (pending.isEmpty()) return@runCatching Result.Sent(0)
        val tag = Config.contactsPushTag(ctx)
        val url = pushUrl(backendUrl) ?: return@runCatching Result.Failed("no url")
        var sent = 0
        for ((push, rows) in pages(since, pending, tag, nowMs)) {
            val request = Request.Builder().url(url)
                .post(push.toByteArray().toRequestBody(PROTOBUF))
                .header("Accept", "application/x-protobuf")
                .header("Authorization", bearer)
                .header("X-Rist-Device", device)
                .build()
            val reply = try {
                http.newCall(request).execute().use { resp ->
                    if (resp.code == Billing.PAYMENT_REQUIRED) runCatching { onLapse(Billing.lapseWithLine(resp)) }
                    if (Enrolment.isExplicitRevocation(resp.code, resp.header(Enrolment.REVOKED_HEADER))) runCatching { onRevoked() }
                    if (resp.code != 200) {
                        Log.i(TAG, "contact push refused: HTTP ${resp.code}")
                        return@runCatching when (ContactsSync.classify(resp.code)) {
                            ContactsSync.Refusal.RETRY -> if (resp.code in 500..599) Result.Failed("HTTP ${resp.code}") else Result.Sent(sent)
                            else -> Result.Refused(resp.code)
                        }
                    }
                    ContactSync.parseFrom(resp.body?.bytes() ?: ByteArray(0))
                }
            } catch (t: Throwable) {
                Log.i(TAG, "contact push failed: ${t.javaClass.simpleName}")
                return@runCatching Result.Failed("network")
            }
            val ids = reply.contactsList.filter { it.externalKey.isNotBlank() && it.id.isNotBlank() }
                .associate { it.externalKey to it.id }
            settle(ctx, rows, tag, ids)
            sent += rows.size
        }
        Log.i(TAG, "contact push: $sent sent")
        Result.Sent(sent)
    }.getOrElse {
        // The class only: a provider message can quote the row it was handed.
        Log.w(TAG, "contact push failed: ${it.javaClass.simpleName}")
        Result.Failed(it.javaClass.simpleName)
    }

    /** Marks sent rows clean, unless the owner changed them again while they were on the way. */
    private fun settle(ctx: Context, rows: List<Pending>, tag: String, ids: Map<String, String>) {
        val cr = ctx.contentResolver
        val adopt = HashMap(parseAdopt(Config.contactsAdopt(ctx)))
        for (p in rows) {
            val uri = ContactsMirror.asSyncAdapter(ContentUris.withAppendedId(RawContacts.CONTENT_URI, p.rawId))
            val sameVersion = "${RawContacts.VERSION} = ?"
            val v = arrayOf(p.version.toString())
            runCatching {
                when {
                    p.device -> adopt[p.rawId] = p.version
                    // As the sync adapter, a delete really removes the row.
                    p.deleted -> cr.delete(uri, null, null)
                    else -> cr.update(uri, ContentValues().apply {
                        put(RawContacts.DIRTY, 0)
                        if (p.sourceId.isBlank()) {
                            val key = ristKey(tag, p.rawId)
                            val id = ids[key]
                            if (id != null) put(RawContacts.SOURCE_ID, id) else put(RawContacts.SYNC3, key)
                        }
                    }, sameVersion, v)
                }
            }.onFailure { Log.w(TAG, "could not mark a sent contact: ${it.javaClass.simpleName}") }
        }
        Config.setContactsAdopt(ctx, formatAdopt(adopt))
    }

    /**
     * After a pull wrote the Rist copies: removes each device contact the backend now holds, when the
     * owner has not changed it since it was sent and it holds nothing a record cannot carry.
     */
    fun adopt(ctx: Context, records: Collection<ContactRecord>) {
        val waiting = HashMap(parseAdopt(Config.contactsAdopt(ctx)))
        if (waiting.isEmpty()) return
        val tag = Config.contactsPushTag(ctx)
        val prefix = deviceKey(tag, 0L).removeSuffix("0")
        var removed = 0
        runCatching {
            for (r in records) {
                if (r.id.isBlank() || !r.externalKey.startsWith(prefix)) continue
                val rawId = r.externalKey.removePrefix(prefix).toLongOrNull() ?: continue
                val sentAt = waiting[rawId] ?: continue
                val row = deviceRowOf(ctx, rawId)
                val now = row?.version
                when {
                    now == null -> waiting.remove(rawId)                 // already gone
                    now != sentAt -> waiting.remove(rawId)               // changed since: send again
                    // A favourite, a ringtone or straight-to-voicemail lives on the row itself and
                    // would go with it; kept, and not sent again.
                    row.personal -> Unit
                    !Pending(rawId, now, true, rows = rowsOf(ctx, rawId)).onlyCarried -> Unit // kept, and not sent again
                    else -> {
                        val uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId).buildUpon()
                            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
                        if (ctx.contentResolver.delete(uri, "${RawContacts.VERSION} = ?", arrayOf(sentAt.toString())) > 0) removed++
                        waiting.remove(rawId)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "could not move device contacts into Rist: ${it.javaClass.simpleName}") }
        Config.setContactsAdopt(ctx, formatAdopt(waiting))
        if (removed > 0) Log.i(TAG, "moved $removed device contact(s) into the Rist account")
    }

    private class DeviceRow(val version: Long, val personal: Boolean)

    /** A device contact still there: its version, and whether it holds settings a record cannot carry. */
    private fun deviceRowOf(ctx: Context, rawId: Long): DeviceRow? =
        ctx.contentResolver.query(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
            arrayOf(RawContacts.VERSION, RawContacts.DELETED, RawContacts.STARRED,
                RawContacts.CUSTOM_RINGTONE, RawContacts.SEND_TO_VOICEMAIL), null, null, null,
        )?.use { c ->
            if (!c.moveToFirst() || (!c.isNull(1) && c.getInt(1) != 0)) return@use null
            val starred = !c.isNull(2) && c.getInt(2) != 0
            val ringtone = !c.isNull(3) && !c.getString(3).isNullOrBlank()
            val voicemail = !c.isNull(4) && c.getInt(4) != 0
            DeviceRow(c.getLong(0), starred || ringtone || voicemail)
        }

    // ---- noticing an edit ----
    //
    // No ContentObserver: nothing in this app watches a provider (SmsAcquisitionBoundaryTest). The
    // Contacts app is opened from Rist and returns to it, so coming back is when an edit is looked
    // for ([nudge] from MainActivity.onResume); every turn's and wake's cursor check looks too
    // ([ContactsSync.onCursor]), and so do boot and the daily pull.

    private val nudgeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "rist-contacts-nudge").apply { isDaemon = true } }
    private val nudgeQueued = AtomicBoolean(false)

    /** Whether anything is waiting to be sent. A few provider queries; call off the main thread. */
    fun hasPending(ctx: Context): Boolean =
        runCatching { pages("", collect(ctx), "", 0L).isNotEmpty() }.getOrDefault(false)

    /** Asks for a sync, in the background, if the owner changed something on the phone. */
    fun nudge(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        if (!nudgeQueued.compareAndSet(false, true)) return
        val gen = ContactsSync.generation
        runCatching {
            nudgeExecutor.execute {
                nudgeQueued.set(false)
                if (gen == ContactsSync.generation && ContactsSync.allowed(app) && ContactsMirror.canWrite(app) && hasPending(app)) {
                    ContactsSync.requestSync(app, full = false, reason = reason)
                }
            }
        }.onFailure { nudgeQueued.set(false) }
    }
}
