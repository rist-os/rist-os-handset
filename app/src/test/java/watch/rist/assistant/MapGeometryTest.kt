package watch.rist.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

class MapGeometryTest {

    @Test
    fun tileDrawScale_isDerivedFromTheBitmap() {
        assertEquals(1f, MapGeometry.tileDrawScale(256), 0f)
        assertEquals(0.5f, MapGeometry.tileDrawScale(512), 0f)
        assertEquals(1f, MapGeometry.tileDrawScale(0), 0f)
    }

    @Test
    fun sdAndHdTiles_coverTheSameWorldRect() {
        val sd = FloatArray(4); val hd = FloatArray(4)
        for ((tx, ty) in listOf(10 to 20, 11 to 20, 10 to 21, 9 to 19)) {
            MapGeometry.tileWorldRect(tx, ty, 10.37, 20.81, 256, 256, sd)
            MapGeometry.tileWorldRect(tx, ty, 10.37, 20.81, 512, 512, hd)
            assertArrayEquals(sd, hd, 1e-3f)
            assertEquals(256f, sd[2] - sd[0], 1e-3f)
            assertEquals(256f, hd[3] - hd[1], 1e-3f)
        }
    }

    @Test
    fun mixedTileSizes_abutWithoutOverlap() {
        val a = FloatArray(4); val b = FloatArray(4)
        MapGeometry.tileWorldRect(10, 20, 10.5, 20.5, 256, 256, a)
        MapGeometry.tileWorldRect(11, 20, 10.5, 20.5, 512, 512, b)
        assertEquals(a[2], b[0], 1e-3f)
        MapGeometry.tileWorldRect(10, 21, 10.5, 20.5, 512, 512, b)
        assertEquals(a[3], b[1], 1e-3f)
    }

    @Test
    fun panMath_isInWorldTileUnits() {
        assertEquals(1.0, MapGeometry.panTiles(256f * 1.8f, 1.8f), 1e-9)
        assertEquals(-0.5, MapGeometry.panTiles(-128f, 1f), 1e-9)
    }

    @Test
    fun hdFrame_getsTheSameScreenRectAsSdFrame() {
        for ((vw, vh) in listOf(1080f to 2400f, 1080f to 1900f, 720f to 1280f, 1440f to 3120f)) {
            val sd = FloatArray(4); val hd = FloatArray(4)
            val sSd = MapGeometry.frameLayout(vw, vh, 240, 320, sd)
            val sHd = MapGeometry.frameLayout(vw, vh, 480, 640, hd)
            assertArrayEquals(sd, hd, 1e-3f)
            assertEquals(sSd, sHd)
        }
    }

    @Test
    fun georefSize_prefersProtoFieldsOverTheBitmap() {
        assertEquals(480, MapGeometry.georefSize(480, 240, 240))
        assertEquals(480, MapGeometry.georefSize(0, 480, 240))
        assertEquals(240, MapGeometry.georefSize(0, 0, 240))
    }

    @Test
    fun fixLandsOnTheSameScreenPoint_forSdAndHdOverview() {
        val minLat = 47.60; val minLon = -122.35; val maxLat = 47.70; val maxLon = -122.15
        val lat = 47.6412; val lon = -122.2533
        val sdOut = FloatArray(2); val hdOut = FloatArray(2)
        val sdDst = FloatArray(4); val hdDst = FloatArray(4)
        MapGeometry.frameLayout(1080f, 2400f, 240, 320, sdDst)
        MapGeometry.frameLayout(1080f, 2400f, 480, 640, hdDst)
        val sdPx = northUp(minLat, minLon, maxLat, maxLon, 240, 320, lat, lon)
        val hdPx = northUp(minLat, minLon, maxLat, maxLon, 480, 640, lat, lon)
        MapGeometry.frameToScreen(sdPx[0], sdPx[1], 240, 320, sdDst, sdOut)
        MapGeometry.frameToScreen(hdPx[0], hdPx[1], 480, 640, hdDst, hdOut)
        assertArrayEquals(sdOut, hdOut, 1e-2f)
    }

    // Mirrors rn_project's north-up branch.
    private fun northUp(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
                        w: Int, h: Int, lat: Double, lon: Double): DoubleArray {
        fun y(d: Double) = ln(tan(PI / 4.0 + Math.toRadians(d) / 2.0))
        return doubleArrayOf(
            (lon - minLon) / (maxLon - minLon) * w,
            (y(maxLat) - y(lat)) / (y(maxLat) - y(minLat)) * h)
    }
}
