package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The choices the user is offered for how long their own messages are kept, and the two
 * properties that matter: the list is the agreed one, and no path silently shortens it.
 */
class RetentionTest {

    @Test
    fun `the offered list is exactly what was asked for, longest first`() {
        assertEquals(
            listOf(
                "Forever", "30 days", "1 week", "1 day", "12 hours",
                "1 hour", "30 minutes", "5 minutes", "2 minutes", "30 seconds",
            ),
            Retention.CHOICES.map { it.label },
        )
    }

    @Test
    fun `forever is zero, which is what turns the age sweep off`() {
        // Transcript.prune skips the cutoff entirely when maxAge <= 0.
        assertEquals(0L, Retention.FOREVER.ms)
        assertEquals(Retention.FOREVER, Retention.byMs(0L))
    }

    @Test
    fun `every choice round-trips through its stored milliseconds`() {
        for (c in Retention.CHOICES) {
            assertEquals("'${c.label}' did not round-trip", c, Retention.byMs(c.ms))
        }
    }

    @Test
    fun `every choice round-trips through its id`() {
        for (c in Retention.CHOICES) {
            assertEquals(c, Retention.byId(c.id))
        }
    }

    @Test
    fun `the durations are the real ones`() {
        fun ms(id: String) = Retention.byId(id)!!.ms
        assertEquals(30L * 1000, ms("30s"))
        assertEquals(2L * 60 * 1000, ms("2m"))
        assertEquals(5L * 60 * 1000, ms("5m"))
        assertEquals(30L * 60 * 1000, ms("30m"))
        assertEquals(60L * 60 * 1000, ms("1h"))
        assertEquals(12L * 60 * 60 * 1000, ms("12h"))
        assertEquals(24L * 60 * 60 * 1000, ms("1d"))
        assertEquals(7L * 24 * 60 * 60 * 1000, ms("7d"))
        assertEquals(30L * 24 * 60 * 60 * 1000, ms("30d"))
    }

    @Test
    fun `an unrecognised stored value rounds UP, never down`() {
        // A number from an older build or a hand edit must not silently shorten how long
        // somebody's messages are kept.
        assertEquals("5m", Retention.byMs(3L * 60 * 1000).id)      // 3 min -> 5 min
        assertEquals("1h", Retention.byMs(45L * 60 * 1000).id)     // 45 min -> 1 hour
        assertEquals("30s", Retention.byMs(1L).id)                 // 1 ms -> the shortest offered
    }

    @Test
    fun `a value longer than the longest choice falls to the longest, not to forever`() {
        val beyond = 365L * 24 * 60 * 60 * 1000
        assertEquals("30d", Retention.byMs(beyond).id)
    }

    @Test
    fun `a negative value is treated as forever, not as an error`() {
        assertEquals(Retention.FOREVER, Retention.byMs(-1L))
    }

    @Test
    fun `an unknown id is refused rather than guessed`() {
        assertNull(Retention.byId("fortnight"))
        assertNull(Retention.byId(""))
        assertNotNull(Retention.byId(" Forever "))   // trimmed and lowercased, still found
    }

    @Test
    fun `ids are unique and stable`() {
        val ids = Retention.CHOICES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it == it.lowercase() && it.isNotBlank() })
    }

    @Test
    fun `the count cap is large enough that forever means something`() {
        // "Forever" with a 200-entry cap was a lie the moment you crossed it.
        assertTrue(
            "cap of ${Config.DEFAULT_TRANSCRIPT_MAX_ENTRIES} is too small to call it forever",
            Config.DEFAULT_TRANSCRIPT_MAX_ENTRIES >= 1000,
        )
    }

    @Test
    fun `the transcript window and the texts and calls window are separate`() {
        // The home feed mixes replies with texts, calls and notifications. The setting claims
        // to govern only the first, so the two windows must not be the same knob.
        assertTrue(
            "the comms window (${CommsFeed.MAX_AGE_MS}ms) now equals the transcript default; " +
                "one setting is silently governing both",
            CommsFeed.MAX_AGE_MS != Config.DEFAULT_TRANSCRIPT_MAX_AGE_MS,
        )
    }
}
