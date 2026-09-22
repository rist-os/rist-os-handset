package watch.rist.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The join screen, and the whole of the security model. A meeting
 * link can reach the assistant from a calendar entry or an email that a stranger wrote, and a
 * model decides to open it. So nothing a model decided may be the last step before a live
 * camera: the person's tap is. Until Join is tapped no address is loaded, no permission is
 * granted and no request of any kind is made, and Cancel leaves no trace.
 */
class VideoCallJoinActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "watch.rist.assistant.extra.CALL_URL"
        const val EXTRA_ORIGINAL_URL = "watch.rist.assistant.extra.CALL_ORIGINAL_URL"
        const val EXTRA_TITLE = "watch.rist.assistant.extra.CALL_TITLE"
        const val EXTRA_PROVIDER = "watch.rist.assistant.extra.CALL_PROVIDER"
        const val EXTRA_FROM_ASSISTANT = "watch.rist.assistant.extra.CALL_FROM_ASSISTANT"

        /** Nobody answering is a Cancel. */
        internal const val TIMEOUT_MS = 2L * 60 * 1000
    }

    private lateinit var theme: RistTheme
    private var tf: Typeface? = null
    private var d = 1f

    private var cameraOn = true
    private var micOn = true
    private val timeout = Handler(Looper.getMainLooper())
    private val cancelForSilence = Runnable { finish() }

    private val askForDevices =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // A refusal is the same as the toggle being off: the call opens without that device.
            if (granted[Manifest.permission.CAMERA] == false) cameraOn = false
            if (granted[Manifest.permission.RECORD_AUDIO] == false) micOn = false
            openCall()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Themes.byId(Config.themeId(this))
        tf = ThemePaint.typefaceOf(this, theme)
        d = resources.displayMetrics.density
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        show()
    }

    /** A second join while this screen is up replaces it, silently: the newest command wins. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show()
    }

    private fun show() {
        val provider = provider()
        if (provider == null || intent.getStringExtra(EXTRA_URL).isNullOrBlank()) { finish(); return }
        // Outside services have their own camera and microphone switches on their own pre-join
        // page; a native switch that denied the permission would contradict them. Always on.
        cameraOn = true
        micOn = true
        setContentView(build(provider, intent.getStringExtra(EXTRA_TITLE).orEmpty().trim()))
        timeout.removeCallbacks(cancelForSilence)
        timeout.postDelayed(cancelForSilence, TIMEOUT_MS)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        timeout.removeCallbacks(cancelForSilence)
        timeout.postDelayed(cancelForSilence, TIMEOUT_MS)
    }

    override fun onDestroy() {
        timeout.removeCallbacks(cancelForSilence)
        super.onDestroy()
    }

    private fun provider(): VideoCalls.Provider? =
        runCatching { VideoCalls.Provider.valueOf(intent.getStringExtra(EXTRA_PROVIDER).orEmpty()) }.getOrNull()

    private fun onJoin() {
        val wanted = buildList {
            if (cameraOn && !has(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (micOn && !has(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
        }
        if (wanted.isEmpty()) openCall() else askForDevices.launch(wanted.toTypedArray())
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openCall() {
        val provider = provider() ?: run { finish(); return }
        // Never two calls at once. The person was asked on this screen before they tapped.
        VideoCalls.end()
        runCatching { PlaybackService.pause(this) }
        startActivity(
            CallBrowserActivity.intent(
                this,
                url = intent.getStringExtra(EXTRA_URL).orEmpty(),
                originalUrl = intent.getStringExtra(EXTRA_ORIGINAL_URL).orEmpty(),
                provider = provider,
                camera = cameraOn,
                mic = micOn,
                fromAssistant = intent.getBooleanExtra(EXTRA_FROM_ASSISTANT, false),
            )
        )
        finish()
    }

    // --- the screen -------------------------------------------------------------------------

    private fun build(provider: VideoCalls.Provider, title: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.ground)
        }
        val pad = (28 * d).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }

        val replacing = VideoCalls.isOpen()
        // The service and the host come from what was checked; the title is whatever a calendar
        // entry or an email said, so it is plain text, under them, never in their place.
        root.addView(text(provider.label, 30f, theme.ink))
        val host = intent.getStringExtra(EXTRA_URL)?.let { android.net.Uri.parse(it).host }.orEmpty()
        if (host.isNotEmpty()) root.addView(text(host, 13f, Themes.readableMuted(theme)).apply {
            setPadding(0, (2 * d).toInt(), 0, 0)
        })
        if (title.isNotEmpty()) root.addView(text(title.take(VideoCalls.TITLE_MAX), 20f, theme.ink).apply {
            setPadding(0, (12 * d).toInt(), 0, 0)
        })
        root.addView(
            text(
                getString(if (replacing) R.string.call_join_replace else R.string.call_join_note),
                16f, if (replacing) theme.accent else Themes.readableMuted(theme),
            ).apply { setPadding(0, (14 * d).toInt(), 0, (22 * d).toInt()) }
        )

        if (provider == VideoCalls.Provider.RIST) {
            root.addView(toggle(R.string.call_camera, { cameraOn }) { cameraOn = !cameraOn })
            root.addView(toggle(R.string.call_microphone, { micOn }) { micOn = !micOn })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (26 * d).toInt(), 0, 0)
        }
        actions.addView(button(getString(R.string.call_cancel), filled = false, weight = 1f) { finish() })
        actions.addView(
            button(getString(if (replacing) R.string.call_leave_and_join else R.string.call_join), filled = true, weight = 2f) {
                onJoin()
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = (12 * d).toInt() }
        )
        root.addView(actions)
        return root
    }

    private fun text(value: String, sp: Float, color: Int) = TextView(this).apply {
        text = value
        setTextColor(color); typeface = tf
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun toggle(label: Int, isOn: () -> Boolean, flip: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * d).toInt()
            isClickable = true; isFocusable = true
        }
        val name = text(getString(label), 20f, theme.ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val state = text("", 16f, theme.ink).apply { letterSpacing = 0.08f }
        fun paint() {
            val on = isOn()
            state.text = getString(if (on) R.string.call_on else R.string.call_off)
            state.setTextColor(if (on) theme.accent else Themes.readableMuted(theme))
            row.contentDescription = getString(label) + ", " + state.text
        }
        row.addView(name); row.addView(state)
        row.setOnClickListener { flip(); paint(); Haptics.ack(this) }
        paint()
        return row
    }

    private fun button(label: String, filled: Boolean, weight: Float, onClick: () -> Unit) = TextView(this).apply {
        text = label
        isAllCaps = true
        letterSpacing = 0.08f
        typeface = tf
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        minHeight = (60 * d).toInt()
        setTextColor(if (filled) theme.ground else theme.ink)
        background = GradientDrawable().apply {
            if (filled) setColor(theme.accent) else setColor(android.graphics.Color.TRANSPARENT)
            setStroke((1.5f * d).toInt(), theme.accent)
            cornerRadius = 14f * d
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }
}
