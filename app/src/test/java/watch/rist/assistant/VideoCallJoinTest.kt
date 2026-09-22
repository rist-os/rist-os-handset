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

/** A command puts up a screen and nothing more; the tap does the rest. */
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
    fun `with a confirmation, the join screen waits for the answer, and comes either way`() {
        val reply = rist.v1.DeviceResponse.newBuilder().setRequestId("vc-confirm")
            .setConfirm(rist.v1.ConfirmRequest.newBuilder().setActionId("a1").setPrompt("Email Sarah the link?"))
            .setVideoCall(join("https://app.zoom.us/wc/123/join")).build()
        assertTrue(DeviceCommands.handle(ctx, reply))
        assertNull(shadowOf(app).nextStartedActivity)
        // Answered (yes or no, it does not matter here): now the join screen.
        VideoCalls.releaseDeferred(ctx)
        assertEquals(VideoCallJoinActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
        VideoCalls.releaseDeferred(ctx)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `the join screen goes up first, so the composer lands on top of it`() {
        val reply = rist.v1.DeviceResponse.newBuilder().setRequestId("vc-sms")
            .setComms(rist.v1.CommsCommand.newBuilder().setAction("sms").setNumber("+15551230000").setBody("https://x/c/1"))
            .setVideoCall(join("https://app.zoom.us/wc/123/join")).build()
        assertTrue(DeviceCommands.handle(ctx, reply))
        // Robolectric hands back the most recent start first: the composer, on top, then the join
        // screen underneath it.
        assertEquals(android.content.Intent.ACTION_SENDTO, shadowOf(app).nextStartedActivity.action)
        assertEquals(VideoCallJoinActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
    }

    @Test
    fun `a second join replaces the join screen instead of stacking another`() {
        VideoCalls.join(ctx, "https://app.zoom.us/wc/1/join", "", "", "zoom")
        val flags = shadowOf(app).nextStartedActivity.flags
        assertTrue(flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun `a title is cut to the length the join screen shows`() {
        VideoCalls.join(ctx, "https://app.zoom.us/wc/1/join", "", "x".repeat(300), "zoom")
        assertEquals(VideoCalls.TITLE_MAX, shadowOf(app).nextStartedActivity.getStringExtra(VideoCallJoinActivity.EXTRA_TITLE)!!.length)
    }

    @Test
    fun `a call command delivered twice opens one join screen`() {
        val reply = rist.v1.DeviceResponse.newBuilder().setRequestId("vc-1")
            .setVideoCall(join("https://app.zoom.us/wc/123/join")).build()
        assertTrue(DeviceCommands.handle(ctx, reply))
        assertEquals(VideoCallJoinActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
        // The same reply delivered twice (activity and service both see it) opens one screen.
        assertFalse(DeviceCommands.handle(ctx, reply))
    }
}
