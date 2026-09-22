package watch.rist.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Bundle
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
import androidx.webkit.ProfileStore
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream

/**
 * The call browser (video_calls.md section 5): a web view whose main frame can be on a meeting
 * page and nowhere else. No address bar, tabs, history, downloads or new windows; the only
 * controls are ours, and hang-up works even when the page does not, by destroying the view.
 * Everything the call stores lives in a storage profile of its own, deleted when it closes.
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
        private const val EXTRA_FROM_ASSISTANT = "watch.rist.assistant.extra.CALL_FROM_ASSISTANT"

        /**
         * The call's own storage. The QR browser shares this process and its default storage, so
         * wiping the default store would wipe it too; a profile is separate and deleted whole.
         */
        internal const val PROFILE = "rist-call"

        /** Tells Rist's call page the join tap already happened. A link cannot set a header. */
        internal const val HEADER_CALL_BROWSER = "X-Rist-Call-Browser"

        fun intent(
            ctx: Context, url: String, originalUrl: String, provider: VideoCalls.Provider,
            camera: Boolean, mic: Boolean, fromAssistant: Boolean,
        ): Intent = Intent(ctx, CallBrowserActivity::class.java)
            .putExtra(EXTRA_FROM_ASSISTANT, fromAssistant)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_ORIGINAL_URL, originalUrl)
            .putExtra(EXTRA_PROVIDER, provider.name)
            .putExtra(EXTRA_CAMERA, camera)
            .putExtra(EXTRA_MIC, mic)

        /**
         * A page may use the camera and microphone and nothing else, and only what is switched
         * on. Denying the permission is the one switch a page cannot ignore.
         */
        internal fun grantable(requested: Array<String>, camera: Boolean, mic: Boolean): Array<String> =
            requested.filter {
                (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && camera) ||
                    (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && mic)
            }.toTypedArray()

        /** Capture goes only to the page the call is on, never to a frame from somewhere else. */
        internal fun sameOrigin(origin: String, mainFrameUrl: String): Boolean {
            val a = origin.trim().toHttpUrlOrNull() ?: return false
            val b = mainFrameUrl.trim().toHttpUrlOrNull() ?: return false
            return a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
        }

        /**
         * Keeps hold of the streams the page opens, so a cellular call can silence them, and of
         * its connections, so the phone can tell when the call is over. Streams and connections
         * are handed back unchanged; nothing else on the page is touched. Only tracks this script
         * silenced are switched back on, so a mute set on the page survives a phone call, and a
         * connection is built with the caller's new.target so a page's own subclass still works.
         */
        internal const val TRACKS_JS =
            "(function(){if(window.__ristCall)return;var s=[],p=[],seen=false;window.__ristCall={set:function(k,on){" +
                "s.forEach(function(m){m.getTracks().forEach(function(t){if(t.kind!==k)return;" +
                "if(!on){if(t.enabled){t.enabled=false;t.__ristOff=true;}}" +
                "else if(t.__ristOff){t.enabled=true;delete t.__ristOff;}});});}," +
                "state:function(){if(!seen)return'idle';for(var i=0;i<p.length;i++){" +
                "if(p[i].connectionState!=='closed'&&p[i].signalingState!=='closed')return'live';}return'over';}};" +
                "var R=window.RTCPeerConnection;if(R){var W=function(){var c=Reflect.construct(R,arguments,new.target||W);p.push(c);" +
                "var up=function(){var x=c.connectionState,y=c.iceConnectionState;" +
                "if(x==='connected'||y==='connected'||y==='completed')seen=true;};" +
                "c.addEventListener('connectionstatechange',up);c.addEventListener('iceconnectionstatechange',up);return c;};" +
                "W.prototype=R.prototype;Object.setPrototypeOf(W,R);window.RTCPeerConnection=W;" +
                "if(window.webkitRTCPeerConnection)window.webkitRTCPeerConnection=W;}" +
                "var d=navigator.mediaDevices;if(!d||!d.getUserMedia)return;var g=d.getUserMedia.bind(d);" +
                "d.getUserMedia=function(c){return g(c).then(function(m){s.push(m);return m;});};})();"
    }

    private var web: WebView? = null
    private var provider = VideoCalls.Provider.OTHER
    private var ristHost: String? = null
    private var mainFrameUrl = ""
    private var fallbackUrl = ""
    private var triedFallback = false
    private var closing = false
    private var usesProfile = false
    private var profile = PROFILE
    private var startScriptInstalled = false

    // Only a Rist call's toggles are ours; Meet, Zoom and Teams keep their own and get both.
    private var cameraOn = true
    private var micOn = true
    private var phoneCallActive = false

    private lateinit var theme: RistTheme
    private var tf: Typeface? = null
    private var d = 1f
    private lateinit var banner: TextView
    private var cameraButton: TextView? = null
    private var micButton: TextView? = null

    private var wakeLock: PowerManager.WakeLock? = null
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
        if (provider == VideoCalls.Provider.RIST) {
            cameraOn = intent.getBooleanExtra(EXTRA_CAMERA, true)
            micOn = intent.getBooleanExtra(EXTRA_MIC, true)
        }
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

        // A Rist call has its own Hang up, so back does nothing there: a stray gesture cannot drop
        // it. Meet, Zoom and Teams end themselves; back asks first, for a page that never does.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (provider != VideoCalls.Provider.RIST) confirmLeave()
            }
        })
        if (provider != VideoCalls.Provider.RIST) watchForCallEnd()

        VideoCalls.onOpened(this)
        runCatching { OverlayHomeService.setVisible(this, false) }
        isolate(view)
        configure(view)
        watchForPhoneCalls()
        wakeLock = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rist:videocall").apply { acquire(4L * 60 * 60 * 1000) }
        }.getOrNull()

        mainFrameUrl = url
        val first = VideoCalls.loadUrl(url, provider, cameraOn, micOn)
        // The header is what lets Rist's page skip its lobby, and only on this first load of a
        // command's own link: a link in a message can carry a fragment but never a header.
        val fromAssistant = intent.getBooleanExtra(EXTRA_FROM_ASSISTANT, false)
        if (provider == VideoCalls.Provider.RIST && fromAssistant) view.loadUrl(first, mapOf(HEADER_CALL_BROWSER to "1"))
        else view.loadUrl(first)
        Log.i(TAG, "call opened (${provider.wire}), own profile=$usesProfile")
    }

    /** Leaves the call. Safe to call twice, from any of the ways a call can end. */
    fun hangUp(why: String) {
        if (closing) return
        closing = true
        Log.i(TAG, "call closing: $why")
        finish()
    }

    private val poller = android.os.Handler(android.os.Looper.getMainLooper())
    private var overPolls = 0

    /** Closes the browser once the page has ended its call: left, hung up on, or ended by the host. */
    private fun watchForCallEnd() {
        poller.postDelayed(object : Runnable {
            override fun run() {
                val w = web ?: return
                if (closing) return
                w.evaluateJavascript("window.__ristCall&&window.__ristCall.state?window.__ristCall.state():'idle'") { r ->
                    if (VideoCalls.pageCall(r) == VideoCalls.PageCall.OVER) overPolls++ else overPolls = 0
                    if (overPolls >= VideoCalls.OVER_POLLS) hangUp("the page ended its call")
                }
                poller.postDelayed(this, VideoCalls.POLL_MS)
            }
        }, VideoCalls.POLL_MS)
    }

    private fun confirmLeave() {
        runCatching {
            RistDialog.ask(
                this, theme, tf, d,
                title = null,
                message = getString(R.string.call_leave_ask),
                positive = getString(R.string.call_leave),
                onPositive = { hangUp("left with back") },
                negative = getString(R.string.call_stay),
            )
        }
    }

    private fun fail() {
        Toast.makeText(this, R.string.call_failed, Toast.LENGTH_LONG).show()
        hangUp("could not be opened")
    }

    override fun onDestroy() {
        poller.removeCallbacksAndMessages(null)
        VideoCalls.onClosed(this)
        val w = web
        web = null
        if (w != null) {
            runCatching { w.stopLoading() }
            runCatching { w.loadUrl("about:blank") }
            if (!usesProfile) LockedBrowserActivity.forgetEverything(w)
            runCatching { (w.parent as? ViewGroup)?.removeView(w) }
            runCatching { w.destroy() }
        }
        // The whole profile goes, cookies, storage, cache, service workers and permissions alike.
        // If the engine still holds it, it is deleted before the next call instead.
        if (usesProfile) runCatching { ProfileStore.getInstance().deleteProfile(profile) }
            .onFailure { Log.i(TAG, "call profile still in use; it is cleared at the next call") }
        phoneListener?.let { l ->
            runCatching { getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(l) }
        }
        phoneListener = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        // Not while another call is up: "Leave and join" opens the new one before this one goes.
        if (!VideoCalls.isOpen()) runCatching { OverlayHomeService.setVisible(this, true) }
        super.onDestroy()
    }

    // --- the page -----------------------------------------------------------------------------

    /** A fresh storage profile for this call, with nothing left in it from the last one. */
    private fun isolate(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                val store = ProfileStore.getInstance()
                // A name of its own: after "Leave and join" the old call still holds its profile
                // for a moment, and reusing the name would hand this call the old one's cookies.
                // Leftovers from calls that ended are cleared here; one still held is skipped.
                store.allProfileNames.filter { it.startsWith(PROFILE) }
                    .forEach { name -> runCatching { store.deleteProfile(name) } }
                profile = "$PROFILE-${android.os.SystemClock.elapsedRealtimeNanos()}"
                store.getOrCreateProfile(profile)
                WebViewCompat.setProfile(view, profile)
                usesProfile = true
            }.onFailure { Log.w(TAG, "could not give the call its own profile", it) }
        }
        if (!usesProfile) {
            // The engine has no profiles: fall back to wiping the shared store, as the QR browser
            // already does, so nothing of the call survives it either way.
            Log.w(TAG, "no multi-profile support; the call uses the shared store and wipes it")
            LockedBrowserActivity.forgetEverything(view)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(false)
        // true, and then refused in onCreateWindow: with false, window.open would navigate the
        // call's own view instead of being stopped.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Otherwise the other person's voice is blocked on any call not tapped into on the page.
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // A Rist link is a credential; nothing about visited addresses leaves the phone.
        runCatching { settings.safeBrowsingEnabled = false }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // No X-Requested-With: it tells a site it is inside an app.
            runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet()) }
        }
        if (provider.desktop) {
            settings.userAgentString = VideoCalls.desktopUserAgent(WebSettings.getDefaultUserAgent(this))
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            desktopClientHints(settings)
        }
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(view, true) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(view, TRACKS_JS, setOf("*")); startScriptInstalled = true }
        }
        // Text selection brings up "Share" and "Web search", which are ways out of the call.
        view.isLongClickable = false
        view.setOnLongClickListener { true }
        view.setDownloadListener { _, _, _, _, _ -> }
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
    }

    /** The user-agent says desktop; without this the Sec-CH-UA-Mobile hint would still say phone. */
    private fun desktopClientHints(settings: WebSettings) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        runCatching {
            val now = WebSettingsCompat.getUserAgentMetadata(settings)
            WebSettingsCompat.setUserAgentMetadata(
                settings,
                UserAgentMetadata.Builder()
                    .setBrandVersionList(now.brandVersionList)
                    .setFullVersion(now.fullVersion)
                    .setPlatform("Linux")
                    .setPlatformVersion("")
                    .setArchitecture("x86")
                    .setModel("")
                    .setMobile(false)
                    .setBitness(64)
                    .setWow64(false)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "could not set desktop client hints", it) }
    }

    private fun setTracks(kind: String, on: Boolean) {
        runCatching { web?.evaluateJavascript("window.__ristCall&&window.__ristCall.set('$kind',$on);", null) }
    }

    /** Every report of where the main frame now is comes through here. */
    private fun onMainFrame(url: String): VideoCalls.Nav {
        val nav = VideoCalls.navigation(url, ristHost)
        if (nav == VideoCalls.Nav.ALLOW) mainFrameUrl = url
        return nav
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            // Frames are not filtered: Zoom's captcha and the sign-in frames live in them, and a
            // filtered frame is a call that never connects. App links are dropped wherever they are.
            if (!request.isForMainFrame) return !url.startsWith("https://", ignoreCase = true) &&
                !url.startsWith("about:", ignoreCase = true)
            return when (onMainFrame(url)) {
                VideoCalls.Nav.ALLOW -> false
                VideoCalls.Nav.ENDED -> { hangUp("the call page said it was over"); true }
                // Silently: Zoom and Teams try their app first and carry on when it fails.
                VideoCalls.Nav.SWALLOW -> true
            }
        }

        // Form posts and loadUrl skip shouldOverrideUrlLoading; this sees them before anything is sent.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!request.isForMainFrame) return null
            return when (VideoCalls.navigation(request.url.toString(), ristHost)) {
                VideoCalls.Nav.ALLOW -> null
                VideoCalls.Nav.ENDED -> { runOnUiThread { hangUp("the call page said it was over") }; empty() }
                VideoCalls.Nav.SWALLOW -> empty()
            }
        }

        private fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url.startsWith("about:")) return
            when (onMainFrame(url)) {
                VideoCalls.Nav.ENDED -> { hangUp("the call page said it was over"); return }
                VideoCalls.Nav.SWALLOW -> { view.stopLoading(); return }
                VideoCalls.Nav.ALLOW -> Unit
            }
            if (!startScriptInstalled) view.evaluateJavascript(TRACKS_JS, null)
        }

        // Single-page navigations report here and nowhere else.
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (url.startsWith("about:")) return
            when (onMainFrame(url)) {
                VideoCalls.Nav.ENDED -> hangUp("the call page said it was over")
                VideoCalls.Nav.SWALLOW -> hangUp("the page left the call")
                VideoCalls.Nav.ALLOW -> Unit
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            fail()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            // A rewritten link is a best guess; the link as the person had it gets one try.
            if (!triedFallback && fallbackUrl.isNotBlank() && VideoCalls.navigation(fallbackUrl, ristHost) == VideoCalls.Nav.ALLOW) {
                triedFallback = true
                Log.i(TAG, "the rewritten address failed; trying the original once")
                view.loadUrl(fallbackUrl)
            } else fail()
        }

        // An expired Rist link, an unreachable server or a full room: the page says why and
        // stays up with its own Join; it is not a failed load. Hang-up (or back, off Rist) closes it.

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
            // Asked again on every getUserMedia, so the answer follows the switches mid-call.
            val fromCallPage = sameOrigin(request.origin.toString(), mainFrameUrl) &&
                VideoCalls.classify(mainFrameUrl, ristHost) != null
            val grant = when {
                !fromCallPage || phoneCallActive -> emptyArray()
                provider == VideoCalls.Provider.RIST -> grantable(request.resources, cameraOn, micOn)
                else -> grantable(request.resources, camera = true, mic = true)
            }
            if (grant.isEmpty()) request.deny() else request.grant(grant)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)

        // Every new window is refused.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message) = false

        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams,
        ): Boolean {
            callback.onReceiveValue(null)
            return true
        }
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
    }

    // --- the invitation, when it arrives during the call --------------------------------------

    /** Shown as a banner, never over the call. Tapping it opens the composer. */
    fun offerComposer(compose: Intent) {
        banner.text = getString(R.string.call_invite_ready)
        banner.visibility = View.VISIBLE
        banner.setOnClickListener {
            banner.visibility = View.GONE
            runCatching { startActivity(compose.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "could not open the composer", it) }
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

        banner = TextView(this).apply {
            setTextColor(theme.ground); typeface = tf
            setBackgroundColor(theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            minHeight = (48 * d).toInt()
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            isClickable = true; isFocusable = true
            visibility = View.GONE
        }
        root.addView(banner)

        // Meet, Zoom and Teams have their own leave button and close the browser themselves;
        // the whole screen goes to the call.
        if (provider != VideoCalls.Provider.RIST) return root

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        run {
            // What these change is only what the phone will answer when the page asks; the
            // page's own camera and mute buttons then do the rest.
            cameraButton = chromeButton("", filled = false, weight = 1f) {
                cameraOn = !cameraOn
                if (!cameraOn) setTracks("video", false)
                paintToggles()
            }.also { bar.addView(it) }
            micButton = chromeButton("", filled = false, weight = 1f) {
                micOn = !micOn
                if (!micOn) setTracks("audio", false)
                paintToggles()
            }.also {
                (it.layoutParams as LinearLayout.LayoutParams).marginStart = (8 * d).toInt()
                bar.addView(it)
            }
            paintToggles()
        }
        bar.addView(
            chromeButton(getString(R.string.call_hang_up), filled = true, weight = 2f) { hangUp("hang-up") }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = (8 * d).toInt()
            }
        )
        root.addView(bar)
        return root
    }

    private fun paintToggles() {
        cameraButton?.text = getString(if (cameraOn) R.string.call_camera_allowed else R.string.call_camera_blocked)
        micButton?.text = getString(if (micOn) R.string.call_mic_allowed else R.string.call_mic_blocked)
    }

    private fun chromeButton(label: String, filled: Boolean, weight: Float, onClick: () -> Unit) = TextView(this).apply {
        text = label
        isAllCaps = true
        letterSpacing = 0.06f
        typeface = tf
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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
