package watch.rist.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import rist.v1.DeviceResponse
import rist.v1.Speech

/** A lapsed subscription: 402 means pay, never "this device is done". */
@RunWith(RobolectricTestRunner::class)
class BillingLapseTest {

    private lateinit var server: MockWebServer
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val renewLine = "Your Rist Assistant subscription has ended — renew at ristmobile.com."

    @Before
    fun start() {
        Config.setDeployDefaultsForTest("", "")
        server = MockWebServer()
        server.start()
        Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
        Config.setEnrolRevoked(ctx, false)
        Config.setCredentialRejected(ctx, false)
        Config.clearBillingLapse(ctx)
        StreamingCancel.resetForTest()
    }

    @After
    fun stop() {
        Config.clearBillingLapse(ctx)
        Config.setEnrolRevoked(ctx, false)
        Config.clearDeployDefaultsForTest()
        runCatching { server.shutdown() }
    }

    private fun spoken(text: String) = Buffer().write(
        DeviceResponse.newBuilder().setStatus(1).setRequestId("r402")
            .setSpeech(Speech.newBuilder().setText(text)).build().toByteArray()
    )

    private fun lapsed(body: Buffer? = spoken(renewLine), reason: String = "lapsed") =
        MockResponse().setResponseCode(402)
            .setHeader("Content-Type", "application/x-protobuf")
            .setHeader("X-Rist-Billing", reason)
            .setHeader("X-Rist-Renew-Url", "ristmobile.com")
            .setHeader("X-Rist-Billing-Portal", "/v1/billing/portal")
            .apply { if (body != null) setBody(body) }

