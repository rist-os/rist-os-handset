package watch.rist.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The phone's offline latitude/longitude -> time zone lookup, read from the shipped asset.
 *
 * Accuracy against the full-resolution boundaries is checked when the file is generated
 * (tools/build_tz_index.py). What this holds is that the phone's reader agrees with the
 * generator's reader, point for point, so that check actually describes the phone.
 */
@RunWith(RobolectricTestRunner::class)
class TimeZoneIndexTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun zone(lat: Double, lon: Double) = TimeZoneIndex.zoneAt(app, lat, lon)

    @Test
    fun `agrees with the generator on every fixture point`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("tz/lookup_fixture.tsv")
        val rows = stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("the fixture should hold thousands of points, got ${rows.size}", rows.size >= 3000)
        val wrong = rows.mapNotNull { row ->
            val (lat, lon, expected) = row.split('\t')
            val got = zone(lat.toDouble(), lon.toDouble()) ?: "-"
            if (got != expected) "$lat,$lon expected $expected got $got" else null
        }
        assertTrue("${wrong.size} disagree, e.g. ${wrong.take(5)}", wrong.isEmpty())
    }

    @Test
    fun `mexico resolves city by city, not to one zone for the country`() {
        // The case that broke: Mexico has several zones, so the country alone cannot choose.
        assertEquals("America/Mexico_City", zone(19.4326, -99.1332))
        assertEquals("America/Cancun", zone(21.1619, -86.8515))
        assertEquals("America/Tijuana", zone(32.5149, -117.0382))
        assertEquals("America/Hermosillo", zone(29.0729, -110.9559))
        assertEquals("America/Chihuahua", zone(28.6320, -106.0691))
        assertEquals("America/Ciudad_Juarez", zone(31.6904, -106.4245))
        assertEquals("America/Mazatlan", zone(23.2494, -106.4111))
        assertEquals("America/Merida", zone(20.9674, -89.5926))
    }

    @Test
    fun `two resorts eight kilometres apart land in their own zones`() {
        assertEquals("America/Mexico_City", zone(20.6534, -105.2253))   // Puerto Vallarta, Jalisco
        assertEquals("America/Bahia_Banderas", zone(20.7000, -105.2980)) // Nuevo Vallarta, Nayarit
    }

    @Test
    fun `a border city on the other side keeps its own zone`() {
        assertEquals("America/Denver", zone(31.7619, -106.4850)) // El Paso, across from Ciudad Juarez
        assertEquals("America/Phoenix", zone(33.4484, -112.0740))
        assertEquals("America/Los_Angeles", zone(47.6062, -122.3321))
    }

    @Test
    fun `the far corners of the grid resolve`() {
        assertEquals("Australia/Sydney", zone(-33.8688, 151.2093))
        assertEquals("Europe/London", zone(51.5074, -0.1278))
        // Exactly on the grid's last edges, which floor into a column and row that do not exist.
        assertTrue(zone(90.0, 180.0) != null)
        assertTrue(zone(-90.0, -180.0) != null)
    }

    @Test
    fun `a point that is not on the planet has no zone`() {
        assertNull(zone(91.0, 0.0))
        assertNull(zone(0.0, 181.0))
        assertNull(zone(Double.NaN, 0.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a file that is not an index is refused rather than misread`() {
        TimeZoneIndex.parse("NOPE and then some bytes".toByteArray())
    }
}
