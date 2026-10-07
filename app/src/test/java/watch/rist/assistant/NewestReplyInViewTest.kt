package watch.rist.assistant

import android.Manifest
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RConfig
import org.robolectric.annotation.GraphicsMode

/** An opened notice above the answers never leaves the newest answer below the screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RConfig(qualifiers = "w411dp-h891dp-xxhdpi")
class NewestReplyInViewTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    private val longNotice = (1..40).joinToString(" ") {
        "Line $it of a long morning briefing that goes on about the weather and the news."
    }

    @Before fun clean() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        Config.usePlainPrefsForTest(app)
        Config.setNotifications(app, "[]")
        Config.setSeenCommsIds(app, emptyList())
        Transcript.clearForTest(app)
        CommsFeedView.resetForTest()
        // Notices sit above the answers only when there is no tile row to hold them.
        HomeBoxes.shippedForTest = false
    }

    @After fun tidy() {
        HomeBoxes.shippedForTest = null
        Config.setNotifications(app, "[]")
        Transcript.clearForTest(app)
        CommsFeedView.resetForTest()
        Config.forgetPrefsForTest()
    }

    private fun settle() = repeat(3) { shadowOf(Looper.getMainLooper()).idle() }

    private fun noticeCard(a: MainActivity): ViewGroup {
        fun find(v: View): View? {
            if (v.tag == CommsFeedView.NOTICE_CARD_TAG) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(a.findViewById(R.id.commsFeed)) as ViewGroup
    }

    /** Top and bottom of the newest answer, in the scroller's visible window. */
    private fun newestOnScreen(a: MainActivity): Pair<Int, Int> {
        val scroll = a.findViewById<ScrollView>(R.id.replyScroll)
        val replies = a.findViewById<LinearLayout>(R.id.replyContainer)
        val newest = replies.getChildAt(0)
        val top = replies.top + newest.top - scroll.scrollY
        return top to top + newest.height
    }

    @Test
    fun `the newest answer is on screen after a reply lands under an opened notice`() {
        NotificationQueue.store(app, listOf(
            rist.v1.Notification.newBuilder().setId("n-1").setKind("scheduled").setTitle(longNotice)
                .setUrgency("passive").setCreatedAtEpochS(System.currentTimeMillis() / 1000).build()
        ))
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        settle()

        noticeCard(a).performClick(); settle()
        val scroll = a.findViewById<ScrollView>(R.id.replyScroll)
        val feed = a.findViewById<View>(R.id.commsFeed)
        assertTrue("the opened notice is taller than the screen", feed.height > scroll.height)

        val id = Transcript.begin(app, "what time is it", EntryState.WAITING)
        Transcript.update(app, id, state = EntryState.ANSWERED, answer = "It is 2:17 PM.")
        c.pause().resume(); settle()

        val (top, bottom) = newestOnScreen(a)
        assertTrue("its top is in view ($top)", top >= 0)
        assertTrue("its bottom is in view ($bottom of ${scroll.height})", bottom <= scroll.height)
    }

    @Test
    fun `with room to spare the feed stays in full view`() {
        val id = Transcript.begin(app, "what time is it", EntryState.WAITING)
        Transcript.update(app, id, state = EntryState.ANSWERED, answer = "It is 2:17 PM.")
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        assertEquals(0, a.findViewById<ScrollView>(R.id.replyScroll).scrollY)
    }

    @Test
    fun `the scroll target keeps as much of the feed as fits`() {
        // Fits under the feed: no scroll.
        assertEquals(0, MainActivity.replyScrollTarget(replyTop = 300, newestHeight = 200, viewport = 800))
        // Below the fold: just far enough to show all of it.
        assertEquals(500, MainActivity.replyScrollTarget(replyTop = 1100, newestHeight = 200, viewport = 800))
        // Taller than the screen: its top.
        assertEquals(1100, MainActivity.replyScrollTarget(replyTop = 1100, newestHeight = 5000, viewport = 800))
        // Nothing to show, or not yet measured.
        assertEquals(0, MainActivity.replyScrollTarget(replyTop = 1100, newestHeight = 0, viewport = 800))
        assertEquals(0, MainActivity.replyScrollTarget(replyTop = 1100, newestHeight = 200, viewport = 0))
    }
}
