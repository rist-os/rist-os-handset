package watch.rist.assistant

object ImageGray {

    const val GRAY_LEVELS = 256

    fun luminance(r: Int, g: Int, b: Int): Int {
        val rr = r.coerceIn(0, 255)
        val gg = g.coerceIn(0, 255)
        val bb = b.coerceIn(0, 255)
        // Rec. 601 luma 0.299 / 0.587 / 0.114 in fixed point (x1000).
        return ((rr * 299 + gg * 587 + bb * 114) / 1000).coerceIn(0, 255)
    }

    fun quantize(value: Int, levels: Int): Int {
        val v = value.coerceIn(0, 255)
        if (levels <= 1) return v
        val steps = levels - 1
        val idx = (v * steps + 127) / 255
        return (idx * 255 + steps / 2) / steps
    }
}
