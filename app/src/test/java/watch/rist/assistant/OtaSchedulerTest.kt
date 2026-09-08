package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaSchedulerTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("ota/$name")) {
            "missing test resource ota/$name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private val body: String get() = resource("manifest.json")
    private val sig: String get() = resource("manifest.json.minisig")
    private val key: String get() = resource("test.pub")

    private val now = 1784937600L - 3600

    private val older = OtaPolicy.LocalBuild("stallion", "2026072200", 1784749407L)

    @Test
    fun `a verified newer manifest reaches Apply`() {
        val step = OtaScheduler.evaluate(body, sig, now, older, "stable", key)
        assertTrue("expected Apply, got $step", step is OtaScheduler.Step.Apply)
        assertEquals("2026072400", (step as OtaScheduler.Step.Apply).manifest.build)
    }

    @Test
    fun `the running build reports UpToDate`() {
        val same = OtaPolicy.LocalBuild("stallion", "2026072400", 1784851200L)
        assertEquals(OtaScheduler.Step.UpToDate,
            OtaScheduler.evaluate(body, sig, now, same, "stable", key))
    }

    @Test
    fun `an unverifiable manifest is refused at SIGNATURE, before policy sees it`() {
        val newer = OtaPolicy.LocalBuild("stallion", "2026080100", 1784937600L)
        val step = OtaScheduler.evaluate(body, sig, now, newer, "stable", OtaSignature.PUBLIC_KEY)
        assertEquals(OtaScheduler.Stage.SIGNATURE,
            (step as OtaScheduler.Step.Refused).stage)
    }

    @Test
    fun `no signature means no update, whatever the manifest says`() {
        val step = OtaScheduler.evaluate(body, null, now, older, "stable", key)
        assertEquals(OtaScheduler.Stage.SIGNATURE, (step as OtaScheduler.Step.Refused).stage)
        assertTrue(step.detail.startsWith("NO_SIGNATURE"))
    }

    @Test
    fun `an unset public key refuses at SIGNATURE`() {
        val step = OtaScheduler.evaluate(body, sig, now, older, "stable", OtaSignature.PLACEHOLDER)
        assertEquals(OtaScheduler.Stage.SIGNATURE, (step as OtaScheduler.Step.Refused).stage)
        assertTrue(step.detail.startsWith("NO_PUBLIC_KEY"))
    }

    @Test
    fun `an expired manifest never reaches the policy`() {
        val step = OtaScheduler.evaluate(body, sig, 1784937600L + 1, older, "stable", key)
        assertEquals(OtaScheduler.Stage.SIGNATURE, (step as OtaScheduler.Step.Refused).stage)
        assertTrue(step.detail.startsWith("EXPIRED"))
    }

    @Test
    fun `a manifest for another handset is refused at POLICY`() {
        val other = OtaPolicy.LocalBuild("bluejay", "2026072200", 1784749407L)
        val step = OtaScheduler.evaluate(body, sig, now, other, "stable", key)
        assertEquals(OtaScheduler.Stage.POLICY, (step as OtaScheduler.Step.Refused).stage)
        assertTrue(step.detail.startsWith("WRONG_DEVICE"))
    }

    @Test
    fun `a beta manifest served on the stable path is refused at POLICY`() {
        val step = OtaScheduler.evaluate(body, sig, now, older, "beta", key)
        assertEquals(OtaScheduler.Stage.POLICY, (step as OtaScheduler.Step.Refused).stage)
        assertTrue(step.detail.startsWith("WRONG_CHANNEL"))
    }

    @Test
    fun `a signed older build is refused at POLICY as a rollback`() {
        val newer = OtaPolicy.LocalBuild("stallion", "2026080100", 1784937599L)
        val step = OtaScheduler.evaluate(body, sig, now, newer, "stable", key)
        assertEquals(OtaScheduler.Stage.POLICY, (step as OtaScheduler.Step.Refused).stage)
        assertTrue(step.detail.startsWith("ROLLBACK"))
    }

    @Test
    fun `signature and structure are separate stages`() {
        val step = OtaScheduler.evaluate(body, sig, now, older, "stable", key)
        assertTrue(step is OtaScheduler.Step.Apply)
        val broken = OtaScheduler.evaluate("{\"expires\":$now}", sig, now, older, "stable", key)
        assertEquals(OtaScheduler.Stage.SIGNATURE, (broken as OtaScheduler.Step.Refused).stage)
    }

    @Test
    fun `the signature sits beside the manifest, with no query`() {
        assertEquals("https://ota.example.com/v1/ota/stallion/stable.minisig",
            OtaCheck.signatureUrl("https://ota.example.com/", "stallion", "stable"))
    }

    @Test
    fun `a bad channel is refused before it becomes a URL`() {
        var threw = false
        try {
            OtaCheck.signatureUrl("https://ota.example.com", "stallion", "../../etc")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a path-traversing channel produced a URL", threw)
    }
}
