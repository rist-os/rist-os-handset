package watch.rist.assistant

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.ContextCompat
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

// Voicemail, network-location and OTA sections are built in onResume and insert at anchors, so rendered order follows the anchors, not the XML.
class SettingsActivity : AppCompatActivity() {

    private lateinit var deviceInfo: TextView

    private val pixelTf: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.pixel) }

    private var pendingBackendScroll = false

    private var backendDialog: android.app.AlertDialog? = null

    private fun px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun buttonFace(t: RistTheme, primary: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(if (primary) t.tileFill else android.graphics.Color.TRANSPARENT)
            setStroke(px(1.5f), if (primary) t.accent else Themes.readableMuted(t))
            cornerRadius = px(14f).toFloat()
        }

    private fun asButton(v: TextView, t: RistTheme, primary: Boolean) {
        v.background = buttonFace(t, primary)
        v.setTextColor(if (primary) t.ink else Themes.readableMuted(t))
        v.minHeight = px(48f)
        v.gravity = Gravity.CENTER
        val h = px(16f)
        val vpad = px(10f)
        v.setPadding(h, vpad, h, vpad)
        v.isClickable = true
        v.isFocusable = true
        androidx.core.view.ViewCompat.setAccessibilityDelegate(
            v,
            object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.roleDescription = "button"
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsScroll)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // max(ime, nav): the ime inset already includes the nav bar while the keyboard is up.
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = maxOf(ime.bottom, bars.bottom))
            insets
        }

        findViewById<View>(R.id.appPhone).setOnClickListener { AppLauncher.launchDialer(this); finish() }
        findViewById<View>(R.id.appMsg).setOnClickListener { AppLauncher.launchMessaging(this); finish() }
        findViewById<View>(R.id.appCameraApp).setOnClickListener { AppLauncher.launchCamera(this); finish() }
        findViewById<View>(R.id.appGallery).setOnClickListener { AppLauncher.launchGallery(this); finish() }
        findViewById<View>(R.id.appMaps).setOnClickListener { AppLauncher.launchMaps(this); finish() }
        findViewById<View>(R.id.appSettings).setOnClickListener { AppLauncher.launchSettings(this); finish() }

        if (intent?.getBooleanExtra(EXTRA_HIDE_APPS, false) == true) {
            findViewById<View>(R.id.appsHeading)?.visibility = View.GONE
            findViewById<View>(R.id.appsRow)?.visibility = View.GONE
        }

        deviceInfo = findViewById(R.id.deviceInfo)

        findViewById<TextView>(R.id.settingsBack).setOnClickListener { navigateHome() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateHome()
        })



        if (Config.isEndpointEditable(this)) {
            val backendInput = findViewById<android.widget.EditText>(R.id.backendUrlInput)
            backendInput.setText(Config.backendUrl(this))
            val bt = Themes.byId(Config.themeId(this))
            asButton(findViewById(R.id.backendSave), bt, primary = true)
            asButton(findViewById(R.id.backendReset), bt, primary = false)

            val save = findViewById<TextView>(R.id.backendSave)
            fun syncSaveEnabled() {
                val typed = backendInput.text.toString().trim()
                val stored = Config.backendUrl(this).trim()
                val changed = typed != stored
                save.isEnabled = changed
                save.alpha = if (changed) 1f else 0.4f
            }
            syncSaveEnabled()
            backendInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) = syncSaveEnabled()
                override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            })

            findViewById<TextView>(R.id.backendSave).setOnClickListener {
                // performClick() ignores isEnabled; the IME and accessibility route through it.
                if (!save.isEnabled) return@setOnClickListener
                val url = backendInput.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    android.widget.Toast.makeText(this, "Enter a valid http(s):// URL", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                confirmBackendChange(getString(R.string.backend_change_confirm)) {
                    Config.setBackendEndpoint(this, url)
                    afterBackendMoved("Endpoint saved")
                    syncSaveEnabled()
                }
            }
            findViewById<TextView>(R.id.backendReset).setOnClickListener {
                confirmBackendChange(getString(R.string.backend_change_reset_confirm)) {
                    Config.clearBackendOverride(this)
                    backendInput.setText(Config.defaultBackendUrl(this))
                    afterBackendMoved("Reset to default")
                    syncSaveEnabled()
                }
            }
        } else {
            findViewById<View>(R.id.backendSection).visibility = View.GONE
        }

        deviceInfo.text = getString(R.string.device_info_fmt, Config.deviceId(this),
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?")

        findViewById<TextView>(R.id.openPhoneSettings)?.apply {
            val t = Themes.byId(Config.themeId(this@SettingsActivity))
            asButton(this, t, primary = true)
            setOnClickListener { AppLauncher.launchSettings(this@SettingsActivity); finish() }
        }

        refreshAppBadges()
        wireBrightness()

        runCatching { buildPairSection() }
            .onFailure { android.util.Log.e("RistSettings", "pair section build failed", it) }
        if (intent?.getBooleanExtra(EXTRA_SHOW_BACKEND, false) == true) {
            pendingBackendScroll = true
        }

        applySettingsTheme()

        val picker = findViewById<LinearLayout>(R.id.themePicker)
        runCatching { buildThemePicker(picker, Config.themeId(this)) }
            .onFailure { android.util.Log.e("RistSettings", "theme picker build failed", it) }
    }

    private fun buildThemePicker(root: LinearLayout, currentId: String) {
        root.removeAllViews()
        for (cat in Themes.CATEGORIES) {
            val open = false
            val host = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (open) View.VISIBLE else View.GONE
            }
            if (open) host.addView(buildMiniGrid(cat.themes, currentId))
            root.addView(
                makeExpandable(cat.title, cat.subtitle, cat.themes.size, open, host, titleSp = 14f) {
                    if (host.childCount == 0) host.addView(buildMiniGrid(cat.themes, currentId))
                }
            )
            root.addView(host)
        }
    }

    private fun makeExpandable(
        title: String, sub: String, count: Int, open: Boolean, target: View, titleSp: Float,
        onExpand: () -> Unit = {},
    ): View {
        target.visibility = if (open) View.VISIBLE else View.GONE
        val chev = TextView(this).apply {
            text = if (open) "▾" else "▸"
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.ink_muted))
            textSize = 13f; setPadding(0, 0, px(10f), 0)
        }
        val label = TextView(this).apply {
            text = title.uppercase()
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.ink))
            textSize = titleSp; typeface = pixelTf; isAllCaps = true
        }
        label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(12f), 0, px(12f))
            isClickable = true; isFocusable = true
            addView(chev); addView(label)
            setOnClickListener {
                val nowOpen = target.visibility != View.VISIBLE
                if (nowOpen) onExpand()
                target.visibility = if (nowOpen) View.VISIBLE else View.GONE
                chev.text = if (nowOpen) "▾" else "▸"
            }
        }
    }


    internal val MODE_SECTION_TAG = "rist_mode_section"
    private val VOICEMAIL_SECTION_TAG = "rist_voicemail_section"

    private fun buildVoicemailSection() {
        val picker = findViewById<LinearLayout>(R.id.themePicker) ?: return
        val parent = picker.parent as? LinearLayout ?: return

        parent.findViewWithTag<View>(VOICEMAIL_SECTION_TAG)?.let { parent.removeView(it) }
        val anchor = findViewById<View>(R.id.themesHeading) ?: picker
        val idx = parent.indexOfChild(anchor).coerceAtLeast(0)

        val vmTheme = Themes.byId(Config.themeId(this))
        val ink = ContextCompat.getColor(this, R.color.ink)
        val muted = Themes.readableMuted(vmTheme)
        // Built in onResume, outside ThemePaint.retint(), so the body typeface must be set explicitly.
        val bodyTf: Typeface? = ThemePaint.typefaceOf(this, vmTheme)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(12f), 0, px(6f))
            tag = VOICEMAIL_SECTION_TAG
        }
        box.addView(TextView(this).apply {
            text = "VOICEMAIL"
            setTextColor(ink); textSize = 14f; typeface = pixelTf; isAllCaps = true
            setPadding(0, px(6f), 0, px(4f))
        })

        val hasNumber = CarrierVoicemail.mailboxNumber(this).isNotBlank()
        box.addView(TextView(this).apply {
            text = "Rist will call your mailbox. Your carrier will ask for your PIN on the call."
            setTextColor(muted); textSize = 10.5f; typeface = bodyTf
            setPadding(0, 0, 0, px(8f))
        })

        Config.setVoicemailPin(this, "")

        box.addView(TextView(this).apply {
            text = "Listen now"
            isAllCaps = true
            textSize = 12f; typeface = pixelTf
            asButton(this, Themes.byId(Config.themeId(this@SettingsActivity)), primary = true)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = px(8f)
            layoutParams = lp
            setOnClickListener {
                if (!CarrierVoicemail.call(this@SettingsActivity)) {
                    android.widget.Toast.makeText(
                        this@SettingsActivity, "Couldn't reach your mailbox",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else finish()
            }
        })

        parent.addView(box, idx)
    }

    private fun confirmBackendChange(confirmLabel: String, onConfirm: () -> Unit) {
        if (Enrolment.needed(this)) { onConfirm(); return }
        if (backendDialog?.isShowing == true) return
        backendDialog = raiseBackendChangeDialog(confirmLabel, onConfirm)
    }

    internal fun raiseBackendChangeDialog(
        confirmLabel: String,
        onConfirm: () -> Unit,
    ): android.app.AlertDialog {
        val t = Themes.byId(Config.themeId(this))
        return RistDialog.ask(
            this,
            t,
            ThemePaint.typefaceOf(this, t),
            resources.displayMetrics.density,
            title = getString(R.string.backend_change_title),
            message = getString(R.string.backend_change_body),
            positive = confirmLabel,
            onPositive = onConfirm,
            negative = getString(R.string.backend_change_cancel),
        )
    }

    private fun afterBackendMoved(saidWhat: String) {
        runCatching { buildPairSection() }.onFailure {
            android.util.Log.e("RistSettings", "pair section rebuild after an endpoint change failed", it)
        }
        android.widget.Toast.makeText(this, saidWhat, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun buildPairSection() {
        val status = findViewById<TextView>(R.id.pairStatus) ?: return
        val input = findViewById<android.widget.EditText>(R.id.pairCodeInput) ?: return
        val submit = findViewById<TextView>(R.id.pairSubmit) ?: return

        asButton(submit, Themes.byId(Config.themeId(this)), primary = true)

        input.setText("")

        val label = findViewById<TextView>(R.id.pairLabel)
        fun offerPairing(on: Boolean) {
            val vis = if (on) View.VISIBLE else View.GONE
            label?.visibility = vis
            input.visibility = vis
            submit.visibility = vis
        }

        if (Config.enrolRevoked(this)) {
            status.text = getString(R.string.pair_revoked)
            offerPairing(false)
            return
        }

        if (!Enrolment.needed(this)) {
            status.text = getString(R.string.pair_connected)
            offerPairing(false)
            return
        }

        status.text = getString(R.string.pair_prompt)
        offerPairing(true)

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                // performClick() ignores isEnabled, so the guard is checked here.
                if (submit.isEnabled && input.visibility == View.VISIBLE) submit.performClick()
                true
            } else false
        }

        submit.setOnClickListener {
            val code = input.text.toString().trim()
            if (code.isEmpty()) {
                status.text = getString(R.string.pair_prompt)
                return@setOnClickListener
            }
            submit.isEnabled = false
            status.text = getString(R.string.pair_working)
            Thread {
                val result = Enrolment.pair(applicationContext, code)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    submit.isEnabled = true
                    if (result == Enrolment.PairResult.OK) {
                        runCatching { buildPairSection() }
                        status.text = Enrolment.explainPair(result)
                    } else {
                        status.text = Enrolment.explainPair(result)
                        input.setText("")
                    }
                }
            }.start()
        }
    }

    private fun buildMiniGrid(themes: List<RistTheme>, currentId: String): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, px(2f), 0, px(12f))
        }
        var row: LinearLayout? = null
        themes.forEachIndexed { i, t ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row)
            }
            row!!.addView(buildMini(t, t.id == currentId).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { val m = px(5f); setMargins(m, m, m, m) }
            })
        }
        if (themes.size % 2 == 1) row?.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        return col
    }

    private fun buildMini(t: RistTheme, selected: Boolean): View {
        val tf: Typeface? = when (t.font) {
            "pixel" -> pixelTf
            "mono"  -> Typeface.MONOSPACE
            "serif" -> Typeface.SERIF
            else    -> Typeface.SANS_SERIF
        }
        val faint = androidx.core.graphics.ColorUtils.blendARGB(t.ink, t.ground, 0.5f)

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(t.ground); cornerRadius = px(15f).toFloat()
                if (selected) setStroke(px(2f), t.accent)
            }
            setPadding(px(9f), px(9f), px(9f), px(10f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(196f))
        }

        val header = TextView(this).apply {
            text = "Status: Idle"; isAllCaps = true; letterSpacing = 0.08f
            setTextColor(t.inkMuted); textSize = 8.5f; typeface = tf
        }

        val tileInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(ImageView(this@SettingsActivity).apply {
                background = Themes.knob(t, resources.displayMetrics.density)
                layoutParams = LinearLayout.LayoutParams(px(42f), px(42f))
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "Hold"; isAllCaps = true; letterSpacing = 0.12f; setTextColor(t.inkMuted); textSize = 7f; typeface = tf
                setPadding(0, px(6f), 0, 0)
            })
        }
        val tile = FrameLayout(this).apply {
            background = Themes.tileFace(t, resources.displayMetrics.density)
            addView(tileInner, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = px(7f); bottomMargin = px(7f) }
        }

        val fieldBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(px(1.4f).coerceAtLeast(1), t.fieldBorder)
                cornerRadius = px(t.fieldRadiusDp).toFloat()
            }
            setPadding(px(9f), px(6f), px(9f), px(6f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = px(6f); rightMargin = px(6f) }
            addView(TextView(this@SettingsActivity).apply {
                text = "Type a message"; setTextColor(faint); textSize = 7.5f; typeface = tf
            })
        }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@SettingsActivity).apply {
                setImageResource(if (t.lineIcons) R.drawable.ic_flashlight_line else R.drawable.ic_flashlight)
                setColorFilter(t.ink)
                layoutParams = LinearLayout.LayoutParams(px(15f), px(17f))
            })
            addView(fieldBox)
            addView(ImageView(this@SettingsActivity).apply {
                setImageResource(if (t.lineIcons) R.drawable.ic_send_line else R.drawable.ic_send)
                setColorFilter(t.accent)
                layoutParams = LinearLayout.LayoutParams(px(17f), px(17f))
            })
        }

        screen.addView(header); screen.addView(tile); screen.addView(dock)

        val name = TextView(this).apply {
            text = (if (selected) "\u25b8 " else "") + t.name
            setTextColor(ContextCompat.getColor(this@SettingsActivity, if (selected) R.color.ink else R.color.ink_muted))
            textSize = 12f; typeface = pixelTf; setPadding(px(2f), px(7f), 0, px(2f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true; isFocusable = true
            addView(screen); addView(name)
            setOnClickListener { Config.setThemeId(this@SettingsActivity, t.id); navigateHome() }
        }
    }

    // Position 0 = AUTO, stored as -1f (BRIGHTNESS_OVERRIDE_NONE).
    private fun wireBrightness() = runCatching {
        val bar = findViewById<android.widget.SeekBar>(R.id.brightnessBar) ?: return@runCatching
        val stored = Config.brightness(this)
        bar.progress = if (stored < 0f) 0 else (stored * 100f).toInt().coerceIn(MIN_PCT, 100)
        renderBrightnessLabel(stored)
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = if (p == 0) -1f else (p.coerceAtLeast(MIN_PCT)) / 100f
                applyBrightness(v)
                Config.setBrightness(this@SettingsActivity, v)
                renderBrightnessLabel(v)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        applyBrightness(stored)
    }.onFailure { Log.w("RistSettings", "brightness wiring failed", it) }.let { }

    private fun renderBrightnessLabel(v: Float) = runCatching {
        findViewById<TextView>(R.id.brightnessLabel)?.text =
            if (v < 0f) "BRIGHTNESS - AUTO" else "BRIGHTNESS - ${(v * 100f).toInt()}%"
    }.let { }

    private fun applyBrightness(v: Float) = runCatching {
        window.attributes = window.attributes.apply {
            screenBrightness = if (v < 0f) -1f else v.coerceIn(0.05f, 1f)
        }
    }.let { }

    private fun refreshAppBadges() = runCatching {
        val w = runCatching { CommsFeedView.waiting(this) }.getOrNull()
        NotificationHub.applyBadge(
            findViewById(R.id.badgePhone),
            maxOf(NotificationHub.phone(this), w?.calls ?: 0),
        )
        NotificationHub.applyBadge(
            findViewById(R.id.badgeMsg),
            maxOf(NotificationHub.messages(), w?.sms ?: 0),
        )
        NotificationHub.applyBadge(findViewById(R.id.badgeCam),   NotificationHub.camera())
        NotificationHub.applyBadge(findViewById(R.id.badgePics),  NotificationHub.gallery())
        NotificationHub.applyBadge(findViewById(R.id.badgeMaps),  NotificationHub.maps())
        NotificationHub.applyBadge(findViewById(R.id.badgeSet),   NotificationHub.settings())
    }

    override fun onResume() {
        super.onResume()
        refreshAppBadges()
        runCatching { buildPairSection() }
            .onFailure { Log.e("RistSettings", "pair section rebuild failed", it) }
        // Built first: each code-built section inserts at the anchor, so the last built sits nearest it.
        runCatching { buildVoicemailSection() }
            .onFailure { Log.e("RistSettings", "voicemail section build failed", it) }
        // ThemePaint.retint() overwrites XML typefaces, so these headings are put back here.
        runCatching {
            for (id in intArrayOf(R.id.backendHeading, R.id.themesHeading)) {
                findViewById<TextView>(id)?.apply {
                    typeface = pixelTf
                    isAllCaps = true
                }
            }
        }.onFailure { Log.w("RistSettings", "section heading typeface failed", it) }
        runCatching {
            ReplyVoiceSection.build(
                this,
                findViewById<View>(R.id.appsHeading)?.parent as? LinearLayout,
                findViewById(R.id.appsHeading),
            )
        }.onFailure { Log.e("RistSettings", "reply voice section build failed", it) }
        runCatching {
            NetworkLocationSection.build(
                this,
                findViewById<LinearLayout>(R.id.themePicker)?.parent as? LinearLayout,
                // sectionsAnchor puts this below the theme picker.
                findViewById(R.id.sectionsAnchor),
            )
        }.onFailure { Log.e("RistSettings", "location section build failed", it) }
        runCatching { OtaSection.checkOnOpen(this) }
            .onFailure { Log.e("RistSettings", "ota check-on-open failed", it) }

        runCatching {
            OtaSection.build(
                this,
                findViewById<LinearLayout>(R.id.themePicker)?.parent as? LinearLayout,
                findViewById(R.id.sectionsAnchor),
            )
        }.onFailure { Log.e("RistSettings", "ota section build failed", it) }

        // Must run after the sections above are built; earlier scrolls are overwritten by layout.
        if (pendingBackendScroll) {
            pendingBackendScroll = false
            val scroller = findViewById<View>(R.id.settingsScroll)
            scroller?.post {
                if (isFinishing || isDestroyed) return@post
                runCatching {
                    findViewById<View>(R.id.backendGroup)?.requestRectangleOnScreen(
                        android.graphics.Rect(
                            0, 0,
                            findViewById<View>(R.id.backendGroup).width,
                            findViewById<View>(R.id.backendGroup).height,
                        ),
                        false,
                    )
                }.onFailure { Log.w("RistSettings", "could not scroll to the BACKEND section", it) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // The refresh timer holds this Activity; stop it or a destroyed Activity leaks.
        runCatching { OtaSection.stopRefresh() }
            .onFailure { Log.e("RistSettings", "ota refresh stop failed", it) }
    }

    private fun applySettingsTheme() = runCatching {
        val t = Themes.byId(Config.themeId(this))
        val faint = ThemePaint.faintOf(t)
        val tf = ThemePaint.typefaceOf(this, t)
        findViewById<View>(R.id.settingsScroll)?.setBackgroundColor(t.ground)
        ThemePaint.retint(this, findViewById(R.id.settingsScroll), t, faint, tf,
            skip = setOf(R.id.themePicker))
        window.statusBarColor = t.ground
        runCatching {
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .isAppearanceLightStatusBars = !t.dark
        }
    }.onFailure { Log.w("RistSettings", "theming failed", it) }.let { }

    private fun navigateHome() {
        if (isTaskRoot) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_HIDE_APPS = "watch.rist.assistant.HIDE_APPS"

        const val EXTRA_SHOW_BACKEND = "watch.rist.assistant.SHOW_BACKEND"

        /** Floor of the pinned range; position 0 is AUTO and not part of it. */
        const val MIN_PCT = 5
    }
}
