package watch.rist.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class KeyEventReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RECORD_DOWN = "watch.rist.assistant.action.RECORD_DOWN"
        const val ACTION_RECORD_UP = "watch.rist.assistant.action.RECORD_UP"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = Intent(ctx, RecordService::class.java).setAction(
            when (intent.action) {
                ACTION_RECORD_DOWN -> RecordService.ACTION_START
                ACTION_RECORD_UP -> RecordService.ACTION_STOP
                else -> return
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc)
        else ctx.startService(svc)
    }
}
