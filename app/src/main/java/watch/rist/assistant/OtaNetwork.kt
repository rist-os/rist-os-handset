package watch.rist.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

object OtaNetwork {

    private const val TAG = "RistOtaNetwork"

    sealed class Suitability {
        object Unmetered : Suitability()
        object Metered : Suitability()
        object None : Suitability()
    }

    fun classify(connected: Boolean, notMetered: Boolean): Suitability = when {
        !connected -> Suitability.None
        notMetered -> Suitability.Unmetered
        else -> Suitability.Metered
    }

    fun current(ctx: Context): Suitability {
        val cm = runCatching {
            ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull() ?: return Suitability.None

        val caps = runCatching {
            val active = cm.activeNetwork ?: return Suitability.None
            cm.getNetworkCapabilities(active)
        }.onFailure {
            Log.w(TAG, "cannot read network capabilities; treating as offline", it)
        }.getOrNull() ?: return Suitability.None

        val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return classify(connected, notMetered)
    }

    fun describe(s: Suitability): String = when (s) {
        Suitability.Unmetered -> "unmetered"
        Suitability.Metered -> "metered"
        Suitability.None -> "no network"
    }
}
