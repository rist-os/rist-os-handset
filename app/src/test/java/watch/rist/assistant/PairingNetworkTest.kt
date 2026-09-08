package watch.rist.assistant

import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PairingNetworkTest {

    // Always takeRequest(timeout, unit): the no-argument overload blocks forever if nothing was sent.

    private lateinit var server: MockWebServer

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val goodCode = "PAIR7F3K"

    @Before
    fun start() {
        // A compiled-in https default would make Config discard the cleartext MockWebServer override.
        Config.setDeployDefaultsForTest("", "")

        server = MockWebServer()
        server.start()
        Config.setBackendEndpoint(ctx(), server.url("/v1/device").toString())
        assertEquals(
            "the fixture's endpoint did not take effect -- Config.resolveBackend discarded it, so " +
                "these tests would be talking to the compiled-in host, not the MockWebServer",
            server.url("/v1/device").toString(),
            Config.backendUrl(ctx())
        )
        Config.setAuthToken(ctx(), "")
    }

    @After
    fun stop() {
        Config.clearDeployDefaultsForTest()
        runCatching { server.shutdown() }
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun pairAgainst(status: Int, body: String = ""): Enrolment.PairResult {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        return Enrolment.pair(ctx(), goodCode)
    }

    @Test
    fun `the pairing request is a POST to v1 enroll carrying the typed code in the nonce field`() {
        server.enqueue(ok("""{"token":"ristd_abc.def"}"""))
        Enrolment.pair(ctx(), goodCode)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertTrue(
            "the enrol endpoint must be derived from the backend URL, not guessed; got ${req.path}",
            req.path!!.endsWith("/v1/enroll")
        )
        assertTrue(
            "the /v1/device suffix must be replaced, not appended to; got ${req.path}",
            !req.path!!.contains("/v1/device")
        )
        assertTrue(
            "the body must be declared as JSON or the backend will not parse it",
            req.getHeader("Content-Type").orEmpty().startsWith("application/json")
        )

        val body = JSONObject(req.body.readUtf8())
        assertEquals("the typed code travels as `nonce`", goodCode, body.optString("nonce"))
        assertTrue("the backend binds the grant to a device_id", body.optString("device_id").isNotBlank())
        assertTrue("the label is what the user sees in their device list", body.optString("label").isNotBlank())
    }

    @Test
    fun `a pasted code reaches the backend without the whitespace around it`() {
        server.enqueue(ok("""{"token":"ristd_abc.def"}"""))
        Enrolment.pair(ctx(), "  $goodCode\n")
        assertEquals(goodCode, JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).optString("nonce"))
    }

    @Test
    fun `a 200 carrying a token is not reported as success when the store silently drops it`() {
        assertEquals(
            "no Android Keystore under Robolectric, so KEY_AUTH_TOKEN is filtered out on write; " +
                "this is the production Keystore-fault path, not a test artefact",
            Enrolment.PairResult.STORE_FAILED,
            pairAgainst(200, """{"token":"ristd_abc.def"}""")
        )
        assertEquals(
            "nothing may be left behind that makes the device look enrolled",
            "", Config.authToken(ctx())
        )
        assertTrue("the request must actually have been sent", server.requestCount == 1)
    }

    @Test
    fun `a dropped store is distinguishable from every other failure the user could hit`() {
        val verdict = pairAgainst(200, """{"token":"ristd_abc.def"}""")
        assertNotEquals(Enrolment.PairResult.OK, verdict)
        assertNotEquals(Enrolment.PairResult.NETWORK, verdict)
        assertNotEquals(Enrolment.PairResult.NOT_GRANTED, verdict)
    }

    @Test
    fun `a 200 with an empty JSON body is NOT_GRANTED and never OK`() {
        val verdict = pairAgainst(200, "{}")
        assertEquals(Enrolment.PairResult.NOT_GRANTED, verdict)
        assertNotEquals(Enrolment.PairResult.OK, verdict)
        assertEquals("", Config.authToken(ctx()))
    }

    @Test
    fun `a 200 whose token field is blank is NOT_GRANTED`() {
        assertEquals(Enrolment.PairResult.NOT_GRANTED, pairAgainst(200, """{"token":"   "}"""))
    }

    @Test
    fun `a 200 that is not JSON at all neither crashes nor pairs the device`() {
        val verdict = pairAgainst(200, "<html><body>captive portal</body></html>")
        assertNotEquals(Enrolment.PairResult.OK, verdict)
        assertEquals(
            "an unparseable 2xx carried no token, so it is a non-grant, not a transport failure",
            Enrolment.PairResult.NOT_GRANTED, verdict
        )
        assertEquals("", Config.authToken(ctx()))
    }

    @Test
    fun `a 404 from the real endpoint means the code is not recognised`() {
        assertEquals(Enrolment.PairResult.NOT_RECOGNISED, pairAgainst(404, """{"detail":"no such nonce"}"""))
    }

    @Test
    fun `a 403 from the real endpoint means the code is refused`() {
        assertEquals(Enrolment.PairResult.REFUSED, pairAgainst(403))
    }

    @Test
    fun `a 422 from the real endpoint means the request was malformed`() {
        assertEquals(Enrolment.PairResult.MALFORMED, pairAgainst(422, """{"detail":"nonce too short"}"""))
    }

    @Test
    fun `a 503 from the real endpoint is treated as a transient network failure`() {
        assertEquals(Enrolment.PairResult.NETWORK, pairAgainst(503))
    }

    @Test
    fun `a 429 from the real endpoint means locked out rather than a network problem`() {
        val verdict = pairAgainst(429)
        assertEquals(Enrolment.PairResult.LOCKED_OUT, verdict)
        assertNotEquals(Enrolment.PairResult.NETWORK, verdict)
    }

    @Test
    fun `a code shorter than the minimum is rejected without reaching the server`() {
        assertEquals(Enrolment.PairResult.MALFORMED, Enrolment.pair(ctx(), "SHORT"))
        assertEquals("a too-short code must not cost a round trip", 0, server.requestCount)
    }

    @Test
    fun `a code that is only long enough because of padding is still rejected offline`() {
        assertEquals(Enrolment.PairResult.MALFORMED, Enrolment.pair(ctx(), "   ABC   "))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an endpoint with nothing listening is a network failure and never a pairing`() {
        server.shutdown()
        val verdict = Enrolment.pair(ctx(), goodCode)
        assertEquals(Enrolment.PairResult.NETWORK, verdict)
        assertNotEquals(Enrolment.PairResult.OK, verdict)
        assertEquals("", Config.authToken(ctx()))
    }

    @Test
    fun `a server that hangs up mid-response is a network failure`() {
        server.enqueue(
            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)
        )
        assertEquals(Enrolment.PairResult.NETWORK, Enrolment.pair(ctx(), goodCode))
    }

    @Test
    fun `pairing with no endpoint set says so instead of blaming the network`() {
        Config.clearBackendOverride(ctx())
        val verdict = Enrolment.pair(ctx(), goodCode)
        assertEquals(Enrolment.PairResult.NO_ENDPOINT, verdict)
        assertEquals("nothing may be sent when there is nowhere to send it", 0, server.requestCount)
    }

    @Test
    fun `a lowercase code is uppercased before it reaches the backend`() {
        server.enqueue(MockResponse().setResponseCode(404))
        Enrolment.pair(ctx(), "abcd2345")
        val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals(
            "a lowercase code reached the wire and would 404 against a byte-exact comparison",
            "ABCD2345", body.getString("nonce")
        )
    }

    @Test
    fun `a pasted code is trimmed and uppercased together`() {
        server.enqueue(MockResponse().setResponseCode(404))
        Enrolment.pair(ctx(), "  aBcD2345\n")
        val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals("ABCD2345", body.getString("nonce"))
    }
}
