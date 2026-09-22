package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Test
import watch.rist.assistant.MediaCommandExecutor.Companion.Chapter
import watch.rist.assistant.MediaCommandExecutor.Companion.queueFor

/** stream_url now, playlist after it, each chapter its own number. */
class MediaQueueTest {

    @Test
    fun `the chapters after the one playing are numbered on from it`() {
        val q = queueFor("ch3.mp3", 3, listOf("ch4.mp3", "ch5.mp3"))
        assertEquals(0, q.startIndex)
        assertEquals(
            listOf(Chapter("ch3.mp3", 3), Chapter("ch4.mp3", 4), Chapter("ch5.mp3", 5)),
            q.chapters,
        )
    }

    @Test
    fun `a fresh play starts at chapter 0 and the next is chapter 1`() {
        val q = queueFor("ch0.mp3", 0, listOf("ch1.mp3", "ch2.mp3"))
        assertEquals(listOf(0, 1, 2), q.chapters.map { it.section })
    }

    @Test
    fun `the last chapter of a book plays alone`() {
        assertEquals(listOf(Chapter("ch9.mp3", 9)), queueFor("ch9.mp3", 9, emptyList()).chapters)
    }

    @Test
    fun `a podcast has no playlist and plays its one episode`() {
        val q = queueFor("episode.mp3", 0, emptyList())
        assertEquals(listOf(Chapter("episode.mp3", 0)), q.chapters)
        assertEquals(0, q.startIndex)
    }

    @Test
    fun `a book that lists the same file twice keeps every chapter, numbered in order`() {
        val q = queueFor("intro.mp3", 0, listOf("ch1.mp3", "intro.mp3", "ch3.mp3"))
        assertEquals(0, q.startIndex)
        assertEquals(listOf(0, 1, 2, 3), q.chapters.map { it.section })
        assertEquals(listOf("intro.mp3", "ch1.mp3", "intro.mp3", "ch3.mp3"), q.chapters.map { it.url })
    }

    @Test
    fun `a blank entry does not renumber the chapters after it`() {
        val q = queueFor("ch0.mp3", 0, listOf("ch1.mp3", "", "ch3.mp3"))
        assertEquals(listOf(0, 1, 3), q.chapters.map { it.section })
    }

    @Test
    fun `a blank stream url is not queued`() {
        val q = queueFor("", 4, listOf("ch5.mp3"))
        assertEquals(listOf(Chapter("ch5.mp3", 5)), q.chapters)
        assertEquals(0, queueFor("", 4, emptyList()).chapters.size)
    }

    @Test
    fun `blank urls are dropped and the rest still follow in order`() {
        val q = queueFor("ch0.mp3", 0, listOf("ch1.mp3", "", "ch2.mp3"))
        assertEquals(listOf("ch0.mp3", "ch1.mp3", "ch2.mp3"), q.chapters.map { it.url })
    }
}
