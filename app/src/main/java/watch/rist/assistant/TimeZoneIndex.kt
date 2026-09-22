package watch.rist.assistant

import android.content.Context
import android.util.Log
import java.lang.ref.SoftReference
import java.nio.ByteBuffer
import kotlin.math.floor

/**
 * Which IANA time zone a latitude and longitude are in, answered on the phone with no network.
 *
 * Reads assets/tz/zones.bin, written by tools/build_tz_index.py from timezone-boundary-builder
 * (OpenStreetMap-derived, ODbL; see NOTICE). The format is documented in that script. Borders are
 * simplified to about half a kilometre, so a point within that distance of a zone border can
 * resolve to the neighbour; everywhere else the answer matches the source.
 *
 * The file is held behind a soft reference: a lookup happens a few times an hour at most, and
 * the 1.6 MB it occupies is better returned to the system between them.
 */
object TimeZoneIndex {

    private const val TAG = "RistTzIndex"
    private const val ASSET = "tz/zones.bin"

    @Volatile private var cached: SoftReference<Index>? = null

    /** The zone at [lat], [lon], or null when the file is unreadable or the point is off the map. */
    fun zoneAt(ctx: Context, lat: Double, lon: Double): String? =
        load(ctx)?.lookup(lat, lon)

    private fun load(ctx: Context): Index? {
        cached?.get()?.let { return it }
        synchronized(this) {
            cached?.get()?.let { return it }
            return runCatching {
                ctx.assets.open(ASSET).use { parse(it.readBytes()) }
            }.onFailure { Log.w(TAG, "time zone index unreadable", it) }
                .getOrNull()
                ?.also { cached = SoftReference(it) }
        }
    }

    internal fun parse(bytes: ByteArray): Index {
        val b = ByteBuffer.wrap(bytes) // big-endian by default, as the writer packs it
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "RTZ1") { "not a time zone index" }
        val scale = b.short.toInt() and 0xFFFF
        val cell = b.short.toInt() and 0xFFFF
        val zoneCount = b.short.toInt() and 0xFFFF
        val names = Array(zoneCount) {
            val len = b.get().toInt() and 0xFF
            val raw = ByteArray(len).also { b.get(it) }
            String(raw, Charsets.UTF_8)
        }
        val w = b.short.toInt() and 0xFFFF
        val h = b.short.toInt() and 0xFFFF
        val grid = IntArray(w * h) { b.int }
        val listCount = b.int
        val lists = Array(listCount) {
            val n = b.short.toInt() and 0xFFFF
            IntArray(n) { b.short.toInt() and 0xFFFF }
        }
        val offsets = IntArray(zoneCount) { b.int }
        val blobLen = b.int
        val blob = ByteArray(blobLen).also { b.get(it) }
        return Index(scale, cell, names, w, h, grid, lists, offsets, blob)
    }

    internal class Index(
        private val scale: Int,
        private val cell: Int,
        private val names: Array<String>,
        private val w: Int,
        private val h: Int,
        private val grid: IntArray,
        private val lists: Array<IntArray>,
        private val offsets: IntArray,
        private val blob: ByteArray,
    ) {
        /** Decoded rings per zone, as (xs, ys) pairs. A trip touches two or three zones at most. */
        private val rings = object : LinkedHashMap<Int, List<Pair<IntArray, IntArray>>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Pair<IntArray, IntArray>>>) =
                size > 8
        }

        fun lookup(lat: Double, lon: Double): String? {
            if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            val col = minOf(floor((lon + 180.0) / cell).toInt(), w - 1)
            val row = minOf(floor((lat + 90.0) / cell).toInt(), h - 1)
            val v = grid[row * w + col]
            if (v == -1) return null
            if (v >= 0) return names[v]
            val candidates = lists[-(v + 2)]
            val x = lon * scale
            val y = lat * scale
            for (z in candidates) if (contains(z, x, y)) return names[z]
            // Reachable only exactly on a quantised border, where the nearest zone is the answer.
            return names[candidates.minByOrNull { distance2(it, x, y) } ?: return null]
        }

        // Even-odd over every ring of the zone. The crossing is computed with the same operations
        // in the same order as the Python reader, so the two agree to the last bit.
        private fun contains(z: Int, x: Double, y: Double): Boolean {
            var inside = false
            for ((xs, ys) in ringsOf(z)) {
                var j = xs.size - 1
                for (i in xs.indices) {
                    val yi = ys[i].toDouble()
                    val yj = ys[j].toDouble()
                    if ((yi > y) != (yj > y)) {
                        val xi = xs[i].toDouble()
                        val xj = xs[j].toDouble()
                        val cross = xi + (y - yi) * (xj - xi) / (yj - yi)
                        if (x < cross) inside = !inside
                    }
                    j = i
                }
            }
            return inside
        }

        private fun distance2(z: Int, x: Double, y: Double): Double {
            var best = Double.POSITIVE_INFINITY
            for ((xs, ys) in ringsOf(z)) {
                var j = xs.size - 1
                for (i in xs.indices) {
                    val ax = xs[j].toDouble(); val ay = ys[j].toDouble()
                    val dx = xs[i] - ax; val dy = ys[i] - ay
                    val len = dx * dx + dy * dy
                    val t = if (len == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / len).coerceIn(0.0, 1.0)
                    val ex = ax + t * dx - x; val ey = ay + t * dy - y
                    best = minOf(best, ex * ex + ey * ey)
                    j = i
                }
            }
            return best
        }

        @Synchronized
        private fun ringsOf(z: Int): List<Pair<IntArray, IntArray>> = rings.getOrPut(z) {
            var p = offsets[z]
            fun varint(): Long {
                var v = 0L
                var shift = 0
                while (true) {
                    val c = blob[p++].toInt() and 0xFF
                    v = v or ((c and 0x7F).toLong() shl shift)
                    shift += 7
                    if (c < 0x80) return v
                }
            }
            fun zigzag(v: Long): Int = ((v ushr 1) xor -(v and 1)).toInt()
            val ringCount = varint().toInt()
            List(ringCount) {
                val n = varint().toInt()
                val xs = IntArray(n)
                val ys = IntArray(n)
                var x = 0
                var y = 0
                for (k in 0 until n) {
                    x += zigzag(varint()); y += zigzag(varint())
                    xs[k] = x; ys[k] = y
                }
                xs to ys
            }
        }
    }
}
