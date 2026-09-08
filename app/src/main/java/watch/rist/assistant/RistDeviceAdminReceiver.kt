package watch.rist.assistant

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class RistDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled; isDeviceOwner=${KioskManager.isDeviceOwner(context)}")
        KioskManager.provisionNow(context)
    }

    override fun onPasswordChanged(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, user)
        Log.i(TAG, "Screen lock changed; refreshing keyguard policy")
        KioskManager.refreshKeyguardPolicy(context)
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        Log.d(TAG, "Lock task entering: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.d(TAG, "Lock task exiting")
    }

    private companion object {
        private const val TAG = "RistDeviceAdmin"
    }
}
