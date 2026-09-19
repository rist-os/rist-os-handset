package watch.rist.assistant

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Reads a QR code out of one camera frame, on the phone, with nothing sent anywhere. */
object QrDecode {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    fun decode(frame: Bitmap): String? {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        return decode(pixels, w, h)
    }

    /** ARGB pixels, row-major. Null when the frame holds no readable code; never throws. */
    internal fun decode(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val source = RGBLuminanceSource(width, height, pixels)
        // Light-on-dark codes (printed white on a dark menu) only read once inverted.
        for (candidate in listOf(source, source.invert())) {
            val text = runCatching {
                QRCodeReader().decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }
}
