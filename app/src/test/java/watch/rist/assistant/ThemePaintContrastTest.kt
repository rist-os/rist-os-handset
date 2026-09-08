package watch.rist.assistant

import android.content.Context
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemePaintContrastTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) +
            0.7152 * ch((c shr 8) and 0xFF) +
            0.0722 * ch(c and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun themes(): List<RistTheme> = Themes.CATEGORIES.flatMap { it.themes }

    @Test
    fun `retint paints muted text at the lifted tone, not the raw palette value`() {
        for (t in themes()) {
            val v = TextView(ctx).apply {
                setTextColor(ContextCompat.getColor(ctx, R.color.ink_muted))
            }
            ThemePaint.retint(ctx, v, t, ThemePaint.faintOf(t), null)
            assertEquals(
                "${t.id}: muted text must be painted with Themes.readableMuted, not t.inkMuted -- " +
                    "the raw value is what failed contrast on the XML half of every screen",
                Themes.readableMuted(t),
                v.currentTextColor,
            )
        }
    }

    @Test
    fun `every theme's muted text clears 4_5 to 1 against its own ground once retinted`() {
        for (t in themes()) {
            val v = TextView(ctx).apply {
                setTextColor(ContextCompat.getColor(ctx, R.color.ink_muted))
            }
            ThemePaint.retint(ctx, v, t, ThemePaint.faintOf(t), null)
            val ratio = contrast(v.currentTextColor, t.ground)
            assertTrue(
                "${t.id}: muted text is ${"%.2f".format(ratio)}:1 against the ground, below 4.5:1",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `a hint is themed too, and is not left at whatever the layout hardcoded`() {
        val hardcoded = ContextCompat.getColor(ctx, R.color.ink_muted)
        for (t in themes()) {
            val v = EditText(ctx).apply { setHintTextColor(hardcoded) }
            ThemePaint.retint(ctx, v, t, ThemePaint.faintOf(t), null)
            assertEquals(
                "${t.id}: the hint colour must be mapped through the theme like any other muted text",
                Themes.readableMuted(t),
                v.currentHintTextColor,
            )
        }
    }

    @Test
    fun `retinting twice does not drift, because the original colour is stashed`() {
        val all = themes()
        if (all.size < 2) return
        val v = EditText(ctx).apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.ink_muted))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.ink_muted))
        }
        ThemePaint.retint(ctx, v, all[0], ThemePaint.faintOf(all[0]), null)
        ThemePaint.retint(ctx, v, all[1], ThemePaint.faintOf(all[1]), null)
        ThemePaint.retint(ctx, v, all[0], ThemePaint.faintOf(all[0]), null)
        assertEquals(
            "returning to a theme must give back that theme's colour exactly",
            Themes.readableMuted(all[0]),
            v.currentTextColor,
        )
        assertEquals(
            "the hint must return exactly too, which needs its own stash slot",
            Themes.readableMuted(all[0]),
            v.currentHintTextColor,
        )
    }

    @Test
    fun `the raw palette muted really was worse, so this test is guarding something`() {
        val differs = themes().count { Themes.readableMuted(it) != it.inkMuted }
        assertNotEquals(
            "no theme's readableMuted differs from its raw inkMuted, so the tests above are vacuous",
            0,
            differs,
        )
    }
}
