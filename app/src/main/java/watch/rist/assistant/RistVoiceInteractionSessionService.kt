package watch.rist.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class RistVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        RistSession(this)
}

// onHide sends CANCEL, not STOP: STOP would upload the clip.
class RistSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val svc = android.content.Intent(context, RecordService::class.java)
            .setAction(RecordService.ACTION_START)
        context.startForegroundService(svc)
    }

    override fun onHide() {
        super.onHide()
        runCatching {
            val svc = android.content.Intent(context, RecordService::class.java)
                .setAction(RecordService.ACTION_CANCEL)
            context.startForegroundService(svc)
        }
    }
}
