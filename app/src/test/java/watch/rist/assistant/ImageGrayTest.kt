package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGrayTest {

    @Test
    fun luminance_grayInputIsUnchanged() {
        for (v in intArrayOf(0, 1, 64, 128, 200, 255)) {
            assertEquals(v, ImageGray.luminance(v, v, v))
        }
    }

    @Test
    fun luminance_pureChannelsUseRec601Weights() {
        assertEquals(76, ImageGray.luminance(255, 0, 0))   // 0.299 * 255
        assertEquals(149, ImageGray.luminance(0, 255, 0))  // 0.587 * 255
        assertEquals(29, ImageGray.luminance(0, 0, 255))   // 0.114 * 255
    }

    @Test
    fun luminance_extremesAndClampingStayInRange() {
        assertEquals(0, ImageGray.luminance(0, 0, 0))
        assertEquals(255, ImageGray.luminance(255, 255, 255))
        assertEquals(255, ImageGray.luminance(300, 300, 300))
        assertEquals(0, ImageGray.luminance(-10, -10, -10))
    }

    @Test
    fun quantize_disabledForOneOrFewerLevels() {
        assertEquals(200, ImageGray.quantize(200, 1))
        assertEquals(200, ImageGray.quantize(200, 0))
    }

    @Test
    fun quantize_twoLevelsIsPureBlackOrWhite() {
        assertEquals(0, ImageGray.quantize(0, 2))
        assertEquals(0, ImageGray.quantize(120, 2))
        assertEquals(255, ImageGray.quantize(200, 2))
        assertEquals(255, ImageGray.quantize(255, 2))
    }

    @Test
    fun quantize_preservesBlackAndWhiteAtAnyLevelCount() {
        for (levels in intArrayOf(2, 4, 8, 16, 256)) {
            assertEquals(0, ImageGray.quantize(0, levels))
            assertEquals(255, ImageGray.quantize(255, levels))
        }
    }

    @Test
    fun quantize_snapsToEvenlySpacedLevels() {
        for (v in 0..255) {
            val q = ImageGray.quantize(v, 16)
            assertTrue("q=$q not on the 16-level ramp", (q * 15) % 255 == 0)
        }
    }

    @Test
    fun quantize_isMonotonicNonDecreasing() {
        var prev = -1
        for (v in 0..255) {
            val q = ImageGray.quantize(v, ImageGray.GRAY_LEVELS)
            assertTrue("quantize must not decrease as input grows", q >= prev)
            prev = q
        }
    }

    @Test
    fun grayLevelsIs256_forTrue8BitGrayscale() {
        assertEquals(256, ImageGray.GRAY_LEVELS)
    }

    @Test
    fun quantize_at256LevelsIsIdentity_noPosterization() {
        for (v in 0..255) {
            assertEquals("quantize must be identity at 256 levels", v, ImageGray.quantize(v, 256))
        }
    }

    @Test
    fun quantize_atDefaultGrayLevelsIsIdentity() {
        for (v in 0..255) {
            assertEquals(v, ImageGray.quantize(v, ImageGray.GRAY_LEVELS))
        }
    }
}
