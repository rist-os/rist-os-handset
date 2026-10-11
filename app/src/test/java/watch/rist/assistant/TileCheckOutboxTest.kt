package watch.rist.assistant

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import rist.v1.BoxSet
import rist.v1.HomeBox
import rist.v1.ItemCheckBatch
import rist.v1.ItemEditBatch
import rist.v1.ItemEditReply
import rist.v1.ItemOutcome
import rist.v1.TileBlock
import rist.v1.TileRow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owner report 2026-10-10: ticks on the to-do tile came back unticked. Every tick reached the
 * backend; the open tile redrew a row it kept from the row as first sent (unticked) once the hold
 * ran out. A tick is now kept as ticked, persisted before it is sent, laid over every refresh
 * until it is answered, answered per tick (v33), retried, and never silently undone.
 */
@RunWith(RobolectricTestRunner::class)
class TileCheckOutboxTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val edits = CopyOnWriteArrayList<ItemEditBatch>()
    private val legacy = CopyOnWriteArrayList<ItemCheckBatch>()

    /** The edit route's HTTP status, and its answer per tick; null = an older backend (no answer). */
    @Volatile private var status = 200
    @Volatile private var answer: ((ItemEditBatch) -> ItemEditReply)? = { b -> reply(b, "saved") }
    @Volatile private var editRoute = true

    @Before
    fun setUp() {
        HomeBoxes.resetForTest(ctx)
        Checklists.resetForTest(ctx)
        ChecklistView.resetForTest()
        Transcript.clearForTest(ctx)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/v1/device/items") {
                    legacy += ItemCheckBatch.parseFrom(request.body.readByteArray())
                    return MockResponse().setResponseCode(if (status == 200) 204 else status)
                }
                if (path != "/v1/device/items/edit" || !editRoute) return MockResponse().setResponseCode(404)
                val batch = ItemEditBatch.parseFrom(request.body.readByteArray())
                edits += batch
                if (status != 200) return MockResponse().setResponseCode(status)
                val r = answer?.invoke(batch) ?: ItemEditReply.getDefaultInstance()
                return MockResponse().setResponseCode(200).setBody(Buffer().write(r.toByteArray()))
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
        HomeBoxes.resetForTest(ctx)
        Checklists.resetForTest(ctx)
        Transcript.clearForTest(ctx)
    }

    private fun reply(b: ItemEditBatch, outcome: String, checked: Boolean? = null, note: String = "") =
        ItemEditReply.newBuilder().addAllOutcomes(b.checksList.map {
            ItemOutcome.newBuilder().setOpId(it.checkId).setOutcome(outcome).setItemId(it.itemId)
                .setChecked(checked ?: it.checked).setNote(note).build()
        }).build()

    private fun item(id: String, text: String, checked: Boolean = false): TileRow = TileRow.newBuilder()
        .setId(id).setText(text).setTarget("item").setCheckable(true).setChecked(checked).setEditText(text).build()

    private fun todo(vararg rows: TileRow): TileBlock = TileBlock.newBuilder().setKind("checklist")
        .addAllRows(rows.toList()).setAddTo("todo").setFallbackMarkdown("-").build()

    private var version = 1L

    private fun tileWith(vararg blocks: TileBlock) {
        HomeBoxes.apply(ctx, BoxSet.newBuilder().setVersion(++version).addBoxes(
            HomeBox.newBuilder().setId("t").setKind("display").setState("ok").setTitle("My to-do list")
                .setValue("3").setBody("- x").addAllBlocks(blocks.toList())).build())
        settle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private var controller: ActivityController<BoxExpandedActivity>? = null

    private fun expanded(): View {
        controller = Robolectric.buildActivity(BoxExpandedActivity::class.java, BoxExpandedActivity.intent(ctx, "t")).setup()
        settle()
        return controller!!.get().window.decorView
    }

    private fun all(root: View, tag: String): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            if (v.tag == tag) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun boxes(root: View) = all(root, ChecklistView.TAG_ROW).map { it as CheckBox }
    private fun box(root: View, text: String) = boxes(root).single { it.contentDescription == text }
    private fun lines(root: View) = all(root, TileBlockView.TAG_STATUS).map { (it as TextView).text.toString() }

    private fun tap(root: View, text: String) {
        box(root, text).performClick()
        Checklists.awaitFlushForTest()
        settle()
    }

    // ---- the reported bug ----

    @Test
    fun `a ticked row kept on an open tile stays ticked after the hold ends and the view comes back`() {
        var now = 1_000_000L
        Checklists.clock = { now }
        tileWith(todo(item("c", "Cheese"), item("t", "Tomato")))
        val root = expanded()
        tap(root, "Cheese")
        assertTrue(Checklists.queued(ctx).isEmpty())
        // The tile refreshes: the ticked item is hidden now.
        tileWith(todo(item("t", "Tomato")))
        // Minutes pass with the screen off; the same view comes back (onRestart).
        now += Checklists.HOLD_MS + 60_000
        controller!!.pause().stop().restart().start().resume()
        settle()
        assertTrue("the kept row shows as ticked, not as first sent", box(root, "Cheese").isChecked)
        assertEquals(1, edits.size)
    }

    // ---- the outbox ----

    @Test
    fun `a tick goes to the edit route with its check id, and the answer clears it`() {
        tileWith(todo(item("c", "Cheese")))
        val root = expanded()
        tap(root, "Cheese")
        val sent = edits.single().checksList.single()
        assertEquals("c", sent.itemId)
        assertTrue(sent.checked && sent.checkId.isNotBlank() && sent.atEpochMs > 0)
        assertTrue("acknowledged: off the queue", Checklists.queued(ctx).isEmpty())
        assertTrue("the older route is not used", legacy.isEmpty())
        assertTrue(lines(root).isEmpty())
    }

    @Test
    fun `a refresh with stale data never unticks a tick not yet answered`() {
        status = 503
        tileWith(todo(item("c", "Cheese"), item("t", "Tomato")))
        val root = expanded()
        tap(root, "Cheese")
        assertEquals(1, Checklists.queued(ctx).size)
        // Refreshes from before the tick arrive, again and again.
        repeat(3) { tileWith(todo(item("c", "Cheese"), item("t", "Tomato"))) }
        assertTrue(box(root, "Cheese").isChecked)
        assertTrue(Checklists.shown(ctx, "c", serverChecked = false))
    }

    @Test
    fun `a tick outlives the process and is resent under the same check id`() {
        status = 503
        tileWith(todo(item("c", "Cheese")))
        tap(expanded(), "Cheese")
        val firstId = edits.single().checksList.single().checkId
        assertTrue("written down before it was sent", Config.itemCheckQueue(ctx).contains(firstId))

        Checklists.forgetForTest()                        // a new process
        status = 200
        assertEquals(1, Checklists.flush(ctx))
        assertEquals(firstId, edits.last().checksList.single().checkId)
        assertTrue(Checklists.queued(ctx).isEmpty())
    }

    @Test
    fun `with retries exhausted the row stays as set and says saving is unavailable, then a later send clears it`() {
        status = 503
        tileWith(todo(item("c", "Cheese")))
        val root = expanded()
        tap(root, "Cheese")
        repeat(Checklists.MAX_TRIES - 1) { Checklists.flush(ctx) }
        settle()
        val tick = Checklists.queued(ctx).single()
        assertTrue(tick.failed)
        assertEquals(Checklists.MAX_TRIES, tick.tries)
        assertTrue("never silently reverted", box(root, "Cheese").isChecked)
        assertEquals(listOf(ctx.getString(R.string.checklist_save_failed)), lines(root))
        assertEquals("Saving is currently unavailable.", ctx.getString(R.string.checklist_save_failed))
        // Still queued, still sent: the next wake gets through.
        status = 200
        assertEquals(1, Checklists.flush(ctx))
        settle()
        assertTrue(box(root, "Cheese").isChecked)
        assertTrue(lines(root).isEmpty())
    }

    @Test
    fun `a failed outcome waits and counts as a try`() {
        answer = { b -> reply(b, "failed", note = "x") }
        tileWith(todo(item("c", "Cheese")))
        tap(expanded(), "Cheese")
        assertEquals(1, Checklists.queued(ctx).single().tries)
        assertTrue(legacy.isEmpty())
    }

    @Test
    fun `a stale answer follows the backend's state and shows its sentence`() {
        val said = "I didn't tick it: it was unticked again after you ticked it."
        answer = { b -> reply(b, "stale", checked = false, note = said) }
        tileWith(todo(item("c", "Cheese"), item("t", "Tomato")))
        val root = expanded()
        tap(root, "Cheese")
        assertTrue(Checklists.queued(ctx).isEmpty())
        assertFalse("the change made elsewhere is not clobbered", box(root, "Cheese").isChecked)
        assertFalse(Checklists.shown(ctx, "c", serverChecked = true))
        assertEquals(listOf(said), lines(root))
    }

    @Test
    fun `a refused answer puts the row back with the backend's sentence`() {
        answer = { b -> reply(b, "refused", note = "I didn't change it: notes and lists are off for your account.") }
        tileWith(todo(item("c", "Cheese")))
        val root = expanded()
        tap(root, "Cheese")
        assertFalse(box(root, "Cheese").isChecked)
        assertEquals(listOf("I didn't change it: notes and lists are off for your account."), lines(root))
    }

    @Test
    fun `an older backend answers nothing for ticks, so they go by the older route`() {
        answer = null
        tileWith(todo(item("c", "Cheese"), item("t", "Tomato")))
        val root = expanded()
        tap(root, "Cheese")
        assertEquals("c", legacy.single().checksList.single().itemId)
        assertTrue(Checklists.queued(ctx).isEmpty())
        tap(root, "Tomato")
        assertEquals("once known, the edit route is not asked again", 1, edits.size)
        assertEquals(2, legacy.size)
    }

    @Test
    fun `a backend without the edit route takes ticks by the older route`() {
        editRoute = false
        tileWith(todo(item("c", "Cheese")))
        val root = expanded()
        tap(root, "Cheese")
        assertEquals("c", legacy.single().checksList.single().itemId)
        assertTrue(Checklists.queued(ctx).isEmpty())
        assertTrue(box(root, "Cheese").isChecked)
    }

    @Test
    fun `a wait grows with each try and stops at five minutes`() {
        assertEquals(2_000L, Checklists.retryDelayMs(1))
        assertEquals(4_000L, Checklists.retryDelayMs(2))
        assertEquals(16_000L, Checklists.retryDelayMs(4))
        assertEquals(300_000L, Checklists.retryDelayMs(40))
    }

    @Test
    fun `a waiting tick is retried on its own`() {
        Checklists.autoRetry = true
        status = 503
        tileWith(todo(item("c", "Cheese")))
        tap(expanded(), "Cheese")
        assertEquals(1, Checklists.queued(ctx).size)
        status = 200
        assertTrue("a retry was scheduled", Checklists.awaitRetryForTest())
        assertTrue(Checklists.queued(ctx).isEmpty())
        assertEquals(2, edits.size)
        assertNull(Checklists.lineFor(ctx, "c"))
    }
}
