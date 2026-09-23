package watch.rist.assistant

object OtaApply {

    // update_engine defaults this to 1 when the header is omitted.
    const val SWITCH_SLOT_ON_REBOOT = "SWITCH_SLOT_ON_REBOOT"

    data class Handoff(
        val url: String,
        val payloadOffset: Long,
        val payloadSize: Long,
        val properties: Array<String>,
        val attachIfRunning: Boolean = false,
    ) {
        // An Array in a data class compares by identity; compare contents.
        override fun equals(other: Any?): Boolean =
            other is Handoff && url == other.url && payloadOffset == other.payloadOffset &&
                payloadSize == other.payloadSize && properties.contentEquals(other.properties) &&
                attachIfRunning == other.attachIfRunning

        override fun hashCode(): Int =
            ((((url.hashCode() * 31 + payloadOffset.hashCode()) * 31) +
                payloadSize.hashCode()) * 31 + properties.contentHashCode()) * 31 +
                attachIfRunning.hashCode()
    }

    fun handoff(m: OtaManifest, attachIfRunning: Boolean = false): Handoff {
        val parsed = OtaManifest.validate(m)
        require(parsed is OtaManifest.Companion.Parsed.Ok) {
            "manifest failed validation: ${(parsed as OtaManifest.Companion.Parsed.Bad).detail}"
        }
        return Handoff(
            url = m.url,
            payloadOffset = m.payloadOffset,
            payloadSize = m.payloadSize,
            properties = m.payloadProperties.toTypedArray(),
            attachIfRunning = attachIfRunning,
        )
    }

    object ErrorCode {
        const val SUCCESS = 0
        const val ERROR = 1
        const val FILESYSTEM_COPIER_ERROR = 4
        const val POST_INSTALL_RUNNER_ERROR = 5
        const val PAYLOAD_MISMATCHED_TYPE_ERROR = 6
        const val INSTALL_DEVICE_OPEN_ERROR = 7
        const val KERNEL_DEVICE_OPEN_ERROR = 8
        const val DOWNLOAD_TRANSFER_ERROR = 9
        const val PAYLOAD_HASH_MISMATCH_ERROR = 10
        const val PAYLOAD_SIZE_MISMATCH_ERROR = 11
        const val DOWNLOAD_PAYLOAD_VERIFICATION_ERROR = 12
        const val DOWNLOAD_NEW_PARTITION_INFO_ERROR = 13
        const val DOWNLOAD_WRITE_ERROR = 14
        const val NEW_ROOTFS_VERIFICATION_ERROR = 15
        const val NEW_KERNEL_VERIFICATION_ERROR = 16
        const val SIGNED_DELTA_PAYLOAD_EXPECTED_ERROR = 17
        const val DOWNLOAD_PAYLOAD_PUB_KEY_VERIFICATION_ERROR = 18
        const val DOWNLOAD_STATE_INITIALIZATION_ERROR = 20
        const val DOWNLOAD_INVALID_METADATA_MAGIC_STRING = 21
        const val DOWNLOAD_SIGNATURE_MISSING_IN_MANIFEST = 22
        const val DOWNLOAD_MANIFEST_PARSE_ERROR = 23
        const val DOWNLOAD_METADATA_SIGNATURE_ERROR = 24
        const val DOWNLOAD_METADATA_SIGNATURE_VERIFICATION_ERROR = 25
        const val DOWNLOAD_METADATA_SIGNATURE_MISMATCH = 26
        const val DOWNLOAD_INVALID_METADATA_SIZE = 32
        const val DOWNLOAD_INVALID_METADATA_SIGNATURE = 33
        const val UNSUPPORTED_MAJOR_PAYLOAD_VERSION = 44
        const val UNSUPPORTED_MINOR_PAYLOAD_VERSION = 45
        const val FILESYSTEM_VERIFIER_ERROR = 47
        const val USER_CANCELED = 48
        const val PAYLOAD_TIMESTAMP_ERROR = 51
        const val UPDATED_BUT_NOT_ACTIVE = 52
        const val NOT_ENOUGH_SPACE = 60
        const val DEVICE_CORRUPTED = 61
        const val UPDATE_PROCESSING = 65
        const val UPDATE_ALREADY_INSTALLED = 66
    }

    object Status {
        const val IDLE = 0
        const val CHECKING_FOR_UPDATE = 1
        const val UPDATE_AVAILABLE = 2
        const val DOWNLOADING = 3
        const val VERIFYING = 4
        const val FINALIZING = 5
        const val UPDATED_NEED_REBOOT = 6
        const val REPORTING_ERROR_EVENT = 7
        const val ATTEMPTING_ROLLBACK = 8
        const val DISABLED = 9
    }

    sealed class Outcome {
        object Applied : Outcome()
        object AppliedNotActive : Outcome()
        data class Retry(val detail: String) : Outcome()
        data class Permanent(val detail: String) : Outcome()
        data class NeedsUser(val detail: String) : Outcome()
        object Corrupted : Outcome()
    }

