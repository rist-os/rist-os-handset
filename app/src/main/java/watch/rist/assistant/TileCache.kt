package watch.rist.assistant

import android.graphics.Bitmap
import android.util.LruCache

// Sized in bytes so 256-px and 512-px tiles share one budget.
internal class TileCache(maxBytes: Int = MapGeometry.TILE_CACHE_BYTES) : LruCache<Long, Bitmap>(maxBytes) {
    override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
        if (oldValue != newValue) runCatching { oldValue.recycle() }
    }
}
