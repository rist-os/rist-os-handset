package watch.rist.assistant

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
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
import rist.v1.BoxEdit
import rist.v1.BoxEditReply
import rist.v1.BoxSet
import rist.v1.HomeBox
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** The refresh button on an expanded display box. */
@RunWith(RobolectricTestRunner::class)
class BoxRefreshTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val edits = CopyOnWriteArrayList<BoxEdit>()
    private val kicks = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var reply: () -> MockResponse = { MockResponse().setResponseCode(200) }

    private val then = System.currentTimeMillis() / 1000 - 480

    @Before
    fun setUp() {
        HomeBoxes.resetForTest(app)
        HomeBoxes.shippedForTest = true
        BoxRefresh.resetForTest()
        BoxRefresh.onlineForTest = true
        BoxRefresh.motionForTest = true
        BoxRefresh.kickForTest = { kicks.incrementAndGet() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl?.encodedPath == "/v1/device/boxes") edits += BoxEdit.parseFrom(request.body.readByteArray())
                return reply()
            }
        }
        server.start()
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(app, server.url("/v1/device").toString())
    }

    @After
    fun tidy() {
        BoxRefresh.awaitForTest()
        HomeBoxes.awaitFlushForTest()
        server.shutdown()
        HomeBoxes.resetForTest(app)
        BoxRefresh.resetForTest()
    }

    private fun box(id: String, kind: String = "display", state: String = "ok", updated: Long = then): HomeBox =
        HomeBox.newBuilder().setId(id).setTitle(id).setKind(kind).setState(state).setValue("54°")
            .setCommand(if (kind == "command") "Check my email" else "").setBody("Body $id")
            .setUpdatedAtEpochS(updated).build()

    private fun set(version: Long, vararg boxes: HomeBox) =
        BoxSet.newBuilder().setVersion(version).addAllBoxes(boxes.toList()).build()

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(id: String): BoxExpandedActivity =
        Robolectric.buildActivity(BoxExpandedActivity::class.java, BoxExpandedActivity.intent(app, id))
            .setup().get().also { settle() }

    private fun button(a: BoxExpandedActivity): View? =
        a.window.decorView.findViewWithTag(BoxExpandedActivity.TAG_REFRESH)

    private fun status(a: BoxExpandedActivity): String? =
        a.window.decorView.findViewWithTag<TextView>(BoxExpandedActivity.TAG_REFRESH_STATUS)
            ?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()

    private fun shown(a: BoxExpandedActivity) = button(a)?.visibility == View.VISIBLE

    private fun sent() {
        BoxRefresh.awaitForTest()
        settle()
    }

    @Test
    fun `a running display box has a refresh button labelled Refresh`() {
        HomeBoxes.apply(app, set(3, box("w")))
        val a = open("w")
        assertTrue(shown(a))
        assertEquals("Refresh", button(a)!!.contentDescription)
        assertTrue(button(a)!!.isClickable)
        assertNull(ViewCompat.getStateDescription(button(a)!!))
    }

    @Test
    fun `a command box and a box that is off or paused have none`() {
        HomeBoxes.apply(app, set(3, box("c", kind = "command"), box("o", state = "off"), box("p", state = "paused"),
            box("e", state = "error")))
        assertFalse(shown(open("c")))
        assertFalse(shown(open("o")))
        assertFalse(shown(open("p")))
        assertTrue("a box that failed can be asked again", shown(open("e")))
    }

    @Test
    fun `a tap sends a refresh of this box, never queued, and spins until the box is updated`() {
        HomeBoxes.apply(app, set(3, box("w"), box("x")))
        val a = open("w")
        button(a)!!.performClick()
        sent()
        assertEquals(1, edits.size)
        val e = edits[0]
        assertEquals(listOf("w"), e.refreshIdsList)
        assertEquals(3L, e.baseVersion)
        assertTrue(e.editId.isNotBlank())
        assertTrue(e.deleteIdsCount == 0 && e.orderCount == 0 && e.renameId.isEmpty())
        assertTrue(HomeBoxes.queued(app).isEmpty())
        assertTrue(a.isRefreshing)
        assertTrue(a.isSpinning)
        assertEquals("the wake is polled at once, not after the idle gap", 1, kicks.get())
        assertEquals("Refreshing", ViewCompat.getStateDescription(button(a)!!))
        assertFalse("a second tap while waiting does nothing", button(a)!!.isEnabled)

        // A list where another box moved does not end it.
        HomeBoxes.apply(app, set(4, box("w"), box("x", updated = System.currentTimeMillis() / 1000)))
        settle()
        assertTrue(a.isRefreshing)

        // The box's own update time moving past the tap does, and the view shows the new text.
        val now = System.currentTimeMillis() / 1000
        HomeBoxes.apply(app, set(5, box("w", updated = now).toBuilder().setBody("Fresh body").build(), box("x")))
        settle()
        assertFalse(a.isRefreshing)
        assertFalse(a.isSpinning)
        assertNull(ViewCompat.getStateDescription(button(a)!!))
        val body = a.window.decorView.findViewWithTag<TextView>(BoxExpandedActivity.TAG_BODY).text.toString()
        assertTrue(body, body.contains("Fresh body"))
        assertEquals(1, edits.size)

        // Resting for a minute: the backend allows one a minute.
        assertFalse(button(a)!!.isEnabled)
        button(a)!!.performClick()
        sent()
        assertEquals(1, edits.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BoxRefresh.COOLDOWN_MS + 1_000))
        // The rest is measured on the wall clock, which Robolectric does not move.
        BoxRefresh.resetForTest(); BoxRefresh.onlineForTest = true; BoxRefresh.motionForTest = true
        BoxRefresh.kickForTest = { kicks.incrementAndGet() }
        HomeBoxes.apply(app, set(6, box("w", updated = now), box("x")))
        settle()
        assertTrue(button(a)!!.isEnabled)
    }

    @Test
    fun `a reply already carrying the updated box ends the wait at once`() {
        HomeBoxes.apply(app, set(3, box("w")))
        reply = {
            // Stamped when the backend answers, which is after the tap.
            val fresh = set(4, box("w", updated = System.currentTimeMillis() / 1000))
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/x-protobuf")
                .setBody(Buffer().write(BoxEditReply.newBuilder().setStatus(200).setBoxes(fresh).build().toByteArray()))
        }
        val a = open("w")
        button(a)!!.performClick()
        sent()
        settle()
        assertEquals(1, edits.size)
        assertEquals(4L, HomeBoxes.version(app))
        assertFalse(a.isRefreshing)
        assertEquals("nothing left to wait for, so no poll", 0, kicks.get())
    }

    @Test
    fun `no answer in thirty seconds says still working and stays locked`() {
        HomeBoxes.apply(app, set(3, box("w")))
        val a = open("w")
        button(a)!!.performClick()
        sent()
        assertTrue(a.isSpinning)
        advance(BoxRefresh.SLOW_MS - 2_000)
        assertTrue(a.isRefreshing)
        assertNull("nothing said before thirty seconds", status(a))
        assertEquals("asked at once and at 3, 6, 10 and 20 s", 5, kicks.get())
        advance(3_000)
        assertTrue(a.isRefreshing)
        assertTrue(a.isSpinning)
        assertEquals("Still working…", status(a))
        assertFalse("still locked", button(a)!!.isEnabled)
        assertEquals("Refreshing", ViewCompat.getStateDescription(button(a)!!))
        advance(BoxExpandedActivity.STATUS_MS + 1_000)
        assertEquals("the word stays while it waits", "Still working…", status(a))
    }

    @Test
    fun `no update by the cap says it could not, and unlocks`() {
        BoxRefresh.motionForTest = false
        HomeBoxes.apply(app, set(3, box("w")))
        val a = open("w")
        button(a)!!.performClick()
        sent()
        advance(BoxRefresh.CAP_MS - 2_000)
        assertTrue(a.isRefreshing)
        assertFalse(button(a)!!.isEnabled)
        advance(3_000)
        assertFalse(a.isRefreshing)
        assertFalse(a.isSpinning)
        assertEquals("Currently unavailable", status(a))
        assertTrue("a refresh that failed can be tried again", button(a)!!.isEnabled)
        assertTrue(BoxRefresh.restingMs("w", System.currentTimeMillis()) == 0L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BoxExpandedActivity.STATUS_MS + 100))
        assertNull(status(a))
    }

    @Test
    fun `offline, nothing is sent or queued and it says Offline`() {
        BoxRefresh.onlineForTest = false
        HomeBoxes.apply(app, set(3, box("w")))
        val a = open("w")
        button(a)!!.performClick()
        sent()
        assertEquals(0, server.requestCount)
        assertTrue(HomeBoxes.queued(app).isEmpty())
        assertFalse(a.isRefreshing)
        assertEquals("Offline", status(a))
    }

    @Test
    fun `a backend that cannot take it says it could not update, and keeps nothing queued`() {
        reply = { MockResponse().setResponseCode(503) }
        HomeBoxes.apply(app, set(3, box("w")))
        val a = open("w")
        button(a)!!.performClick()
        sent()
        assertEquals(1, edits.size)
        assertTrue(HomeBoxes.queued(app).isEmpty())
        assertFalse(a.isRefreshing)
        assertEquals("Currently unavailable", status(a))
        assertTrue("a refused refresh unlocks at once", button(a)!!.isEnabled)
        assertEquals(0, kicks.get())
    }

    @Test
    fun `with motion reduced it does not spin and says Refreshing in words`() {
        BoxRefresh.motionForTest = false
        HomeBoxes.apply(app, set(3, box("w")))
        val a = open("w")
        button(a)!!.performClick()
        sent()
        assertTrue(a.isRefreshing)
        assertFalse(a.isSpinning)
        assertEquals("Refreshing…", status(a))
        assertEquals("Refreshing", ViewCompat.getStateDescription(button(a)!!))
        HomeBoxes.apply(app, set(4, box("w", updated = System.currentTimeMillis() / 1000)))
        settle()
        assertFalse(a.isRefreshing)
        assertNull(status(a))
    }

    private fun openController(id: String) =
        Robolectric.buildActivity(BoxExpandedActivity::class.java, BoxExpandedActivity.intent(app, id))
            .setup().also { settle() }

    /**
     * Steps the main looper in short steps: a spinning icon's frames make one long idle overshoot.
     * With motion off nothing spins, so the steps can be longer (and the long waits stay light).
     */
    private fun advance(ms: Long) {
        val looper = shadowOf(Looper.getMainLooper())
        val step = if (BoxRefresh.motion()) 100L else 1_000L
        val start = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() - start < ms) looper.idleFor(Duration.ofMillis(step))
    }

    @Test
    fun `closing the view and opening it again while a refresh waits keeps it spinning`() {
        HomeBoxes.apply(app, set(3, box("w")))
        val first = openController("w")
        button(first.get())!!.performClick()
        sent()
        assertTrue(first.get().isSpinning)
        first.pause().stop().destroy()
        settle()
        assertTrue("the wait outlives the view", BoxRefresh.isPending("w"))

        val again = open("w")
        assertTrue(again.isRefreshing)
        assertTrue(again.isSpinning)
        assertFalse(button(again)!!.isEnabled)
        assertEquals("Refreshing", ViewCompat.getStateDescription(button(again)!!))
        button(again)!!.performClick()
        sent()
        assertEquals("a tap on the reopened view sends nothing more", 1, edits.size)

        // It still ends when the box comes back, in the new view.
        HomeBoxes.apply(app, set(4, box("w", updated = System.currentTimeMillis() / 1000)))
        settle()
        assertFalse(again.isRefreshing)
        assertFalse(again.isSpinning)
    }

    @Test
    fun `closing the view past thirty seconds and reopening finds it still locked and still working`() {
        HomeBoxes.apply(app, set(3, box("w")))
        val first = openController("w")
        button(first.get())!!.performClick()
        sent()
        first.pause().stop().destroy()
        settle()
        advance(BoxRefresh.SLOW_MS + 5_000)
        assertTrue(BoxRefresh.isPending("w"))

        val again = open("w")
        assertTrue(again.isRefreshing)
        assertTrue(again.isSpinning)
        assertFalse(button(again)!!.isEnabled)
        assertEquals("Still working…", status(again))
        button(again)!!.performClick()
        sent()
        assertEquals("a tap on the reopened view sends nothing more", 1, edits.size)
    }

    @Test
    fun `the box coming back at forty-five seconds updates the view in place`() {
        BoxRefresh.motionForTest = false
        HomeBoxes.apply(app, set(3, box("w")))
        val a = open("w")
        button(a)!!.performClick()
        sent()
        advance(45_000)
        assertTrue(a.isRefreshing)
        assertEquals("Still working…", status(a))
        HomeBoxes.apply(app, set(4, box("w", updated = System.currentTimeMillis() / 1000).toBuilder()
            .setBody("Late body").build()))
        settle()
        assertFalse(a.isRefreshing)
        assertFalse(a.isSpinning)
        assertNull("finishing late is not a failure", status(a))
        val body = a.window.decorView.findViewWithTag<TextView>(BoxExpandedActivity.TAG_BODY).text.toString()
        assertTrue(body, body.contains("Late body"))
        assertFalse("resting after it worked", button(a)!!.isEnabled)
        val kicked = kicks.get()
        advance(60_000)
        assertEquals("no more polls once it is done", kicked, kicks.get())
    }

    @Test
    fun `a cap while the view is closed clears the wait quietly`() {
        BoxRefresh.motionForTest = false
        HomeBoxes.apply(app, set(3, box("w")))
        val first = openController("w")
        button(first.get())!!.performClick()
        sent()
        first.pause().stop().destroy()
        settle()
        advance(BoxRefresh.CAP_MS + 1_000)
        assertFalse(BoxRefresh.isPending("w"))

        val again = open("w")
        assertFalse(again.isRefreshing)
        assertFalse(again.isSpinning)
        assertNull("no stale word on reopening", status(again))
        assertTrue("a refresh that timed out can be tried again", button(again)!!.isEnabled)
    }

    @Test
    fun `the box coming back while the view is closed ends the wait, and the rest survives reopening`() {
        HomeBoxes.apply(app, set(3, box("w")))
        val first = openController("w")
        button(first.get())!!.performClick()
        sent()
        first.pause().stop().destroy()
        settle()

        // The home row is what sees it now: the list taken in from the wake, and its redraw.
        val fresh = box("w", updated = System.currentTimeMillis() / 1000)
        BoxRefresh.observe(listOf(fresh))
        assertFalse(BoxRefresh.isPending("w"))
        assertTrue(BoxRefresh.restingMs("w", System.currentTimeMillis()) > 0)
        HomeBoxes.apply(app, set(4, fresh))
        settle()
        val kicked = kicks.get()
        advance(25_000)
        assertEquals("no more polls once it is done", kicked, kicks.get())

        val again = open("w")
        assertFalse(again.isRefreshing)
        assertFalse(again.isSpinning)
        assertFalse("still resting after reopening", button(again)!!.isEnabled)
        assertEquals(0.4f, button(again)!!.alpha)
        button(again)!!.performClick()
        sent()
        assertEquals(1, edits.size)
    }

    @Test
    fun `the rest after a refresh in the view survives closing and reopening it`() {
        HomeBoxes.apply(app, set(3, box("w")))
        val first = openController("w")
        button(first.get())!!.performClick()
        sent()
        HomeBoxes.apply(app, set(4, box("w", updated = System.currentTimeMillis() / 1000)))
        settle()
        assertFalse(first.get().isRefreshing)
        first.pause().stop().destroy()
        settle()
        val again = open("w")
        assertFalse(button(again)!!.isEnabled)
    }

    @Test
    fun `after the reply the wake is polled at once, at 3, 6, 10 and 20 seconds, then every 30 until the cap`() {
        BoxRefresh.motionForTest = false
        val at = CopyOnWriteArrayList<Long>()
        BoxRefresh.kickForTest = { at += android.os.SystemClock.uptimeMillis() }
        HomeBoxes.apply(app, set(3, box("w")))
        val a = open("w")
        button(a)!!.performClick()
        sent()
        val base = at.first()
        advance(BoxRefresh.CAP_MS + 5_000)
        assertFalse(a.isRefreshing)
        assertEquals(listOf(0L, 3_000L, 6_000L, 10_000L, 20_000L, 50_000L, 80_000L, 110_000L, 140_000L, 170_000L),
            BoxRefresh.POLLS_MS.toList())
        val offsets = at.map { it - base }
        assertEquals(offsets.toString(), BoxRefresh.POLLS_MS.size, offsets.size)
        BoxRefresh.POLLS_MS.forEachIndexed { i, ms ->
            assertTrue("poll $i at ${offsets[i]}, wanted $ms", kotlin.math.abs(offsets[i] - ms) <= 150)
        }
        assertTrue(BoxRefresh.POLLS_MS.all { it < BoxRefresh.CAP_MS })
    }

    @Test
    fun `polls stop as soon as the box comes back`() {
        HomeBoxes.apply(app, set(3, box("w")))
        val a = open("w")
        button(a)!!.performClick()
        sent()
        advance(4_000)
        assertEquals(2, kicks.get())
        HomeBoxes.apply(app, set(4, box("w", updated = System.currentTimeMillis() / 1000)))
        settle()
        advance(BoxRefresh.SLOW_MS + 30_000)
        assertEquals(2, kicks.get())
        assertNull("finishing is not a failure", status(a))
    }

    @Test
    fun `done needs the box's own time to rise above what it was at the tap`() {
        assertFalse(BoxRefresh.done(null, 50))
        assertFalse(BoxRefresh.done(box("w", updated = 50), 50))
        assertFalse(BoxRefresh.done(box("w", updated = 49), 50))
        assertTrue("the phone's clock plays no part", BoxRefresh.done(box("w", updated = 51), 50))
        assertNotNull(BoxRefresh.edit(app, "w").refreshIdsList.singleOrNull())
    }
}
