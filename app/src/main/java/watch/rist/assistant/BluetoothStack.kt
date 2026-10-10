package watch.rist.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's Bluetooth, as [BluetoothCommands] needs it: devices by address, states read on
 * demand. Addresses never leave the phone; [BluetoothAudio] turns them into opaque ids.
 */
interface BluetoothStack {

    /** One device as the stack shows it. [uuids] are lower-case 128-bit strings. */
    data class Dev(
        val address: String,
        val name: String,
        val deviceClass: Int? = null,
        val majorClass: Int? = null,
        val uuids: Set<String> = emptySet(),
    )

    fun present(): Boolean

    /** Nearby devices: BLUETOOTH_CONNECT and BLUETOOTH_SCAN. */
    fun permitted(): Boolean

    fun isOn(): Boolean

    /** Asks the adapter to switch on; false when the request was refused outright. */
    fun requestEnable(): Boolean

    fun bonded(): List<Dev>

    /** An audio profile (A2DP, headset, LE audio, hearing aid) is connected to [address]. */
    fun connected(address: String): Boolean

    fun bondState(address: String): Int

    /** Starts connecting every enabled audio profile. Null when the request was taken, else why not. */
    fun connect(address: String): String?

    /** Starts disconnecting every profile; the device stays paired. Null when taken, else why not. */
    fun disconnect(address: String): String?

    /** Starts bonding. Android shows its own confirmation; nothing here ever answers it. */
    fun createBond(address: String): Boolean

    /** The unbond reason of the last bond attempt on [address] that ended in BOND_NONE, if seen. */
    fun lastUnbondReason(address: String): Int?

    /** Classic discovery for [seconds]: every device found, bonded or not. Null if it could not start. */
    fun discover(seconds: Int, stop: () -> Boolean): List<Dev>?
}

/** The real stack. Connect and disconnect are system APIs, reached by reflection (see [connect]). */
class AndroidBluetoothStack(ctx: Context) : BluetoothStack {

    private val app = ctx.applicationContext

    private val adapter: BluetoothAdapter? =
        runCatching { (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }.getOrNull()

    override fun present(): Boolean = adapter != null

    override fun permitted(): Boolean =
        granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_SCAN)

