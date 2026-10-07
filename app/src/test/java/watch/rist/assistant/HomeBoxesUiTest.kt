package watch.rist.assistant

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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
import rist.v1.BoxSet
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import rist.v1.FeatureSet
import rist.v1.FeatureState
import rist.v1.HomeBox
import rist.v1.Speech
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The box row on the home screen, the expanded view, the sheets and the touch edits. */
@RunWith(RobolectricTestRunner::class)
class HomeBoxesUiTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val turns = CopyOnWriteArrayList<DeviceRequest>()
    private val edits = CopyOnWriteArrayList<BoxEdit>()

    @Before
    fun setUp() {
        HomeBoxes.resetForTest(app)
        HomeBoxes.shippedForTest = true
        Config.setFeatures(app, "")
        Transcript.clearForTest(app)
        StreamingCancel.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readByteArray()
                return if (request.requestUrl?.encodedPath == "/v1/device/boxes") {
                    edits += BoxEdit.parseFrom(body)
                    MockResponse().setResponseCode(503)
                } else {
                    turns += DeviceRequest.parseFrom(body)
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "application/x-protobuf")
                        .setHeadersDelay(300, TimeUnit.MILLISECONDS)
                        .setBody(Buffer().write(DeviceResponse.newBuilder().setIsFinal(true)
                            .setSpeech(Speech.newBuilder().setText("You have 2 new emails.")).build().toByteArray()))
                }
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
        Config.setFeatures(app, "")
        Transcript.clearForTest(app)
    }

    private fun box(
        id: String, title: String = id, kind: String = "display", state: String = "ok",
        value: String = "", command: String = "", note: String = "", body: String = "",
        words: String = "",
    ): HomeBox = HomeBox.newBuilder().setId(id).setTitle(title).setKind(kind).setState(state)
        .setValue(value).setCommand(command).setNote(note).setBody(body).setSourceWords(words)
        .setUpdatedAtEpochS(System.currentTimeMillis() / 1000 - 480).build()

    private fun hold(vararg boxes: HomeBox) =
        HomeBoxes.apply(app, BoxSet.newBuilder().setVersion(3).addAllBoxes(boxes.toList()).build())

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun home(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get().also { settle() }

    private fun tile(a: android.app.Activity, id: String): ViewGroup =
        requireNotNull(a.window.decorView.findViewWithTag<ViewGroup>(BoxBoard.TILE_TAG_PREFIX + id)) { "no tile for $id" }

    private fun text(v: View, tag: String) = v.findViewWithTag<TextView>(tag)?.text?.toString()

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
            settle()
        }
    }

    private fun features(state: String) = Features.apply(app, FeatureSet.newBuilder()
        .addFeatures(FeatureState.newBuilder().setFeature("boxes").setState(state)).build())

    // ---- showing the row ----

    @Test
    fun `the row takes no space when boxes do not ship or the feature is off`() {
        hold(box("t", value = "54°"))
        HomeBoxes.shippedForTest = false
        val a = home()
        assertEquals(View.GONE, a.findViewById<View>(R.id.boxRow).visibility)
        HomeBoxes.shippedForTest = true
        features("off")
        settle()
        assertEquals(View.GONE, a.findViewById<View>(R.id.boxRow).visibility)
        features("on")
        settle()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.boxRow).visibility)
    }

    @Test
    fun `with the row gone the answers keep the gap they had under the timer strip`() {
        HomeBoxes.shippedForTest = false
        val a = home()
        val lp = { a.findViewById<View>(R.id.replyScroll).layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams }
        assertEquals(0, lp().goneTopMargin)
        a.findViewById<View>(R.id.commandStrip).visibility = View.VISIBLE
        a.syncAnswerGap()
        assertEquals(a.resources.getDimensionPixelSize(R.dimen.gap), lp().goneTopMargin)
        a.findViewById<View>(R.id.commandStrip).visibility = View.GONE
        a.syncAnswerGap()
        assertEquals(0, lp().goneTopMargin)
    }

    @Test
    fun `the sheets' fields are named by their visible labels`() {
        val a = home()
        a.boxBoard.openAddSheet()
        settle()
        val root = ShadowDialog.getLatestDialog().window!!.decorView
        val words = root.findViewWithTag<EditText>(BoxSheet.TAG_WORDS)
        assertNull("a description would hide what was typed from TalkBack", words.contentDescription)
        val labels = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView && v !is EditText && v.labelFor == words.id) labels += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        assertEquals(1, labels.size)
        assertTrue(labels.single().text.isNotBlank())
    }

    @Test
    fun `on but empty, the row is one Add a box tile`() {
        val a = home()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.boxRow).visibility)
        assertNotNull(a.findViewById<View>(R.id.boxList).findViewWithTag<View>(BoxBoard.ADD_TAG))
    }

    @Test
    fun `each state draws on its tile, and the held list draws before any network`() {
        hold(
            box("t", title = "Temperature", value = "54°").toBuilder().setIcon("partly_cloudy_day").build(),
            box("e", title = "Temperature", state = "error", value = "54°", note = "Couldn't update"),
            box("o", title = "Stock", state = "off", note = "Not on yet"),
            box("c", title = "Check my email", kind = "command", command = "Check my email"),
        )
        HomeBoxes.forgetCacheForTest()
        val a = home()
        assertEquals("54°", text(tile(a, "t"), BoxBoard.VALUE_TAG))
        assertNull("a fresh value shows no age", text(tile(a, "t"), BoxBoard.DETAIL_TAG))
        assertNotNull("a named icon is drawn", tile(a, "t").findViewWithTag<View>(BoxBoard.ICON_TAG))
        assertNull("no icon named, none drawn", tile(a, "e").findViewWithTag<View>(BoxBoard.ICON_TAG))
        assertEquals("—", text(tile(a, "e"), BoxBoard.VALUE_TAG))
        assertEquals("Couldn't update", text(tile(a, "e"), BoxBoard.DETAIL_TAG))
        assertEquals("Not on yet", text(tile(a, "o"), BoxBoard.DETAIL_TAG))
        // The row scrolls: the last tiles are drawn once it is scrolled to them.
        a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.boxList).scrollToPosition(5)
        settle()
        assertEquals("Check my email", text(tile(a, "c"), BoxBoard.VALUE_TAG))
        assertEquals("Command box: Check my email. Double tap to send.", tile(a, "c").contentDescription)
        assertNotNull("the All tile follows the +", a.findViewById<View>(R.id.boxList).findViewWithTag<View>(BoxBoard.ALL_TAG))
        assertEquals(0, server.requestCount)
    }

    // ---- taps ----

    @Test
    fun `tapping a display box opens its full text and sends nothing`() {
        hold(box("n", title = "News", value = "7 new", body = "**Markets:** stocks rose.\n\n- a local headline"))
        val a = home()
        tile(a, "n").performClick()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(BoxExpandedActivity::class.java.name, started.component?.className)
        assertEquals("n", started.getStringExtra(BoxExpandedActivity.EXTRA_BOX_ID))
        val x = Robolectric.buildActivity(BoxExpandedActivity::class.java, started).setup().get()
        assertEquals("News", text(x.window.decorView, BoxExpandedActivity.TAG_TITLE))
        val body = text(x.window.decorView, BoxExpandedActivity.TAG_BODY)!!
        assertTrue(body, body.contains("Markets: stocks rose."))
        assertTrue(body, !body.contains("**"))
        assertTrue(text(x.window.decorView, BoxExpandedActivity.TAG_UPDATED)!!.startsWith("Updated "))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tapping a command box sends exactly its words with its id, and a second tap in flight is ignored`() {
        hold(box("c", title = "Email", kind = "command", command = "Check my email"))
        val a = home()
        tile(a, "c").performClick()
        settle()
        assertTrue("sending", HomeBoxes.isSending("c"))
        assertEquals("Sending…", text(tile(a, "c"), BoxBoard.DETAIL_TAG))
        tile(a, "c").performClick()
        settle()
        waitFor("the reply") { !HomeBoxes.isSending("c") }
        assertEquals(1, turns.size)
        val req = turns[0]
        assertEquals("Check my email", req.text)
        assertEquals("c", req.boxId)
        assertEquals("", req.targetToolId)
        assertTrue(req.requestId.isNotBlank() && req.utteranceId.isNotBlank())
        assertEquals(3L, req.boxesVersion)
        val entry = Transcript.all(app).last()
        assertEquals("Check my email", entry.prompt)
        assertEquals("the answer lands in the feed", "You have 2 new emails.", entry.answer)
        assertNull("and not in the box", text(tile(a, "c"), BoxBoard.DETAIL_TAG))
    }

    @Test
    fun `a tile tap lands in the feed exactly as the same words typed`() {
        hold(box("c", title = "Email", kind = "command", command = "Check my email"))
        val a = home()
        tile(a, "c").performClick()
        settle()
        waitFor("the tile's reply") { !HomeBoxes.isSending("c") && Transcript.all(app).lastOrNull()?.state == EntryState.ANSWERED }
        val fromTile = Transcript.all(app).last()
        val tileStatus = a.findViewById<TextView>(R.id.statusText)?.text?.toString()?.substringAfter("] ")
        a.findViewById<EditText>(R.id.textInput).apply {
            setText("Check my email")
            onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        }
        settle()
        waitFor("the typed reply") { Transcript.all(app).size == 2 && Transcript.all(app).last().state == EntryState.ANSWERED }
        val typed = Transcript.all(app).last()
        assertEquals(typed.prompt, fromTile.prompt)
        assertEquals(typed.answer, fromTile.answer)
        assertEquals(typed.state, fromTile.state)
        assertEquals(tileStatus, a.findViewById<TextView>(R.id.statusText)?.text?.toString()?.substringAfter("] "))
        assertEquals(2, turns.size)
        assertEquals(turns[1].text, turns[0].text)
        assertEquals(turns[1].targetToolId, turns[0].targetToolId)
    }

    // ---- sheets ----

    @Test
    fun `a one-tap tile from the add sheet is a touch edit with the exact words, not a turn`() {
        val a = home()
        a.findViewById<View>(R.id.boxList).findViewWithTag<View>(BoxBoard.ADD_TAG).performClick()
        settle()
        val d = ShadowDialog.getLatestDialog()
        val root = d.window!!.decorView
        root.findViewWithTag<View>(BoxSheet.TAG_COMMAND).performClick()
        root.findViewWithTag<EditText>(BoxSheet.TAG_WORDS).setText("Check my email, then text Sam")
        root.findViewWithTag<View>(BoxSheet.TAG_SUBMIT).performClick()
        settle()
        HomeBoxes.awaitFlushForTest()
        waitFor("the edit") { edits.isNotEmpty() }
        val add = edits.single()
        assertEquals(1, add.addCount)
        assertEquals("Check my email, then text Sam", add.getAdd(0).command)
        assertEquals("", add.getAdd(0).title)
        Thread.sleep(100); settle()
        assertTrue("no turn is sent", turns.isEmpty())
        // The backend answered 503: the add stays queued for the next connection.
        assertEquals(listOf(add.editId), HomeBoxes.queued(app).map { it.editId })
    }

    @Test
    fun `the add sheet defaults to a display box`() {
        val a = home()
        a.boxBoard.openAddSheet()
        settle()
        val root = ShadowDialog.getLatestDialog().window!!.decorView
        root.findViewWithTag<EditText>(BoxSheet.TAG_WORDS).setText("The temperature here, every 30 minutes")
        root.findViewWithTag<View>(BoxSheet.TAG_SUBMIT).performClick()
        settle()
        waitFor("the turn") { turns.isNotEmpty() }
        assertEquals("Add a display box: The temperature here, every 30 minutes", turns[0].text)
    }

    @Test
    fun `the edit sheet renames by touch edit and changes what it does by a turn naming the box`() {
        hold(box("c", title = "Email", kind = "command", command = "Check my email"))
        val a = home()
        a.boxBoard.openEditSheet(HomeBoxes.find(app, "c")!!)
        settle()
        val root = ShadowDialog.getLatestDialog().window!!.decorView
        assertEquals("Check my email", root.findViewWithTag<EditText>(BoxSheet.TAG_WORDS).text.toString())
        root.findViewWithTag<EditText>(BoxSheet.TAG_NAME).setText("Inbox")
        root.findViewWithTag<EditText>(BoxSheet.TAG_WORDS).setText("Check my work email")
        root.findViewWithTag<View>(BoxSheet.TAG_SUBMIT).performClick()
        settle()
        waitFor("the turn") { turns.isNotEmpty() }
        assertEquals("boxes", turns[0].targetToolId)
        assertEquals("c", turns[0].boxId)
        assertEquals("Change this box: Check my work email", turns[0].text)
        assertEquals("Inbox", HomeBoxes.find(app, "c")!!.title)
        HomeBoxes.awaitFlushForTest()
        assertEquals("c", edits.first().renameId)
        assertEquals("Inbox", edits.first().renameTitle)
    }

    @Test
    fun `the edit sheet starts with the box's own defining words, for display and command boxes`() {
        hold(
            box("d", title = "Weather", words = "the temperature here, every 30 minutes"),
            box("c", title = "Email", kind = "command", command = "Check my email", words = "check my email please"),
            box("e", title = "Old"),
        )
        val a = home()
        fun wordsIn(id: String): String {
            a.boxBoard.openEditSheet(HomeBoxes.find(app, id)!!)
            settle()
            val root = ShadowDialog.getLatestDialog().window!!.decorView
            return root.findViewWithTag<EditText>(BoxSheet.TAG_WORDS).text.toString()
        }
        assertEquals("the temperature here, every 30 minutes", wordsIn("d"))
        assertEquals("check my email please", wordsIn("c"))
        assertEquals("", wordsIn("e"))
    }

    @Test
    fun `the edit sheet shows only the user's words when the stored words carry the phone's own wrapper`() {
        hold(
            box("d", title = "Weather", words = "Add a display box: the temperature here"),
            box("c", title = "Email", kind = "command", command = "Check my email", words = "Add a command box: check my email"),
            box("x", title = "Rain", words = "Change this box: rain today"),
            box("n", title = "Nest", words = "Change this box: Add a display box: the tide"),
            box("p", title = "Plain", words = "add a display box: lower case is the user's own"),
        )
        val a = home()
        fun wordsIn(id: String): String {
            a.boxBoard.openEditSheet(HomeBoxes.find(app, id)!!)
            settle()
            val root = ShadowDialog.getLatestDialog().window!!.decorView
            return root.findViewWithTag<EditText>(BoxSheet.TAG_WORDS).text.toString()
        }
        assertEquals("the temperature here", wordsIn("d"))
        assertEquals("check my email", wordsIn("c"))
        assertEquals("rain today", wordsIn("x"))
        assertEquals("the tide", wordsIn("n"))
        assertEquals("add a display box: lower case is the user's own", wordsIn("p"))
    }

    @Test
    fun `saving a wrapped tile neither resends the wrapper nor nests it`() {
        hold(box("d", title = "Weather", words = "Add a display box: the temperature here"))
        val a = home()
        a.boxBoard.openEditSheet(HomeBoxes.find(app, "d")!!)
        settle()
        var root = ShadowDialog.getLatestDialog().window!!.decorView
        root.findViewWithTag<View>(BoxSheet.TAG_SUBMIT).performClick()
        settle()
        Thread.sleep(100); settle()
        assertTrue("unchanged words send nothing", turns.isEmpty())

        a.boxBoard.openEditSheet(HomeBoxes.find(app, "d")!!)
        settle()
        root = ShadowDialog.getLatestDialog().window!!.decorView
        root.findViewWithTag<EditText>(BoxSheet.TAG_WORDS).setText("the temperature here in Celsius")
        root.findViewWithTag<View>(BoxSheet.TAG_SUBMIT).performClick()
        settle()
        waitFor("the turn") { turns.isNotEmpty() }
        assertEquals("Change this box: the temperature here in Celsius", turns[0].text)
    }

    @Test
    fun `unchanged defining words send no turn`() {
        hold(box("d", title = "Weather", words = "the temperature here"))
        val a = home()
        a.boxBoard.openEditSheet(HomeBoxes.find(app, "d")!!)
        settle()
        val root = ShadowDialog.getLatestDialog().window!!.decorView
        root.findViewWithTag<View>(BoxSheet.TAG_SUBMIT).performClick()
        settle()
        Thread.sleep(100); settle()
        assertTrue(turns.isEmpty())
    }

    // ---- edits ----

    private fun runAction(v: View, label: String) {
        val node = AccessibilityNodeInfoCompat.wrap(v.createAccessibilityNodeInfo())
        val action = node.actionList.firstOrNull { it.label == label }
        assertNotNull("no '$label' action", action)
        v.performAccessibilityAction(action!!.id, null)
        settle()
    }

    @Test
    fun `every box can be moved, edited and deleted without a drag, with an undo`() {
        hold(box("a"), box("b"), box("c"))
        val a = home()
        val labels = AccessibilityNodeInfoCompat.wrap(tile(a, "b").createAccessibilityNodeInfo())
            .actionList.mapNotNull { it.label?.toString() }
        assertTrue(labels.toString(), labels.containsAll(listOf("Move left", "Move right", "Edit", "Delete")))

        runAction(tile(a, "a"), "Move right")
        assertEquals(listOf("b", "a", "c"), HomeBoxes.boxes(app).map { it.id })

        runAction(tile(a, "c"), "Delete")
        assertEquals(listOf("b", "a"), HomeBoxes.boxes(app).map { it.id })
        val undo = a.findViewById<TextView>(R.id.boxUndo)
        assertEquals(View.VISIBLE, undo.visibility)
        assertEquals("Tile removed · UNDO", undo.text.toString())
        undo.performClick()
        settle()
        assertEquals(listOf("b", "a", "c"), HomeBoxes.boxes(app).map { it.id })
        assertEquals(View.GONE, undo.visibility)

        HomeBoxes.awaitFlushForTest()
        // The backend answered 503: everything stays queued, in order, for the next connection.
        val q = HomeBoxes.queued(app)
        assertEquals(3, q.size)
        assertEquals(listOf("b", "a", "c"), q[0].orderList)
        assertEquals(listOf("c"), q[1].deleteIdsList)
        assertEquals(listOf("c"), q[2].restoreIdsList)
    }

    @Test
    fun `the undo goes after five seconds`() {
        hold(box("a"), box("b"))
        val a = home()
        runAction(tile(a, "a"), "Delete")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(HomeBoxes.UNDO_MS + 100))
        assertEquals(View.GONE, a.findViewById<View>(R.id.boxUndo).visibility)
    }

    @Test
    fun `a long press enters edit mode, with remove and edit on each box and Done in place of +`() {
        hold(box("a"), box("b"))
        val a = home()
        tile(a, "a").performLongClick()
        settle()
        assertTrue(a.boxBoard.editMode)
        val list = a.findViewById<View>(R.id.boxList)
        assertNotNull(list.findViewWithTag<View>(BoxBoard.DONE_TAG))
        assertNull(list.findViewWithTag<View>(BoxBoard.ADD_TAG))
        assertNotNull(tile(a, "b").findViewWithTag<View>(BoxBoard.DELETE_TAG))
        tile(a, "b").findViewWithTag<View>(BoxBoard.DELETE_TAG).performClick()
        settle()
        assertEquals(listOf("a"), HomeBoxes.boxes(app).map { it.id })
        list.findViewWithTag<View>(BoxBoard.DONE_TAG).performClick()
        settle()
        assertTrue(!a.boxBoard.editMode)
        // A tap in edit mode never sends or opens anything.
        assertEquals(0, turns.size)
    }

    @Test
    fun `the All boxes grid shows every box in the row's order`() {
        hold(box("a", value = "1"), box("b", kind = "command", command = "go"), box("c", value = "3"))
        val g = Robolectric.buildActivity(AllBoxesActivity::class.java).setup().get()
        settle()
        val root = g.window.decorView
        assertEquals("3 tiles · hold one to move, edit or delete",
            root.findViewWithTag<TextView>(AllBoxesActivity.TAG_COUNT).text.toString())
        for (id in listOf("a", "b", "c")) assertNotNull(root.findViewWithTag<View>(BoxBoard.TILE_TAG_PREFIX + id))
        assertNotNull(root.findViewWithTag<View>(BoxBoard.ADD_TAG))
        // A command tap goes home to be sent.
        root.findViewWithTag<View>(BoxBoard.TILE_TAG_PREFIX + "b").performClick()
        val result = shadowOf(g).resultIntent
        assertEquals(HomeBoxes.Turn("go", "", "b", "go"), AllBoxesActivity.turnFrom(result))
    }
}
