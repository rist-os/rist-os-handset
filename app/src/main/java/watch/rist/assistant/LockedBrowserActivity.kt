package watch.rist.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream

/**
 * The only browser on the phone, and not much of one: it opens the site a scanned code points
 * at and cannot be steered anywhere else. There is no address bar, no search, no tabs, no
 * bookmarks and no history, and nothing a page stores outlives the visit. What may be opened is
 * decided by [SiteLock]; this class only asks it, at every point a page can navigate from.
 */
class LockedBrowserActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RistWeb"
        const val EXTRA_URL = "watch.rist.assistant.extra.WEB_URL"

        fun intent(ctx: Context, url: String): Intent =
            Intent(ctx, LockedBrowserActivity::class.java).putExtra(EXTRA_URL, url)

        /**
         * Every switch that lets a page reach past itself, off. JavaScript and page storage stay
         * on, because menus, tickets and check-ins do not draw without them; the storage is
         * wiped when the visit ends.
         */
        @SuppressLint("SetJavaScriptEnabled")
        internal fun harden(settings: WebSettings) {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
        }

        /** Cookies and page storage are process-wide in WebView, so they are emptied, not scoped. */
        internal fun forgetEverything(web: WebView?) {
            runCatching { CookieManager.getInstance().removeAllCookies(null) }
            runCatching { CookieManager.getInstance().flush() }
            runCatching { WebStorage.getInstance().deleteAllData() }
            runCatching { web?.clearCache(true) }
            runCatching { web?.clearHistory() }
            runCatching { web?.clearFormData() }
        }
    }

    private val session = SiteLock.Session()
    private var web: WebView? = null
    private lateinit var siteLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerAllow: TextView

    private lateinit var theme: RistTheme
    private var tf: Typeface? = null
    private var d = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Themes.byId(Config.themeId(this))
        tf = ThemePaint.typefaceOf(this, theme)
        d = resources.displayMetrics.density

        // Checked again here, not only by the caller: nothing but a plain https link gets in.
        val url = SiteLock.openable(intent.getStringExtra(EXTRA_URL).orEmpty())
        if (url == null) { finish(); return }

        val view = runCatching { WebView(this) }
            .onFailure { Log.w(TAG, "no web engine on this phone", it) }
            .getOrNull()
        setContentView(buildLayout(view))
        if (view == null) {
            siteLabel.text = SiteLock.siteOf(url).orEmpty()
            showNote(getString(R.string.web_no_engine))
            return
        }
        web = view
        forgetEverything(view)
        configure(view)
        siteLabel.text = SiteLock.siteOf(url).orEmpty()
        view.loadUrl(url)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                if (w != null && w.canGoBack()) w.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        val w = web
        web = null
        if (w != null) {
            runCatching { w.stopLoading() }
            forgetEverything(w)
            runCatching { (w.parent as? ViewGroup)?.removeView(w) }
            runCatching { w.destroy() }
        }
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configure(view: WebView) {
        harden(view.settings)
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(view, false) }

        // The first touch is what ends the landing chain and fixes the site (see SiteLock).
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) session.onTouched()
            false
        }
        // Text selection brings up "Share" and "Web search", which are ways into other apps.
        view.isLongClickable = false
        view.setOnLongClickListener { true }
        view.isHapticFeedbackEnabled = false

        view.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, R.string.web_no_downloads, Toast.LENGTH_SHORT).show()
        }
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
    }

    private fun judge(url: String, mainFrame: Boolean, gesture: Boolean): SiteLock.Verdict =
        session.decide(url, mainFrame, gesture)

    private fun refuse(url: String, verdict: SiteLock.Verdict) {
        // Hosts only. A full URL can carry a person's order or table in its path.
        Log.i(TAG, "stopped a navigation: $verdict to ${SiteLock.siteOf(url) ?: "(not a web page)"}")
        if (verdict == SiteLock.Verdict.LEAVES_SITE) showLeaving(url) else showNote(getString(R.string.web_blocked_other))
    }

    private inner class Client : WebViewClient() {

        // Links, scripted navigations and every hop of a redirect come through here.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val verdict = judge(url, request.isForMainFrame, request.hasGesture())
            if (verdict == SiteLock.Verdict.ALLOW) return false
            refuse(url, verdict)
            return true
        }

        // Form posts do not pass through shouldOverrideUrlLoading. This does see them, and sees
        // them before anything is sent, so a page cannot post its way off the site either.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!request.isForMainFrame) return null
            val url = request.url.toString()
            val verdict = judge(url, mainFrame = true, gesture = false)
            if (verdict == SiteLock.Verdict.ALLOW) return null
            runOnUiThread { refuse(url, verdict) }
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // The last line of defence: whatever route got here, a page off the site does not load.
            val verdict = judge(url, mainFrame = true, gesture = false)
            if (verdict != SiteLock.Verdict.ALLOW) {
                view.stopLoading()
                refuse(url, verdict)
                return
            }
            session.onArrived(url)
            siteLabel.text = session.site.orEmpty()
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String) {
            session.onPageDrawn()
            progress.visibility = View.GONE
        }

        // A certificate problem is never offered as something to click through.
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            showNote(getString(R.string.web_failed))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showNote(getString(R.string.web_failed))
        }

        // Without this the whole app dies with the page's renderer.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "the page's renderer went away (crashed=${detail.didCrash()})")
            web = null
            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
            runCatching { view.destroy() }
            finish()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
        }

        // Camera, microphone, location, new windows, file uploads: a page gets none of them.
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

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

    // --- the screen -------------------------------------------------------------------------

    private fun buildLayout(view: WebView?): View {
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

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), 0, (4 * d).toInt(), 0)
        }
        bar.addView(barButton("←", getString(R.string.web_back)) {
            val w = web
            if (w != null && w.canGoBack()) w.goBack() else finish()
        })
        siteLabel = TextView(this).apply {
            setTextColor(theme.ink); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(siteLabel)
        bar.addView(barButton("↻", getString(R.string.web_reload)) { web?.reload() })
        bar.addView(barButton("×", getString(R.string.web_close)) { finish() })
        root.addView(bar)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(theme.accent)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (3 * d).toInt())
            visibility = View.GONE
        }
        root.addView(progress)
        root.addView(View(this).apply {
            setBackgroundColor(theme.fieldBorder)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, d.toInt()))
        })

        val page: View = view ?: View(this)
        page.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(page)

        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.ground)
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            visibility = View.GONE
        }
        bannerText = TextView(this).apply {
            setTextColor(theme.ink); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        banner.addView(bannerText)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        bannerAllow = bannerButton("") {}
        actions.addView(bannerAllow)
        actions.addView(bannerButton(getString(R.string.web_blocked_stay)) { banner.visibility = View.GONE })
        banner.addView(actions)
        root.addView(View(this).apply {
            setBackgroundColor(theme.fieldBorder)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, d.toInt()))
        })
        root.addView(banner)
        return root
    }

    private fun barButton(glyph: String, spoken: String, onClick: () -> Unit) = TextView(this).apply {
        text = glyph
        contentDescription = spoken
        setTextColor(theme.ink); typeface = tf
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        gravity = Gravity.CENTER
        minWidth = (48 * d).toInt()
        minHeight = (52 * d).toInt()
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun bannerButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(theme.ink); typeface = tf
        isAllCaps = true
        letterSpacing = 0.06f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        minHeight = (48 * d).toInt()
        setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke((1.5f * d).toInt(), theme.accent)
            cornerRadius = 12f * d
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (8 * d).toInt() }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A page tried to leave. Named, so that letting it through is a decision about a site. */
    private fun showLeaving(url: String) {
        val target = SiteLock.siteOf(url) ?: return
        bannerText.text = getString(R.string.web_blocked, session.site.orEmpty(), target)
        bannerAllow.text = getString(R.string.web_blocked_allow, target)
        bannerAllow.visibility = View.VISIBLE
        bannerAllow.setOnClickListener {
            session.allow(target)
            banner.visibility = View.GONE
            web?.loadUrl(url)
        }
        banner.visibility = View.VISIBLE
        bannerText.announceForAccessibility(bannerText.text)
    }

    private fun showNote(message: String) {
        bannerText.text = message
        bannerAllow.visibility = View.GONE
        banner.visibility = View.VISIBLE
        bannerText.announceForAccessibility(message)
    }
}
