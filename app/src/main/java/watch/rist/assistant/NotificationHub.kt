package watch.rist.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager

object NotificationHub {

    const val ACTION_COUNTS_CHANGED = "watch.rist.assistant.action.NOTIF_COUNTS_CHANGED"
    private const val TAG = "RistNotif"
    private const val SETTING = "enabled_notification_listeners"

    @Volatile private var counts: Map<String, Int> = emptyMap()

    @Volatile private var voicemail: Boolean = false

    fun voicemailPosted(): Boolean = voicemail

    fun update(ctx: Context, fresh: Map<String, Int>, voicemailPosted: Boolean = false) {
        counts = fresh
        voicemail = voicemailPosted
        // Set-only here: the dialer cancels its voicemail notification on open while the message still waits.
        if (voicemailPosted) Config.setCarrierVoicemailWaiting(ctx.applicationContext, true)
        LocalBroadcastManager.getInstance(ctx.applicationContext)
            .sendBroadcast(Intent(ACTION_COUNTS_CHANGED))
    }

    fun total(ctx: Context): Int =
        CommsFeedView.waitingCount(ctx) + camera() + gallery() + maps() + settings()

    fun rawTotal(): Int = counts.values.sum()

    fun countFor(vararg keys: String): Int = counts.entries
        .filter { (pkg, _) -> keys.any { k -> pkg.equals(k, true) || pkg.contains(k, true) } }
        .sumOf { it.value }

    fun phone(ctx: Context): Int =
        maxOf(phone(), if (CarrierVoicemail.unacknowledged(ctx)) 1 else 0)

    fun phone()    = countFor(AppLauncher.PKG_DIALER, "dialer", "telecom")
    fun messages() = countFor(AppLauncher.PKG_MESSAGING, "messaging", "sms", "mms")
    fun camera()   = countFor(AppLauncher.PKG_CAMERA)
    fun gallery()  = countFor(AppLauncher.PKG_GALLERY, "gallery", "photos")
    fun maps()     = countFor(AppLauncher.PKG_MAPS, AppLauncher.PKG_MAPS_FDROID, "organicmaps")
    fun settings() = countFor(AppLauncher.PKG_SETTINGS)

    fun applyBadge(badge: TextView?, count: Int) {
        badge ?: return
        if (count <= 0) { badge.visibility = View.GONE; return }
        badge.text = if (count > 9) "9+" else count.toString()
        badge.visibility = View.VISIBLE
    }

    @Volatile var listenerConnected = false

    fun ensureListenerEnabled(ctx: Context) = runCatching {
        if (listenerConnected) return@runCatching
        val app = ctx.applicationContext
        val component = ComponentName(app, RistNotificationListener::class.java)

        val granted = runCatching {
            val nm = app.getSystemService(android.app.NotificationManager::class.java)
            android.app.NotificationManager::class.java.getMethod(
                "setNotificationListenerAccessGranted",
                ComponentName::class.java, Boolean::class.javaPrimitiveType
            ).invoke(nm, component, true)
            Log.i(TAG, "listener access granted via NotificationManager")
            true
        }.getOrElse { false }

        if (!granted) {
            val me = component.flattenToString()
            val cr = app.contentResolver
            val others = Settings.Secure.getString(cr, SETTING).orEmpty()
                .split(':').filter { it.isNotBlank() && it != me }
            // NotificationManagerService only rebinds on a value change: remove, then re-add on a delay.
            Settings.Secure.putString(cr, SETTING, others.joinToString(":"))
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching {
                    Settings.Secure.putString(cr, SETTING, (others + me).joinToString(":"))
                    android.service.notification.NotificationListenerService.requestRebind(component)
                    Log.i(TAG, "listener re-added to secure allowlist (delayed toggle)")
                }
            }, 400L)
        }
        runCatching {
            android.service.notification.NotificationListenerService.requestRebind(component)
        }
    }.onFailure { Log.w(TAG, "could not self-enable notification listener", it) }
}
