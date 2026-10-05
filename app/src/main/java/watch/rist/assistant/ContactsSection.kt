package watch.rist.assistant

import android.app.Activity
import android.graphics.Typeface
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Settings > Contacts: the "Sync contacts with Rist" switch, when it last synced, and "Sync now". */
object ContactsSection {

    private const val TAG = "rist_contacts_section"

    /** The line under the switch. */
    internal fun status(a: Activity, nowMs: Long = System.currentTimeMillis()): String {
        val at = Config.contactsSyncedAt(a)
        val held = ContactIndex.size(a)
        return when {
            !Features.isOn(a, Features.Id.CONTACTS) || Config.contactsRefused(a) ->
                "Contacts are not on for this account. The address book on this phone stays as it is."
            Config.contactsSyncOff(a) ->
                "Off. The names already on this phone stay, so calls still show them."
            at <= 0L -> "Not synced yet."
            else -> "Last synced " + DateUtils.getRelativeTimeSpanString(at, nowMs, DateUtils.MINUTE_IN_MILLIS) +
                " · $held contact" + (if (held == 1) "" else "s")
        }
    }

    fun build(a: Activity, host: LinearLayout?, anchor: View?) {
        host ?: return
        host.findViewWithTag<View>(TAG)?.let { host.removeView(it) }
        val idx = (anchor?.let { host.indexOfChild(it) } ?: -1).coerceAtLeast(0)

        val t = Themes.current(a)
        val ink = t.ink
        val muted = Themes.readableMuted(t)
        val pixelTf: Typeface? =
            runCatching { androidx.core.content.res.ResourcesCompat.getFont(a, R.font.pixel) }.getOrNull()
        val bodyTf: Typeface? = ThemePaint.typefaceOf(a, t)
        fun px(v: Float): Int = (v * a.resources.displayMetrics.density).toInt()
        val on = !Config.contactsSyncOff(a)

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(12f), 0, px(6f))
            tag = TAG
        }
        box.addView(TextView(a).apply {
            text = "CONTACTS"
            setTextColor(ink); textSize = 14f; typeface = pixelTf; isAllCaps = true
            setPadding(0, px(6f), 0, px(4f))
        })
        box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = px(56f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(10f), 0, px(10f))
            isClickable = true; isFocusable = true
            contentDescription = "Sync contacts with Rist, " + if (on) "on" else "off"
            addView(TextView(a).apply {
                text = if (on) "◉" else "○"
                setTextColor(if (on) ink else muted)
                textSize = 17f; setPadding(0, 0, px(12f), 0)
            })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(a).apply {
                    text = "Sync contacts with Rist"; setTextColor(ink); textSize = 14f; typeface = bodyTf
                })
                addView(TextView(a).apply {
                    text = status(a); setTextColor(muted); textSize = 11.5f; typeface = bodyTf
                    setPadding(0, px(3f), 0, 0)
                })
            })
            setOnClickListener {
                ContactsSync.setEnabled(a, !on)
                SettingsApply.reportLocal(a, SettingsApply.KEY_CONTACTS_SYNC)
                build(a, host, anchor)
            }
        })
        if (on) {
            box.addView(TextView(a).apply {
                text = "Sync now"
                setTextColor(t.accent); textSize = 14f; typeface = bodyTf
                minimumHeight = px(48f)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                setOnClickListener {
                    ContactsSync.requestSync(a, full = true, reason = "sync now", manual = true)
                    Toast.makeText(a, "Syncing contacts", Toast.LENGTH_SHORT).show()
                    // The pull runs in the background; show its result once it has had time to land.
                    postDelayed({ if (!a.isFinishing && !a.isDestroyed) build(a, host, anchor) }, 5_000)
                }
            })
        }
        box.addView(TextView(a).apply {
            text = "The people Rist knows are copied to this phone, so calls and texts from them " +
                "show their names, even with no signal."
            setTextColor(muted); textSize = 10.5f; typeface = bodyTf
            setPadding(0, px(4f), 0, 0)
        })
        host.addView(box, idx)
    }
}
