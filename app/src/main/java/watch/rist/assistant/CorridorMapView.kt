package watch.rist.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.location.Location
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import rist.v1.Corridor
import rist.v1.MapTile
import rist.v1.NavFrame
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.sinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.min
import kotlin.math.tan

internal const val OSM_ATTRIBUTION = "\u00a9 OpenStreetMap contributors"

class CorridorMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private class Frame(
        val bytes: ByteArray,
        val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double,
        val centerLat: Double, val centerLon: Double, val bearingDeg: Double, val mPerPx: Double,
        val tilePx: Int,
        val tilePxH: Int,
        // role: 0 = overview, 1 = follow, 2 = maneuver
        val role: Int,
        val segmentIndex: Int,
        val turnIndex: Int,
    ) {
        private val pxW: Double get() = MapGeometry.georefSize(tilePx, 0, MapGeometry.BASE_FRAME_W).toDouble()
        private val pxH: Double get() = MapGeometry.georefSize(tilePxH, 0, MapGeometry.BASE_FRAME_H).toDouble()
        // mPerPx > 0 = track-up centre/scale georeference; 0 = north-up bounds.
        val valid: Boolean get() = bytes.isNotEmpty() &&
            (mPerPx > 0.0 || (maxLon > minLon && maxLat > minLat))
        fun contains(lat: Double, lon: Double): Boolean =
            if (mPerPx > 0.0) {
                val latRad = Math.toRadians(centerLat)
                val dx = (lon - centerLon) * 111320.0 * cos(latRad)
                val dy = (lat - centerLat) * 110574.0
                val b = Math.toRadians(bearingDeg)
                val rx = dx * cos(b) - dy * sin(b)
                val ry = dx * sin(b) + dy * cos(b)
                abs(rx) <= (pxW / 2.0) * mPerPx && abs(ry) <= (pxH / 2.0) * mPerPx
            } else {
                lon in minLon..maxLon && lat in minLat..maxLat
            }
    }

    private val frames = mutableListOf<Frame>()
    private var activeIdx = -1
    private var rnHandle: Long = 0L
    private var frameDesc = DoubleArray(0)
    private var frameDescN = 0
    private var tileMode = false
    private val tileBytes = HashMap<Long, ByteArray>()
    private var availZooms = intArrayOf()
    private val tileMatrix = Matrix()
    private val tileMatrixVals = FloatArray(9)
    private val tileRange = IntArray(4)
    private var slotX = IntArray(64)
    private var slotY = IntArray(64)
    private val tileLookup = TileLookup { z, x, y -> tileBytes.containsKey(tileKey(z, x, y)) }
    // What onDraw last drew, so touches go to the tile gestures only while tiles are on screen.
    private var drawingTiles = false
    private var routePts = DoubleArray(0)
    private var routeN = 0
    private val routePath = Path()
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0055FF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routeCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tileCache = TileCache()
    private var animStart = 0L
    private var fromLat = 0.0; private var fromLon = 0.0; private var fromHeading = 0f
    private var lastDispLat = 0.0; private var lastDispLon = 0.0; private var lastDispHeading = 0f
    private var manualMode = false
    private var manualCenterLat = 0.0; private var manualCenterLon = 0.0
    private var manualZoomMul = 1f
    private var lastTileTouchMs = 0L
    private var curTileZoom = 14
    private val tileScaleDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            enterManual(); manualZoomMul = (manualZoomMul * d.scaleFactor).coerceIn(0.4f, 4f); invalidate(); return true
        }
    })
    private val tileGesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            enterManual()
            val nn = (1 shl curTileZoom).toDouble()
            val sUsed = TILE_SCREEN_SCALE * manualZoomMul
            val ctx = (manualCenterLon + 180.0) / 360.0 * nn
            val latR = Math.toRadians(manualCenterLat)
            val cty = (1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * nn
            val nctx = ctx + MapGeometry.panTiles(dx, sUsed)
            val ncty = cty + MapGeometry.panTiles(dy, sUsed)
            manualCenterLon = nctx / nn * 360.0 - 180.0
            manualCenterLat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * ncty / nn))))
            invalidate(); return true
        }
    })

    private fun enterManual() {
        if (!manualMode) { manualCenterLat = lastDispLat; manualCenterLon = lastDispLon }
        manualMode = true; lastTileTouchMs = System.currentTimeMillis()
    }
    private var autoSelect = true
    private var lastTouchMs = 0L

    private var minLat = 0.0
    private var minLon = 0.0
    private var maxLat = 0.0
    private var maxLon = 0.0
    private var activeCenterLat = 0.0
    private var activeCenterLon = 0.0
    private var activeBearing = 0.0
    private var activeMPerPx = 1.0
    private var activeImgW = 0
    private var activeImgH = 0

    private val dstRect = RectF()
    private var frameScale = 1

    private var fixLat = 0.0
    private var fixLon = 0.0
    private var hasFix = false
    private var fixBearing: Float? = null

    private var targetLat = 0.0
    private var targetLon = 0.0
    private var hasTarget = false

    private var destLat = 0.0
    private var destLon = 0.0
    private var hasDest = false

    private var nextTurnIndex = -1
    private var nextTurnLat = 0.0
    private var nextTurnLon = 0.0
    private var hasNextTurn = false

    private var turnInstruction = ""
    private var turnStreet = ""
    private var turnType = -1
    private var showTurnCard = false
    private var statusTime = ""
    private var statusDist = ""
    private var statusEta = ""

    private val inkColor = ContextCompat.getColor(context, R.color.ink)
    private val paperColor = ContextCompat.getColor(context, R.color.paper)
    private val accentColor = ContextCompat.getColor(context, R.color.record_active)
    private val faintColor = ContextCompat.getColor(context, R.color.ink_faint)

    private val bgPaint = Paint().apply { color = paperColor; style = Paint.Style.FILL }
    private val tilePaint = Paint()
    private val hdTilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor; style = Paint.Style.FILL }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paperColor; style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor; style = Paint.Style.FILL }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.FILL }
    private val pinRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF00FF.toInt(); style = Paint.Style.FILL }
    private var deviceFacing = 0f
    private var hasFacing = false
    private val conePath = Path()
    private val coneRect = RectF()
    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x77CCCCCC; style = Paint.Style.FILL }
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0055AA.toInt(); style = Paint.Style.FILL }
    private val bubbleOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); typeface = android.graphics.Typeface.MONOSPACE
    }
    private val bubbleGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val glyphPath = Path()
    // assets/glyphs/00..10.png, indexed by turns[].type
    private val glyphs = arrayOfNulls<Bitmap>(11)
    // Glyphs are 1-bit masks; map luminance -> alpha.
    private val glyphTintPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 255f,
            1f, 0f, 0f, 0f, 0f
        ))
    }
    private val glyphRect = RectF()
    private var turnExitNumber = ""
    private var turnExitBranch = ""
    private var turnExitToward = ""
    private val bubbleDistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }
    private val statusBarPaint = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.FILL }
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); typeface = android.graphics.Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = faintColor; textSize = dp(11f); textAlign = Paint.Align.CENTER
    }
    private val arrowPath = Path()
    private val pinPath = Path()

    private val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    private val bmpCache = object : android.util.LruCache<Int, Bitmap>(BITMAP_CACHE) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue != newValue) runCatching { oldValue.recycle() }
        }
    }

    private fun bitmapFor(idx: Int): Bitmap? {
        val fr = frames.getOrNull(idx) ?: return null
        if (!fr.valid) return null
        bmpCache.get(idx)?.takeIf { !it.isRecycled }?.let { return it }
        val bmp = runCatching { BitmapFactory.decodeByteArray(fr.bytes, 0, fr.bytes.size, decodeOpts) }.getOrNull()
            ?: return null
        bmpCache.put(idx, bmp)
        return bmp
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy) && abs(vx) > dp(180f)) {
                cycleFrame(if (vx < 0f) 1 else -1)
                return true
            }
            return false
        }
    })

    init { isClickable = true }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun setCorridor(corridor: Corridor?) {
        recycleFrames()
        if (corridor != null && corridor.tilesCount > 0) frames.add(makeFrame(corridor, 0, -1, -1))
        resetSelection()
    }

    fun setFrames(overview: Corridor?, navFrames: List<NavFrame>) {
        recycleFrames()
        if (overview != null && overview.tilesCount > 0) frames.add(makeFrame(overview, 0, -1, -1))
        navFrames.filter { it.roleValue == 0 }
            .forEach { frames.add(makeFrame(it.strip, 0, it.segmentIndex, it.turnIndex)) }
        navFrames.filter { it.roleValue == 1 }.sortedBy { it.segmentIndex }
            .forEach { frames.add(makeFrame(it.strip, 1, it.segmentIndex, it.turnIndex)) }
        navFrames.filter { it.roleValue == 2 }.sortedBy { it.turnIndex }
            .forEach { frames.add(makeFrame(it.strip, 2, it.segmentIndex, it.turnIndex)) }
        resetSelection()
    }

    private fun makeFrame(strip: Corridor, role: Int, seg: Int, turn: Int): Frame {
        val bytes = if (strip.tilesCount > 0) strip.tilesList[0].image.toByteArray() else ByteArray(0)
        return Frame(bytes, strip.minLat, strip.minLon, strip.maxLat, strip.maxLon,
            strip.centerLat, strip.centerLon, strip.bearingDeg, strip.mPerPx, strip.tilePx, strip.tilePxH, role, seg, turn)
    }

    fun setRistnavHandle(h: Long) { rnHandle = h }

    // Flat {role,seg,turn,cLat,cLon,bearing,mPerPx,minLat,minLon,maxLat,maxLon,w,h} x N for rn_select_frame.
    private fun buildFrameDesc() {
        frameDescN = frames.size
        if (frameDesc.size < frameDescN * 13) frameDesc = DoubleArray(frameDescN * 13)
        for (i in frames.indices) {
            val fr = frames[i]; val o = i * 13
            frameDesc[o] = fr.role.toDouble()
            frameDesc[o + 1] = fr.segmentIndex.toDouble()
            frameDesc[o + 2] = fr.turnIndex.toDouble()
            frameDesc[o + 3] = fr.centerLat; frameDesc[o + 4] = fr.centerLon
            frameDesc[o + 5] = fr.bearingDeg; frameDesc[o + 6] = fr.mPerPx
            frameDesc[o + 7] = fr.minLat; frameDesc[o + 8] = fr.minLon
            frameDesc[o + 9] = fr.maxLat; frameDesc[o + 10] = fr.maxLon
            frameDesc[o + 11] = MapGeometry.georefSize(fr.tilePx, 0, MapGeometry.BASE_FRAME_W).toDouble()
            frameDesc[o + 12] = MapGeometry.georefSize(fr.tilePxH, 0, MapGeometry.BASE_FRAME_H).toDouble()
        }
    }

    private fun tileKey(z: Int, x: Int, y: Int): Long = (z.toLong() shl 44) or (x.toLong() shl 22) or y.toLong()

    fun setRoutePoints(pts: DoubleArray, n: Int) { routePts = pts; routeN = n; invalidate() }

    fun setTiles(list: List<MapTile>) {
        tileBytes.clear(); tileCache.evictAll()
        for (t in list) tileBytes[tileKey(t.z, t.x, t.y)] = t.image.toByteArray()
        availZooms = list.map { it.z }.distinct().sorted().toIntArray()
        tileMode = tileBytes.isNotEmpty()
        invalidate()
    }

    private fun decodedTile(z: Int, x: Int, y: Int): Bitmap? {
        val k = tileKey(z, x, y)
        tileCache.get(k)?.takeIf { !it.isRecycled }?.let { return it }
        val b = tileBytes[k] ?: return null
        val bmp = runCatching { BitmapFactory.decodeByteArray(b, 0, b.size, decodeOpts) }.getOrNull() ?: return null
        tileCache.put(k, bmp); return bmp
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        val d = (((b - a) % 360f) + 540f) % 360f - 180f
        return a + d * t
    }

    // Returns false, having drawn nothing, when no tile at any zoom covers where the person is;
    // onDraw then shows the frames instead.
    private fun drawTileMap(canvas: Canvas): Boolean {
        val t = ((System.currentTimeMillis() - animStart).toFloat() / ANIM_MS).coerceIn(0f, 1f)
        val useLat = fromLat + (fixLat - fromLat) * t
        val useLon = fromLon + (fixLon - fromLon) * t
        var heading = lerpAngle(fromHeading, fixBearing ?: 0f, t)
        lastDispLat = useLat; lastDispLon = useLon; lastDispHeading = heading
        val manual = manualMode && System.currentTimeMillis() - lastTileTouchMs < MANUAL_TIMEOUT_MS
        if (manualMode && !manual) manualMode = false
        val cenLat = if (manual) manualCenterLat else useLat
        val cenLon = if (manual) manualCenterLon else useLon
        if (manual) heading = 0f
        val near = !manual && hasNextTurn && distanceM(useLat, useLon, nextTurnLat, nextTurnLon) <= 300.0
        val baseZ = TilePlan.pickZoom(availZooms, near, nextTurnLat, nextTurnLon, useLat, useLon, tileLookup)
        val baseScale = TILE_SCREEN_SCALE * (if (manual) manualZoomMul else 1f)
        val z = MapGeometry.drawZoom(baseZ, availZooms, baseScale, width, height)
        val n = 1 shl z
        val latRad = Math.toRadians(cenLat)
        val utx = (cenLon + 180.0) / 360.0 * n
        val uty = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        val anchorCovered = TilePlan.coveringZoom(z, floor(utx).toInt(), floor(uty).toInt(), availZooms, tileLookup) >= 0
        if (!TilePlan.useTiles(true, true, manual, anchorCovered, frames.any { it.valid })) return false
        curTileZoom = baseZ
        dstRect.set(0f, 0f, width.toFloat(), height.toFloat()); frameScale = 2
        val cx = width / 2f; val cy = height * 0.60f
        val scale = baseScale * MapGeometry.drawZoomFactor(baseZ, z)
        val ang = Math.toRadians(-heading.toDouble()); val ca = cos(ang); val sa = sin(ang)
        MapGeometry.visibleTileRange(utx, uty, width, height, scale, heading, cx, cy, tileRange)
        var slots = 0
        for (ty in tileRange[1]..tileRange[3]) for (tx in tileRange[0]..tileRange[2]) {
            if (!MapGeometry.tileOnScreen(tx, ty, utx, uty, width, height, scale, heading, cx, cy)) continue
            if (slots == slotX.size) { slotX = slotX.copyOf(slots * 2); slotY = slotY.copyOf(slots * 2) }
            slotX[slots] = tx; slotY[slots] = ty; slots++
        }
        for (ref in TilePlan.plan(z, slotX, slotY, slots, availZooms, tileLookup)) {
            val bmp = decodedTile(ref.z, ref.x, ref.y) ?: continue
            MapGeometry.tileMatrixValues(ref.x, ref.y, utx, uty, bmp.width, bmp.height, scale, heading, cx, cy,
                tileMatrixVals, ref.levelsUp)
            tileMatrix.setValues(tileMatrixVals)
            // Unfiltered keeps a 256-px watch tile's hard pixel edges; a 2x tile being shrunk, or a
            // coarser tile blown up to stand in for a missing one, looks better smoothed.
            val smooth = ref.levelsUp > 0 || MapGeometry.tileDrawScale(bmp.width) < 1f
            canvas.drawBitmap(bmp, tileMatrix, if (smooth) hdTilePaint else tilePaint)
        }
        val tmp = FloatArray(2)
        fun proj(plat: Double, plon: Double) {
            val prad = Math.toRadians(plat)
            val ptx = (plon + 180.0) / 360.0 * n
            val pty = (1.0 - ln(tan(prad) + 1.0 / cos(prad)) / PI) / 2.0 * n
            val wx = (ptx - utx) * MapGeometry.TILE_WORLD_PX; val wy = (pty - uty) * MapGeometry.TILE_WORLD_PX
            tmp[0] = (cx + (wx * ca - wy * sa) * scale).toFloat()
            tmp[1] = (cy + (wx * sa + wy * ca) * scale).toFloat()
        }
        if (routeN > 1) {
            routePath.reset()
            for (i in 0 until routeN) { proj(routePts[i * 2], routePts[i * 2 + 1]); if (i == 0) routePath.moveTo(tmp[0], tmp[1]) else routePath.lineTo(tmp[0], tmp[1]) }
            routeCasingPaint.strokeWidth = 9f * frameScale; routePaint.strokeWidth = 6f * frameScale
            canvas.drawPath(routePath, routeCasingPaint); canvas.drawPath(routePath, routePaint)
        }
        if (hasDest) { proj(destLat, destLon); drawPin(canvas, tmp[0], tmp[1]) }
        val mx: Float; val my: Float
        if (manual) { proj(useLat, useLon); mx = tmp[0]; my = tmp[1] } else { mx = cx; my = cy }
        if (hasFacing) drawFacingCone(canvas, mx, my, (-PI / 2.0 + Math.toRadians((deviceFacing - heading).toDouble())).toFloat())
        drawHeadingMarker(canvas, mx, my, if (manual) (-PI / 2.0 + Math.toRadians((fixBearing ?: 0f).toDouble())).toFloat() else (-PI / 2.0).toFloat())
        if (t < 1f || manual) postInvalidateOnAnimation()
        return true
    }

    private fun recycleFrames() {
        bmpCache.evictAll()
        frames.clear()
        activeIdx = -1
    }

    private fun resetSelection() {
        buildFrameDesc()
        autoSelect = true
        activeIdx = if (frames.isEmpty()) -1 else pickAutoIdx()
        applyActiveBounds()
        invalidate()
    }

    private fun pickAutoIdx(): Int {
        if (frames.isEmpty()) return -1
        if (hasFix && hasNextTurn && nextTurnIndex >= 0) {
            val d = distanceM(fixLat, fixLon, nextTurnLat, nextTurnLon)
            if (d in 0.0..MANEUVER_SWITCH_M) {
                val i = frames.indexOfFirst { it.role == 2 && it.turnIndex == nextTurnIndex && it.valid }
                if (i >= 0) return i
            }
        }
        if (hasFix) {
            val i = frames.indexOfFirst { it.role == 1 && it.valid && it.contains(fixLat, fixLon) }
            if (i >= 0) return i
        }
        val firstFollow = frames.indexOfFirst { it.role == 1 && it.valid }
        if (firstFollow >= 0) return firstFollow
        val firstValid = frames.indexOfFirst { it.valid }
        return if (firstValid >= 0) firstValid else 0
    }

    private fun applyActiveBounds() {
        val fr = frames.getOrNull(activeIdx) ?: return
        minLat = fr.minLat; minLon = fr.minLon; maxLat = fr.maxLat; maxLon = fr.maxLon
        activeCenterLat = fr.centerLat; activeCenterLon = fr.centerLon
        activeBearing = fr.bearingDeg; activeMPerPx = if (fr.mPerPx > 0.0) fr.mPerPx else 1.0
    }

    private fun cycleFrame(dir: Int) {
        if (frames.size <= 1) return
        autoSelect = false
        lastTouchMs = System.currentTimeMillis()
        activeIdx = ((activeIdx + dir) % frames.size + frames.size) % frames.size
        applyActiveBounds()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tileMode && drawingTiles) { tileScaleDetector.onTouchEvent(e); tileGesture.onTouchEvent(e); return true }
        if (frames.size > 1 && gestureDetector.onTouchEvent(e)) return true
        return super.onTouchEvent(e)
    }

    fun setFix(lat: Double, lon: Double, bearingDeg: Float? = null) {
        fixLat = lat; fixLon = lon; hasFix = true
        if (bearingDeg != null) fixBearing = bearingDeg
        if (lastDispLat == 0.0 && lastDispLon == 0.0) { lastDispLat = lat; lastDispLon = lon; lastDispHeading = bearingDeg ?: 0f }
        fromLat = lastDispLat; fromLon = lastDispLon; fromHeading = lastDispHeading
        animStart = System.currentTimeMillis()
        if (!autoSelect && System.currentTimeMillis() - lastTouchMs > MANUAL_TIMEOUT_MS) autoSelect = true
        if (autoSelect && frames.isNotEmpty()) {
            val idx = if (rnHandle != 0L && frameDescN > 0)
                Ristnav.nSelectFrame(rnHandle, frameDesc, frameDescN, lat, lon).let { if (it in frames.indices) it else pickAutoIdx() }
            else pickAutoIdx()
            if (idx >= 0 && idx != activeIdx) { activeIdx = idx; applyActiveBounds() }
        }
        invalidate()
    }

    fun setTarget(lat: Double, lon: Double) {
        targetLat = lat; targetLon = lon; hasTarget = true
        invalidate()
    }

    fun setFacing(deg: Float) { deviceFacing = deg; hasFacing = true; invalidate() }

    fun clearTarget() {
        hasTarget = false
        invalidate()
    }

    fun setDestination(lat: Double, lon: Double) {
        destLat = lat; destLon = lon; hasDest = true
        invalidate()
    }

    fun setNextManeuver(turnIndex: Int, lat: Double, lon: Double) {
        nextTurnIndex = turnIndex; nextTurnLat = lat; nextTurnLon = lon; hasNextTurn = turnIndex >= 0
        if (autoSelect) {
            val idx = pickAutoIdx()
            if (idx != activeIdx) { activeIdx = idx; applyActiveBounds(); invalidate() }
        }
    }

    fun clearNextManeuver() { hasNextTurn = false; nextTurnIndex = -1 }

    fun setTurnCard(instruction: String, street: String, type: Int,
                    exitNumber: String = "", exitBranch: String = "", exitToward: String = "") {
        turnInstruction = instruction.trim(); turnStreet = street.trim(); turnType = type
        turnExitNumber = exitNumber.trim(); turnExitBranch = exitBranch.trim(); turnExitToward = exitToward.trim()
        showTurnCard = turnInstruction.isNotEmpty()
        invalidate()
    }

    fun setStatusBar(timeRemaining: String, distanceLeft: String, eta: String) {
        statusTime = timeRemaining; statusDist = distanceLeft; statusEta = eta
        invalidate()
    }

    fun clearTurnCard() { showTurnCard = false; statusTime = ""; statusDist = ""; statusEta = ""; invalidate() }

    private fun mercY(latDeg: Double): Double = ln(tan(Math.PI / 4.0 + Math.toRadians(latDeg) / 2.0))

    private val layoutBuf = FloatArray(4)
    private fun computeDstRect(bmp: Bitmap) {
        frameScale = MapGeometry.frameLayout(width.toFloat(), height.toFloat(), bmp.width, bmp.height, layoutBuf)
        dstRect.set(layoutBuf[0], layoutBuf[1], layoutBuf[2], layoutBuf[3])
    }

    private val projBuf = DoubleArray(2)
    private val geoBuf = IntArray(2)
    private fun projectToPixel(latDeg: Double, lonDeg: Double, out: FloatArray): Boolean {
        val w = if (activeImgW > 0) activeImgW else 1
        val h = if (activeImgH > 0) activeImgH else 1
        Ristnav.nProject(activeBearing, activeCenterLat, activeCenterLon, activeMPerPx,
            minLat, minLon, maxLat, maxLon, w, h, latDeg, lonDeg, projBuf)
        layoutBuf[0] = dstRect.left; layoutBuf[1] = dstRect.top; layoutBuf[2] = dstRect.right; layoutBuf[3] = dstRect.bottom
        return MapGeometry.frameToScreen(projBuf[0], projBuf[1], w, h, layoutBuf, out)
    }

    private fun distanceM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(la1, lo1, la2, lo2, out)
        return out[0].toDouble()
    }

    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3A.toInt()
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.RIGHT
    }
    // Set by drawStatusBar in the same frame; read and cleared by onDrawForeground.
    private var statusBarTop = -1f
    private val attributionBg = Paint().apply { color = 0xB3FFFFFF.toInt(); style = Paint.Style.FILL }

    // Every map this view shows is drawn from OpenStreetMap data, and the ODbL makes crediting it
    // a condition of showing it. Drawn last, over every kind of frame, so no path can leave it off.
    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        if (width <= 0 || height <= 0) return
        val pad = 3f * resources.displayMetrics.density
        val w = attributionPaint.measureText(OSM_ATTRIBUTION)
        // Above the ETA bar when one is up, so neither covers the other.
        val floor = statusBarTop.takeIf { it > 0f && it < height } ?: height.toFloat()
        statusBarTop = -1f
        val baseline = floor - pad - attributionPaint.descent()
        canvas.drawRect(width - w - 2 * pad, baseline + attributionPaint.ascent() - pad, width.toFloat(), floor, attributionBg)
        canvas.drawText(OSM_ATTRIBUTION, width - pad, baseline, attributionPaint)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawingTiles = tileMode && hasFix && drawTileMap(canvas)
        if (drawingTiles) {
            if (showTurnCard) drawTurnBubble(canvas)
            if (statusTime.isNotEmpty() || statusDist.isNotEmpty() || statusEta.isNotEmpty()) drawStatusBar(canvas)
            return
        }
        val cx = width / 2f
        val cy = height / 2f
        val fr = frames.getOrNull(activeIdx)
        val bmp = if (fr != null && fr.valid) bitmapFor(activeIdx) else null

        if (fr != null && bmp != null) {
            applyActiveBounds()
            computeDstRect(bmp)
            MapGeometry.frameGeoSize(fr.tilePx, fr.tilePxH, bmp.width, bmp.height, geoBuf)
            activeImgW = geoBuf[0]; activeImgH = geoBuf[1]
            canvas.drawBitmap(bmp, null, dstRect, if (MapGeometry.frameDensity(bmp.width) > 1) hdTilePaint else tilePaint)

            val p = FloatArray(2)
            if (hasDest && projectToPixel(destLat, destLon, p)) {
                drawPin(canvas, p[0], p[1])
            }
            if (hasFix) {
                projectToPixel(fixLat, fixLon, p)
                val ang = if (activeBearing != 0.0) (-Math.PI / 2.0).toFloat()
                    else ((-Math.PI / 2.0) + Math.toRadians((fixBearing ?: 0f).toDouble())).toFloat()
                drawHeadingMarker(canvas, p[0], p[1], ang)
            }
            if (frames.size > 1) drawFrameCaption(canvas, fr)
        } else {
            canvas.drawText(context.getString(R.string.nav_no_basemap), cx, dp(16f), hintPaint)
            if (hasFix) {
                canvas.drawCircle(cx, cy, dp(6f), dotPaint)
                canvas.drawCircle(cx, cy, dp(6f), dotRingPaint)
                if (hasTarget) {
                    val latRad = Math.toRadians(fixLat)
                    val dx = (targetLon - fixLon) * cos(latRad)
                    val dy = -(targetLat - fixLat)
                    if (dx != 0.0 || dy != 0.0) drawArrow(canvas, cx, cy, atan2(dy, dx).toFloat())
                }
            }
        }
        if (showTurnCard) drawTurnBubble(canvas)
        if (statusTime.isNotEmpty() || statusDist.isNotEmpty() || statusEta.isNotEmpty()) drawStatusBar(canvas)
    }

    private fun formatDistance(m: Double): String =
        if (m >= 1609.34) String.format("%.1f mi", m / 1609.34) else "${(m / 0.3048).toInt()} ft"

    private fun clip(text: String, maxW: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun glyphFor(type: Int): Bitmap? {
        if (type < 0 || type >= glyphs.size) return null
        glyphs[type]?.let { if (!it.isRecycled) return it }
        val b = runCatching {
            context.assets.open("glyphs/%02d.png".format(type)).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        glyphs[type] = b
        return b
    }

    private fun drawTurnGlyph(canvas: Canvas, cx: Float, cy: Float, sz: Float) {
        // turns[].type: 1/5 left, 3 slight-left, 2/6 right, 4 slight-right, 7 u-turn, else straight.
        val deg = when (turnType) { 1, 5 -> 180.0; 3 -> 215.0; 2, 6 -> 0.0; 4 -> -35.0; 7 -> 90.0; else -> -90.0 }
        val a = Math.toRadians(deg)
        val tipX = cx + sz * cos(a).toFloat(); val tipY = cy + sz * sin(a).toFloat()
        val bx = cx - sz * 0.6f * cos(a).toFloat(); val by = cy - sz * 0.6f * sin(a).toFloat()
        val perpX = -sin(a).toFloat() * sz * 0.7f; val perpY = cos(a).toFloat() * sz * 0.7f
        glyphPath.reset()
        glyphPath.moveTo(tipX, tipY)
        glyphPath.lineTo(bx + perpX, by + perpY)
        glyphPath.lineTo(bx - perpX, by - perpY)
        glyphPath.close()
        canvas.drawPath(glyphPath, bubbleGlyphPaint)
    }

    private fun drawTurnBubble(canvas: Canvas) {
        val s = frameScale.toFloat()
        val top = dstRect.top + 12f * s
        val left = dstRect.left + 10f * s; val right = dstRect.right - 10f * s
        val distTxt = if (hasFix && hasTarget) formatDistance(distanceM(fixLat, fixLon, targetLat, targetLon)) else ""
        bubbleTextPaint.textSize = 11f * s
        bubbleDistPaint.textSize = 22f * s
        val sub = listOf(turnExitNumber, turnExitBranch, turnExitToward).filter { it.isNotEmpty() }.joinToString(" / ").ifEmpty { turnStreet }
        val oneLine = if (sub.isNotEmpty()) "$turnInstruction · $sub" else turnInstruction
        var tx = left + 12f * s + 26f * s
        val distW = if (distTxt.isNotEmpty()) bubbleDistPaint.measureText(distTxt) + 12f * s else 0f
        val usable = right - (tx + distW) - 10f * s
        val twoLine = sub.isNotEmpty() && bubbleTextPaint.measureText(oneLine) > usable
        val h = (if (twoLine) 68f else 54f) * s
        val r = 10f * s
        canvas.drawRoundRect(left, top, right, top + h, r, r, bubblePaint)
        canvas.drawRoundRect(left, top, right, top + h, r, r, bubbleOutlinePaint)
        val g = glyphFor(turnType)
        if (g != null) {
            val gs = 20f * s; val gl = left + 6f * s; val gt = top + h / 2f - gs / 2f
            glyphRect.set(gl, gt, gl + gs, gt + gs)
            canvas.drawBitmap(g, null, glyphRect, glyphTintPaint)
        } else {
            drawTurnGlyph(canvas, left + 20f * s, top + h / 2f, 9f * s)
        }
        bubbleDistPaint.textAlign = Paint.Align.LEFT
        if (distTxt.isNotEmpty()) canvas.drawText(distTxt, tx, top + h / 2f + bubbleDistPaint.textSize * 0.35f, bubbleDistPaint)
        tx += distW
        bubbleTextPaint.textAlign = Paint.Align.LEFT
        val maxW = right - tx - 10f * s
        if (twoLine) {
            canvas.drawText(clip(turnInstruction, maxW, bubbleTextPaint), tx, top + h * 0.40f, bubbleTextPaint)
            canvas.drawText(clip(sub, maxW, bubbleTextPaint), tx, top + h * 0.74f, bubbleTextPaint)
        } else {
            canvas.drawText(clip(oneLine, maxW, bubbleTextPaint), tx, top + h / 2f + bubbleTextPaint.textSize * 0.35f, bubbleTextPaint)
        }
    }

    private fun drawStatusBar(canvas: Canvas) {
        val s = frameScale.toFloat()
        val barH = 40f * s
        val top = dstRect.bottom - barH
        statusBarTop = top
        canvas.drawRect(dstRect.left, top, dstRect.right, dstRect.bottom, statusBarPaint)
        statusTextPaint.textSize = 12f * s
        val txt = listOf(statusTime, statusDist, statusEta).filter { it.isNotEmpty() }.joinToString("  ·  ")
        canvas.drawText(txt, dstRect.centerX(), top + barH / 2f + statusTextPaint.textSize * 0.35f, statusTextPaint)
    }

    private fun drawFrameCaption(canvas: Canvas, fr: Frame) {
        val label = when (fr.role) {
            1 -> "FOLLOW"
            2 -> "NEXT TURN"
            else -> "OVERVIEW"
        }
        canvas.drawText(label, width / 2f, dp(15f), hintPaint)
        canvas.drawText("‹", dp(12f), height / 2f, hintPaint)
        canvas.drawText("›", width - dp(12f), height / 2f, hintPaint)
    }

    private fun drawFacingCone(canvas: Canvas, cx: Float, cy: Float, angle: Float) {
        val r = 46f * frameScale
        val half = Math.toRadians(28.0).toFloat()
        conePath.reset()
        conePath.moveTo(cx, cy)
        conePath.lineTo(cx + r * cos(angle - half), cy + r * sin(angle - half))
        coneRect.set(cx - r, cy - r, cx + r, cy + r)
        conePath.arcTo(coneRect, Math.toDegrees((angle - half).toDouble()).toFloat(), Math.toDegrees((2f * half).toDouble()).toFloat())
        conePath.close()
        canvas.drawPath(conePath, conePaint)
    }

    private fun drawHeadingMarker(canvas: Canvas, cx: Float, cy: Float, angle: Float) {
        val r = 6f * frameScale
        val backA = angle + Math.PI.toFloat()
        val leftA = angle + 2.4f
        val rightA = angle - 2.4f
        arrowPath.reset()
        arrowPath.moveTo(cx + r * cos(angle), cy + r * sin(angle))
        arrowPath.lineTo(cx + r * 0.95f * cos(leftA), cy + r * 0.95f * sin(leftA))
        arrowPath.lineTo(cx + r * 0.35f * cos(backA), cy + r * 0.35f * sin(backA))
        arrowPath.lineTo(cx + r * 0.95f * cos(rightA), cy + r * 0.95f * sin(rightA))
        arrowPath.close()
        canvas.drawPath(arrowPath, markerPaint)
        canvas.drawPath(arrowPath, markerRingPaint)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, angle: Float) {
        val reach = min(width, height) * 0.32f
        val half = dp(9f)
        val tipX = cx + reach * cos(angle)
        val tipY = cy + reach * kotlin.math.sin(angle)
        val baseCx = cx + (reach - dp(18f)) * cos(angle)
        val baseCy = cy + (reach - dp(18f)) * kotlin.math.sin(angle)
        val perpX = -kotlin.math.sin(angle) * half
        val perpY = cos(angle) * half
        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(baseCx + perpX, baseCy + perpY)
        arrowPath.lineTo(baseCx - perpX, baseCy - perpY)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }

    private fun drawPin(canvas: Canvas, cx: Float, cy: Float) {
        val r = 5f * frameScale
        pinPath.reset()
        pinPath.moveTo(cx, cy - r)
        pinPath.lineTo(cx + r, cy)
        pinPath.lineTo(cx, cy + r)
        pinPath.lineTo(cx - r, cy)
        pinPath.close()
        canvas.drawPath(pinPath, pinPaint)
        canvas.drawPath(pinPath, pinRingPaint)
    }

    fun release() {
        tileMode = false; tileBytes.clear(); tileCache.evictAll()
        recycleFrames()
        hasTarget = false
        hasDest = false
        hasNextTurn = false
        showTurnCard = false
        statusTime = ""; statusDist = ""; statusEta = ""
        invalidate()
    }

    companion object {
        // Screen px per tile world px, where a tile is 256 world px whatever its bitmap size. This
        // sets the map's ground scale (a z14 tile is ~460 px, about 2.3 across a 1080-px screen), not
        // its sharpness: a 512-px tile is pre-scaled by 256/width, so at 1.8 each of its pixels lands
        // on 0.9 of a screen pixel, all of its detail shown, where a watch tile is blown up 1.8x.
        // Left as it is until a real 2x render has been seen on the phone: raising it would only
        // show less road ahead, and nothing serves 2x tiles yet to judge line weights by.
        private const val TILE_SCREEN_SCALE = 1.8f
        private const val ANIM_MS = 1400L
        private const val FOLLOW_VIEW_M = 800.0
        private const val MANEUVER_SWITCH_M = 200.0
        private const val MANUAL_TIMEOUT_MS = 8000L
        private const val BITMAP_CACHE = 6
    }
}
