package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    @Test
    fun `200 with a token is the only success`() {
        assertEquals(Enrolment.PairResult.OK, Enrolment.classifyPair(200, tokenBlank = false))
        assertEquals(Enrolment.PairResult.OK, Enrolment.classifyPair(201, tokenBlank = false))
    }

    @Test
    fun `200 without a token is not success`() {
        assertEquals(Enrolment.PairResult.NOT_GRANTED, Enrolment.classifyPair(200, tokenBlank = true))
        assertNotEquals(Enrolment.PairResult.OK, Enrolment.classifyPair(200, tokenBlank = true))
    }

    @Test
    fun `a 2xx without a token is not reported as a network failure`() {
        assertNotEquals(
            Enrolment.PairResult.NETWORK,
            Enrolment.classifyPair(200, tokenBlank = true)
        )
        assertNotEquals(
            "a reached-the-backend failure must not claim the code is unused",
            Enrolment.explainPair(Enrolment.PairResult.NETWORK),
            Enrolment.explainPair(Enrolment.PairResult.NOT_GRANTED)
        )
    }

    @Test
    fun `404 means the code is not one of theirs, not keep waiting`() {
        assertEquals(Enrolment.PairResult.NOT_RECOGNISED, Enrolment.classifyPair(404, tokenBlank = true))
    }

    @Test
    fun `403 is a refusal`() {
        assertEquals(Enrolment.PairResult.REFUSED, Enrolment.classifyPair(403, tokenBlank = true))
    }

    @Test
    fun `400 and 422 are malformed`() {
        assertEquals(Enrolment.PairResult.MALFORMED, Enrolment.classifyPair(400, tokenBlank = true))
        assertEquals(Enrolment.PairResult.MALFORMED, Enrolment.classifyPair(422, tokenBlank = true))
    }

    @Test
    fun `server errors are inconclusive, never a refusal`() {
        assertEquals(Enrolment.PairResult.NETWORK, Enrolment.classifyPair(500, tokenBlank = true))
        assertEquals(Enrolment.PairResult.NETWORK, Enrolment.classifyPair(503, tokenBlank = true))
    }

    @Test
    fun `no non-2xx status can ever produce OK`() {
        for (code in 100..599) {
            if (code in 200..299) continue
            assertNotEquals(
                "HTTP $code must never be treated as a successful pairing",
                Enrolment.PairResult.OK,
                Enrolment.classifyPair(code, tokenBlank = false)
            )
        }
    }

    @Test
    fun `a 2xx is only OK when a token actually came back`() {
        for (code in 200..299) {
            assertNotEquals(
                "HTTP $code with an empty token must not be a successful pairing",
                Enrolment.PairResult.OK,
                Enrolment.classifyPair(code, tokenBlank = true)
            )
        }
    }

    @Test
    fun `codes shorter than the backend floor are rejected without a round trip`() {
        assertFalse(Enrolment.isPlausibleCode(""))
        assertFalse(Enrolment.isPlausibleCode("        "))
        assertFalse(Enrolment.isPlausibleCode("ABC1234"))
        assertTrue(Enrolment.isPlausibleCode("ABC12345"))
    }

    @Test
    fun `the malformed message states a minimum rather than an exact length`() {
        val m = Enrolment.explainPair(Enrolment.PairResult.MALFORMED)
        assertTrue("does not state a minimum: $m", m.contains("at least"))
        assertTrue("does not name the floor: $m", m.contains(Enrolment.MIN_CODE_LEN.toString()))
        assertTrue(Enrolment.isPlausibleCode("ABC123456789"))
    }

    @Test
    fun `surrounding whitespace does not change the verdict`() {
        assertTrue(Enrolment.isPlausibleCode("  ABC12345  "))
        assertTrue(Enrolment.isPlausibleCode("ABC12345\n"))
    }

    @Test
    fun `every PairResult has a non-blank explanation`() {
        for (r in Enrolment.PairResult.values()) {
            val msg = Enrolment.explainPair(r)
            assertTrue("PairResult.$r has no message for the user", msg.isNotBlank())
            assertTrue("PairResult.$r's message is too terse to act on", msg.length > 15)
        }
    }

    @Test
    fun `every failure message is distinct`() {
        val distinct = Enrolment.PairResult.values().map { Enrolment.explainPair(it) }.toSet()
        assertEquals(
            "each verdict must be separately actionable",
            Enrolment.PairResult.values().size, distinct.size
        )
    }

    @Test
    fun `a mistyped code and a spent code do not read the same`() {
        assertNotEquals(
            Enrolment.explainPair(Enrolment.PairResult.NOT_RECOGNISED),
            Enrolment.explainPair(Enrolment.PairResult.REFUSED)
        )
    }

    @Test
    fun `a rate-limit status is not reported as a network failure`() {
        assertEquals(Enrolment.PairResult.LOCKED_OUT, Enrolment.classifyPair(429, tokenBlank = true))
    }
}
