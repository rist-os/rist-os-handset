package watch.rist.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import org.robolectric.RobolectricTestRunner
import rist.v1.BoxEdit
import rist.v1.BoxEditReply
import rist.v1.BoxSet
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import rist.v1.FeatureSet
import rist.v1.FeatureState
import rist.v1.HomeBox
import rist.v1.Speech
import rist.v1.WakeSignal
import java.util.concurrent.TimeUnit

/** Home boxes: the held list, its clamps, what each box shows, the turns and the touch edits. */
@RunWith(RobolectricTestRunner::class)
class HomeBoxesTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private var server: MockWebServer? = null

    @Before
    fun clean() {
        HomeBoxes.resetForTest(ctx)
        DesignSync.resetForTest(ctx)
        Config.setFeatures(ctx, "")
    }

    @After
    fun tidy() {
        HomeBoxes.resetForTest(ctx)
        DesignSync.resetForTest(ctx)
        Config.setFeatures(ctx, "")
        server?.shutdown()
    }

    private fun box(
        id: String, title: String = id, kind: String = "display", state: String = "ok",
        value: String = "", detail: String = "", command: String = "", note: String = "",
        updated: Long = 0, staleAfter: Long = 0, body: String = "",
    ): HomeBox = HomeBox.newBuilder().setId(id).setTitle(title).setKind(kind).setState(state)
        .setValue(value).setDetail(detail).setCommand(command).setNote(note)
        .setUpdatedAtEpochS(updated).setStaleAfterEpochS(staleAfter).setBody(body).build()

    private fun set(version: Long, vararg boxes: HomeBox): BoxSet =
        BoxSet.newBuilder().setVersion(version).addAllBoxes(boxes.toList()).build()

    private fun ids() = HomeBoxes.boxes(ctx).map { it.id }

    private fun backend(): MockWebServer {
        val s = MockWebServer().also { it.start() }
        server = s
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(ctx, s.url("/v1/device").toString())
        StreamingCancel.resetForTest()
        return s
    }

    private fun protoBody(bytes: ByteArray) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/x-protobuf").setBody(Buffer().write(bytes))

    // ---- the held list ----

    @Test
    fun `a list is kept and is there again after a restart, before any network`() {
        assertTrue(HomeBoxes.apply(ctx, set(4, box("a", value = "54°"), box("b", kind = "command", command = "Check my email"))))
        HomeBoxes.forgetCacheForTest()
        assertEquals(listOf("a", "b"), ids())
        assertEquals(4L, HomeBoxes.version(ctx))
        assertEquals("54°", HomeBoxes.find(ctx, "a")?.value)
        assertFalse("the same list again is not a change", HomeBoxes.apply(ctx, set(4, box("a", value = "54°"), box("b", kind = "command", command = "Check my email"))))
    }

    @Test
    fun `nothing held is an empty list at version zero`() {
        assertTrue(HomeBoxes.boxes(ctx).isEmpty())
        assertEquals(0L, HomeBoxes.version(ctx))
    }

    @Test
    fun `lengths are clamped on receipt, and there is no limit on how many boxes`() {
        val long = box(
            "x", title = "T".repeat(40), value = "V".repeat(30), detail = "D".repeat(60),
            command = "c".repeat(800), body = "b".repeat(40_000),
        )
        val many = (1..300).map { box("n$it") }
        HomeBoxes.apply(ctx, BoxSet.newBuilder().setVersion(1).addBoxes(long).addAllBoxes(many).build())
        val got = HomeBoxes.find(ctx, "x")!!
        assertEquals(HomeBoxes.TITLE_MAX, got.title.length)
        assertEquals(HomeBoxes.VALUE_MAX, got.value.length)
        assertEquals(HomeBoxes.DETAIL_MAX, got.detail.length)
        assertEquals(HomeBoxes.COMMAND_MAX, got.command.length)
        assertEquals(HomeBoxes.BODY_MAX_BYTES, got.body.toByteArray().size)
        assertEquals(301, HomeBoxes.boxes(ctx).size)
    }

    @Test
    fun `a clamp never splits a character, and a box with no id is dropped`() {
        assertEquals("ab", HomeBoxes.clip("ab😀", 2))
        assertEquals("ab😀", HomeBoxes.clip("ab😀c", 3))
        val cut = HomeBoxes.clipUtf8("é".repeat(10), 5)
        assertEquals("éé", cut)
        HomeBoxes.apply(ctx, set(1, box(""), box("kept")))
        assertEquals(listOf("kept"), ids())
    }

    // ---- kinds and states ----

    @Test
    fun `an unknown kind is a display box, which never sends`() {
        val odd = box("o", kind = "widget", command = "delete everything")
        assertEquals(HomeBoxes.Kind.DISPLAY, HomeBoxes.kindOf(odd))
        assertNull(HomeBoxes.commandTurn(odd))
        assertNull(HomeBoxes.commandTurn(box("d", kind = "display", command = "send it")))
    }

    @Test
    fun `an unknown state gets the error look, never an old value shown as current`() {
        val f = HomeBoxes.face(box("w", title = "Temperature", state = "weird", value = "54°"), 1_000)
        assertEquals(HomeBoxes.State.ERROR, f.state)
        assertEquals("—", f.value)
        assertEquals("Couldn't update", f.detail)
    }

    @Test
    fun `each state draws its own words`() {
        val now = 10_000L
        val ok = HomeBoxes.face(box("t", title = "Temperature", value = "54°", detail = "Seattle", updated = now - 480, staleAfter = now + 600), now)
        assertEquals("54°", ok.value)
        assertEquals("Seattle", ok.detail)
        assertFalse(ok.dimmed)
        assertEquals("Temperature, 54°, Seattle. Double tap to open.", ok.description)

        val later = 100_000L
        val stale = HomeBoxes.face(box("t", title = "Tides", value = "5.1 ft", updated = later - 3 * 3600, staleAfter = later - 60), later)
        assertTrue(stale.dimmed)
        assertEquals("3h ago · stale", stale.detail)
        assertTrue(stale.description.contains("out of date"))

        val err = HomeBoxes.face(box("t", title = "Temperature", state = "error", value = "54°", note = "Couldn't reach the weather"), now)
        assertEquals("—", err.value)
        assertEquals("Couldn't reach the weather", err.detail)

        val pending = HomeBoxes.face(box("t", state = "pending"), now)
        assertEquals("…", pending.value)

        val off = HomeBoxes.face(box("s", title = "Stock", state = "off", value = "231", note = "Not on yet"), now)
        assertEquals("", off.value)
        assertEquals("Not on yet", off.detail)

        val paused = HomeBoxes.face(box("s", state = "paused"), now)
        assertEquals("Paused", paused.detail)
    }

    @Test
    fun `a command box shows its words, and says when it is sending`() {
        val b = box("c", title = "Check my email", kind = "command", command = "Check my email")
        val idle = HomeBoxes.face(b, 0)
        assertEquals("Check my email", idle.value)
        assertEquals("Command box: Check my email. Double tap to send.", idle.description)
        val busy = HomeBoxes.face(b, 0, sending = true)
        assertEquals("Sending…", busy.detail)
        assertTrue(busy.sending)
    }

    // ---- turns ----

    @Test
    fun `a command turn is the stored words verbatim, with the box id and no tool`() {
        val t = HomeBoxes.commandTurn(box("c1", kind = "command", command = "Text Sam I'm on my way"))!!
        assertEquals("Text Sam I'm on my way", t.text)
        assertEquals("", t.targetToolId)
        assertEquals("c1", t.boxId)
        assertEquals("the transcript shows just the words", "Text Sam I'm on my way", t.prompt)
    }

    @Test
    fun `the add and change sheets address the boxes tool`() {
        val d = HomeBoxes.addTurn(HomeBoxes.Kind.DISPLAY, " the temperature, every 30 minutes ")
        assertEquals("Add a display box: the temperature, every 30 minutes", d.text)
        assertEquals("boxes", d.targetToolId)
        assertEquals("", d.boxId)
        val c = HomeBoxes.addTurn(HomeBoxes.Kind.COMMAND, "check my email")
        assertEquals("Add a command box: check my email", c.text)
        val e = HomeBoxes.addEdit(ctx, "Check my email, then text Sam ")
        assertEquals(1, e.addCount)
        assertEquals("exactly what was typed", "Check my email, then text Sam ", e.getAdd(0).command)
        assertEquals("the backend names it", "", e.getAdd(0).title)
        assertTrue(e.editId.isNotBlank())
        val ch = HomeBoxes.changeTurn(box("b7"), "show it in Celsius")
        assertEquals("Change this box: show it in Celsius", ch.text)
        assertEquals("boxes", ch.targetToolId)
        assertEquals("b7", ch.boxId)
    }

    @Test
    fun `a second tap on a box in flight is refused until it ends`() {
        assertTrue(HomeBoxes.beginSend("c"))
        assertFalse(HomeBoxes.beginSend("c"))
        assertTrue("another box is free", HomeBoxes.beginSend("d"))
        HomeBoxes.endSend("c")
        assertTrue(HomeBoxes.beginSend("c"))
    }

    // ---- touch edits ----

    @Test
    fun `reorder, delete, undo and rename apply at once and are queued`() {
        HomeBoxes.apply(ctx, set(7, box("a"), box("b"), box("c")))
        HomeBoxes.edit(ctx, HomeBoxes.reorderEdit(ctx, listOf("c", "a", "b")))
        assertEquals(listOf("c", "a", "b"), ids())

        val before = ids()
        HomeBoxes.edit(ctx, HomeBoxes.deleteEdit(ctx, "a"))
        assertEquals(listOf("c", "b"), ids())
        HomeBoxes.edit(ctx, HomeBoxes.restoreEdit(ctx, "a", before))
        assertEquals(listOf("c", "a", "b"), ids())

        HomeBoxes.edit(ctx, HomeBoxes.renameEdit(ctx, "b", "Inbox"))
        assertEquals("Inbox", HomeBoxes.find(ctx, "b")?.title)

        val q = HomeBoxes.queued(ctx)
        assertEquals(4, q.size)
        assertTrue(q.all { it.baseVersion == 7L && it.editId.isNotBlank() })
        assertEquals(4, q.map { it.editId }.toSet().size)
        assertEquals(listOf("a"), q[1].deleteIdsList)
        assertEquals(listOf("a"), q[2].restoreIdsList)
        assertEquals(before, q[2].orderList)
        assertEquals("b", q[3].renameId)
        assertEquals("Inbox", q[3].renameTitle)
    }

    @Test
    fun `an order naming a gone box skips it, and boxes it does not name keep their place after`() {
        val s = HomeBoxes.applyLocally(set(1, box("a"), box("b"), box("c"), box("d")),
            BoxEdit.newBuilder().addAllOrder(listOf("c", "zz", "a")).build())
        assertEquals(listOf("c", "a", "b", "d"), s.boxesList.map { it.id })
    }

    @Test
    fun `edits go to the boxes endpoint, and the reply's list replaces what the phone holds`() {
        val s = backend()
        HomeBoxes.apply(ctx, set(3, box("a"), box("b")))
        HomeBoxes.edit(ctx, HomeBoxes.deleteEdit(ctx, "a"))
        // The backend had moved on: its list is newer and not the one the phone edited.
        val real = set(9, box("b"), box("n", title = "New"))
        s.enqueue(protoBody(BoxEditReply.newBuilder().setStatus(200).setBoxes(real).build().toByteArray()))
        assertEquals(1, HomeBoxes.flush(ctx))
        val req = s.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/device/boxes", req.path)
        assertEquals("POST", req.method)
        val sent = BoxEdit.parseFrom(req.body.readByteArray())
        assertEquals(listOf("a"), sent.deleteIdsList)
        assertEquals(3L, sent.baseVersion)
        assertTrue(sent.editId.isNotBlank())
        assertEquals(listOf("b", "n"), ids())
        assertEquals(9L, HomeBoxes.version(ctx))
        assertTrue(HomeBoxes.queued(ctx).isEmpty())
    }

    @Test
    fun `offline, an edit stays applied and queued, and goes on the next try`() {
        val s = backend()
        HomeBoxes.apply(ctx, set(2, box("a"), box("b")))
        HomeBoxes.edit(ctx, HomeBoxes.reorderEdit(ctx, listOf("b", "a")))
        s.enqueue(MockResponse().setResponseCode(503))
        assertEquals(0, HomeBoxes.flush(ctx))
        assertEquals(1, HomeBoxes.queued(ctx).size)
        assertEquals(listOf("b", "a"), ids())

        // A list that crossed the edit does not undo it on screen.
        HomeBoxes.apply(ctx, set(3, box("a"), box("b"), box("c")))
        assertEquals(listOf("b", "a", "c"), ids())

        s.enqueue(protoBody(BoxEditReply.newBuilder().setBoxes(set(4, box("b"), box("a"), box("c"))).build().toByteArray()))
        assertEquals(1, HomeBoxes.flush(ctx))
        assertTrue(HomeBoxes.queued(ctx).isEmpty())
        assertEquals(4L, HomeBoxes.version(ctx))
        assertEquals(2, s.requestCount)
    }

    @Test
    fun `an edit the backend refuses for good is dropped, not retried forever`() {
        val s = backend()
        HomeBoxes.apply(ctx, set(1, box("a")))
        HomeBoxes.edit(ctx, HomeBoxes.renameEdit(ctx, "a", "Alpha"))
        s.enqueue(MockResponse().setResponseCode(404))
        assertEquals(1, HomeBoxes.flush(ctx))
        assertTrue(HomeBoxes.queued(ctx).isEmpty())
    }

    @Test
    fun `boxes switched off for the account keep the edit for later`() {
        val s = backend()
        HomeBoxes.apply(ctx, set(1, box("a"), box("b")))
        HomeBoxes.edit(ctx, HomeBoxes.reorderEdit(ctx, listOf("b", "a")))
        s.enqueue(MockResponse().setResponseCode(409).setHeader("X-Rist-Feature", "boxes-off"))
        assertEquals(0, HomeBoxes.flush(ctx))
        assertEquals(1, HomeBoxes.queued(ctx).size)
        assertEquals("what the phone has is kept", listOf("b", "a"), ids())
    }

    @Test
    fun `the queue survives a restart`() {
        HomeBoxes.apply(ctx, set(1, box("a"), box("b")))
        HomeBoxes.edit(ctx, HomeBoxes.reorderEdit(ctx, listOf("b", "a")))
        HomeBoxes.forgetCacheForTest()
        assertEquals(1, HomeBoxes.queued(ctx).size)
        assertEquals(listOf("b", "a"), ids())
    }

    // ---- the wire ----

    @Test
    fun `the wake asks with the version held and declares the component`() {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("api.example.test").addPathSegments("v1/device").build().toString()
        val withBoxes = WakeLoop.wakeUrl(url, emptyList(), 8, boxesVersion = 12)!!.toHttpUrl()
        assertEquals("12", withBoxes.queryParameter("boxes"))
        assertEquals("home_boxes", withBoxes.queryParameter("components"))
        val without = WakeLoop.wakeUrl(url, emptyList(), 8)!!.toHttpUrl()
        assertNull(without.queryParameter("boxes"))
        assertNull(without.queryParameter("components"))
    }

    @Test
    fun `a poll from a phone that shows boxes carries its version`() {
        backend()
        HomeBoxes.shippedForTest = true
        DesignSync.shippedForTest = false
        HomeBoxes.apply(ctx, set(5, box("a")))
        val url = WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl()
        assertEquals("/v1/device/wake", url.encodedPath)
        assertEquals("5", url.queryParameter("boxes"))
        assertEquals("home_boxes", url.queryParameter("components"))
    }

    @Test
    fun `a poll from a build without boxes does not mention boxes`() {
        backend()
        HomeBoxes.shippedForTest = false
        HomeBoxes.apply(ctx, set(5, box("a")))
        assertNull(WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl().queryParameter("boxes"))
    }

    @Test
    fun `a wake signal carrying a list is taken in`() {
        WakeLoop.apply(ctx, WakeSignal.newBuilder().setBoxes(set(2, box("w"))).build(), emptyList())
        assertEquals(listOf("w"), ids())
        WakeLoop.apply(ctx, WakeSignal.newBuilder().build(), emptyList())
        assertEquals("a signal without a list leaves the held one", listOf("w"), ids())
    }

    @Test
    fun `the component is declared only when boxes ship`() {
        HomeBoxes.shippedForTest = false
        assertFalse(DeviceProfile.capabilities(1080, 2400).componentsList.contains(HomeBoxes.COMPONENT))
        val caps = DeviceProfile.capabilities(1080, 2400, homeBoxes = true)
        assertTrue(caps.componentsList.contains(HomeBoxes.COMPONENT))
        assertEquals("the backend picks the schema version", DeviceProfile.RCS_SCHEMA_VERSION, caps.schemaVersion)
        HomeBoxes.shippedForTest = true
        assertTrue(DeviceProfile.capabilities(ctx).componentsList.contains(HomeBoxes.COMPONENT))
    }

    @Test
    fun `a turn carries the version held, and a reply's list is taken in`() {
        val s = backend()
        HomeBoxes.shippedForTest = true
        HomeBoxes.apply(ctx, set(6, box("a")))
        s.enqueue(protoBody(DeviceResponse.newBuilder().setIsFinal(true)
            .setSpeech(Speech.newBuilder().setText("Added a temperature box."))
            .setBoxes(set(7, box("a"), box("t", title = "Temperature"))).build().toByteArray()))
        val reply = Uploader(ctx).sendToolCall("boxes", "Add a display box: the temperature")
        assertNotNull(reply)
        val req = DeviceRequest.parseFrom(s.takeRequest(5, TimeUnit.SECONDS)!!.body.readByteArray())
        assertEquals(6L, req.boxesVersion)
        assertEquals("boxes", req.targetToolId)
        assertEquals(listOf("a", "t"), ids())
    }

    @Test
    fun `a typed turn carries the box id only when it came from a tile`() {
        val s = backend()
        HomeBoxes.shippedForTest = false
        repeat(2) {
            s.enqueue(protoBody(DeviceResponse.newBuilder().setIsFinal(true)
                .setSpeech(Speech.newBuilder().setText("ok")).build().toByteArray()))
        }
        Uploader(ctx).sendText("Check my email", boxId = "c1")
        val fromBox = DeviceRequest.parseFrom(s.takeRequest(5, TimeUnit.SECONDS)!!.body.readByteArray())
        assertEquals("Check my email", fromBox.text)
        assertEquals("c1", fromBox.boxId)
        assertEquals("", fromBox.targetToolId)
        Uploader(ctx).sendText("Check my email")
        val typed = DeviceRequest.parseFrom(s.takeRequest(5, TimeUnit.SECONDS)!!.body.readByteArray())
        assertEquals("", typed.boxId)
        assertEquals(0L, typed.boxesVersion)
    }

    // ---- the feature ----

    @Test
    fun `the row shows only when shipped and the account has boxes`() {
        HomeBoxes.shippedForTest = false
        assertFalse("not shipped", HomeBoxes.shown(ctx))
        HomeBoxes.shippedForTest = true
        assertTrue("no feature list yet: as today, on", HomeBoxes.shown(ctx))
        Features.apply(ctx, FeatureSet.newBuilder().addFeatures(FeatureState.newBuilder().setFeature("boxes").setState("off")).build())
        assertFalse(HomeBoxes.shown(ctx))
        Features.apply(ctx, FeatureSet.newBuilder().addFeatures(FeatureState.newBuilder().setFeature("email").setState("on")).build())
        assertFalse("not listed is unavailable", HomeBoxes.shown(ctx))
        Features.apply(ctx, FeatureSet.newBuilder().addFeatures(FeatureState.newBuilder().setFeature("boxes").setState("on")).build())
        assertTrue(HomeBoxes.shown(ctx))
    }
}
