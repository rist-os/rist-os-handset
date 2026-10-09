package watch.rist.assistant

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import rist.v1.LocationRequest
import rist.v1.NavCommand
import rist.v1.Speech

/**
 * An automatic off-corridor reroute (schema v31): it echoes the route being followed so the backend
 * routes to that destination instead of geocoding the label, and GPS wander never sets one off.
 */
@RunWith(RobolectricTestRunner::class)
class RerouteTest {

    private lateinit var server: MockWebServer
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val following: NavCommand = NavCommand.newBuilder()
        .setAction("navigate").setRouteId("rt-31511154")
        .setDestLat(47.6101).setDestLon(-122.1912)
        .setLabel("11115 Northeast 2nd Street").setMode("driving")
        .build()

    private val here = LocationProvider.Fix(47.6200, -122.2000, 8f, 0L, null, 12f, 90f)

    @Before
    fun start() {
        Config.setDeployDefaultsForTest("", "")
        server = MockWebServer().apply { start() }
        Config.setBackendEndpoint(app, server.url("/v1/device").toString())
        Config.setEnrolRevoked(app, false)
        Config.setCredentialRejected(app, false)
        Config.clearBillingLapse(app)
        StreamingCancel.resetForTest()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @After
    fun stop() {
        LocationProvider.debugFix = null
        Config.clearDeployDefaultsForTest()
        runCatching { server.shutdown() }
    }

    private fun reply(build: DeviceResponse.Builder.() -> Unit) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/x-protobuf")
        .setBody(Buffer().write(DeviceResponse.newBuilder().apply(build).build().toByteArray()))

    private fun routed() = reply {
        speech = Speech.newBuilder().setText("Rerouting.").build()
        nav = following.toBuilder().setRouteId("rt-2").build()
    }

    private fun sent(): DeviceRequest =
        DeviceRequest.parseFrom(server.takeRequest(30, TimeUnit.SECONDS)!!.body.readByteArray())

    private fun assertEchoes(req: DeviceRequest) {
        assertTrue(req.hasReroute())
        assertEquals("rt-31511154", req.reroute.routeId)
        assertEquals(47.6101, req.reroute.destLat, 0.0)
        assertEquals(-122.1912, req.reroute.destLon, 0.0)
        assertEquals("11115 Northeast 2nd Street", req.reroute.label)
        assertEquals("driving", req.reroute.mode)
    }

    // ── what goes on the wire ─────────────────────────────────────────────────────────────

    @Test
    fun `a reroute keeps its text and fix and carries the route it is following`() {
        server.enqueue(routed())

        val out = Uploader(app).sendNav("navigate to 11115 Northeast 2nd Street", "map", here, reroute = following)

        val req = sent()
        assertEquals("navigate to 11115 Northeast 2nd Street", req.text)
        assertEquals("map", req.targetToolId)
        assertEquals(47.62, req.location.lat, 1e-9)
        assertEquals(-122.2, req.location.lon, 1e-9)
        assertEchoes(req)
        assertEquals("rt-2", out!!.nav.routeId)
    }

    @Test
    fun `answering a location request sends the same reroute again with the new fix`() {
        LocationProvider.debugFix = LocationProvider.Fix(47.6210, -122.2010, 6f, 0L, null, null)
        server.enqueue(reply {
            locationRequest = LocationRequest.newBuilder().setMaxAgeS(30).setMinAccuracyM(50f).build()
        })
        server.enqueue(routed())

        Uploader(app).sendNav("navigate to 11115 Northeast 2nd Street", "map", here, reroute = following)

        assertEchoes(sent())
        val again = sent()
        assertEchoes(again)
        assertEquals(47.621, again.location.lat, 1e-9)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a navigation turn that is not a reroute carries no reroute`() {
        server.enqueue(routed())
        Uploader(app).sendNav("navigate to the airport", "map", here)
        val req = sent()
        assertFalse(req.hasReroute())
        assertEquals("navigate to the airport", req.text)
    }

    @Test
    fun `the echo is copied verbatim from the nav command`() {
        val r = Uploader.rerouteOf(following.toBuilder().setMode("walking").build())
        assertEquals("rt-31511154", r.routeId)
        assertEquals("walking", r.mode)
    }

    // ── when a reroute fires ──────────────────────────────────────────────────────────────

    private fun fix(lat: Double, lon: Double, acc: Float = 8f, speed: Float? = null, t: Long = NOW) =
        LocationProvider.Fix(lat, lon, acc, t, null, speed)

    @Test
    fun `indoors with a 66 m fix the phone never reroutes, however long it sits`() {
        val gate = RerouteGate()
        repeat(30) { i ->
            val jitter = (i % 3) * 0.0002
            assertFalse(gate.onFix(fix(47.62 + jitter, -122.2, acc = 66f), 80.0, 50.0, NOW))
        }
    }

    @Test
    fun `good fixes off the route while standing still do not reroute`() {
        val gate = RerouteGate()
        repeat(30) { i ->
            val jitter = (i % 2) * 0.00005   // ~5 m wander
            assertFalse(gate.onFix(fix(47.62 + jitter, -122.2, acc = 12f, speed = 0f), 70.0, 50.0, NOW))
        }
    }

    @Test
    fun `driving off the route reroutes after a run of good fixes`() {
        val gate = RerouteGate()
        val fired = (0 until RerouteGate.STREAK).map { i ->
            gate.onFix(fix(47.62 + i * 0.0002, -122.2, acc = 6f, speed = 12f), 120.0, 50.0, NOW)
        }
        assertEquals(List(RerouteGate.STREAK - 1) { false } + true, fired)
    }

    @Test
    fun `walking off the route without a speed reading still counts by distance covered`() {
        val gate = RerouteGate()
        val fired = (0 until RerouteGate.STREAK).map { i ->
            gate.onFix(fix(47.62 + i * 0.00015, -122.2, acc = 5f), 90.0, 50.0, NOW)
        }
        assertTrue(fired.last())
    }

    @Test
    fun `a fix back on the route starts the count again`() {
        val gate = RerouteGate()
        repeat(RerouteGate.STREAK - 1) { gate.onFix(fix(47.62, -122.2, speed = 12f), 120.0, 50.0, NOW) }
        assertFalse(gate.onFix(fix(47.62, -122.2, speed = 12f), 20.0, 50.0, NOW))
        repeat(RerouteGate.STREAK - 1) {
            assertFalse(gate.onFix(fix(47.62, -122.2, speed = 12f), 120.0, 50.0, NOW))
        }
        assertTrue(gate.onFix(fix(47.62, -122.2, speed = 12f), 120.0, 50.0, NOW))
    }

    @Test
    fun `a fix whose error is larger than its distance from the route, or a stale one, does not count`() {
        assertFalse(RerouteGate.trustworthy(fix(47.62, -122.2, acc = 20f), 18.0, NOW))
        assertFalse(RerouteGate.trustworthy(fix(47.62, -122.2, acc = 0f), 100.0, NOW))
        assertFalse(RerouteGate.trustworthy(fix(47.62, -122.2, t = NOW - 60_000), 100.0, NOW))
        assertTrue(RerouteGate.trustworthy(fix(47.62, -122.2, acc = 10f), 100.0, NOW))
    }

    @Test
    fun `distance outside the corridor box is zero inside it`() {
        assertEquals(0.0, MainActivity.metresOutside(47.5, -122.5, 47.0, -123.0, 48.0, -122.0), 0.0)
        val north = MainActivity.metresOutside(48.001, -122.5, 47.0, -123.0, 48.0, -122.0)
        assertEquals(111.3, north, 1.0)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