    private fun granted(p: String) =
        app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun isOn(): Boolean = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    // Deprecated for ordinary apps, still honoured for a device owner and a system app.
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override fun requestEnable(): Boolean = runCatching { adapter?.enable() == true }
        .onFailure { Log.w(TAG, "enable refused: ${it.javaClass.simpleName}") }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    override fun bonded(): List<BluetoothStack.Dev> =
        runCatching { adapter?.bondedDevices.orEmpty().map { dev(it) } }
            .onFailure { Log.w(TAG, "bonded devices unreadable: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    private fun dev(d: BluetoothDevice): BluetoothStack.Dev {
        seen[d.address] = d
        val cls = runCatching { d.bluetoothClass }.getOrNull()
        return BluetoothStack.Dev(
            address = d.address,
            name = runCatching { d.alias }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { d.name }.getOrNull().orEmpty(),
            deviceClass = cls?.deviceClass,
            majorClass = cls?.majorDeviceClass,
            uuids = runCatching { d.uuids }.getOrNull().orEmpty()
                .mapNotNull { it?.uuid?.toString()?.lowercase() }.toSet(),
        )
    }

    // The device objects the stack handed us (bonded or discovered), reused for the same address.
    private val seen = ConcurrentHashMap<String, BluetoothDevice>()

    private fun remote(address: String): BluetoothDevice? =
        seen[address] ?: runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    override fun connected(address: String): Boolean {
        val d = remote(address) ?: return false
        return Proxies.get(app, adapter).any { p ->
            runCatching { p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
        }
    }

    @SuppressLint("MissingPermission")
    override fun bondState(address: String): Int =
        runCatching { remote(address)?.bondState }.getOrNull() ?: BluetoothDevice.BOND_NONE

    /**
     * `BluetoothDevice.connect()` is a system API (BLUETOOTH_PRIVILEGED, Android 13+): it connects
     * every audio profile the device allows. It is not in the public SDK this app compiles against,
     * so it is called by reflection, as OtaEngine calls UpdateEngine. It returns a
     * BluetoothStatusCodes value, 0 = the request was taken.
     */
    override fun connect(address: String): String? = invokeStatus(address, "connect")

    override fun disconnect(address: String): String? = invokeStatus(address, "disconnect")

    private fun invokeStatus(address: String, method: String): String? {
        val d = remote(address) ?: return "no such device"
        return try {
            val code = BluetoothDevice::class.java.getMethod(method).invoke(d) as? Int
            if (code == 0) null else "status $code"
        } catch (e: NoSuchMethodException) {
            "BluetoothDevice.$method() not on this OS"
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // A SecurityException here is BLUETOOTH_PRIVILEGED not held: the OS image's allowlist.
            "${e.targetException?.javaClass?.simpleName}: ${e.targetException?.message.orEmpty().take(120)}"
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}"
        }
    }

    private val unbondReasons = ConcurrentHashMap<String, Int>()
    @Volatile private var bondWatch: BroadcastReceiver? = null

    @SuppressLint("MissingPermission")
    override fun createBond(address: String): Boolean {
        val d = remote(address) ?: return false
        unbondReasons.remove(address)
        watchBonds()
        return runCatching { d.createBond() }
            .onFailure { Log.w(TAG, "createBond refused: ${it.javaClass.simpleName}") }.getOrDefault(false)
    }

    override fun lastUnbondReason(address: String): Int? = unbondReasons[address]

    private fun watchBonds() {
        if (bondWatch != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (state == BluetoothDevice.BOND_NONE) {
                    unbondReasons[d.address] = i.getIntExtra(EXTRA_UNBOND_REASON, 0)
                }
            }
        }
        runCatching {
            app.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                Context.RECEIVER_EXPORTED)
            bondWatch = receiver
        }.onFailure { Log.w(TAG, "bond watch not registered", it) }
    }

    @SuppressLint("MissingPermission")
    override fun discover(seconds: Int, stop: () -> Boolean): List<BluetoothStack.Dev>? {
        val a = adapter ?: return null
        val found = LinkedHashMap<String, BluetoothDevice>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                synchronized(found) { found.putIfAbsent(d.address, d) }
            }
        }
        runCatching {
            app.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), Context.RECEIVER_EXPORTED)
        }.onFailure { Log.w(TAG, "discovery receiver not registered", it); return null }
        try {
            runCatching { if (a.isDiscovering) a.cancelDiscovery() }
            if (!runCatching { a.startDiscovery() }.getOrDefault(false)) return null
            val end = SystemClock.elapsedRealtime() + seconds * 1000L
            while (SystemClock.elapsedRealtime() < end && !stop()) Thread.sleep(POLL_MS)
            return synchronized(found) { found.values.map { dev(it) } }
        } finally {
            runCatching { a.cancelDiscovery() }
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    /**
     * The audio profile proxies, opened once for the process and held: binding them per turn
     * would leave the first turn after each bind without them. A proxy that has not connected yet
     * is waited for briefly.
     */
    internal object Proxies {
        private val held = ConcurrentHashMap<Int, BluetoothProfile>()
        @Volatile private var asked = false

        fun get(ctx: Context, adapter: BluetoothAdapter?): Collection<BluetoothProfile> {
            if (adapter == null) return emptyList()
            if (!asked) {
                asked = true
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { held[profile] = proxy }
                    override fun onServiceDisconnected(profile: Int) { held.remove(profile) }
                }
                for (p in AUDIO_PROFILES) runCatching { adapter.getProfileProxy(ctx.applicationContext, listener, p) }
                val until = SystemClock.elapsedRealtime() + PROXY_WAIT_MS
                while (held.isEmpty() && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            }
            return held.values
        }

        internal fun resetForTest() { held.clear(); asked = false }
    }

    companion object {
        private const val TAG = "RistBluetooth"
        private const val POLL_MS = 200L
        private const val PROXY_WAIT_MS = 1_000L
        // BluetoothDevice.EXTRA_UNBOND_REASON is hidden; this is its value.
        private const val EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON"
        private const val LE_AUDIO = 22
        private const val HEARING_AID = 21
        private val AUDIO_PROFILES = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, LE_AUDIO, HEARING_AID)
    }
}
