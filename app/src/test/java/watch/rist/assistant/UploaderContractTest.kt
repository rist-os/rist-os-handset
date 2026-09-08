package watch.rist.assistant

import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rist.v1.AudioInput
import rist.v1.Capabilities
import rist.v1.DeviceRequest
import rist.v1.ImageInput

class UploaderContractTest {

    private fun pcm(nSamples: Int): ByteArray {
        val out = ByteArray(nSamples * 2)
        for (i in 0 until nSamples) {
            val s = (i * 7) and 0xFFFF
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun audioInput_headerMatchesCaptureFormat_forOneSecond() {
        val samples = pcm(16_000)
        val audio = Uploader.buildAudioInput(samples)

        assertEquals(16_000, audio.sampleRate)
        assertEquals(16, audio.bitDepth)
        assertEquals(1, audio.channels)
        assertEquals(1_000, audio.durationMs)
        assertEquals(32_000, audio.samples.size())
        assertArrayEquals(samples, audio.samples.toByteArray())
    }

    @Test
    fun audioInput_durationRoundsDown_forPartialBuffer() {
        val samples = pcm(8_000)
        assertEquals(500, Uploader.buildAudioInput(samples).durationMs)

        assertEquals(6, Uploader.buildAudioInput(pcm(100)).durationMs)
    }

    @Test
    fun audioInput_emptyBuffer_isZeroDuration() {
        val audio = Uploader.buildAudioInput(ByteArray(0))
        assertEquals(0, audio.durationMs)
        assertEquals(0, audio.samples.size())
    }

    @Test
    fun deviceRequest_setsAudioOneof_notText() {
        val audio = Uploader.buildAudioInput(pcm(1_600))
        val caps = DeviceProfile.capabilities(1080, 2400)
        val req = Uploader.buildRequest(
            deviceId = "dev-abc",
            sessionId = "sess-123",
            timestamp = 1_720_000_000_000L,
            authToken = "tok-xyz",
            caps = caps,
            audio = audio
        )

        assertTrue(req.hasAudio())
        assertEquals(DeviceRequest.InputCase.AUDIO, req.inputCase)
        assertEquals("dev-abc", req.deviceId)
        assertEquals("sess-123", req.sessionId)
        assertEquals(1_720_000_000_000L, req.timestamp)
        assertEquals("tok-xyz", req.authToken)
        assertTrue(req.hasCaps())
        assertEquals(100, req.audio.durationMs)
    }

    @Test
    fun deviceRequest_survivesSerializeParseRoundTrip() {
        val audio = Uploader.buildAudioInput(pcm(4_000))
        val caps = DeviceProfile.capabilities(1440, 3120)
        val original = Uploader.buildRequest(
            deviceId = "dev-roundtrip",
            sessionId = "sess-roundtrip",
            timestamp = 42L,
            authToken = "",
            caps = caps,
            audio = audio
        )

        val bytes = original.toByteArray()
        val parsed = DeviceRequest.parseFrom(bytes)

        assertEquals(original, parsed)
        assertTrue(parsed.hasAudio())
        assertEquals("dev-roundtrip", parsed.deviceId)
        assertEquals(250, parsed.audio.durationMs)
        assertArrayEquals(audio.samples.toByteArray(), parsed.audio.samples.toByteArray())
        assertEquals(caps, parsed.caps)
    }

    private fun jpeg(n: Int): ByteArray = ByteArray(n) { (it * 31 + 7).toByte() }

    @Test
    fun deviceRequest_setsImageOneof_notAudioOrText() {
        val bytes = jpeg(4_096)
        val image = ImageInput.newBuilder()
            .setFormat("jpeg").setWidth(1600).setHeight(1200)
            .setData(ByteString.copyFrom(bytes))
            .build()
        val caps = DeviceProfile.capabilities(1080, 2400)
        val req = Uploader.buildImageRequest(
            deviceId = "dev-img",
            sessionId = "sess-img",
            timestamp = 1_720_000_000_000L,
            authToken = "tok-img",
            caps = caps,
            image = image
        )

        assertTrue(req.hasImage())
        assertEquals(DeviceRequest.InputCase.IMAGE, req.inputCase)
        assertTrue(!req.hasAudio())
        assertEquals("dev-img", req.deviceId)
        assertEquals("sess-img", req.sessionId)
        assertEquals(1_720_000_000_000L, req.timestamp)
        assertEquals("tok-img", req.authToken)
        assertTrue(req.hasCaps())
        assertEquals("jpeg", req.image.format)
        assertEquals(1600, req.image.width)
        assertEquals(1200, req.image.height)
        assertArrayEquals(bytes, req.image.data.toByteArray())
    }

    @Test
    fun deviceRequest_imageSurvivesSerializeParseRoundTrip() {
        val bytes = jpeg(8_192)
        val image = ImageInput.newBuilder()
            .setFormat("jpeg").setWidth(800).setHeight(600)
            .setData(ByteString.copyFrom(bytes))
            .build()
        val caps = DeviceProfile.capabilities(1440, 3120)
        val original = Uploader.buildImageRequest(
            deviceId = "dev-img-rt",
            sessionId = "sess-img-rt",
            timestamp = 99L,
            authToken = "",
            caps = caps,
            image = image
        )

        val parsed = DeviceRequest.parseFrom(original.toByteArray())

        assertEquals(original, parsed)
        assertTrue(parsed.hasImage())
        assertEquals(DeviceRequest.InputCase.IMAGE, parsed.inputCase)
        assertEquals("jpeg", parsed.image.format)
        assertArrayEquals(bytes, parsed.image.data.toByteArray())
        assertEquals(caps, parsed.caps)
    }

    @Test
    fun audioInput_rawBytesArePreservedExactly() {
        val raw = byteArrayOf(0x02, 0x01, 0x7F.toByte(), 0xFF.toByte())
        val audio: AudioInput = Uploader.buildAudioInput(raw)
        assertEquals(ByteString.copyFrom(raw), audio.samples)
        assertEquals(0, audio.durationMs)
    }
}
