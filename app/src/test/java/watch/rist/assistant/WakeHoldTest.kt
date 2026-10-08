package watch.rist.assistant

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.WakeSignal
import java.util.concurrent.TimeUnit

/** The held wake connection and the alarm for the next scheduled instruction (backend v27). */
@RunWith(RobolectricTestRunner::class)
class WakeHoldTest {

    private lateinit var server: MockWebServer
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        DueAlarm.fired()
        WakeLoop.takeRefresh()
    }

    @After
    fun stop() {
        runCatching { server.shutdown() }
        DueAlarm.fired()
    }

    private fun dueAlarms() = shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.action == DueAlarm.ACTION }

    @Test
    fun `every poll declares the held connection and the hold it wants`() {
        val u = WakeLoop.wakeUrl("https://api.example/v1/device", emptyList(), 8, holdS = 210)!!.toHttpUrl()
        assertEquals("210", u.queryParameter("hold_s"))
        assertEquals(WakeLoop.HOLD_V2_COMPONENT, u.queryParameter("components"))
        val withBoxes = WakeLoop.wakeUrl("https://api.example/v1/device", emptyList(), 8,
            boxesVersion = 3, holdS = 600)!!.toHttpUrl()
        assertEquals("${HomeBoxes.COMPONENT},${WakeLoop.HOLD_V2_COMPONENT}", withBoxes.queryParameter("components"))
    }

    @Test
    fun `the read timeout outlasts the hold asked for`() {
        assertEquals(WakeLoop.HOLD_MAX_S + WakeLoop.HOLD_GRACE_S, WakeLoop.readTimeoutS(WakeLoop.HOLD_MAX_S))
        assertEquals(WakeLoop.READ_TIMEOUT_S, WakeLoop.readTimeoutS(10))
    }

    @Test
    fun `a hold that died silently is noticed before the legacy idle gap would have ended`() {
        // Legacy worst case for a notice: the backend's 240 s idle poll_after_s.
        val legacyGapS = 240L
        assertTrue(WakeLoop.readTimeoutS(WakeLoop.HOLD_MAX_S) <= legacyGapS)
        assertTrue(WakeLoop.readTimeoutS(WakeLoop.HOLD_MIN_S) <= legacyGapS)
    }

    @Test
    fun `a silent drop halves the hold, never below the floor, and full holds grow it back`() {
        val t = WakeLoop.HoldTuner()
        assertEquals(WakeLoop.HOLD_MAX_S, t.holdS)
        t.onSilentDrop()
        assertEquals(WakeLoop.HOLD_MAX_S / 2, t.holdS)
        repeat(5) { t.onSilentDrop() }
        assertEquals(WakeLoop.HOLD_MIN_S, t.holdS)
        // An answer that came early (a notice) proves nothing about how long the path holds.
        repeat(5) { t.onAnswered(1_000) }
        assertEquals(WakeLoop.HOLD_MIN_S, t.holdS)
        repeat(WakeLoop.HOLD_GROW_AFTER) { t.onAnswered(t.holdS * 1000) }
        assertEquals(WakeLoop.HOLD_MIN_S * 3 / 2, t.holdS)
        repeat(WakeLoop.HOLD_GROW_AFTER) { t.onSilentDrop(); }
        assertEquals(WakeLoop.HOLD_MIN_S, t.holdS)
        repeat(40) { t.onAnswered(t.holdS * 1000) }
        assertEquals(WakeLoop.HOLD_MAX_S, t.holdS)
    }

    @Test
    fun `only a timeout after the whole hold counts as a silent drop`() {
        assertTrue(WakeLoop.isSilentDrop(230_000, 210))
        assertFalse("a connect timeout is a bad network, not a dropped hold", WakeLoop.isSilentDrop(5_000, 210))
    }

    @Test
    fun `a hold that never answers is reported as a silent drop, not an ordinary failure`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        val out = WakeLoop.exchange(http, server.url("/v1/device/wake").toString(), "Bearer t", "d", emptyList())
        assertTrue(out is WakeLoop.Outcome.Retry && out.silent)
        server.enqueue(MockResponse().setResponseCode(503))
        val failed = WakeLoop.exchange(http, server.url("/v1/device/wake").toString(), "Bearer t", "d", emptyList())
        assertTrue(failed is WakeLoop.Outcome.Retry && !failed.silent)
    }

    @Test
    fun `a hold the server replaced is reported as superseded`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val out = WakeLoop.exchange(OkHttpClient(), server.url("/v1/device/wake").toString(), "Bearer t", "d", emptyList())
        assertTrue(out is WakeLoop.Outcome.Retry && out.why.endsWith("superseded"))
    }

    @Test
    fun `a due alarm drops the current hold and marks the poll for an immediate retry`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        var out: WakeLoop.Outcome? = null
        val t = Thread { out = WakeLoop.exchange(http, server.url("/v1/device/wake").toString(), "Bearer t", "d", emptyList()) }
        t.start()
        server.takeRequest(5, TimeUnit.SECONDS)
        val started = System.currentTimeMillis()
        WakeLoop.refreshNow(ctx)
        t.join(5_000)
        assertTrue("the held call ended at once", System.currentTimeMillis() - started < 3_000)
        assertTrue(out is WakeLoop.Outcome.Retry)
        assertTrue(WakeLoop.takeRefresh())
        assertFalse("taken once", WakeLoop.takeRefresh())
    }

    @Test
    fun `the next due time sets one exact alarm just after it, and none clears it`() {
        val now = System.currentTimeMillis()
        val due = now + 600_000
        WakeLoop.apply(ctx, WakeSignal.newBuilder().setNextDueAtEpochMs(due).build(), emptyList())
        val alarms = dueAlarms()
        assertEquals(1, alarms.size)
        assertEquals(due + DueAlarm.DELAY_MS, alarms[0].triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, alarms[0].type)

        // A later wake moves it; a wake that says nothing is due clears it.
        WakeLoop.apply(ctx, WakeSignal.newBuilder().setNextDueAtEpochMs(due + 60_000).build(), emptyList())
        assertEquals(listOf(due + 60_000 + DueAlarm.DELAY_MS), dueAlarms().map { it.triggerAtTime })
        WakeLoop.apply(ctx, WakeSignal.getDefaultInstance(), emptyList())
        assertTrue(dueAlarms().isEmpty())
    }

    @Test
    fun `a due time already past sets nothing`() {
        assertNull(DueAlarm.fireAtMs(0, 1_000))
        assertNull(DueAlarm.fireAtMs(1_000, 10_000))
        assertEquals(20_000 + DueAlarm.DELAY_MS, DueAlarm.fireAtMs(20_000, 10_000))
    }

    @Test
    fun `an idle answer from the held connection means poll again within a second`() {
        val signal = WakeSignal.newBuilder().setPollAfterS(0).build()
        assertEquals(WakeLoop.POLL_GAP_MIN_MS, WakeLoop.pollGapMs(signal.pollAfterS))
    }

}
