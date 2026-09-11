package watch.rist.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A pinned answer is kept until it is unpinned — not until the retention window runs out,
 * not until the count cap evicts it, and not because Clear was pressed.
 */
@RunWith(RobolectricTestRunner::class)
class TranscriptPinTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        Transcript.clearForTest(app)
        Config.setTranscriptMaxAgeMs(app, Config.DEFAULT_TRANSCRIPT_MAX_AGE_MS)
        Config.setTranscriptMaxEntries(app, Config.DEFAULT_TRANSCRIPT_MAX_ENTRIES)
    }

    @After
    fun tidy() {
        Transcript.clearForTest(app)
        Config.setTranscriptMaxAgeMs(app, Config.DEFAULT_TRANSCRIPT_MAX_AGE_MS)
        Config.setTranscriptMaxEntries(app, Config.DEFAULT_TRANSCRIPT_MAX_ENTRIES)
    }

    /** An answered entry, aged by rewriting its timestamp. */
    private fun answered(prompt: String, ageMs: Long = 0L): Long {
        val id = Transcript.begin(app, prompt, EntryState.SENT)
        Transcript.update(app, id, state = EntryState.ANSWERED, answer = "a")
        if (ageMs > 0L) Transcript.ageForTest(app, id, ageMs)
        return id
    }

    @Test
    fun `an unpinned answer is swept once it is older than the window`() {
        Config.setTranscriptMaxAgeMs(app, 60_000L)
        answered("old", ageMs = 120_000L)
        assertTrue(Transcript.all(app).isEmpty())
    }

    @Test
    fun `a pinned answer survives the age sweep`() {
        Config.setTranscriptMaxAgeMs(app, 60_000L)
        val id = answered("keep me", ageMs = 120_000L)
        Transcript.setPinned(app, id, true)

        val left = Transcript.all(app)
        assertEquals(1, left.size)
        assertTrue(left.single().pinned)
    }

    @Test
    fun `unpinning lets it be swept again`() {
        Config.setTranscriptMaxAgeMs(app, 60_000L)
        val id = answered("keep me", ageMs = 120_000L)
        Transcript.setPinned(app, id, true)
        assertEquals(1, Transcript.all(app).size)

        Transcript.setPinned(app, id, false)
        assertTrue(Transcript.all(app).isEmpty())
    }

    @Test
    fun `the cap counts unpinned entries only, and evicts the oldest of those`() {
        // Counting pinned entries against the budget meant that once enough were pinned,
        // each NEW answer was evicted the moment it arrived.
        Config.setTranscriptMaxAgeMs(app, 0L)   // forever, so only the count cap can bite
        Config.setTranscriptMaxEntries(app, 3)

        Transcript.setPinned(app, answered("first"), true)
        answered("second")
        answered("third")
        answered("fourth")
        answered("fifth")   // now four unpinned against a cap of three

        val kept = Transcript.all(app).map { it.prompt }
        assertTrue("the pinned entry was evicted", kept.contains("first"))
        assertFalse("the oldest unpinned one should have gone", kept.contains("second"))
        assertTrue("the newest answer must never be the one dropped", kept.contains("fifth"))
        assertEquals(listOf("first", "third", "fourth", "fifth"), kept)
    }

    @Test
    fun `a new answer is never evicted on arrival, however many are pinned`() {
        Config.setTranscriptMaxAgeMs(app, 0L)
        Config.setTranscriptMaxEntries(app, 2)
        for (n in 1..5) Transcript.setPinned(app, answered("p$n"), true)

        answered("brand new")

        assertTrue(Transcript.all(app).map { it.prompt }.contains("brand new"))
    }

    @Test
    fun `a list of nothing but pinned entries is allowed to exceed the cap`() {
        Config.setTranscriptMaxAgeMs(app, 0L)
        Config.setTranscriptMaxEntries(app, 2)
        for (n in 1..4) Transcript.setPinned(app, answered("p$n"), true)
        assertEquals(4, Transcript.all(app).size)
    }

    @Test
    fun `clearing spares pinned entries`() {
        val kept = answered("kept")
        Transcript.setPinned(app, kept, true)
        answered("transient")

        Transcript.clear(app)

        assertEquals(listOf("kept"), Transcript.all(app).map { it.prompt })
    }

    @Test
    fun `the pin survives a reload from disk`() {
        val id = answered("keep me")
        Transcript.setPinned(app, id, true)
        Transcript.reloadForTest(app)
        assertTrue(Transcript.all(app).single().pinned)
    }

    @Test
    fun `entries written before pinning existed load as unpinned`() {
        val id = answered("legacy")
        Transcript.reloadForTest(app)
        assertFalse(Transcript.isPinned(app, id))
    }

    @Test
    fun `pinning an id that is not there does nothing and does not throw`() {
        Transcript.setPinned(app, 999_999L, true)
        assertTrue(Transcript.all(app).isEmpty())
    }
}
