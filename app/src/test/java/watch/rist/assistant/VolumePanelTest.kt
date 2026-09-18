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
    fun `with nothing playing the buttons adjust the voice`() {
        assertEquals(VolumeKeys.Channel.VOICE, route())
    }

    @Test
    fun `an audiobook playing takes the buttons, and a reply being spoken takes them over that`() {
        assertEquals(VolumeKeys.Channel.MEDIA, route(media = true))
        assertEquals(VolumeKeys.Channel.VOICE, route(voice = true, media = true))
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
    fun `where voice has no volume of its own, the buttons and the panel go to media`() {
        // The phone's app build cannot be granted the permission the assistant volume needs,
        // so replies play as media; a press that would have gone to voice must reach them.
        assertEquals(VolumeKeys.Channel.MEDIA, VolumeKeys.route(false, false, null, false, false, voiceOwnVolume = false))
        assertEquals(VolumeKeys.Channel.MEDIA,
            VolumeKeys.route(false, false, VolumeKeys.Channel.VOICE, false, false, voiceOwnVolume = false))
        assertFalse(VolumeKeys.channels(voiceOwnVolume = false).contains(VolumeKeys.Channel.VOICE))
        assertTrue(VolumeKeys.channels(voiceOwnVolume = true).contains(VolumeKeys.Channel.VOICE))
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
        // No privileged audio permission here, as on the phone's app build: voice is media.
        assertEquals(VolumeKeys.Channel.MEDIA, a.volumePanel.target)
    }

    @Test
    fun `with the privileged audio permission a press goes to the voice`() {
        val a = Robolectric.buildActivity(MainActivity::class.java).create().resume().visible().get()
        org.robolectric.Shadows.shadowOf(a.application)
            .grantPermissions("android.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED")
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(VolumeKeys.Channel.VOICE, a.volumePanel.target)
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
