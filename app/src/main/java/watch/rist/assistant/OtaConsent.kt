package watch.rist.assistant

import android.app.NotificationManager
import android.content.Context
import android.util.Log

object OtaConsent {

    private const val TAG = "RistOtaConsent"

    enum class Hold {
        NOT_APPROVED,
        APPROVED_OTHER_BUILD,
        METERED_NOT_APPROVED,
        NO_NETWORK,
    }

    sealed class Gate {
        object Go : Gate()
        data class Wait(val reason: Hold) : Gate()
    }

    /** Approval is checked before the network; keep that order. */
    fun decide(
        build: String,
        approvedBuild: String,
        meteredApprovedBuild: String,
        net: OtaNetwork.Suitability,
    ): Gate {
        if (build.isBlank()) return Gate.Wait(Hold.NOT_APPROVED)
        if (approvedBuild.isBlank()) return Gate.Wait(Hold.NOT_APPROVED)
        if (approvedBuild != build) return Gate.Wait(Hold.APPROVED_OTHER_BUILD)

        return when (net) {
            OtaNetwork.Suitability.None -> Gate.Wait(Hold.NO_NETWORK)
            OtaNetwork.Suitability.Unmetered -> Gate.Go
            OtaNetwork.Suitability.Metered ->
                if (meteredApprovedBuild == build) Gate.Go
                else Gate.Wait(Hold.METERED_NOT_APPROVED)
        }
    }

    data class Offer(
        val build: String,
        val payloadBytes: Long,
        val approved: Boolean,
        val meteredApproved: Boolean,
    )

    fun pendingOffer(ctx: Context): Offer? {
        val build = OtaState.offeredBuild(ctx)
        if (build.isBlank()) return null
        if (build == OtaState.readyBuild(ctx)) return null
        return Offer(
            build = build,
            payloadBytes = OtaState.offeredBytes(ctx),
            approved = OtaState.approvedBuild(ctx) == build,
            meteredApproved = OtaState.meteredApprovedBuild(ctx) == build,
        )
    }

    fun networkSuitability(ctx: Context): OtaNetwork.Suitability = OtaNetwork.current(ctx)

    fun approve(ctx: Context, build: String, allowMetered: Boolean) {
        val app = ctx.applicationContext
        OtaState.approveBuild(app, build, allowMetered)
        cancelOfferNotification(app)
        Log.i(TAG, "approved $build (metered=$allowMetered)")
    }

    sealed class Start {
        object Scheduled : Start()
        data class Refused(val reason: Hold) : Start()
    }

    fun startApprovedDownload(ctx: Context, build: String): Start {
        val app = ctx.applicationContext
        val gate = decide(
            build = build,
            approvedBuild = OtaState.approvedBuild(app),
            meteredApprovedBuild = OtaState.meteredApprovedBuild(app),
            net = OtaNetwork.current(app),
        )
        return when (gate) {
            is Gate.Wait -> {
                Log.i(TAG, "not starting $build: ${gate.reason}")
                Start.Refused(gate.reason)
            }
            Gate.Go -> {
                OtaScheduler.requestApprovedDownloadNow(app)
                Start.Scheduled
            }
        }
    }

    /** Decimal GB (10^9). */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "unknown size"
        val gb = bytes / 1_000_000_000.0
        if (gb >= 1.0) return String.format(java.util.Locale.US, "%.1f GB", gb)
        val mb = bytes / 1_000_000.0
        return String.format(java.util.Locale.US, "%.0f MB", mb)
    }

    fun cancelOfferNotification(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(OFFER_NOTIF_ID)
            nm.deleteNotificationChannel(OFFER_CHANNEL_ID)
        }.onFailure { Log.w(TAG, "could not clear a legacy offer notification", it) }
    }

    /** Must not collide with OtaService (1004), PushService (1002/1003), RecordService (1001). */
    private const val OFFER_NOTIF_ID = 1005
    private const val OFFER_CHANNEL_ID = "rist_ota_offer"
}
