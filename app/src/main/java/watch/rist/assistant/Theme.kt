package watch.rist.assistant

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

internal fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
    (Color.red(a) * (1 - f) + Color.red(b) * f).toInt(),
    (Color.green(a) * (1 - f) + Color.green(b) * f).toInt(),
    (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt(),
)
internal fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

/** font: pixel|mono|grot|serif; knob: ring|pixel|dome. */
data class RistTheme(
    val id: String,
    val name: String,
    val ground: Int,
    val ink: Int,
    val inkMuted: Int,
    val tileFill: Int,
    val tileBorder: Int,
    val tileRadiusDp: Float,
    val borderWidthDp: Float,
    val accent: Int,
    val fieldBorder: Int,
    val fieldRadiusDp: Float,
    val pixelFont: Boolean,
    val dark: Boolean,
    val font: String = if (pixelFont) "pixel" else "grot",
    val knob: String = if (pixelFont) "pixel" else "ring",
    val knobGlow: Boolean = false,
    val tileGlow: Boolean = false,
    val scan: Boolean = false,
    val floodOnPress: Boolean = true,
    val tile: Boolean = true,
    val inkFaint: Int? = null,
    val fieldFill: Int? = null,
    val holdOnAccent: Boolean = false,
    val displayFont: String = font,
) {
    val lineIcons: Boolean get() = font != "pixel"
}

class KnobDrawable(
    private val t: RistTheme,
    private val d: Float,
    private val mic: Drawable? = null,
) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    var recording: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val s = minOf(b.width(), b.height()).toFloat()
        if (t.knobGlow) {
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(cx, cy, s * 0.5f,
                withAlpha(t.accent, 120), withAlpha(t.accent, 0), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, s * 0.5f, p)
            p.shader = null
        }
        val r = if (t.knobGlow) s * 0.33f else s * 0.42f
        val dotR = s * 0.10f
        when (t.knob) {
            "dome" -> {
                p.style = Paint.Style.FILL
                p.shader = RadialGradient(cx - r * 0.26f, cy - r * 0.42f, r * 1.3f,
                    intArrayOf(Color.WHITE, blend(t.ground, t.ink, 0.14f), blend(t.ground, t.ink, 0.34f)),
                    floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, r, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f * d
                p.color = blend(t.ground, t.ink, 0.30f); canvas.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.FILL; p.color = t.accent; canvas.drawCircle(cx, cy, dotR, p)
            }
            "pixel" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.085f; p.color = t.accent
                val cr = s * 0.05f
                canvas.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), cr, cr, p)
                p.style = Paint.Style.FILL
                canvas.drawRect(cx - dotR, cy - dotR, cx + dotR, cy + dotR, p)
            }
            else -> {
                val inset = minOf(16.05f * d, s * 0.115f)
                val rr = s * 0.5f - inset
                val ringW = minOf(3.5f * d, s * 0.04f)
                if (recording) {
                    val collarW = inset * 0.98f
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = collarW
                    p.color = withAlpha(t.accent, 46)
                    canvas.drawCircle(cx, cy, rr + collarW * 0.5f, p)
                    p.style = Paint.Style.FILL
                    p.color = t.accent
                    canvas.drawCircle(cx, cy, rr + ringW * 0.5f, p)
                    drawCore(canvas, cx, cy, rr, dotR, t.ground)
                } else {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = ringW
                    p.color = t.accent
                    canvas.drawCircle(cx, cy, rr, p)
                    drawCore(canvas, cx, cy, rr, dotR, t.accent)
                }
            }
        }
    }
    private fun drawCore(canvas: Canvas, cx: Float, cy: Float, rr: Float, dotR: Float, tint: Int) {
        val g = mic
        if (g == null) {
            p.style = Paint.Style.FILL; p.color = tint
            canvas.drawCircle(cx, cy, dotR, p)
            return
        }
        val half = (rr * 0.36f).toInt()
        g.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        g.setTint(tint)
        g.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("deprecated in API") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

class ScanlineDrawable(color: Int, private val radius: Float, private val d: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(color, 30) }
    private val clip = Path()
    override fun onBoundsChange(b: Rect) {
        clip.reset(); clip.addRoundRect(RectF(b), radius, radius, Path.Direction.CW)
    }
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val save = canvas.save(); canvas.clipPath(clip)
        val gap = 3f * d; val line = 1f * d
        var y = b.top.toFloat()
        while (y < b.bottom) { canvas.drawRect(b.left.toFloat(), y, b.right.toFloat(), y + line, p); y += gap }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("deprecated in API") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun luminance(c: Int): Double {
    fun ch(v: Int): Double {
        val x = v / 255.0
        return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
}

internal fun contrast(a: Int, b: Int): Double {
    val la = luminance(a); val lb = luminance(b)
    val hi = maxOf(la, lb); val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

object Themes {
    private fun c(h: String) = Color.parseColor(h)

    fun readableMuted(t: RistTheme): Int {
        if (contrast(t.inkMuted, t.ground) >= 4.5) return t.inkMuted
        var best = t.inkMuted
        var f = 0.1f
        while (f <= 1.0f) {
            best = blend(t.inkMuted, t.ink, f)
            if (contrast(best, t.ground) >= 4.5) return best
            f += 0.1f
        }
        return best
    }

    fun tileFace(t: RistTheme, d: Float): Drawable {
        val base = GradientDrawable().apply {
            setColor(t.tileFill)
            setStroke((t.borderWidthDp * d).toInt().coerceAtLeast(if (t.borderWidthDp > 0f) 1 else 0), t.tileBorder)
            cornerRadius = t.tileRadiusDp * d
        }
        if (!t.scan) return base
        return LayerDrawable(arrayOf(base, ScanlineDrawable(t.accent, t.tileRadiusDp * d, d)))
    }

    fun knob(t: RistTheme, d: Float, mic: Drawable? = null): Drawable = KnobDrawable(t, d, mic)

    private val LEDGER = RistTheme("ledger","Ledger",
        c("#EDE5D5"), c("#241F19"), c("#7B7062"), c("#F6F1E6"), c("#DED4C0"),
        14f, 1.75f, c("#AF452B"), c("#DED4C0"), 35f, false, false, font="grot", knob="ring",
        floodOnPress=false, tile=false, fieldFill=c("#F6F1E6"), inkFaint=c("#9A9184"))

    private val NIGHT = RistTheme("night","Night",
        c("#0B0C0A"), c("#C9D4C2"), c("#5D6A57"), c("#0B0C0A"), c("#232B20"),
        6f, 1.75f, c("#E3B23C"), c("#232B20"), 6f, false, true, font="mono", knob="ring",
        knobGlow=false, floodOnPress=false, tile=false, inkFaint=c("#4E5A49"),
        holdOnAccent=true, displayFont="pixel")

    /** byId() resolves against ALL; a theme must also be in CATEGORIES to show in the picker. */
    val ALL = listOf(
        LEDGER, NIGHT,
    )

    data class Category(val title: String, val subtitle: String, val themes: List<RistTheme>)

    val CATEGORIES = listOf(
        Category("Ledger & Night",  "one accent · ring knob", listOf(LEDGER, NIGHT)),
    )

    fun byId(id: String?): RistTheme = ALL.firstOrNull { it.id == id } ?: LEDGER
}
