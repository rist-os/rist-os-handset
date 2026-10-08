package watch.rist.assistant

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class ConversationSessionTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<Context>()

    private fun store(): android.content.SharedPreferences =
        Config::class.java.getDeclaredField("cached").apply { isAccessible = true }
            .get(null) as android.content.SharedPreferences

    @Before
    fun setUp() {
        Config.setDeployDefaultsForTest("", "")
        Config.usePlainPrefsForTest(ctx())
        store().edit().clear().commit()
    }

    @After
    fun tearDown() {
        store().edit().clear().commit()
        Config.forgetPrefsForTest()
        Config.clearDeployDefaultsForTest()
    }

    @Test
    fun `the session id is stable across calls and has no idle limit`() {
        val first = Config.sessionId(ctx())
        assertTrue(first.isNotEmpty())
        // Hours of idle time: the old build rotated after 5 minutes (20 when awaiting a reply).
        ShadowSystemClock.advanceBy(Duration.ofHours(30))
        assertEquals(first, Config.sessionId(ctx()))
        assertEquals(first, Config.currentSessionId(ctx()))
    }

    @Test
    fun `the session id survives a restart, read from storage`() {
        val first = Config.sessionId(ctx())
        // A fresh process opens the store again; nothing is held only in memory.
        Config.forgetPrefsForTest()
        Config.usePlainPrefsForTest(ctx())
        assertEquals(first, Config.currentSessionId(ctx()))
        assertEquals(first, Config.sessionId(ctx()))
    }

    @Test
    fun `the old idle clock no longer rotates the session`() {
        val first = Config.sessionId(ctx())
        // What an older build left behind: a last-use stamp a day old and the awaiting flag.
        store().edit().putLong("session_last_at", 1L).putBoolean("awaiting_reply", true).commit()
        assertEquals(first, Config.sessionId(ctx()))
        assertFalse("the leftovers are tidied", store().contains("session_last_at"))
    }

    @Test
    fun `an endpoint change keeps the conversation`() {
        val first = Config.sessionId(ctx())
        Config.setBackendEndpoint(ctx(), "https://other.example/v1/device")
        Config.clearBackendOverride(ctx())
        assertEquals(first, Config.sessionId(ctx()))
    }

    @Test
    fun `new conversation replaces the id and only then`() {
        val first = Config.sessionId(ctx())
        val next = Config.newSession(ctx())
        assertNotEquals(first, next)
        assertEquals(next, Config.sessionId(ctx()))
    }

    @Test
    fun `re-pairing to the same account keeps the conversation`() {
        Config.onPairedAccount(ctx(), "acct-1")
        val first = Config.sessionId(ctx())
        assertFalse(Config.onPairedAccount(ctx(), "acct-1"))
        assertEquals(first, Config.sessionId(ctx()))
    }

    @Test
    fun `pairing to a different account starts a new conversation`() {
        Config.onPairedAccount(ctx(), "acct-1")
        val first = Config.sessionId(ctx())
        assertTrue(Config.onPairedAccount(ctx(), "acct-2"))
        assertNotEquals(first, Config.sessionId(ctx()))
        assertEquals("acct-2", Config.sessionAccount(ctx()))
    }

    @Test
    fun `an unknown account on either side starts a new conversation`() {
        Config.onPairedAccount(ctx(), "acct-1")
        val first = Config.sessionId(ctx())
        assertTrue("an answer without user_id cannot prove it is the same account",
            Config.onPairedAccount(ctx(), ""))
        assertNotEquals(first, Config.sessionId(ctx()))
    }

    @Test
    fun `settings New conversation starts one and says so, with no dialog`() {
        val before = Config.sessionId(ctx())
        val a = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
        val button = a.findViewById<TextView>(R.id.newConversation)
        val status = a.findViewById<TextView>(R.id.newConversationStatus)
        assertEquals(a.getString(R.string.new_conversation_hint), status.text.toString())
        button.performClick()
        assertNotEquals(before, Config.sessionId(ctx()))
        assertEquals(a.getString(R.string.new_conversation_started), status.text.toString())
        assertEquals("no dialog may open", null, org.robolectric.shadows.ShadowDialog.getLatestDialog())
        assertTrue("the tap arms new_conversation for the next request",
            Config.newConversationPendingFor(ctx(), Config.sessionId(ctx())))
    }

    @Test
    fun `settings New conversation clears the conversation shown on screen, pinned answers kept`() {
        Transcript.clearForTest(ctx())
        val kept = Transcript.begin(ctx(), "pinned question", EntryState.ANSWERED)
        Transcript.setPinned(ctx(), kept, true)
        Transcript.begin(ctx(), "my locker is number 41", EntryState.ANSWERED)
        val a = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
        a.findViewById<TextView>(R.id.newConversation).performClick()
        assertEquals(listOf(kept), Transcript.all(ctx()).map { it.localId })
        Transcript.clearForTest(ctx())
    }

    // ── new_conversation (v29, DeviceRequest field 30) ─────────────────────────────────────────

    @Test
    fun `the field is number 30 on DeviceRequest`() {
        // Lite runtime, no descriptors: read the wire. Tag 30, varint = (30 << 3) | 0 = 240 = F0 01.
        val bytes = rist.v1.DeviceRequest.newBuilder().setNewConversation(true).build().toByteArray()
        assertEquals(listOf(0xF0, 0x01, 0x01), bytes.map { it.toInt() and 0xff })
    }

    @Test
    fun `nothing is pending until the user asks`() {
        assertFalse(Config.newConversationPendingFor(ctx(), Config.sessionId(ctx())))
    }

    @Test
    fun `a pending new conversation is tied to its session and cleared only for it`() {
        val first = Config.startNewConversation(ctx())
        assertEquals(first, Config.sessionId(ctx()))
        assertTrue(Config.newConversationPendingFor(ctx(), first))
        val second = Config.startNewConversation(ctx())
        // A reply to a request sent before the second tap must not clear the second tap.
        Config.clearNewConversation(ctx(), first)
        assertTrue(Config.newConversationPendingFor(ctx(), second))
        Config.clearNewConversation(ctx(), second)
        assertFalse(Config.newConversationPendingFor(ctx(), second))
    }

    @Test
    fun `a change of account drops the flag rather than ending the new account's conversation`() {
        Config.onPairedAccount(ctx(), "acct-1")
        Config.startNewConversation(ctx())
        Config.onPairedAccount(ctx(), "acct-2")
        assertFalse(Config.newConversationPendingFor(ctx(), Config.sessionId(ctx())))
    }

    private fun reply(text: String) = okhttp3.mockwebserver.MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/x-protobuf")
        .setBody(okio.Buffer().write(rist.v1.DeviceResponse.newBuilder()
            .setSpeech(rist.v1.Speech.newBuilder().setText(text)).build().toByteArray()))

    private fun sent(server: okhttp3.mockwebserver.MockWebServer): rist.v1.DeviceRequest =
        rist.v1.DeviceRequest.parseFrom(
            server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readByteArray())

    @Test
    fun `the next request carries new_conversation, and the one after a reply does not`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            Config.setBackendEndpoint(ctx(), server.url("/v1/device").toString())
            val before = Config.sessionId(ctx())
            Config.startNewConversation(ctx())
            val now = Config.sessionId(ctx())
            assertNotEquals("session_id rotates too", before, now)

            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(500))
            server.enqueue(reply("hi"))
            server.enqueue(reply("again"))
            assertEquals(null, Uploader(ctx()).sendText("hello"))
            val failed = sent(server)
            assertTrue(failed.newConversation)
            assertTrue("no reply yet: still pending", Config.newConversationPendingFor(ctx(), now))

            Uploader(ctx()).sendText("hello")
            val answered = sent(server)
            assertTrue(answered.newConversation)
            assertEquals(now, answered.sessionId)
            assertFalse(Config.newConversationPendingFor(ctx(), now))

            Uploader(ctx()).sendText("and then")
            val next = sent(server)
            assertFalse("only until a reply", next.newConversation)
            assertEquals("the conversation goes on in the same session", now, next.sessionId)
        } finally {
            Config.clearBackendOverride(ctx())
            runCatching { server.shutdown() }
        }
    }
}
