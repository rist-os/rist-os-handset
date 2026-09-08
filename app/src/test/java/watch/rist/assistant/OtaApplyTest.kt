package watch.rist.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaApplyTest {

    @Test
    fun theArgumentsArePassedThroughVerbatim() {
        val m = OtaFixtures.manifest()
        val h = OtaApply.handoff(m)
        assertEquals(OtaFixtures.URL, h.url)
        assertEquals(OtaFixtures.PAYLOAD_OFFSET, h.payloadOffset)
        assertEquals(OtaFixtures.PAYLOAD_SIZE, h.payloadSize)
        assertArrayEquals(
            arrayOf(
                "FILE_HASH=${OtaFixtures.FILE_HASH}",
                "FILE_SIZE=${OtaFixtures.PAYLOAD_SIZE}",
                "METADATA_HASH=${OtaFixtures.METADATA_HASH}",
                "METADATA_SIZE=${OtaFixtures.METADATA_SIZE}",
            ),
            h.properties,
        )
    }

    @Test
    fun switchSlotOnRebootIsNotSentBecauseItsDefaultIsAlreadyRight() {
        assertTrue(OtaApply.handoff(OtaFixtures.manifest()).properties
            .none { it.startsWith(OtaApply.SWITCH_SLOT_ON_REBOOT) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnvalidatedManifestCannotBeHandedOver() {
        OtaApply.handoff(OtaFixtures.manifest().copy(payloadProperties = listOf("FILE_SIZE=1")))
    }

    @Test
    fun successIsApplied() {
        assertEquals(OtaApply.Outcome.Applied, OtaApply.classify(OtaApply.ErrorCode.SUCCESS))
    }

    @Test
    fun updatedButNotActiveIsNotAFailure() {
        assertEquals(OtaApply.Outcome.AppliedNotActive,
            OtaApply.classify(OtaApply.ErrorCode.UPDATED_BUT_NOT_ACTIVE))
    }

    @Test
    fun aTransferErrorIsWhatTheServersCapProduces() {
        val o = OtaApply.classify(OtaApply.ErrorCode.DOWNLOAD_TRANSFER_ERROR)
        assertTrue(o.toString(), o is OtaApply.Outcome.Retry)
    }

    @Test
    fun aSignatureFailureIsPermanentAndIsASecurityEvent() {
        val o = OtaApply.classify(OtaApply.ErrorCode.DOWNLOAD_PAYLOAD_VERIFICATION_ERROR)
        assertTrue(o.toString(), o is OtaApply.Outcome.Permanent)
    }

    @Test
    fun aHashOrSizeMismatchIsPermanent() {
        assertTrue(OtaApply.classify(OtaApply.ErrorCode.PAYLOAD_HASH_MISMATCH_ERROR)
            is OtaApply.Outcome.Permanent)
        assertTrue(OtaApply.classify(OtaApply.ErrorCode.PAYLOAD_SIZE_MISMATCH_ERROR)
            is OtaApply.Outcome.Permanent)
    }

    @Test
    fun updateEnginesOwnDowngradeRefusalIsPermanent() {
        assertTrue(OtaApply.classify(OtaApply.ErrorCode.PAYLOAD_TIMESTAMP_ERROR)
            is OtaApply.Outcome.Permanent)
    }

    @Test
    fun notEnoughSpaceNeedsTheUser() {
        assertTrue(OtaApply.classify(OtaApply.ErrorCode.NOT_ENOUGH_SPACE)
            is OtaApply.Outcome.NeedsUser)
    }

    @Test
    fun deviceCorruptedIsItsOwnOutcome() {
        assertEquals(OtaApply.Outcome.Corrupted,
            OtaApply.classify(OtaApply.ErrorCode.DEVICE_CORRUPTED))
    }

    @Test
    fun anAlreadyRunningUpdateIsJustAWait() {
        assertTrue(OtaApply.classify(OtaApply.ErrorCode.UPDATE_PROCESSING) is OtaApply.Outcome.Retry)
        assertTrue(OtaApply.classify(OtaApply.ErrorCode.UPDATE_ALREADY_INSTALLED)
            is OtaApply.Outcome.Retry)
    }

    @Test
    fun anUnknownCodeIsRetriedRatherThanTreatedAsSuccess() {
        assertTrue(OtaApply.classify(9999) is OtaApply.Outcome.Retry)
    }

    @Test
    fun theOmahaFlagBitsDoNotTurnSuccessIntoAFailure() {
        val devModeFlag = 1 shl 31
        assertEquals(OtaApply.Outcome.Applied, OtaApply.classify(OtaApply.ErrorCode.SUCCESS or devModeFlag))
        val resumedFlag = 1 shl 30
        assertTrue(OtaApply.classify(OtaApply.ErrorCode.DOWNLOAD_TRANSFER_ERROR or resumedFlag)
            is OtaApply.Outcome.Retry)
    }
}
