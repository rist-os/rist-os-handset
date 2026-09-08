package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaRetryTest {

    private val now = 1_784_851_200_000L

    @Test
    fun theStreamCapIsA503WithAWait() {
        val o = OtaRetry.classify(503, "10", null, now)
        assertEquals(OtaRetry.Outcome.BackOff(10L), o)
    }

    @Test
    fun theByteBudgetIsA429WithAWait() {
        assertEquals(OtaRetry.Outcome.BackOff(45L), OtaRetry.classify(429, "45", null, now))
    }

    @Test
    fun aRefusalWithNoHeaderStillWaits() {
        assertEquals(OtaRetry.Outcome.BackOff(OtaRetry.BASE_BACKOFF_SECONDS),
            OtaRetry.classify(503, null, null, now))
    }

    @Test
    fun nothingToDoIsCheapAndDistinct() {
        assertEquals(OtaRetry.Outcome.UpToDate, OtaRetry.classify(204, null, null, now))
    }

    @Test
    fun theTwo404sAreNotFailures() {
        val o = OtaRetry.classify(404, null, """{"error": "no build for stallion/beta"}""", now)
        assertTrue(o is OtaRetry.Outcome.NoBuild)
    }

    @Test
    fun aTokenMismatchIsNotSomethingToRetryFast() {
        assertEquals(OtaRetry.Outcome.Unauthorised, OtaRetry.classify(401, null, null, now))
    }

    @Test
    fun deltaSecondsIsParsed() {
        assertEquals(10L, OtaRetry.retryAfterSeconds("10", now))
        assertEquals(10L, OtaRetry.retryAfterSeconds("  10  ", now))
    }

    @Test
    fun theHttpDateFormIsParsedToo() {
        assertEquals(60L, OtaRetry.retryAfterSeconds("Fri, 24 Jul 2026 00:01:00 GMT", now))
    }

    @Test
    fun aDateInThePastMeansNowNotNever() {
        assertEquals(OtaRetry.MIN_SECONDS, OtaRetry.retryAfterSeconds("Fri, 24 Jul 2020 00:00:00 GMT", now))
    }

    @Test
    fun anUnreadableHeaderIsNullSoTheCallerUsesItsOwnSchedule() {
        assertNull(OtaRetry.retryAfterSeconds("soon", now))
        assertNull(OtaRetry.retryAfterSeconds("", now))
        assertNull(OtaRetry.retryAfterSeconds(null, now))
    }

    @Test
    fun anAbsurdRetryAfterIsCapped() {
        assertEquals(OtaRetry.MAX_RETRY_AFTER_SECONDS, OtaRetry.retryAfterSeconds("999999999", now))
    }

    @Test
    fun aNegativeRetryAfterIsFlooredNotNegated() {
        assertEquals(OtaRetry.MIN_SECONDS, OtaRetry.retryAfterSeconds("-5", now))
    }

    @Test
    fun backoffGrowsAndThenStops() {
        assertEquals(60L, OtaRetry.backoffSeconds(0, null, 0.0))
        assertEquals(120L, OtaRetry.backoffSeconds(1, null, 0.0))
        assertEquals(240L, OtaRetry.backoffSeconds(2, null, 0.0))
        assertEquals(OtaRetry.MAX_BACKOFF_SECONDS, OtaRetry.backoffSeconds(20, null, 0.0))
        assertEquals(OtaRetry.MAX_BACKOFF_SECONDS, OtaRetry.backoffSeconds(31, null, 0.0))
        assertEquals(OtaRetry.MAX_BACKOFF_SECONDS, OtaRetry.backoffSeconds(Int.MAX_VALUE, null, 0.0))
    }

    @Test
    fun aLongerRetryAfterWins() {
        assertEquals(600L, OtaRetry.backoffSeconds(0, 600L, 0.0))
    }

    @Test
    fun aShorterRetryAfterDoesNotShortenOurOwnBackoff() {
        assertEquals(OtaRetry.MAX_BACKOFF_SECONDS, OtaRetry.backoffSeconds(20, 5L, 0.0))
    }

    @Test
    fun jitterSpreadsTheFleetUpwardsOnly() {
        assertEquals(60L, OtaRetry.backoffSeconds(0, null, 0.0))
        assertEquals(72L, OtaRetry.backoffSeconds(0, null, 1.0))
        assertTrue(OtaRetry.backoffSeconds(0, null, 0.5) in 60L..72L)
    }

    @Test
    fun aCorruptStoredRetryAfterCannotParkUpdatesForever() {
        assertEquals(OtaRetry.MAX_BACKOFF_SECONDS, OtaRetry.backoffSeconds(0, Long.MAX_VALUE, 0.0))
    }
}
