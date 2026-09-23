package watch.rist.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
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

    /** The number the visible layout was built for, so a repeat broadcast does not rebuild it. */
    private var shown: String? = null

    private val watchdog = object : Runnable {
        override fun run() {
            if (!IncomingCall.ringing) { finishAndRemoveTask(); return }
            handler.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Telephony is the authority on whether a call is ringing, not our own statics: they are
        // process-global and empty again after a restart, and the system can recreate this activity.
        // Asking first means a recreated screen adopts a live ring instead of the watchdog closing it
        // mid-call, and a stale relaunch closes itself here rather than sitting over the launcher for
        // up to one watchdog tick. A null reading (no telephony) falls back to what we remember.
        val number = intent?.getStringExtra(EXTRA_NUMBER).orEmpty()
        val callState = runCatching {
            getSystemService(TelephonyManager::class.java)?.callState
        }.onFailure { Log.w(TAG, "could not read the call state", it) }.getOrNull()
        when (callState) {
            TelephonyManager.CALL_STATE_RINGING -> IncomingCall.adoptRing(number)
            null -> if (!IncomingCall.ringing) {
                Log.i(TAG, "no call state available and nothing ringing; closing the call screen")
                finishAndRemoveTask()
                return
            }
            else -> {
                Log.i(TAG, "telephony reports call state $callState, not ringing; closing the screen")
                IncomingCall.clear()
                finishAndRemoveTask()
                return
            }
        }

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

        render(number)
    }

    private fun render(number: String) {
        shown = number
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

    /**
     * Repeats are ordinary, not exceptional.
     *
     * Telephony sends PHONE_STATE twice per transition -- once without the number, then again with it
     * for a holder of READ_CALL_LOG -- and [IncomingCall.show] re-raises the screen on every RINGING
     * broadcast so a screen dropped behind HOME comes back. Both land here. Repainting only when the
     * number actually changed keeps that free: a rebuild throws away the ANSWER and DECLINE buttons
     * and builds new ones, which the user may be mid-tap on.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val n = intent?.getStringExtra(EXTRA_NUMBER).orEmpty()
        if (n.isNotBlank() && n != shown) {
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

    /**
     * A failed answer must not close the screen.
     *
     * This used to read `runCatching { tm?.acceptRingingCall(); true }`, which is `true` whenever
     * nothing throws -- including when there is no TelecomManager at all, because acceptRingingCall
     * returns Unit. It then closed the screen either way. That turns a failed answer into a phone that
     * rings with no way to pick it up: the call never connects, so no OFFHOOK or IDLE is coming to put
     * the screen back. Staying put at least leaves the button there to press again.
     */
    private fun answer() {
        val tm = getSystemService(TelecomManager::class.java)
        if (tm == null) {
            Log.e(TAG, "no TelecomManager; cannot answer, leaving the screen up to try again")
            return
        }
        runCatching { tm.acceptRingingCall() }.onFailure {
            Log.e(TAG, "acceptRingingCall failed; leaving the screen up so the call stays answerable", it)
            return
        }
        acted = true
        // Not waiting for OFFHOOK: the screen is going, so the state it guards goes with it.
        IncomingCall.clear()
        Log.i(TAG, "user answered")
        runCatching { tm.showInCallScreen(false) }
            .onFailure { Log.w(TAG, "could not bring up the in-call screen", it) }
        finishAndRemoveTask()
    }

    /**
     * Declining always closes the screen, because the user asked for it gone. But endCall returns a
     * boolean and it was being discarded: AOSP answers false when there is nothing to end, and the log
     * claimed "user declined" regardless. Whether the call actually stopped is worth knowing.
     */
    private fun decline() {
        val ended = runCatching {
            getSystemService(TelecomManager::class.java)?.endCall() ?: false
        }.onFailure { Log.w(TAG, "endCall failed", it) }.getOrDefault(false)
        acted = true
        IncomingCall.clear()
        if (ended) Log.i(TAG, "user declined")
        else Log.w(TAG, "user declined but endCall refused; the call may still be ringing")
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
