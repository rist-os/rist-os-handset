package watch.rist.assistant

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rist.v1.BoxSet
import rist.v1.HomeBox

/**
 * Tiles measured with real text metrics: whatever the backend sends and whatever the system text
 * size, a tile stays its fixed square and everything drawn in it lies inside it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeBoxesLayoutTest {
    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    private fun fontScale(s: Float) {
        val r = app.resources
        r.configuration.fontScale = s
        @Suppress("DEPRECATION") r.updateConfiguration(r.configuration, r.displayMetrics)
    }

    @Before fun setUp() { HomeBoxes.resetForTest(app); HomeBoxes.shippedForTest = true; fontScale(1f) }
    @After fun tidy() { HomeBoxes.resetForTest(app); fontScale(1f) }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun long(): List<HomeBox> {
        val now = System.currentTimeMillis() / 1000
        fun b(id: String) = HomeBox.newBuilder().setId(id).setKind("display").setState("ok")
            .setUpdatedAtEpochS(now - 30).setStaleAfterEpochS(now + 600)
        return listOf(
            b("weather").setTitle("Seattle weather this afternoon").setValue("Partly cloudy")
                .setDetail("High 61°, low 48°, rain after 6pm and wind from the south").build(),
            b("time").setTitle("Next meeting").setValue("12:45 PM").setDetail("Design review with the whole team")
                .setStaleAfterEpochS(now - 60).setUpdatedAtEpochS(now - 9000).build(),
            b("word").setTitle("Word").setValue("Antidisestablishmentarianism").setDetail("noun").build(),
            b("pending").setTitle("Stocks").setState("pending").setNote("Getting the latest prices from the exchange").build(),
            b("error").setTitle("Tides").setState("error").setNote("Couldn't reach the tide service for this harbour").build(),
            b("paused").setTitle("Commute").setState("paused").setNote("Paused until you are back at work on Monday").build(),
            HomeBox.newBuilder().setId("cmd").setKind("command").setState("ok")
                .setCommand("Text Sam that I am running about ten minutes late to dinner tonight").build(),
            HomeBox.newBuilder().setId("send").setKind("command").setState("ok")
                .setCommand("Read me every unread email from this morning, newest first").build(),
        )
    }

    /** Every view drawn in [tile] sits inside its padding, and no text is cut through a line. */
    private fun assertContained(tile: ViewGroup, sizeDp: Float, scale: Float) {
        val d = app.resources.displayMetrics.density
        val id = "${tile.tag} at ${scale}x"
        assertEquals("$id height", (sizeDp * d).toInt(), tile.height)
        val col = tile.getChildAt(0) as ViewGroup
        val top = col.paddingTop
        val bottom = col.height - col.paddingBottom
        fun walk(v: View, dy: Int) {
            if (v.visibility != View.VISIBLE) return
            val y0 = dy + v.top
            val y1 = dy + v.bottom
            if (v !== col) {
                assertTrue("$id: ${v.tag ?: v.javaClass.simpleName} spans $y0..$y1, outside $top..$bottom",
                    y0 >= top && y1 <= bottom)
            }
            if (v is TextView && v.text.isNotEmpty()) {
                val room = v.height - v.paddingTop - v.paddingBottom
                assertTrue("$id: '${v.text}' needs ${v.layout.height}px but has $room", v.layout.height <= room)
                assertTrue("$id: '${v.text}' has no room at all", room > 0)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), if (v === col) 0 else y0)
        }
        walk(col, 0)
    }

    @Test
    fun `long backend text stays inside the home row's squares at every text size`() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            fontScale(scale)
            HomeBoxes.resetForTest(app); HomeBoxes.shippedForTest = true
            HomeBoxes.apply(app, BoxSet.newBuilder().setVersion(3).addAllBoxes(long()).build())
            HomeBoxes.beginSend("send")
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            settle()
            val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.boxList)
            for (b in long()) {
                list.scrollToPosition(long().indexOf(b)); settle()
                val tile = requireNotNull(list.findViewWithTag<ViewGroup>(BoxBoard.TILE_TAG_PREFIX + b.id)) { b.id }
                assertContained(tile, BoxBoard.TILE_DP, scale)
            }
            val sending = list.findViewWithTag<ViewGroup>(BoxBoard.TILE_TAG_PREFIX + "send")
            assertEquals("Sending…", sending.findViewWithTag<TextView>(BoxBoard.DETAIL_TAG)?.text?.toString())
            HomeBoxes.endSend("send")
        }
    }

    @Test
    fun `long backend text stays inside the All grid's tiles at every text size`() {
        for (scale in listOf(1f, 2f)) {
            fontScale(scale)
            HomeBoxes.resetForTest(app); HomeBoxes.shippedForTest = true
            HomeBoxes.apply(app, BoxSet.newBuilder().setVersion(3).addAllBoxes(long()).build())
            val g = Robolectric.buildActivity(AllBoxesActivity::class.java).setup().get()
            settle()
            val root = g.window.decorView
            for (b in long()) {
                val tile = root.findViewWithTag<ViewGroup>(BoxBoard.TILE_TAG_PREFIX + b.id) ?: continue
                assertContained(tile, BoxBoard.GRID_H_DP, scale)
            }
        }
    }

    @Test
    fun `a stale tile shows its age before a long detail cuts it off`() {
        val now = 100_000L
        val b = HomeBox.newBuilder().setId("t").setTitle("Tides").setKind("display").setState("ok").setValue("5.1 ft")
            .setDetail("Seattle, Elliott Bay, next high tide this evening").setUpdatedAtEpochS(now - 3 * 3600)
            .setStaleAfterEpochS(now - 60).build()
        assertTrue(HomeBoxes.face(b, now).detail.startsWith("3h ago · stale"))
    }

    @Test
    fun `the expanded view dates an update that was not today`() {
        val now = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 12); set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        val today = BoxExpandedActivity.updatedWhen(now / 1000 - 60, now)
        val old = BoxExpandedActivity.updatedWhen(now / 1000 - 3 * 86_400, now)
        assertEquals(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(now - 60_000)), today)
        assertTrue("three days ago must carry its date: $old", old.length > today.length)
    }
}
