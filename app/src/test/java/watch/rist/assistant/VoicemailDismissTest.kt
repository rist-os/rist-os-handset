package watch.rist.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A dismissed voicemail row stays gone until the carrier counts a message it did not then. */
class VoicemailDismissTest {

    @Test
    fun `the same count, or fewer, stays dismissed`() {
        assertTrue(CarrierVoicemail.staysDismissed(dismissedAt = 2, current = 2))
        assertTrue(CarrierVoicemail.staysDismissed(dismissedAt = 2, current = 1))
    }

    @Test
    fun `one more message brings the row back`() {
        assertFalse(CarrierVoicemail.staysDismissed(dismissedAt = 1, current = 2))
    }

    @Test
    fun `a count that cannot be read never undoes a dismissal`() {
        assertTrue(CarrierVoicemail.staysDismissed(dismissedAt = 1, current = null))
        assertTrue(CarrierVoicemail.staysDismissed(dismissedAt = 1, current = CarrierVoicemail.COUNT_UNKNOWN))
        assertTrue(CarrierVoicemail.staysDismissed(dismissedAt = CarrierVoicemail.COUNT_UNKNOWN, current = 3))
        // Without a count to compare, a day is as long as a dismissal can hide a voicemail.
        assertFalse(CarrierVoicemail.staysDismissed(
            dismissedAt = CarrierVoicemail.COUNT_UNKNOWN, current = 3, ageMs = CarrierVoicemail.UNKNOWN_DISMISS_MS))
        assertFalse(CarrierVoicemail.staysDismissed(
            dismissedAt = 1, current = null, ageMs = CarrierVoicemail.UNKNOWN_DISMISS_MS + 1))
    }
}
