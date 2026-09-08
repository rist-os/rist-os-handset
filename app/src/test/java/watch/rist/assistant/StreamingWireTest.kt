package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rist.v1.DeviceResponse
import rist.v1.Progress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class StreamingWireTest {

    private fun statusFrame(
        text: String,
        kind: String = "tool",
        toolId: String = "",
        speakable: Boolean = true,
    ): DeviceResponse = DeviceResponse.newBuilder()
        .setIsFinal(false)
        .setRequestId("r-1")
        .setProgress(
            Progress.newBuilder()
                .setText(text).setKind(kind).setToolId(toolId).setSpeakable(speakable)
        )
        .build()

    private fun finalFrame(speech: String = "About forty minutes."): DeviceResponse =
        DeviceResponse.newBuilder()
            .setIsFinal(true)
            .setRequestId("r-1")
            .setSpeech(rist.v1.Speech.newBuilder().setText(speech))
            .build()

    private fun line(
        text: String,
        kind: String = "tool",
        toolId: String = "",
        speakable: Boolean = true,
    ): Progress = statusFrame(text, kind, toolId, speakable).progress

    private fun delimited(vararg frames: DeviceResponse): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        frames.forEach { it.writeDelimitedTo(out) }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun severalStatusFramesThenAFinal() {
        val seen = mutableListOf<String>()
        val outcome = StreamingWire.consume(
            delimited(
                statusFrame("Checking your calendar", toolId = "calendar"),
                statusFrame("Working out the route", toolId = "map"),
                statusFrame("Putting that together", kind = "answering", speakable = false),
                finalFrame("About forty minutes — you'll make it."),
            ),
            onProgress = { seen += it.text },
        )
        assertTrue(outcome is StreamingWire.Outcome.Completed)
        val completed = outcome as StreamingWire.Outcome.Completed
        assertEquals(3, completed.statusFrames)
        assertEquals("About forty minutes — you'll make it.", completed.final.speech.text)
        assertEquals(
            listOf("Checking your calendar", "Working out the route", "Putting that together"),
            seen,
        )
    }

    @Test
    fun aStreamThatEndsWithoutAFinalIsTruncated_neverCompleted() {
        val seen = mutableListOf<String>()
        val outcome = StreamingWire.consume(
            delimited(statusFrame("Searching the web"), statusFrame("Thinking it through")),
            onProgress = { seen += it.text },
        )
        assertEquals(StreamingWire.Outcome.Truncated(2), outcome)
        assertEquals(2, seen.size)
    }

    @Test
    fun anEmptyStreamIsEmpty_notTruncated() {
        assertEquals(
            StreamingWire.Outcome.Empty,
            StreamingWire.consume(ByteArrayInputStream(ByteArray(0))),
        )
    }

    @Test
    fun aSingleFinalWithNoStatusFramesIsACompleteTurn() {
        var progressCalls = 0
        val outcome = StreamingWire.consume(delimited(finalFrame("Yes.")), onProgress = { progressCalls++ })
        assertTrue(outcome is StreamingWire.Outcome.Completed)
        assertEquals(0, (outcome as StreamingWire.Outcome.Completed).statusFrames)
        assertEquals("Yes.", outcome.final.speech.text)
        assertEquals(0, progressCalls)
    }

    @Test
    fun aCancelStopsTheLoopAndIsNotAFinal() {
        val seen = mutableListOf<String>()
        var frames = 0
        val outcome = StreamingWire.consume(
            delimited(
                statusFrame("Searching the web"),
                statusFrame("Reading the results"),
                finalFrame("Here you go."),
            ),
            isCancelled = { frames++ >= 2 },
            onProgress = { seen += it.text },
        )
        assertTrue(outcome is StreamingWire.Outcome.Cancelled)
        assertEquals(listOf("Searching the web"), seen)
    }

    @Test
    fun aCancelBeforeTheFirstFrameReadsNothing() {
        val seen = mutableListOf<String>()
        val outcome = StreamingWire.consume(
            delimited(statusFrame("Searching the web"), finalFrame()),
            isCancelled = { true },
            onProgress = { seen += it.text },
        )
        assertEquals(StreamingWire.Outcome.Cancelled(0), outcome)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun theFinalIsTheLastFrameRead() {
        val stream = delimited(finalFrame("first"), statusFrame("this should never be rendered"))
        val seen = mutableListOf<String>()
        val outcome = StreamingWire.consume(stream, onProgress = { seen += it.text })
        assertEquals("first", (outcome as StreamingWire.Outcome.Completed).final.speech.text)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun anUnrecognisedFrameIsSkipped_notTreatedAsAnError() {
        val unknown = DeviceResponse.newBuilder().setIsFinal(false).setRequestId("r-1").build()
        val outcome = StreamingWire.consume(delimited(unknown, statusFrame("Checking your mail"), finalFrame()))
        assertEquals(1, (outcome as StreamingWire.Outcome.Completed).statusFrames)
    }

    @Test
    fun classifyDistinguishesStatusFinalAndUnknown() {
        assertEquals(StreamingWire.FrameKind.STATUS, StreamingWire.classify(statusFrame("x")))
        assertEquals(StreamingWire.FrameKind.FINAL, StreamingWire.classify(finalFrame()))
        assertEquals(
            StreamingWire.FrameKind.IGNORE,
            StreamingWire.classify(DeviceResponse.newBuilder().setRequestId("r").build()),
        )
    }

    @Test
    fun isStreamedRecognisesTheSequenceType() {
        assertTrue(StreamingWire.isStreamed("application/x-protobuf-seq"))
        assertTrue(StreamingWire.isStreamed("application/x-protobuf-seq; charset=binary"))
        assertTrue(StreamingWire.isStreamed("  APPLICATION/X-PROTOBUF-SEQ  "))
    }

    @Test
    fun everythingElseIsUnary_whichIsTheSafeDirection() {
        assertFalse(StreamingWire.isStreamed("application/x-protobuf"))
        assertFalse(StreamingWire.isStreamed(null))
        assertFalse(StreamingWire.isStreamed(""))
        assertFalse(StreamingWire.isStreamed("application/json"))
        assertFalse(StreamingWire.isStreamed("text/html; charset=utf-8"))
        assertFalse(StreamingWire.isStreamed("application/x-protobuf-sequence"))
    }

    @Test
    fun theAcceptHeaderIsTheOnlyOptIn() {
        assertEquals("application/x-protobuf-seq", StreamingWire.acceptHeader(enabled = true))
        assertEquals("application/x-protobuf", StreamingWire.acceptHeader(enabled = false))
    }

    @Test
    fun speakableIsNotFlattenedAway() {
        val ack = line("Let me check today's headlines.", kind = "say", speakable = true)
        val filler = line("Putting that together", kind = "answering", speakable = false)
        assertEquals(StreamingWire.Treatment.QUOTE, StreamingWire.treatmentFor(ack))
        assertEquals(StreamingWire.Treatment.DISPLAY, StreamingWire.treatmentFor(filler))
    }

    @Test
    fun aSpeakableToolLineIsDisplayedNotSpoken() {
        val toolLine = line("Checking your calendar", kind = "tool", speakable = true)
        assertEquals(StreamingWire.Treatment.DISPLAY, StreamingWire.treatmentFor(toolLine))
    }

    @Test
    fun anUnspeakableLineIsNeverSpoken() {
        val thinking = line("Thinking", kind = "thinking", speakable = false)
        assertEquals(StreamingWire.Treatment.DISPLAY, StreamingWire.treatmentFor(thinking))
    }

    @Test
    fun anUnknownKindIsTreatedAsTool() {
        assertEquals(StreamingWire.KIND_TOOL, StreamingWire.kindOf("some-kind-from-next-year"))
        assertEquals(StreamingWire.KIND_TOOL, StreamingWire.kindOf(""))
        assertEquals(StreamingWire.KIND_TOOL, StreamingWire.kindOf(null))
        assertEquals(StreamingWire.KIND_SAY, StreamingWire.kindOf("SAY"))
        assertEquals(StreamingWire.KIND_WAITING, StreamingWire.kindOf(" waiting "))
    }

    @Test
    fun theTextIsRenderedAsWritten() {
        val toolLine = line("Checking your call settings", kind = "tool", speakable = true)
        assertEquals("Checking your call settings", StreamingWire.renderLine(toolLine))
    }

    @Test
    fun theAckIsMarkedAsRistsOwnWords() {
        val ack = line("Tacoma — pulling that up", kind = "say", speakable = true)
        assertEquals("“Tacoma — pulling that up”", StreamingWire.renderLine(ack))
    }

    @Test
    fun aBlankLineRendersNothingRatherThanClearingTheStatus() {
        assertEquals(null, StreamingWire.renderLine(line("")))
        assertEquals(null, StreamingWire.renderLine(line("   ")))
    }
}
