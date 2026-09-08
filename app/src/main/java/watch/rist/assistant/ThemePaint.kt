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
                }
                is ImageView -> v.setColorFilter(t.ink)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }.let { }

    fun faintOf(t: RistTheme): Int =
        androidx.core.graphics.ColorUtils.blendARGB(t.ink, t.ground, 0.5f)

    fun typefaceOf(ctx: Context, t: RistTheme): Typeface? = when (t.font) {
        "pixel" -> runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.pixel) }.getOrNull()
        "mono" -> Typeface.MONOSPACE
        "serif" -> Typeface.SERIF
        else -> Typeface.SANS_SERIF
    }
}
