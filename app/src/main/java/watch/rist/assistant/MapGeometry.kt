package watch.rist.assistant

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Tiles and frames may arrive at 1x or 2x pixel density; everything here works in the 1x units.
internal object MapGeometry {

    const val TILE_WORLD_PX = 256.0

    const val TILE_CACHE_BYTES = 32 * 1024 * 1024

    // A decoded 512-px RGB_565 tile, the largest the view holds.
    const val MAX_TILE_BYTES = 512 * 512 * 2

    const val BASE_FRAME_W = 240
    const val BASE_FRAME_H = 320

    fun tileDrawScale(bmpWidth: Int): Float = if (bmpWidth > 0) (TILE_WORLD_PX / bmpWidth).toFloat() else 1f

    // out = {left, top, right, bottom} in world px relative to the view centre tile coordinate (utx, uty).
    fun tileWorldRect(tx: Int, ty: Int, utx: Double, uty: Double, bmpW: Int, bmpH: Int, out: FloatArray) {
        val k = tileDrawScale(bmpW)
        val l = ((tx - utx) * TILE_WORLD_PX).toFloat()
        val t = ((ty - uty) * TILE_WORLD_PX).toFloat()
        out[0] = l; out[1] = t; out[2] = l + bmpW * k; out[3] = t + bmpH * k
    }

    // Screen = (cx, cy) + rotate(-headingDeg) * scale * world; out is an android.graphics.Matrix value array.
    // levelsUp > 0 places a coarser tile (zoom z - levelsUp) in the zoom-z view that utx/uty are in,
    // covering 2^levelsUp slots per side: it stands in for finer tiles that did not arrive.
    fun tileMatrixValues(tx: Int, ty: Int, utx: Double, uty: Double, bmpW: Int, bmpH: Int,
                         scale: Float, headingDeg: Float, cx: Float, cy: Float, out: FloatArray,
                         levelsUp: Int = 0) {
        val m = (1 shl levelsUp).toDouble()
        val k = tileDrawScale(bmpW) * m.toFloat()
        val l = ((tx * m - utx) * TILE_WORLD_PX).toFloat(); val t = ((ty * m - uty) * TILE_WORLD_PX).toFloat()
        val a = Math.toRadians(-headingDeg.toDouble()); val ca = cos(a); val sa = sin(a)
        out[0] = (scale * k * ca).toFloat(); out[1] = (-scale * k * sa).toFloat()
        out[2] = (cx + scale * (ca * l - sa * t)).toFloat()
        out[3] = (scale * k * sa).toFloat(); out[4] = (scale * k * ca).toFloat()
        out[5] = (cy + scale * (sa * l + ca * t)).toFloat()
        out[6] = 0f; out[7] = 0f; out[8] = 1f
    }

    // Inverse of the draw transform: screen point -> fractional tile coordinate.
    fun screenToTile(sx: Double, sy: Double, utx: Double, uty: Double, scale: Float, headingDeg: Float,
                     cx: Float, cy: Float, out: DoubleArray) {
        val a = Math.toRadians(-headingDeg.toDouble()); val ca = cos(a); val sa = sin(a)
        val dx = (sx - cx) / scale; val dy = (sy - cy) / scale
        out[0] = utx + (ca * dx + sa * dy) / TILE_WORLD_PX
        out[1] = uty + (-sa * dx + ca * dy) / TILE_WORLD_PX
    }

