package watch.rist.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * "This phone was removed from your account." Shown once when the backend answers 403, which it
 * does only when the phone has been removed (revoked) from its account.
 *
 * Removal is not a reset. The token and everything on the phone are kept, pairing is open again,
 * and a new code from the account's Phones page brings the phone back. The phone's own functions
 * and the emergency button keep working throughout.
 */
class RemovedActivity : AppCompatActivity() {

    private val rt by lazy { Themes.byId(Config.themeId(this)) }
    private val tf: Typeface? by lazy { ThemePaint.typefaceOf(this, rt) }
    private val pixelTf: Typeface? by lazy {
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.pixel) }.getOrNull()
    }
    private fun px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.setRemovedNoticeShown(this, true)
        val scroller = ScrollView(this).apply {
            setBackgroundColor(rt.ground)
            isFillViewport = true
        }
        setContentView(scroller)
        ViewCompat.setOnApplyWindowInsetsListener(scroller) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        val muted = Themes.readableMuted(rt)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22f), px(24f), px(22f), px(28f))
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.removed_title)
            tag = TAG_TITLE
            setTextColor(rt.ink); textSize = 20f; typeface = pixelTf
            setPadding(0, 0, 0, px(10f))
        })
        col.addView(body(getString(R.string.removed_body), rt.ink))
        col.addView(TextView(this).apply {
            text = getString(R.string.removed_steps_heading)
            setTextColor(rt.ink); textSize = 14f; typeface = pixelTf; isAllCaps = true
            setPadding(0, px(14f), 0, px(4f))
        })
        for (step in listOf(R.string.removed_step_1, R.string.removed_step_2, R.string.removed_step_3)) {
            col.addView(body(getString(step), rt.ink))
        }
        col.addView(button(getString(R.string.removed_pair), primary = true, tag = TAG_PAIR) {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_HIDE_APPS, true)
                    .putExtra(SettingsActivity.EXTRA_SHOW_BACKEND, true)
            )
            finish()
        })
        col.addView(button(getString(R.string.emergency_button_desc), primary = false, tag = TAG_EMERGENCY) {
            EmergencyDial.open(this)
        })
        col.addView(button(getString(R.string.removed_later), primary = false, tag = TAG_LATER) { finish() })
        col.addView(body(getString(R.string.status_revoked), muted).apply { textSize = 13f })
        scroller.addView(col)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
    }

    private fun body(t: String, colour: Int) = TextView(this).apply {
        text = t
        setTextColor(colour); textSize = 16f; typeface = tf
        setLineSpacing(px(4f).toFloat(), 1.15f)
        setPadding(0, px(8f), 0, px(8f))
    }

    private fun button(label: String, primary: Boolean, tag: String, onTap: () -> Unit) = TextView(this).apply {
        text = label
        this.tag = tag
        isAllCaps = true; typeface = pixelTf; textSize = 15f
        gravity = Gravity.CENTER
        minHeight = px(64f)
        setPadding(px(16f), px(18f), px(16f), px(18f))
        setTextColor(if (primary) rt.ground else rt.ink)
        background = GradientDrawable().apply {
            setColor(if (primary) rt.accent else rt.tileFill)
            setStroke(px(2f), rt.accent)
            cornerRadius = px(16f).toFloat()
        }
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(12f) }
        setOnClickListener { onTap() }
    }

    companion object {
        internal const val TAG_TITLE = "removed-title"
        internal const val TAG_PAIR = "removed-pair"
        internal const val TAG_EMERGENCY = "removed-emergency"
        internal const val TAG_LATER = "removed-later"

        /** Due once per removal: the flag is cleared when the phone is paired or served again. */
        fun isDue(a: Activity): Boolean = Config.enrolRevoked(a) && !Config.removedNoticeShown(a)

        /** Opens the screen if it is due. Returns whether it did. */
        fun showIfDue(a: Activity): Boolean {
            if (!isDue(a)) return false
            return runCatching { a.startActivity(Intent(a, RemovedActivity::class.java)); true }
                .getOrDefault(false)
        }
    }
}
