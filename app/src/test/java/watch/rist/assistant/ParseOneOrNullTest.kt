package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import rist.v1.DeviceResponse
import java.io.ByteArrayInputStream

class ParseOneOrNullTest {

    @Test
    fun anEmptyStreamIsNull_notABlankMessage() {
        assertNull(Uploader.parseOneOrNull(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun aRealMessageParses() {
        val original = DeviceResponse.newBuilder()
            .setStatus(0)
            .setRequestId("abc-123")
            .setIsFinal(true)
            .build()
        val parsed = Uploader.parseOneOrNull(ByteArrayInputStream(original.toByteArray()))
        assertNotNull(parsed)
        assertEquals("abc-123", parsed!!.requestId)
        assertEquals(true, parsed.isFinal)
    }

    @Test
    fun theFirstByteIsNotConsumedByThePeek() {
        val original = DeviceResponse.newBuilder().setRequestId("first-byte-matters").build()
        val parsed = Uploader.parseOneOrNull(ByteArrayInputStream(original.toByteArray()))
        assertEquals("first-byte-matters", parsed?.requestId)
    }

    @Test
    fun aMessageWhoseEncodingStartsWithZeroStillParses() {
        val withLeadingZero = byteArrayOf(0x00) + DeviceResponse.newBuilder()
            .setRequestId("x").build().toByteArray()
        val threw = runCatching {
            Uploader.parseOneOrNull(ByteArrayInputStream(withLeadingZero))
        }.isFailure
        assert(threw) { "a malformed body must not parse into a silently-blank reply" }
    }
}
