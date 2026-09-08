package watch.rist.assistant

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import rist.v1.Progress

object StreamingStatus {

    const val ACTION_PROGRESS = "watch.rist.assistant.STREAM_PROGRESS"

    const val ACTION_STREAM_ENDED = "watch.rist.assistant.STREAM_ENDED"

    const val EXTRA_PROGRESS_PROTO = "watch.rist.assistant.extra.PROGRESS_PROTO"
    const val EXTRA_REQUEST_ID = "watch.rist.assistant.extra.STREAM_REQUEST_ID"
    const val EXTRA_ENDING = "watch.rist.assistant.extra.STREAM_ENDING"

    const val ENDING_FINAL = "final"
    const val ENDING_TRUNCATED = "truncated"
    const val ENDING_CANCELLED = "cancelled"
    const val ENDING_EMPTY = "empty"

    fun endingOf(outcome: StreamingWire.Outcome): String = when (outcome) {
        is StreamingWire.Outcome.Completed -> ENDING_FINAL
        is StreamingWire.Outcome.Truncated -> ENDING_TRUNCATED
        is StreamingWire.Outcome.Cancelled -> ENDING_CANCELLED
        StreamingWire.Outcome.Empty -> ENDING_EMPTY
    }

    fun failureFor(ending: String): String = when (ending) {
        ENDING_TRUNCATED -> "the assistant was cut off part-way through"
        ENDING_EMPTY -> "the assistant sent an empty reply"
        else -> ""
    }

    fun publish(ctx: Context, requestId: String, progress: Progress) {
        val intent = Intent(ACTION_PROGRESS)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_PROGRESS_PROTO, progress.toByteArray())
        LocalBroadcastManager.getInstance(ctx.applicationContext).sendBroadcast(intent)
    }

    fun publishEnd(ctx: Context, requestId: String, ending: String) {
        val intent = Intent(ACTION_STREAM_ENDED)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_ENDING, ending)
        LocalBroadcastManager.getInstance(ctx.applicationContext).sendBroadcast(intent)
    }
}
