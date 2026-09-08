package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rist.v1.DeviceResponse

class StreamingStatusTest {

    private val aFinal: DeviceResponse = DeviceResponse.newBuilder().setIsFinal(true).build()

    @Test
    fun everyOutcomeGetsItsOwnName() {
        assertEquals(
            StreamingStatus.ENDING_FINAL,
            StreamingStatus.endingOf(StreamingWire.Outcome.Completed(aFinal, 2)),
        )
        assertEquals(
            StreamingStatus.ENDING_TRUNCATED,
            StreamingStatus.endingOf(StreamingWire.Outcome.Truncated(2)),
        )
        assertEquals(
            StreamingStatus.ENDING_CANCELLED,
            StreamingStatus.endingOf(StreamingWire.Outcome.Cancelled(1)),
        )
        assertEquals(
            StreamingStatus.ENDING_EMPTY,
            StreamingStatus.endingOf(StreamingWire.Outcome.Empty),
        )
    }

    @Test
    fun theFourNamesAreDistinct() {
        val names = setOf(
            StreamingStatus.ENDING_FINAL,
            StreamingStatus.ENDING_TRUNCATED,
            StreamingStatus.ENDING_CANCELLED,
            StreamingStatus.ENDING_EMPTY,
        )
        assertEquals(4, names.size)
    }

    @Test
    fun aDroppedConnectionSaysSoRatherThanBlamingTheNetwork() {
        val truncated = StreamingStatus.failureFor(StreamingStatus.ENDING_TRUNCATED)
        assertTrue(truncated.isNotBlank())
        assertNotEquals(truncated, StreamingStatus.failureFor(StreamingStatus.ENDING_EMPTY))
    }

    @Test
    fun aFinalAndACancelHaveNothingToApologiseFor() {
        assertEquals("", StreamingStatus.failureFor(StreamingStatus.ENDING_FINAL))
        assertEquals("", StreamingStatus.failureFor(StreamingStatus.ENDING_CANCELLED))
        assertEquals("", StreamingStatus.failureFor("something that shipped later"))
    }
}
