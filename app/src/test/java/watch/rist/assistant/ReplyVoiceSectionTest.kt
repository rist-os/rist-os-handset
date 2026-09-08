package watch.rist.assistant

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplyVoiceSectionTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clean() { Config.setReplyVoiceEnabled(ctx(), true) }

    @After
    fun tidy() = clean()

    private fun resumed() = Robolectric.buildActivity(SettingsActivity::class.java)
        .create().start().resume()

    private fun glyph(a: SettingsActivity): TextView {
        val host = a.findViewById<View>(R.id.appsHeading).parent as LinearLayout
        return host.findViewWithTag(ReplyVoiceSection.GLYPH_TAG)
            ?: throw AssertionError("the Read-replies-aloud row is not on the settings screen")
    }

    @Test
    fun `the row is on the screen, under the consent paragraph, and defaults to on`() {
        val a = resumed().get()
        val g = glyph(a)
        assertEquals("☑", g.text.toString())
        val host = a.findViewById<View>(R.id.appsHeading).parent as LinearLayout
        val rowIdx = host.indexOfChild(g.parent as View)
        val appsIdx = host.indexOfChild(a.findViewById(R.id.appsHeading))
        assertEquals("the row is not directly above the apps heading", appsIdx - 1, rowIdx)
    }

    @Test
    fun `a stored off is shown as off`() {
        Config.setReplyVoiceEnabled(ctx(), false)
        val a = resumed().get()
        assertEquals("☐", glyph(a).text.toString())
    }

    @Test
    fun `tapping the row flips the stored value and the glyph`() {
        val a = resumed().get()
        assertTrue(Config.isReplyVoiceEnabled(ctx()))

        assertTrue("nothing is listening to the row", (glyph(a).parent as View).performClick())
        assertFalse("the tap did not reach Config", Config.isReplyVoiceEnabled(ctx()))
        assertEquals("the row did not repaint after the tap", "☐", glyph(a).text.toString())

        assertTrue((glyph(a).parent as View).performClick())
        assertTrue(Config.isReplyVoiceEnabled(ctx()))
        assertEquals("☑", glyph(a).text.toString())
    }

    @Test
    fun `a value changed behind the screen's back is shown on the next resume`() {
        val c = resumed()
        val a = c.get()
        assertEquals("☑", glyph(a).text.toString())

        Config.setReplyVoiceEnabled(ctx(), false)
        c.pause().resume()

        assertEquals("a backend write stayed invisible on the settings screen", "☐", glyph(a).text.toString())
        assertNotNull(glyph(a))
    }

    @Test
    fun `resuming repeatedly leaves exactly one row`() {
        val c = resumed()
        val a = c.get()
        c.pause().resume()
        c.pause().resume()
        val host = a.findViewById<View>(R.id.appsHeading).parent as LinearLayout
        var rows = 0
        for (i in 0 until host.childCount) {
            if (host.getChildAt(i).findViewWithTag<View>(ReplyVoiceSection.GLYPH_TAG) != null) rows++
        }
        assertEquals("the row was stacked on resume", 1, rows)
    }
}
