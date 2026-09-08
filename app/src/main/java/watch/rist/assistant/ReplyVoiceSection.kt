package watch.rist.assistant

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

internal object ReplyVoiceSection {
    private const val TAG = "reply_voice_section"

    internal const val GLYPH_TAG = "reply_voice_glyph"

    fun build(a: Activity, host: LinearLayout?, anchor: View?) {
        host ?: return
        host.findViewWithTag<View>(TAG)?.let { host.removeView(it) }
        val idx = (anchor?.let { host.indexOfChild(it) } ?: -1).coerceAtLeast(0)

        val t = Themes.byId(Config.themeId(a))
        val ink = t.ink
        val muted = Themes.readableMuted(t)
        // Typeface must be set explicitly: this section is built after ThemePaint.retint() has run.
        val bodyTf: Typeface? = ThemePaint.typefaceOf(a, t)
        fun px(v: Float): Int = (v * a.resources.displayMetrics.density).toInt()

        val on = Config.isReplyVoiceEnabled(a)

        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = px(56f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(6f), 0, px(10f))
            isClickable = true; isFocusable = true
            tag = TAG
            addView(TextView(a).apply {
                text = if (on) "☑" else "☐"
                setTextColor(if (on) ink else muted)
                textSize = 17f; setPadding(0, 0, px(12f), 0)
                tag = GLYPH_TAG
            })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(a).apply {
                    text = a.getString(R.string.reply_voice_checkbox)
                    setTextColor(if (on) ink else muted); textSize = 14f
                    typeface = bodyTf
                })
                addView(TextView(a).apply {
                    text = "Rist speaks its answers. Off shows them as text only."
                    setTextColor(muted); textSize = 11.5f
                    typeface = bodyTf
                    setPadding(0, px(3f), 0, 0)
                })
            })
            setOnClickListener { set(a, host, anchor, !on) }
        }

        host.addView(row, idx)
    }

    private fun set(a: Activity, host: LinearLayout?, anchor: View?, enable: Boolean) {
        Config.setReplyVoiceEnabled(a, enable)
        android.widget.Toast.makeText(
            a,
            if (enable) "On — Rist will read replies aloud" else "Off — replies show as text",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        build(a, host, anchor)
    }
}
