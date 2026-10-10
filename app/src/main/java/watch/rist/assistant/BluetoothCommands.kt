package watch.rist.assistant

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import android.util.Log
import rist.v1.BluetoothCommand
import rist.v1.BluetoothCommand.Action
import rist.v1.BluetoothResult
import rist.v1.BluetoothResult.Outcome

/**
 * Carries out one [BluetoothCommand] and says what really happened. DONE only after the phone
 * has seen it: the audio profile connected, disconnected, the bond made. Never unpairs. Never
 * answers Android's pairing confirmation: the person sees it and decides.
 */
class BluetoothCommands(
    private val ctx: Context,
    private val stack: BluetoothStack,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** The person stopped the turn: give up waiting. */
    private val stopped: () -> Boolean = { false },
) {

    fun run(cmd: BluetoothCommand): BluetoothResult {
        val result = BluetoothResult.newBuilder()
            .setCommandId(cmd.commandId)
            .setAction(cmd.action)
            .setDeviceId(cmd.deviceId)
        val done = runCatching { outcome(cmd, result) }
            .getOrElse { Log.w(TAG, "${cmd.action} threw", it); Outcome.FAILED to it.javaClass.simpleName }
        result.outcome = done.first
        if (done.second.isNotEmpty()) result.detail = done.second.take(200)
        Log.i(TAG, "${cmd.action} -> ${done.first} ${done.second}")
        return result.build()
    }

    private fun outcome(cmd: BluetoothCommand, result: BluetoothResult.Builder): Pair<Outcome, String> {
        if (!stack.present()) return Outcome.FAILED to "no Bluetooth adapter"
        if (!stack.permitted()) return Outcome.NO_PERMISSION to ""
        return when (cmd.action) {
            Action.CONNECT -> connect(cmd.deviceId)
            Action.DISCONNECT -> disconnect(cmd.deviceId)
            Action.SCAN -> scan(cmd.scanS, result)
            Action.PAIR -> pair(cmd.deviceId)
            else -> Outcome.FAILED to "unknown action ${cmd.actionValue}"
        }
    }

    /** Bluetooth on, switching it on if it is off: the person asked for something that needs it. */
    private fun ensureOn(): Boolean {
        if (stack.isOn()) return true
        if (!stack.requestEnable()) return false
        return waitFor(ENABLE_MS) { stack.isOn() }
    }

    private fun bonded(id: String): BluetoothStack.Dev? =
        BluetoothAudio.bondedAudio(stack).firstOrNull { BluetoothAudio.idOf(ctx, it.address) == id }

    private fun connect(id: String): Pair<Outcome, String> {
        // On first: Android lists no paired devices while Bluetooth is off.
        if (!ensureOn()) return Outcome.BLUETOOTH_OFF to ""
        val dev = bonded(id) ?: return Outcome.NOT_PAIRED to ""
        if (stack.connected(dev.address)) return Outcome.ALREADY to ""
        return connectBonded(dev, ifNot = Outcome.NOT_IN_RANGE)
    }

    private fun connectBonded(dev: BluetoothStack.Dev, ifNot: Outcome): Pair<Outcome, String> {
        stack.connect(dev.address)?.let { return Outcome.FAILED to it }
        return if (waitFor(CONNECT_MS) { stack.connected(dev.address) }) Outcome.DONE to ""
        else ifNot to "no connection in ${CONNECT_MS / 1000}s"
    }

    private fun disconnect(id: String): Pair<Outcome, String> {
        // Off means nothing is connected; nothing to switch on for.
        if (!stack.isOn()) return Outcome.ALREADY to "Bluetooth is off"
        val dev = bonded(id) ?: return Outcome.NOT_PAIRED to ""
        if (!stack.connected(dev.address)) return Outcome.ALREADY to ""
        stack.disconnect(dev.address)?.let { return Outcome.FAILED to it }
        return if (waitFor(DISCONNECT_MS) { !stack.connected(dev.address) }) Outcome.DONE to ""
        else Outcome.FAILED to "still connected after ${DISCONNECT_MS / 1000}s"
    }

    private fun scan(seconds: Int, result: BluetoothResult.Builder): Pair<Outcome, String> {
        if (!ensureOn()) return Outcome.BLUETOOTH_OFF to ""
        val s = seconds.coerceIn(1, MAX_SCAN_S)
        val found = stack.discover(s, stopped) ?: return Outcome.FAILED to "discovery did not start"
        val audio = found
            .filter { BluetoothAudio.isAudio(it) && stack.bondState(it.address) != BluetoothDevice.BOND_BONDED }
            .distinctBy { it.address.uppercase() }
            .take(BluetoothAudio.MAX_DISCOVERED)
        BluetoothAudio.rememberScan(ctx, audio, nowMs())
        audio.forEach { result.addDiscovered(BluetoothAudio.wire(ctx, it, connected = false)) }
        // An empty scan is a report, not a failure: the backend says how to enter pairing mode.
        return Outcome.DONE to ""
    }

    private fun pair(id: String): Pair<Outcome, String> {
        val dev = BluetoothAudio.fromLastScan(id, nowMs()) ?: return Outcome.FAILED to "not from the last scan"
        if (!ensureOn()) return Outcome.BLUETOOTH_OFF to ""
        if (stack.bondState(dev.address) != BluetoothDevice.BOND_BONDED) {
            if (!stack.createBond(dev.address)) return Outcome.FAILED to "createBond refused"
            // Android shows its own pairing confirmation; this only watches the bond state.
            var sawBonding = false
            var ended = false
            val bonded = waitFor(BOND_MS) {
                when (stack.bondState(dev.address)) {
                    BluetoothDevice.BOND_BONDED -> true
                    BluetoothDevice.BOND_BONDING -> { sawBonding = true; false }
                    // Back to none after trying: refused, cancelled, or the stack gave up.
                    else -> {
                        ended = sawBonding || stack.lastUnbondReason(dev.address) != null
                        if (ended) throw BondEnded() else false
                    }
                }
            }
            if (!bonded) return pairFailure(dev, sawBonding, timedOut = !ended)
        }
        return connectBonded(dev, ifNot = Outcome.PAIRED_NOT_CONNECTED)
    }

    private class BondEnded : RuntimeException()

    private fun pairFailure(dev: BluetoothStack.Dev, sawBonding: Boolean, timedOut: Boolean): Pair<Outcome, String> {
        val reason = stack.lastUnbondReason(dev.address)
        return when (reason) {
            UNBOND_AUTH_FAILED, UNBOND_AUTH_REJECTED, UNBOND_AUTH_CANCELED, UNBOND_REMOTE_AUTH_CANCELED ->
                Outcome.PAIR_DECLINED to "unbond reason $reason"
            UNBOND_REMOTE_DEVICE_DOWN, UNBOND_AUTH_TIMEOUT -> Outcome.NOT_IN_RANGE to "unbond reason $reason"
            null -> when {
                timedOut && sawBonding -> Outcome.PAIR_DECLINED to "no answer to the pairing request in ${BOND_MS / 1000}s"
                timedOut -> Outcome.NOT_IN_RANGE to "no bonding progress in ${BOND_MS / 1000}s"
                else -> Outcome.PAIR_DECLINED to "pairing ended"
            }
            else -> Outcome.FAILED to "unbond reason $reason"
        }
    }

    /** Polls [ok] until it holds or [limitMs] passes. A [BondEnded] from [ok] ends it as false. */
    private fun waitFor(limitMs: Long, ok: () -> Boolean): Boolean {
        val end = nowMs() + limitMs
        while (true) {
            try { if (ok()) return true } catch (_: BondEnded) { return false }
            if (stopped() || nowMs() >= end) return false
            sleep(POLL_MS)
        }
    }

    companion object {
        private const val TAG = "RistBluetooth"
        internal const val POLL_MS = 250L
        internal const val ENABLE_MS = 5_000L
        internal const val CONNECT_MS = 15_000L
        internal const val DISCONNECT_MS = 10_000L
        internal const val BOND_MS = 30_000L
        internal const val MAX_SCAN_S = 30

        // BluetoothDevice.UNBOND_REASON_* are hidden; these are their values.
        internal const val UNBOND_AUTH_FAILED = 1
        internal const val UNBOND_AUTH_REJECTED = 2
        internal const val UNBOND_AUTH_CANCELED = 3
        internal const val UNBOND_REMOTE_DEVICE_DOWN = 4
        internal const val UNBOND_AUTH_TIMEOUT = 6
        internal const val UNBOND_REMOTE_AUTH_CANCELED = 8
    }
}
