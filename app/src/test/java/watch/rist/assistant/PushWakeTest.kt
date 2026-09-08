package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushWakeTest {

    @Test
    fun `the documented shape is a wake`() {
        assertTrue(isOtaCheckWake("""{"type":"ota_check"}"""))
    }

    @Test
    fun `extra fields do not stop it being a wake`() {
        assertTrue(isOtaCheckWake("""{"type":"ota_check","sent_at":1793404800,"id":"abc"}"""))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertTrue(isOtaCheckWake("  {\"type\": \"ota_check\"}\n"))
        assertTrue(isOtaCheckWake("  ota_check \n"))
    }

    @Test
    fun `the bare token is a wake`() {
        assertTrue(isOtaCheckWake(PUSH_WAKE_OTA_CHECK))
        assertEquals("ota_check", PUSH_WAKE_OTA_CHECK)
    }

    @Test
    fun `case does not decide whether a phone updates`() {
        assertTrue(isOtaCheckWake("OTA_Check"))
        assertTrue(isOtaCheckWake("""{"type":"OTA_CHECK"}"""))
    }

    @Test
    fun `an ordinary text message is not a wake`() {
        assertFalse(isOtaCheckWake("Your prescription is ready for pickup."))
        assertFalse(isOtaCheckWake(""))
    }

    @Test
    fun `a message containing the token is still a message`() {
        assertFalse(isOtaCheckWake("remind me to run an ota_check tomorrow"))
    }

    @Test
    fun `another command falls through to the ordinary path`() {
        assertFalse(isOtaCheckWake("""{"type":"message","body":"hello"}"""))
        assertFalse(isOtaCheckWake("""{"body":"hello"}"""))
    }

    @Test
    fun `malformed json is delivered rather than thrown`() {
        assertFalse(isOtaCheckWake("""{"type":"ota_check" """))
        assertFalse(isOtaCheckWake("{"))
        assertFalse(isOtaCheckWake("{]"))
    }

    @Test
    fun `a json array is not a wake`() {
        assertFalse(isOtaCheckWake("""["ota_check"]"""))
    }

    @Test
    fun `a deeply nested frame is not a wake and does not escape`() {
        for (n in listOf(600, 5_000, 100_000)) {
            assertFalse(isOtaCheckWake("{\"a\":" + "[".repeat(n)))
            assertFalse(isOtaCheckWake("{" + "\"a\":{".repeat(n)))
        }
    }
}
