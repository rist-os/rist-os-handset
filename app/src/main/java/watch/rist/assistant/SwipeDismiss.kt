package watch.rist.assistant

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration

/**
 * Swipe a row sideways, either way, to dismiss it. A tap still reaches the row and an
 * up-or-down drag still scrolls: only a drag that is plainly sideways is taken.
 *
 * Feed it every touch event of the view the finger is on, with [row] as the view that moves.
 * It returns true while it owns the gesture, so the caller stops treating it as a tap.
 */
class SwipeDismiss(ctx: Context, private val onDismiss: () -> Unit) {

    companion object {
        /** Past this share of the row's width, a slow swipe dismisses; short of it, it springs back. */
        internal const val DISMISS_FRACTION = 0.35f

        /** A flick this fast dismisses however short it was, in dp per second. */
        private const val FLING_DP_S = 900f

        private const val ANIM_MS = 160L
    }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val flingPx = FLING_DP_S * ctx.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    fun onTouch(row: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > 2 * Math.abs(dy)) {
                    dragging = true
                    row.parent?.requestDisallowInterceptTouchEvent(true)
                    row.isPressed = false
                    row.cancelLongPress()
                }
                if (dragging) {
                    row.translationX = dx
                    row.alpha = 1f - Math.min(1f, Math.abs(dx) / Math.max(1, row.width)) * 0.7f
                }
                return dragging
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                dragging = false
                val t = tracker
                tracker = null
                if (!wasDragging) { t?.recycle(); return false }
                t?.addMovement(ev)
                t?.computeCurrentVelocity(1000)
                val vx = t?.xVelocity ?: 0f
                t?.recycle()
                val dx = row.translationX
                val far = Math.abs(dx) > row.width * DISMISS_FRACTION
                val flung = Math.abs(vx) > flingPx && Math.signum(vx) == Math.signum(dx)
                if (ev.actionMasked == MotionEvent.ACTION_UP && (far || flung)) {
                    val off = if (dx < 0) -row.width.toFloat() else row.width.toFloat()
                    row.animate().translationX(off).alpha(0f).setDuration(ANIM_MS)
                        .withEndAction { onDismiss() }.start()
                } else {
                    row.animate().translationX(0f).alpha(1f).setDuration(ANIM_MS).start()
                }
                return true
            }
            else -> return dragging
        }
    }
}

/**
 * A column whose children can be tapped but not swiped: [intercept] sees every touch on its
 * way to a child, and once it returns true the column takes the gesture over and the child
 * hears a cancel. Without it a sideways swipe that began on a clickable child, a checklist row
 * say, never reached the swipe and ended as a click on the child.
 */
class SwipeColumn(ctx: Context) : android.widget.LinearLayout(ctx) {
    var intercept: ((MotionEvent) -> Boolean)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        intercept?.invoke(ev) == true || super.onInterceptTouchEvent(ev)
}
