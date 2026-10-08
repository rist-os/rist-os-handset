package watch.rist.assistant

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CallLog
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import rist.v1.InboundSmsRequest
import rist.v1.Speech

/**
 * Texts and calls on request (schema v26): the backend asks during a turn the owner started, and
 * the phone sends that turn again once with its last day of texts and calls. Only while the owner's
 * switch is on and Rist can read messages; never on its own.
 */
@RunWith(RobolectricTestRunner::class)
class TextsOnRequestTest {

    private lateinit var server: MockWebServer
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun start() {
        Config.setDeployDefaultsForTest("", "")
        server = MockWebServer().apply { start() }
        Config.setBackendEndpoint(app, server.url("/v1/device").toString())
        Config.setEnrolRevoked(app, false)
        Config.setCredentialRejected(app, false)
        Config.clearBillingLapse(app)
        Config.setTextsOnAsk(app, true)
        StreamingCancel.resetForTest()
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG)
        Robolectric.setupContentProvider(RecentSms::class.java, "sms")
        Robolectric.setupContentProvider(RecentCallLog::class.java, CallLog.AUTHORITY)
    }

    @After
    fun stop() {
        Config.setTextsOnAsk(app, true)
        Config.clearDeployDefaultsForTest()
        runCatching { server.shutdown() }
    }

    private fun reply(build: DeviceResponse.Builder.() -> Unit) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/x-protobuf")
        .setBody(Buffer().write(DeviceResponse.newBuilder().apply(build).build().toByteArray()))

    private fun ask() = reply {
        speech = Speech.newBuilder().setText("One moment, checking your texts.").build()
        inboundSmsRequest = InboundSmsRequest.newBuilder().setWindowS(86400).build()
    }

    private fun answer(text: String) = reply { speech = Speech.newBuilder().setText(text).build() }

    private fun sent(): DeviceRequest =
        DeviceRequest.parseFrom(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readByteArray())

    @Test
    fun `asked for its texts, the phone sends the turn again with the last day of texts and calls`() {
        server.enqueue(ask())
        server.enqueue(answer("Dan texted you at 9:12."))

        val out = Uploader(app).sendText("save the number of whoever texted me as Dan")

        val first = sent()
        assertTrue(TextsOnRequest.COMPONENT in first.caps.componentsList)
        assertFalse(first.inboundSmsAnswered)
        assertEquals(0, first.inboundSmsCount)
        val second = sent()
        assertTrue(second.inboundSmsAnswered)
        assertEquals("save the number of whoever texted me as Dan", second.text)
        assertEquals(listOf("+14255559212"), second.inboundSmsList.map { it.from })
        assertEquals("hi it's Dan", second.getInboundSms(0).body)
        assertEquals(listOf("+12065550100" to "missed"), second.recentCallsList.map { it.number to it.kind })
        assertFalse("a new turn, not a retry", first.utteranceId == second.utteranceId)
        assertNotNull(out)
        assertEquals("Dan texted you at 9:12.", out!!.speech.text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `with the switch off nothing is offered and nothing is sent, even if asked`() {
        Config.setTextsOnAsk(app, false)
        server.enqueue(ask())

        Uploader(app).sendText("who texted me?")

        val first = sent()
        assertFalse(TextsOnRequest.COMPONENT in first.caps.componentsList)
        assertEquals(0, first.inboundSmsCount)
        assertEquals("one request: no re-send", 1, server.requestCount)
    }

    @Test
    fun `an ordinary turn carries no texts and no calls`() {
        server.enqueue(answer("Sunny."))
        Uploader(app).sendText("what's the weather")
        val first = sent()
        assertEquals(0, first.inboundSmsCount)
        assertEquals(0, first.recentCallsCount)
        assertFalse(first.inboundSmsAnswered)
    }

    @Test
    fun `the call log answer keeps calls that reached the owner, newest first, inside the day`() {
        val now = 10L * 24 * 3600 * 1000
        val rows = listOf(
            RecentCalls.Row("+1", now - 1_000, CallLog.Calls.MISSED_TYPE, 0),
            RecentCalls.Row("+2", now - 500, CallLog.Calls.OUTGOING_TYPE, 30),
            RecentCalls.Row("+3", now - 200, CallLog.Calls.INCOMING_TYPE, 45),
            RecentCalls.Row("+4", now - RecentCalls.WINDOW_MS - 1, CallLog.Calls.MISSED_TYPE, 0),
            RecentCalls.Row("", now - 100, CallLog.Calls.MISSED_TYPE, 0),
            RecentCalls.Row("+5", now - 300, CallLog.Calls.REJECTED_TYPE, 0),
        )
        assertEquals(listOf("+3" to "incoming", "+5" to "rejected", "+1" to "missed"),
            RecentCalls.fromLog(rows, now).map { it.number to it.kind })
    }

    @Test
    fun `the settings line says what the switch does`() {
        assertTrue(ContactsSection.textsStatus(true, true).contains("Nothing is sent otherwise"))
        assertTrue(ContactsSection.textsStatus(false, true).startsWith("Off."))
        assertTrue(ContactsSection.textsStatus(true, false).contains("not let Rist read"))
    }
}

/** The SMS inbox with one text from Dan, a minute old. */
class RecentSms : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val now = System.currentTimeMillis()
        return MatrixCursor(arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT))
            .apply { addRow(arrayOf<Any>("+14255559212", "hi it's Dan", now - 60_000, now - 61_000)) }
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/** The call log with one missed call, ten minutes old, and one call the owner made. */
class RecentCallLog : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val now = System.currentTimeMillis()
        return MatrixCursor(arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION)).apply {
            addRow(arrayOf<Any>("+12065550100", now - 600_000, CallLog.Calls.MISSED_TYPE, 0))
            addRow(arrayOf<Any>("+12065550199", now - 700_000, CallLog.Calls.OUTGOING_TYPE, 30))
        }
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
