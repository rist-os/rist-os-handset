package watch.rist.assistant

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.DesignSpec

/** The look on screen: live redraw, the first frame after boot, protected controls, Settings > Look. */
@RunWith(RobolectricTestRunner::class)
class DesignSyncUiTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        DesignSync.resetForTest(app)
        Config.setThemeId(app, "ledger")
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(app, "http://127.0.0.1:9/v1/device")
    }

    @After
    fun tidy() {
        DesignSync.awaitFlushForTest()
        DesignSync.resetForTest(app)
        Config.setThemeId(app, "ledger")
    }

    private fun spec(version: Long, vararg tokens: Pair<String, String>, name: String = ""): DesignSpec =
        DesignSpec.newBuilder().setVersion(version).setBaseTheme("ledger").setCatalogue(1)
            .putAllTokens(tokens.toMap()).setName(name).build()

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun home(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get().also { settle() }

    private fun groundOf(a: MainActivity): Int =
        (a.findViewById<View>(R.id.root).background as ColorDrawable).color

    @Test
    fun `a new design redraws the home screen at once, without a restart`() {
        DesignSync.shippedForTest = true
        val a = home()
        assertEquals(Themes.FACTORY.ground, groundOf(a))
        DesignSync.apply(app, spec(2, "color.ground" to "#14284B", "color.ink" to "#F5F1E8"))
        settle()
        assertEquals(Color.parseColor("#14284B"), groundOf(a))
        assertEquals(Color.parseColor("#F5F1E8"), a.findViewById<TextView>(R.id.clockText).currentTextColor)
    }

    @Test
    fun `the stored design is on the first frame after boot`() {
        DesignSync.shippedForTest = true
        DesignSync.apply(app, spec(2, "color.ground" to "#14284B", "color.ink" to "#F5F1E8", "type.clock_size" to "sm"))
        DesignSync.forgetCacheForTest()
        val a = home()
        assertEquals(Color.parseColor("#14284B"), groundOf(a))
        val clock = a.findViewById<TextView>(R.id.clockText)
        assertEquals(40f, clock.textSize / a.resources.displayMetrics.scaledDensity, 0.01f)
    }

    @Test
    fun `the talk button and the gear stay visible, full size and readable whatever arrives`() {
        DesignSync.shippedForTest = true
        DesignSync.apply(app, spec(2,
            "color.ground" to "#FFFFFF", "color.ink" to "#FFFFFF", "color.ink_muted" to "#FFFFFF",
            "color.accent" to "#FFFFFF", "color.tile_fill" to "#FFFFFF", "type.scale" to "1.6",
            "style.tile" to "off", "shape.density" to "compact"))
        val a = home()
        val t = Themes.current(app)
        assertTrue(contrast(t.ink, t.ground) >= 4.5)
        assertTrue(contrast(t.accent, t.ground) >= 3.0)
        val d = a.resources.displayMetrics.density
        val gear = a.findViewById<ImageView>(R.id.settingsGear)
        assertEquals(View.VISIBLE, gear.visibility)
        assertTrue(gear.layoutParams.width >= (48 * d).toInt() && gear.layoutParams.height >= (48 * d).toInt())
        assertEquals("the gear is drawn in the text colour", PorterDuffColorFilter(t.ink, PorterDuff.Mode.SRC_ATOP), gear.colorFilter)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.talkButton).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.recordGlyph).visibility)
        assertNotNull("the knob still draws the mic", a.findViewById<View>(R.id.recordGlyph).background)
    }

    @Test
    fun `Settings shows who set the look and an offline reset in the factory look`() {
        DesignSync.shippedForTest = true
        DesignSync.apply(app, spec(4, "color.ground" to "#14284B", "color.ink" to "#F5F1E8", name = "Evening"))
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        settle()
        val root = s.window.decorView
        assertEquals("Set by your assistant: Evening",
            root.findViewWithTag<TextView>(SettingsActivity.LOOK_SOURCE_TAG).text.toString())
        val reset = root.findViewWithTag<TextView>(SettingsActivity.LOOK_RESET_TAG)
        assertEquals(Themes.FACTORY.ink, reset.currentTextColor)
        assertTrue(reset.minHeight >= (48 * s.resources.displayMetrics.density).toInt())
        reset.performClick()
        settle()
        assertEquals(Themes.FACTORY, Themes.current(app))
        assertTrue(DesignSync.pendingState(app)!!.resetOnDevice)
    }

    @Test
    fun `before it ships Settings keeps the theme picker and has no reset`() {
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        settle()
        val picker = s.findViewById<LinearLayout>(R.id.themePicker)
        assertTrue(picker.childCount > 0)
        assertNull(s.window.decorView.findViewWithTag<View>(SettingsActivity.LOOK_RESET_TAG))
    }
}
