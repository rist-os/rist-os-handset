package watch.rist.assistant

import android.webkit.PermissionRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import watch.rist.assistant.VideoCalls.Nav
import watch.rist.assistant.VideoCalls.Provider

/** Where the call browser may be, and what it is given there. */
class VideoCallsTest {

    private val rist = "api.example.net"

    @Test
    fun `the listed hosts are recognised, by exact name or dot-suffix`() {
        assertEquals(Provider.MEET, VideoCalls.classify("https://meet.google.com/abc-defg-hij", rist))
        assertEquals(Provider.ZOOM, VideoCalls.classify("https://zoom.us/j/123?pwd=x", rist))
        assertEquals(Provider.ZOOM, VideoCalls.classify("https://app.zoom.us/wc/123/join?pwd=x", rist))
        assertEquals(Provider.ZOOM, VideoCalls.classify("https://us02web.zoom.us/j/123", rist))
        assertEquals(Provider.TEAMS, VideoCalls.classify("https://teams.microsoft.com/l/meetup-join/x", rist))
        assertEquals(Provider.TEAMS, VideoCalls.classify("https://teams.live.com/meet/123", rist))
        assertEquals(Provider.RIST, VideoCalls.classify("https://api.example.net/c/abcdef", rist))
        // Microsoft is moving Teams on the web here; a meeting can redirect through it.
        assertEquals(Provider.TEAMS, VideoCalls.classify("https://teams.cloud.microsoft/v2/", rist))
    }

    @Test
    fun `outside services are judged by host alone, so their pages can move between paths`() {
        assertEquals(Provider.TEAMS, VideoCalls.classify("https://teams.microsoft.com/dl/launcher/launcher.html", rist))
        assertEquals(Provider.ZOOM, VideoCalls.classify("https://app.zoom.us/wc/leave", rist))
        assertEquals(Provider.MEET, VideoCalls.classify("https://meet.google.com/", rist))
    }

    @Test
    fun `a backslash is a slash, as the engine reads it`() {
        // To a browser this is evil.com with a path, not a host under zoom.us.
        assertNull(VideoCalls.classify("https://evil.com\\.zoom.us/j/1", rist))
    }

    @Test
    fun `look-alikes and everything else are refused`() {
        for (url in listOf(
            "https://evilzoom.us/j/1", "https://zoom.us.evil.example/j/1",
            "https://meet.google.com.evil.example/abc", "https://accounts.google.com/signin",
            "https://www.google.com/", "https://notteams.microsoft.com.evil.net/",
            "http://meet.google.com/abc-defg-hij", "https://user@meet.google.com/abc",
            "zoommtg://zoom.us/join?confno=1", "msteams://teams.microsoft.com/l/x",
            "intent://x#Intent;end", "javascript:alert(1)", "file:///sdcard/x", "",
        )) assertNull(url, VideoCalls.classify(url, rist))
    }

    @Test
    fun `the backend's host is a call page only under its call paths`() {
        assertNull(VideoCalls.classify("https://api.example.net/v1/device", rist))
        assertNull(VideoCalls.classify("https://api.example.net/", rist))
        assertNull(VideoCalls.classify("https://api.example.net/c", rist))
        // The call page's scripts load as subresources; the main frame is never there.
        assertNull(VideoCalls.classify("https://api.example.net/call-assets/call.js", rist))
        // With no backend configured there is no Rist host at all.
        assertNull(VideoCalls.classify("https://api.example.net/c/abcdef", null))
        assertEquals("api.example.net", VideoCalls.ristHost("https://api.example.net/v1/device"))
        assertNull(VideoCalls.ristHost(""))
    }

    @Test
    fun `navigation is allowed on the list, swallowed off it, and ended by Rist's own page`() {
        assertEquals(Nav.ALLOW, VideoCalls.navigation("https://app.zoom.us/wc/1/join", rist))
        assertEquals(Nav.SWALLOW, VideoCalls.navigation("zoommtg://zoom.us/join?confno=1", rist))
        assertEquals(Nav.SWALLOW, VideoCalls.navigation("https://play.google.com/store/apps/details?id=x", rist))
        assertEquals(Nav.ENDED, VideoCalls.navigation("https://api.example.net/c/ended", rist))
        // Only Rist's page can say a call is over; the same path elsewhere is just a page.
        assertEquals(Nav.SWALLOW, VideoCalls.navigation("https://example.org/c/ended", rist))
    }

    @Test
    fun `a Rist call skips its lobby and carries the toggles in a fragment`() {
        assertEquals(
            "https://api.example.net/c/abc#go=1&camera=1&mic=0",
            VideoCalls.loadUrl("https://api.example.net/c/abc", Provider.RIST, camera = true, mic = false),
        )
        assertEquals(
            "https://api.example.net/c/abc#go=1&name=Ana%20Mar%C3%ADa&camera=0&mic=1",
            VideoCalls.loadUrl("https://api.example.net/c/abc#old", Provider.RIST, false, true, " Ana María "),
        )
    }

