package watch.rist.assistant

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The line above each answer: the request, its time, and the pin. These were three views in a
 * row, and a request longer than one line took the whole width and pushed the time and the pin
 * off the screen. As one piece of text they wrap together.
 */
@RunWith(RobolectricTestRunner::class)
class PromptLineTest {

    private val long = "what is the fastest way to get from Salamanca to the airport in León " +
        "tomorrow morning, and how long will it take with traffic"

    private fun line(prompt: String = long, pinned: Boolean = true) =
        MainActivity.promptLine(prompt, time = "2:17 PM", pinned = pinned, timeColor = 0x111111, pinColor = 0x222222)

    @Test
    fun `a long request still ends with its time and its pin`() {
        val s = line().toString()
        assertTrue(s.startsWith("▸ $long"))
        assertTrue("the pin must follow the time, got: $s", s.endsWith("2:17 PM  📌"))
    }

    @Test
    fun `an unpinned request shows the time and no pin`() {
        val s = line(pinned = false).toString()
        assertTrue(s.endsWith("2:17 PM"))
        assertFalse(s.contains("📌"))
    }

    @Test
    fun `the time cannot be wrapped away from the request or split in two`() {
        // Only no-break spaces between the last word, the time's parts, and the pin.
        val tail = line().toString().removePrefix("▸ $long")
        assertFalse("an ordinary space would let the wrap strand the time: '$tail'", tail.contains(' '))
    }

    @Test
    fun `the time is smaller and fainter than the request, the pin in the accent`() {
        val s = line() as Spanned
        val timeStart = s.indexOf("2:17")
        val size = s.getSpans(timeStart, timeStart + 1, RelativeSizeSpan::class.java).single()
        assertEquals(MainActivity.STAMP_SCALE, size.sizeChange, 0.0001f)
        assertEquals(0x111111, s.getSpans(timeStart, timeStart + 1, ForegroundColorSpan::class.java).single().foregroundColor)
        val pinAt = s.indexOf("📌")
        assertEquals(0x222222, s.getSpans(pinAt, pinAt + 1, ForegroundColorSpan::class.java).single().foregroundColor)
        assertTrue("the request itself carries no span", s.getSpans(2, 3, ForegroundColorSpan::class.java).isEmpty())
    }
}
