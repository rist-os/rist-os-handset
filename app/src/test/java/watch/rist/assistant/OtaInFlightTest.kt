package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class OtaInFlightTest {

    private val build = OtaFixtures.BUILD
    private val otherBuild = "2026080100"
    private val now = OtaFixtures.TIMESTAMP + 3600

    private var server: MockWebServer? = null

    private fun app(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        OtaState.clearOffer(app())
        OtaState.clearApprovals(app())
        OtaState.setApplyingBuild(app(), "", 0L)
        OtaState.setReadyBuild(app(), "")
        OtaState.setRefusedBuild(app(), "")
        OtaState.setLastNudgeAtSeconds(app(), 0L)
        OtaState.setNextCheckAtSeconds(app(), 0L)
        wifi()
    }

    @After
    fun tearDown() {
        runCatching { server?.shutdown() }
        server = null
    }

    @Test
    fun `handing a build over records it as in flight`() {
        OtaConsent.approve(app(), build, allowMetered = false)

        OtaScheduler.offer(app(), routedTo(packageServer()), OtaFixtures.manifest(), now, null)

        assertNotNull("fixture: the approved build should have been handed over",
            shadowOf(app() as android.app.Application).nextStartedService)
        assertEquals(
            "nothing recorded the apply, so the only update screen on this kiosk goes silent for " +
                "the length of a 1.6 GB download",
            build, OtaState.applyingBuild(app())
        )
        assertEquals("an undated claim cannot be aged out and becomes permanent",
            now, OtaState.applyingSinceSeconds(app()))
    }

    @Test
    fun `a build already downloading is not handed over again`() {
        OtaConsent.approve(app(), build, allowMetered = false)
        OtaState.setApplyingBuild(app(), build, now - 60)
        val s = packageServer()

        val next = OtaScheduler.offer(app(), routedTo(s), OtaFixtures.manifest(), now, null)

        assertNull(
            "the poller handed update_engine a build it is already downloading. It answers " +
                "'already processing', that is recorded as a failure, and the loop repeats.",
            shadowOf(app() as android.app.Application).nextStartedService
        )
        assertEquals("a download in flight must not even cost a preflight", 0, s.requestCount)
        assertTrue("the screen must keep saying what is happening",
            OtaState.lastResult(app()).contains("installing $build"))
        assertEquals(OtaScheduler.POLL_INTERVAL_SECONDS, next)
    }

    @Test
    fun `a stale in-flight claim is handed over again`() {
        OtaConsent.approve(app(), build, allowMetered = false)
        OtaState.setApplyingBuild(app(), build, now - OtaState.APPLYING_STALE_SECONDS - 1)

        OtaScheduler.offer(app(), routedTo(packageServer()), OtaFixtures.manifest(), now, null)

        assertNotNull(
            "a lost verdict wedged the update channel permanently: the flag says a download is " +
                "running, nothing will ever clear it, and no build is ever handed over again",
            shadowOf(app() as android.app.Application).nextStartedService
        )
    }

    @Test
    fun `a stale in-flight claim still has to pass the consent gate`() {
        OtaState.setApplyingBuild(app(), build, now - OtaState.APPLYING_STALE_SECONDS - 1)
        val s = packageServer()

        OtaScheduler.offer(app(), routedTo(s), OtaFixtures.manifest(), now, null)

        assertNull(
            "an expired in-flight flag was accepted as consent. Nobody approved this build and " +
                "update_engine is now streaming it.",
            shadowOf(app() as android.app.Application).nextStartedService
        )
        assertEquals("and it must not cost a preflight either", 0, s.requestCount)
    }

    @Test
    fun `a claim stamped in the future does not wedge the update channel`() {
        OtaConsent.approve(app(), build, allowMetered = false)
        OtaState.setApplyingBuild(app(), build, now + 7200)

        OtaScheduler.offer(app(), routedTo(packageServer()), OtaFixtures.manifest(), now, null)

        assertNotNull(
            "a stamp from a clock that has since gone backwards reads as 'started moments ago', " +
                "so the poller refuses to hand off — and keeps refusing until the clock catches up",
            shadowOf(app() as android.app.Application).nextStartedService
        )
    }

    @Test
    fun `an in-flight claim cannot itself start anything`() {
        OtaState.setApplyingBuild(app(), build, now - 60)

        OtaScheduler.offer(app(), routedTo(packageServer()), OtaFixtures.manifest(), now, null)

        assertNull(shadowOf(app() as android.app.Application).nextStartedService)
    }

    @Test
    fun `every verdict clears the in-flight flag`() {
        for (verdict in listOf(
            OtaService.VERDICT_APPLIED, OtaService.VERDICT_PERMANENT, OtaService.VERDICT_RETRY,
            OtaService.VERDICT_NEEDS_USER, OtaService.VERDICT_CORRUPTED, "something_new",
        )) {
            OtaState.setApplyingBuild(app(), build, now - 60)
            result(build, verdict)
            assertEquals(
                "$verdict left the screen claiming an install that has ended",
                "", OtaState.applyingBuild(app())
            )
            OtaState.setRefusedBuild(app(), "")
            OtaState.setReadyBuild(app(), "")
        }
    }

    @Test
    fun `a late verdict for another build leaves a live download alone`() {
        OtaState.setApplyingBuild(app(), build, now - 60)

        result(otherBuild, OtaService.VERDICT_RETRY)

        assertEquals(
            "a stale verdict for a different build erased the display of a running download",
            build, OtaState.applyingBuild(app())
        )
    }

    @Test
    fun `a blank verdict build clears nothing`() {
        OtaState.setApplyingBuild(app(), build, now - 60)
        result("", OtaService.VERDICT_RETRY)
        assertEquals(build, OtaState.applyingBuild(app()))
    }

    @Test
    fun `progress is recorded for the build in flight and dropped for any other`() {
        OtaState.setApplyingBuild(app(), build, now - 60)

        progress(build, OtaApply.Status.DOWNLOADING, 43)
        assertEquals(43, OtaState.applyingPercent(app()))
        assertEquals(OtaApply.Status.DOWNLOADING, OtaState.applyingStatus(app()))

        progress(otherBuild, OtaApply.Status.DOWNLOADING, 12)
        assertEquals(
            "a late reading for a replaced apply wound the bar backwards under a live download",
            43, OtaState.applyingPercent(app())
        )
    }

    @Test
    fun `a progress broadcast does not end the apply or spend consent`() {
        OtaState.setApplyingBuild(app(), build, now - 60)
        OtaConsent.approve(app(), build, allowMetered = true)

        progress(build, OtaApply.Status.DOWNLOADING, 43)

        assertEquals(build, OtaState.applyingBuild(app()))
        assertEquals("progress is not evidence about consent in either direction",
            build, OtaState.approvedBuild(app()))
        assertEquals(build, OtaState.meteredApprovedBuild(app()))
    }

    @Test
    fun `starting a new apply resets the bar`() {
        OtaState.setApplyingBuild(app(), build, now - 60)
        progress(build, OtaApply.Status.DOWNLOADING, 43)

        OtaState.setApplyingBuild(app(), otherBuild, now)

        assertEquals(
            "43% from the previous download is still on screen for a transfer that just began",
            OtaState.PERCENT_UNKNOWN, OtaState.applyingPercent(app())
        )
    }

    @Test
    fun `a restart into the staged build clears the restart prompt`() {
        OtaState.setReadyBuild(app(), build)
        OtaState.clearInFlightOnRestart(app(), runningBuild = build)
        assertEquals("the phone is running it; the instruction is now a permanent lie",
            "", OtaState.readyBuild(app()))
    }

    @Test
    fun `a restart that did not boot the staged build keeps the prompt`() {
        OtaState.setReadyBuild(app(), build)
        OtaState.clearInFlightOnRestart(app(), runningBuild = otherBuild)
        assertEquals(build, OtaState.readyBuild(app()))

        OtaState.clearInFlightOnRestart(app(), runningBuild = "")
        assertEquals("a build string we could not read is not evidence anything was installed",
            build, OtaState.readyBuild(app()))
    }

    @Test
    fun `a restart always ends an apply, and never an approval`() {
        OtaState.setApplyingBuild(app(), build, now - 60)
        OtaConsent.approve(app(), build, allowMetered = true)

        OtaState.clearInFlightOnRestart(app(), runningBuild = otherBuild)

        assertEquals("the :ota process died with the restart; nothing is in flight",
            "", OtaState.applyingBuild(app()))
        assertEquals(
            "rebooting a phone is not unsaying yes to an update. Clearing consent here would make " +
                "the user re-approve after every restart.",
            build, OtaState.approvedBuild(app())
        )
    }

    @Test
    fun `boot and package-replace reconcile, and an unknown action is refused`() {
        assertEquals(OtaScheduler.Wake.RESCHEDULE,
            OtaScheduler.wakeFor(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(OtaScheduler.Wake.RESCHEDULE,
            OtaScheduler.wakeFor(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(OtaScheduler.Wake.POLL, OtaScheduler.wakeFor(OtaScheduler.ACTION_TICK))
        assertEquals(
            "OtaAlarmReceiver is exported, so a catch-all else made a poll free to anybody who " +
                "reaches it. The manifest now fences it with our own signature-level OTA_WAKE " +
                "rather than the auto-granted RECEIVE_BOOT_COMPLETED, but this stays the second " +
                "fence: the action check is the only one that survives a manifest edit",
            OtaScheduler.Wake.IGNORE, OtaScheduler.wakeFor("android.intent.action.VIEW")
        )
        assertEquals("an empty action is not our tick", OtaScheduler.Wake.IGNORE,
            OtaScheduler.wakeFor(""))
    }

    @Test
    fun `a nudge is honoured, then rate limited, then honoured again`() {
        assertEquals(OtaScheduler.Nudge.Honour, OtaScheduler.nudgeDecision(0L, now))

        val d = OtaScheduler.nudgeDecision(now - 10, now)
        assertTrue("a second nudge inside the window must be deferred, not dropped",
            d is OtaScheduler.Nudge.Defer)
        assertEquals(
            "the deferral is an ABSOLUTE time derived from the last honoured nudge; anything " +
                "relative to 'now' is pushed forward by every further wake and never fires",
            OtaScheduler.NUDGE_MIN_GAP_SECONDS - 10, (d as OtaScheduler.Nudge.Defer).seconds
        )

        assertEquals(OtaScheduler.Nudge.Honour,
            OtaScheduler.nudgeDecision(now - OtaScheduler.NUDGE_MIN_GAP_SECONDS, now))
        assertEquals("a stored timestamp from a wrong clock must not park nudges for a decade",
            OtaScheduler.Nudge.Honour, OtaScheduler.nudgeDecision(now + 86400, now))
    }

    @Test
    fun `an honoured check now is stamped and a repeat is refused`() {
        assertTrue("the first press must be honoured", OtaScheduler.requestCheckNow(app()))
        assertTrue("the stamp is what makes the limit survive a restart of this process",
            OtaState.lastNudgeAtSeconds(app()) > 0L)
        assertFalse("a second press moments later cannot be carrying news the first did not",
            OtaScheduler.requestCheckNow(app()))
    }

    @Test
    fun `check now cannot start a download`() {
        OtaScheduler.requestCheckNow(app())
        val s = packageServer()

        OtaScheduler.offer(app(), routedTo(s), OtaFixtures.manifest(), now, null)

        assertNull(
            "pressing CHECK NOW started an unapproved download. The Settings screen is not a " +
                "consent surface and must never become one.",
            shadowOf(app() as android.app.Application).nextStartedService
        )
        assertEquals(0, s.requestCount)
    }

    @Test
    fun `a package that cannot be fetched withdraws the offer and the approval`() {
        OtaState.recordOffer(app(), build, OtaFixtures.PAYLOAD_SIZE)
        OtaConsent.approve(app(), build, allowMetered = true)
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }
        s.start()
        server = s

        OtaScheduler.offer(app(), routedTo(s), OtaFixtures.manifest(), now, null)

        assertEquals("a build whose bytes cannot be got is not on the table",
            "", OtaState.offeredBuild(app()))
        assertEquals(
            "the approval outlived the build it was given for. It will be spent the moment the " +
                "package comes back, with no second question.",
            "", OtaState.approvedBuild(app())
        )
        assertEquals("", OtaState.meteredApprovedBuild(app()))
    }

    @Test
    fun `the progress throttle lets a phase change through and holds a percent step`() {
        val t = 1_000_000L
        assertTrue("the first reading replaces 'nothing is happening' and must not wait",
            otaShouldReportProgress(-1, -1, 0L, 0, OtaApply.Status.DOWNLOADING, t))
        assertFalse("an identical reading changes nothing on screen",
            otaShouldReportProgress(43, 3, t, 43, 3, t + 5_000))
        assertFalse("a percent step inside the window is re-offered moments later",
            otaShouldReportProgress(43, 3, t, 44, 3, t + 500))
        assertTrue(otaShouldReportProgress(43, 3, t, 44, 3, t + OTA_PROGRESS_MIN_INTERVAL_MS))
        assertTrue(
            "DOWNLOADING -> VERIFYING was throttled away, so the screen says 'Downloading 99%' " +
                "for the minutes the verifier runs and nothing ever re-sends it",
            otaShouldReportProgress(100, OtaApply.Status.DOWNLOADING, t, 0,
                OtaApply.Status.VERIFYING, t + 10)
        )
        assertTrue("an NTP correction must not freeze the bar for the length of the jump",
            otaShouldReportProgress(43, 3, t, 44, 3, t - 60_000))
    }

    @Test
    fun `arming a poll records when it is due`() {
        OtaScheduler.schedule(app(), 3600L)
        val at = OtaState.nextCheckAtSeconds(app())
        assertTrue("the 'next check' line has nothing to print", at > 0L)
        assertTrue("expected roughly an hour out, got $at",
            at >= OtaScheduler.trustedNowSeconds() + 3500)
    }

    private fun result(build: String, verdict: String, detail: String = "") {
        OtaResultReceiver().onReceive(
            app(),
            Intent(OtaService.ACTION_RESULT)
                .putExtra(OtaService.EXTRA_BUILD, build)
                .putExtra(OtaService.EXTRA_VERDICT, verdict)
                .putExtra(OtaService.EXTRA_DETAIL, detail)
        )
    }

    private fun progress(build: String, status: Int, percent: Int) {
        OtaResultReceiver().onReceive(
            app(),
            Intent(OtaService.ACTION_PROGRESS)
                .putExtra(OtaService.EXTRA_BUILD, build)
                .putExtra(OtaService.EXTRA_STATUS, status)
                .putExtra(OtaService.EXTRA_PERCENT, percent)
        )
    }

    private fun packageServer(): MockWebServer {
        val cr = "bytes ${OtaFixtures.PAYLOAD_OFFSET}-${OtaFixtures.PAYLOAD_OFFSET + 3}/" +
            "${OtaFixtures.ZIP_SIZE}"
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val r = MockResponse().setResponseCode(206).setHeader("Content-Range", cr)
                return if (request.method == "HEAD") r else r.setBody(OtaRange.PAYLOAD_MAGIC)
            }
        }
        s.start()
        server = s
        return s
    }

    private fun routedTo(s: MockWebServer): OkHttpClient =
        OtaCheck.defaultClient().newBuilder().addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(
                req.newBuilder()
                    .url(req.url.newBuilder().scheme("http").host(s.hostName).port(s.port).build())
                    .build()
            )
        }.build()

    private fun wifi() {
        val cm = app().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }
}
