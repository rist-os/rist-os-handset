package watch.rist.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class OtaStatusWordingTest {

    private val now = 1_800_000_000L

    private fun app(): Context = ApplicationProvider.getApplicationContext()

    private fun lineAfter(result: String): String {
        ShadowLog.clear()
        OtaState.recordCheck(app(), now, result)
        val v = OtaStatusView(
            build = "2026100100", device = "stallion", channel = "stable",
            baseUrl = "https://ota.example.com",
            lastCheckAtSeconds = OtaState.lastCheckAtSeconds(app()),
            lastResult = OtaState.lastResult(app()),
            nextCheckAtSeconds = now + 3600, readyBuild = "", applyingBuild = "",
            engineFault = null, nowSeconds = now,
        )
        return otaLastCheckLine(v)
    }

    private fun refused(stage: OtaScheduler.Stage, detail: String) = "refused ($stage): $detail"

    private fun assertPlain(line: String) {
        assertFalse("a digit reached the screen: $line", line.substringAfter(":").any { it.isDigit() })
        for (word in listOf("POLICY", "SIGNATURE", "MANIFEST", "ROLLBACK", "refused", "RIST_")) {
            assertFalse("'$word' reached the screen: $line", line.contains(word, ignoreCase = true))
        }
    }

    private fun assertLogged(detail: String) {
        assertTrue("the detail must stay in logcat",
            ShadowLog.getLogsForTag("RistOta").any { it.msg.contains(detail) })
    }

    @Test
    fun `up to date reads Up to date`() {
        val line = lineAfter("up to date (2026100100)")
        assertEquals("Last checked just now: Up to date.", line)
        assertPlain(line)
    }

    @Test
    fun `a rollback or same-age offer reads Up to date`() {
        val detail = "${OtaPolicy.Refusal.ROLLBACK}: offered 1790203029 <= running 1791407082"
        val line = lineAfter(refused(OtaScheduler.Stage.POLICY, detail))
        assertEquals("Last checked just now: Up to date.", line)
        assertPlain(line)
        assertLogged(detail)
    }

    @Test
    fun `other refusals and failures read Update currently unavailable`() {
        val stored = listOf(
            refused(OtaScheduler.Stage.SIGNATURE, "EXPIRED: manifest expired at 1791407082"),
            refused(OtaScheduler.Stage.MANIFEST, "BAD_JSON: unexpected token"),
            refused(OtaScheduler.Stage.POLICY, "${OtaPolicy.Refusal.WRONG_DEVICE}: komodo != stallion"),
            "unauthorised: RIST_OTA_TOKEN mismatch",
            "cannot confirm updates: the server sent no signed manifest",
            "package missing: half-finished publish",
            "2026100500 refused permanently: 23",
        )
        for (s in stored) {
            val line = lineAfter(s)
            assertEquals(s, "Last checked just now: Update currently unavailable.", line)
            assertPlain(line)
            assertLogged(s)
        }
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("ota/$name")) {
            "missing test resource ota/$name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    // Same fixtures as OtaSchedulerTest: a signed manifest for 2026072400, expiring at 1784937600.
    private val manifestNow = 1784937600L - 3600

    private fun refuse(local: OtaPolicy.LocalBuild, at: Long): OtaScheduler.Step.Refused {
        val step = OtaScheduler.evaluate(resource("manifest.json"), resource("manifest.json.minisig"),
            at, local, "stable", resource("test.pub"))
        return step as OtaScheduler.Step.Refused
    }

    private fun stateView(at: Long): OtaStatusView = OtaStatusView(
        build = "2026080100", device = "stallion", channel = "stable",
        baseUrl = "https://ota.example.com",
        lastCheckAtSeconds = OtaState.lastCheckAtSeconds(app()),
        lastResult = OtaState.lastResult(app()),
        nextCheckAtSeconds = at + 3600, readyBuild = "", applyingBuild = "",
        engineFault = null, nowSeconds = at,
        failures = OtaState.failures(app()),
        lastSuccessAtSeconds = OtaState.lastSuccessAtSeconds(app()),
    )

    @Test
    fun `repeated rollback refusals are successful checks and never raise the warning`() {
        val newer = OtaPolicy.LocalBuild("stallion", "2026080100", 1784937599L)
        repeat(OTA_PERSISTENT_FAILURES + 2) { i ->
            val step = refuse(newer, manifestNow + i)
            assertEquals(OtaPolicy.Refusal.ROLLBACK, step.policy)
            assertEquals(OtaScheduler.POLL_INTERVAL_SECONDS,
                OtaScheduler.recordRefusal(app(), manifestNow + i, step))
        }
        val v = stateView(manifestNow + 10)
        assertEquals(0, v.failures)
        assertEquals("", otaHeadline(v))
        assertTrue(otaLastCheckLine(v).endsWith("Up to date."))
    }

    @Test
    fun `repeated expired manifests still count as failures and raise the warning`() {
        val older = OtaPolicy.LocalBuild("stallion", "2026072200", 1784749407L)
        OtaState.noteSuccess(app(), 0L)
        repeat(OTA_PERSISTENT_FAILURES) { i ->
            val at = 1784937600L + 1 + i
            val step = refuse(older, at)
            assertEquals(OtaScheduler.Stage.SIGNATURE, step.stage)
            assertTrue(step.detail.startsWith("EXPIRED"))
            OtaScheduler.recordRefusal(app(), at, step)
        }
        val v = stateView(1784937600L + 10)
        assertEquals(OTA_PERSISTENT_FAILURES, v.failures)
        assertTrue(otaHeadline(v).contains("not receiving security updates"))
        assertTrue(otaLastCheckLine(v).endsWith("Update currently unavailable."))
    }

    @Test
    fun `network failures say to try again later`() {
        for (s in listOf("unreachable: HTTP 503", "check failed: SocketTimeoutException",
                "preflight failed: timeout", "server asked for 600s")) {
            val line = lineAfter(s)
            assertEquals(s, "Last checked just now: Update currently unavailable. Try again later.", line)
            assertPlain(line)
        }
    }
}
