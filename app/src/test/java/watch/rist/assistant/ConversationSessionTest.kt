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
    }
}
