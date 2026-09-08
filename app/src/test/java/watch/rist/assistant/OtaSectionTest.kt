package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaSectionTest {

    private val now = 1_800_000_000L

    private fun view(
        build: String = "2026090100",
        device: String = "stallion",
        channel: String = "stable",
        baseUrl: String = "https://ota.example.com",
        lastCheckAtSeconds: Long = now - 600,
        lastResult: String = "up to date (2026090100)",
        nextCheckAtSeconds: Long = now + 3600,
        readyBuild: String = "",
        applyingBuild: String = "",
        engineFault: OtaEngine.Reason? = null,
        lastNudgeAtSeconds: Long = 0L,
        applyingSinceSeconds: Long = 0L,
        applyingPercent: Int = OtaState.PERCENT_UNKNOWN,
        applyingStatus: Int = OtaApply.Status.IDLE,
        offeredBuild: String = "",
        approvedBuild: String = "",
        offeredSize: String = "2.1 GB",
        network: OtaNetwork.Suitability = OtaNetwork.Suitability.Unmetered,
        hold: OtaConsent.Hold? = null,
        failures: Int = 0,
        lastSuccessAtSeconds: Long = now - 600,
    ) = OtaStatusView(
        build = build, device = device, channel = channel, baseUrl = baseUrl,
        lastCheckAtSeconds = lastCheckAtSeconds, lastResult = lastResult,
        nextCheckAtSeconds = nextCheckAtSeconds, readyBuild = readyBuild,
        applyingBuild = applyingBuild, engineFault = engineFault, nowSeconds = now,
        lastNudgeAtSeconds = lastNudgeAtSeconds, applyingSinceSeconds = applyingSinceSeconds,
        applyingPercent = applyingPercent, applyingStatus = applyingStatus,
        offeredBuild = offeredBuild, approvedBuild = approvedBuild,
        offeredSize = offeredSize, network = network, hold = hold,
        failures = failures, lastSuccessAtSeconds = lastSuccessAtSeconds,
    )

    @Test
    fun `the settings section is the consent surface, and reaches it through the one gate`() {
        val code = String(classBytes("OtaSection"), Charsets.ISO_8859_1)
        assertTrue(
            "OtaSection no longer references OtaConsent, so the button on this screen either does " +
                "not exist or is recording consent some other way. There is one gate.",
            code.contains("watch/rist/assistant/OtaConsent")
        )
        assertFalse(
            "OtaSection reaches OtaService directly. Every download must go through the consent " +
                "gate in OtaScheduler.offer, which is what OtaConsent.startApprovedDownload arms.",
            code.contains("watch/rist/assistant/OtaService")
        )
        assertFalse(
            "OtaSection calls requestApplyNow. That is a second consent path " +
                "wearing its old name: it hands a build to the apply sequence from a click " +
                "handler, beside the gate rather than through it.",
            code.contains("requestApplyNow")
        )
        assertTrue(
            "OtaSection no longer calls OtaScheduler.requestCheckNow — opening the screen has " +
                "stopped being the check, and the assertions above are proving nothing",
            code.contains("requestCheckNow")
        )
    }

    @Test
    fun `the button appears only when there is something to press`() {
        assertEquals("nothing on the table", OtaButton.NONE, otaButton(view()))
        assertEquals(
            "an unanswered offer is the whole reason this control exists",
            OtaButton.UPDATE, otaButton(view(offeredBuild = "2026090200"))
        )
        assertEquals(
            "a staged build's action is a restart, which no button here can perform",
            OtaButton.NONE,
            otaButton(view(offeredBuild = "2026090200", readyBuild = "2026090200"))
        )
        assertEquals(
            "a control beside a live 1.5 GB download can only do harm",
            OtaButton.NONE,
            otaButton(
                view(offeredBuild = "2026090200", approvedBuild = "2026090200",
                    applyingBuild = "2026090200", applyingSinceSeconds = now - 60)
            )
        )
        assertEquals(
            "approved and progressing: the exit must exist, or a download that never starts is a " +
                "sentence the user can never get out from under",
            OtaButton.STOP_WAITING,
            otaButton(view(offeredBuild = "2026090200", approvedBuild = "2026090200"))
        )
        assertEquals(
            "the user said yes on Wi-Fi and left the house. The gate is holding for an answer, " +
                "and the only control that could give it must not be the one insisting nothing " +
                "is wrong.",
            OtaButton.UPDATE,
            otaButton(
                view(offeredBuild = "2026090200", approvedBuild = "2026090200",
                    network = OtaNetwork.Suitability.Metered,
                    hold = OtaConsent.Hold.METERED_NOT_APPROVED)
            )
        )
        assertEquals(
            "offline with an approval is still the waiting state; the exit stays reachable",
            OtaButton.STOP_WAITING,
            otaButton(
                view(offeredBuild = "2026090200", approvedBuild = "2026090200",
                    network = OtaNetwork.Suitability.None, hold = OtaConsent.Hold.NO_NETWORK)
            )
        )
    }

    @Test
    fun `the two buttons carry their own words`() {
        assertEquals(R.string.ota_update_button, otaButtonLabel(OtaButton.UPDATE))
        assertEquals(R.string.ota_downloading_stop, otaButtonLabel(OtaButton.STOP_WAITING))
    }

    @Test
    fun `the advice line matches the network, and is absent where there is nothing to advise`() {
        val offered = view(offeredBuild = "2026090200")
        assertEquals(R.string.ota_wifi_hint, otaCaption(offered, OtaButton.UPDATE))
        assertEquals(
            R.string.ota_wifi_hint,
            otaCaption(offered.copy(network = OtaNetwork.Suitability.Metered), OtaButton.UPDATE)
        )
        assertEquals(
            R.string.ota_offline_body,
            otaCaption(offered.copy(network = OtaNetwork.Suitability.None), OtaButton.UPDATE)
        )
        assertEquals("nothing to advise beside the exit",
            0, otaCaption(offered, OtaButton.STOP_WAITING))
        assertEquals("no button, no caption", 0, otaCaption(offered, OtaButton.NONE))
    }

    @Test
    fun `an outstanding offer is flagged, and an answered one is not`() {
        assertTrue("an offer nobody has answered is what the button exists for",
            otaOfferAwaitingAnswer(view(offeredBuild = "2026090200")))

        assertFalse(
            "the user already approved this build; it is held on network, not on them",
            otaOfferAwaitingAnswer(view(offeredBuild = "2026090200", approvedBuild = "2026090200"))
        )
        assertFalse("nothing is on the table", otaOfferAwaitingAnswer(view()))
        assertFalse(
            "a staged build is past the question — the sentence is 'restart to finish'",
            otaOfferAwaitingAnswer(view(offeredBuild = "2026090200", readyBuild = "2026090200"))
        )
        assertFalse(
            "it is downloading; the question was answered and acted on",
            otaOfferAwaitingAnswer(
                view(offeredBuild = "2026090200", applyingBuild = "2026090200",
                    applyingSinceSeconds = now - 60)
            )
        )
    }

    @Test
    fun `an approval for another build does not answer this one`() {
        assertTrue(
            "approving 2026090100 must not silently answer the question about 2026090200",
            otaOfferAwaitingAnswer(view(offeredBuild = "2026090200", approvedBuild = "2026090100"))
        )
    }

    @Test
    fun `the offer headline names the build and quotes the size`() {
        val h = otaHeadline(view(offeredBuild = "2026090200"))
        assertTrue("the build number is the one thing a person can check", h.contains("2026090200"))
        assertTrue("the size is the content of the decision the button asks for: $h",
            h.contains("2.1 GB"))
    }

    @Test
    fun `a missing size is omitted rather than described`() {
        val h = otaHeadline(view(offeredBuild = "2026090200", offeredSize = ""))
        assertTrue("the build must still be named: $h", h.contains("2026090200"))
        assertFalse("an empty size leaked into the sentence: $h", h.contains("about"))
    }

    @Test
    fun `an approved build says whether it is downloading, held on mobile data, or offline`() {
        val approved = view(offeredBuild = "2026090200", approvedBuild = "2026090200")
        assertTrue(otaHeadline(approved).contains("is downloading"))
        assertTrue(
            otaHeadline(approved.copy(hold = OtaConsent.Hold.METERED_NOT_APPROVED))
                .contains("needs mobile data")
        )
        assertTrue(
            otaHeadline(approved.copy(hold = OtaConsent.Hold.NO_NETWORK)).contains("back online")
        )
        assertFalse(
            "a phone with no network at all was told its update is downloading",
            otaHeadline(approved.copy(hold = OtaConsent.Hold.NO_NETWORK)).contains("is downloading")
        )
    }

    @Test
    fun `a staged build is announced, unless the phone is already running it`() {
        assertTrue(
            otaHeadline(view(build = "2026090100", readyBuild = "2026090200"))
                .contains("Restart the phone to finish")
        )
        assertEquals(
            "the phone is running the build it is telling the user to restart into. This is the " +
                "restart-forever bug and it is a permanent, unclearable instruction on a kiosk.",
            "", otaHeadline(view(build = "2026090200", readyBuild = "2026090200"))
        )
    }

    @Test
    fun `an install in flight outranks an offer`() {
        val h = otaHeadline(
            view(applyingBuild = "2026090200", applyingSinceSeconds = now - 60,
                offeredBuild = "2026090300")
        )
        assertTrue("expected the install headline, got: $h", h.startsWith("Installing 2026090200"))
    }

    @Test
    fun `a healthy phone with nothing to do gets no headline`() {
        assertEquals("", otaHeadline(view()))
    }

    @Test
    fun `no endpoint is stated as updates being off`() {
        val h = otaHeadline(view(baseUrl = ""))
        assertTrue("expected an explicit 'off', got: $h", h.contains("never check"))
    }

    @Test
    fun `a phone that cannot reach its server says how long it has been failing`() {
        val h = otaHeadline(
            view(failures = OTA_PERSISTENT_FAILURES, lastSuccessAtSeconds = now - 28 * 86400)
        )
        assertTrue("expected an age from the last success, got: $h", h.contains("28 days ago"))
        assertTrue(h.contains("not receiving security updates"))

        assertEquals("two failures is a tunnel, not a fault", "", otaHeadline(view(failures = 2)))
    }

    @Test
    fun `a phone that has never succeeded says never`() {
        val h = otaPersistentFailureText(view(failures = 5, lastSuccessAtSeconds = 0L))
        assertTrue("expected NEVER, got: $h", h.contains("NEVER"))
        assertFalse("must not date an age from the epoch", h.contains("ago"))
    }

    @Test
    fun `an installing claim is believed while fresh and dropped when too old`() {
        assertFalse(otaApplyLooksStale(view(applyingBuild = "b", applyingSinceSeconds = now - 60)))
        assertTrue(
            otaApplyLooksStale(
                view(applyingBuild = "b",
                    applyingSinceSeconds = now - OtaState.APPLYING_STALE_SECONDS - 1)
            )
        )
        assertTrue("an undated claim survived an upgrade and cannot be dated",
            otaApplyLooksStale(view(applyingBuild = "b", applyingSinceSeconds = 0L)))
        assertFalse("nothing claimed", otaApplyLooksStale(view()))
    }

    @Test
    fun `a backward clock step does not erase a running download`() {
        assertFalse(
            "the clock moved backwards under a live 1.6 GB transfer and the screen went silent",
            otaApplyLooksStale(view(applyingBuild = "b", applyingSinceSeconds = now + 3600))
        )
    }

    @Test
    fun `only the download phase gets a number`() {
        val downloading = view(applyingBuild = "b", applyingSinceSeconds = now - 60,
            applyingStatus = OtaApply.Status.DOWNLOADING, applyingPercent = 43)
        assertEquals(43, otaProgressPercent(downloading))
        assertEquals("Downloading 43%", otaProgressLine(downloading))

        val verifying = downloading.copy(applyingStatus = OtaApply.Status.VERIFYING)
        assertEquals("a number here runs the bar backwards from 100 to 0",
            OtaState.PERCENT_UNKNOWN, otaProgressPercent(verifying))
        assertEquals("Checking the download…", otaProgressLine(verifying))
    }

    @Test
    fun `no reading yet is unknown rather than zero`() {
        assertEquals(
            OtaState.PERCENT_UNKNOWN,
            otaProgressPercent(
                view(applyingBuild = "b", applyingSinceSeconds = now - 5,
                    applyingStatus = OtaApply.Status.DOWNLOADING,
                    applyingPercent = OtaState.PERCENT_UNKNOWN)
            )
        )
        assertEquals("nothing is in flight at all",
            OtaState.PERCENT_UNKNOWN, otaProgressPercent(view()))
    }

    @Test
    fun `a stale claim is not in flight`() {
        assertFalse(
            otaApplyInFlight(
                view(applyingBuild = "b",
                    applyingSinceSeconds = now - OtaState.APPLYING_STALE_SECONDS - 1)
            )
        )
        assertTrue(otaApplyInFlight(view(applyingBuild = "b", applyingSinceSeconds = now - 60)))
    }

    @Test
    fun `the section keeps looking for one nudge window and then stops`() {
        assertTrue(otaCheckJustArmed(view(lastNudgeAtSeconds = now - 5)))
        assertFalse("past the window, there is nothing left to wait for",
            otaCheckJustArmed(view(lastNudgeAtSeconds = now - OtaScheduler.NUDGE_MIN_GAP_SECONDS)))
        assertFalse("never nudged", otaCheckJustArmed(view(lastNudgeAtSeconds = 0L)))
        assertFalse("a backward clock step must not park the repaint forever",
            otaCheckJustArmed(view(lastNudgeAtSeconds = now + 600)))
    }

    @Test
    fun `ages are coarse and never negative`() {
        assertEquals("never", otaAgo(0L, now))
        assertEquals("just now", otaAgo(now - 30, now))
        assertEquals("5 minutes ago", otaAgo(now - 300, now))
        assertEquals("1 hour ago", otaAgo(now - 3600, now))
        assertEquals("3 days ago", otaAgo(now - 3 * 86400, now))
        assertEquals("trustedNowSeconds floors the clock at build time, so this happens for real",
            "just now", otaAgo(now + 4 * 3600, now))
    }

    @Test
    fun `the next check line refuses both a negative and an absurd future`() {
        assertEquals("is not scheduled", otaIn(0L, now))
        assertEquals("is due now", otaIn(now - 10, now))
        assertEquals("in about 4 hours", otaIn(now + 4 * 3600, now))
        assertTrue(
            "a wrong clock must be named as a wrong clock, not printed as 639012 hours",
            otaIn(now + 365L * 86400, now).contains("clock looks wrong")
        )
    }

    @Test
    fun `the build line names build, device and channel`() {
        val l = otaBuildLine(view(build = "2026090100", device = "stallion", channel = "beta"))
        assertTrue(l.contains("2026090100") && l.contains("stallion") && l.contains("beta"))
        assertTrue("a blank build must not print an empty pair of brackets",
            otaBuildLine(view(build = "", device = "")).contains("unknown"))
    }

    @Test
    fun `the last check line prints the stored result verbatim`() {
        val l = otaLastCheckLine(view(lastResult = "package missing: half-finished publish"))
        assertTrue(l.contains("package missing: half-finished publish"))
        assertEquals("Last checked: never.", otaLastCheckLine(view(lastCheckAtSeconds = 0L)))
    }

    @Test
    fun `engine faults are explained in plain language`() {
        for (r in OtaEngine.Reason.values()) {
            val text = otaEngineFaultText(r)
            assertTrue("$r produced nothing", text.isNotBlank())
            assertFalse("$r leaked its enum name onto the screen: $text", text.contains(r.name))
        }
        assertTrue(otaHeadline(view(engineFault = OtaEngine.Reason.NO_CALLBACK_CLASS))
            .contains("cannot install them"))
    }

    private fun classBytes(simpleName: String): ByteArray {
        val url = OtaState::class.java.getResource("OtaState.class")
        assertTrue("cannot locate the compiled classes to scan", url != null)
        val bytes: ByteArray? = if (url!!.protocol == "jar") {
            val jarPath = java.net.URLDecoder.decode(
                url.path.substringAfter("file:").substringBefore("!"), "UTF-8"
            )
            java.util.jar.JarFile(jarPath).use { jar ->
                jar.getJarEntry("watch/rist/assistant/$simpleName.class")
                    ?.let { jar.getInputStream(it).use { s -> s.readBytes() } }
            }
        } else {
            java.io.File(java.io.File(url.toURI()).parentFile, "$simpleName.class")
                .takeIf { it.isFile }?.readBytes()
        }
        assertTrue("$simpleName.class is not on the test classpath — this scan would prove nothing",
            bytes != null && bytes.isNotEmpty())
        assertEquals("$simpleName.class is not a class file", 0xCA.toByte(), bytes!![0])
        return bytes
    }
}
