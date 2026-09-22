package watch.rist.assistant

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TileCacheTest {

    private val hdTiles = 64
    private val hdTileBytes = 512 * 512 * 2

    private fun tile(px: Int) = Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565)

    @Test
    fun holdsExactlyTheBudgetOfHdTiles() {
        val c = TileCache()
        val tiles = (0 until hdTiles).map { tile(512) }
        assertEquals(hdTileBytes, tiles[0].allocationByteCount)
        tiles.forEachIndexed { i, b -> c.put(i.toLong(), b) }
        assertEquals(hdTiles * hdTileBytes, c.size())
        for (i in tiles.indices) assertTrue("tile $i", c.get(i.toLong()) === tiles[i])
    }

    @Test
    fun oneTileOverTheBudgetEvictsAndRecyclesTheOldest() {
        val c = TileCache()
        val tiles = (0..hdTiles).map { tile(512) }
        tiles.forEachIndexed { i, b -> c.put(i.toLong(), b) }
        assertNull(c.get(0L))
        assertTrue(tiles[0].isRecycled)
        assertFalse(tiles[1].isRecycled)
        assertEquals(hdTiles * hdTileBytes, c.size())
    }

    @Test
    fun sdTilesCostAQuarterOfHdTiles() {
        val c = TileCache()
        repeat(4 * hdTiles) { c.put(it.toLong(), tile(256)) }
        assertEquals(4 * hdTiles, c.snapshot().size)
        c.put(-1L, tile(512))
        assertEquals(4 * hdTiles - 3, c.snapshot().size)
    }
}
