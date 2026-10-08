package watch.rist.assistant

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
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
import org.robolectric.shadows.ShadowToast
import rist.v1.NoteCard
import rist.v1.NoteEditBatch
import rist.v1.NoteEditReply
import rist.v1.NoteSaved
import java.util.concurrent.CopyOnWriteArrayList

/** Notes edited in place: the edit queue, what it sends and keeps, and the cards that draw it. */
@RunWith(RobolectricTestRunner::class)
class NoteEditsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val batches = CopyOnWriteArrayList<NoteEditBatch>()
    @Volatile private var status = 200
    @Volatile private var copied = false
    @Volatile private var headers = emptyMap<String, String>()

    @Before
    fun setUp() {
        NoteEdits.resetForTest(ctx)
        NoteCardView.resetForTest()
        Transcript.clearForTest(ctx)
        StreamingCancel.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl!!.encodedPath != "/v1/device/notes") return MockResponse().setResponseCode(404)
                val batch = NoteEditBatch.parseFrom(request.body.readByteArray())
                batches += batch
                if (simulated) return simulate(batch)
                val reply = NoteEditReply.newBuilder().addAllSaved(batch.editsList.map {
                    NoteSaved.newBuilder().setEditId(it.editId)
                        .setNoteId(if (copied) "copy-of-${it.noteId}" else it.noteId)
                        .setVersion("v-${it.text.length}").setCopied(copied).build()
                }).build()
                val code = status
                return MockResponse().setResponseCode(code).apply {
                    headers.forEach { (k, v) -> setHeader(k, v) }
                    if (code == 200) setBody(Buffer().write(reply.toByteArray()))
                }
            }
        }
        server.start()
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
    }

    @After
    fun tidy() {
        NoteEdits.awaitFlushForTest()
        server.shutdown()
        NoteEdits.resetForTest(ctx)
        NoteCardView.resetForTest()
        Transcript.clearForTest(ctx)
        Config.clearBillingLapse(ctx)
    }

    private fun card(id: String, text: String, version: String = "v0", title: String = "") =
        NoteCard.newBuilder().setNoteId(id).setText(text).setVersion(version).setTitle(title).build()

    private fun answered(vararg cards: NoteCard): Long {
        val id = Transcript.begin(ctx, "show my notes", EntryState.WAITING)
        Transcript.update(ctx, id, state = EntryState.ANSWERED, answer = "They're on screen.", noteCards = cards.toList())
        return id
    }

    private fun cards() = Transcript.all(ctx).flatMap { it.noteCards }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun saveOffline(id: String, base: String, text: String) {
        status = 503
        assertTrue(NoteEdits.save(ctx, id, base, text))
        NoteEdits.awaitFlushForTest()
    }

    // ---- the queue ----

    @Test
    fun `two saves of one note queue one edit, the latest text, from the first version`() {
        answered(card("n1", "milk"))
        saveOffline("n1", "v0", "milk, eggs")
        saveOffline("n1", "v9", "milk, eggs, bread")
        val q = NoteEdits.queued(ctx).single()
        assertEquals("milk, eggs, bread", q.text)
        assertEquals("the version the first edit started from", "v0", q.baseVersion)
        assertEquals("the card shows the text at once", "milk, eggs, bread", cards().single().text)
        assertTrue("the queue outlives a restart", Config.noteEditQueue(ctx).contains("bread"))
    }

    @Test
    fun `blank or too long text is never queued`() {
        assertFalse(NoteEdits.save(ctx, "n1", "v0", "   \n"))
        assertFalse(NoteEdits.save(ctx, "n1", "v0", "x".repeat(NoteEdits.MAX_TEXT + 1)))
        assertTrue(NoteEdits.queued(ctx).isEmpty())
    }

    @Test
    fun `an accepted edit goes to the notes endpoint verbatim and the card takes the saved version`() {
        answered(card("n1", "gate 4512"))
        saveOffline("n1", "v0", "  gate 4512\n  oil the hinge ")
        status = 200
        assertEquals(1, NoteEdits.flush(ctx))
        assertTrue(NoteEdits.queued(ctx).isEmpty())
        val sent = batches.last().editsList.single()
        assertEquals("  gate 4512\n  oil the hinge ", sent.text)
        assertEquals("n1", sent.noteId)
        assertEquals("v0", sent.baseVersion)
        assertTrue(sent.editId.isNotBlank() && sent.atEpochMs > 0)
        val now = cards().single()
        assertEquals("n1", now.noteId)
        assertEquals("v-${sent.text.length}", now.version)
    }

    @Test
    fun `a note that had changed is saved as a new one, the card follows it and says so`() {
        answered(card("n1", "gate 4512"))
        saveOffline("n1", "v0", "mine")
        status = 200
        copied = true
        NoteEdits.flush(ctx)
        assertEquals("copy-of-n1", cards().single().noteId)
        assertEquals("mine", cards().single().text)
        settle()
        assertEquals(ctx.getString(R.string.note_edit_saved_as_new), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `offline, auth, busy and broken backends keep the edit for the next try`() {
        answered(card("n1", "a"))
        saveOffline("n1", "v0", "a b")
        for (code in listOf(401, 403, 408, 429, 500, 503)) {
            status = code
            assertEquals("HTTP $code", 0, NoteEdits.flush(ctx))
            assertEquals("HTTP $code", 1, NoteEdits.queued(ctx).size)
        }
    }

    @Test
    fun `a lapsed subscription keeps the edit`() {
        answered(card("n1", "a"))
        saveOffline("n1", "v0", "a b")
        status = 402
        headers = mapOf("X-Rist-Billing" to "lapsed")
        assertEquals(0, NoteEdits.flush(ctx, fromTap = true))
        assertEquals(1, NoteEdits.queued(ctx).size)
        assertTrue(Config.billingLapse(ctx).isNotBlank())
    }

    @Test
    fun `a refusal drops the edit but keeps the user's text on the card and says so`() {
        for (code in listOf(409, 400, 404, 413)) {
            Transcript.clearForTest(ctx)
            answered(card("n1", "before"))
            saveOffline("n1", "v0", "my words")
            status = code
            assertEquals("HTTP $code", 1, NoteEdits.flush(ctx))
            assertTrue("HTTP $code", NoteEdits.queued(ctx).isEmpty())
            assertEquals("HTTP $code: the words stay", "my words", cards().single().text)
            settle()
            assertEquals(ctx.getString(R.string.note_edit_save_failed), ShadowToast.getTextOfLatestToast())
        }
    }

    @Test
    fun `the component is declared and the endpoint sits beside the device route`() {
        assertTrue(DeviceProfile.capabilities(1080, 2400, noteEdit = true).componentsList.contains(NoteEdits.COMPONENT))
        assertFalse(DeviceProfile.capabilities(1080, 2400, noteEdit = false).componentsList.contains(NoteEdits.COMPONENT))
        assertEquals("https://api.example/v1/device/notes", NoteEdits.notesUrl("https://api.example/v1/device/"))
        assertEquals("https://api.example/v1/device/notes", NoteEdits.notesUrl("https://api.example"))
        assertNull(NoteEdits.notesUrl(""))
    }

    @Test
    fun `note cards are kept with their entry across a reload`() {
        val id = answered(card("n1", "line one\nline two", title = "Gate"))
        Transcript.reloadForTest(ctx)
        val kept = Transcript.all(ctx).first { it.localId == id }.noteCards.single()
        assertEquals("Gate", kept.title)
        assertEquals("line one\nline two", kept.text)
    }

    // ---- the card on screen ----

    private fun <T : View> all(v: View, type: Class<T>, tag: String): List<T> {
        val out = ArrayList<T>()
        fun walk(x: View) {
            if (type.isInstance(x) && x.tag == tag) out += type.cast(x)!!
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
    }

    @Test
    fun `tap the text, change it, tick the box, and the card shows the saved text`() {
        answered(card("n1", "2 x *oat* milk"))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val root = home.window.decorView
        val text = all(root, TextView::class.java, NoteCardView.TAG_TEXT).single()
        assertEquals("verbatim, no markdown", "2 x *oat* milk", text.text.toString())
        text.performClick()
        settle()
        val edit = all(root, EditText::class.java, NoteCardView.TAG_EDIT).single()
        assertEquals("2 x *oat* milk", edit.text.toString())
        edit.setText("2 x *oat* milk\nand eggs")
        status = 200
        all(root, CheckBox::class.java, NoteCardView.TAG_SAVE).single().performClick()
        NoteEdits.awaitFlushForTest()
        settle()
        assertEquals("2 x *oat* milk\nand eggs", batches.single().editsList.single().text)
        assertTrue("the editor closed", all(root, EditText::class.java, NoteCardView.TAG_EDIT).isEmpty())
        assertEquals("2 x *oat* milk\nand eggs", all(root, TextView::class.java, NoteCardView.TAG_TEXT).single().text.toString())
        assertEquals("2 x *oat* milk\nand eggs", cards().single().text)
    }

    @Test
    fun `cancel leaves the note as it was and sends nothing`() {
        answered(card("n1", "keep me"))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val root = home.window.decorView
        all(root, TextView::class.java, NoteCardView.TAG_TEXT).single().performClick()
        settle()
        all(root, EditText::class.java, NoteCardView.TAG_EDIT).single().setText("changed my mind")
        all(root, TextView::class.java, NoteCardView.TAG_CANCEL).single().performClick()
        NoteEdits.awaitFlushForTest()
        settle()
        assertTrue(batches.isEmpty())
        assertEquals("keep me", all(root, TextView::class.java, NoteCardView.TAG_TEXT).single().text.toString())
        assertEquals("keep me", cards().single().text)
    }

    @Test
    fun `back leaves the editor without saving`() {
        answered(card("n1", "keep me"))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val root = home.window.decorView
        all(root, TextView::class.java, NoteCardView.TAG_TEXT).single().performClick()
        settle()
        all(root, EditText::class.java, NoteCardView.TAG_EDIT).single().setText("not this")
        home.onBackPressedDispatcher.onBackPressed()
        NoteEdits.awaitFlushForTest()
        settle()
        assertTrue(batches.isEmpty())
        assertTrue(all(root, EditText::class.java, NoteCardView.TAG_EDIT).isEmpty())
        assertEquals("keep me", cards().single().text)
    }

    @Test
    fun `an emptied note is not saved and the editor stays open with a line saying why`() {
        answered(card("n1", "something"))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        val root = home.window.decorView
        all(root, TextView::class.java, NoteCardView.TAG_TEXT).single().performClick()
        settle()
        all(root, EditText::class.java, NoteCardView.TAG_EDIT).single().setText("  ")
        val tick = all(root, CheckBox::class.java, NoteCardView.TAG_SAVE).single()
        tick.performClick()
        NoteEdits.awaitFlushForTest()
        settle()
        assertTrue(batches.isEmpty())
        assertFalse(tick.isChecked)
        assertEquals(1, all(root, EditText::class.java, NoteCardView.TAG_EDIT).size)
        assertEquals(ctx.getString(R.string.note_edit_empty), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a repaint while editing keeps what was typed`() {
        val id = answered(card("n1", "draft me"))
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val home = ctl.get()
        settle()
        val root = home.window.decorView
        all(root, TextView::class.java, NoteCardView.TAG_TEXT).single().performClick()
        settle()
        all(root, EditText::class.java, NoteCardView.TAG_EDIT).single().setText("half typed")
        assertTrue(NoteCardView.isEditing(id))
        // Another answer arrives and the feed is drawn again.
        Transcript.begin(ctx, "what time is it", EntryState.WAITING)
        ctl.recreate()
        settle()
        val again = all(ctl.get().window.decorView, EditText::class.java, NoteCardView.TAG_EDIT).single()
        assertEquals("half typed", again.text.toString())
    }

    // ---- against the backend's rule (note_edits.apply_edit) ----

    /** The backend's notes, by id. Version is a fingerprint of the text, as the backend's is. */
    private val notes = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var simulated = false
    /** The edits are applied but the answer is lost (a gateway timing out on the backend). */
    @Volatile private var dropAnswers = false
    @Volatile private var duringRequest: (() -> Unit)? = null

    private fun ver(text: String) = "v:$text"

    private fun simulate(batch: NoteEditBatch): MockResponse {
        if (status != 200) return MockResponse().setResponseCode(status)
        duringRequest?.let { duringRequest = null; it() }
        val latest = LinkedHashMap<String, rist.v1.NoteEdit>()
        batch.editsList.forEach { latest.remove(it.noteId); latest[it.noteId] = it }
        val reply = NoteEditReply.newBuilder()
        for (e in latest.values) {
            val body = notes[e.noteId]
            val saved = when {
                body == e.text -> NoteSaved.newBuilder().setNoteId(e.noteId).setVersion(ver(e.text))
                body != null && ver(body) == e.baseVersion -> {
                    notes[e.noteId] = e.text
                    NoteSaved.newBuilder().setNoteId(e.noteId).setVersion(ver(e.text))
                }
                else -> {
                    val id = "n${notes.size + 1}"
                    notes[id] = e.text
                    NoteSaved.newBuilder().setNoteId(id).setVersion(ver(e.text)).setCopied(true)
                }
            }
            reply.addSaved(saved.setEditId(e.editId))
        }
        if (dropAnswers) return MockResponse().setResponseCode(504)
        return MockResponse().setResponseCode(200).setBody(Buffer().write(reply.build().toByteArray()))
    }

    private fun tick(entry: Long, text: String) {
        val now = Transcript.noteCard(ctx, entry, 0)!!
        assertTrue(NoteEdits.save(ctx, now.noteId, now.version, text))
        NoteEdits.awaitFlushForTest()
    }

    @Test
    fun `an edit whose answer was lost and a second edit save one note, not a copy`() {
        simulated = true
        notes["n1"] = "milk"
        val entry = answered(card("n1", "milk", ver("milk")))
        dropAnswers = true
        tick(entry, "milk, eggs")
        assertEquals("the backend saved it; the phone never heard", "milk, eggs", notes["n1"])
        dropAnswers = false
        tick(entry, "milk, eggs, bread")

        assertEquals("one note", mapOf("n1" to "milk, eggs, bread"), notes.toMap())
        assertTrue(NoteEdits.queued(ctx).isEmpty())
        assertEquals("n1", cards().single().noteId)
        assertEquals(ver("milk, eggs, bread"), cards().single().version)
    }

    @Test
    fun `a second edit made while the first is on the way and lost still saves one note`() {
        simulated = true
        notes["n1"] = "milk"
        val entry = answered(card("n1", "milk", ver("milk")))
        dropAnswers = true
        duringRequest = { val now = Transcript.noteCard(ctx, entry, 0)!!; NoteEdits.save(ctx, now.noteId, now.version, "milk, eggs, bread") }
        tick(entry, "milk, eggs")
        NoteEdits.awaitFlushForTest()
        dropAnswers = false
        NoteEdits.flush(ctx)

        assertEquals("one note", mapOf("n1" to "milk, eggs, bread"), notes.toMap())
        assertTrue(NoteEdits.queued(ctx).isEmpty())
    }

    @Test
    fun `after a copy the card follows it even with a newer edit on the way, so later edits are not copied again`() {
        simulated = true
        notes["n1"] = "milk"
        val entry = answered(card("n1", "milk", ver("milk")))
        notes["n1"] = "oat milk"                    // a voice edit lands after the card was drawn
        status = 503
        saveOffline("n1", ver("milk"), "milk, eggs")
        status = 200
        duringRequest = { val now = Transcript.noteCard(ctx, entry, 0)!!; NoteEdits.save(ctx, now.noteId, now.version, "milk, eggs, bread") }
        NoteEdits.flush(ctx)
        NoteEdits.awaitFlushForTest()

        assertEquals(mapOf("n1" to "oat milk", "n2" to "milk, eggs, bread"), notes.toMap())
        assertEquals("the card names the copy", "n2", cards().single().noteId)
        tick(entry, "milk, eggs, bread, jam")
        assertEquals("the third edit rewrites the copy", mapOf("n1" to "oat milk", "n2" to "milk, eggs, bread, jam"), notes.toMap())
    }

    @Test
    fun `an undeclared build draws no note cards`() {
        NoteEdits.shippedForTest = false
        answered(card("n1", "hidden"))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()
        assertTrue(all(home.window.decorView, TextView::class.java, NoteCardView.TAG_TEXT).isEmpty())
    }
}
