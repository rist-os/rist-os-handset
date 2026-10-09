package watch.rist.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.telephony.SmsManager
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import rist.v1.CommsCommand
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import rist.v1.Speech
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A turn is the phone's, not the home screen's: leaving home while it is out (a tile, the
 * Messages app) must not leave its entry at "waiting for a reply", and must not keep the text the
 * assistant said it sent from being sent.
 */
@RunWith(RobolectricTestRunner::class)
class TurnSurvivesNavigationTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    @Volatile private var replies = CountDownLatch(1)
    /** Each test's own: device commands are applied once per request id, process-wide. */
    private val cid = "sms-" + java.util.UUID.randomUUID()
    @Volatile private var reply: DeviceResponse = sentReply(cid)

    private fun sentReply(cid: String, number: String = "+12065550100") = DeviceResponse.newBuilder()
        .setRequestId("req-$cid")
        .setIsFinal(true)
        .setSpeech(Speech.newBuilder().setText("Sent."))
        .setComms(CommsCommand.newBuilder().setAction("send_sms").setNumber(number)
            .setBody("on my way").setCorrelationId(cid))
        .build()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS, Manifest.permission.RECORD_AUDIO)
        Config.usePlainPrefsForTest(app)
        TurnRunner.resetForTest()
        Transcript.clearForTest(app)
        StreamingCancel.resetForTest()
        Config.setFeatures(app, "")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val req = DeviceRequest.parseFrom(request.body.readByteArray())
                // Only a turn gets the reply; the home screen's own check-ins get nothing.
                if (request.path != "/v1/device" || req.text.isBlank()) return MockResponse().setResponseCode(404)
                replies.await(30, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/x-protobuf")
                    .setBody(Buffer().write(reply.toByteArray()))
            }
        }
        server.start()
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(app, server.url("/v1/device").toString())
        // Past the send rate limit, which counts from boot.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
    }

    @After
    fun tidy() {
        replies.countDown()
        server.shutdown()
        TurnRunner.resetForTest()
        Transcript.clearForTest(app)
        Config.forgetPrefsForTest()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
            settle()
        }
    }

    private fun home(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup().also { settle() }

    private fun type(a: MainActivity, words: String) {
        a.findViewById<TextView>(R.id.textInput).text = words
        a.findViewById<android.view.View>(R.id.sendButton).performClick()
        settle()
    }

    private fun entry() = Transcript.all(app).single()

    /**
     * The reply's text went to the radio, or the backend will be told why not. The test radio
     * refuses every send, so here it is the second: "could not be handed to the radio".
     */
    private fun textHandled(cid: String): Boolean {
        val sm = app.getSystemService(SmsManager::class.java)
        if (sm != null && shadowOf(sm).lastSentTextMessageParams?.destinationAddress == "+12065550100") return true
        return CommsResults.pending(app).any { it.correlationId == cid }
    }

    private fun feedText(a: Activity): String {
        val out = StringBuilder()
        fun walk(v: android.view.View) {
            if (v is TextView) out.append(v.text).append('\n')
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.findViewById(R.id.replyContainer))
        return out.toString()
    }

    @Test
    fun `a typed turn answered while a tile is in front closes its entry and sends the text`() {
        val home = home()
        type(home.get(), "text Alice on my way")
        assertEquals(EntryState.WAITING, entry().state)

        // A tile opens over home: home is stopped while the turn is out.
        home.pause().stop()
        replies.countDown()
        waitFor("the entry to be answered") { entry().state == EntryState.ANSWERED }

        assertEquals("Sent.", entry().answer)
        assertTrue("the reply's text was never handled", textHandled(cid))

        home.restart().start().resume()
        settle()
        assertFalse(feedText(home.get()).contains("waiting for a reply"))
        assertTrue(feedText(home.get()).contains("Sent."))
    }

    @Test
    fun `a turn whose home screen is destroyed still sends the text and shows the answer on the next one`() {
        val home = home()
        type(home.get(), "text Alice on my way")
        home.pause().stop().destroy()

        replies.countDown()
        waitFor("the entry to be answered") { entry().state == EntryState.ANSWERED }
        assertTrue("the reply's text was never handled", textHandled(cid))
        waitFor("the reply to be kept for the next home screen") { TurnRunner.undeliveredForTest() == 1 }

        val next = home()
        assertEquals(0, TurnRunner.undeliveredForTest())
        assertFalse(feedText(next.get()).contains("waiting for a reply"))
        assertTrue(feedText(next.get()).contains("Sent."))
    }

    @Test
    fun `a spoken turn answered while home is stopped closes its entry and runs its commands`() {
        val home = home()
        val id = Transcript.begin(app, "(voice)", EntryState.WAITING)
        home.pause().stop()

        val service = Robolectric.buildService(RecordService::class.java).create().get()
        service.handleResponse(sentReply(cid), "", id)
        settle()

        assertEquals(EntryState.ANSWERED, Transcript.all(app).single { it.localId == id }.state)
        assertEquals("Sent.", Transcript.all(app).single { it.localId == id }.answer)
        assertTrue("the spoken reply's text was never handled", textHandled(cid))

        home.restart().start().resume()
        settle()
        assertFalse(feedText(home.get()).contains("waiting for a reply"))
    }

    @Test
    fun `a spoken reply closes the entry it was recorded for, not the one being recorded now`() {
        val home = home()
        val first = Transcript.begin(app, "(voice)", EntryState.WAITING)
        // A second recording is under way when the first one's answer comes back.
        val talk = home.get().findViewById<android.view.View>(R.id.talkButton)
        val down = android.os.SystemClock.uptimeMillis()
        talk.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        settle()
        val second = Transcript.all(app).single { it.localId != first }.localId

        val service = Robolectric.buildService(RecordService::class.java).create().get()
        service.handleResponse(null, "the assistant is busy — try again in a moment", first)
        settle()

        val e = Transcript.all(app).single { it.localId == first }
        assertEquals(EntryState.FAILED, e.state)
        assertEquals("the assistant is busy — try again in a moment", e.error)
        assertEquals(EntryState.RECORDING, Transcript.all(app).single { it.localId == second }.state)
    }

    @Test
    fun `the record intent names its entry`() {
        val home = home()
        val a = home.get()
        // The talk button: down starts a recording and opens its entry, up sends it.
        val talk = a.findViewById<android.view.View>(R.id.talkButton)
        val down = android.os.SystemClock.uptimeMillis()
        talk.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        settle()
        val id = Transcript.all(app).single().localId
        talk.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down + 2_000, android.view.MotionEvent.ACTION_UP, 1f, 1f, 0))
        settle()
        var stop: Intent? = null
        while (true) {
            val next = shadowOf(app).nextStartedService ?: break
            if (next.action == RecordService.ACTION_STOP) stop = next
        }
        assertNotNull("no stop sent to the recorder", stop)
        assertEquals(id, stop!!.getLongExtra(RecordService.EXTRA_ENTRY_ID, 0L))
    }

    // ---- what the backend is told about a text ----

    @Test
    fun `a text the phone will not send is reported, not left silent`() {
        CommsResults.ack(app, CommsResults.pending(app).map { it.correlationId })
        DeviceCommands.handle(app, DeviceResponse.newBuilder().setRequestId("r-empty").setComms(
            CommsCommand.newBuilder().setAction("send_sms").setNumber("+12065550100").setBody(" ").setCorrelationId("c-empty")
        ).build())
        DeviceCommands.handle(app, DeviceResponse.newBuilder().setRequestId("r-nonum").setComms(
            CommsCommand.newBuilder().setAction("send_sms").setNumber("").setBody("hi").setCorrelationId("c-nonum")
        ).build())
        DeviceCommands.handle(app, DeviceResponse.newBuilder().setRequestId("r-911").setComms(
            CommsCommand.newBuilder().setAction("send_sms").setNumber("911").setBody("hi").setCorrelationId("c-911")
        ).build())
        val byId = CommsResults.pending(app).associateBy { it.correlationId }
        for (cid in listOf("c-empty", "c-nonum", "c-911")) {
            val r = byId[cid]
            assertNotNull("no result for $cid", r)
            assertFalse("$cid reported as sent", r!!.performed)
            assertTrue("$cid has no reason", r.error.isNotBlank())
        }
    }

    private var resultReceiver: SmsResultReceiver? = null

    /** The radio's [code] for part [part] of [parts] of the text [cid], as it delivers it. */
    private fun sent(cid: String, part: Int, parts: Int, code: Int) {
        if (resultReceiver == null) {
            resultReceiver = SmsResultReceiver().also {
                androidx.core.content.ContextCompat.registerReceiver(
                    app, it, android.content.IntentFilter(SmsResultReceiver.ACTION_SENT),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        }
        val i = shadowOf(SmsResultReceiver.sentIntent(app, cid, "Alice", part, parts)).savedIntent
        app.sendOrderedBroadcast(i, null, null, null, code, null, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a long text with a failed part is reported failed, whatever the last part says`() {
        sent("c-long", 0, 2, SmsManager.RESULT_ERROR_NO_SERVICE)
        sent("c-long", 1, 2, Activity.RESULT_OK)
        val r = CommsResults.pending(app).single { it.correlationId == "c-long" }
        assertFalse(r.performed)
        assertEquals("no service", r.error)
    }

    @Test
    fun `a long text is reported sent only when its last part is`() {
        sent("c-ok", 0, 2, Activity.RESULT_OK)
        assertTrue(CommsResults.pending(app).none { it.correlationId == "c-ok" })
        sent("c-ok", 1, 2, Activity.RESULT_OK)
        assertTrue(CommsResults.pending(app).single { it.correlationId == "c-ok" }.performed)
    }

    @Test
    fun `two texts to one number keep their own correlation ids`() {
        val a = shadowOf(SmsResultReceiver.sentIntent(app, "c-a", "Alice", 0, 1)).savedIntent
        val b = shadowOf(SmsResultReceiver.sentIntent(app, "c-b", "Alice", 0, 1)).savedIntent
        assertEquals("c-a", a.getStringExtra(SmsResultReceiver.EXTRA_CID))
        assertEquals("c-b", b.getStringExtra(SmsResultReceiver.EXTRA_CID))
    }

    @Test
    fun `a turn still being worked on is not swept as unanswered`() {
        // Kept past the default two minutes, so the sweep's verdict stays readable.
        Config.setTranscriptMaxAgeMs(app, 0L)
        val live = Transcript.begin(app, "a slow question", EntryState.WAITING)
        val orphan = Transcript.begin(app, "a lost question", EntryState.WAITING)
        val gate = CountDownLatch(1)
        TurnRunner.launch(app, live, "message", send = { gate.await(30, TimeUnit.SECONDS); reply })
        // A long recording plus a slow or streamed answer: older than the sweep, still running.
        Transcript.ageForTest(app, live, Transcript.IN_FLIGHT_TIMEOUT_MS + 1_000)
        Transcript.ageForTest(app, orphan, Transcript.IN_FLIGHT_TIMEOUT_MS + 1_000)

        assertEquals(EntryState.WAITING, Transcript.all(app).single { it.localId == live }.state)
        assertEquals(EntryState.FAILED, Transcript.all(app).single { it.localId == orphan }.state)

        gate.countDown()
        replies.countDown()
        waitFor("the slow turn to be answered") {
            Transcript.all(app).single { it.localId == live }.state == EntryState.ANSWERED
        }
    }

    @Test
    fun `an answer that lands minutes after the question is kept for the whole window after it`() {
        val window = Config.DEFAULT_TRANSCRIPT_MAX_AGE_MS
        Config.setTranscriptMaxAgeMs(app, window)
        val id = Transcript.begin(app, "a slow question", EntryState.WAITING)
        val gate = CountDownLatch(1)
        TurnRunner.launch(app, id, "message", send = { gate.await(30, TimeUnit.SECONDS); reply })
        // Asked three minutes ago, past the two-minute window, and only answered now.
        Transcript.ageForTest(app, id, 3 * 60_000L)
        gate.countDown()
        replies.countDown()
        waitFor("the slow turn to be answered") {
            Transcript.all(app).any { it.localId == id && it.state == EntryState.ANSWERED }
        }
        assertEquals("Sent.", Transcript.all(app).single { it.localId == id }.answer)

        // Kept through the window counted from the answer, and gone after it as configured.
        Transcript.ageForTest(app, id, window - 10_000L)
        assertTrue("the answer was deleted inside its window", Transcript.all(app).any { it.localId == id })
        Transcript.ageForTest(app, id, 20_000L)
        assertTrue("retention past the window changed", Transcript.all(app).none { it.localId == id })
    }

    @Test
    fun `a reply held for a home screen that has not come back in minutes is not shown`() {
        TurnRunner.finish(app, TurnRunner.Outcome(0L, null, "the network failed", "message"))
        settle()
        assertEquals(1, TurnRunner.undeliveredForTest())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TurnRunner.HELD_FOR_MS + 1_000))

        val shown = mutableListOf<TurnRunner.Outcome>()
        TurnRunner.attach { shown.add(it) }
        assertTrue("a stale reply resurfaced", shown.isEmpty())
        assertEquals(0, TurnRunner.undeliveredForTest())
    }

    @Test
    fun `a recording names its entry when it starts, so one stopped at the time cap closes it`() {
        val home = home()
        val talk = home.get().findViewById<android.view.View>(R.id.talkButton)
        val down = android.os.SystemClock.uptimeMillis()
        talk.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        settle()
        val id = Transcript.all(app).single().localId
        var start: Intent? = null
        while (true) {
            val next = shadowOf(app).nextStartedService ?: break
            if (next.action == RecordService.ACTION_START) start = next
        }
        assertNotNull("no start sent to the recorder", start)
        assertEquals(id, start!!.getLongExtra(RecordService.EXTRA_ENTRY_ID, 0L))
    }

    @Test
    fun `an answer that names no entry does not close the one being recorded now`() {
        val home = home()
        val talk = home.get().findViewById<android.view.View>(R.id.talkButton)
        val down = android.os.SystemClock.uptimeMillis()
        talk.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, 1f, 1f, 0))
        settle()
        val recording = Transcript.all(app).single().localId

        // An earlier recording's answer that did not know its entry, cancelled by this one.
        val service = Robolectric.buildService(RecordService::class.java).create().get()
        service.handleResponse(null, "", 0L)
        settle()

        assertEquals(EntryState.RECORDING, Transcript.all(app).single { it.localId == recording }.state)
    }

    @Test
    fun `a failed part stays failed after the backend has acknowledged it`() {
        sent("c-acked", 0, 2, SmsManager.RESULT_ERROR_NO_SERVICE)
        // A turn in between carried the failure and the backend acknowledged it.
        CommsResults.ack(app, listOf("c-acked"))
        sent("c-acked", 1, 2, Activity.RESULT_OK)
        assertTrue("half a text was reported sent",
            CommsResults.pending(app).none { it.correlationId == "c-acked" && it.performed })
    }
}
