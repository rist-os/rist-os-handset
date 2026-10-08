package watch.rist.assistant

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** An incoming text alerts while the kiosk has muted notifications, by Android's own rules, once. */
@RunWith(RobolectricTestRunner::class)
class TextAlertTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private class FakeEnv(
        var muted: Boolean = true,
        var ringer: Int = AudioManager.RINGER_MODE_NORMAL,
        var allowsAll: Boolean = true,
        var call: Boolean = false,
        var on: Boolean = true,
        var isLocked: Boolean = false,
    ) : TextAlert.Env {
        override fun systemAlertsMuted(ctx: Context) = muted
        override fun ringerMode(ctx: Context) = ringer
        override fun interruptionFilterAllowsAll(ctx: Context) = allowsAll
        override fun inCall(ctx: Context) = call
        override fun screenOn(ctx: Context) = on
        override fun locked(ctx: Context) = isLocked
        override fun defaultSmsPackage(ctx: Context) = AppLauncher.PKG_MESSAGING
    }

    private class Recorder : TextAlert.Effects {
        var sounds = 0
        var vibrations = 0
        override fun sound(ctx: Context, uri: Uri?) { sounds++ }
        override fun vibrate(ctx: Context, pattern: LongArray?) { vibrations++ }
    }

    private class FakeHost : TextAlertBanner.Host {
        val views = mutableListOf<View>()
        override fun add(ctx: Context, view: View): Boolean { views += view; return true }
        override fun remove(view: View) { views -= view }
    }

    private lateinit var env: FakeEnv
    private lateinit var fx: Recorder
    private lateinit var host: FakeHost

    @Before
    fun setUp() {
        TextAlert.resetForTest()
        TextAlertBanner.resetForTest()
        env = FakeEnv(); fx = Recorder(); host = FakeHost()
        TextAlert.envForTest = env
        TextAlert.effectsForTest = fx
        TextAlertBanner.hostForTest = host
    }

    @After
    fun tearDown() {
        TextAlert.resetForTest()
        TextAlertBanner.resetForTest()
    }

    private fun facts(
        matches: Boolean = true,
        peekSuppressed: Boolean = false,
        importance: Int = NotificationManager.IMPORTANCE_HIGH,
        vibrates: Boolean = true,
    ) = TextAlert.RankFacts(matches, peekSuppressed, importance, null, vibrates, null)

    private val openIntent = Intent("watch.rist.assistant.test.OPEN_CONVERSATION")

    private fun sms(
        sender: String = "Alice Example",
        text: String = "Running late, be there at 7\nsecond line",
        whenMs: Long = 1_000L,
        pkg: String = AppLauncher.PKG_MESSAGING,
        id: Int = 1,
        flags: Int = 0,
    ): StatusBarNotification {
        val n = Notification.Builder(app, "msg")
            .setContentTitle(sender)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setWhen(whenMs)
            .setContentIntent(PendingIntent.getBroadcast(app, 0, openIntent, PendingIntent.FLAG_IMMUTABLE))
            .build()
        n.flags = n.flags or flags
        @Suppress("DEPRECATION")
        return StatusBarNotification(
            pkg, pkg, id, null, Process.myUid(), 0, 0, n, Process.myUserHandle(), whenMs
        )
    }

    private fun texts(v: View, tag: String): String? {
        var hit: String? = null
        fun walk(x: View) {
            if (x.tag == tag && x is TextView) hit = x.text.toString()
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return hit
    }

    private fun card(v: View): View {
        var hit: View? = null
        fun walk(x: View) {
            if (x.tag == TextAlertBanner.TAG_BANNER) hit = x
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return requireNotNull(hit)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // ---- the banner ----

    @Test
    fun `a text shows a banner with the sender and the first line`() {
        val a = TextAlert.onPosted(app, sms(), facts())
        assertTrue(a.banner)
        val v = requireNotNull(TextAlertBanner.current())
        assertEquals("Alice Example", texts(v, TextAlertBanner.TAG_TITLE))
        assertEquals("Running late, be there at 7", texts(v, TextAlertBanner.TAG_BODY))
    }

    @Test
    fun `a locked phone shows only New message`() {
        env.isLocked = true
        TextAlert.onPosted(app, sms(), facts())
        val v = requireNotNull(TextAlertBanner.current())
        assertEquals("New message", texts(v, TextAlertBanner.TAG_TITLE))
        assertNull("no body on a locked phone", texts(v, TextAlertBanner.TAG_BODY))
        assertFalse(v.toString(), card(v).contentDescription.toString().contains("Alice"))
    }

    @Test
    fun `tapping the banner opens the conversation and takes the banner down`() {
        TextAlert.onPosted(app, sms(), facts())
        val v = requireNotNull(TextAlertBanner.current())
        card(v).performClick()
        idle(100)
        assertNull(TextAlertBanner.current())
        assertTrue(host.views.isEmpty())
        val sent = shadowOf(app as android.app.Application).broadcastIntents
        assertTrue("the Messages app's own conversation intent was sent: $sent",
            sent.any { it.action == openIntent.action })
    }

    @Test
    fun `swiping the banner up dismisses it`() {
        TextAlert.onPosted(app, sms(), facts())
        val v = requireNotNull(TextAlertBanner.current())
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, 1080, v.measuredHeight)
        idle(300)
        val c = card(v)
        val t0 = SystemClock.uptimeMillis()
        fun ev(action: Int, y: Float, t: Long) = MotionEvent.obtain(t0, t, action, 200f, y, 0)
        c.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 200f, t0))
        for (i in 1..10) c.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 200f - 40f * i, t0 + i * 20L))
        c.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, -200f, t0 + 220L))
        idle(500)
        assertNull("swiped away", TextAlertBanner.current())
        assertTrue(host.views.isEmpty())
        assertEquals("a swipe is not a tap",
            0, shadowOf(app as android.app.Application).broadcastIntents.count { it.action == openIntent.action })
    }

    @Test
    fun `the banner goes by itself after six seconds`() {
        TextAlert.onPosted(app, sms(), facts())
        idle(5_000)
        assertNotNull(TextAlertBanner.current())
        idle(1_500)
        assertNull(TextAlertBanner.current())
    }

    @Test
    fun `a newer text replaces the banner rather than stacking`() {
        TextAlert.onPosted(app, sms(id = 1), facts())
        TextAlert.onPosted(app, sms(id = 2, sender = "Bob", text = "hi", whenMs = 2_000L), facts())
        assertEquals(1, host.views.size)
        assertEquals("Bob", texts(host.views.single(), TextAlertBanner.TAG_TITLE))
    }

    // ---- sound and vibration by the phone's settings ----

    @Test
    fun `ringer normal - sound, and vibration when the channel vibrates`() {
        val a = TextAlert.decide(true, facts(vibrates = true), AudioManager.RINGER_MODE_NORMAL, false, true)
        assertEquals(TextAlert.Alerting(banner = true, sound = true, vibrate = true), a)
        val b = TextAlert.decide(true, facts(vibrates = false), AudioManager.RINGER_MODE_NORMAL, false, true)
        assertEquals(TextAlert.Alerting(banner = true, sound = true, vibrate = false), b)
    }

    @Test
    fun `ringer vibrate - vibration only`() {
        val a = TextAlert.decide(true, facts(vibrates = false), AudioManager.RINGER_MODE_VIBRATE, false, true)
        assertEquals(TextAlert.Alerting(banner = true, sound = false, vibrate = true), a)
    }

    @Test
    fun `ringer silent - no sound, no vibration, banner still shows`() {
        val a = TextAlert.decide(true, facts(), AudioManager.RINGER_MODE_SILENT, false, true)
        assertEquals(TextAlert.Alerting(banner = true, sound = false, vibrate = false), a)
    }

    @Test
    fun `do not disturb holds back sound and vibration`() {
        val a = TextAlert.decide(true, facts(matches = false), AudioManager.RINGER_MODE_NORMAL, false, true)
        assertFalse(a.sound); assertFalse(a.vibrate)
        assertTrue("a DND that does not hide notifications still lets the banner show", a.banner)
        val hidden = TextAlert.decide(true, facts(matches = false, peekSuppressed = true),
            AudioManager.RINGER_MODE_NORMAL, false, true)
        assertEquals(TextAlert.Alerting.NONE, hidden)
    }

    @Test
    fun `do not disturb without a ranking falls back to the interruption filter`() {
        env.allowsAll = false
        val a = TextAlert.onPosted(app, sms(), null)
        assertFalse(a.sound); assertFalse(a.vibrate); assertFalse(a.banner)
        assertEquals(0, fx.sounds); assertEquals(0, fx.vibrations)
    }

    @Test
    fun `a channel turned down is left silent`() {
        val a = TextAlert.decide(true, facts(importance = NotificationManager.IMPORTANCE_LOW),
            AudioManager.RINGER_MODE_NORMAL, false, true)
        assertEquals(TextAlert.Alerting.NONE, a)
    }

    @Test
    fun `on a call - banner only`() {
        val a = TextAlert.decide(true, facts(), AudioManager.RINGER_MODE_NORMAL, true, true)
        assertEquals(TextAlert.Alerting(banner = true, sound = false, vibrate = false), a)
    }

    @Test
    fun `screen off - sound and vibration, no banner`() {
        val a = TextAlert.decide(true, facts(), AudioManager.RINGER_MODE_NORMAL, false, false)
        assertEquals(TextAlert.Alerting(banner = false, sound = true, vibrate = true), a)
    }

    @Test
    fun `onPosted plays what the rules call for`() {
        env.ringer = AudioManager.RINGER_MODE_VIBRATE
        TextAlert.onPosted(app, sms(), facts())
        assertEquals(0, fx.sounds); assertEquals(1, fx.vibrations)
    }

    // ---- never twice ----

    @Test
    fun `when the system is not muted Android alerts and Rist stays out of it`() {
        env.muted = false
        val a = TextAlert.onPosted(app, sms(), facts())
        assertEquals(TextAlert.Alerting.NONE, a)
        assertEquals(0, fx.sounds); assertEquals(0, fx.vibrations)
        assertNull(TextAlertBanner.current())
    }

    @Test
    fun `the same message re-posted does not alert again`() {
        TextAlert.onPosted(app, sms(), facts())
        TextAlert.onPosted(app, sms(), facts())
        assertEquals(1, fx.sounds); assertEquals(1, fx.vibrations)
    }

    @Test
    fun `a new message in the same conversation alerts again`() {
        TextAlert.onPosted(app, sms(text = "one", whenMs = 1_000L), facts())
        TextAlert.onPosted(app, sms(text = "two", whenMs = 2_000L), facts())
        assertEquals(2, fx.sounds)
    }

    @Test
    fun `an update marked alert-once does not alert again`() {
        TextAlert.onPosted(app, sms(text = "one"), facts())
        TextAlert.onPosted(app, sms(text = "one, edited", flags = Notification.FLAG_ONLY_ALERT_ONCE), facts())
        assertEquals(1, fx.sounds)
    }

    @Test
    fun `other apps and group summaries are not texts`() {
        assertEquals(TextAlert.Alerting.NONE, TextAlert.onPosted(app, sms(pkg = "com.example.other"), facts()))
        assertEquals(TextAlert.Alerting.NONE,
            TextAlert.onPosted(app, sms(id = 9, flags = Notification.FLAG_GROUP_SUMMARY), facts()))
        assertEquals(0, fx.sounds)
    }

    // ---- what mutes the system ----

    @Test
    fun `the kiosk's lock task mutes notifications, one with the notifications feature does not`() {
        val kiosk = KioskManager.lockTaskFeaturesFor(hasCredential = true)
        assertTrue(TextAlert.alertsMutedBy(ActivityManager.LOCK_TASK_MODE_LOCKED, kiosk))
        assertFalse(TextAlert.alertsMutedBy(ActivityManager.LOCK_TASK_MODE_LOCKED,
            kiosk or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS))
        assertFalse(TextAlert.alertsMutedBy(ActivityManager.LOCK_TASK_MODE_NONE, kiosk))
        assertTrue(TextAlert.alertsMutedBy(ActivityManager.LOCK_TASK_MODE_PINNED, 0))
    }
}
