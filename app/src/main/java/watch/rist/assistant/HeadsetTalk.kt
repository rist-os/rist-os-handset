package watch.rist.assistant

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log

/**
 * Holding the call button on a Bluetooth headset talks to Rist.
 *
 * A headset asks the phone for its voice assistant over the hands-free link (AT+BVRA=1). The
 * phone's Bluetooth service answers by starting `ACTION_VOICE_COMMAND` and gives the app five
 * seconds to take the request with [BluetoothHeadset.startVoiceRecognition], which opens the
 * headset's microphone. [HeadsetTalkActivity] takes that intent; [RecordService] runs the turn: it
 * listens through the headset until the next press, or the on-screen button's time cap, then sends.
 *
 * A press while it listens reaches the phone one of two ways, and both send. Over an open voice
 * session the headset ends the session (AT+BVRA=0, or the request again, which Bluetooth answers by
 * ending the open one), and its audio link closes. Without one, as when listening fell back to the
 * phone's microphone, the press is a fresh request: `ACTION_VOICE_COMMAND` again.
 *
 * A single tap is play/pause, sent to the media player and never seen here.
 */
object HeadsetTalk {

    internal const val TAG = "RistHeadset"

    /** The Bluetooth service's app id (`android.uid.bluetooth`). */
    private const val BLUETOOTH_APP_ID = 1002

    /** True from the press until the turn stops listening; a press meanwhile ends it. */
    @Volatile var listening = false
        internal set

    internal var linkFactory: (Context) -> HeadsetLink = { BluetoothHeadsetLink(it) }

    internal var launcherUidOf: (Activity) -> Int = { a ->
        // Known to a platform-signed app without the sender opting in.
        runCatching { a.launchedFromUid }.getOrDefault(-1)
    }

    /**
     * Only the system or the Bluetooth service stands for a press. Any app can start
     * `ACTION_VOICE_COMMAND`, and recording needs a person's gesture.
     */
    internal fun pressedOnHeadset(uid: Int): Boolean {
        if (uid < 0) return false
        val appId = uid % 100_000
        return appId == Process.SYSTEM_UID || appId == BLUETOOTH_APP_ID
    }

    fun onVoiceCommand(ctx: Context, launchedFromUid: Int) {
        if (!pressedOnHeadset(launchedFromUid)) {
            Log.w(TAG, "voice command from uid $launchedFromUid ignored: not a headset press")
            return
        }
        val action = if (listening) RecordService.ACTION_STOP else RecordService.ACTION_START
        Log.i(TAG, "headset press: ${if (listening) "stop listening" else "start a turn"}")
        val svc = Intent(ctx, RecordService::class.java).setAction(action)
            .putExtra(RecordService.EXTRA_HEADSET, true)
        runCatching { ctx.startForegroundService(svc) }
            .onFailure { Log.w(TAG, "could not start the turn", it) }
    }
}

/** Shows nothing: takes the headset's request and is gone. Starts behind the lock screen too. */
class HeadsetTalkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_VOICE_COMMAND) {
            HeadsetTalk.onVoiceCommand(this, HeadsetTalk.launcherUidOf(this))
        }
        finish()
    }
}

/** The headset's microphone for one turn. */
internal interface HeadsetLink {
    /**
     * Opens it. [onReady] runs once, on the main thread, with the input to record from, or null
     * for the phone's own microphone. [onDropped] runs if the headset closes it after that, which
     * is what a press does while it listens.
     */
    fun open(onReady: (AudioDeviceInfo?) -> Unit, onDropped: () -> Unit)

    /** Gives the microphone back and undoes any routing [open] set. Safe to call more than once. */
    fun close()
}

/** The hands-free profile, narrowed to what a turn needs. */
internal interface Hfp {
    /**
     * Takes the headset's voice request. [started] gets the headset's address, or null when no
     * hands-free headset is connected or the request was refused. [audio] reports the headset's
     * audio link coming up (true) and going down (false).
     */
    fun start(audio: (Boolean) -> Unit, started: (String?) -> Unit)
    fun stop()
}

