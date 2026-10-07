package watch.rist.assistant

import android.Manifest
import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The arrivals feed with more rows than fit: no "N older, not shown" line, no unread row left out. */
@RunWith(RobolectricTestRunner::class)
class FeedNoOlderLineTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    abstract class Rows : ContentProvider() {
        abstract fun cursor(): Cursor
        override fun onCreate() = true
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = cursor()
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    class Calls : Rows() {
        override fun cursor() = MatrixCursor(arrayOf("number", "date")).apply {
            calls.forEach { addRow(arrayOf<Any>(it.first, it.second)) }
        }
    }

    class Texts : Rows() {
        override fun cursor() = MatrixCursor(arrayOf("address", "date", "date_sent")).apply {
            texts.forEach { addRow(arrayOf<Any>(it.first, it.second, 0L)) }
        }
    }

    companion object {
        val calls = ArrayList<Pair<String, Long>>()
        val texts = ArrayList<Pair<String, Long>>()
    }

    @Before fun clean() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG)
        Config.usePlainPrefsForTest(app)
        Config.setNotifications(app, "[]")
        Config.setSeenCommsIds(app, emptyList())
        Config.setCredentialRejected(app, false)
        Config.setEnrolRevoked(app, false)
        Config.setCarrierVoicemailWaiting(app, false)
        CommsFeedView.resetForTest()
        CommsFeedView.forgetNames()
        Robolectric.setupContentProvider(Calls::class.java, "call_log")
        Robolectric.setupContentProvider(Texts::class.java, "sms")
        val now = System.currentTimeMillis()
        calls.clear(); texts.clear()
        // A full query's worth of each (both are LIMIT HARD_CAP): twice the old feed-wide cap.
        for (i in 1..CommsFeed.HARD_CAP) {
            calls.add("+1206555%04d".format(i) to now - i * 60_000L)
            texts.add("+1425555%04d".format(i) to now - i * 90_000L)
        }
    }

    @After fun tidy() {
        Config.setSeenCommsIds(app, emptyList())
        CommsFeedView.resetForTest()
        Config.forgetPrefsForTest()
    }

    private fun home(): Activity {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.setContentView(LinearLayout(a).apply { id = R.id.commsFeed; orientation = LinearLayout.VERTICAL })
        return a
    }

    private fun texts(a: Activity): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is TextView) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.findViewById(R.id.commsFeed))
        return out
    }

    @Test
    fun `fifty unread calls and texts are all drawn, with no older line`() {
        val a = home()
        CommsFeedView.render(a)
        val shown = texts(a)
        assertTrue("no older line: $shown", shown.none { it.contains("not shown") || it.contains("older") })
        assertEquals("every unread row is drawn", 2 * CommsFeed.HARD_CAP, shown.count { it == "NEW" })
        assertEquals(2 * CommsFeed.HARD_CAP, CommsFeedView.waitingCount(a))
    }

    @Test
    fun `seen calls and texts left out of the feed leave no older line`() {
        Config.setSeenCommsIds(
            app,
            calls.map { "call:${it.first}:${it.second}" } +
                texts.map { "sms:" + SmsInbox.arrivalId(it.first, it.second) },
        )
        val a = home()
        CommsFeedView.render(a)
        val shown = texts(a)
        assertTrue("no older line: $shown", shown.none { it.contains("not shown") || it.contains("older") })
        assertEquals(0, shown.count { it == "NEW" })
    }
}
