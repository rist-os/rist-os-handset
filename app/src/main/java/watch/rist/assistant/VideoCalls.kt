package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import rist.v1.VideoCallCommand
import java.lang.ref.WeakReference

/**
 * Video calls (video_calls.md). A call is a web page in a browser that can show nothing but a
 * call: the backend sends the link, the person taps Join on a native screen, and only then does
 * anything load or any camera open.
 *
 * The backend checks every link before sending it. This is the handset's own copy of the same
 * list, because the handset is what stands between a link a stranger wrote and a live camera.
 */
object VideoCalls {

    private const val TAG = "RistCall"

    /**
     * Whether requests declare the `video_call` component (and schema v15). The backend sends no
     * call command to a device that does not, and says so in words instead.
     *
     * Switched on by the owner on 2026-09-19 to test by voice, before the section 8 live test.
     * The join screen still stands between any command and a live camera, so an early "join"
     * that fails costs a failed page, not a call nobody agreed to.
     */
    const val SHIPPED = true

    const val COMPONENT = "video_call"
    const val SCHEMA_VERSION = 15

    const val ACTION_JOIN = "join"
    const val ACTION_END = "end"

    enum class Provider(val wire: String, val label: String, val desktop: Boolean) {
        MEET("meet", "Google Meet", true),
        ZOOM("zoom", "Zoom", true),
        TEAMS("teams", "Microsoft Teams", true),
        RIST("rist", "Rist call", false),
        /** A value this build has not heard of: a plain page under the same rules, not a refusal. */
        OTHER("", "Video call", false),
    }

    fun provider(wire: String): Provider =
        Provider.values().firstOrNull { it != Provider.OTHER && it.wire == wire.trim().lowercase() } ?: Provider.OTHER

    private val EXACT_HOSTS = mapOf(
        "meet.google.com" to Provider.MEET,
        "zoom.us" to Provider.ZOOM,
        "teams.microsoft.com" to Provider.TEAMS,
        "teams.live.com" to Provider.TEAMS,
        // Microsoft is moving Teams on the web here during September 2026. The backend never
        // sends it, but a meeting can redirect to it, and a blocked redirect is a blank page.
        "teams.cloud.microsoft" to Provider.TEAMS,
    )
    private const val ZOOM_SUFFIX = ".zoom.us"
    /** Only the call itself. The page's own scripts under /call-assets/ load as subresources. */
    private const val RIST_PATH = "/c/"
    private const val RIST_ENDED_PATH = "/c/ended"

    /** The join screen shows at most this much of a title a stranger may have written. */
    const val TITLE_MAX = 80

    /** The host Rist's own call pages are served from: the backend this phone talks to. */
    fun ristHost(backendUrl: String): String? = backendUrl.trim().toHttpUrlOrNull()?.host?.lowercase()

