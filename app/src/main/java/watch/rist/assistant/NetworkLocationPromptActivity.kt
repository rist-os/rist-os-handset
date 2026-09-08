package watch.rist.assistant

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class NetworkLocationPromptActivity : AppCompatActivity() {

    private var showMore = false

    private val rt by lazy { Themes.byId(Config.themeId(this)) }
    private val tf: Typeface? by lazy { ThemePaint.typefaceOf(this, rt) }
    private val pixelTf: Typeface? by lazy {
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.pixel) }.getOrNull()
    }
    private fun px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private lateinit var scroller: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scroller = ScrollView(this).apply {
            setBackgroundColor(rt.ground)
            isFillViewport = true
        }
        setContentView(scroller)
        ViewCompat.setOnApplyWindowInsetsListener(scroller) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        runCatching {
            window.statusBarColor = rt.ground
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .isAppearanceLightStatusBars = !rt.dark
        }

        showQuestion()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dismiss()
        })
    }

    private fun showQuestion() {
        val muted = Themes.readableMuted(rt)
        val col = column()

        col.addView(heading("NETWORK LOCATION"))

        col.addView(body(
            "Network location finds where you are better \u2014 such as indoors \u2014 and uses " +
                "less battery. The ID numbers of the wireless networks nearby are sent to an " +
                "Apple server through a Rist proxy, and only while something is asking where " +
                "you are.",
            rt.ink
        ))

        col.addView(button("TURN IT ON", primary = true) { choose(true) })
        col.addView(button("LEAVE IT OFF", primary = false) { choose(false) })
        col.addView(button("ASK ME LATER", primary = false) { dismiss() })

        col.addView(button(if (showMore) "LESS INFO" else "MORE INFO", primary = false) {
            showMore = !showMore
            showQuestion()
        })

        if (showMore) {
            col.addView(subheading("WHO SEES WHAT"))
            col.addView(body(
                "The proxy is run by Rist, so Apple never sees this phone's own address on the " +
                    "internet \u2014 Rist does, for the moment it passes the request along. Rist " +
                    "keeps no record of it and cannot read what was asked. Apple sees the list " +
                    "of nearby networks, and that list alone shows roughly where you are. " +
                    "Neither is told where you turned out to be: the phone works that out itself.",
                rt.ink
            ))
            col.addView(body(
                "Your Wi\u2011Fi password is never sent, and nothing you do online is sent.",
                muted
            ))
            col.addView(body(
                "You can change this at any time: tap the gear on the home screen, open " +
                    "SETTINGS, and look for NETWORK LOCATION.",
                muted
            ))
        }


        scroller.removeAllViews()
        scroller.addView(col)
    }

    private fun choose(enable: Boolean) {
        val outcome = NetworkLocationConsent.apply(this, enable)
        if (enable && outcome == NetworkLocationConsent.Outcome.REFUSED) {
            showRefused()
            return
        }
        toast(
            if (enable) "Turned on. This phone can now find itself indoors."
            else "Left off. Nothing about nearby Wi‑Fi leaves this phone."
        )
        finish()
    }

    private fun dismiss() {
        NetworkLocationConsent.deferred(this)
        finish()
    }

    private fun showRefused() {
        val col = column()
        col.addView(heading("RIST COULD NOT SWITCH IT ON"))
        col.addView(body(
            "This phone only lets its own Settings app change that. Nothing has changed — " +
                "finding where you are still works from satellites only.",
            rt.ink
        ))
        col.addView(body(
            // "Rist Apple proxy" must match R.string.network_location_enabled_grapheneos_apple_proxy
            // as overridden in aosp/rro/RistSettingsStrings/.
            "You can still turn it on yourself. Open Settings, then Location, then Location " +
                "services, then Network location, and choose the Rist Apple proxy option.",
            rt.ink
        ))
        col.addView(button("OPEN SETTINGS", primary = true) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { toast("Couldn't open Settings") }
            finish()
        })
        col.addView(button("NOT NOW", primary = false) { finish() })
        scroller.removeAllViews()
        scroller.addView(col)
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(22f), px(24f), px(22f), px(28f))
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t
        setTextColor(rt.ink); textSize = 20f; typeface = pixelTf; isAllCaps = true
        setPadding(0, 0, 0, px(10f))
    }

    private fun subheading(t: String) = TextView(this).apply {
        text = t
        setTextColor(rt.ink); textSize = 14f; typeface = pixelTf; isAllCaps = true
        setPadding(0, px(14f), 0, px(4f))
    }

    private fun body(t: String, colour: Int) = TextView(this).apply {
        text = t
        setTextColor(colour); textSize = 16f; typeface = tf
        setLineSpacing(px(4f).toFloat(), 1.15f)
        setPadding(0, px(8f), 0, px(8f))
    }

    private fun button(label: String, primary: Boolean, onTap: () -> Unit) = TextView(this).apply {
        text = label
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

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        fun askIfDue(a: android.app.Activity) {
            if (!NetworkLocationConsent.isDue(a)) return
            // Stamp before launch: the home screen's onResume fires again the instant this activity finishes.
            NetworkLocationConsent.markAsked(a)
            runCatching { a.startActivity(Intent(a, NetworkLocationPromptActivity::class.java)) }
                .onFailure { android.util.Log.w("RistNetLoc", "could not show the location prompt", it) }
        }
    }
}