    // The top four bits are Omaha-only flags; masked so a stray flag cannot turn SUCCESS into an unknown code.
    fun classify(errorCode: Int): Outcome = when (errorCode and 0x0FFFFFFF) {
        ErrorCode.SUCCESS -> Outcome.Applied
        ErrorCode.UPDATED_BUT_NOT_ACTIVE -> Outcome.AppliedNotActive

        ErrorCode.DOWNLOAD_TRANSFER_ERROR -> Outcome.Retry("download transfer error")

        ErrorCode.ERROR -> Outcome.Retry("generic update_engine error")
        ErrorCode.FILESYSTEM_COPIER_ERROR -> Outcome.Retry("filesystem copier error")
        ErrorCode.DOWNLOAD_STATE_INITIALIZATION_ERROR -> Outcome.Retry("download state init error")
        ErrorCode.INSTALL_DEVICE_OPEN_ERROR -> Outcome.Retry("install device open error")
        ErrorCode.KERNEL_DEVICE_OPEN_ERROR -> Outcome.Retry("kernel device open error")
        ErrorCode.POST_INSTALL_RUNNER_ERROR -> Outcome.Retry("post-install runner error")
        ErrorCode.UPDATE_PROCESSING -> Outcome.Retry("update already in progress")
        ErrorCode.UPDATE_ALREADY_INSTALLED -> Outcome.Retry("payload already installed")

        ErrorCode.PAYLOAD_HASH_MISMATCH_ERROR -> Outcome.Permanent("payload hash mismatch")
        ErrorCode.PAYLOAD_SIZE_MISMATCH_ERROR -> Outcome.Permanent("payload size mismatch")
        ErrorCode.PAYLOAD_MISMATCHED_TYPE_ERROR -> Outcome.Permanent("payload type unsupported")
        ErrorCode.DOWNLOAD_PAYLOAD_VERIFICATION_ERROR ->
            Outcome.Permanent("payload signature verification failed")
        ErrorCode.PAYLOAD_TIMESTAMP_ERROR -> Outcome.Permanent("downgrade refused by update_engine")

        // The package itself is wrong or cannot be read. Retrying re-downloads it and fails the
        // same way, so every one of these is permanent for this build. They were all landing in
        // the `else` branch below: build 2026092200 shipped an OTA whose payload manifest the
        // previous release's update_engine could not parse, and the handset reported
        // "retry (unrecognised update_engine code 23)" on a six-hour loop, re-fetching each time.
        ErrorCode.DOWNLOAD_MANIFEST_PARSE_ERROR ->
            Outcome.Permanent("update_engine cannot parse this payload's manifest")
        ErrorCode.DOWNLOAD_INVALID_METADATA_MAGIC_STRING ->
            Outcome.Permanent("payload metadata magic is wrong (not a payload, or a bad offset)")
        ErrorCode.DOWNLOAD_INVALID_METADATA_SIZE -> Outcome.Permanent("payload metadata size is wrong")
        ErrorCode.DOWNLOAD_SIGNATURE_MISSING_IN_MANIFEST ->
            Outcome.Permanent("payload manifest carries no signature")
        ErrorCode.DOWNLOAD_INVALID_METADATA_SIGNATURE ->
            Outcome.Permanent("payload metadata signature is malformed")
        ErrorCode.DOWNLOAD_METADATA_SIGNATURE_ERROR,
        ErrorCode.DOWNLOAD_METADATA_SIGNATURE_VERIFICATION_ERROR,
        ErrorCode.DOWNLOAD_METADATA_SIGNATURE_MISMATCH ->
            Outcome.Permanent("payload metadata signature does not verify")
        ErrorCode.SIGNED_DELTA_PAYLOAD_EXPECTED_ERROR ->
            Outcome.Permanent("an unsigned payload was offered where a signed one is required")
        ErrorCode.DOWNLOAD_PAYLOAD_PUB_KEY_VERIFICATION_ERROR ->
            Outcome.Permanent("payload is not signed by a key this image trusts")
        ErrorCode.UNSUPPORTED_MAJOR_PAYLOAD_VERSION ->
            Outcome.Permanent("payload major version is newer than this build's update_engine")
        ErrorCode.UNSUPPORTED_MINOR_PAYLOAD_VERSION ->
            Outcome.Permanent("payload minor version is newer than this build's update_engine")

        // Written to the inactive slot, then failed its own verification. These were briefly
        // classified Permanent on the theory that re-running writes the same bytes. That is wrong,
        // and dangerously so: these say the bytes that LANDED ON THIS DEVICE do not verify, not that
        // the package is bad. A flaky UFS write, an I/O error on read-back, an interrupted snapshot
        // merge or a low-memory abort during verity all produce them, and all of those succeed on a
        // second attempt. AOSP retries them. Latching a whole build as refused because one handset
        // had one bad write would strand that handset until a new build number shipped.
        ErrorCode.NEW_ROOTFS_VERIFICATION_ERROR,
        ErrorCode.NEW_KERNEL_VERIFICATION_ERROR,
        ErrorCode.FILESYSTEM_VERIFIER_ERROR ->
            Outcome.Retry("the written slot failed verification")

        ErrorCode.DOWNLOAD_NEW_PARTITION_INFO_ERROR -> Outcome.Retry("new partition info error")
        ErrorCode.DOWNLOAD_WRITE_ERROR -> Outcome.Retry("write error")
        ErrorCode.USER_CANCELED -> Outcome.Retry("cancelled")

        ErrorCode.NOT_ENOUGH_SPACE -> Outcome.NeedsUser("not enough space")
        ErrorCode.DEVICE_CORRUPTED -> Outcome.Corrupted

        // Still Retry, because an unknown code may well be transient -- but the caller must bound
        // this. An unknown permanent failure retried forever re-downloads the whole payload on
        // every poll, which is what code 23 did before it was named above.
        else -> Outcome.Retry("unrecognised update_engine code $errorCode")
    }
}
