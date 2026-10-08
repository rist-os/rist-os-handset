package watch.rist.assistant

import android.Manifest
import android.app.Application
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.RawContacts
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.ContactMethod
import rist.v1.ContactPush
import rist.v1.ContactRecord
import rist.v1.ContactSync
import java.util.concurrent.TimeUnit

/**
 * Contact sync, phone to backend: a contact added, changed or deleted on the phone reaches the
 * backend, comes back with its id, and is held once.
 */
@RunWith(RobolectricTestRunner::class)
class ContactsPushTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var contacts: FakeContactsProvider

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        contacts = Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        server = MockWebServer().apply { start() }
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(app, server.url("/v1/device").toString())
        ContactsSync.bearerForTest = "Bearer ristd_test"
        Config.setFeatures(app, "")
        Config.setContactsSyncOff(app, false)
        Config.setContactsRefused(app, false)
        Config.setContactsCursor(app, "")
        Config.setContactsNeedsFull(app, false)
        Config.setContactsAdopt(app, "")
        ContactIndex.resetForTest(app)
        CallerId.forget()
        ContactsSync.runInlineForTest = true
        shadowOf(app.getSystemService(TelephonyManager::class.java)).setNetworkCountryIso("us")
    }

    @After
    fun tearDown() {
        ContactsSync.runInlineForTest = false
        ContactsSync.bearerForTest = null
        Config.clearDeployDefaultsForTest()
        ContactIndex.resetForTest(app)
        CallerId.forget()
        runCatching { server.shutdown() }
    }

    private val tag get() = Config.contactsPushTag(app)

    private fun person(id: String, name: String, vararg phones: String, key: String = "") =
        ContactRecord.newBuilder().setId(id).setDisplayName(name).setUpdatedAtMs(1_000L).setExternalKey(key)
            .addAllMethods(phones.mapIndexed { i, p ->
                ContactMethod.newBuilder().setId("$id-p$i").setKind("phone").setLabel("mobile").setValue(p).build()
            })
            .build()

    private fun page(cursor: String, full: Boolean, vararg people: ContactRecord, deleted: List<String> = emptyList()) =
        MockResponse().setResponseCode(200).setBody(Buffer().write(
            ContactSync.newBuilder().setCursor(cursor).setFull(full).setMore(false)
                .addAllContacts(people.toList()).addAllDeletedIds(deleted).build().toByteArray()
        ))

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
    private fun pushOf(r: RecordedRequest): ContactPush = ContactPush.parseFrom(r.body.readByteArray())

    /** A mirror already holding Alice, as a pull leaves it. */
    private fun synced() {
        server.enqueue(page("c1", full = true, person("a", "Alice Example", "+12065550100", "+12065550101")))
        ContactsSync.syncBlocking(app)
        take()
    }

    @Test
    fun `a contact made in the Contacts app goes to the backend, gets its id, and is held once`() {
        synced()
        val raw = contacts.ownerCreates(ContactsMirror.ACCOUNT_TYPE, "Sam Example", "425-555-9212")
        val key = ContactsPush.ristKey(tag, raw)
        server.enqueue(page("c2", full = false, person("s", "Sam Example", "+14255559212", key = key)))
        server.enqueue(page("c2", full = false, person("s", "Sam Example", "+14255559212", key = key)))

        val out = ContactsSync.syncBlocking(app)

        val post = take()
        assertEquals("POST", post.method)
        assertEquals("/v1/contacts", post.path)
        assertEquals("Bearer ristd_test", post.getHeader("Authorization"))
        val push = pushOf(post)
        assertEquals("c1", push.since)
        assertEquals(1, push.changesCount)
        assertEquals("", push.getChanges(0).id)
        assertEquals(key, push.getChanges(0).externalKey)
        assertEquals("Sam Example", push.getChanges(0).displayName)
        assertEquals("425-555-9212", push.getChanges(0).getMethods(0).value)
        assertEquals("mobile", push.getChanges(0).getMethods(0).label)
        assertEquals("/v1/contacts?since=c1", take().path)
        assertTrue(out is ContactsSync.Outcome.Applied && out.pushed == 1)

        assertEquals("one row for Sam, the one made on the phone", 1, contacts.raw.values.count { it.getAsString(RawContacts.SOURCE_ID) == "s" })
        assertEquals(raw, contacts.rawIdOfSource("s"))
        assertEquals(0, contacts.raw[raw]!!.getAsInteger(RawContacts.DIRTY))
        assertEquals(listOf("+14255559212"), contacts.phonesOf("s"))
        assertEquals("Sam Example", CallerId.nameFor(app, "+14255559212"))

        // Nothing is waiting now, so the next sync sends nothing.
        assertFalse(ContactsPush.hasPending(app))
    }

    @Test
    fun `an id that does not come back with the push is claimed by the pull`() {
        synced()
        val raw = contacts.ownerCreates(ContactsMirror.ACCOUNT_TYPE, "Sam Example", "+14255559212")
        val key = ContactsPush.ristKey(tag, raw)
        server.enqueue(page("c1", full = false))
        server.enqueue(page("c2", full = true, person("a", "Alice Example", "+12065550100"), person("s", "Sam Example", "+14255559212", key = key)))
        Config.setContactsNeedsFull(app, true)

        ContactsSync.syncBlocking(app)
        take(); take()

        assertEquals(1, contacts.raw.values.count { it.getAsString(RawContacts.SOURCE_ID) == "s" })
        assertEquals(raw, contacts.rawIdOfSource("s"))
        assertNull(contacts.raw[raw]!!.getAsString(RawContacts.SYNC3))
    }

    @Test
    fun `an edit to a synced contact goes with its id, and a removed number with its method id`() {
        synced()
        val raw = contacts.rawIdOfSource("a")
        contacts.ownerRenames(raw, "Alice Renamed")
        contacts.ownerRemovesPhone(raw, "+12065550101")
        server.enqueue(page("c2", full = false, person("a", "Alice Renamed", "+12065550100")))
        server.enqueue(page("c2", full = false, person("a", "Alice Renamed", "+12065550100")))

        ContactsSync.syncBlocking(app)

        val push = pushOf(take())
        assertEquals("a", push.getChanges(0).id)
        assertEquals("", push.getChanges(0).externalKey)
        assertEquals("Alice Renamed", push.getChanges(0).displayName)
        assertEquals("a-p0", push.getChanges(0).getMethods(0).id)
        assertEquals(listOf("a-p1"), push.deletedMethodIdsList)
        take()
        assertEquals("Alice Renamed", contacts.nameOf("a"))
        assertEquals(0, contacts.raw[raw]!!.getAsInteger(RawContacts.DIRTY))
    }

    @Test
    fun `a contact deleted on the phone is deleted on the backend and leaves the address book`() {
        synced()
        val raw = contacts.rawIdOfSource("a")
        contacts.ownerDeletes(raw)
        server.enqueue(page("c2", full = false, deleted = listOf("a")))
        server.enqueue(page("c2", full = false, deleted = listOf("a")))

        ContactsSync.syncBlocking(app)

        assertEquals(listOf("a"), pushOf(take()).deletedIdsList)
        take()
        assertTrue(contacts.raw.isEmpty())
    }

    @Test
    fun `a contact saved to the phone's own account moves into Rist once the backend has it`() {
        synced()
        val local = contacts.ownerCreates(null, "Dan Example", "+14255559212")
        val key = ContactsPush.deviceKey(tag, local)
        server.enqueue(page("c2", full = false, person("d", "Dan Example", "+14255559212", key = key)))
        server.enqueue(page("c2", full = false, person("d", "Dan Example", "+14255559212", key = key)))

        ContactsSync.syncBlocking(app)

        val push = pushOf(take())
        assertEquals(key, push.getChanges(0).externalKey)
        take()
        assertNull("the device copy is gone", contacts.raw[local])
        assertEquals(ContactsMirror.ACCOUNT_TYPE, contacts.raw[contacts.rawIdOfSource("d")]!!.getAsString(RawContacts.ACCOUNT_TYPE))
        assertEquals("", Config.contactsAdopt(app))
        assertFalse(ContactsPush.hasPending(app))
    }

    @Test
    fun `a device contact holding more than a record carries is kept, and not sent again`() {
        synced()
        val local = contacts.ownerCreates(null, "Dan Example", "+14255559212",
            extraMime = ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
        val key = ContactsPush.deviceKey(tag, local)
        server.enqueue(page("c2", full = false, person("d", "Dan Example", "+14255559212", key = key)))
        server.enqueue(page("c2", full = false, person("d", "Dan Example", "+14255559212", key = key)))

        ContactsSync.syncBlocking(app)
        take(); take()

        assertTrue("the address is not lost", contacts.raw.containsKey(local))
        assertFalse(ContactsPush.hasPending(app))
    }

    @Test
    fun `a favourite saved to the phone's own account is kept, and not sent again`() {
        synced()
        val local = contacts.ownerCreates(null, "Dan Example", "+14255559212")
        contacts.raw[local]!!.put(RawContacts.STARRED, 1)
        val key = ContactsPush.deviceKey(tag, local)
        server.enqueue(page("c2", full = false, person("d", "Dan Example", "+14255559212", key = key)))
        server.enqueue(page("c2", full = false, person("d", "Dan Example", "+14255559212", key = key)))

        ContactsSync.syncBlocking(app)
        take(); take()

        assertTrue("the star is not lost", contacts.raw.containsKey(local))
        assertFalse(ContactsPush.hasPending(app))
    }

    @Test
    fun `a push the backend turns away is not written over by the pull that follows`() {
        synced()
        val raw = contacts.rawIdOfSource("a")
        contacts.ownerRenames(raw, "Alice Renamed")
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(page("c2", full = false, person("a", "Alice Example", "+12065550100")))

        ContactsSync.syncBlocking(app)

        assertEquals("POST", take().method)
        assertEquals("GET", take().method)
        assertEquals("the edit is kept", "Alice Renamed", contacts.nameOf("a"))
        assertEquals(1, contacts.raw[raw]!!.getAsInteger(RawContacts.DIRTY))
        assertTrue("the next pull is a full one, after the push", Config.contactsNeedsFull(app))
        assertTrue(ContactsPush.hasPending(app))
    }

    @Test
    fun `a contact with no name stays on the phone and sends nothing`() {
        synced()
        val raw = contacts.ownerCreates(null, "", "+14255559212")
        server.enqueue(page("c1", full = false))

        ContactsSync.syncBlocking(app)

        assertEquals("GET", take().method)
        assertTrue(contacts.raw.containsKey(raw))
        assertFalse(ContactsPush.hasPending(app))
    }

    @Test
    fun `a push that fails is not followed by a pull that would write over the edit`() {
        synced()
        contacts.ownerRenames(contacts.rawIdOfSource("a"), "Alice Renamed")
        server.enqueue(MockResponse().setResponseCode(503))

        val out = ContactsSync.syncBlocking(app)

        assertTrue(out is ContactsSync.Outcome.Failed)
        assertEquals("POST", take().method)
        assertEquals("the pull and the push, nothing after", 2, server.requestCount)
        assertEquals(1, contacts.raw[contacts.rawIdOfSource("a")]!!.getAsInteger(RawContacts.DIRTY))
    }

    @Test
    fun `contacts off for the account stops the push and keeps the address book`() {
        synced()
        contacts.ownerCreates(ContactsMirror.ACCOUNT_TYPE, "Sam Example", "+14255559212")
        server.enqueue(MockResponse().setResponseCode(409).setHeader("X-Rist-Feature", "contacts-off"))

        val out = ContactsSync.syncBlocking(app)

        assertEquals(ContactsSync.Outcome.Refused(409), out)
        assertTrue(Config.contactsRefused(app))
        assertEquals("Alice and Sam both stay", 2, contacts.raw.size)
    }

    @Test
    fun `a full pull keeps a contact made on the phone that has not been sent`() {
        synced()
        val raw = contacts.ownerCreates(ContactsMirror.ACCOUNT_TYPE, "Sam Example", "+14255559212")
        // As if the push had not run: the mirror alone, on a full pull.
        assertTrue(ContactsMirror.apply(app, true, listOf(person("a", "Alice Example", "+12065550100")), emptyList()))
        assertTrue(contacts.raw.containsKey(raw))
    }

    @Test
    fun `a mirror write keeps a photo set on the phone`() {
        synced()
        val raw = contacts.rawIdOfSource("a")
        contacts.data[999L] = android.content.ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, raw)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
        }
        assertTrue(ContactsMirror.apply(app, false, listOf(person("a", "Alice Again", "+12065550100")), emptyList()))
        assertTrue(contacts.data.containsKey(999L))
        assertEquals("Alice Again", contacts.nameOf("a"))
    }

    // ---- pure ----

    @Test
    fun `a record is stamped with when the person changed, not when it was sent`() {
        val rows = listOf(ContactsPush.DataRow(StructuredName.CONTENT_ITEM_TYPE, d1 = "Sam", changedAtMs = 2_000L))
        val p = ContactsPush.Pending(rawId = 7, version = 3, device = false, sourceId = "s", rows = rows)
        assertEquals(2_000L, ContactsPush.recordOf(p, "k", 9_000L)!!.updatedAtMs)
        // Unknown, or a clock ahead of ours: the send time.
        val unknown = p.copy(rows = listOf(rows[0].copy(changedAtMs = 0L)))
        assertEquals(9_000L, ContactsPush.recordOf(unknown, "k", 9_000L)!!.updatedAtMs)
        val ahead = p.copy(rows = listOf(rows[0].copy(changedAtMs = 99_000L)))
        assertEquals(9_000L, ContactsPush.recordOf(ahead, "k", 9_000L)!!.updatedAtMs)
    }

    @Test
    fun `a record carries the name, numbers with their labels, nickname and note`() {
        val p = ContactsPush.Pending(rawId = 7, version = 3, device = true, rows = listOf(
            ContactsPush.DataRow(StructuredName.CONTENT_ITEM_TYPE, d1 = "", d2 = "Sam", d3 = "Example"),
            ContactsPush.DataRow(Phone.CONTENT_ITEM_TYPE, d1 = " 425-555-9212 ", d2 = Phone.TYPE_WORK.toString(), primary = true),
            ContactsPush.DataRow(Phone.CONTENT_ITEM_TYPE, d1 = "425-555-0000", d2 = Phone.TYPE_CUSTOM.toString(), d3 = "boat"),
            ContactsPush.DataRow(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, d1 = "Sammy"),
            ContactsPush.DataRow(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, d1 = "from the gym"),
        ))
        val r = ContactsPush.recordOf(p, "k", 5L)!!
        assertEquals("Sam Example", r.displayName)
        assertEquals("k", r.externalKey)
        assertEquals(5L, r.updatedAtMs)
        assertEquals(listOf("425-555-9212", "425-555-0000"), r.methodsList.map { it.value })
        assertEquals(listOf("work", "boat"), r.methodsList.map { it.label })
        assertTrue(r.getMethods(0).isPrimary)
        assertEquals(listOf("Sammy"), r.aliasesList)
        assertEquals("from the gym", r.description)
        assertTrue(p.onlyCarried)
    }

    @Test
    fun `pushes are paged at the backend's limit`() {
        val many = (1..(ContactsPush.PAGE + 3)).map {
            ContactsPush.Pending(rawId = it.toLong(), version = 1, device = true,
                rows = listOf(ContactsPush.DataRow(StructuredName.CONTENT_ITEM_TYPE, d1 = "P$it")))
        }
        val pages = ContactsPush.pages("c", many, "t", 1L)
        assertEquals(listOf(ContactsPush.PAGE, 3), pages.map { it.first.changesCount })
        assertTrue(pages.all { it.first.since == "c" })
    }

    @Test
    fun `keys name this install, so a wiped phone cannot reuse another phone's`() {
        val tag = Config.contactsPushTag(app)
        assertEquals(tag, Config.contactsPushTag(app))
        assertTrue(ContactsPush.ristKey(tag, 5).contains(tag))
        assertFalse(ContactsPush.ristKey(tag, 5) == ContactsPush.deviceKey(tag, 5))
    }
}
