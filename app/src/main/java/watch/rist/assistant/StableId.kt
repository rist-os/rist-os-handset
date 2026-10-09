package watch.rist.assistant

import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * A device identifier that survives a factory reset or a reflash, so the same phone is the same
 * phone to the server. `device_id` (ANDROID_ID) changes on every reset; tokens stay bound to it.
 *
 * stable_id = hex SHA-256 of [SALT] + the hardware serial. The raw serial never leaves this object:
 * it is not stored, sent or logged. Only the digest goes on the wire.
 *
 * Reading the serial needs READ_PRIVILEGED_PHONE_STATE (platform-signed priv-app, allowlisted in
 * aosp/sysconfig) or device-owner status plus READ_PHONE_STATE. Without either, there is no stable_id
 * and only a reason code is logged.
 */
object StableId {

    private const val TAG = "RistStableId"

    /**
     * Fixed and app-specific, so the digest is useless to anyone else holding the same serial. The
     * server checks the shape (64 lowercase hex); this exact prefix is the agreed contract.
     */
    internal const val SALT = "rist-stable-id-v1:"

    /** Why there is no stable_id. Logged as-is; never carries the serial. */
    internal enum class Unavailable { NO_PERMISSION, NO_SERIAL, ERROR }

    @Volatile private var cached: String? = null

    /** Null for a blank or placeholder serial. */
    internal fun derive(serial: String?): String? {
        val s = serial?.trim().orEmpty()
        if (s.isEmpty() || s.equals(Build.UNKNOWN, ignoreCase = true)) return null
        val digest = MessageDigest.getInstance("SHA-256").digest((SALT + s).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** The stable id, or null with a reason code logged. [readSerial] is replaceable for tests. */
    @android.annotation.SuppressLint("HardwareIds", "MissingPermission")
    internal fun read(readSerial: () -> String? = { Build.getSerial() }): String? {
        cached?.let { return it }
        val serial = try {
            readSerial()
        } catch (e: SecurityException) {
            Log.w(TAG, "stable_id unavailable: ${Unavailable.NO_PERMISSION}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "stable_id unavailable: ${Unavailable.ERROR} (${e.javaClass.simpleName})")
            return null
        }
        val id = derive(serial)
        if (id == null) {
            Log.w(TAG, "stable_id unavailable: ${Unavailable.NO_SERIAL}")
            return null
        }
        cached = id
        return id
    }

    internal fun resetForTest() {
        cached = null
    }
}
