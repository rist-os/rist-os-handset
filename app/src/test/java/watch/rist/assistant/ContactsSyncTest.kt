package watch.rist.assistant

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.robolectric.shadows.ShadowToast
import rist.v1.CommsCommand
import rist.v1.ContactMethod
import rist.v1.ContactRecord
import rist.v1.ContactSync
import rist.v1.DeviceResponse
import rist.v1.WakeSignal

/**
 * Contact sync, phone half: the backend's contacts pulled into this phone, and every screen that
 * shows a number naming the person behind it.
 */
@RunWith(RobolectricTestRunner::class)
class ContactsSyncTest {

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
        Config.setContactsSyncedAt(app, 0L)
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

    private fun person(id: String, name: String, vararg phones: String, email: String = "") =
        ContactRecord.newBuilder().setId(id).setDisplayName(name).setUpdatedAtMs(1_000L)
            .addAllMethods(phones.mapIndexed { i, p ->
                ContactMethod.newBuilder().setId("$id-p$i").setKind("phone").setLabel("mobile").setValue(p)
                    .setIsPrimary(i == 0).build()
            })
            .apply {
                if (email.isNotBlank()) addMethods(
                    ContactMethod.newBuilder().setId("$id-e").setKind("email").setLabel("work").setValue(email)
                )
            }
            .build()

    private fun page(cursor: String, full: Boolean, more: Boolean, vararg people: ContactRecord, deleted: List<String> = emptyList()) =
        MockResponse().setResponseCode(200).setBody(Buffer().write(
            ContactSync.newBuilder().setCursor(cursor).setFull(full).setMore(more)
                .addAllContacts(people.toList()).addAllDeletedIds(deleted).build().toByteArray()
        ))

    private fun syncNow() = ContactsSync.syncBlocking(app)

    // ---- pull ----

