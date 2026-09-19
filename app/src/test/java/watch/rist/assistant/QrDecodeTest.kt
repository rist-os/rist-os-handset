package watch.rist.assistant

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A frame goes in as pixels and a link comes out, with no camera and no network involved. */
class QrDecodeTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A QR code drawn small inside a larger frame, as a viewfinder would see it. */
    private fun frame(text: String, dark: Int = black, light: Int = white): Triple<IntArray, Int, Int> {
        val size = 240
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val w = 480
        val h = 640
        val pixels = IntArray(w * h) { light }
        for (y in 0 until size) for (x in 0 until size) {
            if (matrix.get(x, y)) pixels[(y + 200) * w + (x + 120)] = dark
        }
        return Triple(pixels, w, h)
    }

    @Test
    fun `a code in the frame is read`() {
        val (px, w, h) = frame("https://menu.example.com/table/12")
        assertEquals("https://menu.example.com/table/12", QrDecode.decode(px, w, h))
    }

    @Test
    fun `a white-on-dark code is read too`() {
        val (px, w, h) = frame("https://example.com/", dark = white, light = black)
        assertEquals("https://example.com/", QrDecode.decode(px, w, h))
    }

    @Test
    fun `a frame with no code is simply nothing`() {
        assertNull(QrDecode.decode(IntArray(320 * 240) { white }, 320, 240))
        assertNull(QrDecode.decode(IntArray(4), 320, 240))
        assertNull(QrDecode.decode(IntArray(0), 0, 0))
    }
}
