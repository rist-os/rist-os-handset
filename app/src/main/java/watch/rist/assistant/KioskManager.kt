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

    /** A headset's voice request comes to Rist with no chooser, whatever else could take it. */
    fun setAsDefaultForHeadsetTalk(context: Context) {
        if (!isDeviceOwner(context)) return
        val filter = IntentFilter(Intent.ACTION_VOICE_COMMAND).apply { addCategory(Intent.CATEGORY_DEFAULT) }
        runCatching {
            dpm(context).addPersistentPreferredActivity(
                admin(context), filter, ComponentName(context, HeadsetTalkActivity::class.java)
            )
        }.onFailure { Log.w(TAG, "could not make Rist the handler for headset voice requests", it) }
    }

    /** Full browsers on the image. Hidden, not removed: the web engine Rist uses is separate. */
    internal val BROWSERS = listOf("app.vanadium.browser")

    /**
     * Web links reach [LinkActivity], which opens only the camera's QR result. The browser is
     * hidden rather than outranked: while a browser holds the browser role a web link goes
     * straight to it and no preferred activity is consulted, and taking the role needs a
     * permission this app's signature does not carry. Hidden, it also cannot open in the moment
     * after an update when lock task is not yet back.
     */
    fun setAsDefaultForLinks(context: Context, always: Boolean = false) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        var acted = always
        for (pkg in BROWSERS) runCatching {
            if (!dpm.isApplicationHidden(admin, pkg)) {
                acted = true
                Log.i(TAG, "hiding $pkg: ${dpm.setApplicationHidden(admin, pkg, true)}")
            }
        }.onFailure { Log.w(TAG, "could not hide $pkg", it) }
        // Runs on every return to home; the preference is rewritten only when something changed.
        if (!acted) return
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("http")
            addDataScheme("https")
        }
        runCatching {
            dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, LinkActivity::class.java))
        }.onFailure { Log.w(TAG, "could not make Rist the handler for links", it) }
    }

    /** Runtime permissions the Device Owner grants itself (`grantSelfPermissions`). */
    private val SELF_GRANTED = listOf(
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
        // Contact sync writes the owner's backend contacts into the address book, so caller
        // ID names them with no network.
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.ADD_VOICEMAIL,
        // Video calls: a kiosk cannot count on a runtime dialog, and a call page that is
        // refused a headset or a microphone fails without a word.
        Manifest.permission.BLUETOOTH_CONNECT,
        // "Pair my headphones": finding devices in pairing mode.
        Manifest.permission.BLUETOOTH_SCAN,
    )

    /** READ_SMS and READ_CALL_LOG are hard-restricted and may be refused on an image; the rest
     *  always land, so one missing means the grants have not run for this build's manifest. */
    private fun grantsHeld(context: Context): Boolean = runCatching {
        SELF_GRANTED.filterNot {
            it == Manifest.permission.READ_SMS || it == Manifest.permission.READ_CALL_LOG
        }.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }.getOrDefault(true)

    fun grantSelfPermissions(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        val pkg = context.packageName
        for (p in SELF_GRANTED) {
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
        setAsDefaultForLinks(context, always = true)
        setAsDefaultForHeadsetTalk(context)
        applyUserRestrictions(context)
        Config.setKioskProvisionedFor(context, versionCode(context))
    }

    private fun applyUserRestrictions(context: Context) {
        val dpm = dpm(context)
        val admin = admin(context)
        // Developer mode (DeveloperMode.kt) keeps USB debugging open; a re-provision after an
        // update must not close it again behind the owner's back.
        val wanted = kioskRestrictions(BuildVariant.isPublic(), DeveloperMode.debuggingLifted(context))
        for (r in wanted) {
            runCatching { dpm.addUserRestriction(admin, r) }
                .onFailure { Log.w(TAG, "could not set $r", it) }
        }
        for (r in ALL_RESTRICTIONS.filter { it !in wanted }) {
            runCatching { dpm.clearUserRestriction(admin, r) }
                .onFailure { Log.w(TAG, "could not clear $r", it) }
        }
        val held = runCatching { dpm.getUserRestrictions(admin) }.getOrNull()
        if (held == null) {
            Log.w(TAG, "applied user restrictions but could not read them back to confirm")
            return
        }
        val landed = wanted.filter { held.getBoolean(it, false) }
        val missed = wanted.filter { !held.getBoolean(it, false) }
        Log.i(TAG, "user restrictions in force: ${landed.joinToString()}")
        if (missed.isNotEmpty()) {
            Log.w(TAG, "user restrictions NOT in force (unsupported on this build?): ${missed.joinToString()}")
        }
    }

    fun clearUserRestrictions(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        for (r in ALL_RESTRICTIONS) {
            runCatching { dpm.clearUserRestriction(admin, r) }
                .onFailure { Log.w(TAG, "could not clear $r", it) }
        }
        Log.i(TAG, "cleared the kiosk user restrictions")
    }

    private val BASE_RESTRICTIONS = listOf(
        android.os.UserManager.DISALLOW_SAFE_BOOT,
        android.os.UserManager.DISALLOW_ADD_USER,
        android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        android.os.UserManager.DISALLOW_UNINSTALL_APPS,
    )

    private val ALL_RESTRICTIONS = BASE_RESTRICTIONS + android.os.UserManager.DISALLOW_DEBUGGING_FEATURES

    /**
     * Public builds also close adb and Developer options; dev builds keep them for push.sh. On a
     * public build, Developer mode ([developerMode]) is the one way to keep them open.
     */
    internal fun kioskRestrictions(publicBuild: Boolean, developerMode: Boolean = false): List<String> =
        if (publicBuild && !developerMode) ALL_RESTRICTIONS else BASE_RESTRICTIONS

    /** Sets or clears `DISALLOW_DEBUGGING_FEATURES` alone. False when it could not be changed. */
    fun setDebuggingRestriction(context: Context, restricted: Boolean): Boolean {
        if (!isDeviceOwner(context)) return false
        val dpm = dpm(context)
        val admin = admin(context)
        val r = android.os.UserManager.DISALLOW_DEBUGGING_FEATURES
        return runCatching {
            if (restricted) dpm.addUserRestriction(admin, r) else dpm.clearUserRestriction(admin, r)
            true
        }.onFailure { Log.w(TAG, "could not ${if (restricted) "set" else "clear"} $r", it) }
            .getOrDefault(false)
    }

    fun ensureConfigured(context: Context) {
        if (!isDeviceOwner(context)) return
        runCatching { DeveloperMode.enforce(context) }
            .onFailure { Log.w(TAG, "developer mode check failed", it) }
        val vc = versionCode(context)
        // Checked every time: a browser shown again or restored later would take links back.
        setAsDefaultForLinks(context)
        if (vc != 0L && Config.kioskProvisionedFor(context) == vc && allowlistIsCurrent(context) &&
            grantsHeld(context)) return
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
        // A removed screen lock ends Developer mode at once.
        runCatching { DeveloperMode.enforce(context) }
            .onFailure { Log.w(TAG, "developer mode check failed", it) }
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
        // The system photo picker behind "Choose from photos". Lock task blocks any activity
        // outside this list, so without these the picker simply never appears. Both mainline
        // spellings; an uninstalled one is inert, like the cell-broadcast pair above.
        set += "com.android.photopicker"
        set += "com.android.providers.media.module"
        set += "com.google.android.providers.media.module"
        return set.toTypedArray()
    }

    private const val TAG = "RistKiosk"
}
