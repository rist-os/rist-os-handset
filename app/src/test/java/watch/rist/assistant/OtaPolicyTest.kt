package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaPolicyTest {

    private val running = OtaPolicy.LocalBuild(
        device = OtaFixtures.DEVICE,
        build = "2026072200",
        timestampSeconds = OtaFixtures.TIMESTAMP - 86_400L,
    )

    private fun decide(
        m: OtaManifest = OtaFixtures.manifest(),
        local: OtaPolicy.LocalBuild = running,
        channel: String = "stable",
    ) = OtaPolicy.decide(m, local, channel)

    private fun refusal(d: OtaPolicy.Decision): OtaPolicy.Refusal {
        assertTrue("expected a refusal, got $d", d is OtaPolicy.Decision.Refuse)
        return (d as OtaPolicy.Decision.Refuse).reason
    }

    @Test
    fun aNewerBuildForThisDeviceIsApplied() {
        val d = decide()
        assertTrue(d.toString(), d is OtaPolicy.Decision.Apply)
    }

    @Test
    fun theRunningBuildIsUpToDate() {
        val d = decide(local = running.copy(build = OtaFixtures.BUILD))
        assertEquals(OtaPolicy.Decision.UpToDate, d)
    }

    @Test
    fun anOlderBuildIsRefusedAsARollback() {
        val older = running.copy(timestampSeconds = OtaFixtures.TIMESTAMP + 1)
        assertEquals(OtaPolicy.Refusal.ROLLBACK, refusal(decide(local = older)))
    }

    @Test
    fun anEqualTimestampIsRefusedToo() {
        val same = running.copy(timestampSeconds = OtaFixtures.TIMESTAMP)
        assertEquals(OtaPolicy.Refusal.ROLLBACK, refusal(decide(local = same)))
    }

    @Test
    fun anUnreadableLocalBuildRefusesEverything() {
        assertEquals(OtaPolicy.Refusal.UNKNOWN_LOCAL_BUILD,
            refusal(decide(local = running.copy(timestampSeconds = 0))))
        assertEquals(OtaPolicy.Refusal.UNKNOWN_LOCAL_BUILD,
            refusal(decide(local = running.copy(build = ""))))
    }

    @Test
    fun aManifestForAnotherHandsetIsRefused() {
        assertEquals(OtaPolicy.Refusal.WRONG_DEVICE,
            refusal(decide(local = running.copy(device = "husky"))))
    }

    @Test
    fun aBetaManifestCopiedIntoStableIsRefused() {
        val m = OtaFixtures.manifest(OtaFixtures.json(channel = "beta"))
        assertEquals(OtaPolicy.Refusal.WRONG_CHANNEL, refusal(decide(m, channel = "stable")))
    }

    @Test
    fun anIncrementalForAnotherSourceBuildIsRefused() {
        val m = OtaFixtures.manifest(OtaFixtures.json(incrementalFrom = "2026060100"))
        assertEquals(OtaPolicy.Refusal.NO_INCREMENTAL_PATH, refusal(decide(m)))
    }

    @Test
    fun anIncrementalFromTheRunningBuildIsApplied() {
        val m = OtaFixtures.manifest(OtaFixtures.json(incrementalFrom = running.build))
        assertTrue(decide(m) is OtaPolicy.Decision.Apply)
    }

    @Test
    fun aDeviceAlreadyOnTheOfferedBuildReadsAsUpToDateNotAsARollback() {
        val local = OtaPolicy.LocalBuild(OtaFixtures.DEVICE, OtaFixtures.BUILD, OtaFixtures.TIMESTAMP)
        assertEquals(OtaPolicy.Decision.UpToDate, decide(local = local))
    }
}
