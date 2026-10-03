package watch.rist.assistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat

object ThemePaint {

    fun retint(
        ctx: Context,
        root: View?,
        t: RistTheme,
        faint: Int,
        tf: Typeface?,
        skip: Set<Int> = emptySet(),
    ) = runCatching {
        root ?: return@runCatching
        val inkC = ContextCompat.getColor(ctx, R.color.ink)
        val mutedC = ContextCompat.getColor(ctx, R.color.ink_muted)
        val faintC = ContextCompat.getColor(ctx, R.color.ink_faint)

        fun mapped(orig: Int): Int = when (orig) {
            inkC -> t.ink
            mutedC -> Themes.readableMuted(t)
            faintC -> faint
            else -> t.ink
        }

        fun walk(v: View) {
            if (v.id != View.NO_ID && v.id in skip) return
            when (v) {
                is SeekBar -> {
                    val on = ColorStateList.valueOf(t.ink)
                    v.progressTintList = on
                    v.thumbTintList = on
                    v.progressBackgroundTintList = ColorStateList.valueOf(faint)
                }
                is TextView -> {
                    val base = (v.getTag(R.id.tag_theme_base_color) as? Int)
                        ?: v.currentTextColor.also { c -> v.setTag(R.id.tag_theme_base_color, c) }
                    v.setTextColor(mapped(base))
                    // setTextColor does not touch the hint colour; it is mapped separately and stashed in its own tag.
                    val hintBase = (v.getTag(R.id.tag_theme_hint_color) as? Int)
                        ?: v.currentHintTextColor.also { c ->
                            v.setTag(R.id.tag_theme_hint_color, c)
                        }
                    v.setHintTextColor(mapped(hintBase))
                    // `v.typeface = tf` alone resets the style to NORMAL; carry the existing style over.
                    v.typeface = Typeface.create(tf, v.typeface?.style ?: Typeface.NORMAL)
                    if (t.typeScale != 1f || v.getTag(R.id.tag_theme_base_size) != null) scaleText(v, t)
                }
                is ImageView -> v.setColorFilter(t.ink)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }.let { }

    fun faintOf(t: RistTheme): Int =
        androidx.core.graphics.ColorUtils.blendARGB(t.ink, t.ground, 0.5f)

    /** The body typeface: the design's font, in bold when the design asks for bold body text. */
    fun typefaceOf(ctx: Context, t: RistTheme): Typeface? {
        val tf = Fonts.typeface(ctx, t.font)
        return if (t.bold) Typeface.create(tf, Typeface.BOLD) else tf
    }

    /** The clock's and the hold label's typeface. */
    fun displayTypefaceOf(ctx: Context, t: RistTheme): Typeface? = Fonts.typeface(ctx, t.displayFont)

    /** Body text never drops below this after scaling; smaller labels are never made smaller. */
    const val TEXT_FLOOR_SP = 14f

    /**
     * [baseSp] scaled by the design's text scale. Text drawn at 14 sp or more never goes below
     * 14 sp, and text drawn smaller is never shrunk further.
     */
    fun scaledSp(t: RistTheme, baseSp: Float): Float {
        val scaled = baseSp * t.typeScale
        return maxOf(scaled, minOf(baseSp, TEXT_FLOOR_SP))
    }

    /** Sets [v]'s size to its first-seen size scaled by [t]; safe to call on every repaint. */
    fun scaleText(v: TextView, t: RistTheme) {
        val baseSp = (v.getTag(R.id.tag_theme_base_size) as? Float)
            ?: (v.textSize / v.resources.displayMetrics.scaledDensity).also { v.setTag(R.id.tag_theme_base_size, it) }
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, scaledSp(t, baseSp))
    }
}
