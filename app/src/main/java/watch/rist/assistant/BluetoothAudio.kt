package watch.rist.assistant

import android.bluetooth.BluetoothClass
import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import rist.v1.BluetoothDevice as WireDevice
import rist.v1.BluetoothState

/**
 * Bluetooth headphones by voice (schema v32, "bluetooth_v1"). The phone reports its paired audio
 * devices on every turn; the backend picks one and sends a [rist.v1.BluetoothCommand]; the phone
 * carries it out ([BluetoothCommands]) and sends the turn again with what really happened.
 *
 * The backend never sees an address. Each device gets an opaque id: a salted hash of its address,
 * the salt kept on this install only. Names are the user's alias, else the device name; the kind
 * comes from the device class, never from the name.
 */
object BluetoothAudio {

    const val COMPONENT = "bluetooth_v1"

    internal const val MAX_DEVICES = 32
    internal const val MAX_DISCOVERED = 16
    private const val MAX_NAME_CHARS = 64

    /** How long a scan's ids stay good for the PAIR that may follow (the backend allows 2 min). */
    internal const val SCAN_MEMORY_MS = 5 * 60_000L

    private const val PREFS = "rist_bluetooth"
    private const val KEY_SALT = "salt"
    private const val KEY_LAST_BONDED = "last_bonded"

    /** The stack in use; tests put a fake here. */
    @Volatile internal var stackFor: (Context) -> BluetoothStack = { AndroidBluetoothStack(it) }

    fun stack(ctx: Context): BluetoothStack = stackFor(ctx.applicationContext)

    /** A phone with a Bluetooth adapter reports its devices and carries out commands. */
    fun declared(ctx: Context): Boolean = runCatching { stack(ctx).present() }.getOrDefault(false)

    // ── ids ──────────────────────────────────────────────────────────────────────────────────

    private fun salt(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SALT, null)?.let { return it }
        val fresh = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_SALT, fresh).commit()
        return fresh
    }

    /** Opaque and stable for this install: first 16 hex chars of SHA-256(salt ‖ address). */
    fun idOf(ctx: Context, address: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((salt(ctx) + address.uppercase()).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    // ── which devices, and what kind ────────────────────────────────────────────────────────

    private fun short(uuid16: String) = "0000$uuid16-0000-1000-8000-00805f9b34fb"

    private val HEARING_UUIDS = setOf(short("fdf0"), short("1854"))          // ASHA, LE hearing access
    private val AUDIO_UUIDS = setOf(
        short("110b"), short("110d"),                                      // A2DP sink, advanced audio
        short("111e"), short("1108"),                                      // hands-free, headset
        short("184e"), short("1850"), short("1853"),                       // LE audio: ASCS, PACS, CAP
    ) + HEARING_UUIDS

    fun isAudio(d: BluetoothStack.Dev): Boolean =
        d.majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO || d.uuids.any { it in AUDIO_UUIDS }

    fun kindOf(d: BluetoothStack.Dev): String {
        if (d.uuids.any { it in HEARING_UUIDS }) return "hearing_aid"
        return when (d.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> "headphones"
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "headset"
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> "speaker"
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "car"
            else -> "other"
        }
    }

    internal fun nameOf(d: BluetoothStack.Dev): String = d.name.trim().take(MAX_NAME_CHARS)

    internal fun wire(ctx: Context, d: BluetoothStack.Dev, connected: Boolean): WireDevice =
        WireDevice.newBuilder()
            .setId(idOf(ctx, d.address))
            .setName(nameOf(d))
            .setKind(kindOf(d))
            .setConnected(connected)
            .build()

    /** The paired audio devices, by address. Empty when Bluetooth is off: Android lists none then. */
    internal fun bondedAudio(stack: BluetoothStack): List<BluetoothStack.Dev> =
        stack.bonded().filter { isAudio(it) }.take(MAX_DEVICES)

    // ── the report on every turn ────────────────────────────────────────────────────────────

    /**
     * `DeviceRequest.bluetooth`. Android lists no paired devices while Bluetooth is off, so the
     * list last seen while it was on is reported then (none connected): "connect my Shokz" with
     * Bluetooth off must still name the Shokz, and the phone switches Bluetooth on to connect them.
     */
    fun state(ctx: Context, stack: BluetoothStack = stack(ctx)): BluetoothState {
        val permitted = stack.permitted()
        val on = stack.isOn()
        val b = BluetoothState.newBuilder().setAdapterOn(on).setPermission(permitted)
        if (!permitted) return b.build()
        if (on) {
            val bonded = bondedAudio(stack)
            remember(ctx, bonded)
            bonded.forEach { b.addDevices(wire(ctx, it, stack.connected(it.address))) }
        } else {
            remembered(ctx).forEach { b.addDevices(wire(ctx, it, connected = false)) }
        }
        return b.build()
    }

    private fun remember(ctx: Context, devices: List<BluetoothStack.Dev>) {
        val rows = org.json.JSONArray()
        devices.forEach {
            rows.put(org.json.JSONObject()
                .put("a", it.address).put("n", it.name)
                .put("c", it.deviceClass ?: -1).put("m", it.majorClass ?: -1)
                .put("u", org.json.JSONArray(it.uuids.toList())))
        }
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_BONDED, rows.toString()).apply()
        }
    }

    internal fun remembered(ctx: Context): List<BluetoothStack.Dev> = runCatching {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_BONDED, null)
            ?: return emptyList()
        val rows = org.json.JSONArray(raw)
        (0 until rows.length()).map { i ->
            val o = rows.getJSONObject(i)
            val u = o.optJSONArray("u")
            BluetoothStack.Dev(
                address = o.getString("a"),
                name = o.optString("n"),
                deviceClass = o.optInt("c", -1).takeIf { it >= 0 },
                majorClass = o.optInt("m", -1).takeIf { it >= 0 },
                uuids = if (u == null) emptySet() else (0 until u.length()).map { u.getString(it) }.toSet(),
            )
        }
    }.getOrDefault(emptyList())

    // ── what the last scan found, for the PAIR that follows ─────────────────────────────────

    private class Found(val dev: BluetoothStack.Dev, val atMs: Long)

    private val lastScan = HashMap<String, Found>()

    internal fun rememberScan(ctx: Context, found: List<BluetoothStack.Dev>, nowMs: Long) = synchronized(lastScan) {
        lastScan.clear()
        found.forEach { lastScan[idOf(ctx, it.address)] = Found(it, nowMs) }
    }

    internal fun fromLastScan(id: String, nowMs: Long): BluetoothStack.Dev? = synchronized(lastScan) {
        lastScan[id]?.takeIf { nowMs - it.atMs <= SCAN_MEMORY_MS }?.dev
    }

    internal fun resetForTest() {
        synchronized(lastScan) { lastScan.clear() }
        stackFor = { AndroidBluetoothStack(it) }
    }
}
