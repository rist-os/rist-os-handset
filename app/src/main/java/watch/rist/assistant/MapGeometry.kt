package watch.rist.assistant

import kotlin.math.max
import kotlin.math.min

// Tiles and frames may arrive at 1x or 2x pixel density; everything here works in the 1x units.
internal object MapGeometry {

    const val TILE_WORLD_PX = 256.0

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

    // px/py are in the georeferenced image's pixels (geoW x geoH); returns true if on the frame.
    fun frameToScreen(px: Double, py: Double, geoW: Int, geoH: Int, dst: FloatArray, out: FloatArray): Boolean {
        val u = px / geoW
        val v = py / geoH
        out[0] = (dst[0] + u.coerceIn(0.0, 1.0) * (dst[2] - dst[0])).toFloat()
        out[1] = (dst[1] + v.coerceIn(0.0, 1.0) * (dst[3] - dst[1])).toFloat()
        return u in 0.0..1.0 && v in 0.0..1.0
    }
}
