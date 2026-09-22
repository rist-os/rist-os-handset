package watch.rist.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import rist.v1.Notification
import rist.v1.WakeSignal
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** push_notifications.md §3: the held wake poll. */
@RunWith(RobolectricTestRunner::class)
class WakeLoopTest {

    private lateinit var server: MockWebServer
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @Before
    fun start() {
        Config.setDeployDefaultsForTest("", "")
        server = MockWebServer()
        server.start()
        Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
    }

    @After
    fun stop() {
        Config.clearDeployDefaultsForTest()
        runCatching { server.shutdown() }
    }

    private fun note(id: String, urgency: String = "active") = Notification.newBuilder()
        .setId(id).setKind("email").setTitle("New email from Sam").setUrgency(urgency).build()

    private fun signal(vararg n: Notification, mail: Int = 0, vm: Int = 0, after: Int = 240) =
        MockResponse().setResponseCode(200).setBody(Buffer().write(
            WakeSignal.newBuilder().addAllNotifications(n.toList())
                .setMailUnread(mail).setVoicemailUnheard(vm).setPollAfterS(after).build().toByteArray()
        ))

    @Test
    fun `the url is the wake path with max_notifications always explicit`() {
        val u = WakeLoop.wakeUrl("https://api.example/v1/device", emptyList(), 8)!!
        assertEquals("https://api.example/v1/device/wake?max_notifications=8", u)
        val withAcks = WakeLoop.wakeUrl("https://api.example/v1/device/", listOf("a", "b"), 8)!!
        assertEquals("https://api.example/v1/device/wake?ack=a%2Cb&max_notifications=8", withAcks)
        assertEquals("https://api.example/v1/device/wake?max_notifications=8",
            WakeLoop.wakeUrl("https://api.example", emptyList(), 8))
        assertNull(WakeLoop.wakeUrl("", emptyList(), 8))
    }

    private fun url(acks: List<String> = emptyList()) =
        WakeLoop.wakeUrl(server.url("/v1/device").toString(), acks, CommsFeed.MAX_NOTIFICATIONS)!!

    private fun exchange(acks: List<String> = emptyList()) =
        WakeLoop.exchange(http, url(acks), "Bearer tok", "dev1", acks)

    @Test
    fun `a poll is a GET carrying the bearer, the device and the feed's card count`() {
        server.enqueue(signal(note("n1"), mail = 3, vm = 2))
        val out = exchange() as WakeLoop.Outcome.Signal
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/device/wake", req.requestUrl!!.encodedPath)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("dev1", req.getHeader("X-Rist-Device"))
        assertEquals(CommsFeed.MAX_NOTIFICATIONS.toString(), req.requestUrl!!.queryParameter("max_notifications"))
        assertNull(req.requestUrl!!.queryParameter("ack"))
        assertEquals(listOf("n1"), out.signal.notificationsList.map { it.id })
        assertEquals(3, out.signal.mailUnread)
        assertEquals(2, out.signal.voicemailUnheard)
        assertEquals(240, out.signal.pollAfterS)
    }

    @Test
    fun `held ids ride the next poll as acks and are cleared only once it answers`() {
        server.enqueue(signal(note("n1"), note("n2")))
        server.enqueue(signal())
        val first = exchange() as WakeLoop.Outcome.Signal
        server.takeRequest()
        var held = NotificationQueue.upsert(emptyList(), NotificationQueue.fromWire(first.signal.notificationsList, 0L))

        val acks = NotificationQueue.pendingAcks(held)
        val second = exchange(acks) as WakeLoop.Outcome.Signal
        assertEquals("n1,n2", server.takeRequest().requestUrl!!.queryParameter("ack"))
        assertEquals(acks, second.acked)
        held = NotificationQueue.markAcked(held, second.acked)
        assertTrue(NotificationQueue.pendingAcks(held).isEmpty())
    }

    @Test
    fun `a failed poll reports no acks, so nothing is cleared`() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(exchange(listOf("n1")) is WakeLoop.Outcome.Retry)
    }

    @Test
    fun `401 and 403 stop, 503 and transport failures retry`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(WakeLoop.Outcome.Unauthorised, exchange())
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(WakeLoop.Outcome.Revoked, exchange())
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(exchange() is WakeLoop.Outcome.Retry)
        server.enqueue(MockResponse().setResponseCode(200).setBody("not a proto \u0000\u00ff"))
        assertTrue(exchange() is WakeLoop.Outcome.Retry)
        val u = url()
        server.shutdown()
        assertTrue(WakeLoop.exchange(http, u, "Bearer tok", "dev1", emptyList()) is WakeLoop.Outcome.Retry)
    }

    @Test
    fun `no token never reaches the network`() {
        // Robolectric's prefs refuse secrets, so this phone is unenrolled here.
        assertEquals(WakeLoop.Outcome.NotReady, WakeLoop.poll(ctx, http))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the read timeout outlasts the 55 second hold`() {
        assertTrue(WakeLoop.READ_TIMEOUT_S >= 90)
    }

    @Test
    fun `backoff grows from 1s, stays within 60s, and is jittered`() {
        var b = WakeLoop.BACKOFF_MIN_MS
        repeat(20) {
            b = WakeLoop.nextBackoff(b)
            assertTrue(b in WakeLoop.BACKOFF_MIN_MS..WakeLoop.BACKOFF_MAX_MS)
        }
        val a = WakeLoop.nextBackoff(8_000, Random(1))
        val c = WakeLoop.nextBackoff(8_000, Random(2))
        assertTrue(a in 8_000L..16_000L && c in 8_000L..16_000L)
        assertFalse(a == c && a == WakeLoop.nextBackoff(8_000, Random(3)))
    }

    @Test
    fun `only a notification the phone did not already hold is news, and passive never is`() {
        val held = NotificationQueue.fromWire(listOf(note("old")), 0L)
        assertEquals(setOf("new"), NotificationQueue.unheldIds(held, listOf(note("old"), note("new"))))
        assertFalse(WakeLoop.interrupts("passive"))
        assertTrue(WakeLoop.interrupts("active"))
        assertTrue(WakeLoop.interrupts(""))
    }
}
