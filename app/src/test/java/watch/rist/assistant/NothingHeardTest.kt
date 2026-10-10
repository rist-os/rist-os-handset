package watch.rist.assistant

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A recording the person started that is dropped as silent or too short tells them so: the
 * refusal tap, a low tone where the listening cue plays, and "I didn't hear anything." in the
 * feed. Nothing is sent.
 */
@RunWith(RobolectricTestRunner::class)
class NothingHeardTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val am get() = app.getSystemService(AudioManager::class.java)
    private val vibrator get() = app.getSystemService(Vibrator::class.java)
    private val realVibrator = Haptics.vibratorOf

    private class Tone(val hz: Int, val out: AudioDeviceInfo?)
    private val tones = mutableListOf<Tone>()
    private val realTone = CueTone.play
    private val realAmplitude = RecordService.amplitudeOf
    private val realFactory = HeadsetTalk.linkFactory
    private val discards = mutableListOf<Intent>()
    private val discardReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { discards += i }
    }

    /** What the microphone hears: below the speech floor unless a test says otherwise. */
    private var amplitude = 60

    private class FakeLink : HeadsetLink {
        var closed = 0
        var ready: ((AudioDeviceInfo?) -> Unit)? = null
        override fun open(onReady: (AudioDeviceInfo?) -> Unit, onDropped: () -> Unit) { ready = onReady }
        override fun close() { closed++ }
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        Config.usePlainPrefsForTest(app)
        Transcript.clearForTest(app)
        TurnRunner.resetForTest()
        HeadsetTalk.listening = false
        CueTone.play = { hz, _, out -> tones += Tone(hz, out); true }
        RecordService.amplitudeOf = { amplitude }
        Haptics.vibratorOf = { vibrator }
        org.robolectric.shadows.ShadowVibrator.reset()
        shadowOf(vibrator).setHasVibrator(true)
        shadowOf(app).clearStartedServices()
        LocalBroadcastManager.getInstance(app)
            .registerReceiver(discardReceiver, android.content.IntentFilter(RecordService.ACTION_CAPTURE_DISCARDED))
    }

    @After
    fun tearDown() {
        LocalBroadcastManager.getInstance(app).unregisterReceiver(discardReceiver)
        CueTone.play = realTone
        Haptics.vibratorOf = realVibrator
        RecordService.amplitudeOf = realAmplitude
        HeadsetTalk.linkFactory = realFactory
        HeadsetTalk.listening = false
        TurnRunner.resetForTest()
        Transcript.clearForTest(app)
        Config.forgetPrefsForTest()
    }

    private fun idle(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    private fun service() = Robolectric.buildService(RecordService::class.java).create()

    private fun ServiceController<RecordService>.run(i: Intent, id: Int) = withIntent(i).startCommand(0, id)

    /** The vibrations made since [setUp]; the shadow keeps a predefined effect only here. */
    private fun haptics(): String = org.robolectric.shadows.ShadowVibrator::class.java
        .getDeclaredField("vibrationEffectSegments").apply { isAccessible = true }.get(null).toString()

    private fun assertTold(out: AudioDeviceInfo? = null) {
        assertEquals("one low tone", listOf(NothingHeard.TONE_HZ), tones.map { it.hz }.filter { it != 880 })
        assertSame("the tone plays where the listening cue does", out, tones.last().out)
        assertTrue("the refusal tap", haptics().contains("effect=DOUBLE_CLICK"))
        val e = Transcript.all(app).single()
        assertEquals(EntryState.FAILED, e.state)
        assertEquals("⚠ I didn't hear anything. Try again.", MainActivity.failedEntryLine(e.error))
        assertTrue("the home screen keeps the entry", discards.single().getBooleanExtra(RecordService.EXTRA_NOTHING_HEARD, false))
        assertEquals("nothing is sent", 0, TurnRunner.undeliveredForTest())
    }

    private fun press(action: String) {
        shadowOf(app).clearStartedServices()
        KeyEventReceiver().onReceive(app, Intent(action))
    }

    @Test
    fun `a silent hold of the physical button says nothing was heard`() {
        val svc = service()
        press(KeyEventReceiver.ACTION_RECORD_DOWN)
        svc.run(shadowOf(app).nextStartedService, 1)
        idle(1_500)
        press(KeyEventReceiver.ACTION_RECORD_UP)
        svc.run(shadowOf(app).nextStartedService, 2)
        idle(500)
        assertTold()
    }

    @Test
    fun `a too-short press of the physical button says nothing was heard`() {
        val svc = service()
        press(KeyEventReceiver.ACTION_RECORD_DOWN)
        svc.run(shadowOf(app).nextStartedService, 1)
        idle(100)
        press(KeyEventReceiver.ACTION_RECORD_UP)
        svc.run(shadowOf(app).nextStartedService, 2)
        idle(500)
        assertTold()
    }

    @Test
    fun `a silent headset turn says so in the headset, then gives the headset back`() {
        val scoIn = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        val scoOut = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        shadowOf(am).setOutputDevices(listOf(scoOut))
        val link = FakeLink()
        HeadsetTalk.linkFactory = { link }
        val svc = service()
        val headset = Intent(app, RecordService::class.java).putExtra(RecordService.EXTRA_HEADSET, true)
        svc.run(Intent(headset).setAction(RecordService.ACTION_START), 1)
        link.ready!!(scoIn)
        idle(1_500)
        assertEquals("the listening cue played in the headset", listOf(880), tones.map { it.hz })

        svc.run(Intent(headset).setAction(RecordService.ACTION_STOP), 2)
        assertFalse(HeadsetTalk.listening)
        assertEquals("the headset stays routed until the tone has played", 0, link.closed)
        idle(500)
        assertEquals(1, link.closed)
        assertTold(out = scoOut)
    }

    @Test
    fun `a headset press again before the microphone opens says nothing was heard`() {
        val link = FakeLink()
        HeadsetTalk.linkFactory = { link }
        val svc = service()
        val headset = Intent(app, RecordService::class.java).putExtra(RecordService.EXTRA_HEADSET, true)
        svc.run(Intent(headset).setAction(RecordService.ACTION_START), 1)
        svc.run(Intent(headset).setAction(RecordService.ACTION_STOP), 2)
        idle(500)
        assertEquals(1, link.closed)
        assertTold()
    }

    @Test
    fun `a silent hold of the on-screen button leaves its entry saying nothing was heard`() {
        val home = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        val button = home.get().findViewById<View>(R.id.talkButton)
        val down = SystemClock.uptimeMillis()
        shadowOf(app).clearStartedServices()
        button.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        val start = shadowOf(app).nextStartedService
        assertEquals(RecordService.ACTION_START, start.action)
        val svc = service()
        svc.run(start, 1)
        idle(1_500)
        button.dispatchTouchEvent(MotionEvent.obtain(down, down + 1_500, MotionEvent.ACTION_UP, 10f, 10f, 0))
        val stop = shadowOf(app).nextStartedService
        assertEquals(RecordService.ACTION_STOP, stop.action)
        svc.run(stop, 2)
        idle(500)

        assertTold()
        assertTrue(feedText(home.get()).contains("I didn't hear anything. Try again."))
        assertFalse(feedText(home.get()).contains("waiting for a reply"))
    }

    @Test
    fun `a recording the person cancels says nothing`() {
        val svc = service()
        press(KeyEventReceiver.ACTION_RECORD_DOWN)
        svc.run(shadowOf(app).nextStartedService, 1)
        idle(1_500)
        svc.run(Intent(app, RecordService::class.java).setAction(RecordService.ACTION_CANCEL), 2)
        idle(500)
        assertTrue(tones.isEmpty())
        assertEquals("no refusal tap", "[]", haptics())
        assertTrue(Transcript.all(app).isEmpty())
        assertFalse(discards.single().getBooleanExtra(RecordService.EXTRA_NOTHING_HEARD, true))
        assertNull(shadowOf(app).nextStartedService)
    }

    private fun feedText(a: Activity): String {
        val out = StringBuilder()
        fun walk(v: View) {
            if (v is TextView) out.append(v.text).append('\n')
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.findViewById(R.id.replyContainer))
        return out.toString()
    }
}
