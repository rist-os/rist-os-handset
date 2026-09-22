package watch.rist.assistant

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

internal fun interface TileLookup {
    fun has(z: Int, x: Int, y: Int): Boolean
}

// One tile to draw for a view at zoom zView: the tile (z, x, y), which covers 2^levelsUp view
// slots per side when it stands in for finer tiles that did not arrive.
internal data class TileRef(val z: Int, val x: Int, val y: Int, val levelsUp: Int)

// Which tiles the pannable map draws, given a tile set that may be partial.
//
// A cold long route ships whatever the renderer finished in its time budget: the start of the drive
// whole, z12 for as much of the route as time allowed, z14 and z16 thinning out towards the end.
// Drawing only the zoom we picked would leave blank squares under the route line wherever that zoom
// ran out, so every slot on screen falls back to the finest coarser tile we do hold, and when nothing
// at all covers where the person is, the view goes back to the server-drawn frames.
internal object TilePlan {

    const val TURN_ZOOM = 16

    fun tileX(lonDeg: Double, z: Int): Int = floor((lonDeg + 180.0) / 360.0 * (1 shl z)).toInt()

    fun tileY(latDeg: Double, z: Int): Int {
        val r = Math.toRadians(latDeg)
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt()
    }

    // The zoom the view is drawn at. Near a turn that means z16, but only when this turn's own z16
    // tiles came: a partial set has z16 at the first turn and possibly nowhere else, and switching to
    // z16 on the strength of some other turn's tiles would draw the approach from z14 scaled up.
    // Both the turn and where the person is now must have their z16 tile.
    fun pickZoom(avail: IntArray, nearTurn: Boolean,
                 turnLat: Double, turnLon: Double, posLat: Double, posLon: Double,
                 tiles: TileLookup): Int {
        if (avail.isEmpty()) return 14
        if (nearTurn && TURN_ZOOM in avail &&
            tiles.has(TURN_ZOOM, tileX(turnLon, TURN_ZOOM), tileY(turnLat, TURN_ZOOM)) &&
            tiles.has(TURN_ZOOM, tileX(posLon, TURN_ZOOM), tileY(posLat, TURN_ZOOM))) return TURN_ZOOM
        var best = -1
        for (z in avail) if (z < TURN_ZOOM && z > best) best = z
        return if (best >= 0) best else avail.max()
    }

    // The finest zoom at or below z that holds the tile containing slot (z, x, y), or -1 if none does.
    // Never finer than z: a z16 tile is a few streets wide, and drawing it shrunk into a z14 view
    // would put a small sharp patch in a blank square rather than fill it.
    fun coveringZoom(z: Int, x: Int, y: Int, avail: IntArray, tiles: TileLookup): Int {
        var best = -1
        for (az in avail) {
            if (az > z || az <= best) continue
            val d = z - az
            if (tiles.has(az, x shr d, y shr d)) best = az
        }
        return best
    }

    // Tiles to draw for the given on-screen slots at zoom z, coarsest first. Drawn in that order,
    // each finer tile lands over the coarse one standing in beneath it, so every pixel shows the
    // finest tile that covers it. A coarse tile shared by several missing slots appears once.
    fun plan(z: Int, slotsX: IntArray, slotsY: IntArray, n: Int, avail: IntArray, tiles: TileLookup): List<TileRef> {
        val out = ArrayList<TileRef>(n)
        val seen = HashSet<Long>()
        for (i in 0 until n) {
            val cz = coveringZoom(z, slotsX[i], slotsY[i], avail, tiles)
            if (cz < 0) continue
            val d = z - cz
            val ref = TileRef(cz, slotsX[i] shr d, slotsY[i] shr d, d)
            if (seen.add(key(ref.z, ref.x, ref.y))) out.add(ref)
        }
        out.sortBy { it.z }
        return out
    }

    // Tiles, or the frames? Tiles whenever something covers the slot the person is in, at any zoom
    // at or below the view's. When nothing does, the frames: the route has run past the tiles that
    // finished rendering, and a blank screen with a blue line on it is not a map. Two exceptions:
    // while the person is panning by hand, stay on tiles so the gesture is not yanked away when they
    // drag past the edge; and with no usable frame at all, a partly blank tile map beats none.
    fun useTiles(haveTiles: Boolean, hasFix: Boolean, manual: Boolean,
                 anchorCovered: Boolean, haveFrame: Boolean): Boolean =
        haveTiles && hasFix && (manual || anchorCovered || !haveFrame)

    private fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 44) or (x.toLong() shl 22) or y.toLong()
}
