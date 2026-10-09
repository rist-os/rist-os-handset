package watch.rist.assistant

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TableRow
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import org.robolectric.android.controller.ActivityController
import rist.v1.BoxSet
import rist.v1.Checklist
import rist.v1.ChecklistItem
import rist.v1.HomeBox
import rist.v1.ItemCheckBatch
import rist.v1.ItemEditBatch
import rist.v1.ItemEditReply
import rist.v1.ItemOutcome
import rist.v1.TileBlock
import rist.v1.TileRow
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tile blocks: each kind drawn natively, icon colour roles, and edits, adds and deletes from a tile. */
@RunWith(RobolectricTestRunner::class)
class TileBlocksTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val edits = CopyOnWriteArrayList<ItemEditBatch>()
    private val checks = CopyOnWriteArrayList<ItemCheckBatch>()

    /** What the edit route answers: an HTTP status, and per op an outcome. */
    @Volatile private var status = 200
    @Volatile private var answer: (ItemEditBatch) -> ItemEditReply = { b -> reply(b, "saved") }
    @Volatile private var gate: CountDownLatch? = null

    @Before
    fun setUp() {
        HomeBoxes.resetForTest(ctx)
        Checklists.resetForTest(ctx)
        ChecklistView.resetForTest()
        TestLooks.reset(ctx)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/v1/device/items") {
                    checks += ItemCheckBatch.parseFrom(request.body.readByteArray())
                    return MockResponse().setResponseCode(204)
                }
                if (path != "/v1/device/items/edit") return MockResponse().setResponseCode(404)
                val batch = ItemEditBatch.parseFrom(request.body.readByteArray())
                edits += batch
                gate?.await(10, TimeUnit.SECONDS)
                if (status != 200) return MockResponse().setResponseCode(status)
                return MockResponse().setResponseCode(200).setBody(Buffer().write(answer(batch).toByteArray()))
            }
        }
        server.start()
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
    }

    @After
    fun tidy() {
        gate?.countDown()
        ItemEdits.awaitFlushForTest()
        Checklists.awaitFlushForTest()
        server.shutdown()
        HomeBoxes.resetForTest(ctx)
        Checklists.resetForTest(ctx)
        TestLooks.reset(ctx)
        Config.clearBillingLapse(ctx)
    }

    // ---- fixtures ----

    private fun reply(b: ItemEditBatch, outcome: String, note: String = "", text: String = "", itemId: String = ""): ItemEditReply {
        val ids = b.editsList.map { it.editId } + b.addsList.map { it.addId } + b.deletesList.map { it.deleteId }
        return ItemEditReply.newBuilder().addAllOutcomes(ids.map {
            ItemOutcome.newBuilder().setOpId(it).setOutcome(outcome).setNote(note).setText(text).setItemId(itemId).build()
        }).build()
    }

    private fun row(id: String = "", text: String = "", block: TileRow.Builder.() -> Unit = {}): TileRow =
        TileRow.newBuilder().setId(id).setText(text).apply(block).build()

    private fun block(kind: String, vararg rows: TileRow, title: String = "", b: TileBlock.Builder.() -> Unit = {}): TileBlock =
        TileBlock.newBuilder().setKind(kind).setTitle(title).addAllRows(rows.toList()).apply(b).build()

    private fun shopping(vararg rows: TileRow) = block("checklist", *rows, title = "Shopping") {
        addTo = "shopping"; fallbackMarkdown = "- milk\n- eggs"
    }

    private fun item(id: String, text: String, checked: Boolean = false) = row(id, text) {
        target = "item"; checkable = true; this.checked = checked; editable = true; deletable = true; editText = text
    }

    private var version = 1L

    private fun tileWith(vararg blocks: TileBlock, b: HomeBox.Builder.() -> Unit = {}) {
        HomeBoxes.apply(ctx, BoxSet.newBuilder().setVersion(++version).addBoxes(
            HomeBox.newBuilder().setId("t").setKind("display").setState("ok").setTitle("Tile").setValue("1")
                .setBody("**body** markdown").addAllBlocks(blocks.toList()).apply(b)).build())
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

    private fun texts(root: View, tag: String) = all(root, tag).map { (it as TextView).text.toString() }

    private fun flush() {
        ItemEdits.awaitFlushForTest()
        settle()
    }

    /** Opens the row showing [text] for editing, types [typed] and ticks it. */
    private fun editRow(root: View, text: String, typed: String) {
        val words = all(root, TileBlockView.TAG_ROW_TEXT).first { (it as TextView).text == text }.parent as View
        words.performClick()
        settle()
        val edit = all(root, TileBlockView.TAG_EDIT_TEXT).single() as EditText
        assertEquals("the editor starts from the stored text", text, edit.text.toString())
        edit.setText(typed)
        (all(root, TileBlockView.TAG_SAVE).single() as CheckBox).performClick()
        settle()
    }

    // ---- each kind ----

    @Test
    fun `every kind is drawn natively, and an unknown kind draws its markdown`() {
        tileWith(
            block("text") { fallbackMarkdown = "A **calm** day" },
            block("list", row(text = "Standup") { label = "9:00 AM"; detail = "Room 4"; extra = "30 min"; icon = "event"; iconTone = "accent" }, title = "Today"),
            block("forecast",
                row(text = "60°") { label = "Fri"; detail = "44°"; extra = "40%"; icon = "rainy_light"; iconDesc = "light rain"; iconTone = "cool" },
                row(text = "66°") { label = "Sat"; detail = "48°"; extra = "0%"; icon = "sunny"; iconDesc = "sunny"; iconTone = "warm" }),
            block("stat", row(text = "3") { label = "unread"; icon = "mail" }),
            block("table", row { addAllCells(listOf("ACME", "12.40", "+1.2%")) }) { addAllColumns(listOf("Stock", "Price", "Change")) },
            block("progress", row(text = "6 of 10") { label = "Steps"; value = 6.0; max = 10.0 }),
        )
        val root = expanded()
        assertEquals(View.GONE, root.findViewWithTag<View>(BoxExpandedActivity.TAG_BODY).visibility)
        assertEquals(listOf("A calm day"), texts(root, TileBlockView.TAG_TEXT))
        assertEquals(listOf("Standup"), texts(root, TileBlockView.TAG_ROW_TEXT))
        assertEquals(listOf("9:00 AM"), texts(root, TileBlockView.TAG_ROW_LABEL))
        assertEquals(listOf("Room 4 · 30 min"), texts(root, TileBlockView.TAG_ROW_DETAIL))
        val days = all(root, TileBlockView.TAG_FORECAST_DAY)
        assertEquals(listOf("Fri, light rain, 60°, 44°, 40%", "Sat, sunny, 66°, 48°, 0%"), days.map { it.contentDescription.toString() })
        assertNotNull(root.findViewWithTag<View>(TileBlockView.TAG_FORECAST))
        assertEquals(listOf("3"), texts(root, TileBlockView.TAG_STAT_VALUE))
        assertEquals("unread, 3", all(root, TileBlockView.TAG_STAT).single().contentDescription)
        val table = all(root, TileBlockView.TAG_TABLE_ROW).single() as TableRow
        assertEquals("Stock: ACME, Price: 12.40, Change: +1.2%", table.contentDescription)
        assertEquals(3, table.childCount)
        val bar = all(root, TileBlockView.TAG_PROGRESS_BAR).single() as ProgressBar
        assertEquals(600, bar.progress)
        assertEquals(1000, bar.max)
        // Icons are decorative to a screen reader: the words carry the meaning.
        val icons = all(root, TileBlockView.TAG_ICON)
        assertEquals("event, two forecast days, mail", 4, icons.size)
        assertTrue(icons.all { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO })
    }

    @Test
    fun `an unknown kind or the reserved chart draws its markdown, and an unknown icon draws none`() {
        tileWith(
            block("chart", row(text = "x")) { fallbackMarkdown = "**Chart** as text" },
            block("sparkle") { fallbackMarkdown = "Something *new*" },
            block("list", row(text = "No icon here") { icon = "not_an_icon_name" }),
        )
        val root = expanded()
        assertEquals(listOf("Chart as text", "Something new"), texts(root, TileBlockView.TAG_FALLBACK))
        assertTrue(all(root, TileBlockView.TAG_ICON).isEmpty())
        assertEquals(listOf("No icon here"), texts(root, TileBlockView.TAG_ROW_TEXT))
    }

    @Test
    fun `a block with no rows shows its empty line under its title`() {
        tileWith(block("list", title = "Today") { empty = "Nothing today" }, block("table") { empty = "No stocks" })
        val root = expanded()
        assertEquals(listOf("Nothing today", "No stocks"), texts(root, TileBlockView.TAG_EMPTY))
    }

    // ---- what is drawn, first match wins ----

    @Test
    fun `no blocks draws the body as before, and checklists before body`() {
        tileWith()
        var root = expanded()
        assertEquals(View.VISIBLE, root.findViewWithTag<View>(BoxExpandedActivity.TAG_BODY).visibility)
        assertTrue(all(root, TileBlockView.TAG_ROW).isEmpty())
        controller!!.pause().stop().destroy()

        tileWith { addChecklists(Checklist.newBuilder().setList("shopping").addItems(ChecklistItem.newBuilder().setId("m").setText("milk"))) }
        root = expanded()
        assertEquals(View.GONE, root.findViewWithTag<View>(BoxExpandedActivity.TAG_BODY).visibility)
        assertEquals(listOf("milk"), all(root, ChecklistView.TAG_ROW).map { (it as CheckBox).text.toString() })
    }

    @Test
    fun `blocks win over checklists, and a build without blocks keeps drawing checklists`() {
        val withBoth: HomeBox.Builder.() -> Unit = {
            addChecklists(Checklist.newBuilder().setList("shopping").addItems(ChecklistItem.newBuilder().setId("m").setText("milk (old)")))
        }
        tileWith(shopping(item("m", "milk")), b = withBoth)
        var root = expanded()
        assertEquals(listOf("milk"), texts(root, TileBlockView.TAG_ROW_TEXT))
        controller!!.pause().stop().destroy()

        TileBlocks.shippedForTest = false
        tileWith(shopping(item("m", "milk")), b = withBoth)
        root = expanded()
        assertTrue(all(root, TileBlockView.TAG_ROW_TEXT).isEmpty())
        assertEquals(listOf("milk (old)"), all(root, ChecklistView.TAG_ROW).map { (it as CheckBox).text.toString() })
    }

    @Test
    fun `received blocks are clamped, and a row without an id can do nothing`() {
        val many = (1..60).map { row(text = "r$it") }.toTypedArray()
        val box = HomeBoxes.clamp(HomeBox.newBuilder().setId("t")
            .addAllBlocks((1..8).map { block("list", *many, title = "x".repeat(100)) })
            .addBlocks(block("list", row(text = "y") { checkable = true; editable = true; deletable = true; iconDesc = "d".repeat(100) }))
            .build())
        assertEquals(6, box.blocksCount)
        assertEquals(50, box.getBlocks(0).rowsCount)
        assertEquals(48, box.getBlocks(0).title.length)
        val r = TileBlocks.clamp(row(text = "y") { checkable = true; editable = true; deletable = true; iconDesc = "d".repeat(100) })
        assertFalse(r.checkable || r.editable || r.deletable)
        assertEquals(60, r.iconDesc.length)
    }

    // ---- colour roles ----

    @Test
    fun `every role reads at 3 to 1 on light and dark, and an unknown role is the text colour`() {
        for (t in Themes.ALL) {
            for (role in TileTones.ROLES) {
                val c = TileTones.colour(t, role, t.ground)
                assertTrue("$role on ${t.id}: ${contrast(c, t.ground)}", contrast(c, t.ground) >= TileTones.MIN_CONTRAST)
                val onTile = TileTones.colour(t, role, t.tileFill)
                assertTrue("$role on ${t.id} tile", contrast(onTile, t.tileFill) >= TileTones.MIN_CONTRAST)
            }
            assertEquals(t.ink, TileTones.colour(t, "", t.ground))
            assertEquals(t.ink, TileTones.colour(t, "plaid", t.ground))
            assertEquals(Themes.readableMuted(t), TileTones.colour(t, "neutral", t.ground))
        }
        val light = Themes.byId("ledger")
        val dark = Themes.byId("night")
        // A sun is not drawn the same on paper as on black: each background gets its own hue.
        assertTrue(TileTones.colour(light, "warm") != TileTones.colour(dark, "warm"))
        assertTrue("roles differ from each other", TileTones.ROLES.map { TileTones.colour(light, it) }.toSet().size >= 9)
        // A colour too faint for the background is moved toward the text colour until it reads.
        val faint = android.graphics.Color.parseColor("#F0F0F0")
        assertTrue(contrast(TileTones.readable(faint, light.ink, light.ground), light.ground) >= 3.0)
    }

    @Test
    fun `the face icon takes its role only when blocks are declared`() {
        val t = Themes.byId("night")
        val box = HomeBox.newBuilder().setId("w").setIcon("sunny").setIconTone("warm").build()
        assertEquals(TileTones.colour(t, "warm", t.tileFill), TileTones.face(t, box, t.tileFill))
        assertEquals(t.ink, TileTones.face(t, box.toBuilder().setIconTone("").build(), t.tileFill))
        TileBlocks.shippedForTest = false
        assertEquals(t.ink, TileTones.face(t, box, t.tileFill))
    }

    @Test
    fun `a row icon is drawn in its role's colour`() {
        tileWith(block("list", row(text = "Rain") { icon = "rainy"; iconTone = "cool" }))
        val root = expanded()
        val glyph = (all(root, TileBlockView.TAG_ICON).single() as ImageView).drawable
        val t = Themes.current(ctx)
        val paint = BoxIcons.GlyphDrawable::class.java.getDeclaredField("p").apply { isAccessible = true }.get(glyph) as android.graphics.Paint
        assertEquals(TileTones.colour(t, "cool", t.ground), paint.color)
    }

    // ---- declaring it ----

    @Test
    fun `the components go on turns, on the wake and on the boxes endpoint`() {
        HomeBoxes.shippedForTest = true
        val wake = WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl().queryParameter("components")!!.split(",")
        assertTrue(wake.containsAll(listOf("home_boxes", "checklist_v1", "tile_blocks_v1", "item_edit_v1")))
        val boxes = HomeBoxes.boxesUrl("https://api.example/v1/device", checklists = true, tiles = TileBlocks.components())!!.toHttpUrl()
        assertEquals("checklist_v1,tile_blocks_v1,item_edit_v1", boxes.queryParameter("components"))

        ItemEdits.shippedForTest = false
        assertEquals(listOf("tile_blocks_v1"), TileBlocks.components())
        TileBlocks.shippedForTest = false
        assertEquals(emptyList<String>(), TileBlocks.components())
        assertFalse("edits need blocks", ItemEdits.declared())

        assertEquals("https://api.example/v1/device/items/edit", ItemEdits.editUrl("https://api.example/v1/device/"))
        assertEquals("https://api.example/v1/device/items/edit", ItemEdits.editUrl("https://api.example"))
        assertNull(ItemEdits.editUrl(""))
    }

    @Test
    fun `a phone that starts declaring blocks asks for the whole box list once`() {
        HomeBoxes.shippedForTest = true
        TileBlocks.shippedForTest = false
        HomeBoxes.apply(ctx, BoxSet.newBuilder().setVersion(9).addBoxes(HomeBox.newBuilder().setId("t").setKind("display").setState("ok")).build())
        Config.setChecklistBoxesSeen(ctx, true)
        assertEquals("9", WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl().queryParameter("boxes"))
        TileBlocks.shippedForTest = true
        assertEquals("0", WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl().queryParameter("boxes"))
        HomeBoxes.apply(ctx, BoxSet.newBuilder().setVersion(9).addBoxes(HomeBox.newBuilder().setId("t").setKind("display").setState("ok")
            .addBlocks(block("text") { fallbackMarkdown = "x" })).build())
        assertEquals("9", WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl().queryParameter("boxes"))
    }

    // ---- edits ----

    @Test
    fun `an edit shows saving until the answer, then saved, and the request carries the stored text`() {
        tileWith(shopping(item("e", "eggs"), item("m", "milk")))
        val root = expanded()
        gate = CountDownLatch(1)
        editRow(root, "eggs", "6 eggs")
        server.takeRequest(5, TimeUnit.SECONDS)
        settle()
        // Shown at once, and not yet said to be saved: the backend has not answered.
        assertTrue(texts(root, TileBlockView.TAG_ROW_TEXT).contains("6 eggs"))
        assertEquals(listOf("Saving…"), texts(root, TileBlockView.TAG_STATUS))
        gate!!.countDown()
        flush()
        val sent = edits.single().editsList.single()
        assertEquals("item", sent.target)
        assertEquals("e", sent.itemId)
        assertEquals("eggs", sent.baseText)
        assertEquals("6 eggs", sent.text)
        assertTrue(sent.editId.isNotBlank())
        assertEquals(listOf("Saved"), texts(root, TileBlockView.TAG_STATUS))
        assertTrue(texts(root, TileBlockView.TAG_ROW_TEXT).contains("6 eggs"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ItemEdits.SAVED_MS + 100))
        assertTrue(texts(root, TileBlockView.TAG_STATUS).isEmpty())
        // The tile's next list holds the new text: it takes over.
        tileWith(shopping(item("e", "6 eggs"), item("m", "milk")))
        assertEquals(listOf("6 eggs", "milk"), texts(root, TileBlockView.TAG_ROW_TEXT))
    }

    @Test
    fun `unchanged text sends nothing, and cancel leaves the row as it was`() {
        tileWith(shopping(item("e", "eggs")))
        val root = expanded()
        editRow(root, "eggs", "eggs")
        all(root, TileBlockView.TAG_ROW_TEXT).single().let { (it.parent as View).performClick() }
        settle()
        (all(root, TileBlockView.TAG_EDIT_TEXT).single() as EditText).setText("ham")
        all(root, TileBlockView.TAG_CANCEL).single().performClick()
        settle()
        flush()
        assertTrue(edits.isEmpty())
        assertEquals(listOf("eggs"), texts(root, TileBlockView.TAG_ROW_TEXT))
    }

    @Test
    fun `copied shows the backend's sentence verbatim and the row keeps the stored text`() {
        val note = "That item had changed since your screen showed it, so I didn't overwrite it: your text was saved as a new item beside it."
        answer = { b -> reply(b, "copied", note, "6 eggs", "e2") }
        tileWith(shopping(item("e", "eggs")))
        val root = expanded()
        editRow(root, "eggs", "6 eggs")
        flush()
        assertEquals(listOf(note), texts(root, TileBlockView.TAG_STATUS))
        assertEquals(listOf("eggs"), texts(root, TileBlockView.TAG_ROW_TEXT))
    }

    @Test
    fun `stale puts the event's title back, says why, and keeps the typed text in the editor`() {
        val note = "I didn't rename it: the event changed since your screen showed it."
        answer = { b -> reply(b, "stale", note, "Team sync") }
        val ev = row("ev1", "Standup") { target = "event"; editable = true; deletable = true; editText = "Standup"; label = "9:00 AM" }
        tileWith(block("list", ev))
        val root = expanded()
        editRow(root, "Standup", "Daily standup")
        flush()
        assertEquals(listOf(note), texts(root, TileBlockView.TAG_STATUS))
        assertEquals("the title it has now", listOf("Team sync"), texts(root, TileBlockView.TAG_ROW_TEXT))
        assertEquals("event", edits.single().editsList.single().target)
        (all(root, TileBlockView.TAG_ROW_TEXT).single().parent as View).performClick()
        settle()
        assertEquals("Daily standup", (all(root, TileBlockView.TAG_EDIT_TEXT).single() as EditText).text.toString())
    }

    @Test
    fun `gone, refused and failed each say the backend's sentence and give the words back`() {
        for ((outcome, note) in listOf(
            "gone" to "I didn't save that: the item is no longer there.",
            "refused" to "I didn't change it: notes and lists are off for your account.",
            "failed" to "Saving from a tile is currently unavailable. Nothing was changed.",
        )) {
            HomeBoxes.resetForTest(ctx)
            edits.clear()
            answer = { b -> reply(b, outcome, note) }
            tileWith(shopping(item("e", "eggs")))
            val root = expanded()
            editRow(root, "eggs", "6 eggs")
            flush()
            assertEquals(outcome, listOf(note), texts(root, TileBlockView.TAG_STATUS))
            assertEquals(outcome, listOf("eggs"), texts(root, TileBlockView.TAG_ROW_TEXT))
            assertEquals(outcome, "6 eggs", ItemEdits.draft(ItemEdits.rowKey("e")))
            controller!!.pause().stop().destroy()
        }
    }

    @Test
    fun `offline keeps the text and the op, says saving is unavailable, and sends it later`() {
        status = 503
        tileWith(shopping(item("e", "eggs")))
        val root = expanded()
        editRow(root, "eggs", "6 eggs")
        flush()
        assertEquals(listOf("Saving from a tile is currently unavailable."), texts(root, TileBlockView.TAG_STATUS))
        assertTrue(texts(root, TileBlockView.TAG_ROW_TEXT).contains("6 eggs"))
        assertEquals(1, ItemEdits.queued(ctx).size)
        // Kept in storage, so a restart does not lose it.
        assertTrue(Config.itemEditQueue(ctx).contains("6 eggs"))

        status = 200
        ItemEdits.flushSoon(ctx)
        flush()
        assertTrue(ItemEdits.queued(ctx).isEmpty())
        assertEquals(listOf("Saved"), texts(root, TileBlockView.TAG_STATUS))
        assertEquals("the same op, sent again", edits[0].getEdits(0).editId, edits[1].getEdits(0).editId)
    }

    @Test
    fun `an edit route this backend does not have gives the words back with the standard sentence`() {
        status = 404
        tileWith(shopping(item("e", "eggs")))
        val root = expanded()
        editRow(root, "eggs", "6 eggs")
        flush()
        assertEquals(listOf("Saving from a tile is currently unavailable. Nothing was changed."), texts(root, TileBlockView.TAG_STATUS))
        assertEquals(listOf("eggs"), texts(root, TileBlockView.TAG_ROW_TEXT))
        assertTrue(ItemEdits.queued(ctx).isEmpty())
    }

    // ---- delete ----

    @Test
    fun `delete hides the row with Undo and sends nothing, and Undo brings it back`() {
        tileWith(shopping(item("e", "eggs"), item("t", "tea")))
        val root = expanded()
        all(root, TileBlockView.TAG_DELETE)[1].performClick()
        settle()
        assertEquals(listOf("eggs"), texts(root, TileBlockView.TAG_ROW_TEXT))
        assertEquals(1, all(root, TileBlockView.TAG_UNDO).size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ItemEdits.UNDO_MS - 1000))
        all(root, TileBlockView.TAG_UNDO).single().performClick()
        settle()
        assertEquals(listOf("eggs", "tea"), texts(root, TileBlockView.TAG_ROW_TEXT))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ItemEdits.UNDO_MS * 2))
        flush()
        assertTrue("an undone delete is never sent", edits.isEmpty())
    }

    @Test
    fun `a delete goes after the undo window, and stays hidden once deleted`() {
        tileWith(shopping(item("e", "eggs"), item("t", "tea")))
        val root = expanded()
        all(root, TileBlockView.TAG_DELETE)[1].performClick()
        settle()
        flush()
        assertTrue(edits.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ItemEdits.UNDO_MS))
        flush()
        val sent = edits.single().deletesList.single()
        assertEquals("t", sent.itemId)
        assertEquals("item", sent.target)
        // answered "saved" by default above: a delete answered with anything but deleted/gone comes back
        HomeBoxes.resetForTest(ctx)
        edits.clear()
        answer = { b -> reply(b, "deleted") }
        tileWith(shopping(item("e", "eggs"), item("t", "tea")))
        val again = expanded()
        all(again, TileBlockView.TAG_DELETE)[1].performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ItemEdits.UNDO_MS))
        flush()
        assertEquals(listOf("eggs"), texts(again, TileBlockView.TAG_ROW_TEXT))
        assertTrue(texts(again, TileBlockView.TAG_STATUS).isEmpty())
        tileWith(shopping(item("e", "eggs")))
        assertEquals(listOf("eggs"), texts(again, TileBlockView.TAG_ROW_TEXT))
    }

    @Test
    fun `a refused delete brings the row back with the sentence, and gone keeps it hidden with its sentence`() {
        answer = { b -> reply(b, "refused", "I didn't change it: changing your calendar isn't turned on for your account.") }
        val ev = row("ev1", "Standup") { target = "event"; deletable = true; editable = true; editText = "Standup" }
        tileWith(block("list", ev))
        var root = expanded()
        all(root, TileBlockView.TAG_DELETE).single().performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ItemEdits.UNDO_MS))
        flush()
        assertEquals(listOf("Standup"), texts(root, TileBlockView.TAG_ROW_TEXT))
        assertEquals(listOf("I didn't change it: changing your calendar isn't turned on for your account."), texts(root, TileBlockView.TAG_STATUS))
        controller!!.pause().stop().destroy()

        HomeBoxes.resetForTest(ctx)
        answer = { b -> reply(b, "gone", "That event was already deleted.") }
        tileWith(block("list", ev))
        root = expanded()
        all(root, TileBlockView.TAG_DELETE).single().performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ItemEdits.UNDO_MS))
        flush()
        assertTrue(texts(root, TileBlockView.TAG_ROW_TEXT).isEmpty())
        assertEquals(listOf("That event was already deleted."), texts(root, TileBlockView.TAG_STATUS))
    }

    // ---- add ----

    @Test
    fun `the add row adds one item, shows it saving, then saved, until the list holds it`() {
        tileWith(shopping(item("m", "milk")))
        val root = expanded()
        val add = all(root, TileBlockView.TAG_ADD).single() as TextView
        assertEquals("Add to your shopping list", add.contentDescription)
        add.performClick()
        settle()
        gate = CountDownLatch(1)
        answer = { b -> reply(b, "saved", text = "bread", itemId = "b1") }
        (all(root, TileBlockView.TAG_EDIT_TEXT).single() as EditText).setText("bread")
        (all(root, TileBlockView.TAG_SAVE).single() as CheckBox).performClick()
        settle()
        server.takeRequest(5, TimeUnit.SECONDS)
        settle()
        assertEquals(1, all(root, TileBlockView.TAG_PLACEHOLDER).size)
        assertEquals(listOf("Saving…"), texts(root, TileBlockView.TAG_STATUS))
        gate!!.countDown()
        flush()
        val sent = edits.single().addsList.single()
        assertEquals("shopping", sent.addTo)
        assertEquals("bread", sent.text)
        assertEquals(listOf("Saved"), texts(root, TileBlockView.TAG_STATUS))
        tileWith(shopping(item("m", "milk"), item("b1", "bread")))
        assertTrue(all(root, TileBlockView.TAG_PLACEHOLDER).isEmpty())
        assertEquals(listOf("milk", "bread"), texts(root, TileBlockView.TAG_ROW_TEXT))
    }

    @Test
    fun `a refused add takes its placeholder down, says why, and keeps the words for the editor`() {
        answer = { b -> reply(b, "refused", "I didn't change it: notes and lists are off for your account.") }
        tileWith(block("list", row("n1", "Gate code 4411") { target = "note"; editable = true; deletable = true; editText = "Gate code 4411" }) { addTo = "notes" })
        val root = expanded()
        assertEquals("Add a note", all(root, TileBlockView.TAG_ADD).single().contentDescription)
        all(root, TileBlockView.TAG_ADD).single().performClick()
        settle()
        (all(root, TileBlockView.TAG_EDIT_TEXT).single() as EditText).setText("Buy stamps")
        (all(root, TileBlockView.TAG_SAVE).single() as CheckBox).performClick()
        flush()
        assertTrue(all(root, TileBlockView.TAG_PLACEHOLDER).isEmpty())
        assertEquals(listOf("I didn't change it: notes and lists are off for your account."), texts(root, TileBlockView.TAG_STATUS))
        all(root, TileBlockView.TAG_ADD).single().performClick()
        settle()
        assertEquals("Buy stamps", (all(root, TileBlockView.TAG_EDIT_TEXT).single() as EditText).text.toString())
    }

    @Test
    fun `without item_edit_v1 rows have no edit, delete or add`() {
        ItemEdits.shippedForTest = false
        tileWith(shopping(item("m", "milk")))
        val root = expanded()
        assertTrue(all(root, TileBlockView.TAG_DELETE).isEmpty())
        assertTrue(all(root, TileBlockView.TAG_ADD).isEmpty())
        (all(root, TileBlockView.TAG_ROW_TEXT).single().parent as View).performClick()
        settle()
        assertTrue(all(root, TileBlockView.TAG_EDIT_TEXT).isEmpty())
        // The checkbox still works: it uses its own route.
        assertEquals(1, all(root, ChecklistView.TAG_ROW).size)
    }

    // ---- checkboxes ----

    @Test
    fun `a checklist row's box ticks through the checklist route and strikes the text`() {
        tileWith(shopping(item("m", "milk")))
        val root = expanded()
        val box = all(root, ChecklistView.TAG_ROW).single() as CheckBox
        assertEquals("milk", box.contentDescription)
        box.performClick()
        Checklists.awaitFlushForTest()
        settle()
        assertTrue(checks.single().checksList.single().checked)
        assertEquals("m", checks.single().checksList.single().itemId)
        val text = all(root, TileBlockView.TAG_ROW_TEXT).single() as TextView
        assertTrue(text.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG != 0)
        // The next list drops the ticked item; it stays on the open view until it closes.
        tileWith(shopping())
        assertEquals(listOf("milk"), texts(root, TileBlockView.TAG_ROW_TEXT))
    }

    // ---- accessibility ----

    @Test
    fun `a row is one stop for a screen reader, with Edit and Delete as actions`() {
        val ev = row("ev1", "Standup") { target = "event"; editable = true; deletable = true; editText = "Standup"; label = "9:00 AM"; detail = "Room 4"; icon = "event"; iconDesc = "meeting" }
        tileWith(block("list", ev))
        val root = expanded()
        val words = all(root, TileBlockView.TAG_ROW_WORDS).single()
        assertEquals("9:00 AM, meeting, Standup, Room 4. Double tap to edit", words.contentDescription)
        assertTrue(words.isFocusable)
        val labels = words.createAccessibilityNodeInfo().actionList.mapNotNull { it.label?.toString() }
        assertTrue(labels.containsAll(listOf("Edit", "Delete")))
        assertEquals("Delete Standup", all(root, TileBlockView.TAG_DELETE).single().contentDescription)
        // The Delete action does what the button does.
        val action = words.createAccessibilityNodeInfo().actionList.first { it.label == "Delete" }
        words.performAccessibilityAction(action.id, null)
        settle()
        assertEquals(1, all(root, TileBlockView.TAG_UNDO).size)
    }

    @Test
    fun `a read-only row has no actions and says itself`() {
        tileWith(block("list", row(text = "58°F") { label = "Now"; detail = "Cloudy"; icon = "cloud"; iconDesc = "cloudy"; iconTone = "neutral" }))
        val root = expanded()
        val words = all(root, TileBlockView.TAG_ROW_WORDS).single()
        assertEquals("Now, cloudy, 58°F, Cloudy", words.contentDescription)
        val labels = words.createAccessibilityNodeInfo().actionList.mapNotNull { it.label?.toString() }
        assertFalse(labels.contains("Edit") || labels.contains("Delete"))
        assertTrue(all(root, TileBlockView.TAG_DELETE).isEmpty())
    }
}
