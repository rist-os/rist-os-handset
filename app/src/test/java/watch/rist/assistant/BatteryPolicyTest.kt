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

    // ── a travel sign forces a full time zone check at once ───────────────────────────────

    @Test fun `airplane mode turning off is a travel sign, turning on is not`() {
        val a = android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED
        assertEquals("airplane-off", AutoTimeZone.travelSign(a, airplaneOn = false, country = null))
        assertEquals(null, AutoTimeZone.travelSign(a, airplaneOn = true, country = null))
        assertEquals(null, AutoTimeZone.travelSign(a, airplaneOn = null, country = null))
    }

    @Test fun `a new network country is a travel sign, losing service is not`() {
        val c = android.telephony.TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED
        assertEquals("country:mx", AutoTimeZone.travelSign(c, airplaneOn = null, country = "MX"))
        assertEquals(null, AutoTimeZone.travelSign(c, airplaneOn = null, country = ""))
        assertEquals(null, AutoTimeZone.travelSign(c, airplaneOn = null, country = null))
        assertEquals(null, AutoTimeZone.travelSign("some.other.ACTION", airplaneOn = false, country = "mx"))
    }

    @Test fun `only a repeat of the same sign within ten minutes is held back`() {
        val gap = AutoTimeZone.TRAVEL_REPEAT_GAP_MS
        assertTrue(AutoTimeZone.travelSignDue(null, 5_000L))
        assertFalse(AutoTimeZone.travelSignDue(5_000L, 5_000L + gap - 1))
        assertTrue(AutoTimeZone.travelSignDue(5_000L, 5_000L + gap))
    }

    private fun travelIntents(): Triple<android.content.Intent, android.content.Intent, android.content.Intent> {
        val landed = android.content.Intent(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false)
        val takeoff = android.content.Intent(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", true)
        val mexico = android.content.Intent(android.telephony.TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
            .putExtra(android.telephony.TelephonyManager.EXTRA_NETWORK_COUNTRY, "mx")
        return Triple(landed, takeoff, mexico)
    }

    @Test fun `landing and then a new country each check at once, only a repeat waits`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Config.usePlainPrefsForTest(ctx)
        AutoTimeZone.resetTravelForTest()
        try {
            val (landed, takeoff, mexico) = travelIntents()
            assertFalse(AutoTimeZone.onTravelSign(ctx, takeoff))
            assertTrue("landing checks at once", AutoTimeZone.onTravelSign(ctx, landed))
            assertTrue("a new country right after landing checks too", AutoTimeZone.onTravelSign(ctx, mexico))
            assertFalse("the same country again is a repeat", AutoTimeZone.onTravelSign(ctx, mexico))
            assertFalse("landing again is a repeat", AutoTimeZone.onTravelSign(ctx, landed))
        } finally {
            AutoTimeZone.resetTravelForTest()
            Config.forgetPrefsForTest()
        }
    }

    @Test fun `a country with one time decides the zone, one with several does not`() {
        val now = 1_760_000_000_000L
        assertEquals("Europe/London", AutoTimeZone.zoneForCountry("gb", now))
        assertEquals("Asia/Tokyo", AutoTimeZone.zoneForCountry("JP", now))
        assertEquals(null, AutoTimeZone.zoneForCountry("mx", now))
        assertEquals(null, AutoTimeZone.zoneForCountry("us", now))
        assertEquals(null, AutoTimeZone.zoneForCountry("zz", now))
    }

    private class FakeZone(var zone: String) : AutoTimeZone.SystemZone {
        val sets = mutableListOf<String>()
        override fun current() = zone
        override fun canSet(ctx: android.content.Context) = true
        override fun set(ctx: android.content.Context, zone: String): Boolean { sets += zone; this.zone = zone; return true }
        override fun handBack(ctx: android.content.Context) {}
    }

    @Test fun `the network country sets the zone only when the clock shows another time`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Config.usePlainPrefsForTest(ctx)
        val saved = AutoTimeZone.system
        val fake = FakeZone("America/New_York")
        AutoTimeZone.system = fake
        try {
            Config.setAutoTimeZone(ctx, true)
            val now = 1_760_000_000_000L
            AutoTimeZone.setFromCountry(ctx, "jp", now)
            assertEquals(listOf("Asia/Tokyo"), fake.sets)
            AutoTimeZone.setFromCountry(ctx, "mx", now)
            assertEquals("several zones: left to the location check", listOf("Asia/Tokyo"), fake.sets)
            AutoTimeZone.setFromCountry(ctx, "jp", now)
            assertEquals("already right: nothing set", listOf("Asia/Tokyo"), fake.sets)
            Config.setAutoTimeZone(ctx, false)
            fake.zone = "America/New_York"
            AutoTimeZone.setFromCountry(ctx, "jp", now)
            assertEquals("the owner chose the phone's own setting", listOf("Asia/Tokyo"), fake.sets)
        } finally {
            AutoTimeZone.system = saved
            Config.forgetPrefsForTest()
        }
    }

    @Test fun `the wake service listens for landing and for a new network country`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        Config.usePlainPrefsForTest(ctx)
        val controller = Robolectric.buildService(WakeService::class.java).create()
        try {
            controller.startCommand(0, 1)
            val actions = shadowOf(ctx).registeredReceivers.flatMap { w -> w.intentFilter.actionsIterator().asSequence().toList() }
            assertTrue(actions.toString(), android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED in actions)
            assertTrue(actions.toString(), android.telephony.TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED in actions)
        } finally {
            controller.destroy()
            Config.forgetPrefsForTest()
        }
    }

    // ── the wake poll ─────────────────────────────────────────────────────────────────────

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
