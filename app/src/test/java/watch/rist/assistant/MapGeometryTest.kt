package watch.rist.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
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

    @Test
    fun frameDensity_floorsTheWidthRatio() {
        val want = mapOf(239 to 1, 240 to 1, 300 to 1, 479 to 1, 480 to 2, 481 to 2, 640 to 2)
        for ((w, d) in want) assertEquals("width $w", d, MapGeometry.frameDensity(w))
        val out = FloatArray(4)
        assertEquals(3, MapGeometry.frameLayout(1080f, 2400f, 300, 300, out))
        assertArrayEquals(floatArrayOf(90f, 750f, 990f, 1650f), out, 1e-3f)
    }

    @Test
    fun frameGeoSize_keepsWidthAndHeightApart() {
        val out = IntArray(2)
        MapGeometry.frameGeoSize(480, 640, 480, 640, out); assertArrayEquals(intArrayOf(480, 640), out)
        MapGeometry.frameGeoSize(360, 0, 480, 640, out); assertArrayEquals(intArrayOf(360, 640), out)
        MapGeometry.frameGeoSize(0, 0, 480, 640, out); assertArrayEquals(intArrayOf(480, 640), out)
        MapGeometry.frameGeoSize(0, 0, 0, 0, out); assertArrayEquals(intArrayOf(240, 320), out)
    }

    private fun apply(m: FloatArray, x: Float, y: Float) =
        floatArrayOf(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])

    @Test
    fun tileMatrix_drawsSdAndHdTilesOntoTheSameScreenQuad() {
        val sd = FloatArray(9); val hd = FloatArray(9)
        for (heading in listOf(0f, 37f, 90f, 181f)) for (scale in listOf(1.8f, 0.72f, 7.2f)) {
            MapGeometry.tileMatrixValues(11, 19, 10.37, 20.81, 256, 256, scale, heading, 540f, 1440f, sd)
            MapGeometry.tileMatrixValues(11, 19, 10.37, 20.81, 512, 512, scale, heading, 540f, 1440f, hd)
            for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                assertArrayEquals(apply(sd, 256f * u, 256f * v), apply(hd, 512f * u, 512f * v), 1e-2f)
            }
        }
    }

    @Test
    fun tileMatrix_matchesTheRouteProjection() {
        val m = FloatArray(9)
        val utx = 10.37; val uty = 20.81; val scale = 1.8f; val heading = 45f; val cx = 540f; val cy = 1440f
        MapGeometry.tileMatrixValues(11, 21, utx, uty, 512, 512, scale, heading, cx, cy, m)
        val a = Math.toRadians(-heading.toDouble())
        for ((u, v) in listOf(0 to 0, 512 to 0, 0 to 512, 512 to 512, 256 to 128)) {
            val wx = (11 + u / 512.0 - utx) * 256.0; val wy = (21 + v / 512.0 - uty) * 256.0
            val want = floatArrayOf((cx + (wx * cos(a) - wy * sin(a)) * scale).toFloat(),
                (cy + (wx * sin(a) + wy * cos(a)) * scale).toFloat())
            assertArrayEquals(want, apply(m, u.toFloat(), v.toFloat()), 1e-2f)
        }
    }

    private fun visibleTiles(utx: Double, uty: Double, w: Int, h: Int, scale: Float, heading: Float,
                             cx: Float, cy: Float): Set<Pair<Int, Int>> {
        val r = IntArray(4); val out = HashSet<Pair<Int, Int>>()
        MapGeometry.visibleTileRange(utx, uty, w, h, scale, heading, cx, cy, r)
        for (ty in r[1]..r[3]) for (tx in r[0]..r[2])
            if (MapGeometry.tileOnScreen(tx, ty, utx, uty, w, h, scale, heading, cx, cy)) out.add(tx to ty)
        return out
    }

    @Test
    fun visibleTiles_coverEveryScreenPixelAndBeatTheOldWindow() {
        val w = 1080; val h = 2400; val cx = w / 2f; val cy = h * 0.60f
        val utx = 2625.37; val uty = 5720.81
        val p = DoubleArray(2)
        for (heading in listOf(0f, 45f, 90f, 180f)) for (zoom in listOf(0.4f, 1f, 4f)) {
            val scale = 1.8f * zoom
            val sel = visibleTiles(utx, uty, w, h, scale, heading, cx, cy)
            val hit = HashSet<Pair<Int, Int>>()
            for (sy in (0..h step 6) + h) for (sx in (0..w step 6) + w) {
                MapGeometry.screenToTile(sx.toDouble(), sy.toDouble(), utx, uty, scale, heading, cx, cy, p)
                hit.add(floor(p[0]).toInt() to floor(p[1]).toInt())
            }
            val tag = "heading $heading zoom $zoom"
            assertTrue("$tag missing ${hit - sel}", sel.containsAll(hit))
            assertTrue("$tag selects ${sel.size} for ${hit.size} on screen", sel.size <= hit.size + 4)
            val radius = (hypot(w.toDouble(), h.toDouble()) / scale / 256.0).toInt() + 1
            val oldWindow = (2 * radius + 1) * (2 * radius + 1)
            assertTrue("$tag ${sel.size} vs $oldWindow", sel.size * 3 < oldWindow)
        }
    }

    @Test
    fun screenToTile_invertsTheTileMatrix() {
        val m = FloatArray(9); val p = DoubleArray(2)
        for (heading in listOf(0f, 45f, 90f, 180f, 313f)) {
            MapGeometry.tileMatrixValues(7, 9, 6.5, 8.25, 256, 256, 3.6f, heading, 540f, 1440f, m)
            val s = apply(m, 64f, 192f)
            MapGeometry.screenToTile(s[0].toDouble(), s[1].toDouble(), 6.5, 8.25, 3.6f, heading, 540f, 1440f, p)
            assertEquals(7.25, p[0], 1e-4); assertEquals(9.75, p[1], 1e-4)
        }
    }

    // Mirrors rn_project's north-up branch.
    private fun northUp(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
                        w: Int, h: Int, lat: Double, lon: Double): DoubleArray {
        fun y(d: Double) = ln(tan(PI / 4.0 + Math.toRadians(d) / 2.0))
        return doubleArrayOf(
            (lon - minLon) / (maxLon - minLon) * w,
            (y(maxLat) - y(lat)) / (y(maxLat) - y(minLat)) * h)
    }

    @Test
    fun zoomedFarOut_theDrawnTilesAlwaysFitTheCache() {
        val budgetBytes = MapGeometry.TILE_CACHE_BYTES.toLong()
        for ((w, h) in listOf(1080 to 2424, 1080 to 2400, 1440 to 3120, 720 to 1280)) for (baseZ in listOf(14, 16)) {
            val cx = w / 2f; val cy = h * 0.60f
            var zoom = 0.4f
            while (zoom <= 4.001f) {
                val base = 1.8f * zoom
                val z = MapGeometry.drawZoom(baseZ, intArrayOf(12, 14, 16), base, w, h)
                val scale = base * MapGeometry.drawZoomFactor(baseZ, z)
                val f = MapGeometry.drawZoomFactor(baseZ, z).toDouble()
                val utx = 2625.37 * 4 / f; val uty = 5720.81 * 4 / f
                for (heading in 0 until 360 step 5) {
                    val n = visibleTiles(utx, uty, w, h, scale, heading.toFloat(), cx, cy).size
                    assertTrue("${w}x$h z$baseZ zoom $zoom heading $heading draws z$z: $n tiles",
                        n.toLong() * MapGeometry.MAX_TILE_BYTES <= budgetBytes)
                }
                zoom += 0.05f
            }
        }
    }

    @Test
    fun drawZoom_keepsTheBaseZoomAtNormalScaleAndDropsOnlyWhenFarOut() {
        val avail = intArrayOf(12, 14, 16)
        for ((w, h) in listOf(1080 to 2424, 1080 to 2400, 1440 to 3120)) {
            assertEquals(14, MapGeometry.drawZoom(14, avail, 1.8f, w, h))
            assertEquals(16, MapGeometry.drawZoom(16, avail, 1.8f, w, h))
            assertEquals(12, MapGeometry.drawZoom(14, avail, 1.8f * 0.4f, w, h))
            assertEquals(14, MapGeometry.drawZoom(16, avail, 1.8f * 0.4f, w, h))
        }
        assertEquals(13, MapGeometry.drawZoom(14, intArrayOf(12, 13, 14), 1.8f * 0.4f, 1080, 2424))
        assertEquals(14, MapGeometry.drawZoom(14, intArrayOf(14, 16), 1.8f * 0.4f, 1080, 2424))
        assertEquals(14, MapGeometry.drawZoom(14, avail, 1.8f * 0.67f, 1080, 2424))
        assertEquals(12, MapGeometry.drawZoom(14, avail, 1.8f * 0.65f, 1080, 2424))
        assertEquals(4, MapGeometry.drawZoomFactor(14, 12))
        assertEquals(64, MapGeometry.tileBudget())
    }

    @Test
    fun maxTilesOnScreen_boundsTheBruteForceCount() {
        for (heading in 0 until 360 step 3) for (zoom in listOf(0.4f, 0.55f, 0.7f, 1f, 2.5f)) {
            val scale = 1.8f * zoom
            for (off in listOf(0.0, 0.13, 0.5, 0.91)) {
                val n = visibleTiles(2625.0 + off, 5720.0 + off * 0.7, 1080, 2424, scale, heading.toFloat(), 540f, 1454f).size
                assertTrue("heading $heading zoom $zoom: $n",
                    n <= MapGeometry.maxTilesOnScreen(1080, 2424, MapGeometry.TILE_WORLD_PX * scale))
            }
        }
    }
}
