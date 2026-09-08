package watch.rist.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

class IncomingCallActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val pixelTf: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.pixel) }
    private fun px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private var acted = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (!IncomingCall.ringing) { finishAndRemoveTask(); return }
            handler.postDelayed(this, 400L)
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

        render(intent?.getStringExtra(EXTRA_NUMBER).orEmpty())
    }

    private fun render(number: String) {
        val t = Themes.byId(Config.themeId(this))
        val name = CallerId.nameFor(this, number)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(t.ground)
            setPadding(px(28f), px(28f), px(28f), px(28f))

            addView(TextView(this@IncomingCallActivity).apply {
                text = "INCOMING CALL"
                setTextColor(t.inkMuted); textSize = 13f; typeface = pixelTf
                letterSpacing = 0.12f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, px(18f))
            })

            addView(TextView(this@IncomingCallActivity).apply {
                text = name ?: CallerId.pretty(number).ifBlank { "Unknown caller" }
                setTextColor(t.ink); textSize = 28f; typeface = pixelTf
                gravity = Gravity.CENTER
            })
            if (name != null && number.isNotBlank()) {
                addView(TextView(this@IncomingCallActivity).apply {
                    text = CallerId.pretty(number)
                    setTextColor(t.inkMuted); textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(0, px(6f), 0, 0)
                })
            }

            addView(button("ANSWER", t.accent, t.ground) { answer() }, spaced(px(40f)))
            addView(button("DECLINE", t.inkMuted, t.ground) { decline() }, spaced(px(14f)))
        })
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val n = intent?.getStringExtra(EXTRA_NUMBER).orEmpty()
        if (n.isNotBlank()) {
            Log.i(TAG, "caller id arrived after the screen was up; repainting")
            render(n)
        }
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    private fun button(label: String, fill: Int, ink: Int, onTap: () -> Unit) =
        TextView(this).apply {
            text = label
            setTextColor(ink); textSize = 22f; typeface = pixelTf
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(fill); cornerRadius = px(18f).toFloat()
            }
            setPadding(px(40f), px(24f), px(40f), px(24f))
            isClickable = true; isFocusable = true
            setOnClickListener { onTap() }
        }

    private fun answer() {
        acted = true
        val ok = runCatching {
            getSystemService(TelecomManager::class.java)?.acceptRingingCall()
            true
        }.onFailure { Log.w(TAG, "acceptRingingCall failed", it) }.getOrDefault(false)
        Log.i(TAG, "user answered (accepted=$ok)")
        runCatching {
            getSystemService(TelecomManager::class.java)?.showInCallScreen(false)
        }.onFailure { Log.w(TAG, "could not bring up the in-call screen", it) }
        finishAndRemoveTask()
    }

    private fun decline() {
        acted = true
        runCatching {
            getSystemService(TelecomManager::class.java)?.endCall()
        }.onFailure { Log.w(TAG, "endCall failed", it) }
        Log.i(TAG, "user declined")
        finishAndRemoveTask()
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

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchdog)
        if (!acted) Log.i(TAG, "call screen closed without an answer or decline")
    }

    @Deprecated("Inert by design")
    override fun onBackPressed() {  }

    companion object {
        private const val TAG = "RistIncoming"
        const val EXTRA_NUMBER = "number"
    }
}
