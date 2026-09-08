package watch.rist.assistant

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object GeofenceConsent {

    private const val TAG = "RistGeofence"

    private const val PREFS = "rist.geofence.consent"

    private const val KEY_CHOICE = "background_location_choice"
    private const val KEY_ASKS = "background_location_asks"
    private const val KEY_ASKED_AT = "background_location_asked_at"
    private const val KEY_WANTED = "background_location_wanted"

    enum class Choice { UNANSWERED, ON, OFF;
        companion object {
            fun parse(s: String?): Choice = when (s) {
                "on" -> ON
                "off" -> OFF
                else -> UNANSWERED
            }
        }
        val stored: String get() = when (this) { ON -> "on"; OFF -> "off"; UNANSWERED -> "" }
    }

    enum class Status {
        UNANSWERED,
        ON,
        OFF,
        REFUSED,
    }

    enum class Outcome { APPLIED, REFUSED }

    const val RE_ASK_GAP_MS = 24L * 60 * 60 * 1000

    const val MAX_ASKS = 3

    fun status(choice: Choice, osGranted: Boolean): Status = when (choice) {
        Choice.ON -> if (osGranted) Status.ON else Status.REFUSED
        Choice.OFF -> Status.OFF
        Choice.UNANSWERED -> Status.UNANSWERED
    }

    fun evaluationAllowed(status: Status): Boolean = status == Status.ON

    fun refusalReason(status: Status): String = when (status) {
        Status.UNANSWERED ->
            "the user has not yet been asked whether this phone may check where it is in the background"
        Status.OFF ->
            "the user declined background location, so a place trigger cannot be evaluated"
        Status.REFUSED ->
            "the user allowed background location but ACCESS_BACKGROUND_LOCATION is not granted; " +
                "the Device Owner grant did not take"
        Status.ON -> ""
    }

    fun shouldAsk(
        choice: Choice,
        asks: Int,
        lastAskedAt: Long,
        now: Long,
        fenceWanted: Boolean,
    ): Boolean {
        if (!fenceWanted) return false
        if (choice != Choice.UNANSWERED) return false
        if (asks >= MAX_ASKS) return false
        if (lastAskedAt <= 0L) return true
        if (now < lastAskedAt) return true
        return now - lastAskedAt >= RE_ASK_GAP_MS
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun choice(ctx: Context): Choice = Choice.parse(prefs(ctx).getString(KEY_CHOICE, ""))

    fun asks(ctx: Context): Int = prefs(ctx).getInt(KEY_ASKS, 0)

    fun askedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_ASKED_AT, 0L)

    fun fenceWanted(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_WANTED, false)

    fun noteFenceRequested(ctx: Context) {
        if (fenceWanted(ctx)) return
        prefs(ctx).edit().putBoolean(KEY_WANTED, true).apply()
    }

    fun deferred(ctx: Context) {
        prefs(ctx).edit().putInt(KEY_ASKS, asks(ctx) + 1).apply()
    }

    fun markAsked(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_ASKED_AT, System.currentTimeMillis()).apply()
    }

    fun isDue(ctx: Context): Boolean = shouldAsk(
        choice = choice(ctx),
        asks = asks(ctx),
        lastAskedAt = askedAt(ctx),
        now = System.currentTimeMillis(),
        fenceWanted = fenceWanted(ctx),
    )

    fun state(ctx: Context): Status = status(choice(ctx), LocationProvider.hasBackgroundPermission(ctx))

    fun apply(ctx: Context, enable: Boolean): Outcome {
        prefs(ctx).edit()
            .putString(KEY_CHOICE, (if (enable) Choice.ON else Choice.OFF).stored)
            .apply()

        val wrote = setGrant(ctx, enable)
        val held = LocationProvider.hasBackgroundPermission(ctx)
        val outcome = if (held == enable) Outcome.APPLIED else Outcome.REFUSED

        if (outcome == Outcome.REFUSED) {
            Log.w(
                TAG,
                "REFUSED: wanted ACCESS_BACKGROUND_LOCATION granted=$enable, holds=$held " +
                    "(setPermissionGrantState ${if (wrote) "returned true" else "returned false or threw"}, " +
                    "deviceOwner=${KioskManager.isDeviceOwner(ctx)}). Since Android 11 the platform " +
                    "refuses this grant unless a foreground location permission is already held, so " +
                    "check that ACCESS_FINE_LOCATION landed first."
            )
        } else {
            Log.i(TAG, "background location grant is now $held (answer=${enable})")
        }
        return outcome
    }

    fun reassertGrant(ctx: Context) {
        if (choice(ctx) != Choice.ON) return
        if (LocationProvider.hasBackgroundPermission(ctx)) return
        val ok = setGrant(ctx, true)
        Log.i(TAG, "re-asserted the background location grant from the stored answer (recorded=$ok)")
    }

    // A true return means the policy was recorded, not that the permission is now held.
    private fun setGrant(ctx: Context, granted: Boolean): Boolean {
        if (!KioskManager.isDeviceOwner(ctx)) return false
        return runCatching {
            KioskManager.dpm(ctx).setPermissionGrantState(
                KioskManager.admin(ctx),
                ctx.packageName,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                if (granted) DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                else DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            )
        }.onFailure { Log.w(TAG, "setPermissionGrantState for background location threw", it) }
            .getOrDefault(false)
    }
}

