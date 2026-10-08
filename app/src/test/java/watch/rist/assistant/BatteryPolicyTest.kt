package watch.rist.assistant

import android.location.LocationManager.GPS_PROVIDER
import android.location.LocationManager.NETWORK_PROVIDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** What the phone does to stay off GPS, the radio and the CPU when nothing needs them. */
@RunWith(RobolectricTestRunner::class)
class BatteryPolicyTest {

    // ── which location source is asked, in what order ─────────────────────────────────────

    @Test fun `a precise request asks GPS first, the network after`() {
        assertEquals(listOf(GPS_PROVIDER, GPS_PROVIDER, NETWORK_PROVIDER),
            LocationProvider.providerPlan(50f, allowGps = true, networkAllowed = true))
    }

    @Test fun `a city-level request asks the network first`() {
        assertEquals(listOf(NETWORK_PROVIDER, GPS_PROVIDER, GPS_PROVIDER),
            LocationProvider.providerPlan(50_000f, allowGps = true, networkAllowed = true))
        assertEquals(listOf(NETWORK_PROVIDER, GPS_PROVIDER, GPS_PROVIDER),
            LocationProvider.providerPlan(0f, allowGps = true, networkAllowed = true))
    }

    @Test fun `without network location consent nothing changes for GPS`() {
        assertEquals(listOf(GPS_PROVIDER, GPS_PROVIDER),
            LocationProvider.providerPlan(50_000f, allowGps = true, networkAllowed = false))
    }

    @Test fun `allowGps false never turns GPS on`() {
        assertEquals(listOf(NETWORK_PROVIDER),
            LocationProvider.providerPlan(50f, allowGps = false, networkAllowed = true))
        assertEquals(emptyList<String>(),
            LocationProvider.providerPlan(20_000f, allowGps = false, networkAllowed = false))
    }

    // ── the time zone check wakes GPS rarely ──────────────────────────────────────────────

    @Test fun `the time zone check may use GPS once, then not again for hours`() {
        val gap = AutoTimeZone.GPS_MIN_GAP_MS
        assertTrue(AutoTimeZone.gpsAllowed(force = false, lastGpsAttemptMs = 0L, nowElapsedMs = 1_000L))
        assertFalse(AutoTimeZone.gpsAllowed(force = false, lastGpsAttemptMs = 1_000L, nowElapsedMs = 1_000L + 60 * 60 * 1000))
        assertFalse(AutoTimeZone.gpsAllowed(force = false, lastGpsAttemptMs = 1_000L, nowElapsedMs = 1_000L + gap - 1))
        assertTrue(AutoTimeZone.gpsAllowed(force = false, lastGpsAttemptMs = 1_000L, nowElapsedMs = 1_000L + gap))
    }

    @Test fun `boot and turning the setting on may always use GPS`() {
        assertTrue(AutoTimeZone.gpsAllowed(force = true, lastGpsAttemptMs = 1_000L, nowElapsedMs = 2_000L))
    }

    // ── the wake poll ─────────────────────────────────────────────────────────────────────

    @Test fun `the server's idle gap stands unless Battery Saver is on and the screen is off`() {
        val idle = 240_000L
        assertEquals(idle, WakeLoop.idleGapMs(idle, powerSave = false, interactive = false))
        assertEquals(idle, WakeLoop.idleGapMs(idle, powerSave = true, interactive = true))
        assertEquals(WakeLoop.SAVER_IDLE_GAP_MS, WakeLoop.idleGapMs(idle, powerSave = true, interactive = false))
    }

    @Test fun `Battery Saver never slows a draining burst, a deploy or a lost ack`() {
        for (gap in listOf(1_000L, 5_000L, 10_000L)) {
            assertEquals(gap, WakeLoop.idleGapMs(gap, powerSave = true, interactive = false))
        }
    }

    @Test fun `Battery Saver never shortens a longer gap the server asked for`() {
        val hour = 60L * 60 * 1000
        assertEquals(hour, WakeLoop.idleGapMs(hour, powerSave = true, interactive = false))
    }

    @Test fun `offline, a failed poll waits for the network instead of retrying every minute`() {
        assertEquals(WakeLoop.OFFLINE_RETRY_MS, WakeLoop.retryWaitMs(60_000L, hasNetwork = false))
        assertEquals(60_000L, WakeLoop.retryWaitMs(60_000L, hasNetwork = true))
        // Unknown (no network callback to end the wait): the normal backoff.
        assertEquals(2_000L, WakeLoop.retryWaitMs(2_000L, hasNetwork = null))
    }

    // ── navigation GPS ────────────────────────────────────────────────────────────────────

    private fun fix(lat: Double, lon: Double, acc: Float = 10f, speed: Float? = 0f) =
        LocationProvider.Fix(lat, lon, acc, 0L, null, speed)

    @Test fun `navigation GPS keeps running while the map is on screen, however long`() {
        assertFalse(MainActivity.navGpsShouldPause(mapShown = true, movedAtMs = 0L, nowMs = 10L * 60 * 60 * 1000))
    }

    @Test fun `navigation GPS pauses when the map is hidden and the phone has not moved`() {
        val t = MainActivity.NAV_IDLE_PAUSE_MS
        assertFalse(MainActivity.navGpsShouldPause(mapShown = false, movedAtMs = 1_000L, nowMs = 1_000L + t - 1))
        assertTrue(MainActivity.navGpsShouldPause(mapShown = false, movedAtMs = 1_000L, nowMs = 1_000L + t))
    }

    @Test fun `movement is distance beyond the fix's own error, or speed`() {
        val here = fix(47.6, -122.3)
        assertTrue(MainActivity.navMoved(null, here))
        // ~11 m: GPS wander, not movement.
        assertFalse(MainActivity.navMoved(here, fix(47.6001, -122.3)))
        // ~111 m with a 10 m fix: moved.
        assertTrue(MainActivity.navMoved(here, fix(47.601, -122.3)))
        // ~111 m but the fix is only good to 300 m: not proof of movement.
        assertFalse(MainActivity.navMoved(here, fix(47.601, -122.3, acc = 300f)))
        // Walking pace in place still counts.
        assertTrue(MainActivity.navMoved(here, fix(47.6, -122.3, speed = 2f)))
    }

    // ── the push socket ───────────────────────────────────────────────────────────────────

    @Test fun `a build with no push endpoint stops its push service instead of retrying forever`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // A checkout whose local.properties carries a push endpoint builds one in; nothing to test then.
        org.junit.Assume.assumeFalse(isPushUrlValid(Config.pushUrl(ctx)))
        val controller = Robolectric.buildService(PushService::class.java).create()
        controller.startCommand(0, 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun `push endpoints are recognised by scheme`() {
        assertTrue(isPushUrlValid("wss://example.invalid/push"))
        assertFalse(isPushUrlValid(""))
        assertFalse(isPushUrlValid("example.invalid/push"))
    }
}