internal class BluetoothHeadsetLink(
    private val ctx: Context,
    private val hfp: Hfp = SystemHfp(ctx),
) : HeadsetLink {

    companion object {
        /** The headset's audio link usually comes up in under a second. */
        internal const val AUDIO_WAIT_MS = 3_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var ready: ((AudioDeviceInfo?) -> Unit)? = null
    private var dropped: (() -> Unit)? = null
    private var hfpOpen = false
    private var audioUp = false
    private var routedByUs = false
    private val gaveUp = Runnable {
        Log.w(HeadsetTalk.TAG, "headset audio did not come up; using the phone's microphone")
        closeHfp()
        deliver(null)
    }

    override fun open(onReady: (AudioDeviceInfo?) -> Unit, onDropped: () -> Unit) {
        ready = onReady
        dropped = onDropped
        hfp.start(
            audio = { up -> main.post { onAudio(up) } },
            started = { address -> main.post { onStarted(address) } },
        )
    }

    private fun onStarted(address: String?) {
        if (ready == null) return
        if (address == null) {
            deliver(leAudioInput())
            return
        }
        hfpOpen = true
        if (audioUp) deliver(input(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        else main.postDelayed(gaveUp, AUDIO_WAIT_MS)
    }

    private fun onAudio(up: Boolean) {
        if (up) {
            audioUp = true
            if (hfpOpen && ready != null) {
                main.removeCallbacks(gaveUp)
                deliver(input(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
            }
        } else if (audioUp) {
            audioUp = false
            hfpOpen = false
            dropped?.invoke()
        }
    }

    private fun deliver(input: AudioDeviceInfo?) {
        val r = ready ?: return
        ready = null
        Log.i(HeadsetTalk.TAG, "listening on ${input?.let { "headset (type ${it.type})" } ?: "the phone's microphone"}")
        r(input)
    }

    /** A Bluetooth LE Audio headset has no hands-free link; it is chosen as the call device. */
    private fun leAudioInput(): AudioDeviceInfo? {
        val audio = am ?: return null
        val le = runCatching { audio.availableCommunicationDevices }.getOrNull()
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET } ?: return null
        if (!runCatching { audio.setCommunicationDevice(le) }.getOrDefault(false)) return null
        routedByUs = true
        return input(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun input(type: Int): AudioDeviceInfo? =
        runCatching { am?.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrNull()
            ?.firstOrNull { it.type == type }

    private fun closeHfp() {
        if (!hfpOpen) return
        hfpOpen = false
        runCatching { hfp.stop() }
    }

    override fun close() {
        main.removeCallbacks(gaveUp)
        ready = null
        dropped = null
        audioUp = false
        // Also stops a request that is still on its way in.
        hfpOpen = true
        closeHfp()
        if (routedByUs) {
            routedByUs = false
            runCatching { am?.clearCommunicationDevice() }
        }
    }
}

@SuppressLint("MissingPermission")
private class SystemHfp(private val ctx: Context) : Hfp {

    private val adapter = runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
    private var proxy: BluetoothHeadset? = null
    private var device: BluetoothDevice? = null
    private var receiver: BroadcastReceiver? = null
    private var stopped = false

    override fun start(audio: (Boolean) -> Unit, started: (String?) -> Unit) {
        val bt = adapter
        if (bt == null || !bt.isEnabled) { started(null); return }
        val asked = runCatching {
            bt.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                    val hs = p as BluetoothHeadset
                    proxy = hs
                    if (stopped) { release(); return }
                    val d = runCatching { hs.connectedDevices.firstOrNull() }.getOrNull()
                    if (d == null) { started(null); return }
                    watchAudio(d, audio)
                    if (!runCatching { hs.startVoiceRecognition(d) }.getOrDefault(false)) {
                        Log.w(HeadsetTalk.TAG, "the headset's voice request was refused")
                        unwatch()
                        started(null)
                        return
                    }
                    device = d
                    started(d.address)
                }

                override fun onServiceDisconnected(profile: Int) { proxy = null }
            }, BluetoothProfile.HEADSET)
        }.getOrDefault(false)
        if (!asked) started(null)
    }

    private fun watchAudio(d: BluetoothDevice, audio: (Boolean) -> Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val from = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (from != null && from != d) return
                when (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                    BluetoothHeadset.STATE_AUDIO_CONNECTED -> audio(true)
                    BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> audio(false)
                }
            }
        }
        receiver = r
        ctx.registerReceiver(r, IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED), Context.RECEIVER_EXPORTED)
    }

    private fun unwatch() {
        receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }
        receiver = null
    }

    override fun stop() {
        stopped = true
        unwatch()
        val hs = proxy
        val d = device
        device = null
        if (hs != null && d != null) runCatching { hs.stopVoiceRecognition(d) }
        release()
    }

    private fun release() {
        val hs = proxy ?: return
        proxy = null
        runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, hs) }
    }
}

