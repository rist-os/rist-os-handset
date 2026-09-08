package watch.rist.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

class RistVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i("RistVIS", "assistant ready")
    }
}
