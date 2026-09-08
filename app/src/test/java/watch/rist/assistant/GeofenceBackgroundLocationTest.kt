package watch.rist.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceBackgroundLocationTest {

    @Test
    fun onlyAnAnsweredYesWithTheGrantBehindItLetsAFenceBeEvaluated() {
        val table = listOf(
            Triple(GeofenceConsent.Choice.UNANSWERED, false, GeofenceConsent.Status.UNANSWERED),
            Triple(GeofenceConsent.Choice.UNANSWERED, true, GeofenceConsent.Status.UNANSWERED),
            Triple(GeofenceConsent.Choice.OFF, false, GeofenceConsent.Status.OFF),
            Triple(GeofenceConsent.Choice.OFF, true, GeofenceConsent.Status.OFF),
            Triple(GeofenceConsent.Choice.ON, false, GeofenceConsent.Status.REFUSED),
            Triple(GeofenceConsent.Choice.ON, true, GeofenceConsent.Status.ON),
        )
        for ((choice, granted, expected) in table) {
            assertEquals(
                "status($choice, osGranted=$granted)",
                expected, GeofenceConsent.status(choice, granted)
            )
        }
        for ((choice, granted, expected) in table) {
            assertEquals(
                "evaluationAllowed must be true for ON and ONLY for ON — $choice/$granted",
                expected == GeofenceConsent.Status.ON,
                GeofenceConsent.evaluationAllowed(GeofenceConsent.status(choice, granted))
            )
        }
    }

    @Test
    fun holdingTheGrantWithNoAnswerBehindItIsNotConsent() {
        assertEquals(
            "A background-location grant with no stored answer was read as consent. The only thing " +
                "that can create that grant is our own Device Owner call, so this would be the app " +
                "granting itself a permission and then citing the grant as the user's permission.",
            GeofenceConsent.Status.UNANSWERED,
            GeofenceConsent.status(GeofenceConsent.Choice.UNANSWERED, osGranted = true)
        )
        assertFalse(
            GeofenceConsent.evaluationAllowed(
                GeofenceConsent.status(GeofenceConsent.Choice.UNANSWERED, osGranted = true)
            )
        )
    }

    @Test
    fun everyRefusalCarriesItsOwnReasonAndTheAllowedStateCarriesNone() {
        val reasons = listOf(
            GeofenceConsent.Status.UNANSWERED,
            GeofenceConsent.Status.OFF,
            GeofenceConsent.Status.REFUSED,
        ).map { GeofenceConsent.refusalReason(it) }

        for (r in reasons) assertTrue("a refusal was logged with an empty reason", r.isNotBlank())
        assertEquals("the three refusals share a reason string", 3, reasons.toSet().size)
        assertEquals(
            "the allowed state produced a refusal reason, which would read as a failure in the log",
            "", GeofenceConsent.refusalReason(GeofenceConsent.Status.ON)
        )
    }

    @Test
    fun theQuestionIsDueOnlyOnceAFenceHasActuallyBeenArmed() {
        val u = GeofenceConsent.Choice.UNANSWERED
        val now = 1_800_000_000_000L

        assertFalse(
            "the question went up on a handset that has never been asked to watch a place",
            GeofenceConsent.shouldAsk(u, asks = 0, lastAskedAt = 0L, now = now, fenceWanted = false)
        )
        assertTrue(
            GeofenceConsent.shouldAsk(u, asks = 0, lastAskedAt = 0L, now = now, fenceWanted = true)
        )
        assertFalse(
            "the question came back the same hour it was dismissed — that is a wall, not a question",
            GeofenceConsent.shouldAsk(u, 1, now - 3_600_000L, now, fenceWanted = true)
        )
        assertTrue(
            "the question never came back after a day",
            GeofenceConsent.shouldAsk(u, 1, now - GeofenceConsent.RE_ASK_GAP_MS, now, fenceWanted = true)
        )
        assertFalse(
            "the question outlived MAX_ASKS dismissals; three closes are an answer",
            GeofenceConsent.shouldAsk(u, GeofenceConsent.MAX_ASKS, 0L, now, fenceWanted = true)
        )
        for (answered in listOf(GeofenceConsent.Choice.ON, GeofenceConsent.Choice.OFF)) {
            assertFalse(
                "the question was asked again after it had been answered ($answered)",
                GeofenceConsent.shouldAsk(answered, 0, 0L, now, fenceWanted = true)
            )
        }
        assertTrue(
            "a clock that jumped backwards silenced the question until real time caught up",
            GeofenceConsent.shouldAsk(u, 1, now + 5L * 86_400_000L, now, fenceWanted = true)
        )
    }

    @Test
    fun theWatcherRefusesToEvaluateWithoutTheBackgroundLocationConsent() {
        val bytes = classBytes("GeofenceWatcher")
        assertTrue(
            "GeofenceWatcher no longer consults GeofenceConsent. Background evaluation would then " +
                "run on whatever the OS happens to have granted, which on this build is nothing " +
                "the user was ever asked about.",
            bytes.references("watch/rist/assistant/GeofenceConsent")
        )
        assertTrue(
            "GeofenceWatcher no longer calls evaluationAllowed — the gate is gone even if the " +
                "reference survives.",
            bytes.references("evaluationAllowed")
        )
        assertTrue(
            "GeofenceWatcher stopped dropping its fences when background evaluation is not " +
                "allowed. Holding them is worse than not: the empty geofence_state that dropAll " +
                "produces is the backend's ONLY signal that the trigger cannot run.",
            bytes.references("dropAll")
        )
    }

    @Test
    fun theManifestDeclaresBackgroundLocationAndTheKioskDoesNotSilentlyGrantIt() {
        val manifest = sourceFile("app/src/main/AndroidManifest.xml")
        assertTrue(
            "ACCESS_BACKGROUND_LOCATION is not declared. The geofence poll runs from a broadcast " +
                "receiver, so every fix it takes comes back null the moment the uid is not held in " +
                "a foreground state by some unrelated foreground service.",
            manifest.contains("android.permission.ACCESS_BACKGROUND_LOCATION")
        )

        val kiosk = sourceFile("app/src/main/java/watch/rist/assistant/KioskManager.kt")
        assertFalse(
            "ACCESS_BACKGROUND_LOCATION appears in KioskManager's blanket self-grant. As Device " +
                "Owner that grants it silently, with no dialog and no answer, widening a consent " +
                "the user gave for a one-shot foreground fix into continuous background " +
                "evaluation. The grant belongs behind GeofenceConsent.apply, on the user's own " +
                "answer; GeofenceConsent.reassertGrant is the only thing KioskManager may call.",
            kiosk.contains("permission.ACCESS_BACKGROUND_LOCATION")
        )
    }

    @Test
    fun theNetworkFallbackFiresOnlyWhenTheNetworkLocationConsentIsOn() {
        assertTrue(
            "the fallback refused with the consent ON and the platform value applied — the indoor " +
                "case stays broken and nothing says so",
            LocationProvider.networkFallbackPermitted(
                NetworkLocationConsent.status(
                    NetworkLocationConsent.Choice.ON, NetworkLocationConsent.VALUE_PROXY
                )
            )
        )
    }

    @Test
    fun theNetworkFallbackDoesNotFireWhenTheConsentIsOff() {
        val off = NetworkLocationConsent.status(
            NetworkLocationConsent.Choice.OFF, NetworkLocationConsent.VALUE_OFF
        )
        assertEquals(NetworkLocationConsent.Status.OFF, off)
        assertFalse(
            "a handset whose owner declined network location asked the network provider anyway. " +
                "That is a lookup of the wireless networks around them, sent away, on a question " +
                "they answered no to.",
            LocationProvider.networkFallbackPermitted(off)
        )
    }

    @Test
    fun theNetworkFallbackDoesNotFireOnAnUnansweredOrRefusedConsent() {
        val unanswered = NetworkLocationConsent.status(
            NetworkLocationConsent.Choice.UNANSWERED, NetworkLocationConsent.VALUE_OFF
        )
        assertEquals(NetworkLocationConsent.Status.UNANSWERED, unanswered)
        assertFalse(
            "an unanswered question was treated as a yes",
            LocationProvider.networkFallbackPermitted(unanswered)
        )

        val refused = NetworkLocationConsent.status(
            NetworkLocationConsent.Choice.ON, NetworkLocationConsent.VALUE_OFF
        )
        assertEquals(NetworkLocationConsent.Status.REFUSED, refused)
        assertFalse(
            "the fallback fired on a REFUSED consent, spending a timeout on a provider the " +
                "platform never switched on",
            LocationProvider.networkFallbackPermitted(refused)
        )
    }

    @Test
    fun theFallbackIsWiredIntoTheAcquisitionPath() {
        val bytes = classBytes("LocationProvider")
        assertTrue(
            "LocationProvider no longer consults NetworkLocationConsent, so the network fallback " +
                "is either gone or ungated. Ungated is the worse of the two.",
            bytes.references("NetworkLocationConsent")
        )
        assertTrue(
            "LocationProvider no longer calls networkFallbackPermitted — the gate on the fallback " +
                "has been removed even though the predicate still compiles.",
            bytes.references("networkFallbackPermitted")
        )
        assertEquals(
            "the network fallback's own budget changed. It is deliberately separate from the " +
                "caller's timeout so that adding the fallback did not shorten the GPS attempt and " +
                "quietly trade the outdoor case for the indoor one.",
            4_000L, LocationProvider.NETWORK_TIMEOUT_MS
        )
    }

    private fun homeFence(side: GeofenceSide?, pending: GeofenceSide? = null, pendingAt: Long = 0L) =
        Geofence(
            id = "fence-home", lat = 47.6062, lon = -122.3321, radiusM = 150,
            direction = "enter", dwellS = 60, pollS = 120, expiresEpochS = 0L,
            requireExitFirst = false, label = "home",
            side = side, pendingSide = pending, pendingSinceMs = pendingAt,
        )

    @Test
    fun anIndoorNetworkFixConfirmsTheDwellAGpsFixStarted() {
        val t0 = 1_800_000_000_000L
        val started = Geofences.step(homeFence(GeofenceSide.OUTSIDE), 47.6062, -122.3321, 12f, t0)
        assertEquals(GeofenceSide.INSIDE, started.fence.pendingSide)
        assertEquals(t0, started.fence.pendingSinceMs)
        assertNull("a boundary crossing is not yet an arrival", started.crossing)

        val confirmed = Geofences.step(started.fence, 47.6062, -122.3321, 10f, t0 + 61_000L)
        assertNotNull(
            "the indoor fix did not confirm the dwell, so the arrival is still owed and will be " +
                "reported whenever the user next goes OUTSIDE — the headline defect, verbatim",
            confirmed.crossing
        )
        val crossing = confirmed.crossing!!
        assertEquals("enter", crossing.direction)
        assertEquals(
            "at_ms is when the crossing HAPPENED — the first fix on the new side, out on the " +
                "drive — not the moment indoors that confirmed it",
            t0, crossing.atMs
        )
        assertEquals(GeofenceSide.INSIDE, confirmed.fence.side)
    }

    @Test
    fun aFallbackFixAdvancesTheStateExactlyAsAGpsFixDoes() {
        val t = 1_800_000_000_000L
        val fence = homeFence(GeofenceSide.OUTSIDE)
        val gps = Geofences.step(fence, 47.6062, -122.3321, 8f, t)
        val network = Geofences.step(fence, 47.6062, -122.3321, 8f, t)
        assertEquals(
            "two identical fixes produced different fence state — something has started reading " +
                "more into a fix than its coordinates and its accuracy",
            gps.fence, network.fence
        )

        assertTrue(Geofences.accuracyUsable(10f, 150))
        assertTrue(Geofences.accuracyUsable(75f, 150))
        assertFalse(
            "a fix coarser than the fence was accepted; ±250 m 'inside' a 150 m fence could be " +
                "the next street",
            Geofences.accuracyUsable(250f, 150)
        )
        assertFalse(
            "accuracy 0 means the platform gave no accuracy, which is UNKNOWN, not perfect",
            Geofences.accuracyUsable(0f, 150)
        )
    }

    private fun ByteArray.references(marker: String): Boolean =
        String(this, Charsets.ISO_8859_1).contains(marker)

    private fun classBytes(simpleName: String): ByteArray {
        val entry = "watch/rist/assistant/$simpleName.class"
        val url = Geofences::class.java.getResource("Geofences.class")
        assertNotNull("cannot locate the compiled classes to scan", url)

        val bytes: ByteArray? = if (url!!.protocol == "jar") {
            val jarPath = java.net.URLDecoder.decode(
                url.path.substringAfter("file:").substringBefore("!"), "UTF-8"
            )
            java.util.jar.JarFile(jarPath).use { jar ->
                jar.getJarEntry(entry)?.let { jar.getInputStream(it).use { s -> s.readBytes() } }
            }
        } else {
            File(File(url.toURI()).parentFile, "$simpleName.class").takeIf { it.isFile }?.readBytes()
        }

        assertNotNull("$entry is not on the test classpath — this scan would prove nothing", bytes)
        assertTrue("$entry is empty", bytes!!.isNotEmpty())
        assertEquals("$entry is not a class file", 0xCA.toByte(), bytes[0])
        return bytes
    }

    private fun sourceFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, relative)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
