package watch.rist.assistant

import android.content.ClipboardManager
import android.provider.Settings
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class StableDeviceIdTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val serial = "1A2B3C4D5E6F7G"

    @Before
    fun clean() {
        StableId.resetForTest()
        ShadowLog.clear()
    }

    @After
    fun tidy() = StableId.resetForTest()

    private fun allLogs(): String = ShadowLog.getLogs().joinToString("\n") { "${it.tag}: ${it.msg} ${it.throwable ?: ""}" }

    // ── stable_id derivation ──────────────────────────────────────────────────────────────────

    @Test
    fun `stable_id is deterministic, 64 hex characters, and salted`() {
        val a = StableId.derive(serial)!!
        assertEquals(a, StableId.derive(serial))
        assertEquals(a, StableId.derive("  $serial "))
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
        val unsalted = MessageDigest.getInstance("SHA-256").digest(serial.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertNotEquals("the salt must be applied", unsalted, a)
        assertNotEquals(a, StableId.derive(serial + "X"))
        assertFalse("the digest must not carry the serial", a.contains(serial, ignoreCase = true))
        val contract = MessageDigest.getInstance("SHA-256").digest("rist-stable-id-v1:$serial".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("the backend contract: sha256(\"rist-stable-id-v1:\" + serial)", contract, a)
    }

    @Test
    fun `a blank or placeholder serial gives no stable_id`() {
        assertNull(StableId.derive(null))
        assertNull(StableId.derive(""))
        assertNull(StableId.derive(android.os.Build.UNKNOWN))
    }

    @Test
    fun `reading the serial never logs it`() {
        val id = StableId.read { serial }
        assertEquals(StableId.derive(serial), id)
        StableId.resetForTest()
        StableId.read { throw IllegalStateException("boom $serial") }
        assertFalse("the serial must never reach the log", allLogs().contains(serial))
    }

    @Test
    fun `without the permission there is no stable_id, and only a reason code is logged`() {
        assertNull(StableId.read { throw SecurityException("no access to $serial") })
        val logs = allLogs()
        assertTrue(logs, logs.contains("NO_PERMISSION"))
        assertFalse(logs.contains(serial))
    }

    @Test
    fun `the enrol body carries stable_id when known and omits it otherwise`() {
        val withId = JSONObject(Enrolment.enrolPayload(ctx(), "PAIR7F3K", "ab".repeat(32)))
        assertEquals("ab".repeat(32), withId.getString("stable_id"))
        assertEquals("device_id is unchanged", Config.deviceId(ctx()), withId.getString("device_id"))
        val without = JSONObject(Enrolment.enrolPayload(ctx(), "PAIR7F3K", null))
        assertFalse(without.has("stable_id"))
        assertEquals(Config.deviceId(ctx()), without.getString("device_id"))
    }

    // ── enrol refusals ────────────────────────────────────────────────────────────────────────

    private lateinit var server: MockWebServer

    private fun startServer() {
        Config.setDeployDefaultsForTest("", "")
        server = MockWebServer()
        server.start()
        Config.setBackendEndpoint(ctx(), server.url("/v1/device").toString())
        Config.setAuthToken(ctx(), "")
    }

    private fun stopServer() {
        Config.clearDeployDefaultsForTest()
        runCatching { server.shutdown() }
    }

    private val limitMessage =
        "This account already has 2 phones. Remove one at ristassist.com, under Phones, then enter the code again."

    @Test
    fun `a 409 device_limit is not retried and shows the server's message`() {
        startServer()
        try {
            server.enqueue(MockResponse().setResponseCode(409)
                .setBody("""{"detail":"limit","reason":"device_limit","limit":2,"message":"$limitMessage","url":"https://ristassist.com/account/phones"}"""))
            val reply = Enrolment.pairWithReply(ctx(), "PAIR7F3K")
            assertEquals(Enrolment.PairResult.DEVICE_LIMIT, reply.result)
            assertEquals(limitMessage, Enrolment.explainPair(reply))
            assertTrue("the same code may be entered again", Enrolment.keepsCode(reply.result))
            assertEquals("one request, no retry", 1, server.requestCount)
            val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals("PAIR7F3K", body.getString("nonce"))
        } finally { stopServer() }
    }

    @Test
    fun `a 409 reason read from the header or a nested detail object`() {
        startServer()
        try {
            server.enqueue(MockResponse().setResponseCode(409).setHeader("X-Rist-Enrol", "device-limit")
                .setBody("""{"detail":{"reason":"device_limit","message":"$limitMessage"}}"""))
            val reply = Enrolment.pairWithReply(ctx(), "PAIR7F3K")
            assertEquals(Enrolment.PairResult.DEVICE_LIMIT, reply.result)
            assertEquals(limitMessage, Enrolment.explainPair(reply))
        } finally { stopServer() }
    }

    @Test
    fun `a 409 held_elsewhere shows the server's message and is not retried`() {
        startServer()
        try {
            val msg = "This phone is still signed in to another Rist account. Remove it there, under Phones, then try again."
            server.enqueue(MockResponse().setResponseCode(409).setHeader("X-Rist-Enrol", "held-elsewhere")
                .setBody("""{"detail":"this device id is already registered to another account","reason":"held_elsewhere","message":"$msg"}"""))
            val reply = Enrolment.pairWithReply(ctx(), "PAIR7F3K")
            assertEquals(Enrolment.PairResult.HELD_ELSEWHERE, reply.result)
            assertEquals(msg, Enrolment.explainPair(reply))
            assertEquals(1, server.requestCount)
        } finally { stopServer() }
    }

    @Test
    fun `a 409 without a server message still says what to do and keeps the code`() {
        val text = Enrolment.explainPair(Enrolment.PairReply(Enrolment.PairResult.DEVICE_LIMIT))
        assertTrue(text.contains(Enrolment.PHONES_PAGE))
        assertFalse("the code is no longer spent at the limit", text.contains("has been used"))
    }

    @Test
    fun `the texted-nonce claim stops on a 409 instead of polling into a 403`() {
        startServer()
        try {
            server.enqueue(MockResponse().setResponseCode(409)
                .setBody("""{"reason":"device_limit","message":"$limitMessage"}"""))
            // Anything further would be a retry; answer it with the spent-code 403 the bug produced.
            repeat(3) { server.enqueue(MockResponse().setResponseCode(403).setBody("""{"reason":"spent"}""")) }
            assertFalse(Enrolment.claim(ctx(), "a".repeat(32)))
            assertEquals("a 409 must never be retried", 1, server.requestCount)
        } finally { stopServer() }
    }

    @Test
    fun `403 spent and expired are told apart`() {
        startServer()
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"reason":"spent","message":"Used."}"""))
            server.enqueue(MockResponse().setResponseCode(403).setHeader("X-Rist-Enrol", "expired")
                .setBody("""{"detail":"enrollment refused"}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"enrollment refused"}"""))
            val spent = Enrolment.pairWithReply(ctx(), "PAIR7F3K")
            val expired = Enrolment.pairWithReply(ctx(), "PAIR7F3K")
            val bare = Enrolment.pairWithReply(ctx(), "PAIR7F3K")
            assertEquals(Enrolment.PairResult.SPENT, spent.result)
            assertEquals("Used.", Enrolment.explainPair(spent))
            assertEquals(Enrolment.PairResult.EXPIRED, expired.result)
            assertTrue(Enrolment.explainPair(expired).contains("expired"))
            assertFalse(Enrolment.explainPair(expired).contains("used"))
            assertEquals(Enrolment.PairResult.REFUSED, bare.result)
            assertFalse("a used code is never kept for another try", Enrolment.keepsCode(spent.result))
            assertFalse(Enrolment.keepsCode(expired.result))
            assertEquals("one request per press, no retries", 3, server.requestCount)
        } finally { stopServer() }
    }

    @Test
    fun `a server message is capped in length`() {
        val r = Enrolment.parseRefusal(null, JSONObject().put("message", "x".repeat(5000)).toString())
        assertEquals(Enrolment.MAX_SERVER_MESSAGE, r.message.length)
    }

    // ── settings display ──────────────────────────────────────────────────────────────────────

    @Test
    fun `settings shows the short device id and copies the full one`() {
        val full = "0123456789abcdef"
        Settings.Secure.putString(ctx().contentResolver, Settings.Secure.ANDROID_ID, full)
        assertEquals(full, Config.deviceId(ctx()))
        val a = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
        val info = a.findViewById<TextView>(R.id.deviceInfo)
        val shown = info.text.toString()
        assertTrue(shown, shown.contains(full.take(Config.SHORT_DEVICE_ID_LEN)))
        assertFalse("only the short form is shown", shown.contains(full))
        info.performClick()
        val clip = a.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(full, clip.getItemAt(0).text.toString())
    }

    @Test
    fun `a short id is shown whole`() {
        assertEquals("abc", Config.shortDeviceId("abc"))
        assertEquals("0123456789ab…", Config.shortDeviceId("0123456789abcdef"))
    }
}
