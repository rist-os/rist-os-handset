package watch.rist.assistant

object OtaPolicy {

    data class LocalBuild(
        val device: String,
        val build: String,
        val timestampSeconds: Long,
    ) {
        companion object {
            fun current(): LocalBuild = LocalBuild(
                device = android.os.Build.DEVICE ?: "",
                build = android.os.Build.VERSION.INCREMENTAL ?: "",
                timestampSeconds = android.os.Build.TIME / 1000L,
            )
        }
    }

    enum class Refusal {
        WRONG_DEVICE,
        WRONG_CHANNEL,
        ROLLBACK,
        NO_INCREMENTAL_PATH,
        UNKNOWN_LOCAL_BUILD,
    }

    sealed class Decision {
        object UpToDate : Decision()
        data class Apply(val manifest: OtaManifest) : Decision()
        data class Refuse(val reason: Refusal, val detail: String) : Decision()
    }

    fun decide(m: OtaManifest, local: LocalBuild, askedChannel: String): Decision {
        if (local.build.isBlank() || local.timestampSeconds <= 0L) {
            return Decision.Refuse(Refusal.UNKNOWN_LOCAL_BUILD,
                "build='${local.build}' timestamp=${local.timestampSeconds}")
        }
        if (!m.device.equals(local.device, ignoreCase = true)) {
            return Decision.Refuse(Refusal.WRONG_DEVICE, "${m.device} != ${local.device}")
        }
        if (!m.channel.equals(askedChannel, ignoreCase = true)) {
            return Decision.Refuse(Refusal.WRONG_CHANNEL, "${m.channel} != $askedChannel")
        }

        // Before the timestamp check, so a device already on this build reports up to date, not rollback.
        if (m.build == local.build) return Decision.UpToDate

        // Strictly newer; equality is refused deliberately.
        if (m.timestampSeconds <= local.timestampSeconds) {
            return Decision.Refuse(Refusal.ROLLBACK,
                "offered ${m.timestampSeconds} <= running ${local.timestampSeconds}")
        }

        val from = m.incrementalFrom
        if (from != null && from != local.build) {
            return Decision.Refuse(Refusal.NO_INCREMENTAL_PATH, "$from != ${local.build}")
        }

        return Decision.Apply(m)
    }
}
