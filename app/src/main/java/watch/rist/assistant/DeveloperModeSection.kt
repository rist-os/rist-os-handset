package watch.rist.assistant

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Settings > Developer mode. Shown only on a public image whose account the backend allows
 * ([DeveloperMode.rowVisible]); every other phone gets no row at all.
 *
 * [confirmCredential] asks for the screen lock and runs its argument only when it was entered.
 */
object DeveloperModeSection {

    private const val TAG = "rist_developer_mode_section"

    fun build(a: Activity, host: LinearLayout?, anchor: View?, confirmCredential: (onConfirmed: () -> Unit) -> Unit) {
        host ?: return
        host.findViewWithTag<View>(TAG)?.let { host.removeView(it) }
        DeveloperMode.enforce(a)
        if (!DeveloperMode.rowVisible(a)) return
        val idx = (anchor?.let { host.indexOfChild(it) } ?: -1).coerceAtLeast(0)

        val t = Themes.current(a)
        val muted = Themes.readableMuted(t)
        val pixelTf: Typeface? =
            runCatching { androidx.core.content.res.ResourcesCompat.getFont(a, R.font.pixel) }.getOrNull()
        val bodyTf: Typeface? = ThemePaint.typefaceOf(a, t)
        fun px(v: Float): Int = (v * a.resources.displayMetrics.density).toInt()

        val on = DeveloperMode.isOn(a)
        val secure = DeveloperMode.isDeviceSecure(a)

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(12f), 0, px(6f))
            tag = TAG
        }
        box.addView(TextView(a).apply {
            text = "DEVELOPER MODE"
            setTextColor(t.ink); textSize = 14f; typeface = pixelTf; isAllCaps = true
            setPadding(0, px(6f), 0, px(4f))
        })
        box.addView(TextView(a).apply {
            text = stateText(on = on, secure = secure)
            setTextColor(muted); textSize = 11.5f; typeface = bodyTf
            setPadding(0, 0, 0, px(8f))
        })

        val enabled = on || secure
        box.addView(TextView(a).apply {
            text = if (on) "Turn off Developer mode" else "Turn on Developer mode"
            contentDescription = text
            setTextColor(if (enabled) t.accent else muted); textSize = 15f; typeface = bodyTf
            minimumHeight = px(48f)
            setPadding(0, px(10f), 0, px(10f))
            isClickable = enabled; isFocusable = enabled
            if (enabled) setOnClickListener {
                if (on) {
                    DeveloperMode.turnOff(a, "turned off in Settings")
                    toast(a, "Developer mode is off. USB debugging is blocked.")
                    build(a, host, anchor, confirmCredential)
                } else {
                    if (!DeveloperMode.mayTurnOn(a)) {
                        toast(a, "Developer mode can't be turned on right now.")
                        build(a, host, anchor, confirmCredential)
                        return@setOnClickListener
                    }
                    confirmCredential {
                        val ok = DeveloperMode.turnOn(a)
                        toast(a, if (ok) "Developer mode is on. USB debugging is allowed."
                            else "Developer mode can't be turned on right now.")
                        build(a, host, anchor, confirmCredential)
                    }
                }
            }
        })
        box.addView(TextView(a).apply {
            text = "Before installing a system update, remove any copy of Rist you installed " +
                "from a computer. The update can't be installed while it is there."
            setTextColor(muted); textSize = 10.5f; typeface = bodyTf
            setPadding(0, px(8f), 0, 0)
        })

        host.addView(box, idx)
    }

    internal fun stateText(on: Boolean, secure: Boolean): String = when {
        on -> "On. A computer you have allowed can install and debug apps on this phone over USB. " +
            "Turn it off when you are done."
        !secure -> "Off. Set a screen lock first: Developer mode needs one, and turns itself off " +
            "if the screen lock is removed."
        else -> "Off. USB debugging is blocked. Turning it on asks for your screen lock."
    }

    private fun toast(a: Activity, msg: String) =
        runCatching { Toast.makeText(a, msg, Toast.LENGTH_LONG).show() }
}
