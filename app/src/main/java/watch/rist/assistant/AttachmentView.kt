package watch.rist.assistant

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.util.Locale

object AttachmentView {

    private const val TAG = "RistAttach"

    // Must match android:maxHeight on the ImageView in attachment_card.xml.
    internal const val MAX_IMAGE_HEIGHT_DP = 320

    // Must match android:maxLines on attachmentText in attachment_card.xml.
    internal const val COLLAPSED_LINES = 8

    internal const val EXPAND_CHAR_BUDGET = COLLAPSED_LINES * 20

    // WCAG 1.4.11 boundary contrast (3:1), not the 4.5:1 text holds to.
    internal const val CARD_EDGE_MIN = 3.0

    // Face:ground contrast above which the card face counts as its own surface.
    private const val FACE_IS_ITS_OWN_SURFACE = 1.05

    internal var cardLayout: Int = R.layout.attachment_card

    fun render(container: ViewGroup, items: List<RistAttachment>, insertAfter: Int = -1) = runCatching {
        removeOwnCards(container)
        // Resolved after removeOwnCards: removing cards shifts every later index.
        var at = if (insertAfter < 0) -1 else (insertAfter + 1).coerceIn(0, container.childCount)

        val ctx = container.context
        val t = Themes.byId(Config.themeId(ctx))
        val tf = ThemePaint.typefaceOf(ctx, t)
        val d = ctx.resources.displayMetrics.density
        val muted = readableOn(t.inkMuted, t.ink, t.tileFill)
        val inflater = LayoutInflater.from(ctx)

        for (item in items) {
            val card = runCatching { buildCard(inflater, container, t, tf, muted, d, item) }
                .onFailure { Log.w(TAG, "attachment card failed to build", it) }
                .getOrElse {
                    runCatching { minimalFailureCard(ctx, t, tf, d, item) }
                        .onFailure { e -> Log.w(TAG, "the fallback card failed to build too", e) }
                        .getOrNull()
                } ?: continue
            if (at < 0) container.addView(card) else container.addView(card, at++)
            // Must run after addView: announceForAccessibility is a no-op on a parentless view.
            announceIfFailed(container, card, item)
        }

        container.visibility = if (container.childCount == 0) View.GONE else View.VISIBLE
    }.onFailure { Log.w(TAG, "attachment render failed", it) }.let { }

    private fun announceIfFailed(container: ViewGroup, card: View, item: RistAttachment) {
        val res = container.resources
        val spoken = when {
            card is TextView -> card.text?.toString()
            else -> card.findViewById<TextView>(R.id.attachmentError)
                ?.takeIf { it.getTag(R.id.attachmentError) == true }
                ?.text?.toString()
                ?.let { res.getString(R.string.attach_announce_failed, displayTitle(res, item), it) }
        }
        if (spoken.isNullOrBlank()) return
        card.announceForAccessibility(spoken)
    }

    private fun fail(error: TextView, text: String) {
        error.visibility = View.VISIBLE
        error.text = text
        error.setTag(R.id.attachmentError, true)
    }

