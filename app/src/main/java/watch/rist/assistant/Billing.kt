package watch.rist.assistant

import android.content.Context
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * A lapsed subscription (HTTP 402). It means "pay" and nothing else: the token, the enrolment and
 * everything else on the phone are kept, and the next turn the backend serves ends it. The lapse
 * recorded here only paints the notice and its Update payment button; it never blocks a turn.
 */
object Billing {

    private const val TAG = "RistBilling"

    const val PAYMENT_REQUIRED = 402

    internal const val DEFAULT_RENEW_URL = "ristmobile.com"
    internal const val DEFAULT_PORTAL_PATH = "/v1/billing/portal"
    internal const val PORTAL_HOST = "billing.stripe.com"

    data class Lapse(val reason: String, val renewUrl: String, val portalPath: String)

    internal fun lapseFrom(reason: String?, renewUrl: String?, portalPath: String?): Lapse = Lapse(
        reason = reason?.trim()?.takeIf { it.isNotEmpty() } ?: "lapsed",
        renewUrl = renewUrl?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_RENEW_URL,
        portalPath = portalPath?.trim()?.takeIf { it.startsWith("/") } ?: DEFAULT_PORTAL_PATH,
    )

    internal fun lapseFrom(resp: okhttp3.Response): Lapse = lapseFrom(
        resp.header("X-Rist-Billing"),
        resp.header("X-Rist-Renew-Url"),
        resp.header("X-Rist-Billing-Portal"),
    )

    /** Said only when the 402's own body cannot be read; the backend's line is always preferred. */
    fun fallbackLine(renewUrl: String = DEFAULT_RENEW_URL): String =
        "Your Rist Assistant subscription has ended — renew at ${renewUrl.ifBlank { DEFAULT_RENEW_URL }}."

    fun onLapsed(ctx: Context, lapse: Lapse) {
        if (Config.billingLapse(ctx).isEmpty()) Log.w(TAG, "402: subscription ${lapse.reason}")
        Config.setBillingLapse(ctx, lapse.reason, lapse.renewUrl, lapse.portalPath)
    }

    /** The numbers the backend still dials for a lapsed account (emergency_turn.py), with a 200. */
    internal val EMERGENCY_DIALS = setOf("911", "112", "988", "933")

    /** A served reply proves the account is paid, except the emergency dial let through its 402. */
    internal fun provesPaid(ctx: Context, resp: rist.v1.DeviceResponse): Boolean {
        if (!resp.hasComms()) return true
        val action = resp.comms.action.trim().lowercase()
        if (action != "dial" && action != "call") return true
        val digits = resp.comms.number.filter { it.isDigit() }
        return digits !in EMERGENCY_DIALS && !DeviceCommands.isEmergency(ctx, resp.comms.number)
    }

    fun onServed(ctx: Context, resp: rist.v1.DeviceResponse? = null) {
        if (Config.billingLapse(ctx).isEmpty()) return
        if (resp != null && !provesPaid(ctx, resp)) {
            Log.i(TAG, "an emergency dial was let through; the lapse stands")
            return
        }
        Log.i(TAG, "served again; the lapse is over")
        Config.clearBillingLapse(ctx)
    }

    fun lapse(ctx: Context): Lapse? {
        val reason = Config.billingLapse(ctx)
        if (reason.isEmpty()) return null
        return lapseFrom(reason, Config.billingRenewUrl(ctx), Config.billingPortalPath(ctx))
    }

    fun notice(ctx: Context): String? = lapse(ctx)?.let { fallbackLine(it.renewUrl) }

    fun offersPayment(ctx: Context): Boolean = lapse(ctx) != null && !Config.billingNoPortal(ctx)

    internal fun portalUrl(backendUrl: String, path: String): String? {
        val base = backendUrl.trim().trimEnd('/').let {
            if (it.endsWith("/v1/device")) it.removeSuffix("/v1/device") else it
        }
        if (!base.startsWith("https://") && !base.startsWith("http://")) return null
        return (base + path).toHttpUrlOrNull()?.toString()
    }

    /** Only Stripe's hosted page, over https, and only a link this endpoint just handed over. */
    internal fun openablePortal(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.host.lowercase() != PORTAL_HOST || parsed.port != 443) return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        return SiteLock.openable(parsed.toString())
    }

    sealed class Portal {
        data class Open(val url: String) : Portal()
        object NoSubscription : Portal()
        object Unauthorised : Portal()
        object Revoked : Portal()
        object Unavailable : Portal()
    }

    fun explain(p: Portal): String = when (p) {
        is Portal.Open -> ""
        Portal.NoSubscription -> "There is no subscription on this account to update."
        Portal.Unauthorised -> "This device needs to be paired again before it can open the payment page."
        Portal.Revoked -> "This device's access was turned off. Pair it again in Settings."
        Portal.Unavailable -> "Couldn't open the payment page — try again in a minute."
    }

    internal fun classifyPortal(code: Int, body: String): Portal = when {
        code in 200..299 -> runCatching { JSONObject(body).optString("url") }.getOrDefault("")
            .let { openablePortal(it) }?.let { Portal.Open(it) } ?: Portal.Unavailable
        code == 404 -> Portal.NoSubscription
        code == 401 -> Portal.Unauthorised
        code == 403 -> Portal.Revoked
        else -> Portal.Unavailable
    }

    /** One-time and short-lived: fetched when the button is pressed, opened at once, never kept. Blocking. */
    fun fetchPortal(ctx: Context, http: OkHttpClient = Uploader.sharedClient()): Portal {
        val path = lapse(ctx)?.portalPath ?: DEFAULT_PORTAL_PATH
        val url = portalUrl(Config.backendUrl(ctx), path) ?: return Portal.Unavailable
        val bearer = Uploader.bearer(ctx) ?: return Portal.Unauthorised
        val out = runCatching {
            val req = Request.Builder().url(url)
                .post(ByteArray(0).toRequestBody(null))
                .header("Authorization", bearer)
                .header("X-Rist-Device", Config.deviceId(ctx))
                .build()
            http.newCall(req).execute().use { resp -> classifyPortal(resp.code, resp.body?.string().orEmpty()) }
        }.onFailure { Log.w(TAG, "portal request failed: ${it.javaClass.simpleName}") }
            .getOrDefault(Portal.Unavailable)
        when (out) {
            Portal.NoSubscription -> Config.setBillingNoPortal(ctx, true)
            Portal.Unauthorised -> Enrolment.onCredentialDead(ctx)
            Portal.Revoked -> Enrolment.onRevoked(ctx)
            else -> Unit
        }
        Log.i(TAG, "portal -> ${out.javaClass.simpleName}")
        return out
    }
}
