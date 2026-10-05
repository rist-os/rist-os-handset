package watch.rist.assistant

import android.Manifest
import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config as RConfig
import org.robolectric.annotation.GraphicsMode
import rist.v1.DesignSpec

/**
 * A server notice (the daily briefing) drawn on the home screen as an answer is drawn, measured
 * with real text metrics: a long one previews a few lines and opens in place to all of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RConfig(qualifiers = "w411dp-h891dp-xxhdpi")
class NotificationCardTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    private val briefing = "Good morning. **Weather:** Seattle is 52° and cloudy now, rising to a high of 61° " +
        "with rain after 6 pm and wind from the south at 15 mph. **News:** The city council approved the " +
        "new transit levy late last night after a six-hour session; the measure goes to voters in November. " +
        "Boeing said deliveries rose for the third month in a row. The Mariners won 5 to 3 in ten innings. " +
        "Gas prices fell four cents over the week. A winter storm watch is up for the Cascades from Thursday " +
        "night, with a foot of snow possible above 3,000 feet. The final headline is that the ferry to " +
        "Bainbridge runs on a reduced schedule this weekend for maintenance. END-OF-BRIEFING"

    @Before fun clean() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        Config.usePlainPrefsForTest(app)
        Config.setNotifications(app, "[]")
        Config.setSeenCommsIds(app, emptyList())
        Config.setCredentialRejected(app, false)
        Config.setEnrolRevoked(app, false)
        Config.setCarrierVoicemailWaiting(app, false)
        CommsFeedView.resetForTest()
    }

    @After fun tidy() {
        Config.setNotifications(app, "[]")
        Config.setSeenCommsIds(app, emptyList())
        CommsFeedView.resetForTest()
        Config.forgetPrefsForTest()
    }

    private fun wire(id: String, title: String, atS: Long = System.currentTimeMillis() / 1000) =
        rist.v1.Notification.newBuilder().setId(id).setKind("scheduled").setTitle(title)
            .setUrgency("passive").setCreatedAtEpochS(atS).build()

    private fun home(): Activity {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.setContentView(LinearLayout(a).apply { id = R.id.commsFeed; orientation = LinearLayout.VERTICAL })
        return a
    }

    private fun paint(a: Activity) {
        CommsFeedView.render(a)
        settle(a)
    }

    /** Measures at the screen width, lets the post-layout toggle land, and measures again. */
    private fun settle(a: Activity) {
        val host = a.findViewById<View>(R.id.commsFeed)
        val w = a.resources.displayMetrics.widthPixels
        repeat(2) {
            host.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            host.layout(0, 0, w, host.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun cards(a: Activity): List<ViewGroup> {
        val out = ArrayList<ViewGroup>()
        fun walk(v: View) {
            if (v.tag == CommsFeedView.NOTICE_CARD_TAG) out.add(v as ViewGroup)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.findViewById(R.id.commsFeed))
        return out
    }

    private fun card(a: Activity): ViewGroup = cards(a).single()
    private fun body(c: View): TextView = c.findViewWithTag(CommsFeedView.NOTICE_BODY_TAG)
    private fun toggle(c: View): TextView = c.findViewWithTag(CommsFeedView.NOTICE_TOGGLE_TAG)

    @Test
    fun `a long briefing previews a few lines, then shows every word after a tap`() {
        NotificationQueue.store(app, listOf(wire("brief-1", briefing)))
        val a = home(); paint(a)

        val closed = body(card(a))
        assertTrue("the preview is bounded", closed.lineCount <= CommsFeedView.NOTICE_PREVIEW_LINES)
        assertTrue("and it does cut the text", CommsFeedView.noticeOverflows(closed))
        val t = toggle(card(a))
        assertEquals(View.VISIBLE, t.visibility)
        assertEquals(CommsFeedView.NOTICE_SHOW_MORE, t.text.toString())
        val closedH = closed.height
        val lineH = closed.lineHeight
        assertTrue("collapsed height ~ ${CommsFeedView.NOTICE_PREVIEW_LINES} lines ($closedH px)",
            closedH <= lineH * (CommsFeedView.NOTICE_PREVIEW_LINES + 1))

        t.performClick(); settle(a)

        val open = body(card(a))
        val l = requireNotNull(open.layout)
        assertEquals("nothing ellipsized", 0, l.getEllipsisCount(l.lineCount - 1))
        assertTrue("more lines than the preview", open.lineCount > CommsFeedView.NOTICE_PREVIEW_LINES)
        assertTrue("the end of the briefing is in the text", open.text.toString().endsWith("END-OF-BRIEFING"))
        assertTrue("the view is tall enough for every line", open.height >= l.height)
        assertTrue("taller than the preview", open.height > closedH)
        assertEquals(CommsFeedView.NOTICE_SHOW_LESS, toggle(card(a)).text.toString())

        // Tapping the card itself closes it again.
        card(a).performClick(); settle(a)
        assertTrue(body(card(a)).lineCount <= CommsFeedView.NOTICE_PREVIEW_LINES)
        assertEquals(CommsFeedView.NOTICE_SHOW_MORE, toggle(card(a)).text.toString())
        assertEquals("still on screen after it was read", 1, cards(a).size)
    }

    @Test
    fun `a short notice shows whole with no show-more`() {
        NotificationQueue.store(app, listOf(wire("short-1", "Your package was delivered.")))
        val a = home(); paint(a)
        assertEquals(View.GONE, toggle(card(a)).visibility)
        assertFalse(CommsFeedView.noticeOverflows(body(card(a))))
        assertEquals("Your package was delivered.", body(card(a)).text.toString())
    }

    @Test
    fun `the card carries a visible accent bar on its leading edge`() {
        NotificationQueue.store(app, listOf(wire("bar-1", briefing)))
        val a = home(); paint(a)
        val c = card(a)
        val bar = c.findViewWithTag<View>(CommsFeedView.NOTICE_BAR_TAG)
        assertNotNull(bar)
        assertEquals("the bar is the first child", bar, c.getChildAt(0))
        assertEquals(View.VISIBLE, bar.visibility)
        val d = app.resources.displayMetrics.density
        assertEquals((5 * d).toInt(), bar.width)
        assertTrue("the bar runs the card's height", bar.height >= body(c).height)
        val color = (bar.background as ColorDrawable).color
        assertEquals(CommsFeedView.noticeBarColor(Themes.current(a)), color)
        val label = c.findViewWithTag<TextView>(CommsFeedView.NOTICE_LABEL_TAG).text.toString()
        assertTrue("not colour alone: the label says what it is ($label)", label.startsWith("NOTIFICATION · "))
    }

    @Test
    fun `the bar holds 3 to 1 and small accent text 4_5 to 1 in every theme`() {
        val hc = DesignSync.resolve(
            DesignSpec.newBuilder().setVersion(1).setBaseTheme("high_contrast").setCatalogue(1)
                .putTokens("color.ground", "#000000").putTokens("color.ink", "#FFFFFF")
                .putTokens("color.accent", "#FFD400").build()
        ) { true }.theme
        val faint = Themes.FACTORY.copy(accent = Themes.FACTORY.ground)
        for (t in Themes.ALL + hc + faint) {
            assertTrue("${t.id} bar", contrast(CommsFeedView.noticeBarColor(t), t.ground) >= 3.0)
            assertTrue("${t.id} text", contrast(CommsFeedView.noticeAccentText(t), t.ground) >= 4.5)
        }
    }

    @Test
    fun `the answer timer does not take a notice away`() {
        NotificationQueue.store(app, listOf(wire("keep-1", briefing, atS = System.currentTimeMillis() / 1000 - 3600)))
        val before = Config.transcriptMaxAgeMs(app)
        try {
            Config.setTranscriptMaxAgeMs(app, 60_000L)
            Transcript.all(app)
            val a = home(); paint(a)
            assertEquals(1, cards(a).size)
        } finally {
            Config.setTranscriptMaxAgeMs(app, before)
        }
    }

    @Test
    fun `swipe and close both dismiss, the ack is untouched, and a redelivery stays away`() {
        NotificationQueue.store(app, listOf(wire("d-1", "First."), wire("d-2", "Second.")))
        val a = home(); paint(a)
        assertEquals(2, cards(a).size)

        val swiped = cards(a).first { body(it).text.toString() == "First." }
        assertTrue(CommsFeedView.dismissRow(swiped)); settle(a)
        assertEquals(1, cards(a).size)

        card(a).findViewWithTag<View>(CommsFeedView.NOTICE_CLOSE_TAG).performClick(); settle(a)
        assertEquals(0, cards(a).size)

        assertEquals("both still owed an ack", setOf("d-1", "d-2"), NotificationQueue.pendingAcks(app).toSet())
        NotificationQueue.store(app, listOf(wire("d-1", "First.")))
        paint(a)
        assertEquals("a lost-ack redelivery does not bring it back", 0, cards(a).size)
    }

    @Test
    fun `a screen reader hears the whole notice, who sent it and when`() {
        NotificationQueue.store(app, listOf(wire("tb-1", briefing)))
        val a = home(); paint(a)
        val c = card(a)
        val said = c.contentDescription.toString()
        assertTrue(said, said.startsWith("Notification from Rist, "))
        assertTrue("the full text even while collapsed", said.contains("END-OF-BRIEFING"))
        assertFalse("markdown markers are not read out", said.contains("**"))
        assertEquals("Dismiss notification",
            c.findViewWithTag<View>(CommsFeedView.NOTICE_CLOSE_TAG).contentDescription.toString())
        val labels = c.createAccessibilityNodeInfo().actionList.mapNotNull { it.label?.toString() }
        assertTrue(labels.toString(), "Dismiss this notification" in labels)
        assertTrue(labels.toString(), "Show more" in labels)
    }

    @Test
    fun `a preview cut at a line break still offers show more`() {
        NotificationQueue.store(app, listOf(wire("lines-1", "One.\nTwo.\nThree.\nFour.\nFive is hidden.")))
        val a = home(); paint(a)
        assertTrue(CommsFeedView.noticeOverflows(body(card(a))))
        assertEquals(View.VISIBLE, toggle(card(a)).visibility)
    }

    @Test
    fun `a tapped notice stays read when the seen list forgets it`() {
        NotificationQueue.store(app, listOf(wire("r-1", "Your package was delivered.")))
        val a = home(); paint(a)
        card(a).performClick(); settle(a)
        Config.setSeenCommsIds(app, emptyList())
        paint(a)
        assertFalse("no NEW again", card(a).contentDescription.toString().endsWith("New."))
    }

    @Test
    fun `the bar's gap follows the reading direction`() {
        NotificationQueue.store(app, listOf(wire("rtl-1", "Short.")))
        val a = home(); paint(a)
        val lp = card(a).findViewWithTag<View>(CommsFeedView.NOTICE_BAR_TAG).layoutParams
            as ViewGroup.MarginLayoutParams
        assertTrue(lp.marginEnd > 0)
        assertTrue(lp.isMarginRelative)
    }
}
