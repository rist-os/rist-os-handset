package watch.rist.assistant

import android.content.Context
import android.util.Log
import org.json.JSONObject
import rist.v1.FeatureSet

/**
 * Which assistant features this account has, as the backend reports them.
 *
 * PENDING: the signal is `DeviceResponse.features` (32) and `WakeSignal.features` (10), proposed
 * to the backend as schema v19 and not yet in the canonical proto. Until a backend sends it, no
 * set has ever been received and every feature counts as on, which is today's behaviour.
 *
 * The backend decides; this only hides what it says is not there. Once a set has arrived, a
 * feature it does not list is treated as unavailable, and so is a state this build does not know.
 * The phone's own apps (dialer, messages, camera, gallery, offline maps) are not assistant
 * features and are never hidden.
 */
object Features {

    private const val TAG = "RistFeatures"

    enum class Id(val wire: String) {
        EMAIL("email"),
        TEXTING("texting"),
        CONTACTS("contacts"),
        CALENDAR("calendar"),
        MAPS("maps"),
        MEDIA("media"),
        SOUND_ID("sound_id"),
        VIDEO_CALLS("video_calls"),
        CALL_SCREENING("call_screening"),
        VOICEMAIL("voicemail"),
    }

    enum class State(val wire: String) { ON("on"), OFF("off"), UNAVAILABLE("unavailable") }

    internal fun stateOf(wire: String): State =
        State.values().firstOrNull { it.wire == wire.trim().lowercase() } ?: State.UNAVAILABLE

    /** The set as a map of wire id to state. Later duplicates win, as a later line would. */
    internal fun decode(set: FeatureSet): Map<String, State> =
        set.featuresList.filter { it.feature.isNotBlank() }
            .associate { it.feature.trim().lowercase() to stateOf(it.state) }

    /** Null when no set has ever been received from this backend. */
    internal fun held(ctx: Context): Map<String, State>? {
        val raw = Config.features(ctx)
        if (raw.isBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { stateOf(o.optString(it)) }
        }.getOrNull()
    }

    fun state(ctx: Context, id: Id): State {
        val held = held(ctx) ?: return State.ON
        return held[id.wire] ?: State.UNAVAILABLE
    }

    fun isOn(ctx: Context, id: Id): Boolean = state(ctx, id) == State.ON

    /** Stores a set the backend sent. Returns true when what the phone should show has changed. */
    fun apply(ctx: Context, set: FeatureSet): Boolean {
        val next = decode(set)
        val before = held(ctx)
        if (before == next) return false
        val json = JSONObject().apply { next.forEach { (k, v) -> put(k, v.wire) } }
        Config.setFeatures(ctx, json.toString())
        Log.i(TAG, "features now: " + Id.values().joinToString { "${it.wire}=${state(ctx, it).wire}" })
        if (isOn(ctx, Id.CONTACTS)) ContactsSync.onFeatureOn(ctx) else ContactsSync.onFeatureOff(ctx)
        runCatching {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx.applicationContext)
                .sendBroadcast(android.content.Intent(ACTION_CHANGED))
        }
        return true
    }

    const val ACTION_CHANGED = "watch.rist.assistant.FEATURES_CHANGED"
}
