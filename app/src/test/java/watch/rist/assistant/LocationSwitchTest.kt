package watch.rist.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import rist.v1.GeofenceEvent
import rist.v1.Location
import rist.v1.WakeSignal

/** The account's location switch (schema v24): off means nothing about where the phone is leaves it. */
@RunWith(RobolectricTestRunner::class)
class LocationSwitchTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun on() {
        Config.usePlainPrefsForTest(app)
        LocationSwitch.onResponse(app, present = true, value = false)
        Geofences.dropAll(app, "test")
        LocationProvider.debugFix = null
    }

    @After
    fun reset() {
        LocationSwitch.onResponse(app, present = true, value = false)
        Geofences.dropAll(app, "test")
        LocationProvider.debugFix = null
        Config.forgetPrefsForTest()
    }

    @Test
    fun `present sets the state and absent keeps it`() {
        assertTrue(LocationSwitch.next(current = false, present = true, value = true))
        assertFalse(LocationSwitch.next(current = true, present = true, value = false))
        assertTrue(LocationSwitch.next(current = true, present = false, value = false))
        assertFalse(LocationSwitch.next(current = false, present = false, value = true))
    }

    @Test
    fun `a phone never told shares location`() {
        app.getSharedPreferences("rist.location.switch", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(LocationSwitch.isOff(app))
    }

    @Test
    fun `a wake turns it off, an absent field keeps it off, a present false turns it on`() {
        LocationSwitch.onWake(app, present = true, value = true)
        assertTrue(LocationSwitch.isOff(app))
        // A cancelled turn's final, a refusal, an older backend: no word.
        LocationSwitch.onResponse(app, present = false, value = false)
        assertTrue(LocationSwitch.isOff(app))
        LocationSwitch.onResponse(app, present = true, value = false)
        assertFalse(LocationSwitch.isOff(app))
    }

    @Test
    fun `the wire tells present false from absent`() {
        val absent = DeviceResponse.parseFrom(DeviceResponse.newBuilder().build().toByteArray())
        assertFalse(absent.hasLocationOff())
        val on = DeviceResponse.parseFrom(DeviceResponse.newBuilder().setLocationOff(false).build().toByteArray())
        assertTrue(on.hasLocationOff())
        assertFalse(on.locationOff)
        val off = WakeSignal.parseFrom(WakeSignal.newBuilder().setLocationOff(true).build().toByteArray())
        assertTrue(off.hasLocationOff())
        assertTrue(off.locationOff)
    }

    @Test
    fun `while off no fix is read for the assistant`() {
        LocationProvider.debugFix = LocationProvider.Fix(47.6, -122.3, 10f, 0L, null, null)
        assertNotNull(LocationProvider.cached(app))
        LocationSwitch.onWake(app, present = true, value = true)
        assertNull(LocationProvider.cached(app))
        assertNull(LocationProvider.freshBlocking(app, maxAgeS = 60, minAccuracyM = 0f, timeoutMs = 1))
    }

    @Test
    fun `while off a request carries the time zone and nothing else about place`() {
        val req = DeviceRequest.newBuilder()
            .setLocation(Location.newBuilder().setLat(47.6).setLon(-122.3).setAccuracyM(8)
                .setTimestamp(1L).setAgeS(3).setLabel("home").setSpeedMps(1f).setBearingDeg(90f)
                .setTimezone("America/Los_Angeles"))
            .addGeofenceState("fence-1")
            .addGeofenceEvents(GeofenceEvent.newBuilder().setId("c1").setFenceId("fence-1")
                .setFix(Location.newBuilder().setLat(47.6).setLon(-122.3)))
            .build()
        val out = LocationSwitch.scrub(req, "Europe/Paris")
        assertEquals(Location.newBuilder().setTimezone("America/Los_Angeles").build(), out.location)
        assertEquals(0, out.geofenceEventsCount)
        assertEquals(listOf("fence-1"), out.geofenceStateList)
    }

    @Test
    fun `a request with no time zone gets the phone's`() {
        val out = LocationSwitch.scrub(DeviceRequest.getDefaultInstance(), "Europe/Paris")
        assertEquals("Europe/Paris", out.location.timezone)
        assertEquals(0.0, out.location.lat, 0.0)
    }

    @Test
    fun `turning off drops every place reminder`() {
        Geofences.arm(app, listOf(Geofences.arming("f1", 47.6, -122.3, 150, "enter", 60, 120, 0L, false, "home")))
        assertEquals(listOf("f1"), Geofences.heldIds(app))
        LocationSwitch.onResponse(app, present = true, value = true)
        assertTrue(Geofences.heldIds(app).isEmpty())
    }

    @Test
    fun `while off landing or a new network country changes nothing`() {
        val real = AutoTimeZone.system
        val sets = mutableListOf<String>()
        AutoTimeZone.system = object : AutoTimeZone.SystemZone {
            override fun current() = "America/New_York"
            override fun canSet(ctx: Context) = true
            override fun set(ctx: Context, zone: String): Boolean { sets += zone; return true }
            override fun handBack(ctx: Context) {}
            override fun networkInCharge(ctx: Context) = false
        }
        AutoTimeZone.resetTravelForTest()
        try {
            Config.setAutoTimeZone(app, true)
            LocationSwitch.onWake(app, present = true, value = true)
            val landed = android.content.Intent(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false)
            val japan = android.content.Intent(android.telephony.TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
                .putExtra(android.telephony.TelephonyManager.EXTRA_NETWORK_COUNTRY, "jp")
            assertFalse(AutoTimeZone.onTravelSign(app, landed))
            assertFalse(AutoTimeZone.onTravelSign(app, japan))
            AutoTimeZone.setFromCountry(app, "jp", 1_760_000_000_000L)
            assertTrue(sets.isEmpty())
            // On again: the same country sets the zone.
            LocationSwitch.onResponse(app, present = true, value = false)
            AutoTimeZone.setFromCountry(app, "jp", 1_760_000_000_000L)
            assertEquals(listOf("Asia/Tokyo"), sets)
        } finally {
            AutoTimeZone.system = real
            AutoTimeZone.resetTravelForTest()
            Config.setAutoTimeZonePending(app, null, 0L)
        }
    }

    @Test
    fun `while off the time zone is not taken from a fix`() {
        val real = AutoTimeZone.system
        val sets = mutableListOf<String>()
        AutoTimeZone.system = object : AutoTimeZone.SystemZone {
            override fun current() = "America/Los_Angeles"
            override fun canSet(ctx: Context) = true
            override fun set(ctx: Context, zone: String): Boolean { sets += zone; return true }
            override fun handBack(ctx: Context) {}
            override fun networkInCharge(ctx: Context) = false
        }
        try {
            Config.setAutoTimeZone(app, true)
            LocationSwitch.onWake(app, present = true, value = true)
            val now = System.currentTimeMillis()
            AutoTimeZone.consider(app, LocationProvider.Fix(19.4326, -99.1332, 30f, now - 60_000, null, null), now)
            assertTrue(sets.isEmpty())
        } finally {
            AutoTimeZone.system = real
            Config.setAutoTimeZonePending(app, null, 0L)
        }
    }
}
