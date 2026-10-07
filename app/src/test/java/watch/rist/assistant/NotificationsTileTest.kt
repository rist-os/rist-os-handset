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

/** The feed's heading, and the built-in Notifications tile that leads the home row. */
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

    @After
    fun tidy() {
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
        Robolectric.buildActivity(MainActivity::class.java).setup().get().also { settle() }

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
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.setContentView(LinearLayout(a).apply { id = R.id.commsFeed; orientation = LinearLayout.VERTICAL })
        CommsFeedView.render(a)
        assertEquals("Notifications", feedHeading(a))
        assertFalse(feedHeading(a).contains("Calls", ignoreCase = true))
    }

    @Test
    fun `the Notifications tile comes first, before the boxes, the Add and the All tiles`() {
        hold("a", "b")
        val a = home()
        val tags = order(a)
        assertEquals(BoxBoard.NOTIFICATIONS_TAG, tags.first())
        assertEquals(listOf(BoxBoard.TILE_TAG_PREFIX + "a", BoxBoard.TILE_TAG_PREFIX + "b"), tags.drop(1).take(2))
        assertEquals("Notifications", tile(a).findViewWithTag<TextView>(BoxBoard.LABEL_TAG).text.toString())
    }

    @Test
    fun `a notice already seen but still listed is counted, and not as new`() {
        NotificationQueue.store(app, listOf(notice("n1")))
        NotificationQueue.markRead(app, listOf("n1"))
        hold("a")
        val a = home()
        assertEquals("1", shownCount(a))
        assertEquals("None new", tile(a).findViewWithTag<TextView>(BoxBoard.DETAIL_TAG).text.toString())
    }

    @Test
    fun `with no boxes the row is the Notifications tile and Add`() {
        val a = home()
        assertEquals(listOf(BoxBoard.NOTIFICATIONS_TAG, BoxBoard.ADD_TAG), order(a))
    }

    @Test
    fun `the tile also leads the All tiles grid`() {
        hold("a")
        val g = Robolectric.buildActivity(AllBoxesActivity::class.java).setup().get()
        settle()
        val grid = g.window.decorView.findViewWithTag<RecyclerView>(AllBoxesActivity.TAG_GRID)
        assertEquals(BoxBoard.NOTIFICATIONS_TAG, grid.getChildAt(0).tag)
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
        return Robolectric.buildActivity(NotificationsActivity::class.java, started).setup().get().also { settle() }
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
        val c = Robolectric.buildActivity(MainActivity::class.java).setup().also { settle() }
        val a = c.get()
        assertEquals(2, CommsFeedView.waitingCount(app))
        assertEquals("2", shownCount(a))
        assertEquals("Notifications, 2 new. Double tap to open.", tile(a).contentDescription)

        // A notice arrives while home is up, with nothing drawn on home: the tile follows.
        NotificationQueue.store(app, listOf(notice("n3")))
        settle()
        assertEquals("3", shownCount(a))
        assertEquals(null, homeFeed(a).findViewWithTag<View>(CommsFeedView.NOTICE_CARD_TAG))

        // CLEAR ALL on the tile's page: the tile goes to nothing at once.
        val list = page(a)
        assertNotNull(list.findViewById<ViewGroup>(R.id.commsFeed).findViewWithTag<View>(CommsFeedView.NOTICE_CARD_TAG))
        val bar = list.findViewById<ViewGroup>(R.id.commsFeed).getChildAt(0) as ViewGroup
        assertEquals("3 NEW", (bar.getChildAt(0) as TextView).text.toString().uppercase())
        bar.getChildAt(1).performClick()
        settle()
        assertEquals(0, CommsFeedView.waitingCount(app))
        // Back on the home screen, the tile reads the cleared count.
        c.pause().resume(); settle()
        assertEquals("0", shownCount(a))
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

        val list = Robolectric.buildActivity(NotificationsActivity::class.java, started).setup().get()
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
