package watch.rist.assistant

import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import rist.v1.Speech

class OpusContractTest {

    private fun ogg(n: Int): ByteArray = ByteArray(n) { (it * 13 + 3).toByte() }

    @Test
    fun pcmPath_setsPcmCodec() {
        val audio = Uploader.buildAudioInput(ByteArray(3_200))
        assertEquals(Uploader.CODEC_PCM, audio.codec)
        assertEquals(16, audio.bitDepth)
    }

    @Test
    fun opusPath_setsOpusCodecAndHeader() {
        val bytes = ogg(4_096)
        val audio = Uploader.buildOpusAudioInput(bytes)

        assertEquals("opus", audio.codec)
        assertEquals(Uploader.CODEC_OPUS, audio.codec)
        assertEquals(16_000, audio.sampleRate)
        assertEquals(1, audio.channels)
        assertEquals(0, audio.bitDepth)
        assertEquals(0, audio.durationMs)
        assertArrayEquals(bytes, audio.samples.toByteArray())
    }

    @Test
    fun opusRequest_setsAudioOneof_andSurvivesRoundTrip() {
        val bytes = ogg(9_001)
        val caps = DeviceProfile.capabilities(1080, 2400)
        val original = Uploader.buildRequest(
            deviceId = "dev-opus",
            sessionId = "sess-opus",
            timestamp = 1_720_000_000_000L,
            authToken = "tok-opus",
            caps = caps,
            audio = Uploader.buildOpusAudioInput(bytes)
        )

        val parsed = DeviceRequest.parseFrom(original.toByteArray())

        assertEquals(original, parsed)
        assertTrue(parsed.hasAudio())
        assertEquals(DeviceRequest.InputCase.AUDIO, parsed.inputCase)
        assertEquals("opus", parsed.audio.codec)
        assertArrayEquals(bytes, parsed.audio.samples.toByteArray())
    }

    @Test
    fun speech_audioCodec_roundTrips() {
        val speech = Speech.newBuilder()
            .setText("hello")
            .setAudio(ByteString.copyFrom(ogg(512)))
            .setAudioCodec("opus")
            .build()
        val parsed = Speech.parseFrom(speech.toByteArray())
        assertEquals("opus", parsed.audioCodec)

        val legacy = Speech.newBuilder().setAudio(ByteString.copyFrom(ByteArray(4))).build()
        assertEquals("", Speech.parseFrom(legacy.toByteArray()).audioCodec)
    }

    @Test
    fun deviceResponse_carriesAudioCodec() {
        val resp = DeviceResponse.newBuilder()
            .setSpeech(Speech.newBuilder().setAudio(ByteString.copyFrom(ogg(8))).setAudioCodec("opus"))
            .build()
        assertEquals("opus", DeviceResponse.parseFrom(resp.toByteArray()).speech.audioCodec)
    }

    @Test
    fun playbackDispatch_isOpus_matchesOnlyOpus() {
        assertTrue(Playback.isOpus("opus"))
        assertTrue(Playback.isOpus("OPUS"))
        assertTrue(Playback.isOpus(" opus "))
        assertFalse(Playback.isOpus(""))
        assertFalse(Playback.isOpus(null))
        assertFalse(Playback.isOpus("pcm_s16le"))
    }
}
