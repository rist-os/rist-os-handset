package watch.rist.assistant

// Constructed reflectively by OtaEngine: the single-argument constructor signature must not change.
class OtaEngineCallback(
    private val progress: OtaEngine.Progress,
) : android.os.UpdateEngineCallback() {

    // percent is 0..1 within the current status, not overall.
    override fun onStatusUpdate(status: Int, percent: Float) {
        progress.onStatus(status, percent.toDouble())
    }

    override fun onPayloadApplicationComplete(errorCode: Int) {
        progress.onComplete(errorCode)
    }
}
