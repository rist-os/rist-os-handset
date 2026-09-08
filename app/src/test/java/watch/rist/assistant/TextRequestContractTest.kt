package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rist.v1.DeviceRequest

class TextRequestContractTest {

    @Test
    fun deviceRequest_setsTextOneof_notAudioOrImage() {
        val caps = DeviceProfile.capabilities(1080, 2400)
        val req = Uploader.buildTextRequest(
            deviceId = "dev-txt",
            sessionId = "sess-txt",
            timestamp = 1_720_000_000_000L,
            authToken = "tok-txt",
            caps = caps,
            text = "what time is my next meeting?"
        )

        assertEquals(DeviceRequest.InputCase.TEXT, req.inputCase)
        assertEquals("what time is my next meeting?", req.text)
        assertFalse(req.hasAudio())
        assertFalse(req.hasImage())
        assertEquals("dev-txt", req.deviceId)
        assertEquals("sess-txt", req.sessionId)
        assertEquals(1_720_000_000_000L, req.timestamp)
        assertEquals("tok-txt", req.authToken)
        assertTrue(req.hasCaps())
        assertEquals(caps, req.caps)
    }

    @Test
    fun deviceRequest_textSurvivesSerializeParseRoundTrip() {
        val caps = DeviceProfile.capabilities(1440, 3120)
        val original = Uploader.buildTextRequest(
            deviceId = "dev-txt-rt",
            sessionId = "sess-txt-rt",
            timestamp = 7L,
            authToken = "",
            caps = caps,
            text = "hello Rist — unicode ✎ café 日本語"
        )

        val parsed = DeviceRequest.parseFrom(original.toByteArray())

        assertEquals(original, parsed)
        assertEquals(DeviceRequest.InputCase.TEXT, parsed.inputCase)
        assertEquals("hello Rist — unicode ✎ café 日本語", parsed.text)
        assertEquals("dev-txt-rt", parsed.deviceId)
        assertEquals(caps, parsed.caps)
    }
}
