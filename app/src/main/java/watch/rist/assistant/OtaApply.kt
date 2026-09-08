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
        const val DOWNLOAD_STATE_INITIALIZATION_ERROR = 20
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

        ErrorCode.NOT_ENOUGH_SPACE -> Outcome.NeedsUser("not enough space")
        ErrorCode.DEVICE_CORRUPTED -> Outcome.Corrupted

        else -> Outcome.Retry("unrecognised update_engine code $errorCode")
    }
}
