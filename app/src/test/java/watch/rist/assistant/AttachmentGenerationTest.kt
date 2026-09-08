package watch.rist.assistant

import android.content.pm.ApplicationInfo
import android.view.ViewGroup
import android.widget.LinearLayout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AttachmentGenerationTest {

    // FLAG_DEBUGGABLE is the only thing [Config.isDebugBuild] reads; MockWebServer speaks plain http.
    private fun allowPlainHttp(a: MainActivity) {
        val ai = a.applicationContext.applicationInfo
        ai.flags = ai.flags or ApplicationInfo.FLAG_DEBUGGABLE
    }

    private fun activity() = Robolectric.buildActivity(MainActivity::class.java).create().get()

    private fun items(title: String) = listOf(
        RistAttachment(
            kind = "text", mime = "text/plain", title = title, text = "body",
            bytes = null, toolId = "t", error = null,
        )
    )

    private fun container(a: MainActivity): ViewGroup =
        a.findViewById<LinearLayout>(R.id.replyContainer)

    private fun cards(a: MainActivity): Int {
        val c = container(a)
        var n = 0
        for (i in 0 until c.childCount) if (c.getChildAt(i).id == R.id.attachmentCard) n++
        return n
    }

    @Test
    fun `attachments from the turn on screen are painted`() {
        val a = activity()
        a.attachmentGeneration = 1

        a.paintAttachmentsIfCurrent(1, items("current"))

        assertEquals(
            "attachments belonging to the visible turn must render",
            1, cards(a)
        )
    }

    @Test
    fun `attachments from a superseded turn are discarded, not painted under the new reply`() {
        val a = activity()
        a.attachmentGeneration = 2

        a.paintAttachmentsIfCurrent(1, items("stale"))

        assertEquals(
            "a picture from an earlier turn painted itself under a later answer, where the user " +
                "will read it as belonging to the question they just asked",
            0, cards(a)
        )
    }

    @Test
    fun `a stale batch does not clear attachments the current turn already painted`() {
        val a = activity()
        a.attachmentGeneration = 2
        a.paintAttachmentsIfCurrent(2, items("current"))
        assertEquals("precondition: the current turn painted", 1, cards(a))

        a.paintAttachmentsIfCurrent(1, items("stale"))

        assertEquals(
            "the late stale batch reached render() and wiped the current turn's attachments",
            1, cards(a)
        )
    }

    @Test
    fun `an empty result for the current turn clears the previous turn's cards`() {
        val a = activity()
        a.attachmentGeneration = 1
        a.paintAttachmentsIfCurrent(1, items("first"))
        assertEquals("precondition", 1, cards(a))

        a.attachmentGeneration = 2
        a.paintAttachmentsIfCurrent(2, emptyList())

        assertEquals(
            "a reply carrying no attachments left the previous reply's pictures on screen",
            0, cards(a)
        )
    }

    @Test
    fun `a destroyed activity is not drawn into`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create()
        val a = c.get()
        a.attachmentGeneration = 1
        c.destroy()

        a.paintAttachmentsIfCurrent(1, items("after destroy"))

        assertEquals(
            "attachments were painted into an activity that is already destroyed",
            0, cards(a)
        )
    }

    @Test
    fun `attachments survive a repaint of the transcript`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create()
        val a = c.get()
        a.attachmentGeneration = 1
        a.paintAttachmentsIfCurrent(1, items("keep me"))
        assertEquals("precondition: painted once", 1, cards(a))

        c.pause().resume()

        assertEquals(
            "the transcript repaint wiped the attachments and nothing put them back, so a picture " +
                "vanishes when the screen sleeps while the answer describing it remains",
            1, cards(a)
        )
    }

    @Test
    fun `clearing the remembered set stops them coming back on the next repaint`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create()
        val a = c.get()
        a.attachmentGeneration = 1
        a.paintAttachmentsIfCurrent(1, items("first"))
        assertEquals("precondition", 1, cards(a))

        a.lastAttachments = emptyList()
        c.pause().resume()

        assertEquals("a later turn with no attachments kept showing the earlier turn's", 0, cards(a))
    }

    @Test
    fun `an activity that is finishing is not drawn into`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create()
        val a = c.get()
        a.attachmentGeneration = 1
        a.finish()

        a.paintAttachmentsIfCurrent(1, items("while finishing"))

        assertEquals(
            "attachments were painted into an activity that is on its way out",
            0, cards(a)
        )
    }

    @Test
    fun `a reply carrying an attachment puts a card on screen`() {
        val a = activity()
        assertEquals("precondition: nothing painted yet", 0, cards(a))

        a.handleReply(replyWithNote("Chart", "the numbers"), subject = "test", clear = true)

        assertTrue(
            "a DeviceResponse carrying field 15 reached handleReply and nothing appeared. The " +
                "attachment dispatch in renderReply is missing or disconnected, so the whole " +
                "feature is dead in the real path however well its parts are tested.",
            awaitCards(a) > 0
        )
    }

    @Test
    fun `the production path advances the generation counter`() {
        val a = activity()
        a.attachmentGeneration = 0

        a.handleReply(replyWithNote("Chart", "the numbers"), subject = "test", clear = true)
        awaitCards(a)

        assertTrue(
            "attachmentGeneration was never incremented by production, so the ordering guard is " +
                "only ever exercised by tests setting the counter by hand -- it protects nothing",
            a.attachmentGeneration > 0
        )
    }

    @Test
    fun `a reply with no attachments advances the generation counter too`() {
        val a = activity()
        a.attachmentGeneration = 0

        a.handleReply(plainReply(), subject = "test", clear = true)

        assertTrue(
            "a reply carrying no attachments left the counter where it was, so a resolve still " +
                "running from the previous question will match it and paint under this answer",
            a.attachmentGeneration > 0
        )
    }

    @Test
    fun `a second reply replaces the first reply's attachments`() {
        val a = activity()
        a.handleReply(replyWithNote("First", "one"), subject = "test", clear = true)
        awaitCards(a)

        a.handleReply(replyWithNote("Second", "two"), subject = "test", clear = true)
        val text = awaitTitle(a, "Second")
        assertTrue("the newest reply's attachment must be shown; got: $text", text.contains("Second"))
        assertTrue(
            "the previous reply's attachment is still on screen under the new answer, where the " +
                "user will read it as belonging to the question they just asked. Got: $text",
            !text.contains("First")
        )
    }

    @Test
    fun `a reply with no attachments leaves no cards behind`() {
        val a = activity()
        a.handleReply(replyWithNote("Chart", "the numbers"), subject = "test", clear = true)
        assertTrue("precondition: a card was painted", awaitCards(a) > 0)

        a.handleReply(plainReply(), subject = "test", clear = true)

        assertEquals(
            "a reply carrying no attachments inherited the previous one's picture",
            0, awaitNoCards(a)
        )
    }

    @Test(timeout = 30_000)
    fun `a superseded reply stops before fetching the attachments it had not reached`() {
        val gate = CountDownLatch(1)
        val requests = AtomicInteger(0)
        val server = gatedServer(gate, requests)
        try {
            val a = activity()
            allowPlainHttp(a)
            a.handleReply(
                replyWithFetches(server, "First", "Second"),
                subject = "test", clear = true,
            )
            assertTrue(
                "precondition: the first attachment fetch never started",
                awaitRequests(requests, atLeast = 1)
            )

            a.handleReply(replyWithNote("Newer", "two"), subject = "test", clear = true)
            gate.countDown()
            awaitTitle(a, "Newer")

            assertEquals(
                "the superseded turn carried on and fetched its second attachment. Nothing will " +
                    "ever be drawn from it -- the generation guard sees to that -- so this is a " +
                    "socket, an IO thread and the user's cellular data spent entirely on a turn " +
                    "they have already moved past, and a pushing backend can stack these up.",
                1, settledRequests(requests)
            )
        } finally {
            gate.countDown()
            runCatching { server.shutdown() }
        }
    }

    @Test(timeout = 30_000)
    fun `a reply with no attachments supersedes an in-flight resolve too`() {
        val gate = CountDownLatch(1)
        val requests = AtomicInteger(0)
        val server = gatedServer(gate, requests)
        try {
            val a = activity()
            allowPlainHttp(a)
            a.handleReply(replyWithFetches(server, "Stale"), subject = "test", clear = true)
            assertTrue("precondition: the fetch never started", awaitRequests(requests, atLeast = 1))

            a.handleReply(plainReply(), subject = "test", clear = true)
            gate.countDown()

            assertFalse(
                "an attachment from the previous question painted itself under an answer that " +
                    "carried none, where the user reads it as belonging to what they just asked",
                awaitSettledText(a).contains("Stale")
            )
        } finally {
            gate.countDown()
            runCatching { server.shutdown() }
        }
    }

    private fun plainReply(): rist.v1.DeviceResponse =
        rist.v1.DeviceResponse.newBuilder()
            .setStatus(200)
            .setSpeech(rist.v1.Speech.newBuilder().setText("here you are").build())
            .setIsFinal(true)
            .build()

    private fun replyWithNote(title: String, body: String): rist.v1.DeviceResponse =
        rist.v1.DeviceResponse.newBuilder()
            .setStatus(200)
            .setSpeech(rist.v1.Speech.newBuilder().setText("here you are").build())
            .setIsFinal(true)
            .addAttachments(
                rist.v1.Attachment.newBuilder()
                    .setKind("text")
                    .setMime("text/plain")
                    .setTitle(title)
                    .setText(body)
                    .setToolId("test")
                    .build()
            )
            .build()

    // The resolve runs on Dispatchers.IO, a real thread pool under Robolectric, so idle repeatedly.
    private fun awaitCards(a: MainActivity, timeoutMs: Long = 5_000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cards(a) > 0) return cards(a)
            Thread.sleep(20)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return cards(a)
    }

    private fun awaitTitle(a: MainActivity, title: String, timeoutMs: Long = 5_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = ""
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            seen = allText(a.findViewById<ViewGroup>(R.id.replyContainer))
            if (seen.contains(title)) return seen
            Thread.sleep(20)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return allText(a.findViewById<ViewGroup>(R.id.replyContainer))
    }

    private fun awaitNoCards(a: MainActivity, timeoutMs: Long = 5_000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cards(a) == 0) return 0
            Thread.sleep(20)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return cards(a)
    }

    private fun gatedServer(gate: CountDownLatch, requests: AtomicInteger): MockWebServer =
        MockWebServer().also { s ->
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (requests.incrementAndGet() == 1) gate.await(20, TimeUnit.SECONDS)
                    return MockResponse().setBody("attachment-bytes")
                }
            }
            s.start()
        }

    private fun replyWithFetches(server: MockWebServer, vararg titles: String): rist.v1.DeviceResponse {
        val b = rist.v1.DeviceResponse.newBuilder()
            .setStatus(200)
            .setSpeech(rist.v1.Speech.newBuilder().setText("here you are").build())
            .setIsFinal(true)
        titles.forEach { t ->
            b.addAttachments(
                rist.v1.Attachment.newBuilder()
                    .setKind("data")
                    .setMime("application/octet-stream")
                    .setTitle(t)
                    .setUri(server.url("/$t.bin").toString())
                    .setToolId("test")
                    .build()
            )
        }
        return b.build()
    }

    private fun awaitRequests(
        requests: AtomicInteger,
        atLeast: Int,
        timeoutMs: Long = 10_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (requests.get() >= atLeast) return true
            Thread.sleep(20)
        }
        return requests.get() >= atLeast
    }

    // A wait, not a read: the assertion is that a second request never comes.
    private fun settledRequests(requests: AtomicInteger, windowMs: Long = 2_000): Int {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (requests.get() > 1) return requests.get()
            Thread.sleep(20)
        }
        return requests.get()
    }

    private fun awaitSettledText(a: MainActivity, windowMs: Long = 2_000): String {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return allText(a.findViewById<ViewGroup>(R.id.replyContainer))
    }

    private fun allText(v: android.view.View?): String {
        if (v == null) return ""
        val sb = StringBuilder()
        if (v is android.widget.TextView) sb.append(v.text).append(' ')
        if (v is ViewGroup) for (i in 0 until v.childCount) sb.append(allText(v.getChildAt(i)))
        return sb.toString()
    }

    @Test
    fun `painting attachments leaves the rest of the reply surface alone`() {
        val a = activity()
        val container = a.findViewById<ViewGroup>(R.id.replyContainer)
        val marker = android.widget.TextView(a).apply { text = "the view tree was here" }
        container.addView(marker)

        a.attachmentGeneration = 1
        a.paintAttachmentsIfCurrent(1, items("picture"))

        assertTrue("a card should have been painted", cards(a) > 0)
        assertTrue(
            "the attachment paint removed a sibling view. On a real reply that sibling is the " +
                "declarative view tree, the actions row, or the photo the user just took.",
            allText(container).contains("the view tree was here")
        )
    }

    @Test
    fun `a question that is still waiting does not inherit the previous answer's picture`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create()
        val a = c.get()
        Transcript.begin(a, "first question", EntryState.ANSWERED)
        a.attachmentGeneration = 1
        a.paintAttachmentsIfCurrent(1, items("the chart"))
        assertTrue("precondition: the first answer has its picture", cards(a) > 0)

        Transcript.begin(a, "a different question", EntryState.WAITING)
        c.pause().resume()

        assertEquals(
            "the previous answer's picture is pinned under a question that has not been answered " +
                "yet, where the user will read it as the reply to what they just asked",
            0, cards(a)
        )
    }

    @Test
    fun `dismissing the answer dismisses its attachments`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create()
        val a = c.get()
        val id = Transcript.begin(a, "show me the chart", EntryState.ANSWERED)
        a.attachmentGeneration = 1
        a.paintAttachmentsIfCurrent(1, items("the chart"))
        assertTrue("precondition: painted", cards(a) > 0)

        Transcript.discard(a, id)
        c.pause().resume()

        assertEquals(
            "the answer was dismissed and its picture is still on screen",
            0, cards(a)
        )
    }
}
