package watch.rist.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * A picture that pinches to zoom, drags when zoomed, and double-taps between fitted and 2.5x.
 * It never lets the picture drift off the screen: at every step the matrix is pulled back so
 * the picture fills whatever it can and is centred where it cannot.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    companion object {
        internal const val MAX_ZOOM = 5f
        internal const val DOUBLE_TAP_ZOOM = 2.5f

        /**
         * The offset that keeps a picture of [content] length on a view of [view] length: centred
         * when it is smaller, and never showing a gap at either edge when it is larger.
         */
        internal fun clampOffset(offset: Float, content: Float, view: Float): Float =
            if (content <= view) (view - content) / 2f
            else offset.coerceIn(view - content, 0f)
    }

    /** Called on a long press; the viewer offers to save the picture. */
    var onLongPress: (() -> Unit)? = null

    private val m = Matrix()
    private val values = FloatArray(9)
    private var fitScale = 1f

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (scale() * detector.scaleFactor).coerceIn(fitScale, fitScale * MAX_ZOOM)
            val factor = target / scale()
            m.postScale(factor, factor, detector.focusX, detector.focusY)
            settle()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale() <= fitScale * 1.001f) return false
            m.postTranslate(-dx, -dy)
            settle()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (scale() > fitScale * 1.2f) fitScale else fitScale * DOUBLE_TAP_ZOOM
            val factor = target / scale()
            m.postScale(factor, factor, e.x, e.y)
            settle()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            onLongPress?.invoke()
        }
    })

    private fun scale(): Float {
        m.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    /** Fits the picture to the view, centred. Called whenever the picture or the view changes. */
    fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        fitScale = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        m.reset()
        m.postScale(fitScale, fitScale)
        settle()
    }

    private fun settle() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        val x = clampOffset(r.left, r.width(), width.toFloat())
        val y = clampOffset(r.top, r.height(), height.toFloat())
        m.postTranslate(x - r.left, y - r.top)
        imageMatrix = m
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        fit()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}
