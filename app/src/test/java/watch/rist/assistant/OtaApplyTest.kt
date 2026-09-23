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
    fun aPayloadThisBuildCannotReadIsPermanentRatherThanRetriedForever() {
        // 2026092200 shipped an OTA whose manifest the previous release's update_engine could not
        // parse. Code 23 was unnamed, fell into the unknown-code branch, and the handset retried
        // every six hours -- re-fetching the payload each time -- with no cap and no backoff.
        // Every code here means "this package is wrong": another download produces the same bytes.
        for (code in listOf(
            OtaApply.ErrorCode.DOWNLOAD_MANIFEST_PARSE_ERROR,
            OtaApply.ErrorCode.DOWNLOAD_INVALID_METADATA_MAGIC_STRING,
            OtaApply.ErrorCode.DOWNLOAD_INVALID_METADATA_SIZE,
            OtaApply.ErrorCode.DOWNLOAD_SIGNATURE_MISSING_IN_MANIFEST,
            OtaApply.ErrorCode.DOWNLOAD_INVALID_METADATA_SIGNATURE,
            OtaApply.ErrorCode.DOWNLOAD_METADATA_SIGNATURE_ERROR,
            OtaApply.ErrorCode.DOWNLOAD_METADATA_SIGNATURE_VERIFICATION_ERROR,
            OtaApply.ErrorCode.DOWNLOAD_METADATA_SIGNATURE_MISMATCH,
            OtaApply.ErrorCode.SIGNED_DELTA_PAYLOAD_EXPECTED_ERROR,
            OtaApply.ErrorCode.DOWNLOAD_PAYLOAD_PUB_KEY_VERIFICATION_ERROR,
            OtaApply.ErrorCode.UNSUPPORTED_MAJOR_PAYLOAD_VERSION,
            OtaApply.ErrorCode.UNSUPPORTED_MINOR_PAYLOAD_VERSION,
        )) {
            assertTrue(
                "update_engine code $code must be permanent, not retried",
                OtaApply.classify(code) is OtaApply.Outcome.Permanent)
        }
    }

    @Test
    fun aFailedWriteOnThisHandsetIsRetried() {
        // These three were briefly classified Permanent alongside the codes above, on the reasoning
        // that re-running writes the same bytes. That reads the codes backwards: they say the bytes
        // that landed ON THIS DEVICE did not verify, not that the package is bad. A flaky UFS write,
        // an I/O error on read-back, an interrupted snapshot merge or a low-memory abort during
        // verity all produce them, and all succeed on a second attempt -- AOSP retries them. Left
        // Permanent, one bad write on one handset refused the whole build on that handset, and a
        // refused build is skipped until an entirely new build number ships.
        for (code in listOf(
            OtaApply.ErrorCode.NEW_ROOTFS_VERIFICATION_ERROR,
            OtaApply.ErrorCode.NEW_KERNEL_VERIFICATION_ERROR,
            OtaApply.ErrorCode.FILESYSTEM_VERIFIER_ERROR,
        )) {
            assertTrue(
                "update_engine code $code is a device-side write failure and must be retried",
                OtaApply.classify(code) is OtaApply.Outcome.Retry)
        }
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
