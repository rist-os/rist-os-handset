package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Security review 2026-10-03: the small pure rules behind each handset fix. */
class HardeningTest {

    @Test
    fun `only a public build disallows debugging features`() {
        val dbg = android.os.UserManager.DISALLOW_DEBUGGING_FEATURES
        assertTrue(dbg in KioskManager.kioskRestrictions(publicBuild = true))
        assertFalse(dbg in KioskManager.kioskRestrictions(publicBuild = false))
        assertTrue(android.os.UserManager.DISALLOW_SAFE_BOOT in KioskManager.kioskRestrictions(false))
        assertFalse(android.os.UserManager.DISALLOW_FACTORY_RESET in KioskManager.kioskRestrictions(true))
    }

    @Test
    fun `a backend cancel or snooze silences only the alarm it names`() {
        assertTrue(AlarmService.ringingMatches("a7", blankMeansAll = true, ringing = "a7"))
        assertFalse(AlarmService.ringingMatches("a9", blankMeansAll = true, ringing = "a7"))
        assertFalse(AlarmService.ringingMatches("a9", blankMeansAll = false, ringing = "a7"))
        assertTrue(AlarmService.ringingMatches("", blankMeansAll = true, ringing = "a7"))
        assertFalse(AlarmService.ringingMatches("", blankMeansAll = false, ringing = "a7"))
        assertFalse(AlarmService.ringingMatches("a7", blankMeansAll = true, ringing = ""))
    }

    @Test
    fun `only a 403 that says revoked revokes`() {
        assertTrue(Enrolment.isExplicitRevocation(403, "1"))
        assertTrue(Enrolment.isExplicitRevocation(403, " true "))
        assertFalse(Enrolment.isExplicitRevocation(403, null))
        assertFalse(Enrolment.isExplicitRevocation(403, "0"))
        assertFalse(Enrolment.isExplicitRevocation(401, "1"))
    }

    @Test
    fun `voicemail ids cannot climb out of the cache or the URL`() {
        assertTrue(VoicemailAudio.safeId("vm_RE0123456789abcdef"))
        assertTrue(VoicemailAudio.safeId("vm_carrier_" + "a".repeat(64)))
        assertFalse(VoicemailAudio.safeId("../../x"))
        assertFalse(VoicemailAudio.safeId("a/b"))
        assertFalse(VoicemailAudio.safeId("x.audio"))
        assertFalse(VoicemailAudio.safeId(""))
        assertFalse(VoicemailAudio.safeId("a".repeat(129)))
    }

    @Test
    fun `a voicemail body past the cap leaves nothing on disk`() {
        val out = File.createTempFile("vmail", ".part")
        assertNull(VoicemailAudio.copyBounded(ByteArray(2000).inputStream(), out, 1000))
        assertFalse(out.exists())
        val ok = File.createTempFile("vmail", ".part")
        assertEquals(1000L, VoicemailAudio.copyBounded(ByteArray(1000).inputStream(), ok, 1000))
        assertEquals(1000L, ok.length())
        ok.delete()
    }

    @Test
    fun `the feed resolver reads only what looks like a feed`() {
        assertTrue(RssResolver.mediaType("audio/mpeg"))
        assertFalse(RssResolver.mediaType("application/octet-stream"))
        assertFalse(RssResolver.looksLikeFeed("application/octet-stream", "ID3\u0004\u0000"))
        assertTrue(RssResolver.looksLikeFeed("application/octet-stream", "<?xml version=\"1.0\"?><rss>"))
        assertFalse(RssResolver.mediaType("application/rss+xml"))
        assertTrue(RssResolver.looksLikeFeed("text/xml; charset=utf-8", ""))
        assertTrue(RssResolver.looksLikeFeed(null, "﻿<?xml version=\"1.0\"?><rss>"))
        assertFalse(RssResolver.looksLikeFeed("text/plain", "ID3\u0003"))
    }

    @Test
    fun `only this app, SystemUI, Bluetooth and the system may control playback`() {
        val own = 10123
        assertEquals(ControllerTrust.FULL, trustFor(own, "watch.rist.assistant", own, listOf("watch.rist.assistant")))
        assertEquals(ControllerTrust.TRANSPORT, trustFor(1002, "com.android.bluetooth", own, emptyList()))
        assertEquals(ControllerTrust.TRANSPORT, trustFor(1000, "android", own, emptyList()))
        assertEquals(ControllerTrust.TRANSPORT, trustFor(10050, "x", own, listOf(SYSTEM_UI_PACKAGE)))
        assertEquals(ControllerTrust.REJECT, trustFor(10200, SYSTEM_UI_PACKAGE, own, listOf("evil.app")))
        assertEquals(ControllerTrust.REJECT, trustFor(10200, "evil.app", own, listOf("evil.app")))
    }

    @Test
    fun `an unsigned up-to-date is believed only after a recent signed manifest`() {
        val now = 2_000_000_000L
        assertFalse(OtaScheduler.unsignedReplyTrusted(0L, now))
        assertTrue(OtaScheduler.unsignedReplyTrusted(now - 3600, now))
        assertFalse(OtaScheduler.unsignedReplyTrusted(now - OtaSignature.MAX_LIFETIME_SECONDS - 1, now))
        assertFalse(OtaScheduler.unsignedReplyTrusted(now + 3600, now))
    }

    @Test
    fun `a view image must come over https`() {
        assertEquals(ViewLogic.ImageSource.None, ViewLogic.resolveImageSource(mapOf("src" to "http://10.0.0.1/a.png"), 1024))
        assertEquals(ViewLogic.ImageSource.None, ViewLogic.resolveImageSource(mapOf("src" to "file:///sdcard/a.png"), 1024))
    }
}
