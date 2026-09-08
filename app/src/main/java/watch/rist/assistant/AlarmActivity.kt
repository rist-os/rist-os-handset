package watch.rist.assistant

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

class AlarmActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val pixelTf: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.pixel) }
    private fun px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private val watchdog = object : Runnable {
        override fun run() {
            if (!DeviceCommands.ringing()) { finishAndRemoveTask(); return }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val t = Themes.byId(Config.themeId(this))
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Time's up" }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(t.ground)
            setPadding(px(28f), px(28f), px(28f), px(28f))

            addView(TextView(this@AlarmActivity).apply {
                text = label
                setTextColor(t.ink); textSize = 30f; typeface = pixelTf
                gravity = Gravity.CENTER
            })
            addView(TextView(this@AlarmActivity).apply {
                text = "Press DISMISS, or any volume button"
                setTextColor(t.inkMuted); textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, px(12f), 0, px(36f))
            })
            addView(TextView(this@AlarmActivity).apply {
                text = "DISMISS"
                setTextColor(t.ground); textSize = 24f; typeface = pixelTf
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(t.accent); cornerRadius = px(18f).toFloat()
                }
                setPadding(px(48f), px(28f), px(48f), px(28f))
                isClickable = true; isFocusable = true
                setOnClickListener { dismiss("the dismiss button") }
            })
        })
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(watchdog)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
            dismiss("a volume key"); true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {  }

    private fun dismiss(reason: String) {
        Log.i(TAG, "dismissing via $reason")
        AlarmService.dismiss(this, reason)
        // Remove the task, not just the activity; finish() alone can leave an empty task behind.
        finishAndRemoveTask()
    }

    companion object {
        private const val TAG = "RistAlarmUI"
        const val EXTRA_LABEL = "label"
    }
}
