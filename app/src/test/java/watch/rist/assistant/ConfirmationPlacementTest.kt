package watch.rist.assistant

import android.widget.LinearLayout
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import rist.v1.ConfirmRequest
import rist.v1.DeviceResponse
import rist.v1.Speech

/**
 * The bug: a question asking the user to confirm was drawn at the bottom of the home feed,
 * under every older answer, while the answer that asked it sat at the top. The feed is
 * newest-first, and the question was appended after the whole list.
 */
@RunWith(RobolectricTestRunner::class)
class ConfirmationPlacementTest {

    private val prompt = "Send \"on my way\" to Sam?"

    private val app: android.app.Application = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    @Before
    fun emptyFeed() = Transcript.clearForTest(app)

    @After
    fun cleanUp() = Transcript.clearForTest(app)

    private fun answered(a: MainActivity, asked: String, said: String) {
        val id = Transcript.begin(a, asked, EntryState.WAITING)
        Transcript.update(a, id, state = EntryState.ANSWERED, answer = said)
    }

    private fun indexOfText(root: LinearLayout, text: String): Int {
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v is TextView && v.text.toString() == text) return i
        }
        return -1
    }

    @Test
    fun `the question sits directly under the answer that asks it, at the top`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).create().get()
        answered(a, "what time is it", "It is ten.")
        answered(a, "weather", "Sunny.")
        answered(a, "text Sam on my way", "I can send that.")

        val reply = DeviceResponse.newBuilder()
            .setSpeech(Speech.newBuilder().setText("I can send that."))
            .setConfirm(ConfirmRequest.newBuilder().setActionId("act-1").setPrompt(prompt))
            .build()
        a.handleReply(reply, subject = "message", clear = true)

        val feed = a.findViewById<LinearLayout>(R.id.replyContainer)
        val at = indexOfText(feed, prompt)
        assertTrue("the confirmation question was not drawn at all", at >= 0)
        assertEquals("the question must follow the newest answer, not the oldest", 1, at)
        assertTrue("and its yes / no row comes right after it", feed.childCount > at + 1)
        assertTrue("older answers stay below it", feed.childCount >= at + 2 + 2)
    }
}