    @Test
    fun `an outside meeting's address is loaded exactly as sent`() {
        val zoom = "https://app.zoom.us/wc/123/join?pwd=abc"
        assertEquals(zoom, VideoCalls.loadUrl(zoom, Provider.ZOOM, camera = false, mic = false))
    }

    @Test
    fun `the three outside services get a desktop user-agent, with the engine's real version`() {
        val engine = "Mozilla/5.0 (Linux; Android 16; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/152.0.7977.84 Mobile Safari/537.36"
        val ua = VideoCalls.desktopUserAgent(engine)
        assertTrue(ua.contains("Chrome/152.0.7977.84"))
        assertFalse(ua.contains("Mobile"))
        assertFalse(ua.contains("Android"))
        assertTrue(Provider.MEET.desktop && Provider.ZOOM.desktop && Provider.TEAMS.desktop)
        assertFalse(Provider.RIST.desktop)
    }

    @Test
    fun `a page is granted only what the person left on, and never anything else`() {
        val all = arrayOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID, PermissionRequest.RESOURCE_MIDI_SYSEX,
        )
        assertArrayEquals(
            arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            CallBrowserActivity.grantable(all, camera = true, mic = true),
        )
        assertArrayEquals(
            arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
            CallBrowserActivity.grantable(all, camera = false, mic = true),
        )
        assertEquals(0, CallBrowserActivity.grantable(all, camera = false, mic = false).size)
    }

    @Test
    fun `the capability is declared with v15 or not at all`() {
        val without = DeviceProfile.capabilities(1080, 2424, videoCalls = false)
        assertFalse(VideoCalls.COMPONENT in without.componentsList)
        assertEquals(DeviceProfile.RCS_SCHEMA_VERSION, without.schemaVersion)

        val with = DeviceProfile.capabilities(1080, 2424, videoCalls = true)
        assertTrue(VideoCalls.COMPONENT in with.componentsList)
        assertEquals(15, with.schemaVersion)
    }

    @Test
    fun `capture goes only to the main frame's own origin`() {
        val main = "https://meet.google.com/abc-defg-hij"
        assertTrue(CallBrowserActivity.sameOrigin("https://meet.google.com/", main))
        assertFalse(CallBrowserActivity.sameOrigin("https://accounts.google.com/", main))
        assertFalse(CallBrowserActivity.sameOrigin("http://meet.google.com/", main))
        assertFalse(CallBrowserActivity.sameOrigin("https://meet.google.com:8443/", main))
        assertFalse(CallBrowserActivity.sameOrigin("null", main))
    }

    @Test
    fun `an unknown provider label is a plain page, not a refusal`() {
        assertEquals(Provider.OTHER, VideoCalls.provider("webex"))
        assertEquals(Provider.ZOOM, VideoCalls.provider(" Zoom "))
    }

    @Test
    fun `a meeting link is found in a text, bare or in full, and nothing else is`() {
        val rist = "rist.example"
        assertEquals("https://meet.google.com/ari-frsk-xzk",
            VideoCalls.meetingLinkIn("Join me: meet.google.com/ari-frsk-xzk.", rist))
        assertEquals("https://us02web.zoom.us/j/123?pwd=abc",
            VideoCalls.meetingLinkIn("see https://example.com then https://us02web.zoom.us/j/123?pwd=abc", rist))
        assertEquals(null, VideoCalls.meetingLinkIn("lunch at noon? example.com/menu", rist))
        assertEquals(null, VideoCalls.meetingLinkIn("http://meet.google.com/abc", rist))
        assertEquals(null, VideoCalls.meetingLinkIn("https://meet.google.com.evil.example/x", rist))
        // A service's own pages and an email address are not meetings.
        assertEquals(null, VideoCalls.meetingLinkIn("get it at zoom.us/download", rist))
        assertEquals(null, VideoCalls.meetingLinkIn("write to bob@zoom.us", rist))
        assertEquals(null, VideoCalls.meetingLinkIn("https://meet.google.com/", rist))
        assertEquals("https://teams.microsoft.com/l/meetup-join/19%3ameeting",
            VideoCalls.meetingLinkIn("https://teams.microsoft.com/l/meetup-join/19%3ameeting", rist))
    }

    @Test
    fun `only a page that reports its call over ends it`() {
        assertEquals(VideoCalls.PageCall.OVER, VideoCalls.pageCall("\"over\""))
        assertEquals(VideoCalls.PageCall.LIVE, VideoCalls.pageCall("\"live\""))
        assertEquals(VideoCalls.PageCall.IDLE, VideoCalls.pageCall("null"))
        assertEquals(VideoCalls.PageCall.IDLE, VideoCalls.pageCall(null))
    }
}
