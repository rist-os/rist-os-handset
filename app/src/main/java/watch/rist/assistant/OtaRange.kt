package watch.rist.assistant

object OtaRange {

    // The first four bytes of payload.bin.
    const val PAYLOAD_MAGIC = "CrAU"

    val PAYLOAD_MAGIC_BYTES: ByteArray = PAYLOAD_MAGIC.toByteArray(Charsets.US_ASCII)

    fun rangeHeader(payloadOffset: Long, payloadSize: Long, received: Long, limit: Long? = null): String? {
        if (payloadOffset < 0L || payloadSize <= 0L || received < 0L) return null
        if (received >= payloadSize) return null
        val start = payloadOffset + received
        val remaining = payloadSize - received
        val want = if (limit == null) remaining else minOf(limit, remaining)
        if (want <= 0L) return null
        // Inclusive end, per HTTP; never past the payload window (416).
        return "bytes=$start-${start + want - 1}"
    }

    // total is -1 for an unknown "*".
    data class ContentRange(val start: Long, val end: Long, val total: Long)

    // Digit runs bounded: the header is attacker-supplied.
    private val CONTENT_RANGE_RE = Regex("""^bytes\s+(\d{1,19})-(\d{1,19})/(\d{1,19}|\*)$""")

    fun parseContentRange(header: String?): ContentRange? {
        val m = CONTENT_RANGE_RE.matchEntire(header?.trim().orEmpty()) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        val total = if (m.groupValues[3] == "*") -1L else (m.groupValues[3].toLongOrNull() ?: return null)
        if (end < start) return null
        if (total in 0 until (end + 1)) return null
        return ContentRange(start, end, total)
    }

    sealed class Ranged {
        data class Honoured(val range: ContentRange) : Ranged()
        object Ignored : Ranged()
        object Unsatisfiable : Ranged()
        data class Wrong(val detail: String) : Ranged()
    }

    fun verifyRanged(status: Int, contentRange: String?, requestedStart: Long): Ranged {
        if (status == 200) return Ranged.Ignored
        if (status == 416) return Ranged.Unsatisfiable
        if (status != 206) return Ranged.Wrong("HTTP $status")
        val cr = parseContentRange(contentRange)
            ?: return Ranged.Wrong("206 without a usable Content-Range: $contentRange")
        if (cr.start != requestedStart) {
            return Ranged.Wrong("206 starts at ${cr.start}, asked for $requestedStart")
        }
        return Ranged.Honoured(cr)
    }

    fun looksLikePayload(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < PAYLOAD_MAGIC_BYTES.size) return false
        for (i in PAYLOAD_MAGIC_BYTES.indices) if (bytes[i] != PAYLOAD_MAGIC_BYTES[i]) return false
        return true
    }
}
