package watch.rist.assistant

import android.app.Application
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class HeadsetTalkTest {

    private lateinit var app: Application
    private val am get() = app.getSystemService(AudioManager::class.java)

    private class FakeLink : HeadsetLink {
        var opened = 0
        var closed = 0
        var ready: ((AudioDeviceInfo?) -> Unit)? = null
        var dropped: (() -> Unit)? = null
        override fun open(onReady: (AudioDeviceInfo?) -> Unit, onDropped: () -> Unit) {
            opened++; ready = onReady; dropped = onDropped
        }
        override fun close() { closed++ }
    }

    private class FakeHfp(val address: String?) : Hfp {
        var audio: ((Boolean) -> Unit)? = null
        var stops = 0
        override fun start(audio: (Boolean) -> Unit, started: (String?) -> Unit) {
            this.audio = audio
            started(address)
        }
        override fun stop() { stops++ }
    }

    private val defaultFactory = HeadsetTalk.linkFactory
    private val defaultUid = HeadsetTalk.launcherUidOf

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        HeadsetTalk.listening = false
        shadowOf(app).clearStartedServices()
    }

    @After
    fun tearDown() {
        HeadsetTalk.linkFactory = defaultFactory
        HeadsetTalk.launcherUidOf = defaultUid
        HeadsetTalk.listening = false
    }

    private fun launch(action: String, uid: Int): Intent? {
        HeadsetTalk.launcherUidOf = { uid }
        shadowOf(app).clearStartedServices()
        val activity = Robolectric.buildActivity(HeadsetTalkActivity::class.java, Intent(action)).create().get()
        assertTrue("the activity shows nothing and is gone", activity.isFinishing)
        return shadowOf(app).nextStartedService
    }

    @Test
    fun `holding the headset button starts a hands-free turn`() {
        val svc = launch(Intent.ACTION_VOICE_COMMAND, uid = 1002)
        assertNotNull("the headset's voice request must start a turn", svc)
        assertEquals(RecordService::class.java.name, svc!!.component?.className)
        assertEquals(RecordService.ACTION_START, svc.action)
        assertTrue(svc.getBooleanExtra(RecordService.EXTRA_HEADSET, false))
    }

    @Test
    fun `the headset's voice request reaches Rist`() {
        val found = app.packageManager.queryIntentActivities(Intent(Intent.ACTION_VOICE_COMMAND), 0)
            .map { it.activityInfo.name }
        assertTrue(found.toString(), HeadsetTalkActivity::class.java.name in found)
    }

    @Test
    fun `a single tap stays play and pause`() {
        val tap = Intent(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        val ours = app.packageManager.queryIntentActivities(tap, 0).map { it.activityInfo.name } +
            app.packageManager.queryBroadcastReceivers(tap, 0).map { it.activityInfo.name }
        assertFalse(ours.toString(), HeadsetTalkActivity::class.java.name in ours)
        assertFalse(ours.toString(), KeyEventReceiver::class.java.name in ours)
        assertNull("a media button never starts a turn", launch(Intent.ACTION_MEDIA_BUTTON, uid = 1002))
    }

    @Test
    fun `another app's voice command does not open the microphone`() {
        assertNull(launch(Intent.ACTION_VOICE_COMMAND, uid = 10_123))
        assertNull(launch(Intent.ACTION_VOICE_COMMAND, uid = 2000))
        assertNull(launch(Intent.ACTION_VOICE_COMMAND, uid = -1))
        assertTrue(HeadsetTalk.pressedOnHeadset(1000))
        assertTrue("Bluetooth in a secondary user", HeadsetTalk.pressedOnHeadset(10 * 100_000 + 1002))
    }

    @Test
    fun `a press while listening ends listening`() {
        HeadsetTalk.listening = true
        val svc = launch(Intent.ACTION_VOICE_COMMAND, uid = 1002)
        assertEquals(RecordService.ACTION_STOP, svc!!.action)
    }

    private class Listening(
        val controller: org.robolectric.android.controller.ServiceController<RecordService>,
        val link: FakeLink,
        val discards: IntArray,
    )

    /** A headset turn with its microphone open, counting recordings thrown away unsent. */
    private fun listening(): Listening {
        val link = FakeLink()
        HeadsetTalk.linkFactory = { link }
        val discards = intArrayOf(0)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(app).registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: Intent) { discards[0]++ }
            },
            android.content.IntentFilter(RecordService.ACTION_CAPTURE_DISCARDED),
        )
        val controller = Robolectric.buildService(RecordService::class.java).create()
        controller.withIntent(
            Intent(app, RecordService::class.java).setAction(RecordService.ACTION_START)
                .putExtra(RecordService.EXTRA_HEADSET, true)
        ).startCommand(0, 1)
        link.ready!!(null)
        ShadowLooper.idleMainLooper(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("listening once the microphone is open", HeadsetTalk.listening)
        return Listening(controller, link, discards)
    }

    @Test
    fun `silence never ends a headset turn`() {
        val t = listening()
        ShadowLooper.idleMainLooper(RecordService.MAX_CAPTURE_MS - 5_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("no stop when speech ends, and no give-up when none comes", HeadsetTalk.listening)
        assertEquals(0, t.link.closed)
        assertEquals(0, t.discards[0])
    }

    @Test
    fun `a headset turn stops at the on-screen button's cap and sends`() {
        val t = listening()
        ShadowLooper.idleMainLooper(RecordService.MAX_CAPTURE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(HeadsetTalk.listening)
        assertEquals("the headset's microphone is given back", 1, t.link.closed)
        assertEquals("sent, as the on-screen button does at its cap", 0, t.discards[0])
    }

    @Test
    fun `a press that closes the headset's voice session sends`() {
        val t = listening()
        t.link.dropped!!()
        ShadowLooper.idleMainLooper()
        assertFalse(HeadsetTalk.listening)
        assertEquals(1, t.link.closed)
        assertEquals("a press sends; it does not cancel", 0, t.discards[0])
    }

    @Test
    fun `a press that arrives as a new voice request sends`() {
        val t = listening()
        val stop = launch(Intent.ACTION_VOICE_COMMAND, uid = 1002)!!
        assertEquals(RecordService.ACTION_STOP, stop.action)
        t.controller.withIntent(stop).startCommand(0, 2)
        ShadowLooper.idleMainLooper()
        assertFalse(HeadsetTalk.listening)
        assertEquals(1, t.link.closed)
        assertEquals("a press sends; it does not cancel", 0, t.discards[0])
    }

    @Test
    fun `the headset's microphone is used, then given back`() {
        val sco = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        shadowOf(am).setInputDevices(listOf(sco))
        val hfp = FakeHfp("00:11:22:33:44:55")
        val link = BluetoothHeadsetLink(app, hfp)
        var got: AudioDeviceInfo? = null
        var dropped = false
        link.open({ got = it }, { dropped = true })
        ShadowLooper.idleMainLooper()
        assertNull("not before the headset's audio is up", got)

        hfp.audio!!(true)
        ShadowLooper.idleMainLooper()
        assertSame(sco, got)

        hfp.audio!!(false)
        ShadowLooper.idleMainLooper()
        assertTrue("the headset closing its audio is the second press", dropped)

        link.close()
        assertEquals(1, hfp.stops)
    }

    @Test
    fun `a headset whose audio never comes up falls back to the phone's microphone`() {
        val hfp = FakeHfp("00:11:22:33:44:55")
        val link = BluetoothHeadsetLink(app, hfp)
        var called = false
        var got: AudioDeviceInfo? = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        link.open({ called = true; got = it }, {})
        ShadowLooper.idleMainLooper(BluetoothHeadsetLink.AUDIO_WAIT_MS + 100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(called)
        assertNull(got)
        assertEquals("the headset's request is closed", 1, hfp.stops)
    }

    @Test
    fun `an LE Audio headset is routed for the turn and the routing restored`() {
        val ble = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLE_HEADSET).build()
        shadowOf(am).setAvailableCommunicationDevices(listOf(ble))
        shadowOf(am).setInputDevices(listOf(ble))
        val link = BluetoothHeadsetLink(app, FakeHfp(null))
        var got: AudioDeviceInfo? = null
        link.open({ got = it }, {})
        ShadowLooper.idleMainLooper()
        assertSame(ble, got)
        assertSame(ble, am.communicationDevice)

        link.close()
        assertNull("routing is back to what it was", am.communicationDevice)
    }

    @Test
    fun `media pauses while listening and plays on after the reply`() {
        val link = FakeLink()
        val turn = HeadsetTurn(app, link)
        turn.begin({}, {})
        val req = shadowOf(am).lastAudioFocusRequest
        assertNotNull("media is paused", req)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, req!!.durationHint)
        assertEquals(1, link.opened)

        turn.releaseRoute()
        assertEquals(1, link.closed)
        assertNull("media stays paused until the reply is spoken", shadowOf(am).lastAbandonedAudioFocusRequest)

        turn.releaseMedia()
        assertSame(req.audioFocusRequest, shadowOf(am).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `a reply that never arrives still lets media play on`() {
        val turn = HeadsetTurn(app, FakeLink())
        turn.begin({}, {})
        val req = shadowOf(am).lastAudioFocusRequest!!
        val service = Robolectric.buildService(RecordService::class.java).create().get()
        service.handleResponse(null, "", 0L, turn)
        assertSame(req.audioFocusRequest, shadowOf(am).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `a press too soon after the cue is dropped like a too-short hold, and media plays on`() {
        val link = FakeLink()
        HeadsetTalk.linkFactory = { link }
        val controller = Robolectric.buildService(RecordService::class.java).create()
        controller.withIntent(
            Intent(app, RecordService::class.java).setAction(RecordService.ACTION_START)
                .putExtra(RecordService.EXTRA_HEADSET, true)
        ).startCommand(0, 1)
        assertEquals(1, link.opened)
        assertTrue(HeadsetTalk.listening)
        val focus = shadowOf(am).lastAudioFocusRequest
        assertNotNull(focus)

        link.ready!!(null)
        ShadowLooper.idleMainLooper(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        link.dropped!!()
        ShadowLooper.idleMainLooper()
        assertFalse(HeadsetTalk.listening)
        assertEquals("the headset's microphone and routing are given back", 1, link.closed)
        assertSame(focus!!.audioFocusRequest, shadowOf(am).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `pressing again before the microphone opens cancels the turn`() {
        val link = FakeLink()
        HeadsetTalk.linkFactory = { link }
        val controller = Robolectric.buildService(RecordService::class.java).create()
        controller.withIntent(
            Intent(app, RecordService::class.java).setAction(RecordService.ACTION_START)
                .putExtra(RecordService.EXTRA_HEADSET, true)
        ).startCommand(0, 1)
        controller.withIntent(
            Intent(app, RecordService::class.java).setAction(RecordService.ACTION_STOP)
                .putExtra(RecordService.EXTRA_HEADSET, true)
        ).startCommand(0, 2)
        assertFalse(HeadsetTalk.listening)
        assertEquals(1, link.closed)
    }
}