class BackgroundLocationPromptActivity : AppCompatActivity() {

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

        col.addView(heading("WATCHING FOR A PLACE"))
        col.addView(body("One question, asked once. Nothing is watched until you choose.", muted))

        col.addView(body(
            "You have asked Rist to do something when you get somewhere — arrive home, leave " +
                "work. To notice that, this phone has to keep checking where it is even when you " +
                "are not using it, including while it is in your pocket and the screen is off.",
            rt.ink
        ))

        col.addView(subheading("WHAT IT DOES"))
        col.addView(body(
            "Every couple of minutes the phone quietly checks its own position and asks itself " +
                "one question: am I there yet? It checks less often when you have not moved, and " +
                "much less often overnight on the charger. It costs well under one percent of the " +
                "battery in a day.",
            rt.ink
        ))

        col.addView(subheading("WHAT LEAVES THE PHONE"))
        col.addView(body(
            "Nothing, until you arrive. The checking happens on the phone. When you reach the " +
                "place, Rist is told that you got there and when — and that is what lets it send " +
                "the message you asked for. Rist is not sent a trail of where you have been.",
            rt.ink
        ))
        col.addView(body(
            "Say no and everything else keeps working exactly as it does now. The only thing you " +
                "lose is asking Rist to do something when you get somewhere.",
            muted
        ))

        col.addView(button("ALLOW IT", primary = true) { choose(true) })
        col.addView(button("NO, DON'T WATCH", primary = false) { choose(false) })
        col.addView(button("ASK ME LATER", primary = false) { dismiss() })

        scroller.removeAllViews()
        scroller.addView(col)
    }

    private fun choose(enable: Boolean) {
        val outcome = GeofenceConsent.apply(this, enable)
        if (enable && outcome == GeofenceConsent.Outcome.REFUSED) {
            showRefused()
            return
        }
        toast(
            if (enable) "Allowed. Rist can now notice when you get somewhere."
            else "Left off. This phone will not watch for places."
        )
        finish()
    }

    private fun dismiss() {
        GeofenceConsent.deferred(this)
        finish()
    }

    private fun showRefused() {
        val col = column()
        col.addView(heading("RIST COULD NOT SWITCH IT ON"))
        col.addView(body(
            "Your answer has been saved, but this phone did not give Rist permission to keep " +
                "checking where it is. Nothing has changed, and watching for a place will not work " +
                "until this is fixed.",
            rt.ink
        ))
        col.addView(body(
            "This is a fault in Rist, not something you have done wrong. Rist will try again by " +
                "itself the next time the phone starts.",
            Themes.readableMuted(rt)
        ))
        col.addView(button("OK", primary = true) { finish() })
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
            if (!GeofenceConsent.isDue(a)) return
            // Must be stamped before startActivity: the caller's onResume refires when this finishes.
            GeofenceConsent.markAsked(a)
            runCatching { a.startActivity(Intent(a, BackgroundLocationPromptActivity::class.java)) }
                .onFailure { Log.w("RistGeofence", "could not show the background location prompt", it) }
        }
    }
}
