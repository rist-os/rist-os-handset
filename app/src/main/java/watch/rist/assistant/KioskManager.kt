package watch.rist.assistant

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log

object KioskManager {

    // Must match the Provision app's admin receiver component.
    fun admin(context: Context): ComponentName =
        ComponentName(context, RistDeviceAdminReceiver::class.java)

    fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isDeviceOwner(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    fun configureLockTask(context: Context) {
        if (!isDeviceOwner(context)) return
        grantSelfPermissions(context)
        val dpm = dpm(context)
        val admin = admin(context)
        val packages = buildAllowlist(context)
        try {
            dpm.setLockTaskPackages(admin, packages)
            val features = desiredLockTaskFeatures(context)
            dpm.setLockTaskFeatures(admin, features)
            Log.i(
                TAG,
                "Lock task configured for ${packages.size} packages (features=$features, " +
                    "keyguard=${deviceHasCredential(context)}): ${packages.joinToString()}"
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "configureLockTask failed (not device owner?)", e)
        } catch (e: Exception) {
            Log.w(TAG, "configureLockTask failed", e)
        }
    }

    fun setAsDefaultLauncher(context: Context) {
        if (!isDeviceOwner(context)) return
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        runCatching {
            dpm(context).addPersistentPreferredActivity(
                admin(context), filter, ComponentName(context, MainActivity::class.java)
            )
        }.onFailure { Log.w(TAG, "setAsDefaultLauncher failed", it) }
    }

    fun grantSelfPermissions(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        val pkg = context.packageName
        val perms = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ADD_VOICEMAIL,
        )
        for (p in perms) {
            runCatching {
                dpm.setPermissionGrantState(
                    admin, pkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }.onFailure { Log.w(TAG, "grant $p failed", it) }
        }
        for (p in listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG)) {
            val held = runCatching {
                context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (held) {
                Log.i(TAG, "$p: granted")
            } else if (p == Manifest.permission.READ_SMS) {
                Log.e(
                    TAG,
                    "$p WAS REFUSED. The Device Owner grant of this hard-restricted permission did " +
                        "not land on this image, so Rist cannot read the message store at all. " +
                        "Asking about texts will now fail out loud (Uploader.smsUnreadableFailure) " +
                        "and the arrivals feed will say texts are not shown -- neither will " +
                        "pretend there are no messages. Diagnose with: adb shell dumpsys package " +
                        "$pkg | grep -A2 READ_SMS"
                )
            } else {
                Log.w(TAG, "$p was refused; that feature degrades rather than lies")
            }
        }
        // Must stay after the loop above: background location can only be granted once foreground location is held.
        GeofenceConsent.reassertGrant(context)
        for (mapsPkg in listOf(AppLauncher.PKG_MAPS, AppLauncher.PKG_MAPS_FDROID)) {
            for (p in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                runCatching {
                    // GRANTED locks the permission as policy-fixed; flipping back to DEFAULT keeps it granted but user-changeable.
                    dpm.setPermissionGrantState(
                        admin, mapsPkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                    dpm.setPermissionGrantState(
                        admin, mapsPkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                    )
                }.onFailure { Log.w(TAG, "grant maps $p failed", it) }
            }
        }
    }

    fun provisionNow(context: Context) {
        if (!isDeviceOwner(context)) return
        configureLockTask(context)
        setAsDefaultLauncher(context)
        applyUserRestrictions(context)
        Config.setKioskProvisionedFor(context, versionCode(context))
    }

    private fun applyUserRestrictions(context: Context) {
        val dpm = dpm(context)
        val admin = admin(context)
        for (r in KIOSK_RESTRICTIONS) {
            runCatching { dpm.addUserRestriction(admin, r) }
                .onFailure { Log.w(TAG, "could not set $r", it) }
        }
        val held = runCatching { dpm.getUserRestrictions(admin) }.getOrNull()
        if (held == null) {
            Log.w(TAG, "applied user restrictions but could not read them back to confirm")
            return
        }
        val landed = KIOSK_RESTRICTIONS.filter { held.getBoolean(it, false) }
        val missed = KIOSK_RESTRICTIONS.filter { !held.getBoolean(it, false) }
        Log.i(TAG, "user restrictions in force: ${landed.joinToString()}")
        if (missed.isNotEmpty()) {
            Log.w(TAG, "user restrictions NOT in force (unsupported on this build?): ${missed.joinToString()}")
        }
    }

    fun clearUserRestrictions(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        for (r in KIOSK_RESTRICTIONS) {
            runCatching { dpm.clearUserRestriction(admin, r) }
                .onFailure { Log.w(TAG, "could not clear $r", it) }
        }
        Log.i(TAG, "cleared the kiosk user restrictions")
    }

    private val KIOSK_RESTRICTIONS = arrayOf(
        android.os.UserManager.DISALLOW_SAFE_BOOT,
        android.os.UserManager.DISALLOW_ADD_USER,
        android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        android.os.UserManager.DISALLOW_UNINSTALL_APPS,
    )

    fun ensureConfigured(context: Context) {
        if (!isDeviceOwner(context)) return
        val vc = versionCode(context)
        if (vc != 0L && Config.kioskProvisionedFor(context) == vc && allowlistIsCurrent(context)) return
        Log.i(TAG, "kiosk policy stale (vc=$vc); provisioning")
        provisionNow(context)
    }

    private fun deviceHasCredential(context: Context): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }.getOrDefault(false)

    private fun desiredLockTaskFeatures(context: Context): Int =
        lockTaskFeaturesFor(deviceHasCredential(context))

    internal fun lockTaskFeaturesFor(hasCredential: Boolean): Int {
        var f = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
        if (hasCredential) {
            f = f or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
        }
        return f
    }

    internal fun featuresToPushOnCredentialChange(currentFeatures: Int, hasCredential: Boolean): Int? {
        val desired = lockTaskFeaturesFor(hasCredential)
        return if (currentFeatures == desired) null else desired
    }

    fun refreshKeyguardPolicy(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        try {
            val current = dpm.getLockTaskFeatures(admin)
            val push = featuresToPushOnCredentialChange(current, deviceHasCredential(context))
            if (push == null) {
                Log.i(TAG, "credential changed; lock task features already $current, nothing to push")
                return
            }
            dpm.setLockTaskFeatures(admin, push)
            Log.i(TAG, "credential changed; lock task features $current -> $push")
        } catch (e: SecurityException) {
            Log.w(TAG, "refreshKeyguardPolicy failed (not device owner?)", e)
        } catch (e: Exception) {
            Log.w(TAG, "refreshKeyguardPolicy failed", e)
        }
    }

    internal fun duressCredentialIsReachable(hasCredential: Boolean): Boolean =
        lockTaskFeaturesFor(hasCredential) and DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD != 0

    private fun allowlistIsCurrent(context: Context): Boolean = runCatching {
        val dpm = dpm(context)
        val admin = admin(context)
        dpm.getLockTaskPackages(admin).toSet() == buildAllowlist(context).toSet() &&
            dpm.getLockTaskFeatures(admin) == desiredLockTaskFeatures(context)
    }.getOrDefault(true)

    private fun versionCode(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    fun buildAllowlist(context: Context): Array<String> {
        val set = linkedSetOf(context.packageName)
        set += AppLauncher.resolvedPackages(context)
        // Handles ACTION_CALL (UserCallActivity); lock task refuses outgoing calls without it.
        set += "com.android.server.telecom"
        // Telephony UI; needed for the power-menu EMERGENCY button.
        set += "com.android.phone"
        // Wireless emergency alert dialog; both mainline package names, uninstalled ones are inert.
        set += "com.android.cellbroadcastreceiver"
        set += "com.google.android.cellbroadcastreceiver"
        // USB debugging authorization dialog.
        set += "com.android.systemui"
        return set.toTypedArray()
    }

    private const val TAG = "RistKiosk"
}
