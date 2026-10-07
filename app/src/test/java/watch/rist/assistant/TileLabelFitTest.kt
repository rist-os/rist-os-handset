package watch.rist.assistant

import android.Manifest
import android.app.Activity
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
import rist.v1.BoxSet
import rist.v1.HomeBox

/** Tile labels shrink to fit instead of being cut to "WEATH…"; CLEAR ALL says what it clears. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RConfig(qualifiers = "w411dp-h891dp-xxhdpi")
class TileLabelFitTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        Config.usePlainPrefsForTest(app)
        Config.setNotifications(app, "[]")
        Config.setSeenCommsIds(app, emptyList())
        CommsFeedView.resetForTest()
        HomeBoxes.resetForTest(app)
        HomeBoxes.shippedForTest = true
        Config.setDeployDefaultsForTest("", "")
    }

    @After fun tidy() {
        HomeBoxes.awaitFlushForTest()
        HomeBoxes.resetForTest(app)
        Config.setNotifications(app, "[]")
        CommsFeedView.resetForTest()
        Config.forgetPrefsForTest()
    }

    private fun settle() = repeat(3) { shadowOf(Looper.getMainLooper()).idle() }

    private fun box(id: String, title: String) = HomeBox.newBuilder().setId(id).setTitle(title).setKind("display")
        .setState("ok").setValue("52°").setUpdatedAtEpochS(System.currentTimeMillis() / 1000).build()

    private fun label(a: Activity, id: String): TextView {
        val list = a.findViewById<RecyclerView>(R.id.boxList)
        val tile = requireNotNull(list.findViewWithTag<ViewGroup>(BoxBoard.TILE_TAG_PREFIX + id)) { "no tile $id" }
        return tile.findViewWithTag(BoxBoard.LABEL_TAG)
    }

    @Test
    fun `box labels shrink to fit rather than being cut`() {
        HomeBoxes.apply(app, BoxSet.newBuilder().setVersion(3)
            .addBoxes(box("w", "Weather")).addBoxes(box("t", "Test tile")).addBoxes(box("c", "Calendar")).build())
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val sp = app.resources.displayMetrics.scaledDensity
        for (id in listOf("w", "t", "c")) {
            val v = label(a, id)
            assertEquals(0f, v.letterSpacing)
            assertEquals(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM, v.autoSizeTextType)
            assertEquals(BoxBoard.LABEL_MIN_SP.toFloat(), v.autoSizeMinTextSize / sp, 0.01f)
            assertEquals(10f, v.autoSizeMaxTextSize / sp, 0.01f)
            val l = requireNotNull(v.layout) { "label $id not laid out" }
            assertEquals("${v.text} is whole on one line", 1, l.lineCount)
            assertEquals("${v.text} is not ellipsized", 0, l.getEllipsisCount(0))
            assertTrue("${v.text} stays legible (${v.textSize / sp} sp)", v.textSize / sp >= BoxBoard.LABEL_MIN_SP)
        }
    }

    @Test
    fun `a label too long even at the smallest size is ellipsized, not clipped`() {
        HomeBoxes.apply(app, BoxSet.newBuilder().setVersion(3)
            .addBoxes(box("x", "Extraordinarily long tile name that cannot possibly fit")).build())
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val v = label(a, "x")
        assertEquals(android.text.TextUtils.TruncateAt.END, v.ellipsize)
        assertEquals(1, v.maxLines)
    }

    @Test
    fun `CLEAR ALL tells a screen reader it clears every notification`() {
        NotificationQueue.store(app, listOf(rist.v1.Notification.newBuilder().setId("n1").setKind("scheduled")
            .setTitle("Reminder").setUrgency("passive").setCreatedAtEpochS(System.currentTimeMillis() / 1000).build()))
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.setContentView(LinearLayout(a).apply { id = R.id.commsFeed; orientation = LinearLayout.VERTICAL })
        CommsFeedView.render(a)
        fun find(v: View): TextView? {
            if (v is TextView && v.text.toString().equals("CLEAR ALL", ignoreCase = true)) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val clear = requireNotNull(find(a.findViewById(R.id.commsFeed)))
        assertEquals("Clear all notifications, 1 new", clear.contentDescription.toString())
        assertEquals("Clear all notifications", CommsFeedView.clearAllSpoken(0))
    }
}
