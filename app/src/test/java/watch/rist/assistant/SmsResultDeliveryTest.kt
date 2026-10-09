package watch.rist.assistant

import android.os.Looper
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The radio's verdict on a text reaches the backend even if the app died after sending it, and
 * reaches it straight away, not with whatever the person happens to say next.
 */
@RunWith(RobolectricTestRunner::class)
class SmsResultDeliveryTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    private fun source(path: String): File =
        File(path).takeIf { it.exists() } ?: File("app/$path")

    @Before
    fun setUp() {
        Config.usePlainPrefsForTest(app)
        SmsResultReceiver.Parts.clearForTest(app)
        CommsResults.ack(app, CommsResults.pending(app).map { it.correlationId })
        server = MockWebServer()
        server.start()
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(app, server.url("/v1/device").toString())
    }

    @After
    fun tidy() {
        server.shutdown()
        Config.forgetPrefsForTest()
    }

    private fun ackFor(vararg ids: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/x-protobuf")
        .setBody(Buffer().write(DeviceResponse.newBuilder().setRequestId("r").setIsFinal(true)
            .addAllCommsResultsAck(ids.toList()).build().toByteArray()))

    @Test
    fun `the receiver is declared in the manifest and not exported`() {
        val manifest = source("src/main/AndroidManifest.xml").readText()
        val tag = Regex("""<receiver[^>]*\.SmsResultReceiver"[^>]*>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.value
        assertNotNull("SmsResultReceiver is not declared: a verdict after the process dies is lost", tag)
        assertTrue("SmsResultReceiver must not be exported", tag!!.contains("""android:exported="false""""))
    }

    @Test
    fun `nothing registers the sent-result receiver at runtime`() {
        // Registered twice, every verdict would be handled twice.
        val offenders = source("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .filter { f ->
                val t = f.readText()
                Regex("""registerReceiver\([^)]*(SmsResultReceiver|ACTION_SENT)""", RegexOption.DOT_MATCHES_ALL)
                    .containsMatchIn(t) || t.contains("SmsResultReceiver.register(")
            }.map { it.name }.toList()
        assertTrue("runtime registration of the SMS result receiver: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the sent intent names the receiver explicitly`() {
        val i = shadowOf(SmsResultReceiver.sentIntent(app, "c-x", "Alice", 0, 1)).savedIntent
        assertEquals(SmsResultReceiver::class.java.name, i.component?.className)
        assertEquals(SmsResultReceiver.ACTION_SENT, i.action)
    }

    @Test
    fun `a verdict is carried to the backend at once and cleared when acknowledged`() {
        server.enqueue(ackFor("c-now"))
        val i = shadowOf(SmsResultReceiver.sentIntent(app, "c-now", "Alice", 0, 1)).savedIntent
        app.sendOrderedBroadcast(i, null, null, null, SmsManager.RESULT_ERROR_RADIO_OFF, null, null)
        shadowOf(Looper.getMainLooper()).idle()

        val recorded = server.takeRequest(30, TimeUnit.SECONDS)
        assertNotNull("no check-in carried the result", recorded)
        val req = DeviceRequest.parseFrom(recorded!!.body.readByteArray())
        assertTrue("a check-in must say nothing", req.text.isBlank() && !req.hasAudio())
        val r = req.commsResultsList.single { it.correlationId == "c-now" }
        assertEquals("send_sms", r.action)
        assertFalse(r.performed)
        assertEquals("the radio is off", r.error)

        val until = System.currentTimeMillis() + 30_000
        while (CommsResults.pending(app).isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("the acknowledged result is still queued", CommsResults.pending(app).isEmpty())
    }

    @Test
    fun `nothing queued, nothing sent`() {
        Uploader(app).reportCommsResults()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a check-in that fails keeps the result for the next request`() {
        server.enqueue(MockResponse().setResponseCode(500))
        CommsResults.record(app, "c-keep", "send_sms", true)
        Uploader(app).reportCommsResults()
        assertEquals(1, server.requestCount)
        assertTrue(CommsResults.pending(app).single { it.correlationId == "c-keep" }.performed)
    }

    @Test
    fun `a verdict with no correlation id still decides on its last part`() {
        assertEquals(SmsResultReceiver.Verdict.PENDING, SmsResultReceiver.Parts.settle(app, "", 0, 2, true))
        assertEquals(SmsResultReceiver.Verdict.SENT, SmsResultReceiver.Parts.settle(app, "", 1, 2, true))
        assertEquals(SmsResultReceiver.Verdict.FAILED,
            SmsResultReceiver.Parts.settle(app, "", 0, 1, false))
    }
}
