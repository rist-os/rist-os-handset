package watch.rist.assistant

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** An unpinned answer swipes away; a pinned one cannot, however it is swiped. */
@RunWith(RobolectricTestRunner::class)
class SwipeAnswersTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    @Before
    fun empty() = Transcript.clearForTest(app)

    @After
    fun tidy() = Transcript.clearForTest(app)

    private fun answered(asked: String, pinned: Boolean = false): Long {
        val id = Transcript.begin(app, asked, EntryState.WAITING)
        Transcript.update(app, id, state = EntryState.ANSWERED, answer = "an answer to $asked")
        if (pinned) Transcript.setPinned(app, id, true)
        return id
    }

    private fun home(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).create().start().resume().get()

    /** The answer's column, which takes the touches; null when the answer is not on screen. */
    private fun column(a: MainActivity, asked: String): View? {
        var hit: View? = null
        fun walk(v: View) {
            val d = v.contentDescription?.toString()
            if (d == asked || d == "Pinned. $asked") hit = v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.findViewById<LinearLayout>(R.id.replyContainer))
        return hit
    }

    private fun swipe(v: View, dx: Float) {
        val t0 = SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, t: Long) = MotionEvent.obtain(t0, t, action, x, 30f, 0)
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 20f, t0))
        for (i in 1..10) v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 20f + dx * i / 10, t0 + i * 30L))
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 20f + dx, t0 + 330L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }

    @Test
    fun `an unpinned answer swipes away`() {
        answered("what time is it")
        val a = home()
        swipe(requireNotNull(column(a, "what time is it")), dx = -700f)
        assertTrue("swiped away", Transcript.all(app).none { it.prompt == "what time is it" })
    }

    @Test
    fun `a pinned answer cannot be swiped away, either way`() {
        answered("keep me", pinned = true)
        val a = home()
        swipe(requireNotNull(column(a, "keep me")), dx = -700f)
        swipe(requireNotNull(column(a, "keep me")), dx = 700f)
        assertEquals(1, Transcript.all(app).count { it.prompt == "keep me" })
        assertNotNull("and it is still on the screen, unmoved", column(a, "keep me"))
    }

    @Test
    fun `answers have no x any more`() {
        answered("what time is it")
        val a = home()
        var x = 0
        fun walk(v: View) {
            if (v is android.widget.TextView && v.text.toString() == "✕") x++
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.findViewById<LinearLayout>(R.id.replyContainer))
        assertEquals(0, x)
    }
}