    // out = {minTx, minTy, maxTx, maxTy}: the tile-aligned bounding box of the screen in tile space.
    fun visibleTileRange(utx: Double, uty: Double, viewW: Int, viewH: Int, scale: Float, headingDeg: Float,
                         cx: Float, cy: Float, out: IntArray) {
        val a = Math.toRadians(-headingDeg.toDouble()); val ca = cos(a); val sa = sin(a)
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0..3) {
            val dx = ((if (i and 1 == 0) 0 else viewW) - cx).toDouble() / scale
            val dy = ((if (i < 2) 0 else viewH) - cy).toDouble() / scale
            val px = utx + (ca * dx + sa * dy) / TILE_WORLD_PX; val py = uty + (-sa * dx + ca * dy) / TILE_WORLD_PX
            x0 = min(x0, px); y0 = min(y0, py); x1 = max(x1, px); y1 = max(y1, py)
        }
        out[0] = floor(x0).toInt(); out[1] = floor(y0).toInt(); out[2] = floor(x1).toInt(); out[3] = floor(y1).toInt()
    }

    // With visibleTileRange this is the full separating-axis test between the tile and the screen.
    fun tileOnScreen(tx: Int, ty: Int, utx: Double, uty: Double, viewW: Int, viewH: Int, scale: Float,
                     headingDeg: Float, cx: Float, cy: Float): Boolean {
        val a = Math.toRadians(-headingDeg.toDouble()); val ca = cos(a); val sa = sin(a)
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0..3) {
            val wx = (tx + (i and 1) - utx) * TILE_WORLD_PX; val wy = (ty + (i shr 1) - uty) * TILE_WORLD_PX
            val sx = cx + scale * (ca * wx - sa * wy); val sy = cy + scale * (sa * wx + ca * wy)
            x0 = min(x0, sx); y0 = min(y0, sy); x1 = max(x1, sx); y1 = max(y1, sy)
        }
        return x1 >= 0.0 && y1 >= 0.0 && x0 <= viewW && y0 <= viewH
    }

    // Cells a viewW x viewH screen can touch at any heading and offset, tilePx screen px per tile: they all
    // lie in the screen grown by one tile each way, area w*h + 2(w+h)(|cos|+|sin|) + 4 in tile units.
    fun maxTilesOnScreen(viewW: Int, viewH: Int, tilePx: Double): Int {
        val w = viewW / tilePx; val h = viewH / tilePx
        return floor(w * h + 2.0 * sqrt(2.0) * (w + h) + 4.0).toInt()
    }

    fun tileBudget(cacheBytes: Int = TILE_CACHE_BYTES, tileBytes: Int = MAX_TILE_BYTES): Int = cacheBytes / tileBytes

    // Keeps a far pinch-out from decoding every tile every frame: once baseZ's tiles could outnumber the
    // cache, draw the highest lower zoom that fits. Draw at scale * drawZoomFactor(baseZ, z).
    fun drawZoom(baseZ: Int, availZooms: IntArray, scale: Float, viewW: Int, viewH: Int,
                 budget: Int = tileBudget()): Int {
        fun fits(z: Int) = maxTilesOnScreen(viewW, viewH, TILE_WORLD_PX * scale * drawZoomFactor(baseZ, z)) <= budget
        if (fits(baseZ)) return baseZ
        var z = baseZ
        for (c in availZooms.sortedDescending()) if (c < baseZ) { z = c; if (fits(c)) break }
        return z
    }

    fun drawZoomFactor(baseZ: Int, z: Int): Int = 1 shl (baseZ - z)

    fun panTiles(deltaScreenPx: Float, screenScale: Float): Double = deltaScreenPx / screenScale / TILE_WORLD_PX

    fun frameDensity(bmpW: Int): Int = max(1, bmpW / BASE_FRAME_W)

    // out = {left, top, right, bottom}; returns the integer upscale applied to the 1x frame.
    fun frameLayout(viewW: Float, viewH: Float, bmpW: Int, bmpH: Int, out: FloatArray): Int {
        if (bmpW <= 0 || bmpH <= 0 || viewW <= 0f || viewH <= 0f) {
            out[0] = 0f; out[1] = 0f; out[2] = viewW; out[3] = viewH
            return 1
        }
        val d = frameDensity(bmpW)
        val lw = bmpW.toFloat() / d
        val lh = bmpH.toFloat() / d
        val s = max(1, min((viewW / lw).toInt(), (viewH / lh).toInt()))
        val dw = lw * s; val dh = lh * s
        val left = (viewW - dw) / 2f; val top = (viewH - dh) / 2f
        out[0] = left; out[1] = top; out[2] = left + dw; out[3] = top + dh
        return s
    }

    fun georefSize(protoPx: Int, bmpPx: Int, fallback: Int): Int =
        if (protoPx > 0) protoPx else if (bmpPx > 0) bmpPx else fallback

    // out = {width, height} of the georeferenced frame; tilePx is the width, tilePxH the height.
    fun frameGeoSize(tilePx: Int, tilePxH: Int, bmpW: Int, bmpH: Int, out: IntArray) {
        out[0] = georefSize(tilePx, bmpW, BASE_FRAME_W)
        out[1] = georefSize(tilePxH, bmpH, BASE_FRAME_H)
    }

    // px/py are in the georeferenced image's pixels (geoW x geoH); returns true if on the frame.
    fun frameToScreen(px: Double, py: Double, geoW: Int, geoH: Int, dst: FloatArray, out: FloatArray): Boolean {
        val u = px / geoW
        val v = py / geoH
        out[0] = (dst[0] + u.coerceIn(0.0, 1.0) * (dst[2] - dst[0])).toFloat()
        out[1] = (dst[1] + v.coerceIn(0.0, 1.0) * (dst[3] - dst[1])).toFloat()
        return u in 0.0..1.0 && v in 0.0..1.0
    }
}