    private fun answered(text: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/x-protobuf")
        .setBody(Buffer().write(DeviceResponse.newBuilder().setSpeech(Speech.newBuilder().setText(text)).build().toByteArray()))

    @Test
    fun `a 402 turn is answered with the backend's own renew line`() {
        server.enqueue(lapsed())
        val up = Uploader(ctx)
        val reply = up.sendText("what's the weather")
        assertNotNull("the 402's spoken body must come back as the reply, like the 413's", reply)
        assertEquals(renewLine, reply!!.speech.text)
        assertEquals("r402", reply.requestId)
        assertEquals("lapsed", up.lastLapse?.reason)
        assertEquals("", up.lastFailure)
    }

    @Test
    fun `a 402 never revokes, never kills the credential, and records the lapse from the headers`() {
        server.enqueue(lapsed(reason = "disputed"))
        Uploader(ctx).sendText("hello")
        assertFalse("402 must never set enrolRevoked", Config.enrolRevoked(ctx))
        assertFalse("402 must never mark the credential dead", Config.credentialRejected(ctx))
        val lapse = Billing.lapse(ctx)!!
        assertEquals("disputed", lapse.reason)
        assertEquals("ristmobile.com", lapse.renewUrl)
        assertEquals("/v1/billing/portal", lapse.portalPath)
        assertEquals(renewLine, Billing.notice(ctx))
    }

    @Test
    fun `an unreadable 402 body falls back to the fixed sentence, with the header's address`() {
        server.enqueue(lapsed(body = null).setHeader("X-Rist-Renew-Url", "example.org/renew"))
        val up = Uploader(ctx)
        assertNull(up.sendText("hello"))
        assertEquals(
            "Your Rist Assistant subscription has ended — renew at example.org/renew.",
            up.lastFailure
        )
        assertFalse(Config.enrolRevoked(ctx))
    }

    @Test
    fun `the first served turn after a lapse clears it, with nothing to reset`() {
        server.enqueue(lapsed())
        server.enqueue(answered("It's sunny."))
        Uploader(ctx).sendText("weather")
        assertNotNull(Billing.lapse(ctx))
        val reply = Uploader(ctx).sendText("weather")
        assertEquals("It's sunny.", reply?.speech?.text)
        assertNull("a paid-up user must not keep seeing the lapse", Billing.lapse(ctx))
    }

    @Test
    fun `a confirmation refused for billing is handled the same way`() {
        server.enqueue(lapsed())
        val up = Uploader(ctx)
        val reply = up.sendConfirmation("act-1", true)
        assertEquals(renewLine, reply?.speech?.text)
        assertFalse(Config.enrolRevoked(ctx))
        assertNotNull(up.lastLapse)
    }

    @Test
    fun `a lapse is never retried on its own`() {
        server.enqueue(lapsed())
        Uploader(ctx).sendText("hello")
        assertEquals("one press, one request: a turn repeats real-world actions", 1, server.requestCount)
    }

    @Test
    fun `429 and 503 say what happened, not a bare status code`() {
        server.enqueue(MockResponse().setResponseCode(429))
        val busy = Uploader(ctx).also { it.sendText("hi") }.lastFailure
        server.enqueue(MockResponse().setResponseCode(503))
        val down = Uploader(ctx).also { it.sendText("hi") }.lastFailure
        assertEquals("the assistant is busy — try again in a moment", busy)
        assertEquals("the assistant is briefly unavailable — trying again shortly", down)
        assertNull("neither is a billing lapse", Billing.lapse(ctx))
        assertFalse(Config.enrolRevoked(ctx))
    }

    @Test
    fun `headers are optional, and the defaults are the contract's`() {
        val l = Billing.lapseFrom(null, " ", "not-a-path")
        assertEquals("lapsed", l.reason)
        assertEquals(Billing.DEFAULT_RENEW_URL, l.renewUrl)
        assertEquals(Billing.DEFAULT_PORTAL_PATH, l.portalPath)
        assertEquals("/v2/pay", Billing.lapseFrom("ended", "x.com", "/v2/pay").portalPath)
    }

    @Test
    fun `the portal address is built from the backend and the header's path`() {
        assertEquals("https://api.example/v1/billing/portal",
            Billing.portalUrl("https://api.example/v1/device", "/v1/billing/portal"))
        assertEquals("https://api.example/v2/pay",
            Billing.portalUrl("https://api.example/", "/v2/pay"))
        assertNull(Billing.portalUrl("", "/v1/billing/portal"))
    }

    @Test
    fun `only a Stripe billing page over https may open in the locked browser`() {
        assertEquals("https://billing.stripe.com/p/session_abc",
            Billing.openablePortal("https://billing.stripe.com/p/session_abc"))
        assertNull(Billing.openablePortal("http://billing.stripe.com/p/session_abc"))
        assertNull(Billing.openablePortal("https://evil.example/p/session_abc"))
        assertNull(Billing.openablePortal("https://billing.stripe.com.evil.example/p"))
        assertNull(Billing.openablePortal("https://me@billing.stripe.com/p"))
        assertNull(Billing.openablePortal(""))
    }

    @Test
    fun `portal answers map to what the contract says to do`() {
        assertEquals(Billing.Portal.Open("https://billing.stripe.com/p/s"),
            Billing.classifyPortal(200, """{"url":"https://billing.stripe.com/p/s"}"""))
        assertEquals(Billing.Portal.Unavailable, Billing.classifyPortal(200, """{"url":"https://evil.example/"}"""))
        assertEquals(Billing.Portal.Unavailable, Billing.classifyPortal(200, "not json"))
        assertEquals(Billing.Portal.NoSubscription, Billing.classifyPortal(404, ""))
        assertEquals(Billing.Portal.Unauthorised, Billing.classifyPortal(401, ""))
        assertEquals(Billing.Portal.Revoked, Billing.classifyPortal(403, ""))
        assertEquals(Billing.Portal.Unavailable, Billing.classifyPortal(502, ""))
        assertEquals(Billing.Portal.Unavailable, Billing.classifyPortal(503, ""))
        assertEquals("Couldn't open the payment page — try again in a minute.",
            Billing.explain(Billing.Portal.Unavailable))
    }

    @Test
    fun `no token means the portal is never asked`() {
        assertEquals(Billing.Portal.Unauthorised, Billing.fetchPortal(ctx))
        assertEquals(0, server.requestCount)
        assertFalse("a missing token is not a dead credential", Config.credentialRejected(ctx))
    }

    @Test
    fun `a 404 from the portal hides the button until the lapse ends`() {
        Billing.onLapsed(ctx, Billing.lapseFrom("lapsed", null, null))
        assertTrue(Billing.offersPayment(ctx))
        Config.setBillingNoPortal(ctx, true)
        assertFalse(Billing.offersPayment(ctx))
        Billing.onServed(ctx)
        assertFalse(Config.billingNoPortal(ctx))
        assertNull(Billing.notice(ctx))
    }

    @Test
    fun `a new backend forgets the old one's lapse`() {
        Billing.onLapsed(ctx, Billing.lapseFrom("lapsed", null, null))
        Config.setBackendEndpoint(ctx, "https://other.example/v1/device")
        assertNull(Billing.lapse(ctx))
    }

    @Test
    fun `the wake channel reads a 402 as a lapse, not a revocation`() {
        server.enqueue(lapsed(body = null, reason = "ended"))
        val http = okhttp3.OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        val url = WakeLoop.wakeUrl(server.url("/v1/device").toString(), emptyList(), 8)!!
        val out = WakeLoop.exchange(http, url, "Bearer tok", "dev1", emptyList())
        assertTrue("402 was read as $out", out is WakeLoop.Outcome.Lapsed)
        assertEquals("ended", (out as WakeLoop.Outcome.Lapsed).lapse.reason)
        assertTrue("its wait is a slow re-ask, not the 1s retry", WakeLoop.LAPSED_RECHECK_MS >= 60_000L)
    }

    @Test
    fun `media progress ignores a 402 and changes nothing`() {
        server.enqueue(MockResponse().setResponseCode(402))
        Uploader.sendProgress(ctx, rist.v1.MediaProgress.newBuilder().setItemId("b1").build())
        assertEquals(1, server.requestCount)
        assertFalse(Config.enrolRevoked(ctx))
        assertFalse(Config.credentialRejected(ctx))
    }

    @Test
    fun `pairing answered with 402 says so and keeps the code`() {
        assertEquals(Enrolment.PairResult.PAYMENT_REQUIRED, Enrolment.classifyPair(402, tokenBlank = true))
        assertTrue(Enrolment.explainPair(Enrolment.PairResult.PAYMENT_REQUIRED).contains("has not been used"))
        assertFalse(Config.enrolRevoked(ctx))
    }
}