    @Test
    fun `a full pull pages until more is clear, then fills the address book and keeps the last cursor`() {
        server.enqueue(page("c1", full = true, more = true, person("a", "Alice Example", "+12065550100", email = "a@example.com")))
        server.enqueue(page("c2", full = true, more = false, person("b", "Bob Example", "+12065550111")))

        val out = syncNow()

        assertEquals(ContactsSync.Outcome.Applied(full = true, written = 2, removed = 0), out)
        val first = server.takeRequest(30, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("/v1/contacts?since=", first.path)
        assertEquals("Bearer ristd_test", first.getHeader("Authorization"))
        assertEquals("/v1/contacts?since=c1", server.takeRequest(30, java.util.concurrent.TimeUnit.SECONDS)!!.path)
        assertEquals("c2", Config.contactsCursor(app))
        assertTrue(Config.contactsSyncedAt(app) > 0)
        assertFalse(Config.contactsNeedsFull(app))

        assertEquals(setOf("a", "b"), contacts.sourceIds())
        assertEquals("Alice Example", contacts.nameOf("a"))
        assertEquals(listOf("+12065550100"), contacts.phonesOf("a"))
        assertTrue(contacts.raw.values.all { it.getAsString(ContactsContract.RawContacts.ACCOUNT_TYPE) == ContactsMirror.ACCOUNT_TYPE })
        assertTrue(android.accounts.AccountManager.get(app).getAccountsByType(ContactsMirror.ACCOUNT_TYPE).isNotEmpty())
    }

    @Test
    fun `a delta upserts on id and removes tombstones, leaving everyone else`() {
        server.enqueue(page("c1", full = true, more = false,
            person("a", "Alice Example", "+12065550100"), person("b", "Bob Example", "+12065550111"),
            person("c", "Cy Example", "+12065550122")))
        syncNow()
        server.takeRequest(30, java.util.concurrent.TimeUnit.SECONDS)!!

        server.enqueue(page("c2", full = false, more = false,
            person("a", "Alice Renamed", "+12065550199"), deleted = listOf("b")))
        val out = syncNow()

        assertEquals("/v1/contacts?since=c1", server.takeRequest(30, java.util.concurrent.TimeUnit.SECONDS)!!.path)
        assertEquals(ContactsSync.Outcome.Applied(full = false, written = 1, removed = 1), out)
        assertEquals(setOf("a", "c"), contacts.sourceIds())
        assertEquals("Alice Renamed", contacts.nameOf("a"))
        assertEquals(listOf("+12065550199"), contacts.phonesOf("a"))
        assertEquals("c2", Config.contactsCursor(app))
        assertNull(CallerId.nameFor(app, "+12065550111"))
        assertEquals("Alice Renamed", CallerId.nameFor(app, "+12065550199"))
        assertNull("the old number is gone with the edit", CallerId.nameFor(app, "+12065550100"))
    }

    @Test
    fun `a full pull replaces the mirror, removing people the backend no longer has`() {
        server.enqueue(page("c1", full = true, more = false, person("a", "Alice", "+12065550100"), person("b", "Bob", "+12065550111")))
        syncNow()
        server.enqueue(page("c9", full = true, more = false, person("b", "Bob", "+12065550111")))
        ContactsSync.requestSync(app, full = true, reason = "test", manual = true)

        server.takeRequest(30, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("sync now asks from the start", "/v1/contacts?since=", server.takeRequest(30, java.util.concurrent.TimeUnit.SECONDS)!!.path)
        assertEquals(setOf("b"), contacts.sourceIds())
        assertNull(CallerId.nameFor(app, "+12065550100"))
    }

    @Test
    fun `a page that says more without moving the cursor stops the pull and applies nothing`() {
        server.enqueue(page("", full = true, more = true, person("a", "Alice", "+12065550100")))
        assertTrue(syncNow() is ContactsSync.Outcome.Failed)
        assertEquals("", Config.contactsCursor(app))
        assertTrue(contacts.sourceIds().isEmpty())
    }

    @Test
    fun `contacts off for the account keeps the address book and stops asking`() {
        server.enqueue(page("c1", full = true, more = false, person("a", "Alice", "+12065550100")))
        syncNow()
        server.enqueue(MockResponse().setResponseCode(409).setHeader("X-Rist-Feature", "contacts-off"))
        assertEquals(ContactsSync.Outcome.Refused(409), syncNow())
        assertEquals(setOf("a"), contacts.sourceIds())
        assertEquals(ContactsSync.Outcome.NotAllowed, syncNow())
        assertFalse(Config.enrolRevoked(app))
        // A wake carrying a cursor means contacts are on again.
        server.enqueue(page("c2", full = false, more = false))
        WakeLoop.apply(app, WakeSignal.newBuilder().setContactsCursor("c2").build(), emptyList())
        assertFalse(Config.contactsRefused(app))
    }

    @Test
    fun `the owner's switch off stops syncing`() {
        ContactsSync.setEnabled(app, false)
        assertEquals(ContactsSync.Outcome.NotAllowed, syncNow())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `without the contacts permission names still show, and the next pull is a full one`() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_CONTACTS)
        server.enqueue(page("c1", full = true, more = false, person("a", "Alice Example", "+12065550100")))
        syncNow()
        assertTrue(contacts.sourceIds().isEmpty())
        assertTrue(Config.contactsNeedsFull(app))
        assertEquals("Alice Example", CallerId.nameFor(app, "2065550100"))
    }

    // ---- the nudge ----

    @Test
    fun `a wake or a turn whose cursor differs pulls, and the same cursor does not`() {
        server.enqueue(page("c5", full = true, more = false, person("a", "Alice", "+12065550100")))
        WakeLoop.apply(app, WakeSignal.newBuilder().setContactsCursor("c5").build(), emptyList())
        assertEquals(1, server.requestCount)
        assertEquals("c5", Config.contactsCursor(app))

        ContactsSync.onCursor(app, "c5")
        ContactsSync.onCursor(app, "")
        assertEquals("no pull for a cursor already applied, or for none", 1, server.requestCount)
    }

    @Test
    fun `a turn reply's cursor is taken in`() {
        // The device turn is answered first, then the pull the new cursor asks for.
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(
            DeviceResponse.newBuilder().setIsFinal(true).setContactsCursor("t1").build().toByteArray()
        )))
        server.enqueue(page("t1", full = true, more = false, person("a", "Alice", "+12065550100")))
        StreamingCancel.resetForTest()
        Uploader(app).sendText("text Alice")
        assertEquals("t1", Config.contactsCursor(app))
    }

    // ---- names on screen ----

    private fun synced() {
        server.enqueue(page("c1", full = true, more = false, person("a", "Alice Example", "+12065550100")))
        syncNow()
    }

    @Test
    fun `the incoming call screen names a synced caller, in whatever form the network sends the number`() {
        synced()
        for (n in listOf("+12065550100", "2065550100", "12065550100", "(206) 555-0100")) {
            CallerId.forget()
            shadowOf(app.getSystemService(TelephonyManager::class.java)).setCallState(TelephonyManager.CALL_STATE_RINGING)
            val a = Robolectric.buildActivity(
                IncomingCallActivity::class.java,
                Intent(app, IncomingCallActivity::class.java).putExtra(IncomingCallActivity.EXTRA_NUMBER, n),
            ).create().get()
            val texts = allTexts(a.window.decorView)
            assertTrue("$n -> $texts", "Alice Example" in texts)
            assertTrue("the number is still shown under the name: $texts", "(206) 555-0100" in texts)
            a.finish()
        }
        IncomingCall.clear()
    }

    @Test
    fun `a missed call and a text from a synced contact show the name in the feed`() {
        synced()
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG)
        Robolectric.setupContentProvider(FakeCallLog::class.java, CallLog.AUTHORITY)
        Robolectric.setupContentProvider(FakeSms::class.java, "sms")
        CallerId.forget()
        val items = CommsFeedView.candidates(app)
        val call = items.first { it.kind == FeedKind.MISSED_CALL }
        val text = items.first { it.kind == FeedKind.TEXT }
        assertEquals("Alice Example", call.contactName)
        assertEquals("Alice Example", text.contactName)
    }

    @Test
    fun `a message about to be sent names the recipient with the number`() {
        synced()
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)
        // Past the send rate limit, which counts from boot.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30))
        DeviceCommands.handle(app, DeviceResponse.newBuilder().setRequestId("send-1").setComms(
            CommsCommand.newBuilder().setAction("send_sms").setNumber("+12065550100").setBody("on my way")
        ).build())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // The test radio cannot send, so this is "Could not send to …"; on a phone it is
        // "Sending to …". Either way the toast names the recipient with the number.
        val shown = ShadowToast.getTextOfLatestToast().orEmpty()
        assertTrue(shown, shown.contains("send to Alice Example · (206) 555-0100", ignoreCase = true) ||
            shown.startsWith("Sending to Alice Example · (206) 555-0100\non my way"))
    }

    @Test
    fun `a stranger stays a number`() {
        synced()
        assertEquals("(206) 555-0177", CallerId.label(app, "+12065550177"))
        assertNull(CallerId.nameFor(app, "+12065550177"))
    }

    // ---- numbers ----

    @Test
    fun `numbers normalise to one form for matching`() {
        for (n in listOf("+1 206-555-0100", "2065550100", "1 (206) 555-0100", "+12065550100")) {
            assertEquals(n, "+12065550100", PhoneNumbers.key(n, "US"))
        }
        assertEquals("+447700900123", PhoneNumbers.key("+44 7700 900123", "US"))
        assertEquals("", PhoneNumbers.key("Unknown", "US"))
        assertEquals("2065550100", PhoneNumbers.tail("+1 (206) 555-0100"))
    }

    @Test
    fun `two people sharing the last ten digits get no name rather than a wrong one`() {
        ContactIndex.apply(app, true, listOf(
            person("a", "Alice", "+12065550100"), person("b", "Bob", "+442065550100"),
        ), emptyList())
        assertEquals("Alice", ContactIndex.nameFor(app, "2065550100"))
        assertEquals("Bob", ContactIndex.nameFor(app, "+442065550100"))
        assertNull(ContactIndex.nameFor(app, "+72065550100"))
    }

    // ---- review fixes ----

    @Test
    fun `a number written internationally never borrows a name from its last ten digits`() {
        ContactIndex.apply(app, true, listOf(person("a", "Alice", "+12065550100")), emptyList())
        assertNull(ContactIndex.nameFor(app, "+442065550100"))
        assertNull(ContactIndex.nameFor(app, "00442065550100"))
        assertEquals("Alice", ContactIndex.nameFor(app, "2065550100"))
        assertEquals("Alice", ContactIndex.nameFor(app, "+1 (206) 555-0100"))
    }

    @Test
    fun `a hint that is only the number again is not shown as a name`() {
        synced()
        assertEquals("Alice Example · (206) 555-0100", CallerId.label(app, "+12065550100", "(206) 555-0100"))
        assertEquals("Al · (206) 555-0100", CallerId.label(app, "+12065550100", "Al"))
    }

    @Test
    fun `removing the Rist account rebuilds the address book on the next nudge`() {
        synced()
        server.takeRequest(30, java.util.concurrent.TimeUnit.SECONDS)!!
        android.accounts.AccountManager.get(app).removeAccountExplicitly(ContactsMirror.account)
        contacts.raw.clear(); contacts.data.clear() // the provider drops a removed account's rows
        server.enqueue(page("c1", full = true, more = false, person("a", "Alice Example", "+12065550100")))

        ContactsSync.onCursor(app, "c1")

        assertEquals("/v1/contacts?since=", server.takeRequest(30, java.util.concurrent.TimeUnit.SECONDS)!!.path)
        assertEquals(setOf("a"), contacts.sourceIds())
    }

    @Test
    fun `a new pairing starts the address book over rather than a delta on the last owner's`() {
        synced()
        assertEquals("c1", Config.contactsCursor(app))
        Config.setAuthToken(app, "ristd_someone_else")
        assertEquals("", Config.contactsCursor(app))
        assertTrue(Config.contactsNeedsFull(app))
    }

    @Test
    fun `a full pull removes a second row held for the same person`() {
        synced()
        val extra = ContentValues().apply {
            put(ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsMirror.ACCOUNT_TYPE)
            put(ContactsContract.RawContacts.ACCOUNT_NAME, ContactsMirror.ACCOUNT_NAME)
            put(ContactsContract.RawContacts.SOURCE_ID, "a")
        }
        contacts.insert(ContactsContract.RawContacts.CONTENT_URI, extra)
        server.enqueue(page("c2", full = true, more = false, person("a", "Alice Example", "+12065550100")))
        ContactsSync.requestSync(app, full = true, reason = "test", manual = true)
        assertEquals(1, contacts.raw.values.count { it.getAsString(ContactsContract.RawContacts.SOURCE_ID) == "a" })
    }

    @Test
    fun `a damaged index file is never quoted into the log`() {
        java.io.File(app.noBackupFilesDir, "contacts_index.json")
            .writeText("""[{"i":"a","d":"Alice Private","n":["+12065550100"]""")
        org.robolectric.shadows.ShadowLog.clear()
        assertEquals(0, ContactIndex.size(app))
        val logged = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { it.msg + (it.throwable?.toString() ?: "") }
        assertFalse(logged, logged.contains("Alice") || logged.contains("5550100"))
    }

    @Test
    fun `failures back off, and a pull asked for inside the gap waits instead of being dropped`() {
        assertEquals(0L, ContactsSync.waitMs(now = 100_000, lastAutoAt = 0, failures = 0, failedAt = 0))
        assertEquals(10_000L, ContactsSync.waitMs(now = 10_000, lastAutoAt = 5_000, failures = 0, failedAt = 0))
        assertEquals(ContactsSync.MIN_GAP_MS * 4, ContactsSync.backoffMs(3))
        assertEquals(ContactsSync.MAX_BACKOFF_MS, ContactsSync.backoffMs(50))
        assertEquals(ContactsSync.backoffMs(3) - 1_000,
            ContactsSync.waitMs(now = 1_000_000 + 1_000, lastAutoAt = 0, failures = 3, failedAt = 1_000_000))

        ContactsSync.runInlineForTest = false
        ContactsSync.minGapMs = 200L
        server.enqueue(page("c1", full = true, more = false, person("a", "Alice", "+12065550100")))
        ContactsSync.requestSync(app, full = false, reason = "test")
        waitFor { Config.contactsCursor(app) == "c1" }
        server.enqueue(page("c2", full = false, more = false, person("b", "Bob", "+12065550111")))
        ContactsSync.requestSync(app, full = false, reason = "test")
        waitFor { Config.contactsCursor(app) == "c2" }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the daily pull is not pushed back a day every time the app starts`() {
        val am = app.getSystemService(android.app.AlarmManager::class.java)
        ContactsSync.scheduleDaily(app)
        val first = shadowOf(am).peekNextScheduledAlarm()!!.triggerAtTime
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(3))
        ContactsSync.scheduleDaily(app)
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
        assertEquals(first, shadowOf(am).peekNextScheduledAlarm()!!.triggerAtTime)
    }

    private fun waitFor(cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
        while (!cond()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `the contacts url sits beside the device url`() {
        assertEquals("https://h.example/v1/contacts?since=c%201",
            ContactsSync.contactsUrl("https://h.example/v1/device", "c 1"))
        assertEquals("https://h.example/v1/contacts?since=", ContactsSync.contactsUrl("https://h.example", ""))
        assertNull(ContactsSync.contactsUrl("", ""))
    }

    private fun allTexts(v: android.view.View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { allTexts(v.getChildAt(it)) }
        else -> emptyList()
    }
}

/** The call log with one missed call from Alice's number, written nationally. */
class FakeCallLog : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
        MatrixCursor(arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE)).apply { addRow(arrayOf<Any>("2065550100", 1_000L)) }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/** The SMS inbox with one text from Alice's number, written in E.164. */
class FakeSms : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
        MatrixCursor(arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT))
            .apply { addRow(arrayOf<Any>("+12065550100", 2_000L, 0L)) }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
