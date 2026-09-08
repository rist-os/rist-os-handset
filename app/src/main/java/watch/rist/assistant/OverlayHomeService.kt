package watch.rist.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayHomeService : Service() {

    private var wm: WindowManager? = null
    private var button: View? = null
    private var desiredVisible = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.hasExtra(EXTRA_VISIBLE)) {
            desiredVisible = intent.getBooleanExtra(EXTRA_VISIBLE, false)
        }
        ensureButton()
        applyVisibility()
        return START_STICKY
    }

    private fun ensureButton() {
        if (button != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "canDrawOverlays=false; cannot show floating home button")
            return
        }
        try {
            addButton()
        } catch (e: Exception) {
            Log.e(TAG, "addButton failed", e)
        }
    }

    private fun applyVisibility() {
        button?.visibility = if (desiredVisible) View.VISIBLE else View.GONE
        Log.i(TAG, "applyVisibility visible=$desiredVisible")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun addButton() {
        val wmgr = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = wmgr
        val size = dp(52)

        val view = TextView(this).apply {
            text = "⌂"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6202124.toInt())
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            alpha = 0.92f
            contentDescription = "Back to Rist home"
            visibility = if (desiredVisible) View.VISIBLE else View.GONE
        }

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        // setSystemApplicationOverlay is @SystemApi; called reflectively so the public-SDK build compiles.
        runCatching {
            android.view.WindowManager.LayoutParams::class.java
                .getMethod("setSystemApplicationOverlay", java.lang.Boolean.TYPE)
                .invoke(lp, true)
        }
        lp.gravity = Gravity.TOP or Gravity.START
        val dm = resources.displayMetrics
        lp.x = dm.widthPixels - size - dp(12)
        lp.y = dm.heightPixels - size - dp(120)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                    lp.x = (startX + dx).roundToInt().coerceIn(0, dm.widthPixels - size)
                    lp.y = (startY + dy).roundToInt().coerceIn(0, dm.heightPixels - size)
                    runCatching { wmgr.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) goHome()
                    true
                }
                else -> false
            }
        }

        wmgr.addView(view, lp)
        button = view
        Log.i(TAG, "floating home button added (visible=$desiredVisible)")
    }

    private fun goHome() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            Log.e(TAG, "goHome failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        button?.let { v -> runCatching { wm?.removeView(v) } }
        button = null
    }

    companion object {
        private const val TAG = "RistOverlayHome"
        private const val EXTRA_VISIBLE = "visible"

        fun setVisible(ctx: Context, visible: Boolean) {
            runCatching {
                ctx.startService(
                    Intent(ctx, OverlayHomeService::class.java).putExtra(EXTRA_VISIBLE, visible)
                )
            }
        }
    }
}
