package watch.rist.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/**
 * Developer mode: USB debugging on a public image, for an account the backend allows (schema v28,
 * `developer_mode` on every turn and wake).
 *
 * Public images keep adb and Developer options shut with `DISALLOW_DEBUGGING_FEATURES`. Developer
 * mode lifts only that restriction, and only while all of these hold:
 *  - the backend says this account may (`developer_mode` true; a phone never told assumes not),
 *  - the phone has a secure lock screen,
 *  - Rist is the Device Owner,
 * and it is turned on only right after the user confirms the device credential. When any of the
 * first three stops holding (the backend says false, the lock screen is removed), it turns itself
 * off: adb off, Developer options off, the restriction back.
 *
 * Dev images never set the restriction, so there is nothing to lift and the row is not shown.
 */
object DeveloperMode {

    private const val TAG = "RistDeveloperMode"
    private const val PREFS = "rist.developer.mode"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_ON = "on"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The backend's last word: this account may use Developer mode. */
    fun isAllowed(ctx: Context): Boolean =
        runCatching { prefs(ctx).getBoolean(KEY_ALLOWED, false) }.getOrDefault(false)

    /** The user turned Developer mode on and it has not been turned off since. */
    fun isOn(ctx: Context): Boolean =
        runCatching { prefs(ctx).getBoolean(KEY_ON, false) }.getOrDefault(false)

    // ── the gates, pure ────────────────────────────────────────────────────────────────────────

    /** The Settings row exists only on a public image, for an allowed account. */
    internal fun rowVisible(publicBuild: Boolean, allowed: Boolean): Boolean = publicBuild && allowed

    /** Every condition for turning it on, short of the credential check that comes right after. */
    internal fun mayTurnOn(publicBuild: Boolean, allowed: Boolean, secure: Boolean, deviceOwner: Boolean): Boolean =
        publicBuild && allowed && secure && deviceOwner

    /** It stays on only while the account is allowed and the phone has a secure lock screen. */
    internal fun mustTurnOff(on: Boolean, allowed: Boolean, secure: Boolean): Boolean =
        on && !(allowed && secure)

    /** Whether the Device Owner should hold `DISALLOW_DEBUGGING_FEATURES` right now. */
    internal fun debuggingLifted(publicBuild: Boolean, on: Boolean, allowed: Boolean, secure: Boolean): Boolean =
        publicBuild && on && allowed && secure

    /** The state after one message: [present] false means nothing was said, so [current] stays. */
    internal fun next(current: Boolean, present: Boolean, value: Boolean): Boolean =
        if (present) value else current

    // ── the device ─────────────────────────────────────────────────────────────────────────────

    fun isDeviceSecure(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }.getOrDefault(false)

    fun rowVisible(ctx: Context): Boolean = rowVisible(BuildVariant.isPublic(), isAllowed(ctx))

    fun mayTurnOn(ctx: Context): Boolean = mayTurnOn(
        BuildVariant.isPublic(), isAllowed(ctx), isDeviceSecure(ctx), KioskManager.isDeviceOwner(ctx))

    /** True while the restriction should be off; [KioskManager] asks this before re-applying it. */
    fun debuggingLifted(ctx: Context): Boolean =
        debuggingLifted(BuildVariant.isPublic(), isOn(ctx), isAllowed(ctx), isDeviceSecure(ctx))

    /** From a turn's final response. */
    fun onResponse(ctx: Context, present: Boolean, value: Boolean) = take(ctx, present, value, "turn")

    /** From a wake answer. */
    fun onWake(ctx: Context, present: Boolean, value: Boolean) = take(ctx, present, value, "wake")

    private fun take(ctx: Context, present: Boolean, value: Boolean, from: String) {
        val was = isAllowed(ctx)
        val now = next(was, present, value)
        if (now != was) {
            runCatching { prefs(ctx).edit().putBoolean(KEY_ALLOWED, now).commit() }
                .onFailure { Log.w(TAG, "could not store the backend's word", it) }
            Log.i(TAG, "developer mode ${if (now) "allowed" else "not allowed"} for this account (from the $from)")
        }
        enforce(ctx)
    }

    /**
     * Called after the user confirmed the device credential. Re-checks every gate, then lifts the
     * restriction and turns adb on. False (and nothing changed) when a gate does not hold.
     */
    fun turnOn(ctx: Context): Boolean {
        if (!mayTurnOn(ctx)) {
            Log.w(TAG, "refused: allowed=${isAllowed(ctx)} secure=${isDeviceSecure(ctx)} " +
                "public=${BuildVariant.isPublic()} owner=${KioskManager.isDeviceOwner(ctx)}")
            return false
        }
        // Stored first: a re-provision racing this must not put the restriction back.
        prefs(ctx).edit().putBoolean(KEY_ON, true).commit()
        if (!KioskManager.setDebuggingRestriction(ctx, restricted = false)) {
            prefs(ctx).edit().putBoolean(KEY_ON, false).commit()
            return false
        }
        putGlobal(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        putGlobal(ctx, Settings.Global.ADB_ENABLED, 1)
        Log.i(TAG, "developer mode ON: USB debugging allowed")
        return true
    }

    /** Off: adb and Developer options off, and on a public image the restriction back. */
    fun turnOff(ctx: Context, why: String) {
        prefs(ctx).edit().putBoolean(KEY_ON, false).commit()
        if (!BuildVariant.isPublic()) return
        // Before the restriction: once it is set, these writes are refused (and adb is off anyway).
        putGlobal(ctx, Settings.Global.ADB_ENABLED, 0)
        putGlobal(ctx, ADB_WIFI_ENABLED, 0)
        putGlobal(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        KioskManager.setDebuggingRestriction(ctx, restricted = true)
        Log.i(TAG, "developer mode OFF ($why): USB debugging closed")
    }

    /**
     * Turns it off if a gate stopped holding. Cheap; called on every turn, wake, lock screen change,
     * kiosk check and Settings visit.
     */
    fun enforce(ctx: Context) {
        val on = isOn(ctx)
        if (!on) return
        val allowed = isAllowed(ctx)
        val secure = isDeviceSecure(ctx)
        if (mustTurnOff(on, allowed, secure)) {
            turnOff(ctx, if (!allowed) "the account is no longer allowed" else "the screen lock was removed")
        }
    }

    /** True while USB debugging is actually blocked on this phone (what the row reports). */
    fun restrictionInForce(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(UserManager::class.java)
            ?.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES) == true
    }.getOrDefault(true)

    private fun putGlobal(ctx: Context, key: String, value: Int) {
        runCatching { Settings.Global.putInt(ctx.contentResolver, key, value) }
            .onFailure { Log.w(TAG, "could not set $key=$value", it) }
    }

    // Settings.Global.ADB_WIFI_ENABLED is @hide.
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
}
