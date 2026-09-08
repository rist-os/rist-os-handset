package watch.rist.assistant

import rist.v1.DeviceResponse
import rist.v1.Progress
import java.io.InputStream

object StreamingWire {

    const val SEQ_MEDIA_TYPE = "application/x-protobuf-seq"

    const val UNARY_MEDIA_TYPE = "application/x-protobuf"

    // Never send the streaming Accept header without a working cancel path (StreamingCancel).
    const val STREAMING_ENABLED = false

    fun acceptHeader(enabled: Boolean = STREAMING_ENABLED): String =
        if (enabled) SEQ_MEDIA_TYPE else UNARY_MEDIA_TYPE

    fun isStreamed(contentType: String?): Boolean {
        val mediaType = contentType?.substringBefore(';')?.trim() ?: return false
        return mediaType.equals(SEQ_MEDIA_TYPE, ignoreCase = true)
    }

    enum class FrameKind { STATUS, FINAL, IGNORE }

    fun classify(frame: DeviceResponse): FrameKind = when {
        frame.isFinal -> FrameKind.FINAL
        frame.hasProgress() -> FrameKind.STATUS
        else -> FrameKind.IGNORE
    }

    sealed class Outcome {
        data class Completed(val final: DeviceResponse, val statusFrames: Int) : Outcome()

        data class Truncated(val statusFrames: Int) : Outcome()

        object Empty : Outcome()

        data class Cancelled(val statusFrames: Int) : Outcome()
    }

    fun consume(
        input: InputStream,
        isCancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): Outcome {
        var seen = 0
        var statusFrames = 0
        while (true) {
            if (isCancelled()) return Outcome.Cancelled(statusFrames)
            // Null at a clean end of stream; throws on a truncated frame.
            val frame = DeviceResponse.parseDelimitedFrom(input) ?: break
            seen++
            when (classify(frame)) {
                FrameKind.FINAL -> return Outcome.Completed(frame, statusFrames)
                FrameKind.STATUS -> {
                    statusFrames++
                    if (isCancelled()) return Outcome.Cancelled(statusFrames)
                    onProgress(frame.progress)
                }
                FrameKind.IGNORE -> Unit
            }
        }
        return if (seen == 0) Outcome.Empty else Outcome.Truncated(statusFrames)
    }

    const val KIND_THINKING = "thinking"
    const val KIND_TOOL = "tool"
    const val KIND_SAY = "say"
    const val KIND_ANSWERING = "answering"
    const val KIND_WAITING = "waiting"
    private val KNOWN_KINDS =
        setOf(KIND_THINKING, KIND_TOOL, KIND_SAY, KIND_ANSWERING, KIND_WAITING)

    fun kindOf(raw: String?): String {
        val k = raw?.trim()?.lowercase().orEmpty()
        return if (k in KNOWN_KINDS) k else KIND_TOOL
    }

    enum class Treatment {
        SPEAK,

        QUOTE,

        DISPLAY,
    }

    fun treatmentFor(progress: Progress): Treatment {
        val speakable = progress.speakable
        return when {
            speakable && kindOf(progress.kind) == KIND_SAY -> Treatment.QUOTE
            else -> Treatment.DISPLAY
        }
    }

    fun renderLine(progress: Progress): String? {
        val text = progress.text?.trim().orEmpty()
        if (text.isEmpty()) return null
        return when (treatmentFor(progress)) {
            Treatment.QUOTE -> "“$text”"
            else -> text
        }
    }
}
