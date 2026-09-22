package watch.rist.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import rist.v1.DeviceResponse
import rist.v1.Progress
import rist.v1.Speech

/**
 * Turns now ask for the stream (long_turns.md): the backend may take as long as the work needs,
 * and the stream is what keeps the connection alive and the person informed meanwhile.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingTurnTest {

    private lateinit var server: MockWebServer
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun start() {
        Config.setDeployDefaultsForTest("", "")
        server = MockWebServer()
        server.start()
        Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
        Config.setAuthToken(ctx, "")
        StreamingCancel.resetForTest()
    }

    @After
    fun stop() {
        Config.clearDeployDefaultsForTest()
        runCatching { server.shutdown() }
    }

    private fun frames(vararg f: DeviceResponse): Buffer {
        val out = ByteArrayOutputStream()
        f.forEach { it.writeDelimitedTo(out) }
        return Buffer().write(out.toByteArray())
    }

    private fun progress(text: String) = DeviceResponse.newBuilder()
        .setIsFinal(false).setProgress(Progress.newBuilder().setText(text).setKind("tool")).build()

    private fun final(text: String) = DeviceResponse.newBuilder()
        .setIsFinal(true).setSpeech(Speech.newBuilder().setText(text)).build()

    private fun streamed(body: Buffer) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", StreamingWire.SEQ_MEDIA_TYPE).setBody(body)

    @Test
    fun `a turn asks for the stream, shows each status line, and returns the answer`() {
        val lines = mutableListOf<String>()
        LocalBroadcastManager.getInstance(ctx).registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val p = Progress.parseFrom(i.getByteArrayExtra(StreamingStatus.EXTRA_PROGRESS_PROTO))
                lines += p.text
            }
        }, IntentFilter(StreamingStatus.ACTION_PROGRESS))
        server.enqueue(streamed(frames(progress("Checking your calendar"), progress("Writing the reply"), final("You're free at 3."))))

        val reply = Uploader(ctx).sendText("am I free this afternoon")
        ShadowLooper.idleMainLooper()

        val sent = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(StreamingWire.SEQ_MEDIA_TYPE, sent.getHeader("Accept"))
        assertEquals("You're free at 3.", reply?.speech?.text)
        assertEquals(listOf("Checking your calendar", "Writing the reply"), lines)
    }

    @Test
    fun `a stream cut off before its answer is reported as maybe done, not failed`() {
        // The backend had the request and keeps going after a socket drops; saying "it failed"
        // is what made people ask again and get the action twice.
        server.enqueue(streamed(frames(progress("Sending the email"))))
        val up = Uploader(ctx)
        assertNull(up.sendText("email Sam the plan"))
        assertEquals(Uploader.MAY_HAVE_HAPPENED, up.lastFailure)
    }

    @Test
    fun `turns wait three minutes between bytes, and never retry on their own`() {
        val c = Uploader.turnClient()
        assertEquals(180_000, c.readTimeoutMillis)
        assertEquals(false, c.retryOnConnectionFailure)
    }
}
