package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast

object AppLauncher {

    const val PKG_CAMERA = "app.grapheneos.camera"
    const val PKG_GALLERY = "com.android.gallery3d"
    const val PKG_DIALER = "com.android.dialer"
    const val PKG_MESSAGING = "com.android.messaging"
    const val PKG_SETTINGS = "com.android.settings"
    const val PKG_CONTACTS = "com.android.contacts"
    const val PKG_MAPS = "app.organicmaps.web"
    const val PKG_MAPS_FDROID = "app.organicmaps"

    private fun cameraIntent(): Intent =
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)

    private fun galleryIntent(): Intent =
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)

    private fun dialerIntent(): Intent =
        Intent(Intent.ACTION_VIEW).setType("vnd.android.cursor.dir/calls")

    private fun messagingIntent(): Intent =
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)

    private fun settingsIntent(): Intent =
        Intent(Settings.ACTION_SETTINGS)

    fun launchCamera(ctx: Context) = launch(ctx, cameraIntent(), PKG_CAMERA)
    fun launchGallery(ctx: Context) = launch(ctx, galleryIntent(), PKG_GALLERY)
    fun launchDialer(ctx: Context) = launch(ctx, dialerIntent(), PKG_DIALER)
    fun launchMessaging(ctx: Context) = launch(ctx, messagingIntent(), PKG_MESSAGING)
    fun launchSettings(ctx: Context) = launch(ctx, settingsIntent(), PKG_SETTINGS)

    fun launchMaps(ctx: Context) {
        for (pkg in listOf(PKG_MAPS, PKG_MAPS_FDROID)) {
            val li = ctx.packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: continue
            try { ctx.startActivity(li); return } catch (e: Exception) { Log.w(TAG, "maps launch failed for $pkg", e) }
        }
        launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")), PKG_MAPS)
    }

    private fun dismissNotificationsFor(pkg: String) {
        val group = when (pkg) {
            PKG_DIALER -> setOf(PKG_DIALER, "com.android.phone", "com.android.server.telecom")
            PKG_MAPS, PKG_MAPS_FDROID -> setOf(PKG_MAPS, PKG_MAPS_FDROID)
            else -> setOf(pkg)
        }
        runCatching { RistNotificationListener.dismissFor(group) }
            .onFailure { Log.w(TAG, "could not dismiss notifications for $pkg", it) }
    }

    private fun launch(ctx: Context, intent: Intent, fallbackPkg: String) {
        dismissNotificationsFor(fallbackPkg)

        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            Log.w(TAG, "Implicit launch failed for $intent, trying fallback $fallbackPkg", e)
        }
        val fb = ctx.packageManager.getLaunchIntentForPackage(fallbackPkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (fb != null) {
            try {
                ctx.startActivity(fb)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Fallback launch failed for $fallbackPkg", e)
            }
        }
        Toast.makeText(ctx, "App unavailable", Toast.LENGTH_SHORT).show()
        Log.w(TAG, "No handler for $intent and no launch intent for $fallbackPkg")
    }

    fun resolvedPackages(ctx: Context): List<String> = cachedPackages
        ?: resolvePackages(ctx).also { cachedPackages = it }

    @Volatile private var cachedPackages: List<String>? = null

    private fun resolvePackages(ctx: Context): List<String> {
        val pm = ctx.packageManager
        fun resolve(intent: Intent, fallback: String): String {
            val pkg = pm.resolveActivity(intent, 0)?.activityInfo?.packageName
            return if (!pkg.isNullOrBlank() && pkg != "android") pkg else fallback
        }
        return listOf(
            resolve(cameraIntent(), PKG_CAMERA),
            resolve(galleryIntent(), PKG_GALLERY),
            resolve(dialerIntent(), PKG_DIALER),
            resolve(messagingIntent(), PKG_MESSAGING),
            resolve(settingsIntent(), PKG_SETTINGS),
            PKG_CONTACTS,
            PKG_MAPS,
            PKG_MAPS_FDROID,
        ).distinct()
    }

    private const val TAG = "RistAppLauncher"
}
