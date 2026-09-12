package watch.rist.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                KioskManager.provisionNow(ctx)
                val svc = Intent(ctx, PushService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc)
                else ctx.startService(svc)
                // Only on BOOT_COMPLETED: the fence and alarm stores are in credential-encrypted
                // prefs and are unreadable before first unlock.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    runCatching { GeofenceWatcher.reschedule(ctx.applicationContext) }
                    // A reboot wipes every AlarmManager alarm, so anything the user set is gone
                    // until this puts it back.
                    runCatching { Alarms.reschedule(ctx.applicationContext) }
                    // A phone switched off at home and on again abroad is the commonest way to
                    // arrive somewhere with the wrong clock.
                    runCatching {
                        AutoTimeZone.schedule(ctx.applicationContext)
                        AutoTimeZone.checkInBackground(ctx.applicationContext, force = true)
                    }
                }
            }
        }
    }
}
