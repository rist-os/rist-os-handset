package watch.rist.assistant

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.VideoCallCommand

/** video_calls.md section 4: a command puts up a screen and nothing more; the tap does the rest. */
@RunWith(RobolectricTestRunner::class)
class VideoCallJoinTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun backend() {
        Config.setDeployDefaultsForTest("https://api.example.net/v1/device", "")
    }

    @After
    fun reset() = Config.clearDeployDefaultsForTest()

    private fun join(url: String, provider: String = "zoom") = VideoCallCommand.newBuilder()
        .setAction("join").setUrl(url).setProvider(provider).setTitle("Standup").build()

    @Test
    fun `a join command shows the join screen and does not open the call`() {
        VideoCalls.onCommand(ctx, join("https://app.zoom.us/wc/123/join?pwd=x"))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(VideoCallJoinActivity::class.java.name, started.component?.className)
        assertEquals("ZOOM", started.getStringExtra(VideoCallJoinActivity.EXTRA_PROVIDER))
        // The screen, and only the screen: the browser is a second step behind a tap.
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `a link off the list never reaches the screen`() {
        assertFalse(VideoCalls.join(ctx, "https://evilzoom.us/j/1", "", "", "zoom"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `the host picks the handling, whatever label came with it`() {
        VideoCalls.onCommand(ctx, join("https://meet.google.com/abc-defg-hij", provider = "rist"))
        assertEquals("MEET", shadowOf(app).nextStartedActivity.getStringExtra(VideoCallJoinActivity.EXTRA_PROVIDER))
    }

    @Test
    fun `an original link that is not on the list is dropped, not carried along`() {
        VideoCalls.join(ctx, "https://app.zoom.us/wc/1/join", "https://evil.example/j/1", "", "zoom")
        assertEquals("", shadowOf(app).nextStartedActivity.getStringExtra(VideoCallJoinActivity.EXTRA_ORIGINAL_URL))
    }

    @Test
    fun `end with no call open is a silent no-op`() {
        assertFalse(VideoCalls.isOpen())
        VideoCalls.onCommand(ctx, VideoCallCommand.newBuilder().setAction("end").build())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `the join screen opens nothing by itself, and closes without a provider`() {
        val ok = Robolectric.buildActivity(
            VideoCallJoinActivity::class.java,
            Intent(ctx, VideoCallJoinActivity::class.java)
                .putExtra(VideoCallJoinActivity.EXTRA_URL, "https://meet.google.com/abc-defg-hij")
                .putExtra(VideoCallJoinActivity.EXTRA_PROVIDER, "MEET"),
        ).create().start().resume().get()
        assertFalse(ok.isFinishing)
        assertNull(shadowOf(app).nextStartedActivity)

        val bad = Robolectric.buildActivity(
            VideoCallJoinActivity::class.java, Intent(ctx, VideoCallJoinActivity::class.java)
        ).create().get()
        assertTrue(bad.isFinishing)
    }

    @Test
    fun `a call command in a reply is applied after any message it arrives with`() {
        val reply = rist.v1.DeviceResponse.newBuilder().setRequestId("vc-1")
            .setVideoCall(join("https://app.zoom.us/wc/123/join")).build()
        assertTrue(DeviceCommands.handle(ctx, reply))
        assertEquals(VideoCallJoinActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
        // The same reply delivered twice (activity and service both see it) opens one screen.
        assertFalse(DeviceCommands.handle(ctx, reply))
    }
}
