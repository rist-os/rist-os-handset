package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rist.v1.DeviceResponse
import rist.v1.Progress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class StreamingChunkingTest {

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

    private fun wire(vararg frames: DeviceResponse): ByteArray {
        val out = ByteArrayOutputStream()
        frames.forEach { it.writeDelimitedTo(out) }
        return out.toByteArray()
    }

    private class Dribbling(bytes: ByteArray, private val chunk: Int) : InputStream() {
        private val src = ByteArrayInputStream(bytes)
        override fun read(): Int = src.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, minOf(len, chunk))
        override fun available(): Int = src.available()
    }

    private fun dribbled(chunk: Int, vararg frames: DeviceResponse): InputStream =
        Dribbling(wire(*frames), chunk)

    @Test
    fun aBodyDeliveredOneByteAtATimeReadsTheSameAsOneDeliveredWhole() {
        val seen = mutableListOf<String>()
        val outcome = StreamingWire.consume(
            dribbled(
                1,
                statusFrame("Checking your calendar", toolId = "calendar"),
                statusFrame("Working out the route", toolId = "map"),
                finalFrame("About forty minutes."),
            ),
            onProgress = { seen += it.text },
        )
        assertTrue(outcome is StreamingWire.Outcome.Completed)
        assertEquals(2, (outcome as StreamingWire.Outcome.Completed).statusFrames)
        assertEquals("About forty minutes.", outcome.final.speech.text)
        assertEquals(listOf("Checking your calendar", "Working out the route"), seen)
    }

    @Test
    fun everyChunkSizeGivesTheSameAnswer() {
        val frames = arrayOf(
            statusFrame("Searching the web"),
            statusFrame("Reading the results", kind = "thinking", speakable = false),
            finalFrame("Three of them look relevant."),
        )
        for (chunk in 1..17) {
            val seen = mutableListOf<String>()
            val outcome = StreamingWire.consume(dribbled(chunk, *frames), onProgress = { seen += it.text })
            assertTrue("chunk=$chunk", outcome is StreamingWire.Outcome.Completed)
            assertEquals("chunk=$chunk", 2, (outcome as StreamingWire.Outcome.Completed).statusFrames)
            assertEquals("chunk=$chunk", "Three of them look relevant.", outcome.final.speech.text)
            assertEquals("chunk=$chunk", listOf("Searching the web", "Reading the results"), seen)
        }
    }

    @Test
    fun aCharacterSplitAcrossTwoReadsIsNotCorrupted() {
        val text = "Tacoma — pulling that up ☕️ naïve café"
        val speech = "It's 18 °C — mostly cloudy 🌤 later"
        val seen = mutableListOf<String>()
        val outcome = StreamingWire.consume(
            dribbled(1, statusFrame(text, kind = "say"), finalFrame(speech)),
            onProgress = { seen += it.text },
        )
        assertEquals(listOf(text), seen)
        assertEquals(speech, (outcome as StreamingWire.Outcome.Completed).final.speech.text)
        assertFalse(seen.first().contains('�'))
        assertFalse(outcome.final.speech.text.contains('�'))
    }

    @Test
    fun aFrameTooLongForASingleVarintByteSurvivesAPartialRead() {
        val long = "Reading the results — " + "and there are rather a lot of them. ".repeat(12)
        assertTrue(long.toByteArray(Charsets.UTF_8).size > 300)
        val seen = mutableListOf<String>()
        val outcome = StreamingWire.consume(
            dribbled(1, statusFrame(long), finalFrame(long)),
            onProgress = { seen += it.text },
        )
        assertEquals(listOf(long), seen)
        assertEquals(long, (outcome as StreamingWire.Outcome.Completed).final.speech.text)
    }

    @Test
    fun aStreamThatEndsWithoutAFinalIsStillTruncatedWhenItDribbles() {
        val seen = mutableListOf<String>()
        val outcome = StreamingWire.consume(
            dribbled(1, statusFrame("Searching the web"), statusFrame("Thinking it through")),
            onProgress = { seen += it.text },
        )
        assertEquals(StreamingWire.Outcome.Truncated(2), outcome)
        assertEquals(2, seen.size)
    }

    @Test
    fun aBodyCutOffPartWayThroughAFrameIsNeverAnAnswer() {
        val whole = wire(statusFrame("Searching the web"), finalFrame("Here you go."))
        val seen = mutableListOf<String>()
        try {
            val outcome = StreamingWire.consume(
                Dribbling(whole.copyOf(whole.size - 4), 1),
                onProgress = { seen += it.text },
            )
            assertTrue(
                "a cut-off body must never read as an answer",
                outcome !is StreamingWire.Outcome.Completed,
            )
        } catch (expected: com.google.protobuf.InvalidProtocolBufferException) {
        }
        assertEquals(listOf("Searching the web"), seen)
    }

    @Test
    fun aCancelMidStreamStopsAtTheNextFrameEvenWhenBytesTrickle() {
        var frames = 0
        val seen = mutableListOf<String>()
        val outcome = StreamingWire.consume(
            dribbled(
                1,
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
    fun aUnaryReplyIsNeverRunThroughTheFrameLoop() {
        val unary = finalFrame("It's about forty minutes.")
        assertFalse(StreamingWire.isStreamed("application/x-protobuf"))
        assertFalse(StreamingWire.isStreamed(null))
        val parsed = DeviceResponse.parseFrom(unary.toByteArray())
        assertEquals("It's about forty minutes.", parsed.speech.text)
        assertTrue(parsed.isFinal)
    }

    @Test
    fun theCancelFinalTheBackendSendsCarriesNoWords() {
        val cancelFinal = DeviceResponse.newBuilder()
            .setIsFinal(true).setStatus(0).setRequestId("r-1").build()
        val outcome = StreamingWire.consume(dribbled(1, statusFrame("Searching the web"), cancelFinal))
        assertTrue(outcome is StreamingWire.Outcome.Completed)
        assertEquals("", (outcome as StreamingWire.Outcome.Completed).final.speech.text)
    }

    @Test
    fun theOptInIsOffByDefaultAndTheStreamedPathIsTestedAnyway() {
        assertFalse(StreamingWire.STREAMING_ENABLED)
        assertEquals(StreamingWire.UNARY_MEDIA_TYPE, StreamingWire.acceptHeader())
        assertEquals(StreamingWire.SEQ_MEDIA_TYPE, StreamingWire.acceptHeader(enabled = true))
    }
}