    private fun removeOwnCards(container: ViewGroup) {
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).id == R.id.attachmentCard) container.removeViewAt(i)
        }
    }

    private fun minimalFailureCard(
        ctx: Context,
        t: RistTheme,
        tf: android.graphics.Typeface?,
        d: Float,
        item: RistAttachment,
    ): View = TextView(ctx).apply {
        id = R.id.attachmentCard
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val pad = (14 * d).toInt()
        setPadding(pad, pad, pad, pad)
        setTextColor(t.accent)
        typeface = tf
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        text = ctx.getString(R.string.attach_card_failed, displayTitle(ctx.resources, item))
        visibility = View.VISIBLE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun buildCard(
        inflater: LayoutInflater,
        container: ViewGroup,
        t: RistTheme,
        tf: android.graphics.Typeface?,
        muted: Int,
        d: Float,
        item: RistAttachment,
    ): View {
        val res = container.resources
        val card = inflater.inflate(cardLayout, container, false)
        card.background = cardFace(t, d)

        val kindLabel = card.findViewById<TextView>(R.id.attachmentKind)
        val title = card.findViewById<TextView>(R.id.attachmentTitle)
        val meta = card.findViewById<TextView>(R.id.attachmentMeta)
        val image = card.findViewById<ImageView>(R.id.attachmentImage)
        val noteBlock = card.findViewById<View>(R.id.attachmentNoteBlock)
        val body = card.findViewById<TextView>(R.id.attachmentText)
        val expand = card.findViewById<TextView>(R.id.attachmentExpand)
        val error = card.findViewById<TextView>(R.id.attachmentError)

        kindLabel.setTextColor(muted); kindLabel.typeface = tf
        title.setTextColor(t.ink); title.typeface = tf
        meta.setTextColor(muted); meta.typeface = tf
        body.setTextColor(t.ink); body.typeface = tf
        expand.setTextColor(t.accent); expand.typeface = tf
        error.setTextColor(t.accent); error.typeface = tf

        kindLabel.text = kindWord(res, item)
        title.text = displayTitle(res, item)

        kindLabel.importantForAccessibility =
            if (item.title.isBlank()) View.IMPORTANT_FOR_ACCESSIBILITY_NO
            else View.IMPORTANT_FOR_ACCESSIBILITY_YES

        if (item.error != null) {
            fail(error, humanReason(res, item.error))
            return card
        }

        when (item.kind) {
            "image" -> renderImage(card, image, item, d)
            "text" -> renderText(noteBlock, body, expand, error, item)
            else -> renderData(meta, error, item)
        }
        return card
    }

    private fun renderImage(card: View, image: ImageView, item: RistAttachment, d: Float) {
        val res = card.resources
        val error = card.findViewById<TextView>(R.id.attachmentError)
        val bytes = item.bytes
        if (item.bitmap == null && (bytes == null || bytes.isEmpty())) {
            fail(error, res.getString(R.string.attach_no_bytes))
            return
        }
        val ctx = card.context
        val reqW = ctx.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val reqH = (MAX_IMAGE_HEIGHT_DP * d).toInt().coerceAtLeast(1)
        val bmp = item.bitmap ?: bytes?.let { decodeBounded(it, reqW, reqH) }
        if (bmp == null) {
            fail(error, res.getString(R.string.attach_bad_format, item.mime))
            return
        }
        image.setImageBitmap(bmp)
        image.visibility = View.VISIBLE
        image.contentDescription =
            if (item.title.isNotBlank()) item.title.trim()
            else res.getString(R.string.attach_image_desc)
    }

    private fun renderText(
        block: View,
        body: TextView,
        expand: TextView,
        error: TextView,
        item: RistAttachment,
    ) {
        val res = block.resources
        if (item.text.isBlank()) {
            fail(error, res.getString(R.string.attach_empty_note))
            return
        }
        val text = item.text.trim()
        body.text = text
        block.visibility = View.VISIBLE
        if (!needsExpand(text)) return

        expand.text = res.getString(R.string.attach_show_all)
        expand.contentDescription = res.getString(R.string.attach_show_all_desc)
        expand.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: android.view.accessibility.AccessibilityNodeInfo,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
        expand.visibility = View.VISIBLE
        expand.setOnClickListener { pressed ->
            body.maxLines = Integer.MAX_VALUE
            body.ellipsize = null
            pressed.visibility = View.GONE
        }
    }

    internal fun needsExpand(text: String): Boolean =
        text.length > EXPAND_CHAR_BUDGET || text.count { it == '\n' } >= COLLAPSED_LINES

    private fun renderData(meta: TextView, error: TextView, item: RistAttachment) {
        val res = meta.resources
        meta.visibility = View.VISIBLE
        meta.text = res.getString(
            R.string.attach_meta_fmt,
            item.mime.ifBlank { res.getString(R.string.attach_unknown_type) },
            item.bytes?.let { humanSize(res, it.size.toLong()) }
                ?: res.getString(R.string.attach_size_unknown),
        )
        error.visibility = View.VISIBLE
        error.text = res.getString(R.string.attach_cannot_open)
    }

    internal fun kindWord(res: Resources, item: RistAttachment): String = when (item.kind) {
        "image" -> res.getString(R.string.attach_kind_image)
        "text" -> res.getString(R.string.attach_kind_note)
        else -> res.getString(R.string.attach_kind_file)
    }

    internal fun displayTitle(res: Resources, item: RistAttachment): String =
        item.title.trim().ifBlank {
            when (item.kind) {
                "image" -> res.getString(R.string.attach_untitled_image)
                "text" -> res.getString(R.string.attach_untitled_note)
                else -> res.getString(R.string.attach_untitled_file)
            }
        }

    fun predecode(ctx: android.content.Context, items: List<RistAttachment>): List<RistAttachment> {
        if (items.isEmpty()) return items
        val d = ctx.resources.displayMetrics.density
        val reqW = ctx.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val reqH = (MAX_IMAGE_HEIGHT_DP * d).toInt().coerceAtLeast(1)
        return items.map { item ->
            val bytes = item.bytes
            if (item.kind != "image" || item.error != null || bytes == null || bytes.isEmpty()) item
            else {
                val bmp = runCatching { decodeBounded(bytes, reqW, reqH) }.getOrNull()
                // Bytes are kept on a failed decode: renderImage needs them to tell unreadable from never-arrived.
                if (bmp != null) item.copy(bitmap = bmp, bytes = null) else item.copy(bitmap = null)
            }
        }
    }

    internal fun humanReason(res: Resources, raw: String?): String {
        val r = raw?.trim()?.lowercase().orEmpty()
        return when {
            r.contains("too large") || r.contains("no room") ->
                res.getString(R.string.attach_reason_too_big)
            r.contains("not a valid image") || r.contains("format") ->
                res.getString(R.string.attach_reason_unreadable)
            r.isBlank() -> res.getString(R.string.attach_reason_unknown)
            else -> res.getString(R.string.attach_reason_absent)
        }
    }

    // Decimal units (1000), matching OtaConsent.formatBytes.
    internal fun humanSize(res: Resources, n: Long): String = when {
        n < 0L -> res.getString(R.string.attach_size_unknown)
        n == 1L -> res.getString(R.string.attach_size_one_byte)
        n < 1_000L -> res.getString(R.string.attach_size_bytes, n)
        n < 1_000_000L -> res.getString(R.string.attach_size_kb, oneDecimal(n / 1_000.0))
        n < 1_000_000_000L -> res.getString(R.string.attach_size_mb, oneDecimal(n / 1_000_000.0))
        else -> res.getString(R.string.attach_size_gb, oneDecimal(n / 1_000_000_000.0))
    }

    // Locale.US pins the decimal separator; the unit stays translatable.
    private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)

    // Powers of two only: BitmapFactory rounds anything else down.
    internal fun sampleSizeFor(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        if (w <= 0 || h <= 0 || reqW <= 0 || reqH <= 0) return 1
        var s = 1
        while (w / s > reqW || h / s > reqH) s = s shl 1
        return s
    }

    private fun decodeBounded(bytes: ByteArray, reqW: Int, reqH: Int) = runCatching {
        // Bounds pass first: dimensions are untrusted, decoding at native size can OOM.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, reqW, reqH)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.onFailure { Log.w(TAG, "image decode failed", it) }.getOrNull()

    internal fun readableOn(from: Int, toward: Int, bg: Int, target: Double = 4.5): Int {
        if (contrast(from, bg) >= target) return from
        var best = from
        var f = 0.1f
        while (f <= 1.0f) {
            best = blend(from, toward, f)
            if (contrast(best, bg) >= target) return best
            f += 0.1f
        }
        return best
    }

    internal fun cardEdge(t: RistTheme): Int {
        if (contrast(t.tileFill, t.ground) >= FACE_IS_ITS_OWN_SURFACE) return t.tileBorder
        return readableOn(t.tileBorder, t.ink, t.tileFill, CARD_EDGE_MIN)
    }

    private fun cardFace(t: RistTheme, d: Float): Drawable {
        val base = GradientDrawable().apply {
            setColor(t.tileFill)
            setStroke(
                (t.borderWidthDp * d).toInt().coerceAtLeast(if (t.borderWidthDp > 0f) 1 else 0),
                cardEdge(t),
            )
            cornerRadius = t.tileRadiusDp * d
        }
        if (!t.scan) return base
        return LayerDrawable(arrayOf(base, ScanlineDrawable(t.accent, t.tileRadiusDp * d, d)))
    }
}
