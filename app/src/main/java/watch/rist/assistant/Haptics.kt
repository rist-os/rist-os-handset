package watch.rist.assistant

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object Haptics {

    private const val TAG = "RistHaptics"

    fun ack(ctx: Context) = tap(ctx, VibrationEffect.EFFECT_TICK, "ack")

    fun final(ctx: Context) = tap(ctx, VibrationEffect.EFFECT_CLICK, "final")

    private fun tap(ctx: Context, effectId: Int, what: String) {
        if (!Config.isHapticsEnabled(ctx)) return
        runCatching {
            val v = vibrator(ctx) ?: return
            if (!v.hasVibrator()) return
            v.vibrate(VibrationEffect.createPredefined(effectId))
        }.onFailure {
            Log.d(TAG, "no $what tap: ${it.javaClass.simpleName}")
        }
    }

    private fun vibrator(ctx: Context): Vibrator? = runCatching {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    }.getOrNull()
}
