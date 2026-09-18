package watch.rist.assistant

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The bug: voice replies play on Android's assistant volume, which Android's volume panel has
 * no slider for, so a phone with that volume at zero had no visible way to raise it.
 */
@RunWith(RobolectricTestRunner::class)
class VolumePanelTest {

    private fun route(
        call: Boolean = false, alarm: Boolean = false, picked: VolumeKeys.Channel? = null,
        voice: Boolean = false, media: Boolean = false,
    ) = VolumeKeys.route(call, alarm, picked, voice, media)

    @Test
    fun `the buttons start on the ringer`() {
        assertEquals(VolumeKeys.Channel.RINGER, route())
    }

    @Test
    fun `the ringer stays the default even while something plays`() {
        assertEquals(VolumeKeys.Channel.RINGER, route(media = true))
        assertEquals(VolumeKeys.Channel.RINGER, route(voice = true, media = true))
    }

    @Test
    fun `a slider picked in the open panel keeps the buttons`() {
        assertEquals(VolumeKeys.Channel.ALARM, route(picked = VolumeKeys.Channel.ALARM, media = true))
    }

    @Test
    fun `calls and ringing alarms keep Android's own button behaviour`() {
        assertNull("in a call the buttons set call volume", route(call = true, picked = VolumeKeys.Channel.MEDIA))
        assertNull("a ringing alarm is its own screen's to handle", route(alarm = true))
    }

    @Test
    fun `a step stays inside the stream's range`() {
        assertEquals(1, VolumeKeys.step(0, 0, 15, raise = true))
        assertEquals(0, VolumeKeys.step(0, 0, 15, raise = false))
        assertEquals(15, VolumeKeys.step(15, 0, 15, raise = true))
        assertEquals("the alarm cannot be stepped below its floor", 1, VolumeKeys.step(1, 1, 7, raise = false))
    }

    @Test
    fun `without the privileged permission, Voice is still its own slider, kept by RIST`() {
        // The phone's app build cannot set Android's assistant volume, so replies play as media
        // scaled by RIST's own level: turning Voice down must not touch Media.
        val a = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()
        val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)
        Config.setVoiceLevel(a, Config.VOICE_LEVEL_MAX)

        a.volumePanel.show(VolumeKeys.Channel.VOICE)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))

        assertEquals(Config.VOICE_LEVEL_MAX - 1, Config.voiceLevel(a))
        assertEquals("media is untouched", 9, am.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(VolumeKeys.channels(voiceOwnVolume = false).contains(VolumeKeys.Channel.VOICE))
    }

    @Test
    fun `the voice level scales replies from silent to full, never louder`() {
        assertEquals(0f, Playback.gainFor(0), 0f)
        assertEquals(1f, Playback.gainFor(Config.VOICE_LEVEL_MAX), 0f)
        assertEquals(1f, Playback.gainFor(Config.VOICE_LEVEL_MAX + 5), 0f)
    }

    @Test
    fun `the ringer button cycles ring, vibrate, silent, as Android's does`() {
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, VolumeKeys.nextRingerMode(AudioManager.RINGER_MODE_NORMAL))
        assertEquals(AudioManager.RINGER_MODE_SILENT, VolumeKeys.nextRingerMode(AudioManager.RINGER_MODE_VIBRATE))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, VolumeKeys.nextRingerMode(AudioManager.RINGER_MODE_SILENT))
    }

    @Test
    fun `the voice is the first slider, as the one with no other control`() {
        assertEquals(VolumeKeys.Channel.VOICE, VolumeKeys.Channel.values().first())
        assertEquals(11, VolumeKeys.Channel.VOICE.stream)
    }

    @Test
    fun `on the home screen a volume press is taken and opens the panel`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()

        assertTrue("the press must not fall through to Android",
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue("and the release too, or Android acts on it",
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue(a.volumePanel.isShowing)
        assertEquals(VolumeKeys.Channel.RINGER, a.volumePanel.target)
    }

    @Test
    fun `picking media in the panel then pressing down lowers media`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()
        val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)

        a.volumePanel.show(VolumeKeys.Channel.MEDIA)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals(4, am.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `every slider in the panel is laid out wide enough to see`() {
        // The row once took the width of the ringer button above it: only one slider showed.
        val a = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()
        a.volumePanel.show(VolumeKeys.Channel.MEDIA)
        val card = (a.window.decorView as android.view.ViewGroup).let { root ->
            (0 until root.childCount).map { root.getChildAt(it) }.last() as android.widget.LinearLayout
        }
        card.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.AT_MOST),
        )
        val row = card.getChildAt(1) as android.widget.LinearLayout
        val sliders = VolumeKeys.channels(VolumeKeys.voiceHasOwnVolume(a)).size
        assertEquals(sliders, row.childCount)
        val d = a.resources.displayMetrics.density
        assertTrue("row is ${row.measuredWidth}px for $sliders sliders",
            row.measuredWidth >= (sliders * 44 * d).toInt())
    }

    @Test
    fun `leaving the home screen closes the panel`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible()
        val a = controller.get()
        a.volumePanel.show(VolumeKeys.Channel.VOICE)
        assertTrue(a.volumePanel.isShowing)
        controller.pause()
        assertFalse(a.volumePanel.isShowing)
        assertNull(a.volumePanel.target)
    }
}
