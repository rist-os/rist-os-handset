package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaManifestTest {

    private fun bad(json: String): OtaManifest.Companion.Parsed.Bad {
        val p = OtaManifest.parse(json)
        assertTrue("expected a rejection, got $p", p is OtaManifest.Companion.Parsed.Bad)
        return p as OtaManifest.Companion.Parsed.Bad
    }

    @Test
    fun readsTheReleaseToolsOutput() {
        val m = OtaFixtures.manifest()
        assertEquals(OtaFixtures.DEVICE, m.device)
        assertEquals("stable", m.channel)
        assertEquals(OtaFixtures.BUILD, m.build)
        assertEquals(OtaFixtures.TIMESTAMP, m.timestampSeconds)
        assertEquals(OtaFixtures.PAYLOAD_OFFSET, m.payloadOffset)
        assertEquals(OtaFixtures.PAYLOAD_SIZE, m.payloadSize)
        assertEquals(OtaFixtures.ZIP_SIZE, m.zipSize)
        assertEquals(4, m.payloadProperties.size)
    }

    @Test
    fun aFullPackageHasNoIncrementalSource() {
        assertNull(OtaFixtures.manifest().incrementalFrom)
    }

    @Test
    fun anIncrementalCarriesItsSourceBuild() {
        val m = OtaFixtures.manifest(OtaFixtures.json(incrementalFrom = "2026072200"))
        assertEquals("2026072200", m.incrementalFrom)
    }

    @Test
    fun rubbishIsNotAnException() {
        assertEquals(OtaManifest.Companion.Fault.NOT_JSON, bad("<html>502</html>").fault)
        assertEquals(OtaManifest.Companion.Fault.NOT_JSON, bad("").fault)
        assertEquals(OtaManifest.Companion.Fault.NOT_JSON, bad("{\"device\": ").fault)
        assertTrue(OtaManifest.parse(null) is OtaManifest.Companion.Parsed.Bad)
    }

    @Test
    fun everyFieldTheServerRequiresIsRequiredHere() {
        for (key in listOf("device", "channel", "build", "timestamp", "url",
                           "payload_offset", "payload_size", "payload_properties")) {
            val stripped = OtaFixtures.json().lines()
                .filterNot { it.trimStart().startsWith("\"$key\"") }
                .joinToString("\n").replace(Regex(",(\\s*})"), "$1")
            val b = bad(stripped)
            assertTrue("$key: got ${b.fault} ${b.detail}",
                b.fault == OtaManifest.Companion.Fault.MISSING_FIELD ||
                    b.fault == OtaManifest.Companion.Fault.NOT_JSON)
        }
    }

    @Test
    fun aCleartextUrlIsRefused() {
        val b = bad(OtaFixtures.json(url = "http://ota.example.com/pkg/x.zip"))
        assertEquals(OtaManifest.Companion.Fault.INSECURE_URL, b.fault)
    }

    @Test
    fun aZeroPayloadSizeIsRefused() {
        assertEquals(OtaManifest.Companion.Fault.BAD_VALUE,
            bad(OtaFixtures.json(payloadSize = 0)).fault)
    }

    @Test
    fun theTwoIndependentlyProducedSizesMustAgree() {
        val b = bad(OtaFixtures.json(properties = listOf(
            "FILE_HASH=${OtaFixtures.FILE_HASH}",
            "FILE_SIZE=${OtaFixtures.PAYLOAD_SIZE - 1}",
            "METADATA_HASH=${OtaFixtures.METADATA_HASH}",
            "METADATA_SIZE=${OtaFixtures.METADATA_SIZE}",
        )))
        assertEquals(OtaManifest.Companion.Fault.INCONSISTENT, b.fault)
    }

    @Test
    fun aRepeatedPropertyKeyIsRefused() {
        val b = bad(OtaFixtures.json(properties = listOf(
            "FILE_HASH=${OtaFixtures.FILE_HASH}",
            "FILE_SIZE=${OtaFixtures.PAYLOAD_SIZE}",
            "FILE_SIZE=${OtaFixtures.PAYLOAD_SIZE}",
            "METADATA_SIZE=${OtaFixtures.METADATA_SIZE}",
        )))
        assertEquals(OtaManifest.Companion.Fault.BAD_PROPERTIES, b.fault)
        assertTrue(b.detail, b.detail.contains("repeated key"))
    }

    @Test
    fun anEmptyHashCostsTheDeviceItsAbilityToResume() {
        val b = bad(OtaFixtures.json(properties = listOf(
            "FILE_HASH=",
            "FILE_SIZE=${OtaFixtures.PAYLOAD_SIZE}",
            "METADATA_HASH=${OtaFixtures.METADATA_HASH}",
            "METADATA_SIZE=${OtaFixtures.METADATA_SIZE}",
        )))
        assertEquals(OtaManifest.Companion.Fault.BAD_PROPERTIES, b.fault)
    }

    @Test
    fun aHashThatIsNotBase64AtAllIsRefused() {
        for (broken in listOf("aaa!", "not base64!", "abc", "aa aa", OtaFixtures.FILE_HASH + "=")) {
            val b = bad(OtaFixtures.json(properties = listOf(
                "FILE_HASH=$broken",
                "FILE_SIZE=${OtaFixtures.PAYLOAD_SIZE}",
                "METADATA_HASH=${OtaFixtures.METADATA_HASH}",
                "METADATA_SIZE=${OtaFixtures.METADATA_SIZE}",
            )))
            assertEquals(broken, OtaManifest.Companion.Fault.BAD_PROPERTIES, b.fault)
        }
        assertTrue(OtaManifest.isBase64("a".repeat(64)))
    }

    @Test
    fun thePayloadMustFitInsideTheZip() {
        assertEquals(OtaManifest.Companion.Fault.INCONSISTENT,
            bad(OtaFixtures.json(zipSize = OtaFixtures.PAYLOAD_OFFSET + 10)).fault)
    }

    @Test
    fun anOverflowingPayloadWindowDoesNotWrapPastTheCheck() {
        val b = bad(OtaFixtures.json(
            payloadOffset = Long.MAX_VALUE - 5,
            payloadSize = 100,
            zipSize = Long.MAX_VALUE,
            properties = listOf(
                "FILE_HASH=${OtaFixtures.FILE_HASH}",
                "FILE_SIZE=100",
                "METADATA_HASH=${OtaFixtures.METADATA_HASH}",
                "METADATA_SIZE=10",
            )))
        assertEquals(OtaManifest.Companion.Fault.INCONSISTENT, b.fault)
    }

    @Test
    fun base64Recogniser() {
        assertTrue(OtaManifest.isBase64(OtaFixtures.FILE_HASH))
        assertTrue(OtaManifest.isBase64("AAAA"))
        assertTrue(OtaManifest.isBase64("AA=="))
        assertTrue(!OtaManifest.isBase64(""))
        assertTrue(!OtaManifest.isBase64("AAA"))
        assertTrue(!OtaManifest.isBase64("A==="))
        assertTrue(!OtaManifest.isBase64("AA A"))
        assertTrue(!OtaManifest.isBase64("AAAé"))
    }
}
