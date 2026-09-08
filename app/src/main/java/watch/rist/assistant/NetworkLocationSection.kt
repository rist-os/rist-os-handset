package watch.rist.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object NetworkLocationSection {

    private const val TAG = "rist_netloc_section"

    fun build(a: Activity, host: LinearLayout?, anchor: View?) {
        host ?: return
        host.findViewWithTag<View>(TAG)?.let { host.removeView(it) }
        val idx = (anchor?.let { host.indexOfChild(it) } ?: -1).coerceAtLeast(0)

        val t = Themes.byId(Config.themeId(a))
        val ink = t.ink
        val muted = Themes.readableMuted(t)
        val pixelTf: Typeface? =
            runCatching { androidx.core.content.res.ResourcesCompat.getFont(a, R.font.pixel) }.getOrNull()
        // Typeface must be set explicitly: this section is built in onResume, after ThemePaint.retint().
        val bodyTf: Typeface? = ThemePaint.typefaceOf(a, t)
        fun px(v: Float): Int = (v * a.resources.displayMetrics.density).toInt()

        val status = NetworkLocationConsent.status(a)
        val on = status == NetworkLocationConsent.Status.ON

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(12f), 0, px(6f))
            tag = TAG
        }
        box.addView(TextView(a).apply {
            text = "NETWORK LOCATION"
            setTextColor(ink); textSize = 14f; typeface = pixelTf; isAllCaps = true
            setPadding(0, px(6f), 0, px(4f))
        })

        fun option(selected: Boolean, title: String, subtitle: String, onTap: () -> Unit) =
            LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = px(56f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(10f), 0, px(10f))
                isClickable = true; isFocusable = true
                addView(TextView(a).apply {
                    text = if (selected) "◉" else "○"
                    setTextColor(if (selected) ink else muted)
                    textSize = 17f; setPadding(0, 0, px(12f), 0)
                })
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(a).apply {
                        text = title; setTextColor(if (selected) ink else muted); textSize = 14f
                        typeface = bodyTf
                    })
                    addView(TextView(a).apply {
                        text = subtitle; setTextColor(muted); textSize = 11.5f
                        typeface = bodyTf
                        setPadding(0, px(3f), 0, 0)
                    })
                })
                setOnClickListener { onTap() }
            }

        box.addView(option(
            on,
            "Use nearby Wi‑Fi to find me",
            "Fast, and works indoors. The ID numbers of nearby Wi‑Fi networks are sent away " +
                "to be looked up, so whoever looks them up can tell roughly where you are."
        ) { set(a, host, anchor, true) })

        box.addView(option(
            !on,
            "Satellites only",
            "Nothing about nearby Wi‑Fi leaves this phone. Indoors it can take about half a " +
                "minute and be off by a couple of hundred feet."
        ) { set(a, host, anchor, false) })

        if (status == NetworkLocationConsent.Status.REFUSED) {
            box.addView(TextView(a).apply {
                text = "You chose to turn this on and Rist could not — this phone only lets " +
                    "its own Settings app change it. Tap here to open Settings, then Location, " +
                    "then Location services, then Network location."
                setTextColor(ink); textSize = 11.5f; typeface = bodyTf
                setPadding(0, px(8f), 0, px(8f))
                minimumHeight = px(48f)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    runCatching {
                        a.startActivity(
                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure {
                        android.widget.Toast.makeText(
                            a, "Couldn't open Settings", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
        }

        box.addView(TextView(a).apply {
            text = "This is about the phone knowing where it is — for maps, directions and " +
                "when you ask Rist. It does not change anything about phone calls."
            setTextColor(muted); textSize = 10.5f; typeface = bodyTf
            setPadding(0, px(8f), 0, 0)
        })

        host.addView(box, idx)
    }

    private fun set(a: Activity, host: LinearLayout?, anchor: View?, enable: Boolean) {
        val outcome = NetworkLocationConsent.apply(a, enable)
        val msg = when {
            outcome == NetworkLocationConsent.Outcome.REFUSED && enable ->
                "Rist couldn't change this — see below"
            outcome == NetworkLocationConsent.Outcome.REFUSED ->
                "Rist couldn't change this"
            enable -> "On — this phone can now find itself indoors"
            else -> "Off — nothing about nearby Wi‑Fi leaves this phone"
        }
        android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_LONG).show()
        build(a, host, anchor)
    }
}
