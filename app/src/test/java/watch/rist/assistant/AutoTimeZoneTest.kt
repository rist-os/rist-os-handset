package watch.rist.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bug: a phone that travelled to Mexico kept its home time zone. The OS only learns a zone
 * from the cell network's time signal, or from the country when the country has one zone.
 */
@RunWith(RobolectricTestRunner::class)
class AutoTimeZoneTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    // A fixed moment in September, when Los Angeles is on daylight time (UTC-7).
    private val now = ZonedDateTime.of(2026, 9, 12, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

    private class FakeZone(var zone: String, var owner: Boolean = true) : AutoTimeZone.SystemZone {
        val sets = mutableListOf<String>()
        var handedBack = 0
        override fun current() = zone
        override fun canSet(ctx: Context) = owner
        override fun set(ctx: Context, zone: String): Boolean { sets += zone; this.zone = zone; return true }
        override fun handBack(ctx: Context) { handedBack++ }
    }

    private lateinit var fake: FakeZone
    private lateinit var real: AutoTimeZone.SystemZone

    @Before
    fun useFake() {
        real = AutoTimeZone.system
        fake = FakeZone("America/Los_Angeles")
        AutoTimeZone.system = fake
        Config.setAutoTimeZone(app, true)
        Config.setAutoTimeZonePending(app, null, 0L)
    }

    @After
    fun restore() {
        AutoTimeZone.system = real
        Config.setAutoTimeZonePending(app, null, 0L)
    }

    private fun fixAt(lat: Double, lon: Double, ageMs: Long = 60_000, accuracyM: Float = 30f) =
        LocationProvider.Fix(lat, lon, accuracyM, now - ageMs, null, null)

    private val mexicoCity = fixAt(19.4326, -99.1332)

    @Test
    fun `arriving in Mexico City switches the zone at once`() {
        AutoTimeZone.consider(app, mexicoCity, now)
        assertEquals(listOf("America/Mexico_City"), fake.sets)
    }

    @Test
    fun `staying put changes nothing`() {
        fake.zone = "America/Mexico_City"
        AutoTimeZone.consider(app, mexicoCity, now)
        assertTrue(fake.sets.isEmpty())
    }

    @Test
    fun `a same-offset neighbour needs to be seen twice`() {
        // El Paso (America/Denver) and Ciudad Juarez share an offset: the clock reads the same
        // either way, so a border fix alone must not flip the zone.
        fake.zone = "America/Denver"
        val juarez = fixAt(31.6904, -106.4245)
        AutoTimeZone.consider(app, juarez, now)
        assertTrue("first sighting only notes it", fake.sets.isEmpty())
        assertEquals("America/Ciudad_Juarez", Config.autoTimeZonePending(app)?.first)

        AutoTimeZone.consider(app, juarez, now + 15 * 60_000)
        assertEquals(listOf("America/Ciudad_Juarez"), fake.sets)
        assertNull("and the note is cleared", Config.autoTimeZonePending(app))
    }

    @Test
    fun `a same-offset sighting too long ago does not count`() {
        fake.zone = "America/Denver"
        Config.setAutoTimeZonePending(app, "America/Ciudad_Juarez", now - AutoTimeZone.PENDING_TTL_MS - 1)
        AutoTimeZone.consider(app, fixAt(31.6904, -106.4245), now)
        assertTrue(fake.sets.isEmpty())
    }

    @Test
    fun `a fix from before the flight is ignored`() {
        AutoTimeZone.consider(app, fixAt(19.4326, -99.1332, ageMs = AutoTimeZone.MAX_FIX_AGE_MS + 1), now)
        assertTrue(fake.sets.isEmpty())
    }

    @Test
    fun `a fix too coarse to tell zones apart is ignored`() {
        AutoTimeZone.consider(app, fixAt(19.4326, -99.1332, accuracyM = AutoTimeZone.MAX_ACCURACY_M + 1), now)
        assertTrue(fake.sets.isEmpty())
    }

    @Test
    fun `a fix at sea leaves the zone alone`() {
        AutoTimeZone.consider(app, fixAt(15.0, -110.0), now) // Pacific, resolves to Etc/GMT+7
        assertTrue(fake.sets.isEmpty())
    }

    @Test
    fun `turned off, location is not used`() {
        Config.setAutoTimeZone(app, false)
        AutoTimeZone.consider(app, mexicoCity, now)
        assertTrue(fake.sets.isEmpty())
    }

    @Test
    fun `where Rist is not device owner, nothing is attempted`() {
        fake.owner = false
        AutoTimeZone.consider(app, mexicoCity, now)
        assertTrue(fake.sets.isEmpty())
    }

    @Test
    fun `turning it off hands the clock back to the phone`() {
        AutoTimeZone.setEnabled(app, false)
        assertEquals(1, fake.handedBack)
        assertTrue(!Config.isAutoTimeZone(app))
    }

    private fun zoneChecks() =
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms
            .filter { org.robolectric.Shadows.shadowOf(it.operation).savedIntent.action == AutoTimeZone.ACTION_CHECK }

    @Test
    fun `a restart schedules the hourly check once, however often it is asked`() {
        BootReceiver().onReceive(app, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        AutoTimeZone.schedule(app)
        AlarmReceiver().onReceive(app, android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED))
        val checks = zoneChecks()
        assertEquals(1, checks.size)
        assertEquals(android.app.AlarmManager.INTERVAL_HOUR, checks.single().interval)
    }

    @Test
    fun `only zones this phone knows, and no ocean zones, are ever set`() {
        assertTrue(AutoTimeZone.settable("America/Mexico_City"))
        assertTrue(!AutoTimeZone.settable("Etc/GMT+7"))
        assertTrue(!AutoTimeZone.settable("Mars/Olympus_Mons"))
    }

    @Test
    fun `the settings line names the place and its offset`() {
        assertEquals("Mexico City · GMT-06:00", AutoTimeZone.describeZone(TimeZone.getTimeZone("America/Mexico_City"), now))
        assertEquals("Kolkata · GMT+05:30", AutoTimeZone.describeZone(TimeZone.getTimeZone("Asia/Kolkata"), now))
    }
}
