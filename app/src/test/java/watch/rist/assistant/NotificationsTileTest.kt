package watch.rist.assistant

import android.Manifest
import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.BoxEdit
import rist.v1.BoxSet
import rist.v1.HomeBox
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The feed's heading, and the built-in Notifications tile that leads the home row while anything
 * is new, and the gear menu's entry that keeps what was already read in reach.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationsTileTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val edits = CopyOnWriteArrayList<BoxEdit>()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        Config.usePlainPrefsForTest(app)
        Config.setNotifications(app, "[]")
        Config.setSeenCommsIds(app, emptyList())
        Config.setCredentialRejected(app, false)
        Config.setEnrolRevoked(app, false)
        Config.setCarrierVoicemailWaiting(app, false)
        CommsFeedView.resetForTest()
        HomeBoxes.resetForTest(app)
        HomeBoxes.shippedForTest = true
        Config.setFeatures(app, "")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/v1/device/boxes") edits += BoxEdit.parseFrom(request.body.readByteArray())
                return MockResponse().setResponseCode(503)
            }
        }
        server.start()
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(app, server.url("/v1/device").toString())
    }

    // Every activity a test starts is destroyed after it. One left started keeps its receivers in
    // the process-wide LocalBroadcastManager and its turn running, and a later test in the same
    // fork (OtaUpdateButtonTest's theme broadcast) then recreates it on a reset looper and fails.
    private val controllers = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    private fun <T : android.app.Activity> build(cls: Class<T>, intent: android.content.Intent? = null) =
        Robolectric.buildActivity(cls, intent).also { controllers += it }

    private fun destroyStarted() {
        controllers.asReversed().forEach { c ->
            runCatching {
                if (!c.get().isDestroyed) c.pause().stop().destroy()
            }.onFailure { runCatching { c.destroy() } }
        }
        controllers.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tidy() {
        destroyStarted()
        HomeBoxes.awaitFlushForTest()
        server.shutdown()
        HomeBoxes.resetForTest(app)
        HomeBoxes.shippedForTest = null
        Config.setFeatures(app, "")
        Config.setMailUnread(app, 0)
        Config.setMailAcknowledged(app, 0)
        Config.setCarrierVoicemailWaiting(app, false)
        Config.setNotifications(app, "[]")
        Config.setSeenCommsIds(app, emptyList())
        CommsFeedView.resetForTest()
        Config.forgetPrefsForTest()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun notice(id: String, title: String = "Reminder $id") =
        rist.v1.Notification.newBuilder().setId(id).setKind("scheduled").setTitle(title)
            .setUrgency("passive").setCreatedAtEpochS(System.currentTimeMillis() / 1000).build()

    private fun box(id: String) = HomeBox.newBuilder().setId(id).setTitle(id).setKind("display")
        .setState("ok").setValue("1").setUpdatedAtEpochS(System.currentTimeMillis() / 1000).build()

    private fun hold(vararg ids: String) =
        HomeBoxes.apply(app, BoxSet.newBuilder().setVersion(3).addAllBoxes(ids.map { box(it) }).build())

    private fun home(): MainActivity =
        build(MainActivity::class.java).setup().get().also { settle() }

    private fun row(a: Activity) = a.findViewById<RecyclerView>(R.id.boxList)

    private fun order(a: Activity): List<String> {
        val list = row(a)
        return (0 until list.childCount).map { list.getChildAt(it).tag?.toString().orEmpty() }
    }

    private fun tile(a: Activity): ViewGroup =
        requireNotNull(row(a).findViewWithTag<ViewGroup>(BoxBoard.NOTIFICATIONS_TAG)) { "no Notifications tile" }

    private fun shownCount(a: Activity): String =
        tile(a).findViewWithTag<TextView>(BoxBoard.VALUE_TAG).text.toString()

    private fun feedHeading(a: Activity): String {
        val bar = a.findViewById<ViewGroup>(R.id.commsFeed).getChildAt(0) as ViewGroup
        return (bar.getChildAt(0) as TextView).text.toString()
    }

    @Test
    fun `the feed's heading says Notifications when nothing is new`() {
        NotificationQueue.store(app, listOf(notice("n1")))
        NotificationQueue.markRead(app, listOf("n1"))
        val a = build(Activity::class.java).setup().get()
        a.setContentView(LinearLayout(a).apply { id = R.id.commsFeed; orientation = LinearLayout.VERTICAL })
        CommsFeedView.render(a)
        assertEquals("Notifications", feedHeading(a))
        assertFalse(feedHeading(a).contains("Calls", ignoreCase = true))
    }

    private fun noTile(a: Activity) = row(a).findViewWithTag<View>(BoxBoard.NOTIFICATIONS_TAG)

    @Test
    fun `the Notifications tile comes first, before the boxes, the Add and the All tiles`() {
        NotificationQueue.store(app, listOf(notice("n1")))
        hold("a", "b")
        val a = home()
        val tags = order(a)
        assertEquals(BoxBoard.NOTIFICATIONS_TAG, tags.first())
        assertEquals(listOf(BoxBoard.TILE_TAG_PREFIX + "a", BoxBoard.TILE_TAG_PREFIX + "b"), tags.drop(1).take(2))
        assertEquals("Notifications", tile(a).findViewWithTag<TextView>(BoxBoard.LABEL_TAG).text.toString())
    }

    @Test
    fun `with nothing new the tile is not shown, though a notice already read is still listed`() {
        NotificationQueue.store(app, listOf(notice("n1")))
        NotificationQueue.markRead(app, listOf("n1"))
        hold("a", "b")
        val a = home()
        assertEquals(1, CommsFeedView.listedCount(app))
        assertEquals(0, CommsFeedView.waitingCount(app))
        assertEquals(null, noTile(a))
        assertEquals(listOf(BoxBoard.TILE_TAG_PREFIX + "a", BoxBoard.TILE_TAG_PREFIX + "b"), order(a).take(2))
    }

    @Test
    fun `a new one beside one already read, the tile counts both and one of them new`() {
        NotificationQueue.store(app, listOf(notice("n1"), notice("n2")))
        NotificationQueue.markRead(app, listOf("n1"))
        hold("a")
        val a = home()
        assertEquals("2", shownCount(a))
        assertEquals("1 new", tile(a).findViewWithTag<TextView>(BoxBoard.DETAIL_TAG).text.toString())
    }

    @Test
    fun `with no boxes and something new the row is the Notifications tile and Add`() {
        NotificationQueue.store(app, listOf(notice("n1")))
        val a = home()
        assertEquals(listOf(BoxBoard.NOTIFICATIONS_TAG, BoxBoard.ADD_TAG), order(a))
    }

    @Test
    fun `with no boxes and nothing new the row is the Add square alone, at the row's end`() {
        val a = home()
        val list = row(a)
        assertEquals(1, list.childCount)
        assertEquals(null, noTile(a))
        val add = requireNotNull(list.findViewWithTag<View>(BoxBoard.ADD_TAG)) { "no Add square" }
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, list.getChildAt(0).layoutParams.width)
        assertTrue(add.contentDescription.toString().startsWith("Add"))
    }

    @Test
    fun `the tile appears the moment something arrives, at the start of the row`() {
        hold("a", "b")
        val a = home()
        assertEquals(null, noTile(a))

        NotificationQueue.store(app, listOf(notice("n1")))
        settle()
        assertEquals(BoxBoard.NOTIFICATIONS_TAG, order(a).first())
        assertEquals("1", shownCount(a))
        assertEquals(listOf(BoxBoard.TILE_TAG_PREFIX + "a", BoxBoard.TILE_TAG_PREFIX + "b"), order(a).drop(1).take(2))
    }

    @Test
    fun `with no boxes, the lone Add square makes room for the tile when something arrives`() {
        val a = home()
        NotificationQueue.store(app, listOf(notice("n1")))
        settle()
        assertEquals(listOf(BoxBoard.NOTIFICATIONS_TAG, BoxBoard.ADD_TAG), order(a))
        assertEquals(BoxBoard.TILE_DP, row(a).getChildAt(1).layoutParams.width / app.resources.displayMetrics.density, 1f)
    }

    @Test
    fun `the tile goes once everything is read, and comes back for the next arrival`() {
        NotificationQueue.store(app, listOf(notice("n1")))
        hold("a")
        val c = build(MainActivity::class.java).setup().also { settle() }
        val a = c.get()
        assertNotNull(noTile(a))

        // Read on the page, by opening its card: back home, no tile, though it is still listed.
        page(a).window.decorView.findViewWithTag<View>(CommsFeedView.NOTICE_CARD_TAG).performClick()
        settle()
        assertEquals(1, CommsFeedView.listedCount(app))
        c.pause().resume(); settle()
        assertEquals(0, CommsFeedView.waitingCount(app))
        assertEquals(null, noTile(a))
        assertEquals(BoxBoard.TILE_TAG_PREFIX + "a", order(a).first())

        NotificationQueue.store(app, listOf(notice("n2")))
        settle()
        assertEquals(BoxBoard.NOTIFICATIONS_TAG, order(a).first())
        assertEquals("1 new", tile(a).findViewWithTag<TextView>(BoxBoard.DETAIL_TAG).text.toString())
    }

    @Test
    fun `the tile also leads the All tiles grid, and is not there with nothing new`() {
        hold("a")
        val empty = build(AllBoxesActivity::class.java).setup().get()
        settle()
        val none = empty.window.decorView.findViewWithTag<RecyclerView>(AllBoxesActivity.TAG_GRID)
        assertEquals(BoxBoard.TILE_TAG_PREFIX + "a", none.getChildAt(0).tag)
        assertEquals(null, none.findViewWithTag<View>(BoxBoard.NOTIFICATIONS_TAG))

        NotificationQueue.store(app, listOf(notice("n1")))
        val g = build(AllBoxesActivity::class.java).setup().get()
        settle()
        val grid = g.window.decorView.findViewWithTag<RecyclerView>(AllBoxesActivity.TAG_GRID)
        assertEquals(BoxBoard.NOTIFICATIONS_TAG, grid.getChildAt(0).tag)
    }

    @Test
    fun `the gear menu opens the notifications page while any are listed, read or not`() {
        val a0 = home()
        a0.findViewById<View>(R.id.settingsGear).performClick(); settle()
        assertEquals(View.GONE, a0.findViewById<View>(R.id.drawerNotifications).visibility)

        NotificationQueue.store(app, listOf(notice("n1")))
        NotificationQueue.markRead(app, listOf("n1"))
        val a = home()
        assertEquals(null, noTile(a))
        a.findViewById<View>(R.id.settingsGear).performClick(); settle()
        val entry = a.findViewById<View>(R.id.drawerNotifications)
        assertEquals(View.VISIBLE, entry.visibility)
        assertEquals("Notifications", entry.contentDescription)
        entry.performClick(); settle()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(NotificationsActivity::class.java.name, started.component?.className)
        val list = build(NotificationsActivity::class.java, started).setup().get()
        settle()
        assertNotNull(list.window.decorView.findViewWithTag<View>(CommsFeedView.NOTICE_CARD_TAG))
    }

    private fun homeFeed(a: Activity): ViewGroup = a.findViewById(R.id.commsFeed)

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun page(a: Activity): NotificationsActivity {
        tile(a).performClick()
        val started = shadowOf(a).nextStartedActivity
        return build(NotificationsActivity::class.java, started).setup().get().also { settle() }
    }

    @Test
    fun `with the tile row, home lists no notifications and no feed heading, only the tile counts them`() {
        NotificationQueue.store(app, listOf(notice("n1"), notice("n2")))
        Config.setMailUnread(app, 2)
        val a = home()
        val feed = homeFeed(a)
        assertEquals(null, feed.findViewWithTag<View>(CommsFeedView.NOTICE_CARD_TAG))
        val said = texts(feed)
        assertTrue(said.toString(), said.none { it.contains("NEW", ignoreCase = true) || it == "CLEAR ALL" })
        assertTrue(said.toString(), said.none { it.contains("email") })
        assertEquals(0, feed.childCount)
        // Two notices and the mail line: the page's rows, counted on the tile.
        assertEquals(3, CommsFeedView.listedCount(app))
        assertEquals("3", shownCount(a))
    }

    @Test
    fun `the count is the feed's own and follows notices arriving and being cleared on the page`() {
        NotificationQueue.store(app, listOf(notice("n1"), notice("n2")))
        val c = build(MainActivity::class.java).setup().also { settle() }
        val a = c.get()
        assertEquals(2, CommsFeedView.waitingCount(app))
        assertEquals("2", shownCount(a))
        assertEquals("Notifications, 2 new. Double tap to open.", tile(a).contentDescription)

        // A notice arrives while home is up, with nothing drawn on home: the tile follows.
        NotificationQueue.store(app, listOf(notice("n3")))
        settle()
        assertEquals("3", shownCount(a))
        assertEquals(null, homeFeed(a).findViewWithTag<View>(CommsFeedView.NOTICE_CARD_TAG))

        // CLEAR ALL on the tile's page: nothing is new any more.
        val list = page(a)
        assertNotNull(list.findViewById<ViewGroup>(R.id.commsFeed).findViewWithTag<View>(CommsFeedView.NOTICE_CARD_TAG))
        val bar = list.findViewById<ViewGroup>(R.id.commsFeed).getChildAt(0) as ViewGroup
        assertEquals("3 NEW", (bar.getChildAt(0) as TextView).text.toString().uppercase())
        bar.getChildAt(1).performClick()
        settle()
        assertEquals(0, CommsFeedView.waitingCount(app))
        // Back on the home screen, with nothing new, the tile is gone.
        c.pause().resume(); settle()
        assertEquals(null, noTile(a))
    }

    @Test
    fun `without the tile row, home keeps the feed so nothing is out of sight`() {
        HomeBoxes.shippedForTest = false
        NotificationQueue.store(app, listOf(notice("n1", "Pick up the dry cleaning")))
        val a = home()
        val feed = homeFeed(a)
        assertEquals(View.VISIBLE, feed.visibility)
        assertNotNull(feed.findViewWithTag<View>(CommsFeedView.NOTICE_CARD_TAG))
        assertEquals("1 NEW", feedHeading(a).uppercase())
    }

    @Test
    fun `with the tile row, a lapsed subscription is still told on home`() {
        Billing.onLapsed(app, Billing.lapseFrom("ended", "ristmobile.com", null))
        NotificationQueue.store(app, listOf(notice("n1")))
        val a = home()
        val feed = homeFeed(a)
        assertEquals(View.VISIBLE, feed.visibility)
        assertNotNull(feed.findViewWithTag<View>(CommsFeedView.BILLING_ROW_TAG))
        assertEquals(null, feed.findViewWithTag<View>(CommsFeedView.NOTICE_CARD_TAG))
        assertEquals(1, feed.childCount)
    }

    @Test
    fun `a tap opens every notification full screen`() {
        NotificationQueue.store(app, listOf(notice("n1", "Pick up the dry cleaning")))
        val a = home()
        tile(a).performClick()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(NotificationsActivity::class.java.name, started.component?.className)

        val list = build(NotificationsActivity::class.java, started).setup().get()
        settle()
        val root = list.window.decorView
        assertEquals(View.VISIBLE, list.findViewById<View>(R.id.commsFeed).visibility)
        assertEquals(View.GONE, root.findViewWithTag<View>(NotificationsActivity.TAG_EMPTY).visibility)
        assertNotNull(root.findViewWithTag<View>(CommsFeedView.NOTICE_CARD_TAG))

        // Cleared there, the screen says so.
        val bar = list.findViewById<ViewGroup>(R.id.commsFeed).getChildAt(0) as ViewGroup
        bar.getChildAt(1).performClick()
        settle()
        assertEquals(View.VISIBLE, root.findViewWithTag<View>(NotificationsActivity.TAG_EMPTY).visibility)
    }

    @Test
    fun `the tile cannot be moved, edited or deleted, and never reaches the backend`() {
        NotificationQueue.store(app, listOf(notice("n1")))
        hold("a", "b")
        val a = home()
        val t = tile(a)
        val labels = AccessibilityNodeInfoCompat.wrap(t.createAccessibilityNodeInfo())
            .actionList.mapNotNull { it.label?.toString() }
        assertTrue(labels.toString(), labels.none { it in setOf("Move left", "Move right", "Edit", "Delete") })
        t.performLongClick()
        settle()
        assertFalse("a long press on it does not start editing", a.boxBoard.editMode)

        // A touch edit to the user's tiles: the order sent names only them.
        val b = row(a).findViewWithTag<View>(BoxBoard.TILE_TAG_PREFIX + "b")
        val move = AccessibilityNodeInfoCompat.wrap(b.createAccessibilityNodeInfo())
            .actionList.first { it.label?.toString() == "Move left" }
        b.performAccessibilityAction(move.id, null)
        settle()
        HomeBoxes.awaitFlushForTest()
        assertEquals(listOf("b", "a"), HomeBoxes.boxes(app).map { it.id })
        val sent = edits.toList() + HomeBoxes.queued(app)
        assertTrue("an edit was made", sent.isNotEmpty())
        for (e in sent) {
            assertEquals(listOf("b", "a"), e.orderList)
            assertFalse(e.toString().contains("notification", ignoreCase = true))
        }
        // In edit mode it stays first, with no remove or edit controls of its own.
        row(a).findViewWithTag<View>(BoxBoard.TILE_TAG_PREFIX + "a").performLongClick()
        settle()
        assertTrue(a.boxBoard.editMode)
        assertEquals(BoxBoard.NOTIFICATIONS_TAG, order(a).first())
        assertEquals(null, tile(a).findViewWithTag<View>(BoxBoard.DELETE_TAG))
        assertEquals(null, tile(a).findViewWithTag<View>(BoxBoard.EDIT_TAG))
    }
}
