package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceEvaluationTest {

    private fun homeFence(
        direction: String = "enter",
        requireExitFirst: Boolean = false,
        radiusM: Int = 150,
        dwellS: Int = 60,
        expiresEpochS: Long = 0L,
        nowLat: Double? = null,
        nowLon: Double? = null,
        nowAccuracyM: Float = 0f,
    ) = Geofences.arming(
        id = "fence-home", lat = 47.6062, lon = -122.3321, radiusM = radiusM,
        direction = direction, dwellS = dwellS, pollS = 120, expiresEpochS = expiresEpochS,
        requireExitFirst = requireExitFirst, label = "home",
        nowLat = nowLat, nowLon = nowLon, nowAccuracyM = nowAccuracyM,
    )

    private fun northOfHome(metres: Double): Pair<Double, Double> =
        (47.6062 + metres / 111_320.0) to -122.3321

    private val T0 = 1_800_000_000_000L

    @Test
    fun aFixCoarserThanTheRadius_isNotEvidence_andChangesNothing() {
        val f = homeFence().copy(side = GeofenceSide.OUTSIDE)
        val (lat, lon) = northOfHome(0.0)
        val s = Geofences.step(f, lat, lon, accuracyM = 500f, nowMs = T0)

        assertNull("a 500m fix reported a crossing on a 150m fence", s.crossing)
        assertEquals("a fix that cannot prove anything moved the state", f, s.fence)
        assertTrue("the rejection was not explained", s.rejected.isNotEmpty())
    }

    @Test
    fun aFixWithNoAccuracyAtAll_isRejected() {
        assertFalse(Geofences.accuracyUsable(0f, 150))
        assertFalse(Geofences.accuracyUsable(-1f, 150))
        assertTrue(Geofences.accuracyUsable(30f, 150))
        assertTrue("a fix exactly at the radius is still usable", Geofences.accuracyUsable(150f, 150))
        assertFalse(Geofences.accuracyUsable(151f, 150))
    }

    @Test
    fun aCoarseFixCannotResetADwellInProgress() {
        val (inLat, inLon) = northOfHome(10.0)
        var f = homeFence().copy(side = GeofenceSide.OUTSIDE)
        f = Geofences.step(f, inLat, inLon, 20f, T0).fence
        assertEquals(GeofenceSide.INSIDE, f.pendingSide)

        f = Geofences.step(f, inLat, inLon, 900f, T0 + 30_000L).fence
        assertEquals("a coarse fix restarted the dwell", T0, f.pendingSinceMs)

        val s = Geofences.step(f, inLat, inLon, 20f, T0 + 61_000L)
        assertNotNull("the dwell did not complete", s.crossing)
    }

    @Test
    fun crossingTheBoundary_doesNotFireUntilTheDwellIsServed() {
        val (inLat, inLon) = northOfHome(50.0)
        var f = homeFence().copy(side = GeofenceSide.OUTSIDE)

        val first = Geofences.step(f, inLat, inLon, 25f, T0)
        assertNull("fired the instant the boundary was crossed", first.crossing)
        f = first.fence

        val midway = Geofences.step(f, inLat, inLon, 25f, T0 + 59_000L)
        assertNull("fired one second before the dwell was up", midway.crossing)
        f = midway.fence

        val settled = Geofences.step(f, inLat, inLon, 25f, T0 + 60_000L)
        val c = settled.crossing
        assertNotNull("did not fire once the dwell was served", c)
        assertEquals("enter", c!!.direction)
        assertEquals("fence-home", c.fenceId)
        assertEquals(GeofenceSide.INSIDE, settled.fence.side)
        assertNull("the dwell was not cleared on commit", settled.fence.pendingSide)
    }

    @Test
    fun aFixThatWandersOverTheEdgeAndBack_isNoise() {
        val (inLat, inLon) = northOfHome(50.0)
        val (outLat, outLon) = northOfHome(400.0)
        var f = homeFence().copy(side = GeofenceSide.OUTSIDE)

        f = Geofences.step(f, inLat, inLon, 25f, T0).fence
        f = Geofences.step(f, outLat, outLon, 25f, T0 + 20_000L).fence
        assertNull("a wander back out left a dwell running", f.pendingSide)

        val s = Geofences.step(f, inLat, inLon, 25f, T0 + 40_000L)
        assertNull(s.crossing)
        assertEquals("the dwell did not restart from the re-entry", T0 + 40_000L, s.fence.pendingSinceMs)
    }

    @Test
    fun atMs_isWhenItHappened_notWhenItWasConfirmed() {
        val (inLat, inLon) = northOfHome(50.0)
        var f = homeFence().copy(side = GeofenceSide.OUTSIDE)
        f = Geofences.step(f, inLat, inLon, 25f, T0).fence
        val c = Geofences.step(f, inLat, inLon, 25f, T0 + 65_000L).crossing

        assertNotNull(c)
        assertEquals("at_ms was stamped at confirmation, not at the crossing", T0, c!!.atMs)
        assertEquals("the fix's own time was lost", T0 + 65_000L, c.fixTimeMs)
    }

    @Test
    fun leavingUsesAWiderRadiusThanArriving() {
        val f = homeFence()
        assertEquals(150.0, Geofences.enterRadiusM(f), 0.001)
        assertEquals("max(150*1.3, 150+50) = 200", 200.0, Geofences.exitRadiusM(f), 0.001)

        assertEquals(150.0, Geofences.exitRadiusM(homeFence(radiusM = 100)), 0.001)
        assertEquals(1300.0, Geofences.exitRadiusM(homeFence(radiusM = 1000)), 0.001)
    }

    @Test
    fun aPhoneJitteringAcrossTheBoundary_doesNotFlap() {
        val (edgeLat, edgeLon) = northOfHome(170.0)

        val inside = homeFence().copy(side = GeofenceSide.INSIDE)
        assertEquals(GeofenceSide.INSIDE, Geofences.observedSide(inside, 170.0))

        val outside = homeFence().copy(side = GeofenceSide.OUTSIDE)
        assertEquals(GeofenceSide.OUTSIDE, Geofences.observedSide(outside, 170.0))

        var f = inside
        for (i in 0..20) {
            val s = Geofences.step(f, edgeLat, edgeLon, 20f, T0 + i * 120_000L)
            assertNull("a stationary phone on the boundary reported a crossing", s.crossing)
            f = s.fence
        }
    }

    @Test
    fun requireExitFirst_startsInside_soAnArrivalNeedsARealDeparture() {
        val f = homeFence(requireExitFirst = true)
        assertEquals(GeofenceSide.INSIDE, f.side)

        val (inLat, inLon) = northOfHome(20.0)
        var s = Geofences.step(f, inLat, inLon, 20f, T0)
        assertNull("fired on the first fix while the user was still standing there", s.crossing)
        s = Geofences.step(s.fence, inLat, inLon, 20f, T0 + 600_000L)
        assertNull("fired later without the user ever leaving", s.crossing)
    }

    @Test
    fun requireExitFirst_firesOnceTheyHaveActuallyLeftAndComeBack() {
        var f = homeFence(requireExitFirst = true)
        val (inLat, inLon) = northOfHome(20.0)
        val (outLat, outLon) = northOfHome(900.0)

        f = Geofences.step(f, outLat, outLon, 20f, T0).fence
        val exitStep = Geofences.step(f, outLat, outLon, 20f, T0 + 61_000L)
        assertNull("an exit was reported on an enter fence", exitStep.crossing)
        assertEquals(GeofenceSide.OUTSIDE, exitStep.fence.side)
        f = exitStep.fence

        f = Geofences.step(f, inLat, inLon, 20f, T0 + 4 * 3_600_000L).fence
        val arrival = Geofences.step(f, inLat, inLon, 20f, T0 + 4 * 3_600_000L + 61_000L)
        assertNotNull("the arrival after a real departure was not reported", arrival.crossing)
        assertEquals("enter", arrival.crossing!!.direction)
    }

    @Test
    fun armingWhileAlreadyInside_doesNotReportArrivingWhereYouAre_evenWithoutTheFlag() {
        val (inLat, inLon) = northOfHome(10.0)
        val f = homeFence(nowLat = inLat, nowLon = inLon, nowAccuracyM = 20f)
        assertEquals(GeofenceSide.INSIDE, f.side)

        val s = Geofences.step(f, inLat, inLon, 20f, T0 + 600_000L)
        assertNull("reported a transition into the state it was already in", s.crossing)
    }

    @Test
    fun theFirstUsableFix_adoptsASideAndNeverReports() {
        val f = homeFence()
        assertNull(f.side)
        val (inLat, inLon) = northOfHome(10.0)
        val s = Geofences.step(f, inLat, inLon, 20f, T0)
        assertEquals(GeofenceSide.INSIDE, s.fence.side)
        assertNull("the first fix reported a crossing", s.crossing)
    }

    @Test
    fun anUnresolvedFenceBreaksTheTieTowardsNotFiring() {
        val enter = homeFence(direction = "enter")
        assertEquals(GeofenceSide.INSIDE, Geofences.observedSide(enter, 170.0))

        val exit = homeFence(direction = "exit")
        assertEquals(GeofenceSide.OUTSIDE, Geofences.observedSide(exit, 170.0))
    }

    @Test
    fun anArmingFixTooCoarseToDecide_leavesTheFenceUndetermined() {
        val (inLat, inLon) = northOfHome(10.0)
        val f = homeFence(nowLat = inLat, nowLon = inLon, nowAccuracyM = 800f)
        assertNull(f.side)
    }

    @Test
    fun onlyTheDirectionTheUserAskedFor_isReported() {
        var f = homeFence(direction = "exit")
        val (inLat, inLon) = northOfHome(10.0)
        val (outLat, outLon) = northOfHome(900.0)

        f = Geofences.step(f, inLat, inLon, 20f, T0).fence
        f = Geofences.step(f, outLat, outLon, 20f, T0 + 120_000L).fence
        val s = Geofences.step(f, outLat, outLon, 20f, T0 + 181_000L)
        assertEquals("exit", s.crossing?.direction)

        var g = Geofences.step(s.fence, inLat, inLon, 20f, T0 + 300_000L).fence
        val back = Geofences.step(g, inLat, inLon, 20f, T0 + 361_000L)
        assertNull("an enter was reported on an exit fence", back.crossing)
        assertEquals(GeofenceSide.INSIDE, back.fence.side)
    }

    @Test
    fun theCrossingId_isDeterministic_soACrashCannotProduceASecondText() {
        assertEquals(
            Geofences.crossingId("fence-home", T0),
            Geofences.crossingId("fence-home", T0)
        )
        assertFalse(Geofences.crossingId("fence-home", T0) == Geofences.crossingId("fence-home", T0 + 1))
        assertFalse(Geofences.crossingId("fence-work", T0) == Geofences.crossingId("fence-home", T0))
        assertEquals(32, Geofences.crossingId("fence-home", T0).length)
    }

    @Test
    fun aFenceIsDroppedPastItsExpiry_withoutAskingAndWithoutReporting() {
        val nowS = T0 / 1000L
        val live = homeFence(expiresEpochS = nowS + 3600)
        val dead = homeFence(expiresEpochS = nowS - 1).copy(id = "fence-old")
        val forever = homeFence(expiresEpochS = 0L).copy(id = "fence-none")

        val kept = Geofences.pruneExpired(listOf(live, dead, forever), T0)
        assertEquals(listOf("fence-home", "fence-none"), kept.map { it.id })
    }

    @Test
    fun ageIsMeasuredAgainstTheRequestTimestamp_onTheSameClock() {
        val c = GeofenceCrossing("x", "fence-home", "enter", atMs = T0, lat = 0.0, lon = 0.0,
            accuracyM = 20, fixTimeMs = T0)
        assertEquals(29L * 60_000L, Geofences.ageMs(c, T0 + 29L * 60_000L))
        assertFalse(Geofences.isStale(c, T0 + 29L * 60_000L))
        assertTrue(Geofences.isStale(c, T0 + 31L * 60_000L))
    }

    @Test
    fun theQueueCapEvictsAlreadyStaleCrossingsFirst() {
        val stale = (1..3).map {
            GeofenceCrossing("stale-$it", "f", "enter", atMs = T0 - 60L * 60_000L - it,
                lat = 0.0, lon = 0.0, accuracyM = 20, fixTimeMs = T0)
        }
        val fresh = (1..2).map {
            GeofenceCrossing("fresh-$it", "f", "enter", atMs = T0 - 60_000L * it,
                lat = 0.0, lon = 0.0, accuracyM = 20, fixTimeMs = T0)
        }
        val kept = Geofences.trimQueue(stale + fresh, nowMs = T0, cap = 3)
        assertEquals(3, kept.size)
        assertTrue("a runnable crossing was evicted while stale ones remained",
            kept.map { it.id }.containsAll(listOf("fresh-1", "fresh-2")))
    }

    @Test
    fun aQueueUnderTheCapIsReturnedUntouched() {
        val q = listOf(
            GeofenceCrossing("a", "f", "enter", T0, 0.0, 0.0, 20, T0),
            GeofenceCrossing("b", "f", "enter", T0 + 1, 0.0, 0.0, 20, T0),
        )
        assertEquals(q, Geofences.trimQueue(q, T0, cap = 10))
    }

    @Test
    fun aReArmOfTheSameFence_keepsTheEvaluationState() {
        val (inLat, inLon) = northOfHome(50.0)
        val armed = Geofences.step(homeFence().copy(side = GeofenceSide.OUTSIDE), inLat, inLon, 20f, T0).fence
        assertEquals(GeofenceSide.INSIDE, armed.pendingSide)

        val merged = Geofences.mergeArm(listOf(armed), listOf(homeFence()))
        assertEquals(1, merged.size)
        assertEquals(GeofenceSide.OUTSIDE, merged[0].side)
        assertEquals(GeofenceSide.INSIDE, merged[0].pendingSide)
        assertEquals(T0, merged[0].pendingSinceMs)
    }

    @Test
    fun aFenceWhoseGeometryChanged_startsFresh() {
        val armed = homeFence().copy(side = GeofenceSide.INSIDE)
        val moved = Geofences.arming(
            id = "fence-home", lat = 47.7000, lon = -122.3321, radiusM = 150, direction = "enter",
            dwellS = 60, pollS = 120, expiresEpochS = 0L, requireExitFirst = false, label = "home"
        )
        val merged = Geofences.mergeArm(listOf(armed), listOf(moved))
        assertNull("state survived a fence being re-centred", merged[0].side)
    }

    @Test
    fun anEmptyArmMeansHoldNone() {
        assertTrue(Geofences.mergeArm(listOf(homeFence()), emptyList()).isEmpty())
    }

    @Test
    fun theRadiusFloorAndDefaultsAreApplied() {
        val tight = Geofences.arming("f", 47.6, -122.3, radiusM = 30, direction = "ENTER",
            dwellS = 0, pollS = 0, expiresEpochS = 0L, requireExitFirst = false, label = "")
        assertEquals(100, tight.radiusM)
        assertEquals(60, tight.dwellS)
        assertEquals(120, tight.pollS)
        assertEquals("direction is compared as a lowercase word everywhere else", "enter", tight.direction)

        val unset = Geofences.arming("f", 47.6, -122.3, radiusM = 0, direction = "", dwellS = 0,
            pollS = 0, expiresEpochS = 0L, requireExitFirst = false, label = "")
        assertEquals(150, unset.radiusM)
        assertEquals("an unset direction must not silently become an exit fence", "enter", unset.direction)
    }

    @Test
    fun theCadenceNeverGoesBelowPollS_norBelowTheDeviceFloor() {
        val f = homeFence().copy(side = GeofenceSide.OUTSIDE, pollS = 120)
        val (atLat, atLon) = northOfHome(0.0)
        val delay = Geofences.nextPollDelayMs(listOf(f), atLat, atLon, charging = false, stationary = false)
        assertTrue("polled more often than poll_s said", delay >= 120_000L)

        val chatty = f.copy(pollS = 5)
        val floored = Geofences.nextPollDelayMs(listOf(chatty), atLat, atLon, false, false)
        assertTrue("a 5s poll_s turned the handset into a GPS logger",
            floored >= Geofences.MIN_POLL_S * 1000L)
    }

    @Test
    fun beingMilesAway_backsOffHard() {
        val f = homeFence().copy(side = GeofenceSide.OUTSIDE)
        val (farLat, farLon) = northOfHome(20_000.0)
        val delay = Geofences.nextPollDelayMs(listOf(f), farLat, farLon, false, stationary = false)
        assertTrue("20 km away and still polling every two minutes", delay > 600_000L)
        assertTrue("slept past the hard ceiling", delay <= Geofences.HARD_CEIL_MS)
    }

    @Test
    fun sittingInsideTheFenceOnTheCharger_isTheOvernightCase_andSleeps() {
        val f = homeFence().copy(side = GeofenceSide.INSIDE)
        val (atLat, atLon) = northOfHome(0.0)

        val awake = Geofences.nextPollDelayMs(listOf(f), atLat, atLon, charging = false, stationary = false)
        assertEquals("a moving phone inside the fence gets the floor", 120_000L, awake)

        val still = Geofences.nextPollDelayMs(listOf(f), atLat, atLon, charging = false, stationary = true)
        assertEquals(Geofences.STILL_FLOOR_MS, still)

        val asleep = Geofences.nextPollDelayMs(listOf(f), atLat, atLon, charging = true, stationary = true)
        assertEquals(Geofences.CHARGING_STILL_FLOOR_MS, asleep)
    }

    @Test
    fun theCadenceKeysOnTheNEARESTFence_soDistantOnesAreFree() {
        val near = homeFence().copy(side = GeofenceSide.OUTSIDE)
        val far = Geofences.arming("f2", 40.7128, -74.0060, 150, "enter", 60, 120, 0L, false, "ny")
            .copy(side = GeofenceSide.OUTSIDE)
        val (atLat, atLon) = northOfHome(300.0)

        val one = Geofences.nextPollDelayMs(listOf(near), atLat, atLon, false, false)
        val many = Geofences.nextPollDelayMs(listOf(near, far), atLat, atLon, false, false)
        assertEquals("a distant fence changed the cadence", one, many)
    }

    @Test
    fun noFences_meansNoWakeAtAll() {
        assertEquals(0L, Geofences.nextPollDelayMs(emptyList(), 47.6, -122.3, false, false))
    }

    @Test
    fun noFix_meansCheckAtTheFloorRatherThanBackOffBlind() {
        val f = homeFence().copy(side = GeofenceSide.OUTSIDE)
        assertEquals(120_000L, Geofences.nextPollDelayMs(listOf(f), null, null, false, true))
    }

    @Test
    fun stationaryIsMovementPlusNoise_notExactEquality() {
        val (a, b) = northOfHome(0.0)
        val (c, d) = northOfHome(20.0)
        val (e, g) = northOfHome(300.0)
        assertTrue("20 m of GPS noise was read as movement", Geofences.stationary(a, b, c, d))
        assertFalse("300 m of travel was read as standing still", Geofences.stationary(a, b, e, g))
        assertFalse("no previous fix must not read as stationary", Geofences.stationary(null, null, a, b))
    }

    @Test
    fun distanceIsMetres() {
        val (lat, lon) = northOfHome(1000.0)
        assertEquals(1000.0, Geofences.distanceM(47.6062, -122.3321, lat, lon), 5.0)
        assertEquals(0.0, Geofences.distanceM(47.6062, -122.3321, 47.6062, -122.3321), 0.001)
    }
}
