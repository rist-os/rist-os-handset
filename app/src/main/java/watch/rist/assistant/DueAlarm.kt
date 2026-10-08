package watch.rist.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Wakes the phone just after the next scheduled instruction is due (a reminder, a scheduled text,
 * a briefing), so its notice arrives on time even if the held wake connection died without a
 * word. The time comes from the backend on every wake (`next_due_at_epoch_ms`); 0 cancels.
 *
 * Normally the notice is pushed down the open hold within seconds of the run finishing and this
 * alarm only confirms it. One exact alarm at a time; each wake replaces it.
 */
object DueAlarm {

    private const val TAG = "RistDueAlarm"
    internal const val ACTION = "watch.rist.assistant.action.WAKE_DUE"

    /** Fired this long after the due time, so the backend has started the run. */
    internal const val DELAY_MS = 1_500L

    @Volatile private var setForMs = 0L

    /** When to fire for [dueMs], or null for none: absent, or already past. */
    internal fun fireAtMs(dueMs: Long, nowMs: Long): Long? =
        if (dueMs <= 0L || dueMs + DELAY_MS <= nowMs) null else dueMs + DELAY_MS

    fun set(ctx: Context, dueMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val at = fireAtMs(dueMs, nowMs)
        if ((at ?: 0L) == setForMs) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, DueAlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            am.cancel(pi)
            setForMs = 0L
            return
        }
        // USE_EXACT_ALARM is held (alarms and timers), and the app is exempt from Doze, so the
        // exact form is allowed; the inexact one is only a fallback if the OS ever refuses it.
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            .onFailure {
                Log.w(TAG, "exact alarm refused; setting an inexact one", it)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        setForMs = at
        Log.i(TAG, "next scheduled instruction at $dueMs; polling at $at")
    }

    /** The alarm went off: nothing is set until the next wake says what comes next. */
    internal fun fired() { setForMs = 0L }
}

class DueAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DueAlarm.ACTION) return
        DueAlarm.fired()
        val app = context.applicationContext
        WakeLoop.refreshNow(app)
        WakeService.start(app)
    }
}