/**
 * One headset turn's hold on the phone: media paused while it listens and until its reply has
 * been spoken, the headset's microphone while it listens, and the CPU awake meanwhile.
 */
internal class HeadsetTurn(
    private val ctx: Context,
    private val link: HeadsetLink,
) {
    companion object {
        private const val CUE_MS = 120
        /** A reply that never reports its end still hands media back. */
        private const val MEDIA_HELD_MAX_MS = 3 * 60_000L
        /** Past the time cap, with room for the headset's audio to come up first. */
        private const val AWAKE_MAX_MS = RecordService.MAX_CAPTURE_MS + 15_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var routeOpen = false
    private var wake: PowerManager.WakeLock? = null
    private val releaseMediaLate = Runnable { releaseMedia() }

    fun begin(onReady: (AudioDeviceInfo?) -> Unit, onPressedAgain: () -> Unit) {
        holdMedia()
        wake = runCatching {
            ctx.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rist:headset-turn")
                ?.apply { setReferenceCounted(false); acquire(AWAKE_MAX_MS) }
        }.getOrNull()
        routeOpen = true
        link.open(onReady, onPressedAgain)
    }

    /** Other players pause, as for any voice assistant, and play on when this is let go. */
    private fun holdMedia() {
        val audio = am ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        if (runCatching { audio.requestAudioFocus(req) }.getOrNull() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focus = req
        }
        main.postDelayed(releaseMediaLate, MEDIA_HELD_MAX_MS)
    }

    /** The listening cue: the usual tap, and a short tone in the headset before the mic opens. */
    fun cue(input: AudioDeviceInfo?, then: () -> Unit) {
        Haptics.ack(ctx)
        val out = input?.let { i ->
            runCatching { am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
                ?.firstOrNull { it.type == i.type }
        }
        val played = runCatching { tone(out) }.getOrDefault(false)
        if (played) main.postDelayed(then, CUE_MS + 80L) else then()
    }

    private fun tone(out: AudioDeviceInfo?): Boolean {
        val rate = 16_000
        val n = rate * CUE_MS / 1000
        val pcm = ShortArray(n) { i ->
            val fade = minOf(1.0, minOf(i, n - i) / (rate * 0.01))
            (kotlin.math.sin(2 * Math.PI * 880 * i / rate) * 6000 * fade).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(n * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        if (out != null) track.setPreferredDevice(out)
        track.write(pcm, 0, n)
        track.play()
        main.postDelayed({ runCatching { track.release() } }, CUE_MS + 400L)
        return true
    }

    /** Listening is over: the headset's microphone and its routing go back at once. */
    fun releaseRoute() {
        if (!routeOpen) return
        routeOpen = false
        runCatching { link.close() }
        wake?.let { w -> runCatching { if (w.isHeld) w.release() } }
        wake = null
    }

    /** The reply has been spoken, or there is none: paused media plays on. */
    fun releaseMedia() {
        main.removeCallbacks(releaseMediaLate)
        val req = focus ?: return
        focus = null
        runCatching { am?.abandonAudioFocusRequest(req) }
    }

    fun end() {
        releaseRoute()
        releaseMedia()
    }
}
