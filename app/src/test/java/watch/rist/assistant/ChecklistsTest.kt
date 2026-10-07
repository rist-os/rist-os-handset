package watch.rist.assistant

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import rist.v1.BoxSet
import rist.v1.Checklist
import rist.v1.ChecklistItem
import rist.v1.HomeBox
import rist.v1.ItemCheckBatch
import java.util.concurrent.CopyOnWriteArrayList

/** Checkable list items: the tick queue, what it sends and keeps, and the rows that draw it. */
@RunWith(RobolectricTestRunner::class)
class ChecklistsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val batches = CopyOnWriteArrayList<ItemCheckBatch>()
    private val paths = CopyOnWriteArrayList<String>()
    @Volatile private var status = 204
    @Volatile private var headers = emptyMap<String, String>()

    @Before
    fun setUp() {
        Checklists.resetForTest(ctx)
        ChecklistView.resetForTest()
        HomeBoxes.resetForTest(ctx)
        Transcript.clearForTest(ctx)
        StreamingCancel.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.requestUrl!!.encodedPath
                if (request.requestUrl!!.encodedPath == "/v1/device/items") {
                    batches += ItemCheckBatch.parseFrom(request.body.readByteArray())
                }
                return MockResponse().setResponseCode(status).apply { headers.forEach { (k, v) -> setHeader(k, v) } }
            }
        }
        server.start()
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
    }

    @After
    fun tidy() {
        Checklists.awaitFlushForTest()
        server.shutdown()
        Checklists.resetForTest(ctx)
        HomeBoxes.resetForTest(ctx)
        Transcript.clearForTest(ctx)
        Config.clearBillingLapse(ctx)
    }

    private fun item(id: String, text: String = id, checked: Boolean = false) =
        ChecklistItem.newBuilder().setId(id).setText(text).setChecked(checked).build()

    private fun list(name: String, vararg items: ChecklistItem, title: String = "") =
        Checklist.newBuilder().setList(name).setTitle(title).addAllItems(items.toList()).build()

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** A tap whose send waits: the backend is briefly unable. */
    private fun tapOffline(id: String, checked: Boolean, shown: Boolean) {
        status = 503
        Checklists.tap(ctx, id, checked, shown)
        Checklists.awaitFlushForTest()
    }

    private fun answeredCard(vararg lists: Checklist): Long {
        val id = Transcript.begin(ctx, "what's on my shopping list", EntryState.WAITING)
        Transcript.update(ctx, id, state = EntryState.ANSWERED, answer = "Here it is.", checklists = lists.toList())
        return id
    }

    private fun cardItems() = Transcript.all(ctx).flatMap { it.checklists }.flatMap { it.itemsList }

    // ---- the queue ----

    @Test
    fun `several taps on one item queue one tick, the latest state, remembering the first before`() {
        tapOffline("milk", checked = true, shown = false)
        tapOffline("milk", checked = false, shown = true)
        tapOffline("milk", checked = true, shown = false)
        val q = Checklists.queued(ctx)
        assertEquals(1, q.size)
        assertTrue(q.single().checked)
        assertFalse("the state to go back to is the one before the first tap", q.single().before)
        assertTrue(Checklists.shown(ctx, "milk", serverChecked = false))
    }

    @Test
    fun `the queue is kept in storage, so it outlives a restart`() {
        tapOffline("eggs", checked = true, shown = false)
        assertTrue(Config.itemCheckQueue(ctx).contains("eggs"))
        val again = Checklists.queued(ctx).single()
        assertEquals("eggs", again.itemId)
        assertTrue(again.atMs > 0)
    }

    @Test
    fun `an accepted tick goes to the items endpoint as the state wanted, and leaves the queue`() {
        tapOffline("milk", checked = true, shown = false)
        status = 204
        assertEquals(1, Checklists.flush(ctx))
        assertTrue(Checklists.queued(ctx).isEmpty())
        assertTrue(paths.all { it == "/v1/device/items" })
        val sent = batches.last().checksList.single()
        assertEquals("milk", sent.itemId)
        assertTrue(sent.checked)
        assertTrue(sent.atEpochMs > 0)
        assertTrue(sent.checkId.isNotBlank())
    }

    @Test
    fun `an accepted tick is held over the lists until one agrees, or for two minutes at most`() {
        var now = 1_000_000L
        Checklists.clock = { now }
        tapOffline("milk", checked = true, shown = false)
        status = 204
        Checklists.flush(ctx)
        assertTrue("a list sent before the tick does not flip it back", Checklists.shown(ctx, "milk", false))

        // A list that agrees ends the hold: the backend is the authority again.
        Checklists.observe(listOf(HomeBox.newBuilder().setId("b")
            .addChecklists(list("shopping", item("milk", checked = true))).build()))
        assertFalse(Checklists.shown(ctx, "milk", false))

        tapOffline("eggs", checked = true, shown = false)
        status = 204
        Checklists.flush(ctx)
        now += Checklists.HOLD_MS + 1
        assertFalse(Checklists.shown(ctx, "eggs", false))
    }

    @Test
    fun `ticks go at most fifty to a request`() {
        status = 503
        for (i in 1..120) Checklists.tap(ctx, "i$i", true, false)
        Checklists.awaitFlushForTest()
        assertEquals(120, Checklists.queued(ctx).size)
        batches.clear()
        status = 204
        assertEquals(120, Checklists.flush(ctx))
        assertEquals(listOf(50, 50, 20), batches.map { it.checksCount })
    }

    @Test
    fun `offline, auth, busy and broken backends keep the tick for the next try`() {
        for (code in listOf(401, 403, 408, 429, 500, 503)) {
            tapOffline("milk", checked = true, shown = false)
            status = code
            assertEquals("HTTP $code", 0, Checklists.flush(ctx))
            assertEquals("HTTP $code", 1, Checklists.queued(ctx).size)
        }
        server.shutdown()
        assertEquals(0, Checklists.flush(ctx))
        assertEquals(1, Checklists.queued(ctx).size)
        assertTrue(Checklists.shown(ctx, "milk", false))
    }

    @Test
    fun `a lapsed subscription keeps the tick and records the lapse`() {
        tapOffline("milk", checked = true, shown = false)
        status = 402
        headers = mapOf("X-Rist-Billing" to "lapsed")
        assertEquals(0, Checklists.flush(ctx, fromTap = true))
        assertEquals(1, Checklists.queued(ctx).size)
        assertTrue(Config.billingLapse(ctx).isNotBlank())
        settle()
        assertTrue(ShadowToast.getTextOfLatestToast().isNotBlank())
    }

    @Test
    fun `notes off, or any other refusal, drops the tick, puts the box back and says so`() {
        for (code in listOf(409, 400, 404, 413)) {
            Transcript.clearForTest(ctx)
            answeredCard(list("shopping", item("milk")))
            tapOffline("milk", checked = true, shown = false)
            assertTrue("the card keeps the tap", cardItems().single().checked)
            status = code
            assertEquals("HTTP $code", 1, Checklists.flush(ctx))
            assertTrue("HTTP $code", Checklists.queued(ctx).isEmpty())
            assertFalse("HTTP $code: the card goes back", cardItems().single().checked)
            assertFalse("HTTP $code: the tile goes back", Checklists.shown(ctx, "milk", false))
            settle()
            assertEquals(ctx.getString(R.string.checklist_save_failed), ShadowToast.getTextOfLatestToast())
        }
    }

    // ---- what the phone declares ----

    @Test
    fun `the component is declared on turns, on the wake and on the boxes endpoint`() {
        assertTrue(DeviceProfile.capabilities(1080, 2400, checklists = true).componentsList.contains(Checklists.COMPONENT))
        assertFalse(DeviceProfile.capabilities(1080, 2400, checklists = false).componentsList.contains(Checklists.COMPONENT))

        val wake = WakeLoop.wakeUrl("https://api.example/v1/device", emptyList(), 8, checklists = true)!!.toHttpUrl()
        assertEquals(Checklists.COMPONENT, wake.queryParameter("components"))
        val withBoxes = WakeLoop.wakeUrl("https://api.example/v1/device", emptyList(), 8, boxesVersion = 4, checklists = true)!!.toHttpUrl()
        assertEquals("home_boxes,checklist_v1", withBoxes.queryParameter("components"))

        val boxes = HomeBoxes.boxesUrl("https://api.example/v1/device", checklists = true)!!.toHttpUrl()
        assertEquals("/v1/device/boxes", boxes.encodedPath)
        assertEquals(Checklists.COMPONENT, boxes.queryParameter("components"))
        assertNull(HomeBoxes.boxesUrl("https://api.example/v1/device", checklists = false)!!.toHttpUrl().queryParameter("components"))

        assertEquals("https://api.example/v1/device/items", Checklists.itemsUrl("https://api.example/v1/device/"))
        assertEquals("https://api.example/v1/device/items", Checklists.itemsUrl("https://api.example"))
        assertNull(Checklists.itemsUrl(""))
    }

    @Test
    fun `a phone that starts declaring checklists asks for the whole box list once`() {
        HomeBoxes.shippedForTest = true
        Checklists.shippedForTest = false
        HomeBoxes.apply(ctx, BoxSet.newBuilder().setVersion(7)
            .addBoxes(HomeBox.newBuilder().setId("shop").setKind("display").setState("ok")).build())
        assertEquals("7", WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl().queryParameter("boxes"))

        // The update that declares checklists: the list held has none, so the wake asks from 0.
        Checklists.shippedForTest = true
        assertEquals("0", WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl().queryParameter("boxes"))
        assertEquals("0", WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl().queryParameter("boxes"))

        // A list arrives (here on the wake); from then on the held version is asked with.
        WakeLoop.apply(ctx, rist.v1.WakeSignal.newBuilder().setBoxes(BoxSet.newBuilder().setVersion(7)
            .addBoxes(HomeBox.newBuilder().setId("shop").setKind("display").setState("ok")
                .addChecklists(list("shopping", item("m", "milk"))))).build(), emptyList())
        assertEquals("7", WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl().queryParameter("boxes"))
        assertTrue("kept across a restart", Config.checklistBoxesSeen(ctx))
    }

    // ---- reply cards ----

    @Test
    fun `a reply card's lists are kept with its entry, and a tick lands on every card that shows the item`() {
        val a = answeredCard(list("shopping", item("milk"), item("eggs"), title = "Shopping list"))
        answeredCard(list("shopping", item("milk")))
        Transcript.setItemChecked(ctx, "milk", true)
        Transcript.reloadForTest(ctx)
        val first = Transcript.all(ctx).first { it.localId == a }
        assertEquals("Shopping list", first.checklists.single().title)
        assertEquals(listOf("milk", "eggs"), first.checklists.single().itemsList.map { it.text })
        assertEquals(2, cardItems().count { it.id == "milk" && it.checked })
        assertFalse(cardItems().first { it.id == "eggs" }.checked)
    }

    @Test
    fun `the feed draws a card's rows with verbatim text, and a tap sends at once`() {
        answeredCard(list("shopping", item("m1", "2 x *oat* milk"), item("e1", "eggs", checked = true)))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val rows = boxesIn(home.window.decorView)
        assertEquals(listOf("2 x *oat* milk", "eggs"), rows.map { it.text.toString() })
        assertEquals(listOf(false, true), rows.map { it.isChecked })
        assertTrue("a checked row is struck through",
            rows[1].paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG != 0)

        status = 204
        rows[0].performClick()
        Checklists.awaitFlushForTest()
        assertTrue(rows[0].isChecked)
        assertTrue(rows[0].paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG != 0)
        assertEquals("m1", batches.single().checksList.single().itemId)
        assertTrue(cardItems().first { it.id == "m1" }.checked)
    }

    /**
     * A finger put down on [v] at [fromX] across its width, moved by [dx] and [dy] in ten steps
     * and lifted, all sent through the window as the screen would send it.
     */
    private fun drag(root: View, v: View, fromX: Float, dx: Float, dy: Float = 0f) {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        val x0 = at[0] + v.width * fromX
        val y0 = at[1] + v.height / 2f
        val t0 = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, y: Float, t: Long) = android.view.MotionEvent.obtain(t0, t, action, x, y, 0)
        root.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, x0, y0, t0))
        for (i in 1..10) root.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, x0 + dx * i / 10, y0 + dy * i / 10, t0 + i * 30L))
        root.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, x0 + dx, y0 + dy, t0 + 330L))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a swipe that starts on a card's row clears the card and ticks nothing`() {
        answeredCard(list("shopping", item("f1", "2 tablespoons flour"), item("s1", "sugar")))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val root = home.window.decorView
        val row = boxesIn(root).first { it.text == "2 tablespoons flour" }
        assertTrue("the row is on screen", row.width > 0 && row.height > 0)
        drag(root, row, fromX = 0.15f, dx = row.width * 0.75f)
        Checklists.awaitFlushForTest()
        assertFalse("the row was not ticked", row.isChecked)
        assertTrue("nothing was sent", batches.isEmpty())
        assertTrue("the card was cleared", Transcript.all(ctx).isEmpty())
        assertTrue("and is gone from the screen", boxesIn(root).isEmpty())
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a drag that moves past the slop but is not a swipe ticks nothing either`() {
        answeredCard(list("shopping", item("f1", "2 tablespoons flour")))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val root = home.window.decorView
        val row = boxesIn(root).single()
        val slop = android.view.ViewConfiguration.get(home).scaledTouchSlop
        // Sideways enough to pass the slop, too steep to be a swipe, too short to scroll.
        drag(root, row, fromX = 0.3f, dx = slop * 1.5f, dy = slop * 0.9f)
        Checklists.awaitFlushForTest()
        assertFalse(row.isChecked)
        assertTrue(batches.isEmpty())
        assertEquals("the card stays", 1, Transcript.all(ctx).size)
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tap on a card's row ticks it`() {
        answeredCard(list("shopping", item("f1", "2 tablespoons flour")))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val root = home.window.decorView
        val row = boxesIn(root).single()
        drag(root, row, fromX = 0.3f, dx = 0f)
        Checklists.awaitFlushForTest()
        assertTrue("ticked", row.isChecked)
        assertEquals("f1", batches.single().checksList.single().itemId)
        assertEquals("the card stays", 1, Transcript.all(ctx).size)
    }

    // ---- the expanded tile ----

    private fun tileWith(vararg lists: Checklist) = HomeBoxes.apply(ctx, BoxSet.newBuilder().setVersion(2)
        .addBoxes(HomeBox.newBuilder().setId("shop").setTitle("Shopping").setKind("display").setState("ok")
            .setValue("3").setBody("- milk\n- eggs").addAllChecklists(lists.toList())).build())

    private fun boxesIn(v: View): List<CheckBox> {
        val out = ArrayList<CheckBox>()
        fun walk(x: View) {
            if (x is CheckBox) out += x
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
    }

    private fun expanded() = Robolectric.buildActivity(BoxExpandedActivity::class.java,
        BoxExpandedActivity.intent(ctx, "shop")).setup().get().also { settle() }

    @Test
    fun `a tile with checklists draws every section, lists as rows, the rest as text`() {
        tileWith(
            list("", title = "Today").toBuilder().setMarkdown("**Rain** later").build(),
            list("shopping", item("m", "milk"), item("e", "eggs"), title = "Shopping"),
            list("todo", title = "To-do").toBuilder().setMarkdown("Nothing to do").build(),
        )
        val x = expanded()
        val root = x.window.decorView
        assertEquals(View.GONE, root.findViewWithTag<View>(BoxExpandedActivity.TAG_BODY).visibility)
        val lists = root.findViewWithTag<ViewGroup>(BoxExpandedActivity.TAG_LISTS)
        assertEquals(View.VISIBLE, lists.visibility)
        assertEquals(listOf("milk", "eggs"), boxesIn(lists).map { it.text.toString() })
        val texts = (0 until lists.childCount).map { lists.getChildAt(it) }
            .filter { it !is CheckBox }.map { (it as android.widget.TextView).text.toString() }
        assertTrue(texts.contains("Rain later"))
        assertTrue(texts.contains("Nothing to do"))
    }

    @Test
    fun `a tile without checklists still draws its body`() {
        tileWith()
        val root = expanded().window.decorView
        assertEquals(View.VISIBLE, root.findViewWithTag<View>(BoxExpandedActivity.TAG_BODY).visibility)
        assertEquals(View.GONE, root.findViewWithTag<View>(BoxExpandedActivity.TAG_LISTS).visibility)
    }

    @Test
    fun `a row ticked on an open tile stays until the view closes, then is hidden`() {
        tileWith(list("shopping", item("m", "milk"), item("e", "eggs")))
        val x = expanded()
        status = 204
        boxesIn(x.window.decorView).first { it.text == "milk" }.performClick()
        Checklists.awaitFlushForTest()
        settle()
        // The tile's next list no longer holds the checked item.
        tileWith(list("shopping", item("e", "eggs")))
        settle()
        val rows = boxesIn(x.window.decorView)
        assertEquals("the ticked row keeps its place", listOf("milk", "eggs"), rows.map { it.text.toString() })
        assertTrue(rows[0].isChecked)

        x.finish()
        val again = expanded()
        assertEquals(listOf("eggs"), boxesIn(again.window.decorView).map { it.text.toString() })
    }

    @Test
    fun `a section listing checked items shows them struck through`() {
        tileWith(list("shopping", item("m", "milk", checked = true), item("e", "eggs")))
        val rows = boxesIn(expanded().window.decorView)
        assertEquals(listOf("milk", "eggs"), rows.map { it.text.toString() })
        assertTrue(rows[0].isChecked)
        assertFalse(rows[1].isChecked)
    }

    @Test
    fun `a tick still queued is laid over a list that has not caught up`() {
        tapOffline("e", checked = true, shown = false)
        tileWith(list("shopping", item("m", "milk"), item("e", "eggs")))
        assertEquals("a checked item is hidden on a fresh view", listOf("milk"),
            boxesIn(expanded().window.decorView).map { it.text.toString() })
    }

    @Test
    fun `a refused tick puts the open row back`() {
        tileWith(list("shopping", item("m", "milk")))
        val x = expanded()
        status = 409
        headers = mapOf("X-Rist-Feature" to "notes-off")
        val row = boxesIn(x.window.decorView).single()
        row.performClick()
        Checklists.awaitFlushForTest()
        settle()
        assertFalse(row.isChecked)
        assertTrue(Checklists.queued(ctx).isEmpty())
    }

    // ---- one state per item, everywhere it is shown ----

    private fun feedRows(home: MainActivity, id: String) =
        boxesIn(home.window.decorView).filter { it.text.toString() == id }

    @Test
    fun `a tap on one card ticks the same item on an older card too`() {
        val older = answeredCard(list("shopping", item("m1", "milk")))
        answeredCard(list("shopping", item("m1", "milk"), item("e1", "eggs")))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val rows = feedRows(home, "milk")
        assertEquals(2, rows.size)

        status = 204
        rows[0].performClick()
        Checklists.awaitFlushForTest()
        settle()
        assertTrue("the older card's row follows", rows[1].isChecked)
        assertTrue(Transcript.all(ctx).first { it.localId == older }.checklists.single().itemsList.single().checked)
        assertEquals("one tap sends one tick", 1, batches.single().checksCount)
    }

    @Test
    fun `a newer reply's state reaches older cards, stored and on screen`() {
        val older = answeredCard(list("shopping", item("m1", "milk")))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val row = feedRows(home, "milk").single()
        assertFalse(row.isChecked)

        answeredCard(list("shopping", item("m1", "milk", checked = true)))
        settle()
        assertTrue("the row already on screen follows", row.isChecked)
        assertTrue(Transcript.all(ctx).first { it.localId == older }.checklists.single().itemsList.single().checked)
        assertTrue("nothing is sent for it", Checklists.queued(ctx).isEmpty())
    }

    @Test
    fun `a newer box list reaches reply cards and an open tile`() {
        val older = answeredCard(list("shopping", item("m1", "milk", checked = true)))
        tileWith(list("shopping_all", item("m1", "milk", checked = true), item("e", "eggs", checked = true)))
        val x = expanded()
        val tileRow = boxesIn(x.window.decorView).first { it.text.toString() == "milk" }
        assertTrue(tileRow.isChecked)

        tileWith(list("shopping_all", item("m1", "milk"), item("e", "eggs", checked = true)))
        settle()
        assertFalse("the open tile row follows", tileRow.isChecked)
        assertFalse("the reply card follows", Transcript.all(ctx).first { it.localId == older }
            .checklists.single().itemsList.single().checked)
    }

    @Test
    fun `a tick not yet accepted still wins over a newer list`() {
        answeredCard(list("shopping", item("m1", "milk")))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val row = feedRows(home, "milk").single()
        tapOffline("m1", checked = true, shown = false)
        settle()
        assertTrue(row.isChecked)

        tileWith(list("shopping", item("m1", "milk")))
        settle()
        assertTrue("the queued tick is laid over the stale list", row.isChecked)
    }
}
