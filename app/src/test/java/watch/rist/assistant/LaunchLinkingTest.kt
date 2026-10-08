package watch.rist.assistant

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.BoxSet
import rist.v1.DeviceResponse
import rist.v1.HomeBox
import rist.v1.Speech

/** Linking a phone to a Rist Assist account, and what it shows when the account can't be served. */
@RunWith(RobolectricTestRunner::class)
class LaunchLinkingTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    private val routerLine = "Your Rist Assistant subscription has ended — renew at ristassist.com."

    @Before
    fun start() {
        Config.setDeployDefaultsForTest("", "")
        server = MockWebServer().apply { start() }
        Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
        Config.clearBillingLapse(ctx)
        Config.setEnrolRevoked(ctx, false)
        Config.setCredentialRejected(ctx, false)
        StreamingCancel.resetForTest()
        HomeBoxes.resetForTest(ctx)
        BoxCreate.resetForTest()
        BoxRefresh.resetForTest()
    }

    @After
    fun stop() {
        Config.clearBillingLapse(ctx)
        Config.setEnrolRevoked(ctx, false)
        Config.setCredentialRejected(ctx, false)
        HomeBoxes.resetForTest(ctx)
        BoxCreate.resetForTest()
        BoxRefresh.resetForTest()
        ContactsSync.runInlineForTest = false
        ContactsSync.bearerForTest = null
        Config.clearDeployDefaultsForTest()
        runCatching { server.shutdown() }
    }

    private fun spoken(text: String) = Buffer().write(
        DeviceResponse.newBuilder().setStatus(1).setRequestId("r402")
            .setSpeech(Speech.newBuilder().setText(text)).build().toByteArray()
    )

    private fun lapsed(text: String? = routerLine, reason: String = "ended") = MockResponse().setResponseCode(402)
        .setHeader("Content-Type", "application/x-protobuf")
        .setHeader("X-Rist-Billing", reason)
        .setHeader("X-Rist-Renew-Url", "ristassist.com")
        .setHeader("X-Rist-Billing-Portal", "/v1/billing/portal")
        .apply { if (text != null) setBody(spoken(text)) }

    // ---- the account page hosts ----

    @Test
    fun `ristassist dot com and its www host are account pages, alongside the old ones`() {
        fun page(url: String) = Billing.accountPage(Billing.lapseFrom(Billing.REASON_NOT_ACTIVE, "ristassist.com", null, url))
        assertEquals("https://ristassist.com/account", page("https://ristassist.com/account"))
        assertEquals("https://www.ristassist.com/account", page("https://www.ristassist.com/account"))
        assertEquals("https://ristassistant.com/account", page("https://ristassistant.com/account"))
        assertEquals("https://www.ristmobile.com/", page("https://www.ristmobile.com/"))
    }

    @Test
    fun `account hosts match exactly, never by suffix`() {
        fun page(url: String) = Billing.accountPage(Billing.lapseFrom(Billing.REASON_NOT_ACTIVE, "ristassist.com", null, url))
        assertNull(page("https://pay.ristassist.com/account"))
        assertNull(page("https://evilristassist.com/account"))
        assertNull(page("https://ristassist.com.evil.example/account"))
        assertNull(page("http://ristassist.com/account"))
        assertNull(page("https://ristassist.com:8443/account"))
        assertNull(page("https://someone@ristassist.com/account"))
        assertNull(page("https://ristassist.com.@evil.example/account"))
        assertNull(page("https://evil.example\\@ristassist.com/account"))
        assertNull(page("https://rіstassist.com/account")) // Cyrillic i: a different (punycode) host
        assertNull(page("javascript:alert(1)"))
        assertNull(page("ristassist.com.evil.example"))
    }

    // ---- pairing ----

    @Test
    fun `a device-limit 409 on pairing says two phones, names the Phones page, and to enter the code again`() {
        server.enqueue(MockResponse().setResponseCode(409)
            .setHeader(Enrolment.ENROL_REASON_HEADER, Enrolment.HELD_DEVICE_LIMIT)
            .setBody("""{"detail":"This account already has its maximum of 2 devices. Remove one before adding another."}"""))
        val r = Enrolment.pair(ctx, "ABCD2345")
        assertEquals(Enrolment.PairResult.DEVICE_LIMIT, r)
        assertEquals("/v1/enroll", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        val shown = Enrolment.explainPair(r)
        assertTrue(shown, shown.contains("already has two phones"))
        assertTrue(shown, shown.contains("Phones page at ristassist.com/account/phones"))
        assertTrue(shown, shown.contains("enter the code again"))
        assertFalse(shown, shown.contains("not been used"))
        assertFalse(shown, shown.contains("Couldn't reach"))
    }

    @Test
    fun `the 409 header decides between the device limit and a phone held elsewhere`() {
        assertEquals(Enrolment.PairResult.DEVICE_LIMIT,
            Enrolment.classifyPair(409, true, "", Enrolment.HELD_DEVICE_LIMIT))
        assertEquals(Enrolment.PairResult.HELD_ELSEWHERE,
            Enrolment.classifyPair(409, true, "maximum", Enrolment.HELD_ELSEWHERE))
        assertEquals(Enrolment.PairResult.DEVICE_LIMIT, Enrolment.classifyPair(409, true, "its maximum of 2 devices"))
    }

    @Test
    fun `a successful pairing says Connected to Rist Assist`() {
        assertEquals("Connected to Rist Assist.", Enrolment.explainPair(Enrolment.PairResult.OK))
    }

    @Test
    fun `the Settings labels the website quotes are unchanged`() {
        assertEquals("Save endpoint", ctx.getString(R.string.backend_save))
        assertEquals("Connect", ctx.getString(R.string.pair_submit))
    }

    // ---- 402 on a turn ----

    @Test
    fun `a 402 turn shows the router's sentence, and the feed's notice is that sentence too`() {
        val said = "Your Rist Assistant subscription has ended. Update your card at ristassist.com."
        server.enqueue(lapsed(said))
        val up = Uploader(ctx)
        val reply = up.sendText("hello")
        assertEquals(said, reply!!.speech.text)
        assertFalse(up.lastFailure.contains("402"))
        assertEquals(said, Billing.notice(ctx))
        assertEquals("one request: a 402 is not retried", 1, server.requestCount)
    }

    @Test
    fun `the capped 402's emergency sentence is spoken with that turn but not kept in the notice`() {
        val said = "If this is an emergency, dial 9 1 1 on your phone now. $routerLine"
        server.enqueue(lapsed(said))
        val reply = Uploader(ctx).sendText("hello")
        assertEquals(said, reply!!.speech.text)
        assertEquals(routerLine, Billing.notice(ctx))
        assertEquals("", Billing.noticeLine("If this is an emergency, dial 9 1 1 on your phone now."))
        assertEquals(routerLine, Billing.noticeLine(routerLine))
    }

    @Test
    fun `a pairing that may have reached the router never says the code is unused`() {
        assertFalse(Enrolment.explainPair(Enrolment.PairResult.NETWORK).contains("not been used"))
    }

    @Test
    fun `a 402 without a sentence keeps the sentence already held for the same reason`() {
        Billing.onLapsed(ctx, Billing.lapseFrom("ended", "ristassist.com", null, null, "Said by the router."))
        Billing.onLapsed(ctx, Billing.lapseFrom("ended", "ristassist.com", null))
        assertEquals("Said by the router.", Billing.notice(ctx))
        Billing.onLapsed(ctx, Billing.lapseFrom(Billing.REASON_NOT_ACTIVE, "ristassist.com", null))
        assertEquals(Billing.notActiveLine("ristassist.com"), Billing.notice(ctx))
    }

    @Test
    fun `the 402 sentence is read from a protobuf or a JSON body`() {
        assertEquals(routerLine, Billing.lineFromBody(spoken(routerLine).readByteArray()))
        assertEquals("From JSON.", Billing.lineFromBody("""{"status":1,"speech":{"text":"From JSON."}}""".toByteArray()))
        assertEquals("", Billing.lineFromBody(ByteArray(0)))
        assertEquals("", Billing.lineFromBody(null))
    }

    // ---- 402 on the wake loop ----

    @Test
    fun `a 402 on the wake poll is a lapse carrying the router's sentence, and waits minutes`() {
        server.enqueue(lapsed())
        val out = WakeLoop.exchange(http, server.url("/v1/device/wake").toString(), "Bearer tok", "dev1", emptyList())
        assertTrue(out is WakeLoop.Outcome.Lapsed)
        assertEquals(routerLine, Billing.lineFor((out as WakeLoop.Outcome.Lapsed).lapse))
        assertTrue("the wake loop does not spin on a 402", WakeLoop.LAPSED_RECHECK_MS >= 60_000L)
    }

    // ---- 402 on box edits ----

    @Test
    fun `a 402 on a box edit keeps it queued, records the lapse, and does not retry`() {
        HomeBoxes.apply(ctx, BoxSet.newBuilder().setVersion(1)
            .addBoxes(HomeBox.newBuilder().setId("a").setTitle("a").setKind("display"))
            .addBoxes(HomeBox.newBuilder().setId("b").setTitle("b").setKind("display")).build())
        HomeBoxes.edit(ctx, HomeBoxes.reorderEdit(ctx, listOf("b", "a")))
        server.enqueue(lapsed())
        assertEquals(0, HomeBoxes.flush(ctx))
        assertEquals(1, HomeBoxes.queued(ctx).size)
        assertEquals(1, server.requestCount)
        assertEquals(routerLine, Billing.notice(ctx))
    }

    @Test
    fun `a one-tap tile refused with 402 takes its placeholder down with the router's sentence`() {
        val told = mutableListOf<String>()
        val said = mutableListOf<Int>()
        BoxCreate.toldForTest = { told += it }
        BoxCreate.saidForTest = { said += it }
        BoxCreate.kickForTest = { }
        val e = HomeBoxes.addEdit(ctx, "check my email")
        BoxCreate.startAdd(ctx, "check my email", e.editId)
        HomeBoxes.edit(ctx, e)
        server.enqueue(lapsed())
        HomeBoxes.flush(ctx)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(routerLine), told)
        assertTrue("not the offline word", said.isEmpty())
        assertTrue(BoxCreate.waiting().isEmpty())
        assertEquals("the add waits for the account", 1, HomeBoxes.queued(ctx).size)
    }

    @Test
    fun `a 402 on a box refresh is a lapse with the router's sentence, not a failure`() {
        BoxRefresh.onlineForTest = true
        server.enqueue(lapsed())
        val out = BoxRefresh.request(ctx, "a", http)
        assertEquals(BoxRefresh.Outcome.Lapsed(routerLine), out)
    }

    // ---- 402 on contacts sync ----

    @Test
    fun `a 402 on a contacts pull records the lapse and backs off like a failure`() {
        ContactsSync.bearerForTest = "Bearer ristd_test"
        Config.setFeatures(ctx, "")
        Config.setContactsSyncOff(ctx, false)
        Config.setContactsRefused(ctx, false)
        server.enqueue(lapsed())
        val out = ContactsSync.syncBlocking(ctx)
        assertEquals(ContactsSync.Outcome.Refused(402), out)
        assertEquals(ContactsSync.Refusal.LAPSED, ContactsSync.classify(402))
        assertEquals(routerLine, Billing.notice(ctx))
        assertFalse("contacts stay on: a 402 is not the feature being off", Config.contactsRefused(ctx))
        assertFalse(Config.credentialRejected(ctx))
    }

    // ---- the not-active line ----

    @Test
    fun `tapping the not-active line opens the account page in the locked browser`() {
        Billing.onLapsed(ctx, Billing.lapseFrom(Billing.REASON_NOT_ACTIVE, "ristassist.com", null, "https://ristassist.com/account"))
        val a = Robolectric.buildActivity(MainActivity::class.java).create().get()
        CommsFeedView.render(a)
        val row = a.findViewById<LinearLayout>(R.id.commsFeed).findViewWithTag<ViewGroup>(CommsFeedView.BILLING_ROW_TAG)
        assertNotNull(row)
        val line = row.findViewWithTag<TextView>(CommsFeedView.BILLING_LINE_TAG)
        assertTrue(line.isClickable)
        line.performClick()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(LockedBrowserActivity::class.java.name, started?.component?.className)
        assertEquals("https://ristassist.com/account", started!!.getStringExtra(LockedBrowserActivity.EXTRA_URL))
    }

    @Test
    fun `a not-active line whose account address is not ours opens nothing`() {
        Billing.onLapsed(ctx, Billing.lapseFrom(Billing.REASON_NOT_ACTIVE, "evil.example", null, "https://evil.example/account"))
        val a = Robolectric.buildActivity(MainActivity::class.java).create().get()
        CommsFeedView.render(a)
        val row = a.findViewById<LinearLayout>(R.id.commsFeed).findViewWithTag<ViewGroup>(CommsFeedView.BILLING_ROW_TAG)
        val line = row.findViewWithTag<TextView>(CommsFeedView.BILLING_LINE_TAG)
        assertFalse(line.isClickable)
        line.performClick()
        val started = generateSequence { shadowOf(a).nextStartedActivity }.toList()
        assertTrue(started.none { it.component?.className == LockedBrowserActivity::class.java.name })
    }
}
