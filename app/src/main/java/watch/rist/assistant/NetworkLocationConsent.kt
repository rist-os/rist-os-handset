package watch.rist.assistant

import android.content.Context
import android.provider.Settings
import android.util.Log

object NetworkLocationConsent {

    private const val TAG = "RistNetLoc"

    const val KEY_NETWORK_LOCATION = "network_location"

    const val KEY_GEOCODER = "geocoder"

    const val VALUE_OFF = 0

    // 2 = NETWORK_LOCATION_GRAPHENEOS_APPLE_PROXY. Never write 1 (APPLE): it hands Apple this handset's own IP.
    const val VALUE_PROXY = 2

    // 1 = GEOCODER_SERVER_OPENSTREETMAP, 0 = disabled.
    const val GEOCODER_OSM = 1
    const val GEOCODER_OFF = 0

    enum class Choice { UNANSWERED, ON, OFF;
        companion object {
            fun parse(s: String?): Choice = when (s) {
                "on" -> ON
                "off" -> OFF
                else -> UNANSWERED
            }
        }
        val stored: String get() = when (this) { ON -> "on"; OFF -> "off"; UNANSWERED -> "" }
    }

    enum class Status {
        UNANSWERED,
        ON,
        OFF,
        REFUSED,
    }

    enum class Outcome { APPLIED, REFUSED }

    const val RE_ASK_GAP_MS = 7L * 24 * 60 * 60 * 1000

    const val MAX_ASKS = 3

    fun shouldAsk(
        choice: Choice,
        asks: Int,
        lastAskedAt: Long,
        now: Long,
        systemValue: Int,
    ): Boolean {
        if (systemValue != VALUE_OFF) return false
        if (choice != Choice.UNANSWERED) return false
        if (asks >= MAX_ASKS) return false
        if (lastAskedAt <= 0L) return true
        // A clock that jumped backwards counts as due.
        if (now < lastAskedAt) return true
        return now - lastAskedAt >= RE_ASK_GAP_MS
    }

    fun status(choice: Choice, systemValue: Int): Status = when {
        systemValue != VALUE_OFF -> Status.ON
        choice == Choice.ON -> Status.REFUSED
        choice == Choice.OFF -> Status.OFF
        else -> Status.UNANSWERED
    }

    fun outcomeOf(wanted: Int, readBack: Int): Outcome =
        if (readBack == wanted) Outcome.APPLIED else Outcome.REFUSED

    fun systemValue(ctx: Context): Int = runCatching {
        Settings.Global.getInt(ctx.contentResolver, KEY_NETWORK_LOCATION, VALUE_OFF)
    }.getOrDefault(VALUE_OFF)

    fun geocoderValue(ctx: Context): Int = runCatching {
        Settings.Global.getInt(ctx.contentResolver, KEY_GEOCODER, GEOCODER_OFF)
    }.getOrDefault(GEOCODER_OFF)

    fun status(ctx: Context): Status = status(Config.networkLocationChoice(ctx), systemValue(ctx))

    fun apply(ctx: Context, enable: Boolean): Outcome {
        val wantNet = if (enable) VALUE_PROXY else VALUE_OFF
        val wantGeo = if (enable) GEOCODER_OSM else GEOCODER_OFF

        Config.setNetworkLocationChoice(ctx, if (enable) Choice.ON else Choice.OFF)

        val netWritten = write(ctx, KEY_NETWORK_LOCATION, wantNet)
        val geoWritten = write(ctx, KEY_GEOCODER, wantGeo)

        val netBack = systemValue(ctx)
        val geoBack = geocoderValue(ctx)
        val outcome = outcomeOf(wantNet, netBack)

        if (outcome == Outcome.REFUSED) {
            Log.w(
                TAG,
                "REFUSED: wanted $KEY_NETWORK_LOCATION=$wantNet, read back $netBack " +
                    "(putInt ${if (netWritten) "returned normally" else "threw"}); " +
                    "$KEY_GEOCODER wanted $wantGeo, read back $geoBack " +
                    "(putInt ${if (geoWritten) "returned normally" else "threw"}). " +
                    "Both keys are @Protected(readWrite={SETTINGS, SETUP_WIZARD}) and Rist is " +
                    "neither. The user must use the system Settings app: " +
                    "Location > Location services > Network location."
            )
        } else {
            Log.i(TAG, "applied $KEY_NETWORK_LOCATION=$netBack $KEY_GEOCODER=$geoBack")
        }
        return outcome
    }

    fun deferred(ctx: Context) {
        Config.setNetworkLocationAsks(ctx, Config.networkLocationAsks(ctx) + 1)
    }

    fun markAsked(ctx: Context) {
        Config.setNetworkLocationAskedAt(ctx, System.currentTimeMillis())
    }

    fun isDue(ctx: Context): Boolean = shouldAsk(
        choice = Config.networkLocationChoice(ctx),
        asks = Config.networkLocationAsks(ctx),
        lastAskedAt = Config.networkLocationAskedAt(ctx),
        now = System.currentTimeMillis(),
        systemValue = systemValue(ctx),
    )

    private fun write(ctx: Context, key: String, value: Int): Boolean = runCatching {
        Settings.Global.putInt(ctx.contentResolver, key, value)
    }.onFailure { Log.w(TAG, "putInt $key=$value threw", it) }.isSuccess
}
