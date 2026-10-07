package watch.rist.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import rist.v1.DeviceResponse
import rist.v1.Speech

/** 403 is still "revoked", but a revoked phone can come back without a factory reset. */
@RunWith(RobolectricTestRunner::class)
class RevokeRecoveryTest {

    private lateinit var server: MockWebServer
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun start() {
        Config.setDeployDefaultsForTest("", "")
        server = MockWebServer()
        server.start()
        Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
        Config.setEnrolRevoked(ctx, false)
        Enrolment.clear(ctx)
        StreamingCancel.resetForTest()
    }

    @After
    fun stop() {
        Config.setEnrolRevoked(ctx, false)
        Enrolment.clear(ctx)
        Config.setRistNumber(ctx, "")
        Config.clearDeployDefaultsForTest()
        runCatching { server.shutdown() }
    }

    @Test
    fun `a 403 turn still revokes, and says how to come back`() {
        server.enqueue(MockResponse().setResponseCode(403))
        val up = Uploader(ctx)
        up.sendText("hello")
        assertTrue(Config.enrolRevoked(ctx))
        assertTrue("the failure must point at re-pairing: ${up.lastFailure}", up.lastFailure.contains("pair it again"))
    }

    @Test
    fun `a served turn after a revocation clears it`() {
        Config.setEnrolRevoked(ctx, true)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(
            DeviceResponse.newBuilder().setSpeech(Speech.newBuilder().setText("ok")).build().toByteArray()
        )))
        Uploader(ctx).sendText("hello")
        assertFalse("a reinstated device must not stay marked revoked", Config.enrolRevoked(ctx))
    }

    @Test
    fun `a revoked phone may pair again`() {
        Config.setEnrolRevoked(ctx, true)
        assertTrue(Enrolment.canPair(ctx))
        Config.setEnrolRevoked(ctx, false)
        assertEquals(Enrolment.needed(ctx), Enrolment.canPair(ctx))
    }

    @Test
    fun `revocation does not block the enrolment flow`() {
        Config.setEnrolRevoked(ctx, true)
        Config.setRistNumber(ctx, "+15550100")
        val r = Enrolment.readiness(ctx)
        assertNotEquals(Enrolment.Readiness.ALREADY_ENROLLED, r)
        assertNotEquals(Enrolment.Readiness.BACKING_OFF, r)
    }

    @Test
    fun `onReinstated clears only a revocation`() {
        Enrolment.onReinstated(ctx)
        assertFalse(Config.enrolRevoked(ctx))
        Enrolment.onRevoked(ctx)
        assertTrue(Config.enrolRevoked(ctx))
        Enrolment.onReinstated(ctx)
        assertFalse(Config.enrolRevoked(ctx))
    }

    @Test
    fun `a new backend forgets the old one's revocation`() {
        Config.setEnrolRevoked(ctx, true)
        Config.setBackendEndpoint(ctx, "https://other.example/v1/device")
        assertFalse(Config.enrolRevoked(ctx))
    }

    @Test
    fun `enrolment backs off instead of giving up after three tries`() {
        for (n in 0 until Enrolment.QUICK_ATTEMPTS) assertEquals(0L, Enrolment.retryDelayMs(n))
        var last = 0L
        for (n in Enrolment.QUICK_ATTEMPTS until 40) {
            val d = Enrolment.retryDelayMs(n)
            assertTrue("attempt $n has no wait", d > 0)
            assertTrue("attempt $n waits less than the one before", d >= last)
            assertTrue("attempt $n waits past the ceiling", d <= Enrolment.BACKOFF_MAX_MS)
            last = d
        }
        assertEquals(Enrolment.BACKOFF_MAX_MS, Enrolment.retryDelayMs(1_000))
    }

    @Test
    fun `after the quick tries the phone waits, then is ready again on its own`() {
        Config.setRistNumber(ctx, "+15550100")
        Config.setEnrolAttempts(ctx, 5)
        Config.setEnrolSentAtMs(ctx, System.currentTimeMillis())
        assertEquals(Enrolment.Readiness.BACKING_OFF, Enrolment.readiness(ctx))
        assertTrue(Enrolment.explain(Enrolment.Readiness.BACKING_OFF).contains("try again on its own"))

        Config.setEnrolSentAtMs(ctx, System.currentTimeMillis() - Enrolment.retryDelayMs(5) - 1)
        assertNotEquals(
            "the wait ended and the phone still will not try",
            Enrolment.Readiness.BACKING_OFF, Enrolment.readiness(ctx)
        )
    }

    @Test
    fun `the wake loop asks a revoked token again, slowly`() {
        Config.setEnrolRevoked(ctx, true)
        assertEquals(
            "a revoked phone with no token has nothing to poll with, but is not refused locally",
            WakeLoop.Outcome.NotReady, WakeLoop.poll(ctx)
        )
        assertTrue(WakeLoop.REVOKED_RECHECK_MS in 60_000L..24L * 60 * 60 * 1000)
    }

    @Test
    fun `a revoked token is sat out for the hour, kicks or not, then asked again`() {
        val t0 = 1_000_000L
        val until = WakeLoop.revokedUntil(t0)
        assertTrue(WakeLoop.sitsOut("Bearer old", "Bearer old", until, t0 + 1))
        assertTrue(WakeLoop.sitsOut("Bearer old", "Bearer old", until, until - 1))
        assertFalse("the hourly re-ask never comes", WakeLoop.sitsOut("Bearer old", "Bearer old", until, until))
    }

    @Test
    fun `a new token from a re-pair is polled with at once, not after the revoked token's hour`() {
        val t0 = 1_000_000L
        val until = WakeLoop.revokedUntil(t0)
        assertFalse(WakeLoop.sitsOut("Bearer new", "Bearer old", until, t0 + 1))
        assertFalse(WakeLoop.sitsOut(null, "Bearer old", until, t0 + 1))
    }

    @Test
    fun `a 401'd token is sat out until it changes`() {
        assertTrue(WakeLoop.sitsOut("Bearer dead", "Bearer dead", Long.MAX_VALUE, Long.MAX_VALUE - 1))
    }

    @Test
    fun `a send stamped in the future by a wrong clock does not hold enrolment off`() {
        Config.setRistNumber(ctx, "+15550100")
        Config.setEnrolAttempts(ctx, 20)
        Config.setEnrolSentAtMs(ctx, System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
        assertNotEquals(Enrolment.Readiness.BACKING_OFF, Enrolment.readiness(ctx))
    }
}
