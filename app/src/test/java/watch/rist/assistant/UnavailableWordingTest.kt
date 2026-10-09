package watch.rist.assistant

import android.content.Context
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A failure the person cannot fix says "<Feature> is currently unavailable." and nothing else; one
 * they can fix says what to do. No status codes, causes or "went wrong".
 */
@RunWith(RobolectricTestRunner::class)
class UnavailableWordingTest {

    private lateinit var server: MockWebServer
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun start() {
        Config.setDeployDefaultsForTest("", "")
        server = MockWebServer()
        server.start()
        Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
        Config.setEnrolRevoked(ctx, false)
        Config.setCredentialRejected(ctx, false)
        Config.clearBillingLapse(ctx)
        StreamingCancel.resetForTest()
    }

    @After
    fun stop() {
        Config.setEnrolRevoked(ctx, false)
        Config.setCredentialRejected(ctx, false)
        Config.clearDeployDefaultsForTest()
        runCatching { server.shutdown() }
    }

    private val diagnostic = Regex(
        """\d{3}|error|wrong|trouble|garbled|failed|couldn't|can't|cannot|not working|sorry""",
        RegexOption.IGNORE_CASE,
    )

    private fun assertPlain(line: String) =
        assertFalse("a debugging report reached the person: \"$line\"", diagnostic.containsMatchIn(line))

    @Test
    fun `every server-side refusal the person cannot fix says the assistant is unavailable`() {
        for (code in listOf(404, 429, 500, 502, 503, 504, 418)) {
            server.enqueue(MockResponse().setResponseCode(code))
            val said = Uploader(ctx).also { it.sendText("hi") }.lastFailure
            assertEquals("HTTP $code", Unavailable.ASSISTANT, said)
            assertPlain(said)
        }
    }

    @Test
    fun `a 401 or a revocation tells the person to pair again`() {
        assertEquals(Unavailable.PAIR_AGAIN, Uploader.httpFailure(401, null, revoked = false))
        assertEquals(Unavailable.REMOVED, Uploader.httpFailure(403, null, revoked = true))
        assertEquals(Unavailable.ASSISTANT, Uploader.httpFailure(403, null, revoked = false))
        assertEquals("Pair it again.", Uploader.httpFailure(401, "pair it again", revoked = false))
    }

    @Test
    fun `a dropped send is unavailable, or the fix when the phone is offline`() {
        val e = java.net.ConnectException("refused")
        assertEquals(Unavailable.ASSISTANT, Uploader.transportFailure(e, offline = false))
        assertEquals(Unavailable.NO_NETWORK, Uploader.transportFailure(e, offline = true))
        for (t in listOf(javax.net.ssl.SSLException("x"), java.net.UnknownHostException("x"),
                com.google.protobuf.InvalidProtocolBufferException("x"), IllegalStateException("x"))) {
            assertEquals(t.javaClass.simpleName, Unavailable.ASSISTANT, Uploader.transportFailure(t, offline = false))
        }
        // The backend may already have acted: the honest outcome stays.
        assertEquals(Uploader.MAY_HAVE_HAPPENED,
            Uploader.transportFailure(java.net.SocketTimeoutException("x"), offline = true))
    }

    @Test
    fun `an empty reply is unavailable`() {
        assertEquals(Unavailable.ASSISTANT, StreamingStatus.failureFor(StreamingStatus.ENDING_EMPTY))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/x-protobuf"))
        assertEquals(Unavailable.ASSISTANT, Uploader(ctx).also { it.sendText("hi") }.lastFailure)
    }

    @Test
    fun `no assistant service tells the person where to connect one`() {
        Config.setBackendEndpoint(ctx, "")
        Config.setDeployDefaultsForTest("", "")
        val said = Uploader(ctx).also { it.sendText("hi") }.lastFailure
        assertTrue(said, said == Unavailable.NOT_PAIRED || said == Unavailable.BAD_ADDRESS)
    }

