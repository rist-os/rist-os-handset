package watch.rist.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TilePlanTest {

    private class Held(vararg tiles: Triple<Int, Int, Int>) : TileLookup {
        val set = tiles.toHashSet()
        override fun has(z: Int, x: Int, y: Int) = Triple(z, x, y) in set
        fun add(z: Int, x: Int, y: Int) = set.add(Triple(z, x, y))
        fun zooms() = set.map { it.first }.distinct().sorted().toIntArray()
    }

    // Seattle, and a point ~1.5 km east of it: same z14 neighbourhood, different z16 tiles.
    private val lat = 47.6062; private val lon = -122.3321
    private val farLat = 47.6062; private val farLon = -122.3121

    private fun t(z: Int, la: Double, lo: Double) = Triple(z, TilePlan.tileX(lo, z), TilePlan.tileY(la, z))

    @Test
    fun tileIndex_matchesTheStandardXyzFormula() {
        // z0 is one tile; z1 splits at the equator and the antimeridian.
        assertEquals(0, TilePlan.tileX(lon, 0)); assertEquals(0, TilePlan.tileY(lat, 0))
        assertEquals(0, TilePlan.tileX(-1.0, 1)); assertEquals(1, TilePlan.tileX(1.0, 1))
        assertEquals(0, TilePlan.tileY(1.0, 1)); assertEquals(1, TilePlan.tileY(-1.0, 1))
        // Seattle at z14, as any slippy-map calculator gives it.
        assertEquals(2624, TilePlan.tileX(lon, 14)); assertEquals(5721, TilePlan.tileY(lat, 14))
    }

    @Test
    fun pickZoom_awayFromATurnIsTheFinestBelow16() {
        val held = Held(t(12, lat, lon), t(14, lat, lon), t(16, lat, lon))
        assertEquals(14, TilePlan.pickZoom(held.zooms(), false, lat, lon, lat, lon, held))
        val onlyZ12 = Held(t(12, lat, lon))
        assertEquals(12, TilePlan.pickZoom(onlyZ12.zooms(), true, lat, lon, lat, lon, onlyZ12))
        assertEquals(14, TilePlan.pickZoom(intArrayOf(), true, lat, lon, lat, lon, Held()))
    }

    @Test
    fun pickZoom_nearATurnUses16OnlyWhenThatTurnsTilesCame() {
        val here = Held(t(12, lat, lon), t(14, lat, lon), t(16, lat, lon))
        assertEquals(16, TilePlan.pickZoom(here.zooms(), true, lat, lon, lat, lon, here))

        // z16 exists (at the first turn, far away) but not at this turn: the partial-set case.
        val elsewhere = Held(t(12, lat, lon), t(14, lat, lon), t(16, farLat, farLon))
        assertEquals(14, TilePlan.pickZoom(elsewhere.zooms(), true, lat, lon, lat, lon, elsewhere))

        // The turn's tile came but the one the person is standing in did not.
        val turnOnly = Held(t(14, lat, lon), t(16, farLat, farLon))
        assertEquals(14, TilePlan.pickZoom(turnOnly.zooms(), true, farLat, farLon, lat, lon, turnOnly))
    }

    @Test
    fun coveringZoom_fallsBackToTheFinestCoarserTile() {
        val held = Held(Triple(12, 100, 200), Triple(13, 201, 401))
        val av = held.zooms()
        // z14 slot (403, 803): parent z13 (201, 401) held.
        assertEquals(13, TilePlan.coveringZoom(14, 403, 803, av, held))
        // z14 slot (400, 800): z13 (200, 400) missing, z12 (100, 200) held.
        assertEquals(12, TilePlan.coveringZoom(14, 400, 800, av, held))
        // Outside the z12 tile altogether.
        assertEquals(-1, TilePlan.coveringZoom(14, 404, 800, av, held))
        // The slot itself wins when held.
        held.add(14, 400, 800)
        assertEquals(14, TilePlan.coveringZoom(14, 400, 800, held.zooms(), held))
    }

    @Test
    fun coveringZoom_neverUsesAFinerTile() {
        // Only z16 children of the z14 slot are held: a z14 view does not draw them shrunk.
        val held = Held(Triple(16, 1600, 3200))
        assertEquals(-1, TilePlan.coveringZoom(14, 400, 800, held.zooms(), held))
    }

    @Test
    fun plan_fullSetIsJustTheViewZoom() {
        val held = Held()
        val xs = IntArray(9); val ys = IntArray(9)
        for (i in 0 until 9) { xs[i] = 400 + i % 3; ys[i] = 800 + i / 3; held.add(14, xs[i], ys[i]) }
        held.add(12, 100, 200)
        val plan = TilePlan.plan(14, xs, ys, 9, held.zooms(), held)
        assertEquals(9, plan.size)
        assertTrue(plan.all { it.z == 14 && it.levelsUp == 0 })
    }

    @Test
    fun plan_fillsMissingSlotsWithOneSharedCoarseTileDrawnFirst() {
        // z14 arrived for the left column only; z12 (100, 200) covers the whole 3x3 view.
        val held = Held(Triple(12, 100, 200))
        val xs = IntArray(9); val ys = IntArray(9)
        for (i in 0 until 9) { xs[i] = 400 + i % 3; ys[i] = 800 + i / 3; if (i % 3 == 0) held.add(14, xs[i], ys[i]) }
        val plan = TilePlan.plan(14, xs, ys, 9, held.zooms(), held)
        assertEquals(TileRef(12, 100, 200, 2), plan.first())
        assertEquals(1, plan.count { it.z == 12 })
        assertEquals(3, plan.count { it.z == 14 && it.levelsUp == 0 })
        assertEquals(4, plan.size)
    }

    @Test
    fun plan_skipsSlotsNothingCovers() {
        val held = Held(Triple(14, 400, 800))
        val plan = TilePlan.plan(14, intArrayOf(400, 5000), intArrayOf(800, 5000), 2, held.zooms(), held)
        assertEquals(listOf(TileRef(14, 400, 800, 0)), plan)
    }

    // What a cold long route ships: z12 for the whole corridor, z14 for only the first part of it,
    // z16 at the first turn. Walking the route, no slot on it may be left blank, and every slot must
    // end up showing the finest tile that covers it (the last one drawn over it wins).
    @Test
    fun partialSet_leavesNoBlankSquareOnTheRoute() {
        val rnd = Random(7)
        val route = ArrayList<Pair<Int, Int>>()
        var x = 10480; var y = 22880
        repeat(200) { route.add(x to y); if (rnd.nextBoolean()) x++ else y++ }
        val held = Held()
        for ((rx, ry) in route) for (dx in -1..1) for (dy in -1..1) {
            held.add(12, (rx shr 2) + dx, (ry shr 2) + dy)
        }
        for ((rx, ry) in route.take(40)) for (dx in -1..1) for (dy in -1..1) held.add(14, rx + dx, ry + dy)
        for (dx in -1..1) for (dy in -1..1) held.add(16, route[5].first * 4 + dx, route[5].second * 4 + dy)
        val av = held.zooms()

        for ((i, p) in route.withIndex()) {
            val xs = IntArray(25); val ys = IntArray(25)
            for (k in 0 until 25) { xs[k] = p.first - 2 + k % 5; ys[k] = p.second - 2 + k / 5 }
            val plan = TilePlan.plan(14, xs, ys, 25, av, held)
            for (k in 0 until 25) {
                val last = plan.lastOrNull { covers(it, 14, xs[k], ys[k]) }
                val want = TilePlan.coveringZoom(14, xs[k], ys[k], av, held)
                if (xs[k] == p.first && ys[k] == p.second) assertTrue("route slot $i blank", last != null)
                assertEquals("slot ${xs[k]},${ys[k]} at step $i", want, last?.z ?: -1)
            }
            assertTrue(plan.zipWithNext().all { (a, b) -> a.z <= b.z })
        }
        // And past the z14 coverage the view is still a map, from z12.
        val late = route[150]
        assertEquals(12, TilePlan.coveringZoom(14, late.first, late.second, av, held))
    }

    private fun covers(r: TileRef, z: Int, x: Int, y: Int): Boolean {
        val d = z - r.z
        return d >= 0 && x shr d == r.x && y shr d == r.y
    }

    @Test
    fun useTiles_fallsBackToFramesOnlyWhenNothingCoversThePerson() {
        assertTrue(TilePlan.useTiles(true, true, false, true, true))
        assertFalse(TilePlan.useTiles(true, true, false, false, true))
        // Panning by hand keeps the tile map, so the drag is not taken away at the edge.
        assertTrue(TilePlan.useTiles(true, true, true, false, true))
        // No frame to fall back to: a partly blank tile map beats "no basemap".
        assertTrue(TilePlan.useTiles(true, true, false, false, false))
        assertFalse(TilePlan.useTiles(false, true, false, true, true))
        assertFalse(TilePlan.useTiles(true, false, false, true, true))
    }

    @Test
    fun coarseStandIn_landsExactlyOnTheSlotsItReplaces() {
        val utx = 402.37; val uty = 801.81; val scale = 1.8f; val cx = 540f; val cy = 1440f
        val coarse = FloatArray(9); val fine = FloatArray(9)
        for (heading in listOf(0f, 33f, 180f)) for (bmp in listOf(256, 512)) {
            // z12 (100, 200) in a z14 view covers z14 slots 400..403 x 800..803.
            MapGeometry.tileMatrixValues(100, 200, utx, uty, bmp, bmp, scale, heading, cx, cy, coarse, levelsUp = 2)
            MapGeometry.tileMatrixValues(400, 800, utx, uty, 256, 256, scale, heading, cx, cy, fine)
            assertArrayEquals(apply(fine, 0f, 0f), apply(coarse, 0f, 0f), 1e-2f)
            MapGeometry.tileMatrixValues(403, 803, utx, uty, 256, 256, scale, heading, cx, cy, fine)
            assertArrayEquals(apply(fine, 256f, 256f), apply(coarse, bmp.toFloat(), bmp.toFloat()), 1e-2f)
            MapGeometry.tileMatrixValues(401, 802, utx, uty, 256, 256, scale, heading, cx, cy, fine)
            assertArrayEquals(apply(fine, 0f, 0f), apply(coarse, bmp / 4f, bmp / 2f), 1e-2f)
        }
    }

    private fun apply(m: FloatArray, x: Float, y: Float) =
        floatArrayOf(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])
}
