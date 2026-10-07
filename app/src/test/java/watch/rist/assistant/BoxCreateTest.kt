package watch.rist.assistant

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import rist.v1.BoxEdit
import rist.v1.BoxEditReply
import rist.v1.BoxSet
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import rist.v1.HomeBox
import rist.v1.Speech
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The placeholder tile shown between an add from the sheet and the new box arriving. */
@RunWith(RobolectricTestRunner::class)
class BoxCreateTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val turns = CopyOnWriteArrayList<DeviceRequest>()
    private val kicks = AtomicInteger()
    private val said = CopyOnWriteArrayList<Int>()
    @Volatile private var reply: () -> MockResponse = { ok(DeviceResponse.newBuilder()) }
    private val edits = CopyOnWriteArrayList<BoxEdit>()
    @Volatile private var boxesReply: (BoxEdit) -> MockResponse = { MockResponse().setResponseCode(503) }

    private fun editReply(s: BoxSet) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/x-protobuf")
        .setHeadersDelay(300, TimeUnit.MILLISECONDS)
        .setBody(Buffer().write(BoxEditReply.newBuilder().setStatus(200).setBoxes(s).build().toByteArray()))

    private fun ok(r: DeviceResponse.Builder) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/x-protobuf")
        .setHeadersDelay(300, TimeUnit.MILLISECONDS)
        .setBody(Buffer().write(r.setIsFinal(true).setSpeech(Speech.newBuilder().setText("Done.")).build().toByteArray()))

    @Before
    fun setUp() {
        HomeBoxes.resetForTest(app)
        HomeBoxes.shippedForTest = true
        BoxRefresh.resetForTest()
        BoxRefresh.motionForTest = true
        BoxCreate.kickForTest = { kicks.incrementAndGet() }
        BoxCreate.saidForTest = { said += it }
        Config.setFeatures(app, "")
        Transcript.clearForTest(app)
        StreamingCancel.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readByteArray()
                if (request.path == "/v1/device/boxes") {
                    val e = BoxEdit.parseFrom(body)
                    edits += e
                    return boxesReply(e)
                }
                val req = DeviceRequest.parseFrom(body)
                // Only a turn gets the test's reply; the home screen's own check-ins get nothing.
                if (request.path != "/v1/device" || (req.text.isBlank() && req.targetToolId.isBlank())) {
                    return MockResponse().setResponseCode(404)
                }
                turns += req
                return reply()
            }
        }
        server.start()
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(app, server.url("/v1/device").toString())
    }

    @After
    fun tidy() {
        HomeBoxes.awaitFlushForTest()
        server.shutdown()
        HomeBoxes.resetForTest(app)
        BoxRefresh.resetForTest()
        Config.setFeatures(app, "")
        Transcript.clearForTest(app)
    }

    private fun box(id: String): HomeBox = HomeBox.newBuilder().setId(id).setTitle(id).setKind("display")
        .setState("ok").setValue("54°").setUpdatedAtEpochS(System.currentTimeMillis() / 1000 - 60).build()

    private fun set(vararg ids: String): BoxSet =
        BoxSet.newBuilder().setVersion(4).addAllBoxes(ids.map { box(it) }).build()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))

    private fun home(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get().also { settle() }

    private fun row(a: android.app.Activity): RecyclerView = a.findViewById(R.id.boxList)

    private fun placeholder(a: android.app.Activity): ViewGroup? =
        row(a).findViewWithTag(BoxBoard.CREATING_TAG)

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
            settle()
        }
    }

    private fun submit(a: MainActivity, words: String) {
        a.boxBoard.openAddSheet()
        settle()
        val root = ShadowDialog.getLatestDialog().window!!.decorView
        root.findViewWithTag<EditText>(BoxSheet.TAG_WORDS).setText(words)
        root.findViewWithTag<View>(BoxSheet.TAG_SUBMIT).performClick()
        settle()
    }

    private fun submitCommand(a: MainActivity, words: String) {
        a.boxBoard.openAddSheet()
        settle()
        val root = ShadowDialog.getLatestDialog().window!!.decorView
        root.findViewWithTag<View>(BoxSheet.TAG_COMMAND).performClick()
        root.findViewWithTag<EditText>(BoxSheet.TAG_WORDS).setText(words)
        root.findViewWithTag<View>(BoxSheet.TAG_SUBMIT).performClick()
        settle()
    }

    private fun command(id: String, words: String): HomeBox = HomeBox.newBuilder().setId(id).setTitle("Email")
        .setKind("command").setState("ok").setCommand(words).setSourceWords(words).build()

    @Test
    fun `a one-tap tile is added by touch edit, and the reply's new box replaces the placeholder`() {
        HomeBoxes.apply(app, set("a"))
        boxesReply = { e ->
            editReply(set("a").toBuilder().setVersion(5).addBoxes(command("new", e.getAdd(0).command)).build())
        }
        val a = home()
        submitCommand(a, "Check my email, then text Sam")
        assertNotNull("the placeholder is up at once", placeholder(a))
        waitFor("the new box") { BoxCreate.waiting().isEmpty() }
        HomeBoxes.awaitFlushForTest()
        a.renderBoxes(); settle()
        assertNull(placeholder(a))
        assertNotNull(row(a).findViewWithTag<View>(BoxBoard.TILE_TAG_PREFIX + "new"))
        assertEquals("Check my email, then text Sam", edits.single().getAdd(0).command)
        assertTrue("no turn at all", turns.isEmpty())
        assertTrue(HomeBoxes.queued(app).isEmpty())
        assertTrue(said.isEmpty())
    }

    @Test
    fun `a list taking in the new box before the reply is judged makes one tile and sends no turn`() {
        HomeBoxes.apply(app, set("a"))
        // As on the phone: while the add is out, the wake flushes too, on another thread, and the
        // list holding the new box is taken in before the reply to the add is looked at.
        val other = AtomicInteger()
        boxesReply = { e ->
            if (other.getAndIncrement() == 0) {
                Thread { runCatching { HomeBoxes.flush(app) } }.start()
                Thread.sleep(300)
            }
            val made = set("a").toBuilder().setVersion(5).addBoxes(command("new", e.getAdd(0).command)).build()
            HomeBoxes.apply(app, made)
            editReply(made)
        }
        val a = home()
        submitCommand(a, "check my email")
        waitFor("the new box") { BoxCreate.waiting().isEmpty() }
        HomeBoxes.awaitFlushForTest()
        Thread.sleep(500); settle()
        assertEquals("the add is sent once", 1, edits.size)
        assertTrue("no creating turn", turns.isEmpty())
        assertEquals(listOf("a", "new"), HomeBoxes.boxes(app).map { it.id })
        assertTrue(said.isEmpty())
    }

    @Test
    fun `a reply that lacks the new box sends no turn, and the placeholder goes with a word`() {
        BoxCreate.graceMsForTest = 300L
        HomeBoxes.apply(app, set("a"))
        boxesReply = { editReply(set("a")) }
        val a = home()
        submitCommand(a, "check my email")
        HomeBoxes.awaitFlushForTest()
        waitFor("the failure") { BoxCreate.waiting().isEmpty() }
        assertEquals(1, edits.size)
        assertTrue("no creating turn", turns.isEmpty())
        assertEquals(listOf(R.string.boxes_create_failed), said.toList())
        assertEquals(listOf("a"), HomeBoxes.boxes(app).map { it.id })
    }

    @Test
    fun `tapping a tile that stored the add wrapper sends only the words`() {
        HomeBoxes.apply(app, set("a").toBuilder().addBoxes(command("old", "Add a command box: check my email")).build())
        val a = home()
        a.boxBoard.tap(HomeBoxes.find(app, "old")!!)
        waitFor("the turn") { turns.isNotEmpty() }
        assertEquals("check my email", turns.single().text)
        assertEquals("", turns.single().targetToolId)
    }

    @Test
    fun `a one-tap tile offline is queued, and its placeholder goes with a word`() {
        HomeBoxes.apply(app, set("a"))
        val a = home()
        submitCommand(a, "check my email")
        HomeBoxes.awaitFlushForTest()
        waitFor("the placeholder to go") { BoxCreate.waiting().isEmpty() }
        assertEquals(listOf(R.string.boxes_add_queued), said.toList())
        assertEquals("check my email", HomeBoxes.queued(app).single().getAdd(0).command)
        assertTrue(turns.isEmpty())
        // Back online: the queued add goes and its box arrives.
        boxesReply = { e -> editReply(set("a").toBuilder().addBoxes(command("new", e.getAdd(0).command)).build()) }
        HomeBoxes.flush(app)
        assertNotNull(HomeBoxes.find(app, "new"))
        assertTrue(turns.isEmpty())
    }

    /** The order the row draws: tags of each item, left to right. */
    private fun order(a: android.app.Activity): List<String> {
        val list = row(a)
        return (0 until list.childCount).map { list.getChildAt(it).tag?.toString().orEmpty() }
    }

    @Test
    fun `a submit puts up a spinning placeholder before the Add square, with the request's first words`() {
        BoxCreate.graceMsForTest = 300L
        HomeBoxes.apply(app, set("a"))
        // A list without the new box, late: the placeholder is checked while the turn is out.
        reply = { ok(DeviceResponse.newBuilder().setBoxes(set("a"))).setHeadersDelay(2, TimeUnit.SECONDS) }
        val a = home()
        submit(a, "the temperature here every thirty minutes please")
        val p = requireNotNull(placeholder(a)) { "no placeholder" }
        assertEquals(listOf(BoxBoard.NOTIFICATIONS_TAG, BoxBoard.TILE_TAG_PREFIX + "a", BoxBoard.CREATING_TAG), order(a).take(3))
        row(a).scrollToPosition(3)
        settle()
        assertEquals(order(a).indexOf(BoxBoard.CREATING_TAG) + 1, order(a).indexOf(BoxBoard.ADD_TAG))
        assertEquals("New tile", p.findViewWithTag<TextView>(BoxBoard.LABEL_TAG).text.toString())
        assertEquals("the temperature here every…", p.findViewWithTag<TextView>(BoxBoard.DETAIL_TAG).text.toString())
        assertNotNull(p.findViewWithTag<View>(BoxBoard.SPINNER_TAG))
        assertTrue(p.contentDescription.toString().startsWith("Creating tile"))
        assertTrue("the wake is polled at once", kicks.get() >= 1)
        waitFor("the turn") { turns.isNotEmpty() }
        assertEquals("Add a display box: the temperature here every thirty minutes please", turns[0].text)
        assertEquals("only the add turn is sent", 1, turns.size)
        waitFor("the turn to end") { BoxCreate.waiting().isEmpty() }
    }

    @Test
    fun `with animations off the placeholder says Creating instead of spinning`() {
        BoxRefresh.motionForTest = false
        val a = home()
        BoxCreate.start(app, "my next meeting")
        a.renderBoxes(); settle()
        val p = requireNotNull(placeholder(a))
        assertNull(p.findViewWithTag<View>(BoxBoard.SPINNER_TAG))
        assertEquals("Creating…", p.findViewWithTag<TextView>(BoxBoard.VALUE_TAG).text.toString())
    }

    @Test
    fun `the reply's list with the new box replaces the placeholder`() {
        HomeBoxes.apply(app, set("a"))
        reply = { ok(DeviceResponse.newBuilder().setBoxes(set("a", "fresh"))) }
        val a = home()
        submit(a, "my next meeting")
        assertNotNull(placeholder(a))
        waitFor("the new box") { BoxCreate.waiting().isEmpty() }
        a.renderBoxes(); settle()
        assertNull(placeholder(a))
        assertNotNull(row(a).findViewWithTag<View>(BoxBoard.TILE_TAG_PREFIX + "fresh"))
        assertTrue(said.isEmpty())
    }

    @Test
    fun `a box arriving on the wake later ends the wait, the newest new one`() {
        HomeBoxes.apply(app, set("a"))
        val p = BoxCreate.start(app, "stocks")
        HomeBoxes.apply(app, set("a"))
        assertTrue(BoxCreate.isWaiting(p))
        HomeBoxes.apply(app, set("a", "x", "y"))
        assertTrue(BoxCreate.waiting().isEmpty())
    }

    @Test
    fun `a failed turn removes the placeholder with a word`() {
        reply = { MockResponse().setResponseCode(400) }
        val a = home()
        submit(a, "my next meeting")
        waitFor("the failure") { BoxCreate.waiting().isEmpty() }
        settle()
        assertNull(placeholder(a))
        assertEquals(listOf(R.string.boxes_create_failed), said.toList())
        assertEquals("Couldn't create that tile", app.getString(R.string.boxes_create_failed))
    }

    @Test
    fun `a reply whose list has no new box removes the placeholder after a grace`() {
        BoxCreate.graceMsForTest = 300L
        HomeBoxes.apply(app, set("a"))
        reply = { ok(DeviceResponse.newBuilder().setBoxes(set("a"))) }
        val a = home()
        submit(a, "something it cannot do")
        waitFor("the failure") { BoxCreate.waiting().isEmpty() }
        assertEquals(listOf(R.string.boxes_create_failed), said.toList())
    }

    @Test
    fun `the wake is polled at 0, 3, 6, 10 and 20 s, then every 30 s, before the 90 s cap`() {
        assertEquals(listOf(0L, 3_000L, 6_000L, 10_000L, 20_000L, 50_000L, 80_000L), BoxCreate.POLLS_MS.toList())
        assertEquals(90_000L, BoxCreate.CAP_MS)
        // Held first, so a list a turn from an earlier test lands late brings no new box.
        HomeBoxes.apply(app, set("a", "fresh", "x", "y"))
        val p = BoxCreate.start(app, "my next meeting")
        assertEquals(BoxCreate.CAP_MS, p.deadlineUptimeMs - p.startedUptimeMs)
        assertEquals(1, kicks.get())
        // Short steps only: a long idle here would carry into the tests after it.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_100))
        assertEquals(2, kicks.get())
        assertTrue(BoxCreate.isWaiting(p))
    }

    @Test
    fun `a reply without a list keeps waiting until the cap, then goes with a word`() {
        BoxCreate.capMsForTest = 400L
        // Held first, so a list a turn from an earlier test lands late brings no new box.
        HomeBoxes.apply(app, set("a", "fresh", "x", "y"))
        val p = BoxCreate.start(app, "my next meeting")
        BoxCreate.turnEnded(app, p, replied = true, carriedBoxes = false, expectsReply = false)
        assertTrue(BoxCreate.isWaiting(p))
        assertTrue(said.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertTrue(BoxCreate.waiting().isEmpty())
        assertEquals(listOf(R.string.boxes_create_failed), said.toList())
    }

    @Test
    fun `a reply asking something back keeps the placeholder`() {
        // Held first, so a list a turn from an earlier test lands late brings no new box.
        HomeBoxes.apply(app, set("a", "fresh", "x", "y"))
        val p = BoxCreate.start(app, "my next meeting")
        BoxCreate.turnEnded(app, p, replied = true, carriedBoxes = true, expectsReply = true)
        assertTrue(BoxCreate.isWaiting(p))
    }

    @Test
    fun `the placeholder survives leaving and reopening home, and redraws`() {
        BoxRefresh.motionForTest = false
        val a = home()
        BoxCreate.start(app, "my next meeting")
        a.renderBoxes(); a.renderBoxes(); settle()
        assertNotNull(placeholder(a))
        val b = home()
        assertNotNull(placeholder(b))
        val g = Robolectric.buildActivity(AllBoxesActivity::class.java).setup().get()
        settle()
        assertNotNull(g.window.decorView.findViewWithTag<View>(BoxBoard.CREATING_TAG))
    }

    @Test
    fun `a long press removes the placeholder on the phone only, and a tap expands nothing`() {
        val a = home()
        BoxCreate.start(app, "my next meeting")
        a.renderBoxes(); settle()
        while (shadowOf(a).nextStartedActivity != null) Unit
        placeholder(a)!!.performClick()
        settle()
        assertNull(shadowOf(a).nextStartedActivity)
        assertTrue(BoxCreate.waiting().isNotEmpty())
        placeholder(a)!!.performLongClick()
        settle()
        assertNull(placeholder(a))
        assertTrue(BoxCreate.waiting().isEmpty())
        assertTrue(said.isEmpty())
        assertTrue(turns.isEmpty())
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun worded(id: String, words: String): HomeBox = box(id).toBuilder().setSourceWords(words).build()

    @Test
    fun `a box made just after a reply whose list lacked it still ends the wait, without a word`() {
        BoxCreate.graceMsForTest = 60_000L
        HomeBoxes.apply(app, set("a"))
        val p = BoxCreate.start(app, "my next meeting")
        val before = kicks.get()
        BoxCreate.turnEnded(app, p, replied = true, carriedBoxes = true, expectsReply = false)
        assertTrue("a list from before the box is not yet a failure", BoxCreate.isWaiting(p))
        assertTrue("the wake is polled at the reply", kicks.get() > before)
        idle(1_000)
        HomeBoxes.apply(app, set("a", "fresh"))
        assertTrue(BoxCreate.waiting().isEmpty())
        idle(61_000)
        assertTrue(said.isEmpty())
    }

    @Test
    fun `a box another placeholder took never ends a later wait`() {
        HomeBoxes.apply(app, set("a"))
        val first = BoxCreate.start(app, "my next meeting")
        val second = BoxCreate.start(app, "stocks")
        HomeBoxes.apply(app, set("a", "x"))
        assertTrue(!BoxCreate.isWaiting(first))
        assertTrue(BoxCreate.isWaiting(second))
        // The second's reply carries the same list: x is the first's, not the second's.
        BoxCreate.observe(HomeBoxes.boxes(app))
        assertTrue(BoxCreate.isWaiting(second))
        HomeBoxes.apply(app, set("a", "x", "y"))
        assertTrue(BoxCreate.waiting().isEmpty())
    }

    @Test
    fun `a new box goes to the placeholder whose words made it`() {
        HomeBoxes.apply(app, set("a"))
        val meeting = BoxCreate.start(app, "my next meeting")
        val stocks = BoxCreate.start(app, "stocks")
        HomeBoxes.apply(app, BoxSet.newBuilder().setVersion(5)
            .addBoxes(box("a")).addBoxes(worded("s", "Stocks")).build())
        assertTrue("the stocks box is not the meeting's", BoxCreate.isWaiting(meeting))
        assertTrue(!BoxCreate.isWaiting(stocks))
        assertTrue(BoxCreate.sameWords("  My next\tmeeting ", "my next meeting"))
        assertTrue(!BoxCreate.sameWords("", ""))
    }

    @Test
    fun `a connection lost after the add was sent keeps waiting`() {
        HomeBoxes.apply(app, set("a"))
        val p = BoxCreate.start(app, "my next meeting")
        BoxCreate.turnEnded(app, p, replied = false, carriedBoxes = false, expectsReply = false, mayHaveHappened = true)
        assertTrue(BoxCreate.isWaiting(p))
        assertTrue(said.isEmpty())
        HomeBoxes.apply(app, set("a", "fresh"))
        assertTrue(BoxCreate.waiting().isEmpty())
    }

    @Test
    fun `the cap does not end a wait whose turn is still out, its late reply decides`() {
        BoxCreate.capMsForTest = 400L
        BoxCreate.graceMsForTest = 300L
        HomeBoxes.apply(app, set("a"))
        val p = BoxCreate.start(app, "my next meeting")
        idle(1_000)
        assertTrue("the turn is still out", BoxCreate.isWaiting(p))
        assertTrue(said.isEmpty())
        BoxCreate.turnEnded(app, p, replied = true, carriedBoxes = false, expectsReply = false)
        assertTrue("a grace from the reply", BoxCreate.isWaiting(p))
        idle(500)
        assertTrue(BoxCreate.waiting().isEmpty())
        assertEquals(listOf(R.string.boxes_create_failed), said.toList())
    }

    @Test
    fun `a turn never heard from still ends at the turn cap`() {
        BoxCreate.capMsForTest = 400L
        BoxCreate.turnCapMsForTest = 120_000L
        HomeBoxes.apply(app, set("a"))
        val p = BoxCreate.start(app, "my next meeting")
        idle(500)
        assertTrue(BoxCreate.isWaiting(p))
        idle(121_000)
        assertTrue(BoxCreate.waiting().isEmpty())
        assertEquals(listOf(R.string.boxes_create_failed), said.toList())
    }

    @Test
    fun `a reply asking something back goes quietly at the cap`() {
        BoxCreate.capMsForTest = 400L
        HomeBoxes.apply(app, set("a"))
        val p = BoxCreate.start(app, "my next meeting")
        BoxCreate.turnEnded(app, p, replied = true, carriedBoxes = true, expectsReply = true)
        idle(1_000)
        assertTrue(BoxCreate.waiting().isEmpty())
        assertTrue("the answer may still make it; no failure is said", said.isEmpty())
    }

    @Test
    fun `the spinner runs only while on screen, and a replaced one does not come back`() {
        val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root = android.widget.FrameLayout(a).also { a.setContentView(it) }
        settle()
        val arc = View(a)
        var current: android.animation.Animator? = null
        val spin = BoxBoard.spinWhileAttached(arc) { current }
        current = spin
        root.addView(arc)
        assertTrue("on screen it spins", spin.isStarted)
        root.removeView(arc)
        assertTrue("gone from the window it stops", !spin.isStarted)
        root.addView(arc)
        assertTrue("back on screen it spins again", spin.isStarted)
        root.removeView(arc)
        current = null
        root.addView(arc)
        assertTrue("one a rebind replaced stays stopped", !spin.isStarted)
    }
}
