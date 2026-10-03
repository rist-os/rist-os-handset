package watch.rist.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import rist.v1.HomeBox

/**
 * The icon on a home box.
 *
 * A box names an icon from Material Symbols, which is bundled (a static outlined instance, drawn
 * by codepoint from the name list shipped beside it). When no named icon fits, the backend may
 * send a small PNG instead; it is drawn as an alpha mask in the theme's colour, so it matches any
 * theme. A name this phone does not know falls back to the image; an image that is too large or
 * will not decode draws nothing. Nothing is ever fetched.
 */
object BoxIcons {

    private const val TAG = "RistBoxIcons"

    const val FONT_ASSET = "icons/material_symbols_outlined.ttf"
    const val NAMES_ASSET = "icons/material_symbols_outlined.codepoints"

    const val IMAGE_MAX_BYTES = 16 * 1024
    const val IMAGE_MAX_PX = 96

    sealed class Source {
        data class Named(val codepoint: Int) : Source()
        class Image(val mask: Bitmap) : Source()
        object None : Source()
    }

    @Volatile private var names: Map<String, Int>? = null
    @Volatile private var font: Typeface? = null

    private fun names(ctx: Context): Map<String, Int> {
        names?.let { return it }
        synchronized(this) {
            names?.let { return it }
            val m = runCatching {
                ctx.applicationContext.assets.open(NAMES_ASSET).bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val parts = line.trim().split(' ')
                        if (parts.size != 2) null
                        else parts[1].toIntOrNull(16)?.let { parts[0] to it }
                    }.toMap()
                }
            }.onFailure { Log.w(TAG, "icon names unreadable", it) }.getOrDefault(emptyMap())
            names = m
            return m
        }
    }

    fun typeface(ctx: Context): Typeface? {
        font?.let { return it }
        synchronized(this) {
            font?.let { return it }
            val tf = runCatching { Typeface.createFromAsset(ctx.applicationContext.assets, FONT_ASSET) }
                .onFailure { Log.w(TAG, "icon font unreadable", it) }.getOrNull()
            font = tf
            return tf
        }
    }

    /** The codepoint for a Material Symbols name, or null when this phone does not have it. */
    fun codepoint(ctx: Context, name: String): Int? {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return null
        return names(ctx)[n]
    }

    /**
     * Width and height from a PNG's header, or null when it is not a PNG. Read by hand, before any
     * decoder sees the bytes, so the size limit cannot be argued with.
     */
    internal fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (bytes.size < 24) return null
        for (i in sig.indices) if (bytes[i] != sig[i]) return null
        if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }

    /** Whether a custom icon is within the limits: a PNG, at most 16 KB and 96 x 96 px. */
    fun imageAcceptable(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > IMAGE_MAX_BYTES) return false
        val (w, h) = pngSize(bytes) ?: return false
        return w in 1..IMAGE_MAX_PX && h in 1..IMAGE_MAX_PX
    }

    // Decoded masks by box id and content hash; a rejected image is remembered too.
    private val masks = object : LruCache<String, Any>(64) {}
    private val REJECTED = Any()

    private fun mask(boxId: String, bytes: ByteArray): Bitmap? {
        val key = boxId + ":" + bytes.contentHashCode()
        masks.get(key)?.let { return it as? Bitmap }
        val decoded = if (!imageAcceptable(bytes)) null else runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                if (bmp.width > IMAGE_MAX_PX || bmp.height > IMAGE_MAX_PX) null else bmp.extractAlpha()
            }
        }.getOrNull()
        masks.put(key, decoded ?: REJECTED)
        return decoded
    }

    /** What to draw for [b]: its named icon, else its image, else nothing. */
    fun resolve(ctx: Context, b: HomeBox): Source {
        codepoint(ctx, b.icon)?.let { return Source.Named(it) }
        if (!b.iconImage.isEmpty) mask(b.id, b.iconImage.toByteArray())?.let { return Source.Image(it) }
        return Source.None
    }

    /** The icon as a drawable in [colour], or null when the box has none. */
    fun drawable(ctx: Context, b: HomeBox, colour: Int): Drawable? = when (val s = resolve(ctx, b)) {
        is Source.Named -> typeface(ctx)?.let { GlyphDrawable(String(Character.toChars(s.codepoint)), it, colour) }
        is Source.Image -> MaskDrawable(s.mask, colour)
        Source.None -> null
    }

    internal fun clearForTest() = masks.evictAll()

    /** One glyph of the icon font, filling its bounds. */
    class GlyphDrawable(val text: String, tf: Typeface, colour: Int) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; color = colour; textAlign = Paint.Align.CENTER
        }
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            p.textSize = b.height().toFloat()
            val fm = p.fontMetrics
            val y = b.exactCenterY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, b.exactCenterX(), y, p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("deprecated in API") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** An alpha mask painted in one colour. */
    class MaskDrawable(val mask: Bitmap, colour: Int) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = colour }
        private val src = Rect(0, 0, mask.width, mask.height)
        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            canvas.drawBitmap(mask, src, bounds, p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("deprecated in API") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