    @Test
    fun `every line the person sees is a finished sentence that failureLine passes through`() {
        for (line in listOf(Unavailable.ASSISTANT, Unavailable.NO_NETWORK, Unavailable.NOT_PAIRED,
                Unavailable.BAD_ADDRESS, Unavailable.PAIR_AGAIN, Unavailable.REMOVED,
                Unavailable.TOO_LONG, Unavailable.MESSAGES, Uploader.MAY_HAVE_HAPPENED)) {
            assertEquals(line, MainActivity.failureLine(line))
        }
    }

    @Test
    fun `a server line is shown as a sentence`() {
        assertEquals("Renew at x.com.", Unavailable.sentence("renew at x.com"))
        assertEquals("Done!", Unavailable.sentence(" Done! "))
        assertEquals("", Unavailable.sentence("  "))
    }

    @Test
    fun `voicemail the server cannot give says voicemail is unavailable`() {
        // Robolectric has no keystore: hold the token in plain test prefs for this one test.
        Config.usePlainPrefsForTest(ctx)
        Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
        Config.setAuthToken(ctx, "t0k3n-for-test")
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            val text = VoicemailTranscript.fetch(ctx, "abc123")
            assertEquals(VoicemailTranscript.Result.Failed(Unavailable.VOICEMAIL), text)
            server.enqueue(MockResponse().setResponseCode(502))
            val audio = VoicemailAudio.fetch(ctx, "abc124")
            assertEquals(VoicemailAudio.Result.Failed(Unavailable.VOICEMAIL), audio)
        } finally {
            Config.setAuthToken(ctx, "")
            Config.forgetPrefsForTest()
        }
    }

    @Test
    fun `an unsent text names the fix only when it is airplane mode`() {
        assertEquals("Not sent to Sam. Turn off airplane mode and try again.",
            SmsResultReceiver.notSentLine("Sam", SmsManager.RESULT_ERROR_RADIO_OFF))
        for (code in listOf(SmsManager.RESULT_ERROR_NO_SERVICE, SmsManager.RESULT_ERROR_NULL_PDU,
                SmsManager.RESULT_ERROR_GENERIC_FAILURE, 99)) {
            val line = SmsResultReceiver.notSentLine("Sam", code)
            assertEquals("Not sent to Sam. ${Unavailable.TEXTING}", line)
            assertPlain(line)
        }
    }

    @Test
    fun `pairing that the person cannot fix says pairing is unavailable`() {
        for (r in listOf(Enrolment.PairResult.NETWORK, Enrolment.PairResult.NOT_GRANTED,
                Enrolment.PairResult.STORE_FAILED)) {
            val line = Enrolment.explainPair(r)
            assertTrue("$r: $line", line.startsWith(Unavailable.PAIRING))
            assertPlain(line)
        }
    }

    @Test
    fun `update, place and attachment lines carry no cause`() {
        assertEquals(Unavailable.UPDATES, OTA_UNAVAILABLE)
        assertPlain(GeofenceConsent.REFUSED_LINE)
        val res = ctx.resources
        for (id in listOf(R.string.ota_offer_refused, R.string.nav_routing_unavailable,
                R.string.attach_no_bytes, R.string.attach_bad_format, R.string.attach_empty_note,
                R.string.attach_reason_unreadable, R.string.attach_reason_unknown,
                R.string.attach_reason_too_big, R.string.attach_reason_absent,
                R.string.boxes_refresh_failed, R.string.boxes_create_failed, R.string.web_failed,
                R.string.web_no_engine, R.string.link_failed, R.string.call_failed,
                R.string.camera_unavailable, R.string.photo_save_failed)) {
            val line = res.getString(id)
            assertTrue(line, line.contains("currently unavailable", ignoreCase = true))
            assertPlain(line)
        }
        assertEquals("Directions are currently unavailable.", res.getString(R.string.nav_routing_unavailable))
    }
}
