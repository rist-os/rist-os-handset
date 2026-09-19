package watch.rist.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream

/**
 * The call browser (video_calls.md section 5): a web view that can be on a meeting page and
 * nowhere else. No address bar, tabs, history, downloads or new windows; the only controls are
 * ours, and hang-up works even when the page does not. Nothing a call stores outlives it.
 *
 * Opened only by [VideoCallJoinActivity], after the person's tap.
 */
class CallBrowserActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RistCall"
        private const val EXTRA_URL = "watch.rist.assistant.extra.CALL_URL"
        private const val EXTRA_ORIGINAL_URL = "watch.rist.assistant.extra.CALL_ORIGINAL_URL"
        private const val EXTRA_PROVIDER = "watch.rist.assistant.extra.CALL_PROVIDER"
        private const val EXTRA_CAMERA = "watch.rist.assistant.extra.CALL_CAMERA"
        private const val EXTRA_MIC = "watch.rist.assistant.extra.CALL_MIC"

        /** How long Rist's "this link is no longer valid" page stays up before the browser closes. */
        private const val DEAD_LINK_SHOWN_MS = 4_000L

        fun intent(
            ctx: Context, url: String, originalUrl: String, provider: VideoCalls.Provider,
            camera: Boolean, mic: Boolean,
        ): Intent = Intent(ctx, CallBrowserActivity::class.java)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_ORIGINAL_URL, originalUrl)
            .putExtra(EXTRA_PROVIDER, provider.name)
            .putExtra(EXTRA_CAMERA, camera)
            .putExtra(EXTRA_MIC, mic)

        /**
         * A page may use the camera and microphone and nothing else, and only what the person
         * left switched on. Denying the permission is the one switch a page cannot ignore, so
         * the toggles are enforced here and not left to the page's goodwill.
         */
        internal fun grantable(requested: Array<String>, camera: Boolean, mic: Boolean): Array<String> =
            requested.filter {
                (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && camera) ||
                    (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && mic)
            }.toTypedArray()

        /**
         * Keeps hold of the streams the page opens, so that the phone can silence them when a
         * cellular call arrives or the screen locks. It passes everything through unchanged.
         */
        internal const val TRACKS_JS =
            "(function(){if(window.__ristCall)return;var s=[];window.__ristCall={set:function(k,on){" +
                "s.forEach(function(m){m.getTracks().forEach(function(t){if(t.kind===k)t.enabled=on;});});}};" +
                "var d=navigator.mediaDevices;if(!d||!d.getUserMedia)return;var g=d.getUserMedia.bind(d);" +
                "d.getUserMedia=function(c){return g(c).then(function(m){s.push(m);return m;});};})();"
    }

    private var web: WebView? = null
    private var provider = VideoCalls.Provider.OTHER
    private var ristHost: String? = null
    private var firstUrl = ""
    private var fallbackUrl = ""
    private var triedFallback = false
    private var closing = false

    private var cameraOn = true
    private var micOn = true
    private var speakerOn = false
    private var phoneCallActive = false

    private lateinit var theme: RistTheme
    private var tf: Typeface? = null
    private var d = 1f
    private lateinit var speakerButton: TextView

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var phoneListener: TelephonyCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Themes.byId(Config.themeId(this))
        tf = ThemePaint.typefaceOf(this, theme)
        d = resources.displayMetrics.density
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ristHost = VideoCalls.ristHost(Config.backendUrl(this))
        provider = runCatching { VideoCalls.Provider.valueOf(intent.getStringExtra(EXTRA_PROVIDER).orEmpty()) }
            .getOrDefault(VideoCalls.Provider.OTHER)
        cameraOn = intent.getBooleanExtra(EXTRA_CAMERA, true)
        micOn = intent.getBooleanExtra(EXTRA_MIC, true)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        fallbackUrl = intent.getStringExtra(EXTRA_ORIGINAL_URL).orEmpty()

        // Checked again here. Whatever started this activity, only a listed host is ever loaded.
        if (VideoCalls.navigation(url, ristHost) != VideoCalls.Nav.ALLOW) { finish(); return }

        val view = runCatching { WebView(this) }
            .onFailure { Log.w(TAG, "no web engine on this phone", it) }
            .getOrNull()
        if (view == null) { fail(); return }
        web = view
        setContentView(buildLayout(view))

        // Hang-up is the only way out. Back does nothing, so a stray gesture cannot drop a call.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        VideoCalls.onOpened(this)
        runCatching { OverlayHomeService.setVisible(this, false) }
        LockedBrowserActivity.forgetEverything(view)
        configure(view)
        beginAudio()
        watchForPhoneCalls()
        wakeLock = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rist:videocall").apply { acquire(4L * 60 * 60 * 1000) }
        }.getOrNull()

        firstUrl = VideoCalls.loadUrl(url, provider, cameraOn, micOn)
        view.loadUrl(firstUrl)
        Log.i(TAG, "call opened (${provider.wire}), camera=$cameraOn mic=$micOn")
    }

    /** Leaves the call. Safe to call twice, from any of the ways a call can end. */
    fun hangUp(why: String) {
        if (closing) return
        closing = true
        Log.i(TAG, "call closing: $why")
        finish()
    }

    private fun fail() {
        Toast.makeText(this, R.string.call_failed, Toast.LENGTH_LONG).show()
        hangUp("could not be opened")
    }

    override fun onStart() {
        super.onStart()
        // Back from a locked screen: the camera returns only if it was on.
        if (!phoneCallActive) setTracks("video", cameraOn)
    }

    override fun onStop() {
        // Locked: the call's audio carries on, the camera does not. (A page behind a lock
        // screen filming the room is not something anyone agreed to.)
        val interactive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (!isFinishing && !interactive) setTracks("video", false)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        VideoCalls.onClosed(this)
        val w = web
        web = null
        if (w != null) {
            runCatching { w.stopLoading() }
            runCatching { w.loadUrl("about:blank") }
            LockedBrowserActivity.forgetEverything(w)
            runCatching { (w.parent as? ViewGroup)?.removeView(w) }
            runCatching { w.destroy() }
        }
        endAudio()
        phoneListener?.let { l ->
            runCatching { getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(l) }
        }
        phoneListener = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { OverlayHomeService.setVisible(this, true) }
        super.onDestroy()
    }

    // --- the page -----------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // The other person's voice has to start by itself.
            mediaPlaybackRequiresUserGesture = false
            if (provider.desktop) {
                userAgentString = VideoCalls.desktopUserAgent(WebSettings.getDefaultUserAgent(this@CallBrowserActivity))
                // A desktop page laid out at desktop width and scaled to the screen.
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
        }
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(view, true) }
        // Text selection brings up "Share" and "Web search", which are ways out of the call.
        view.isLongClickable = false
        view.setOnLongClickListener { true }
        view.setDownloadListener { _, _, _, _, _ -> }
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
    }

    private fun setTracks(kind: String, on: Boolean) {
        runCatching { web?.evaluateJavascript("window.__ristCall&&window.__ristCall.set('$kind',$on);", null) }
    }

    private fun onTopLevel(url: String): VideoCalls.Nav = VideoCalls.navigation(url, ristHost)

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!request.isForMainFrame) {
                // Frames inside a trusted page load what they like over https; an app link
                // (zoommtg:, msteams:, intent:) is dropped wherever it comes from.
                return !url.startsWith("https://", ignoreCase = true) && !url.startsWith("about:", ignoreCase = true)
            }
            return when (onTopLevel(url)) {
                VideoCalls.Nav.ALLOW -> false
                VideoCalls.Nav.ENDED -> { hangUp("the call page said it was over"); true }
                // Silently. Zoom and Teams both try their app first and carry on when it fails.
                VideoCalls.Nav.SWALLOW -> true
            }
        }

        // Form posts skip shouldOverrideUrlLoading; this sees them, before anything is sent.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!request.isForMainFrame) return null
            return when (onTopLevel(request.url.toString())) {
                VideoCalls.Nav.ALLOW -> null
                VideoCalls.Nav.ENDED -> { runOnUiThread { hangUp("the call page said it was over") }; empty() }
                VideoCalls.Nav.SWALLOW -> empty()
            }
        }

        private fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            when (onTopLevel(url)) {
                VideoCalls.Nav.ENDED -> { hangUp("the call page said it was over"); return }
                VideoCalls.Nav.SWALLOW -> if (!url.startsWith("about:")) { view.stopLoading(); return }
                VideoCalls.Nav.ALLOW -> Unit
            }
            view.evaluateJavascript(TRACKS_JS, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(TRACKS_JS, null)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            fail()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            // A rewritten link is a best guess; the link as the person had it gets one try.
            if (!triedFallback && fallbackUrl.isNotBlank() && onTopLevel(fallbackUrl) == VideoCalls.Nav.ALLOW) {
                triedFallback = true
                Log.i(TAG, "the rewritten address failed; trying the original once")
                view.loadUrl(fallbackUrl)
            } else fail()
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            // Rist's page for a dead link explains itself; show it briefly, then go.
            if (request.isForMainFrame && provider == VideoCalls.Provider.RIST && response.statusCode == 404) {
                main.postDelayed({ hangUp("the call link is no longer valid") }, DEAD_LINK_SHOWN_MS)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "the call page's renderer went away (crashed=${detail.didCrash()})")
            web = null
            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
            runCatching { view.destroy() }
            fail()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {

        override fun onPermissionRequest(request: PermissionRequest) {
            // The page's own origin only: a frame from somewhere else inside it gets nothing.
            val fromCallPage = VideoCalls.classify(request.origin.toString(), ristHost) != null
            val grant = if (fromCallPage && !phoneCallActive) grantable(request.resources, cameraOn, micOn) else emptyArray()
            if (grant.isEmpty()) request.deny() else request.grant(grant)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message) = false

        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams,
        ): Boolean {
            callback.onReceiveValue(null)
            return true
        }
    }

    // --- sound ----------------------------------------------------------------------------------

    /** A call, not a video: earpiece by default, echo cancellation on, headsets honoured. */
    private fun beginAudio() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            am.requestAudioFocus(req)
            focus = req
        }
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        route(am)
    }

    private fun route(am: AudioManager) {
        runCatching {
            val devices = am.availableCommunicationDevices
            val headset = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val wanted = when {
                speakerOn -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                headset != null -> headset
                else -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (wanted != null) am.setCommunicationDevice(wanted) else am.clearCommunicationDevice()
        }.onFailure { Log.w(TAG, "could not route the call's audio", it) }
    }

    private fun endAudio() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { am.clearCommunicationDevice() }
        runCatching { am.mode = AudioManager.MODE_NORMAL }
        focus?.let { f -> runCatching { am.abandonAudioFocusRequest(f) } }
        focus = null
    }

    // --- a phone call wins ------------------------------------------------------------------

    private fun watchForPhoneCalls() {
        runCatching {
            val tm = getSystemService(TelephonyManager::class.java) ?: return
            val l = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onPhoneCall(state != TelephonyManager.CALL_STATE_IDLE)
            }
            tm.registerTelephonyCallback(mainExecutor, l)
            phoneListener = l
        }.onFailure { Log.w(TAG, "cannot watch for phone calls during the video call", it) }
    }

    /** The video call is muted and blinded for as long as the phone call lasts, and not closed. */
    private fun onPhoneCall(active: Boolean) {
        if (active == phoneCallActive) return
        phoneCallActive = active
        Log.i(TAG, if (active) "a phone call took over; video call muted" else "phone call over; video call restored")
        setTracks("audio", !active && micOn)
        setTracks("video", !active && cameraOn)
        if (!active) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
            route(am)
        }
    }

    // --- the screen -------------------------------------------------------------------------

    private fun buildLayout(view: WebView): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.ground)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(view)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        speakerButton = chromeButton("", filled = false, weight = 1f) {
            speakerOn = !speakerOn
            route(getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            paintSpeaker()
        }
        paintSpeaker()
        bar.addView(speakerButton)
        bar.addView(
            chromeButton(getString(R.string.call_hang_up), filled = true, weight = 2f) { hangUp("hang-up") }
                .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = (12 * d).toInt() }
        )
        root.addView(bar)
        return root
    }

    private fun paintSpeaker() {
        speakerButton.text = getString(if (speakerOn) R.string.call_speaker_on else R.string.call_speaker_off)
    }

    private fun chromeButton(label: String, filled: Boolean, weight: Float, onClick: () -> Unit) = TextView(this).apply {
        text = label
        isAllCaps = true
        letterSpacing = 0.08f
        typeface = tf
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        minHeight = (56 * d).toInt()
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
