package watch.rist.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * What the person is told when something does not work. A failure the person cannot fix is said
 * as "<Feature> is currently unavailable." and nothing more: no codes, causes or apologies. Only
 * when the person can make it work again are they told what to do. The cause goes to the log.
 */
object Unavailable {

    const val ASSISTANT = "Rist Assistant is currently unavailable."
    const val VOICEMAIL = "Voicemail is currently unavailable."
    const val UPDATES = "Updates are currently unavailable."
    const val DIRECTIONS = "Directions are currently unavailable."
    const val MESSAGES = "Messages are currently unavailable."
    const val TEXTING = "Texting is currently unavailable."
    const val CALLING = "Calling is currently unavailable."
    const val PHOTOS = "Photos are currently unavailable."
    const val PAIRING = "Pairing is currently unavailable."
    const val PLAYBACK = "Playback is currently unavailable."
    const val PODCASTS = "Podcasts are currently unavailable."
    const val PAYMENT_PAGE = "The payment page is currently unavailable."
    const val DEVELOPER_MODE = "Developer mode is currently unavailable."
    const val SETTINGS = "Settings are currently unavailable."
    /** Short enough for a tile. */
    const val TILE = "Currently unavailable"

    /** The phone has no network: the one transport failure the person can fix. */
    const val NO_NETWORK = "Connect to Wi-Fi or mobile data."
    /** No assistant service on this phone: the person pairs it. */
    const val NOT_PAIRED = "Connect this phone to Rist Assistant in Settings."
    /** The address the person typed in Settings is not a web address. */
    const val BAD_ADDRESS = "Check the assistant address in Settings."
    const val PAIR_AGAIN = "This phone is no longer connected to your account. Pair it again in Settings."
    const val REMOVED = "This phone was removed from your account. Pair it again in Settings."
    const val TOO_LONG = "That recording was too long. Try a shorter one."

    /** [feature]'s line, or the no-network line when the phone is offline. */
    fun orOffline(ctx: Context, feature: String): String =
        if (offline(ctx)) NO_NETWORK else feature

    /** The person-fixable case only: no network at all. */
    internal fun pick(offline: Boolean, feature: String): String = if (offline) NO_NETWORK else feature

    /** True only when the phone has no network at all; any doubt reads as online. */
    fun offline(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(net) ?: return false
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    /** A line from the server, shown as a sentence: capital first, closing stop. */
    fun sentence(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return t
        val head = t.first().uppercaseChar() + t.substring(1)
        return if (head.last() in ".!?") head else "$head."
    }
}
