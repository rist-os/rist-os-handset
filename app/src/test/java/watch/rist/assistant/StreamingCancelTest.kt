package watch.rist.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamingCancelTest {

    @Before fun clean() = StreamingCancel.resetForTest()
    @After fun tidy() = StreamingCancel.resetForTest()

    @Test
    fun theCancelUrlTracksWhicheverBackendIsConfigured() {
        assertEquals(
            "https://example.test/v1/cancel",
            StreamingCancel.cancelUrlFrom("https://example.test/v1/device"),
        )
    }

    @Test
    fun aBaseWithoutTheDeviceSuffixGetsThePathAppended() {
        assertEquals("https://example.test/v1/cancel", StreamingCancel.cancelUrlFrom("https://example.test/"))
        assertEquals("https://example.test/v1/cancel", StreamingCancel.cancelUrlFrom("https://example.test"))
        assertEquals("", StreamingCancel.cancelUrlFrom("   "))
    }

    @Test
    fun theBodyNamesOnlyTheRequestId() {
        assertEquals("{\"request_id\":\"r-8f2c\"}", StreamingCancel.cancelBody("r-8f2c"))
    }

    @Test
    fun theBodyEscapesWhateverIsInTheId() {
        assertEquals("{\"request_id\":\"a\\\"b\"}", StreamingCancel.cancelBody("a\"b"))
        assertEquals("{\"request_id\":\"a\\\\b\"}", StreamingCancel.cancelBody("a\\b"))
        assertEquals("{\"request_id\":\"a\\nb\"}", StreamingCancel.cancelBody("a\nb"))
    }

    @Test
    fun nothingInFlightMeansNothingToCancel() {
        assertEquals("", StreamingCancel.inFlightId())
        assertEquals("", StreamingCancel.markCancelled())
        assertFalse(StreamingCancel.takeCancelledFlag())
    }

    @Test
    fun cancellingMarksTheTurnThatIsActuallyInFlight() {
        StreamingCancel.begin("r-1")
        assertEquals("r-1", StreamingCancel.inFlightId())
        assertFalse(StreamingCancel.isCancelled("r-1"))
        assertEquals("r-1", StreamingCancel.markCancelled())
        assertTrue(StreamingCancel.isCancelled("r-1"))
    }

    @Test
    fun aCancelNeverLeaksOntoAnotherTurn() {
        StreamingCancel.begin("r-1")
        StreamingCancel.markCancelled()
        StreamingCancel.end("r-1")
        StreamingCancel.begin("r-2")
        assertFalse("a fresh turn is not cancelled by the previous turn's cancel",
            StreamingCancel.isCancelled("r-2"))
    }

    @Test
    fun endOnlyClearsTheSlotIfItIsStillOurs() {
        StreamingCancel.begin("r-1")
        StreamingCancel.begin("r-2")
        StreamingCancel.end("r-1")
        assertEquals("r-2", StreamingCancel.inFlightId())
        assertEquals("r-2", StreamingCancel.markCancelled())
    }

    @Test
    fun anEmptyIdIsNeverConsideredCancelled() {
        assertFalse(StreamingCancel.isCancelled(""))
        StreamingCancel.begin("r-1")
        StreamingCancel.markCancelled()
        assertFalse(StreamingCancel.isCancelled(""))
    }

    @Test
    fun theCancelledFlagIsOneShot() {
        StreamingCancel.begin("r-1")
        StreamingCancel.markCancelled()
        assertTrue(StreamingCancel.takeCancelledFlag())
        assertFalse(StreamingCancel.takeCancelledFlag())
    }

    @Test
    fun aTurnThatEndsNormallySetsNoFlag() {
        StreamingCancel.begin("r-1")
        StreamingCancel.end("r-1")
        assertFalse(StreamingCancel.takeCancelledFlag())
    }
}
