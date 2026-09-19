package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Test
import watch.rist.assistant.MediaCommandExecutor.Companion.Chapter
import watch.rist.assistant.MediaCommandExecutor.Companion.queueFor

/** media_player.md 4.1: the playlist is the whole book, and section is the index into it. */
class MediaQueueTest {

    private val book = listOf("ch0.mp3", "ch1.mp3", "ch2.mp3", "ch3.mp3")

    @Test
    fun `resuming mid-book queues the whole book and starts on the saved chapter`() {
        val q = queueFor("ch2.mp3", 2, book)
        assertEquals(2, q.startIndex)
        assertEquals(book, q.chapters.map { it.url })
    }

    @Test
    fun `each chapter carries its own number, so the card follows the book`() {
        val q = queueFor("ch0.mp3", 0, book)
        assertEquals(listOf(0, 1, 2, 3), q.chapters.map { it.section })
        // The chapter after the first is the second, not the first again.
        assertEquals(Chapter("ch1.mp3", 1), q.chapters[q.startIndex + 1])
    }

    @Test
    fun `when the index and the file disagree the file wins`() {
        assertEquals(3, queueFor("ch3.mp3", 1, book).startIndex)
    }

    @Test
    fun `a podcast has no playlist and plays its one episode`() {
        val q = queueFor("episode.mp3", 0, emptyList())
        assertEquals(listOf(Chapter("episode.mp3", 0)), q.chapters)
        assertEquals(0, q.startIndex)
    }

    @Test
    fun `a file that is not in the list plays alone rather than dragging the book in`() {
        val q = queueFor("other.mp3", 9, book)
        assertEquals(listOf(Chapter("other.mp3", 9)), q.chapters)
    }

    @Test
    fun `no stream url falls back to the indexed chapter`() {
        assertEquals(1, queueFor("", 1, book).startIndex)
    }
}
