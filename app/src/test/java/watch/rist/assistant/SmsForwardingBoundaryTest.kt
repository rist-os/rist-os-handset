package watch.rist.assistant

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rist.v1.DeviceRequest

class SmsForwardingBoundaryTest {

    @Test
    fun theSmsReadPath_referencesNothingThatCanSend() {
        val classes = readPathClasses()
        assertTrue("no compiled read-path classes found — the scan below proves nothing",
            classes.size >= 3)

        val forbidden = listOf(
            "okhttp3/", "java/net/", "javax/net/", "HttpURLConnection", "URLConnection",
            "watch/rist/assistant/Uploader", "watch/rist/assistant/PushService",
            "androidx/work/", "android/app/job/", "android/app/AlarmManager",
            "startService", "startForegroundService", "startActivity", "sendBroadcast",
        )

        for ((name, bytes) in classes) {
            // ISO-8859-1 is byte-preserving; UTF-8 would mangle constant-pool bytes.
            val text = String(bytes, Charsets.ISO_8859_1)
            for (marker in forbidden) {
                assertFalse(
                    "$name references '$marker'. Reading a text must not cause anything to leave " +
                        "this device from inside the read: the sender consented to nothing and " +
                        "could not be asked. A text reaches the backend only on a request the USER asked for, " +
                        "and only from the caller's side of the seam — see Uploader.smsToCarry.",
                    text.contains(marker)
                )
            }
        }
    }

    private fun readPathClasses(): List<Pair<String, ByteArray>> {
        val prefixes = listOf("SmsInbox", "SmsReceiver", "InboundSms", "SmsRead")
        val anchor = SmsInbox::class.java.getResource("SmsInbox.class")
        assertNotNull("cannot locate the compiled classes to scan", anchor)
        val url = anchor!!

        fun wanted(entry: String): Boolean =
            entry.endsWith(".class") &&
                prefixes.any { entry.substringAfterLast('/').startsWith(it) }

        if (url.protocol == "jar") {
            val jarPath = java.net.URLDecoder.decode(
                url.path.substringAfter("file:").substringBefore("!"), "UTF-8"
            )
            return java.util.jar.JarFile(jarPath).use { jar ->
                jar.entries().toList()
                    .filter { wanted(it.name) }
                    .map { it.name to jar.getInputStream(it).use { s -> s.readBytes() } }
            }
        }
        val dir: File? = File(url.toURI()).parentFile
        return dir?.listFiles().orEmpty()
            .filter { wanted(it.name) }
            .map { it.name to it.readBytes() }
    }

    private fun queued(n: Int): List<InboundSms> = (1..n).map {
        InboundSms(
            id = SmsInbox.stableId("+15555550$it", 1_700_000_000_000L + it, "code $it"),
            from = "+15555550$it",
            body = "your verification code is 00$it",
            sentAtMs = 1_700_000_000_000L + it,
        )
    }

    private fun weatherRequest(): DeviceRequest = Uploader.buildTextRequest(
        deviceId = "dev-sms", sessionId = "sess-sms", timestamp = 1_700_000_000_000L,
        authToken = "tok", caps = DeviceProfile.capabilities(1080, 2400),
        text = "what's the weather tomorrow?"
    )

    @Test
    fun anOrdinaryRequest_carriesNoInboundSms_evenWithAFullQueue() {
        val pending = queued(3)
        val carried = Uploader.smsToCarry(optedIn = false, alreadyOnRequest = 0, pending = pending)
        assertTrue(
            "an unrelated request tried to carry ${carried.size} texts. Nothing about the weather " +
                "needs someone else's messages, and shipping them is the violation itself rather " +
                "than a step towards one.",
            carried.isEmpty()
        )

        val req = Uploader.attachInboundSms(weatherRequest(), carried)
        assertEquals(0, req.inboundSmsCount)
        assertEquals(0, DeviceRequest.parseFrom(req.toByteArray()).inboundSmsCount)
    }

    @Test
    fun anOrdinaryRequest_isByteIdenticalToOneThatNeverConsideredSms() {
        val plain = weatherRequest()
        val throughTheSeam = Uploader.attachInboundSms(plain, emptyList())
        assertSame(plain, throughTheSeam)
        assertArrayEquals(plain.toByteArray(), throughTheSeam.toByteArray())
    }

    @Test
    fun anOptedInRequest_carriesTheQueue() {
        val pending = queued(2)
        val carried = Uploader.smsToCarry(optedIn = true, alreadyOnRequest = 0, pending = pending)
        assertEquals(2, carried.size)

        val req = Uploader.attachInboundSms(weatherRequest(), carried)
        val parsed = DeviceRequest.parseFrom(req.toByteArray())
        assertEquals(2, parsed.inboundSmsCount)
        assertEquals(pending[0].id, parsed.getInboundSms(0).id)
        assertEquals(pending[0].from, parsed.getInboundSms(0).from)
        assertEquals(pending[0].body, parsed.getInboundSms(0).body)
        assertEquals(pending[0].sentAtMs, parsed.getInboundSms(0).sentAtMs)
    }

    @Test
    fun aRequestThatAlreadyCarriesTexts_isNotToppedUp() {
        val carried = Uploader.smsToCarry(optedIn = true, alreadyOnRequest = 1, pending = queued(4))
        assertTrue(carried.isEmpty())
    }

    @Test
    fun onlyTheMessageToolReleasesTexts() {
        assertTrue(Uploader.releasesInboundSms("message"))
        assertTrue("tool ids arrive as the backend spells them", Uploader.releasesInboundSms(" Message "))

        for (id in listOf("map", "audiobooks", "podcasts", "calendar", "weather", "", "   ",
                          "call", "messages", "sms", "messaging", "inbox", "texts")) {
            assertFalse("tool_id '$id' released the user's texts", Uploader.releasesInboundSms(id))
        }
    }
}
