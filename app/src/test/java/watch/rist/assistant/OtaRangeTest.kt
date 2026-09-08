package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaRangeTest {

    private val offset = OtaFixtures.PAYLOAD_OFFSET
    private val size = OtaFixtures.PAYLOAD_SIZE

    @Test
    fun aFreshStartAsksForTheWholePayloadWindow() {
        assertEquals("bytes=$offset-${offset + size - 1}",
            OtaRange.rangeHeader(offset, size, 0L))
    }

    @Test
    fun aResumeAsksOnlyForWhatIsLeft() {
        assertEquals("bytes=${offset + 1000}-${offset + size - 1}",
            OtaRange.rangeHeader(offset, size, 1000L))
    }

    @Test
    fun theEndIsClampedToThePayloadNotTheZip() {
        assertEquals("bytes=$offset-${offset + 3}", OtaRange.rangeHeader(offset, size, 0L, 4L))
        assertEquals("bytes=${offset + size - 2}-${offset + size - 1}",
            OtaRange.rangeHeader(offset, size, size - 2, 1_000_000L))
    }

    @Test
    fun aCompletedPayloadHasNoNextRange() {
        assertNull(OtaRange.rangeHeader(offset, size, size))
        assertNull(OtaRange.rangeHeader(offset, size, size + 1))
    }

    @Test
    fun nonsenseInputsProduceNoRangeRatherThanABadOne() {
        assertNull(OtaRange.rangeHeader(-1, size, 0))
        assertNull(OtaRange.rangeHeader(offset, 0, 0))
        assertNull(OtaRange.rangeHeader(offset, size, -1))
    }

    @Test
    fun contentRangeIsParsed() {
        val cr = OtaRange.parseContentRange("bytes 4443-4446/1662717378")
        assertEquals(OtaRange.ContentRange(4443, 4446, 1662717378), cr)
    }

    @Test
    fun anUnknownTotalIsAllowed() {
        assertEquals(-1L, OtaRange.parseContentRange("bytes 0-3/*")?.total)
    }

    @Test
    fun aMalformedContentRangeIsNull() {
        assertNull(OtaRange.parseContentRange(null))
        assertNull(OtaRange.parseContentRange("bytes=4443-4446/1662717378"))
        assertNull(OtaRange.parseContentRange("bytes 4446-4443/1662717378"))
        assertNull(OtaRange.parseContentRange("bytes 0-100/50"))
        assertNull(OtaRange.parseContentRange("bytes 0-" + "9".repeat(40) + "/100"))
    }

    @Test
    fun aTwoHundredMeansTheServerIgnoredTheRange() {
        assertEquals(OtaRange.Ranged.Ignored, OtaRange.verifyRanged(200, null, offset))
        assertEquals(OtaRange.Ranged.Ignored,
            OtaRange.verifyRanged(200, "bytes 4443-4446/1662717378", offset))
    }

    @Test
    fun a206StartingSomewhereElseIsRejected() {
        val v = OtaRange.verifyRanged(206, "bytes 0-3/1662717378", offset)
        assertTrue(v.toString(), v is OtaRange.Ranged.Wrong)
    }

    @Test
    fun a206WithNoContentRangeIsRejected() {
        assertTrue(OtaRange.verifyRanged(206, null, offset) is OtaRange.Ranged.Wrong)
    }

    @Test
    fun a416MeansTheManifestAndTheObjectDisagree() {
        assertEquals(OtaRange.Ranged.Unsatisfiable, OtaRange.verifyRanged(416, null, offset))
    }

    @Test
    fun aGoodRangedAnswerCarriesTheObjectSize() {
        val v = OtaRange.verifyRanged(206, "bytes $offset-${offset + 3}/${OtaFixtures.ZIP_SIZE}", offset)
        assertTrue(v is OtaRange.Ranged.Honoured)
        assertEquals(OtaFixtures.ZIP_SIZE, (v as OtaRange.Ranged.Honoured).range.total)
    }

    @Test
    fun theMagicIsTheCheapestProofAnOffsetIsRight() {
        assertTrue(OtaRange.looksLikePayload("CrAU".toByteArray()))
        assertTrue(OtaRange.looksLikePayload("CrAU and then some".toByteArray()))
        assertFalse(OtaRange.looksLikePayload("PK".toByteArray()))
        assertFalse(OtaRange.looksLikePayload("Crau".toByteArray()))
        assertFalse(OtaRange.looksLikePayload("Cr".toByteArray()))
        assertFalse(OtaRange.looksLikePayload(ByteArray(0)))
        assertFalse(OtaRange.looksLikePayload(null))
    }
}
