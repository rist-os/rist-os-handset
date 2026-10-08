package watch.rist.assistant

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The drop-down banner for an incoming text, over whatever is on screen: Rist, or an allowlisted
 * app inside the kiosk. It is a system overlay window like [OverlayHomeService]'s button, because
 * the stock heads-up is withheld in lock task.
 *
 * Tap opens the conversation; swipe up puts it away; it goes by itself after [SHOW_MS]. A newer
 * text replaces the one showing.
 */
object TextAlertBanner {

    const val SHOW_MS = 6_000L

    /** Past this share of its height, a slow upward drag dismisses. */
    private const val DISMISS_FRACTION = 0.3f
    private const val FLING_DP_S = 700f
    private const val ANIM_MS = 160L

    const val TAG_BANNER = "text-alert-banner"
    const val TAG_TITLE = "text-alert-title"
    const val TAG_BODY = "text-alert-body"

    private const val TAG = "RistTextBanner"

    /** Where the banner's window goes. Swapped in tests for one that only remembers the view. */
    interface Host {
        fun add(ctx: Context, view: View): Boolean
        fun remove(view: View)
    }

    @Volatile internal var hostForTest: Host? = null
    private val host: Host get() = hostForTest ?: OverlayHost

    private val main = Handler(Looper.getMainLooper())
    private var showing: View? = null
    private val timeout = Runnable { dismiss() }

    /** The banner on screen now, if any. */
    fun current(): View? = showing

    fun show(ctx: Context, title: String, body: String, onOpen: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(ctx, title, body, onOpen) }
            return
        }
        removeNow()
        val view = build(ctx, title, body, onOpen)
        if (!host.add(ctx, view)) return
        showing = view
        view.translationY = -dp(ctx, 120f).toFloat()
        view.animate().translationY(0f).setDuration(ANIM_MS).start()
        main.postDelayed(timeout, SHOW_MS)
    }

    /** Slides the banner up and away. */
    fun dismiss() {
        val v = showing ?: return
        main.removeCallbacks(timeout)
        v.animate().translationY(-(v.height.coerceAtLeast(1)).toFloat()).alpha(0f)
            .setDuration(ANIM_MS).withEndAction { if (showing === v) removeNow() }.start()
    }

    private fun removeNow() {
        main.removeCallbacks(timeout)
        val v = showing ?: return
        showing = null
        v.animate().cancel()
        runCatching { host.remove(v) }.onFailure { Log.w(TAG, "banner not removed", it) }
    }

    internal fun resetForTest() {
        removeNow()
        hostForTest = null
    }

    private fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

    internal fun build(ctx: Context, title: String, body: String, onOpen: () -> Unit): View {
        val t = Themes.current(ctx)
        val tf = ThemePaint.typefaceOf(ctx, t)
        val d = ctx.resources.displayMetrics.density
        val card = LinearLayout(ctx).apply {
            tag = TAG_BANNER
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            minimumHeight = (64 * d).toInt()
            background = GradientDrawable().apply {
                // A design whose tiles are see-through would leave the banner unreadable over the app.
                setColor(if (android.graphics.Color.alpha(t.tileFill) == 255) t.tileFill else t.ground)
                cornerRadius = t.tileRadiusDp * d
                setStroke(Math.max(1, (t.borderWidthDp * d).toInt()), t.tileBorder)
            }
            elevation = 8 * d
            isClickable = true; isFocusable = true
            contentDescription = if (body.isBlank()) "$title. Tap to open, swipe up to dismiss."
                else "New message from $title: $body. Tap to open, swipe up to dismiss."
        }
        card.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_app_msg)
            setColorFilter(t.ink)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()).apply { rightMargin = (12 * d).toInt() })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            tag = TAG_TITLE
            text = title
            typeface = tf
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(t.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemePaint.scaledSp(t, 16f))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (body.isNotBlank()) col.addView(TextView(ctx).apply {
            tag = TAG_BODY
            text = body
            typeface = tf
            setTextColor(Themes.readableMuted(t))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemePaint.scaledSp(t, 15f))
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.setOnClickListener {
            removeNow()
            onOpen()
        }
        card.setOnTouchListener(SwipeUp(ctx) { dismiss() })

        // Margins come from a frame, so the card does not run edge to edge or under the status bar.
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * d).toInt(), statusBarHeight(ctx) + (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun statusBarHeight(ctx: Context): Int = runCatching {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)

    /** An upward drag moves the banner with the finger; far or fast enough, it goes. A tap is left alone. */
    private class SwipeUp(ctx: Context, private val onDismiss: () -> Unit) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val flingPx = FLING_DP_S * ctx.resources.displayMetrics.density
        private var downY = 0f
        private var dragging = false
        private var tracker: VelocityTracker? = null

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            // The whole window moves, so the drag is measured against the screen, not the card.
            val target = (v.parent as? View) ?: v
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY; dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                    main.removeCallbacks(TextAlertBanner.timeout)
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(ev)
                    val dy = ev.rawY - downY
                    if (!dragging && -dy > slop) {
                        dragging = true
                        v.isPressed = false
                        v.cancelLongPress()
                    }
                    if (dragging) target.translationY = Math.min(0f, dy)
                    return dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val was = dragging
                    dragging = false
                    val t = tracker
                    tracker = null
                    if (!was) {
                        t?.recycle()
                        main.postDelayed(TextAlertBanner.timeout, SHOW_MS)
                        return false
                    }
                    t?.addMovement(ev)
                    t?.computeCurrentVelocity(1000)
                    val vy = t?.yVelocity ?: 0f
                    t?.recycle()
                    val dy = target.translationY
                    val far = -dy > target.height * DISMISS_FRACTION
                    if (ev.actionMasked == MotionEvent.ACTION_UP && (far || vy < -flingPx)) {
                        onDismiss()
                    } else {
                        target.animate().translationY(0f).setDuration(ANIM_MS).start()
                        main.postDelayed(TextAlertBanner.timeout, SHOW_MS)
                    }
                    return true
                }
                else -> return dragging
            }
        }
    }

    private object OverlayHost : Host {
        override fun add(ctx: Context, view: View): Boolean {
            if (!Settings.canDrawOverlays(ctx)) {
                Log.w(TAG, "canDrawOverlays=false; no banner for the text")
                return false
            }
            return runCatching {
                val wm = ctx.getSystemService(WindowManager::class.java)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
                // As OverlayHomeService: @SystemApi, so reflective.
                runCatching {
                    WindowManager.LayoutParams::class.java
                        .getMethod("setSystemApplicationOverlay", java.lang.Boolean.TYPE)
                        .invoke(lp, true)
                }
                lp.gravity = Gravity.TOP
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                wm.addView(view, lp)
                true
            }.onFailure { Log.e(TAG, "banner window refused", it) }.getOrDefault(false)
        }

        override fun remove(view: View) {
            view.context.getSystemService(WindowManager::class.java)?.removeView(view)
        }
    }
}
