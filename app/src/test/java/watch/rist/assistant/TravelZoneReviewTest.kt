package watch.rist.assistant

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Time zone after travel, and what the location switch going off must stop or hand back. */
@RunWith(RobolectricTestRunner::class)
class TravelZoneReviewTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var saved: AutoTimeZone.SystemZone

    private class FakeZone(var zone: String) : AutoTimeZone.SystemZone {
        val sets = mutableListOf<String>()
        var handBacks = 0
        override fun current() = zone
        override fun canSet(ctx: Context) = true
        override fun set(ctx: Context, zone: String): Boolean { sets += zone; this.zone = zone; return true }
        override fun handBack(ctx: Context) { handBacks++ }
    }

    @Before fun setUp() {
        Config.usePlainPrefsForTest(app)
        LocationSwitch.onResponse(app, present = true, value = false)
        saved = AutoTimeZone.system
        AutoTimeZone.resetTravelForTest()
    }

    @After fun tearDown() {
        AutoTimeZone.system = saved
        AutoTimeZone.resetTravelForTest()
        LocationSwitch.onResponse(app, present = true, value = false)
        Config.forgetPrefsForTest()
    }

    private val now = 1_760_000_000_000L // 2025-10-09: London BST (+1), Paris CEST (+2)

    private fun fixAt(lat: Double, lon: Double, timeMs: Long) = LocationProvider.Fix(lat, lon, 10f, timeMs, null, 0f)

    @Test fun `a fix from before the flight does not put back the zone the country just set`() {
        val fake = FakeZone("Europe/London")
        AutoTimeZone.system = fake
        Config.setAutoTimeZone(app, true)
        // Landed in Paris: the network country sets the zone at once.
        AutoTimeZone.setFromCountry(app, "fr", now)
        assertEquals("Europe/Paris", fake.zone)
        // The last fix the phone holds is Heathrow, 90 minutes ago (under the 2 h age limit).
        AutoTimeZone.consider(app, fixAt(51.47, -0.45, now - 90L * 60 * 1000), now)
        assertEquals("a pre-flight fix must not undo the landing", "Europe/Paris", fake.zone)
        // A fix taken after landing still decides.
        AutoTimeZone.consider(app, fixAt(49.01, 2.55, now + 60_000), now + 60_000)
        assertEquals("Europe/Paris", fake.zone)
        assertEquals(listOf("Europe/Paris"), fake.sets)
    }

    @Test fun `until a fix from after a travel sign is seen, checks may use GPS and want a newer fix`() {
        val t = now
        assertTrue(AutoTimeZone.travelUnsettled(t, t + 60L * 60 * 1000))
        assertFalse(AutoTimeZone.travelUnsettled(0L, t))
        assertFalse(AutoTimeZone.travelUnsettled(t, t + AutoTimeZone.TRAVEL_UNSETTLED_MAX_MS))
        assertTrue(AutoTimeZone.fixPredatesTravel(t - 90L * 60 * 1000, t))
        assertFalse("arrival fix, clock slack", AutoTimeZone.fixPredatesTravel(t - 30_000, t))
        assertFalse(AutoTimeZone.fixPredatesTravel(t - 90L * 60 * 1000, 0L))
        assertEquals(1800, AutoTimeZone.freshMaxAgeS(0L, t))
        assertEquals(360, AutoTimeZone.freshMaxAgeS(t - 5L * 60 * 1000, t))
    }

    private fun country(iso: String) = Intent(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED)
        .putExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY, iso)

    @Test fun `coming back into service in the same country is not a travel sign`() {
        assertTrue(AutoTimeZone.onTravelSign(app, country("us")))
        assertFalse("losing service says nothing", AutoTimeZone.onTravelSign(app, country("")))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11))
        assertFalse("the same country again after a tunnel is not travel",
            AutoTimeZone.onTravelSign(app, country("us")))
        assertTrue("a new country is", AutoTimeZone.onTravelSign(app, country("ca")))
    }

    @Test fun `location off hands the zone back to the phone instead of freezing it`() {
        val fake = FakeZone("America/Los_Angeles")
        AutoTimeZone.system = fake
        Config.setAutoTimeZone(app, true)
        LocationSwitch.onWake(app, present = true, value = true)
        assertEquals(1, fake.handBacks)
        // Repeats of off do not hand back again.
        LocationSwitch.onWake(app, present = true, value = true)
        assertEquals(1, fake.handBacks)
    }

    @Test fun `location off leaves a zone the owner set by hand alone`() {
        val fake = FakeZone("America/Los_Angeles")
        AutoTimeZone.system = fake
        Config.setAutoTimeZone(app, false)
        LocationSwitch.onWake(app, present = true, value = true)
        assertEquals(0, fake.handBacks)
    }

    @Test fun `location off is told to navigation at once`() {
        var told = 0
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { told++ } }
        LocalBroadcastManager.getInstance(app).registerReceiver(r, IntentFilter(LocationSwitch.ACTION_LOCATION_OFF))
        try {
            LocationSwitch.onWake(app, present = true, value = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, told)
        } finally {
            LocalBroadcastManager.getInstance(app).unregisterReceiver(r)
        }
    }
}
