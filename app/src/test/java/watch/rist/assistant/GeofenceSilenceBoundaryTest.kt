package watch.rist.assistant

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rist.v1.DeviceRequest

class GeofenceSilenceBoundaryTest {

    @Test
    fun theGeofencePath_referencesNothingThatCanAnnounce() {
        val classes = geofencePathClasses()
        assertTrue("no compiled geofence classes found — the scan below proves nothing",
            classes.size >= 4)

        val forbidden = listOf(
            "android/speech/", "TextToSpeech", "android/media/", "MediaPlayer", "Ringtone",
            "ToneGenerator", "AudioManager",
            "android/widget/", "android/view/", "Toast", "setContentView", "startActivity",
            "android/app/Notification", "NotificationManager", "NotificationChannel",
            "Vibrator", "VibrationEffect",
            "startService", "startForegroundService", "sendBroadcast",
            "watch/rist/assistant/AlarmService", "watch/rist/assistant/AlarmActivity",
            "watch/rist/assistant/MainActivity", "watch/rist/assistant/NotificationHub",
            "watch/rist/assistant/OverlayHomeService", "watch/rist/assistant/ViewRenderer",
            "watch/rist/assistant/PlaybackService", "watch/rist/assistant/RecordService",
            "watch/rist/assistant/DeviceCommands", "watch/rist/assistant/Transcript",
        )

        for ((name, bytes) in classes) {
            val text = String(bytes, Charsets.ISO_8859_1)
            for (marker in forbidden) {
                assertFalse(
                    "$name references '$marker'. A fence must never speak or display anything: the " +
                        "handset knows the moment, the backend owns the instruction and the " +
                        "authority to run it, and a device that announced the crossing would be " +
                        "acting on an instruction it has never seen. It reports and stops — see " +
                        "Uploader.reportGeofenceCrossings, whose return type is the enforcement.",
                    text.contains(marker)
                )
            }
        }
    }

    private fun geofencePathClasses(): List<Pair<String, ByteArray>> {
        val pkg = "watch/rist/assistant/"
        val anchor = Geofences::class.java.getResource("Geofences.class")
        assertNotNull("cannot locate the compiled classes to scan", anchor)
        val url = anchor!!

        fun wanted(entry: String): Boolean =
            entry.endsWith(".class") && entry.substringAfterLast('/').startsWith("Geofence")

        if (url.protocol == "jar") {
            val jarPath = java.net.URLDecoder.decode(
                url.path.substringAfter("file:").substringBefore("!"), "UTF-8"
            )
            return java.util.jar.JarFile(jarPath).use { jar ->
                jar.entries().toList()
                    .filter { it.name.startsWith(pkg) && wanted(it.name) }
                    .map { it.name to jar.getInputStream(it).use { s -> s.readBytes() } }
            }
        }
        val dir: File? = File(url.toURI()).parentFile
        return dir?.listFiles().orEmpty()
            .filter { wanted(it.name) }
            .map { it.name to it.readBytes() }
    }

    @Test
    fun theCrossingReport_returnsNothingToSpeak() {
        val m = Uploader::class.java.declaredMethods.firstOrNull { it.name == "reportGeofenceCrossings" }
        assertNotNull("Uploader.reportGeofenceCrossings is gone — the report path is unpinned", m)
        assertEquals(
            "reportGeofenceCrossings now hands a response back to its caller. That is how a fence " +
                "acquires a voice: the next reader will speak resp.speech or dispatch resp.comms " +
                "from inside the reporter. Keep it Unit.",
            Void.TYPE, m!!.returnType
        )
    }

    private fun weatherRequest(): DeviceRequest = Uploader.buildTextRequest(
        deviceId = "dev-geo", sessionId = "sess-geo", timestamp = 1_800_000_000_000L,
        authToken = "tok", caps = DeviceProfile.capabilities(1080, 2400),
        text = "what's the weather tomorrow?"
    )

