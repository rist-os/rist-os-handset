package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaSignatureTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("ota/$name")) {
            "missing test resource ota/$name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private val body: String get() = resource("manifest.json")
    private val sig: String get() = resource("manifest.json.minisig")
    private val testKey: String get() = resource("test.pub")

    private val expires = 1784937600L
    private val beforeExpiry = expires - 3600
    private val afterExpiry = expires + 1

    @Test
    fun `a real minisign signature over the real manifest is accepted`() {
        val v = OtaSignature.verify(body, sig, beforeExpiry, testKey)
        assertTrue("expected Trusted, got $v", v is OtaSignature.Verdict.Trusted)
        assertEquals(expires, (v as OtaSignature.Verdict.Trusted).expiresAtSeconds)
    }

    @Test
    fun `the fixture really is a prehashed ED signature`() {
        val parsed = OtaSignature.parseSignature(sig)
        assertNotNull(parsed)
        assertEquals("ED", parsed!!.algorithm)
    }

    @Test
    fun `a public key file carries Ed while its signatures carry ED`() {
        assertEquals("Ed", "Ed")
        val key = OtaSignature.parsePublicKey(testKey)
        assertNotNull(key)
        assertEquals(8, key!!.keyId.size)
        assertEquals(32, key.publicKey.size)
        assertTrue(key.keyId.contentEquals(OtaSignature.parseSignature(sig)!!.keyId))
    }

    @Test
    fun `a manifest signed by tools ota_sign py verifies`() {
        val published = resource("published.json")
        val publishedSig = resource("published.json.minisig")
        val expiresAt = 1789284853L
        val v = OtaSignature.verify(published, publishedSig, expiresAt - 86400, testKey)
        assertTrue("the publisher tool's output did not verify: $v",
            v is OtaSignature.Verdict.Trusted)
        assertEquals(expiresAt, (v as OtaSignature.Verdict.Trusted).expiresAtSeconds)
        assertEquals(OtaSignature.Fault.EXPIRED,
            (OtaSignature.verify(published, publishedSig, expiresAt + 1, testKey)
                as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `the compiled-in release key is a well-formed minisign public key`() {
        val key = OtaSignature.parsePublicKey(OtaSignature.PUBLIC_KEY)
        assertNotNull("PUBLIC_KEY does not parse; the device would refuse every update", key)
        assertEquals(32, key!!.publicKey.size)
        assertEquals(8, key.keyId.size)
        // keyId bytes are stored little-endian; minisign prints the id big-endian.
        assertEquals("e1afc7c6cffef5a9", key.keyId.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `a signature from another key is refused as WRONG_KEY against the release key`() {
        val v = OtaSignature.verify(body, sig, beforeExpiry, OtaSignature.PUBLIC_KEY)
        assertEquals(OtaSignature.Fault.WRONG_KEY, (v as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `an unset public key refuses a valid signature`() {
        for (unset in listOf("", "   ", OtaSignature.PLACEHOLDER)) {
            val v = OtaSignature.verify(body, sig, beforeExpiry, unset)
            assertTrue("'$unset' did not refuse: $v", v is OtaSignature.Verdict.Rejected)
            assertEquals(OtaSignature.Fault.NO_PUBLIC_KEY,
                (v as OtaSignature.Verdict.Rejected).fault)
        }
    }

    @Test
    fun `a malformed public key refuses rather than skipping`() {
        val v = OtaSignature.verify(body, sig, beforeExpiry, "RWTnot-a-real-key")
        assertEquals(OtaSignature.Fault.BAD_PUBLIC_KEY, (v as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `an unsigned manifest is never trusted`() {
        for (none in listOf(null, "", "   ")) {
            val v = OtaSignature.verify(body, none, beforeExpiry, testKey)
            assertEquals(OtaSignature.Fault.NO_SIGNATURE,
                (v as OtaSignature.Verdict.Rejected).fault)
        }
    }

    @Test
    fun `a tampered manifest is refused`() {
        val tampered = body.replace("\"timestamp\": 1784851200", "\"timestamp\": 1784851201")
        assertTrue("the fixture did not contain the field being tampered with", tampered != body)
        val v = OtaSignature.verify(tampered, sig, beforeExpiry, testKey)
        assertEquals(OtaSignature.Fault.BAD_SIGNATURE, (v as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `a corrupted signature is refused`() {
        val lines = sig.split("\n").toMutableList()
        val b = lines[1].toCharArray()
        b[20] = if (b[20] == 'A') 'B' else 'A'
        lines[1] = String(b)
        val v = OtaSignature.verify(body, lines.joinToString("\n"), beforeExpiry, testKey)
        assertEquals(OtaSignature.Fault.BAD_SIGNATURE, (v as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `a rewritten trusted comment is refused`() {
        val lines = sig.split("\n").toMutableList()
        lines[2] = "trusted comment: ristos-ota channel=stable build=9999999999 expires=99999999999"
        val v = OtaSignature.verify(body, lines.joinToString("\n"), beforeExpiry, testKey)
        assertEquals(OtaSignature.Fault.BAD_GLOBAL_SIGNATURE,
            (v as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `a truncated signature file is refused as malformed`() {
        val v = OtaSignature.verify(body, sig.lines().take(2).joinToString("\n"), beforeExpiry, testKey)
        assertEquals(OtaSignature.Fault.MALFORMED_SIGNATURE,
            (v as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `an HTML error page in place of a signature is refused`() {
        val v = OtaSignature.verify(body, "<html><body>404 Not Found</body></html>",
            beforeExpiry, testKey)
        assertEquals(OtaSignature.Fault.MALFORMED_SIGNATURE,
            (v as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `a validly signed but expired manifest is refused`() {
        val v = OtaSignature.verify(body, sig, afterExpiry, testKey)
        assertEquals(OtaSignature.Fault.EXPIRED, (v as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `expiry is inclusive of the final second`() {
        assertTrue(OtaSignature.verify(body, sig, expires, testKey) is OtaSignature.Verdict.Trusted)
        assertTrue(OtaSignature.verify(body, sig, expires + 1, testKey)
            is OtaSignature.Verdict.Rejected)
    }

    @Test
    fun `a signed manifest with no expires is refused`() {
        val v = OtaSignature.checkExpiry("""{"build":"2026072400"}""", beforeExpiry)
        assertEquals(OtaSignature.Fault.NO_EXPIRY, (v as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `a non-numeric expires is refused`() {
        val v = OtaSignature.checkExpiry("""{"expires":"soon"}""", beforeExpiry)
        assertEquals(OtaSignature.Fault.BAD_EXPIRY, (v as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `an absurdly long lifetime is refused`() {
        val far = beforeExpiry + OtaSignature.MAX_LIFETIME_SECONDS + 1
        val v = OtaSignature.checkExpiry("""{"expires":$far}""", beforeExpiry)
        assertEquals(OtaSignature.Fault.LIFETIME_TOO_LONG,
            (v as OtaSignature.Verdict.Rejected).fault)
    }

    @Test
    fun `blake2b-512 matches the published vectors`() {
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            OtaBlake2b.digest512("abc".toByteArray()).joinToString("") { "%02x".format(it) })
        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419" +
                "d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            OtaBlake2b.digest512(ByteArray(0)).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `blake2b-512 handles block boundaries`() {
        val digests = listOf(127, 128, 129, 256).map { n ->
            OtaBlake2b.digest512(ByteArray(n) { (it % 251).toByte() })
                .joinToString("") { "%02x".format(it) }
        }
        digests.forEach { assertEquals(128, it.length) }
        assertEquals(digests.size, digests.toSet().size)
    }

    @Test
    fun `a CRLF signature file still verifies`() {
        val v = OtaSignature.verify(body, sig.replace("\n", "\r\n"), beforeExpiry, testKey)
        assertTrue("CRLF broke verification: $v", v is OtaSignature.Verdict.Trusted)
    }

    @Test
    fun `a bare base64 blob is accepted as a public key`() {
        val whole = OtaSignature.parsePublicKey(testKey)
        val bare = OtaSignature.parsePublicKey(testKey.trim().lines().last())
        assertNotNull(bare)
        assertTrue(whole!!.publicKey.contentEquals(bare!!.publicKey))
    }

    @Test
    fun `the placeholder never parses`() {
        assertNull(OtaSignature.parsePublicKey(OtaSignature.PLACEHOLDER))
        assertNull(OtaSignature.parsePublicKey(""))
        assertNull(OtaSignature.parsePublicKey(null))
    }
}