    /**
     * Which service a MAIN-FRAME address belongs to, or null when the call browser must not be
     * there. https only, no user name, and a host matched exactly or as a dot-suffix, so that
     * evilzoom.us is not zoom.us and meet.google.com.evil.example is not Meet. Hosts only for the
     * three outside services: their pages move between paths during a call.
     *
     * Parsed the way the engine parses, never by string suffix: to a browser a backslash is a
     * slash, so https://evil.com\.zoom.us/ is evil.com, and OkHttp's parser agrees.
     */
    fun classify(url: String, ristHost: String?): Provider? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        if (!u.isHttps || u.username.isNotEmpty() || u.password.isNotEmpty()) return null
        val host = u.host.lowercase()
        EXACT_HOSTS[host]?.let { return it }
        if (host.endsWith(ZOOM_SUFFIX)) return Provider.ZOOM
        if (ristHost != null && host == ristHost && u.encodedPath.startsWith(RIST_PATH)) {
            return Provider.RIST
        }
        return null
    }

    enum class Nav {
        ALLOW,
        /** Off the list, or not a web page at all (zoommtg://, msteams://, intent:). Dropped without a word. */
        SWALLOW,
        /** Rist's own page saying the call is over. */
        ENDED,
    }

    /**
     * The first meeting link in a text, or null. A bare `meet.google.com/…` counts; what the
     * call browser would refuse does not, so the button only appears where Join would work.
     */
    fun meetingLinkIn(text: String, ristHost: String?): String? =
        LINK.findAll(text).map { m ->
            val raw = m.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':', '"', '\'', '>')
            if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
        }.firstOrNull { classify(it, ristHost) != null && isMeetingPath(it) }

    // Not after "@" or a dot: bob@zoom.us is an address, not a link.
    private val LINK = Regex("""(?i)(?<![@\w.])(?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/\S*)?""")

    /** The page of a meeting, not the service's home, download or sign-in page. */
    internal fun isMeetingPath(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false
        val path = u.encodedPath.lowercase()
        return when (EXACT_HOSTS[u.host.lowercase()]) {
            Provider.MEET -> Regex("""/[a-z]{3}-[a-z]{4}-[a-z]{3}/?""").matches(path) ||
                path.startsWith("/lookup/")
            Provider.TEAMS -> "meetup-join" in path || path.startsWith("/meet/")
            else -> if (u.host.lowercase().endsWith(ZOOM_SUFFIX) || u.host.lowercase() == "zoom.us")
                Regex("""/(j|w|wc|my|s)/.+""").matches(path)
            else path.startsWith(RIST_PATH) && path.length > RIST_PATH.length
        }
    }

    /**
     * What the page reports about its call, polled by the call browser. A call is over once it
     * has connected and every connection the page made is closed: that is what Meet, Zoom and
     * Teams do when someone leaves or the host ends it. Muting and turning the camera off do not
     * close a connection, so neither ends the call here.
     */
    enum class PageCall { IDLE, LIVE, OVER }

    fun pageCall(reported: String?): PageCall = when (reported?.trim()?.trim('"')) {
        "live" -> PageCall.LIVE
        "over" -> PageCall.OVER
        else -> PageCall.IDLE
    }

    /** Over for this many polls in a row before the browser closes: a reconnect is not an end. */
    const val OVER_POLLS = 2
    const val POLL_MS = 2_000L

    fun navigation(url: String, ristHost: String?): Nav {
        val provider = classify(url, ristHost) ?: return Nav.SWALLOW
        if (provider == Provider.RIST && url.trim().toHttpUrlOrNull()?.encodedPath == RIST_ENDED_PATH) return Nav.ENDED
        return Nav.ALLOW
    }

    /**
     * The address actually loaded. A Rist call gets a fragment so the page skips its own lobby
     * (the person has already tapped Join) and honours the toggles. A fragment, not a query: it
     * is never sent to a server or written to a log.
     */
    fun loadUrl(url: String, provider: Provider, camera: Boolean, mic: Boolean, name: String = ""): String {
        if (provider != Provider.RIST) return url
        val base = url.substringBefore('#')
        val who = name.trim().takeIf { it.isNotEmpty() }
            ?.let { "&name=" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }.orEmpty()
        return "$base#go=1$who&camera=${if (camera) 1 else 0}&mic=${if (mic) 1 else 0}"
    }

    /**
     * Meet, Zoom and Teams hand a phone browser an app-store page; with a desktop Chrome
     * user-agent they serve the web client. Built from the engine's own, so the version is real.
     */
    fun desktopUserAgent(engineDefault: String): String {
        val chrome = Regex("Chrome/([0-9.]+)").find(engineDefault)?.groupValues?.get(1) ?: "140.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    // --- the open call, and commands from the backend -----------------------------------------

    @Volatile private var open: WeakReference<CallBrowserActivity>? = null

    /**
     * A join that arrived with a confirmation ("...and email her the link"). The confirmation
     * goes first; the join screen follows whichever way it is answered, because the call exists
     * either way and only the invitation was in question.
     */
    @Volatile private var deferred: VideoCallCommand? = null

    fun defer(cmd: VideoCallCommand) { deferred = cmd }

    /** Called once the confirmation that held a join back has been answered or has lapsed. */
    fun releaseDeferred(ctx: Context) {
        val cmd = deferred ?: return
        deferred = null
        onCommand(ctx, cmd)
    }

    /**
     * The composer for an invitation that arrived while a call is open. It waits behind a banner
     * in the call rather than covering it.
     */
    internal fun queueComposer(intent: Intent): Boolean {
        val a = open?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return false
        a.runOnUiThread { a.offerComposer(intent) }
        return true
    }

    fun isOpen(): Boolean = open?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

    internal fun onOpened(a: CallBrowserActivity) { open = WeakReference(a) }

    internal fun onClosed(a: CallBrowserActivity) { if (open?.get() === a) open = null }

    /** Leaves the open call. Nothing to do, and nothing said, when there is none. */
    fun end() {
        val a = open?.get() ?: return
        a.runOnUiThread { a.hangUp("ended by the assistant") }
    }

    fun onCommand(ctx: Context, cmd: VideoCallCommand) {
        when (cmd.action.trim().lowercase()) {
            ACTION_END -> end()
            ACTION_JOIN -> join(ctx, cmd.url, cmd.originalUrl, cmd.title, cmd.provider, fromAssistant = true)
            else -> Log.w(TAG, "unknown video call action; ignored")
        }
    }

    /** Puts the join screen up. Returns false for an address the call browser will not load. */
    fun join(
        ctx: Context, url: String, originalUrl: String, title: String, providerWire: String,
        fromAssistant: Boolean = false,
    ): Boolean {
        val host = ristHost(Config.backendUrl(ctx))
        val byHost = classify(url, host)
        if (byHost == null) {
            // The url is a credential for a Rist call, so it is never logged; the verdict is.
            Log.w(TAG, "refusing a call link that is not on the list")
            return false
        }
        // SINGLE_TOP: a second join while the screen is up replaces it silently (a turn re-sent
        // after a location request brings a fresh one).
        val intent = Intent(ctx, VideoCallJoinActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(VideoCallJoinActivity.EXTRA_URL, url)
            .putExtra(VideoCallJoinActivity.EXTRA_ORIGINAL_URL, originalUrl.takeIf { classify(it, host) != null }.orEmpty())
            .putExtra(VideoCallJoinActivity.EXTRA_TITLE, title.take(TITLE_MAX))
            // The HOST picks the handling, not the label sent with it: the host is what was checked.
            .putExtra(VideoCallJoinActivity.EXTRA_PROVIDER, byHost.name)
            // Only a command's own link may skip Rist's lobby; a texted or scanned one may not.
            .putExtra(VideoCallJoinActivity.EXTRA_FROM_ASSISTANT, fromAssistant)
        return runCatching { ctx.startActivity(intent); true }
            .onFailure { Log.w(TAG, "could not show the join screen", it) }
            .getOrDefault(false)
    }
}
