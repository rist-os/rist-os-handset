package watch.rist.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Installing a new APK cancels every PendingIntent this package holds, so an app update
        // silently disarms the alarms exactly the way a reboot does. Same store, same repair.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { Alarms.reschedule(context.applicationContext) }
                .onFailure { Log.w(TAG, "could not re-arm alarms after the update", it) }
            runCatching { AutoTimeZone.schedule(context.applicationContext) }
            // The update killed the process and the wake loop with it.
            WakeService.start(context.applicationContext)
            return
        }

        // The clock moved to another zone: repeating alarms follow it to the same local time.
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            runCatching { Alarms.reschedule(context.applicationContext) }
                .onFailure { Log.w(TAG, "could not move alarms to the new time zone", it) }
            return
        }

        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val id = intent.getStringExtra(EXTRA_ALARM_ID).orEmpty()
        val isTimer = intent.action == ACTION_TIMER
        Log.i(TAG, "${if (isTimer) "timer" else "alarm"} fired id='$id' label='$label'")

        if (isTimer) runCatching {
            DeviceCommands.clearTimer(context, intent.getStringExtra(EXTRA_TIMER_KEY).orEmpty())
        } else runCatching {
            // It has gone off, so this one is no longer armed. A repeating alarm is moved on to
            // its next occurrence here; a one-shot is forgotten, which also stops the store
            // growing without bound.
            Alarms.onFired(context, id)
        }

        runCatching {
            val svc = Intent(context, AlarmService::class.java)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_ALARM_ID, id)
                .putExtra(AlarmService.EXTRA_IS_TIMER, isTimer)
                .putExtra(EXTRA_SOUND, intent.getBooleanExtra(EXTRA_SOUND, true))
                .putExtra(EXTRA_VIBRATE, intent.getBooleanExtra(EXTRA_VIBRATE, true))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc)
            else context.startService(svc)
        }.onFailure { Log.w(TAG, "could not start the alarm service", it) }
    }

    companion object {
        private const val TAG = "RistCmd"
        const val ACTION_FIRE = "watch.rist.assistant.ALARM_FIRE"
        const val ACTION_TIMER = "watch.rist.assistant.TIMER_FIRE"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_VIBRATE = "vibrate"
        const val EXTRA_TIMER_KEY = "timer_key"
    }
}

object TimerAlarm {
    private fun req(key: String) = ("rist-timer:" + key).hashCode()

    private fun pi(ctx: Context, label: String, key: String) = android.app.PendingIntent.getBroadcast(
        ctx, req(key),
        Intent(ctx, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_TIMER)
            .putExtra(AlarmReceiver.EXTRA_LABEL, label)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, "timer")
            .putExtra(AlarmReceiver.EXTRA_TIMER_KEY, key),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context, inMs: Long, label: String, key: String) = runCatching {
        if (inMs <= 0L) return@runCatching
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        am.setExactAndAllowWhileIdle(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + inMs, pi(ctx, label, key)
        )
    }.let { }

    fun cancel(ctx: Context, key: String) = runCatching {
        (ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            .cancel(pi(ctx, "", key))
    }.let { }
}