    @Test
    fun anEmptyGeofenceState_stillGoesThroughTheBuilder() {
        val plain = weatherRequest()
        val attached = Uploader.attachGeofenceState(plain, emptyList())
        assertNotSame(
            "attachGeofenceState short-circuited on an empty list. An empty geofence_state is not " +
                "nothing to say — it is the report that triggers a re-arm, and without it a fence " +
                "lost to one dropped packet is lost permanently.",
            plain, attached
        )
        assertEquals(0, attached.geofenceStateCount)
    }

    @Test
    fun theHeldIds_goOutVerbatim() {
        val req = Uploader.attachGeofenceState(weatherRequest(), listOf("fence-home", "fence-work"))
        val parsed = DeviceRequest.parseFrom(req.toByteArray())
        assertEquals(listOf("fence-home", "fence-work"), parsed.geofenceStateList)
    }

    private fun crossing(atMs: Long) = GeofenceCrossing(
        id = Geofences.crossingId("fence-home", atMs),
        fenceId = "fence-home", direction = "enter", atMs = atMs,
        lat = 47.6062, lon = -122.3321, accuracyM = 22, fixTimeMs = atMs + 61_000L,
    )

    @Test
    fun aCrossingIsPutOnTheWireWithItsOwnTimeAndItsAccuracy() {
        val at = 1_800_000_000_000L
        val req = Uploader.attachGeofenceEvents(
            weatherRequest(), listOf(crossing(at)), "America/Los_Angeles", at + 4L * 3_600_000L
        )
        val parsed = DeviceRequest.parseFrom(req.toByteArray())
        assertEquals(1, parsed.geofenceEventsCount)
        val e = parsed.getGeofenceEvents(0)

        assertEquals("fence-home", e.fenceId)
        assertEquals("enter", e.direction)
        assertEquals(
            "at_ms was rewritten. A crossing delivered four hours late must SAY it is four hours " +
                "late, so the backend can decline to text somebody \"I'm home\" at midnight about " +
                "a 7pm arrival. Papering over it turns a visible non-event into a wrong text.",
            at, e.atMs
        )
        assertEquals("accuracy_m is what the backend judges the report with", 22, e.fix.accuracyM)
        assertEquals(47.6062, e.fix.lat, 0.000001)
        assertTrue("the crossing id is empty — the backend drops it, silently and forever",
            e.id.isNotBlank())
    }

    @Test
    fun anOrdinaryRequestWithNothingOwed_isByteIdenticalToOneThatNeverConsideredFences() {
        val plain = weatherRequest()
        val throughTheSeam = Uploader.attachGeofenceEvents(plain, emptyList(), "UTC", 0L)
        assertSame(plain, throughTheSeam)
        assertArrayEquals(plain.toByteArray(), throughTheSeam.toByteArray())
    }

    @Test
    fun aCrossingReportIsValidWithNoUtteranceAtAll() {
        val at = 1_800_000_000_000L
        val req = Uploader.attachGeofenceState(
            Uploader.attachGeofenceEvents(
                DeviceRequest.newBuilder()
                    .setDeviceId("dev-geo").setSessionId("sess-geo").setTimestamp(at)
                    .setRequestId("req-geo").setAuthToken("tok")
                    .setCaps(DeviceProfile.capabilities(1080, 2400))
                    .build(),
                listOf(crossing(at - 120_000L)), "UTC", at
            ),
            listOf("fence-home")
        )
        val parsed = DeviceRequest.parseFrom(req.toByteArray())
        assertEquals(DeviceRequest.InputCase.INPUT_NOT_SET, parsed.inputCase)
        assertEquals(1, parsed.geofenceEventsCount)
        assertEquals(at, parsed.timestamp)
    }

    @Test
    fun maxGeofencesIsAdvertised_andIsNotZero() {
        val caps = DeviceProfile.capabilities(1080, 2400)
        assertEquals(
            "caps.max_geofences is 0, which tells the backend this build cannot do place triggers " +
                "at all. It will then refuse to create one — correctly, but silently as far as " +
                "this side is concerned.",
            Geofences.MAX_FENCES, caps.maxGeofences
        )
        assertTrue(caps.maxGeofences > 0)
        assertEquals("the backend caps a user at 16 and sends min(ours, theirs)", 16, caps.maxGeofences)
        assertTrue("place triggers need at least schema v11", caps.schemaVersion >= 11)
    }
}
