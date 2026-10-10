package watch.rist.assistant

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothDevice
import rist.v1.BluetoothCommand
import rist.v1.BluetoothCommand.Action
import rist.v1.BluetoothResult.Outcome
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import rist.v1.Speech

/**
 * Bluetooth headphones by voice (v32, "bluetooth_v1"): the phone reports its paired audio devices
 * on every turn, carries out the one command the backend sends, and sends the turn again with what
 * really happened. Never says it worked itself, never unpairs, never answers the pairing dialog.
 */
@RunWith(RobolectricTestRunner::class)
class BluetoothTest {

    private lateinit var server: MockWebServer
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val shokz = BluetoothStack.Dev("00:11:22:33:44:55", "OpenRun Pro by Shokz",
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, BluetoothClass.Device.Major.AUDIO_VIDEO)
    private val keyboard = BluetoothStack.Dev("00:11:22:33:44:66", "Keyboard",
        BluetoothClass.Device.PERIPHERAL_KEYBOARD, BluetoothClass.Device.Major.PERIPHERAL)
    private val airpods = BluetoothStack.Dev("AA:BB:CC:DD:EE:01", "Sam's AirPods Pro",
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET, BluetoothClass.Device.Major.AUDIO_VIDEO)
    private val mouse = BluetoothStack.Dev("AA:BB:CC:DD:EE:02", "Mouse",
        BluetoothClass.Device.PERIPHERAL_POINTING, BluetoothClass.Device.Major.PERIPHERAL)

    /** A phone's Bluetooth with no radio: every change is one the test makes or allows. */
    private class FakeStack(
        var on: Boolean = true,
        var permitted: Boolean = true,
        var canEnable: Boolean = true,
        val bonded: MutableList<BluetoothStack.Dev> = mutableListOf(),
        val connectedSet: MutableSet<String> = mutableSetOf(),
        var answers: Boolean = true,
        var nearby: List<BluetoothStack.Dev> = emptyList(),
        /** What the person does with Android's pairing confirmation: null = never answers. */
        var personAccepts: Boolean? = true,
        var unbondReason: Int? = null,
    ) : BluetoothStack {
        val calls = mutableListOf<String>()
        val bonds = mutableMapOf<String, Int>()
        var onConnect: () -> Unit = {}

        override fun present() = true
        override fun permitted() = permitted
        override fun isOn() = on
        override fun requestEnable(): Boolean { calls += "enable"; if (canEnable) on = true; return canEnable }
        override fun bonded() = if (on) bonded.toList() else emptyList()
        override fun connected(address: String) = on && address in connectedSet
        override fun bondState(address: String) = when {
            bonded.any { it.address == address } -> BluetoothDevice.BOND_BONDED
            else -> bonds[address] ?: BluetoothDevice.BOND_NONE
        }
        override fun connect(address: String): String? {
            calls += "connect $address"
            onConnect()
            if (answers) connectedSet += address
            return null
        }
        override fun disconnect(address: String): String? { calls += "disconnect $address"; connectedSet -= address; return null }
        override fun createBond(address: String): Boolean {
            calls += "createBond $address"
            when (personAccepts) {
                true -> bonded += nearby.first { it.address == address }
                false -> { bonds[address] = BluetoothDevice.BOND_NONE; unbondReason = BluetoothCommands.UNBOND_AUTH_CANCELED }
                null -> bonds[address] = BluetoothDevice.BOND_BONDING
            }
            return true
        }
        override fun lastUnbondReason(address: String) = unbondReason
        override fun discover(seconds: Int, stop: () -> Boolean): List<BluetoothStack.Dev> {
            calls += "discover $seconds"
            return nearby + bonded
        }
    }

    private lateinit var fake: FakeStack

    @Before
    fun start() {
        Config.setDeployDefaultsForTest("", "")
        server = MockWebServer().apply { start() }
        Config.setBackendEndpoint(app, server.url("/v1/device").toString())
        Config.setEnrolRevoked(app, false)
        Config.setCredentialRejected(app, false)
        Config.clearBillingLapse(app)
        StreamingCancel.resetForTest()
        BluetoothAudio.resetForTest()
        AndroidBluetoothStack.Proxies.resetForTest()
        fake = FakeStack(bonded = mutableListOf(shokz, keyboard))
        BluetoothAudio.stackFor = { fake }
    }

    @After
    fun stop() {
        BluetoothAudio.resetForTest()
        AndroidBluetoothStack.Proxies.resetForTest()
        Config.clearDeployDefaultsForTest()
        runCatching { server.shutdown() }
    }

    private fun reply(build: DeviceResponse.Builder.() -> Unit) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/x-protobuf")
        .setBody(Buffer().write(DeviceResponse.newBuilder().apply(build).build().toByteArray()))

    private fun command(action: Action, device: BluetoothStack.Dev? = null, id: String = "c-${action.name}") = reply {
        speech = Speech.newBuilder().setText("One moment.").build()
        bluetooth = BluetoothCommand.newBuilder().setCommandId(id).setAction(action)
            .setDeviceId(device?.let { BluetoothAudio.idOf(app, it.address) } ?: "")
            .setDeviceName(device?.name ?: "")
            .setScanS(if (action == Action.SCAN) 12 else 0)
            .build()
    }

    private fun answer(text: String) = reply { speech = Speech.newBuilder().setText(text).build() }

    private fun sent(): DeviceRequest =
        DeviceRequest.parseFrom(server.takeRequest(30, TimeUnit.SECONDS)!!.body.readByteArray())

    private fun idOf(d: BluetoothStack.Dev) = BluetoothAudio.idOf(app, d.address)

    // ── the report ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `every turn declares the component and reports the paired audio devices only`() {
        fake.connectedSet += shokz.address
        server.enqueue(answer("Sunny."))

        Uploader(app).sendText("what's the weather")

        val req = sent()
        assertTrue(BluetoothAudio.COMPONENT in req.caps.componentsList)
        assertEquals("the schema version stays the handset's", DeviceProfile.RCS_SCHEMA_VERSION, req.caps.schemaVersion)
        assertTrue(req.hasBluetooth())
        assertTrue(req.bluetooth.adapterOn)
        assertTrue(req.bluetooth.permission)
        assertEquals("no keyboards", listOf("OpenRun Pro by Shokz"), req.bluetooth.devicesList.map { it.name })
        val d = req.bluetooth.getDevices(0)
        assertEquals("headphones", d.kind)
        assertTrue(d.connected)
        assertEquals(16, d.id.length)
        assertFalse("never the address", d.id.contains(":") || d.id.equals(shokz.address.replace(":", ""), true))
        assertEquals("stable for the install", d.id, idOf(shokz))
    }

    @Test
    fun `with nothing paired an empty list is still a report`() {
        fake.bonded.clear()
        server.enqueue(answer("None."))
        Uploader(app).sendText("what bluetooth devices do I have")
        val req = sent()
        assertTrue(req.hasBluetooth())
        assertEquals(0, req.bluetooth.devicesCount)
    }

    @Test
    fun `without Nearby devices the report says so and lists nothing`() {
        fake.permitted = false
        server.enqueue(answer("Allow it."))
        Uploader(app).sendText("connect my shokz")
        val req = sent()
        assertFalse(req.bluetooth.permission)
        assertEquals(0, req.bluetooth.devicesCount)
    }

    @Test
    fun `with Bluetooth off the devices last seen are still named, none connected`() {
        server.enqueue(answer("ok"))
        Uploader(app).sendText("hello")
        sent()
        fake.on = false
        server.enqueue(answer("ok"))
        Uploader(app).sendText("connect my shokz")
        val req = sent()
        assertFalse(req.bluetooth.adapterOn)
        assertEquals(listOf(idOf(shokz)), req.bluetooth.devicesList.map { it.id })
        assertFalse(req.bluetooth.getDevices(0).connected)
    }

    // ── commands and the re-send ───────────────────────────────────────────────────────────

    @Test
    fun `connect is carried out and the same turn is sent again with what really happened`() {
        server.enqueue(command(Action.CONNECT, shokz))
        server.enqueue(answer("Connected to OpenRun Pro by Shokz."))

        val out = Uploader(app).sendText("connect my shokz")

        val first = sent()
        val second = sent()
        assertEquals(listOf("connect ${shokz.address}"), fake.calls)
        assertEquals("connect my shokz", second.text)
        assertNotEquals(first.requestId, second.requestId)
        assertNotEquals(first.utteranceId, second.utteranceId)
        assertFalse(first.hasBluetoothResult())
        val r = second.bluetoothResult
        assertEquals("c-CONNECT", r.commandId)
        assertEquals(Action.CONNECT, r.action)
        assertEquals(idOf(shokz), r.deviceId)
        assertEquals(Outcome.DONE, r.outcome)
        assertTrue("read after the command", second.bluetooth.devicesList.single().connected)
        assertEquals("Connected to OpenRun Pro by Shokz.", out!!.speech.text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an already connected device is left alone and reported ALREADY`() {
        fake.connectedSet += shokz.address
        server.enqueue(command(Action.CONNECT, shokz))
        server.enqueue(answer("Already connected."))
        Uploader(app).sendText("connect my shokz")
        sent()
        assertEquals(Outcome.ALREADY, sent().bluetoothResult.outcome)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `Bluetooth off is switched on to connect`() {
        fake.on = false
        server.enqueue(command(Action.CONNECT, shokz))
        server.enqueue(answer("Connected."))
        Uploader(app).sendText("connect my shokz")
        sent()
        val second = sent()
        assertEquals(listOf("enable", "connect ${shokz.address}"), fake.calls)
        assertEquals(Outcome.DONE, second.bluetoothResult.outcome)
        assertTrue(second.bluetooth.adapterOn)
    }

    @Test
    fun `Bluetooth that cannot be switched on is reported BLUETOOTH_OFF`() {
        fake.on = false
        fake.canEnable = false
        server.enqueue(command(Action.CONNECT, shokz))
        server.enqueue(answer("Turn it on."))
        Uploader(app).sendText("connect my shokz")
        sent()
        assertEquals(Outcome.BLUETOOTH_OFF, sent().bluetoothResult.outcome)
    }

    @Test
    fun `without the permission nothing is attempted`() {
        fake.permitted = false
        server.enqueue(command(Action.CONNECT, shokz))
        server.enqueue(answer("Allow it."))
        Uploader(app).sendText("connect my shokz")
        sent()
        assertEquals(Outcome.NO_PERMISSION, sent().bluetoothResult.outcome)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `an id that is no longer paired is NOT_PAIRED`() {
        server.enqueue(command(Action.CONNECT, airpods))
        server.enqueue(answer("Not paired."))
        Uploader(app).sendText("connect my airpods")
        sent()
        assertEquals(Outcome.NOT_PAIRED, sent().bluetoothResult.outcome)
    }

    @Test
    fun `disconnect disconnects and never unpairs`() {
        fake.connectedSet += shokz.address
        server.enqueue(command(Action.DISCONNECT, shokz))
        server.enqueue(answer("Disconnected."))
        Uploader(app).sendText("disconnect my headphones")
        sent()
        val second = sent()
        assertEquals(Outcome.DONE, second.bluetoothResult.outcome)
        assertEquals(listOf("disconnect ${shokz.address}"), fake.calls)
        assertEquals("still paired", listOf(idOf(shokz)), second.bluetooth.devicesList.map { it.id })
        assertFalse(second.bluetooth.getDevices(0).connected)
    }

    @Test
    fun `scan reports audio devices in pairing mode, then the pair that follows connects`() {
        fake.nearby = listOf(airpods, mouse)
        server.enqueue(command(Action.SCAN))
        server.enqueue(command(Action.PAIR, airpods))
        server.enqueue(answer("Paired and connected Sam's AirPods Pro."))

        val out = Uploader(app).sendText("pair my airpods")

        val first = sent()
        val scanned = sent()
        val paired = sent()
        assertEquals(Action.SCAN, scanned.bluetoothResult.action)
        assertEquals(Outcome.DONE, scanned.bluetoothResult.outcome)
        assertEquals("audio, not already paired", listOf("Sam's AirPods Pro"),
            scanned.bluetoothResult.discoveredList.map { it.name })
        assertEquals(idOf(airpods), scanned.bluetoothResult.getDiscovered(0).id)
        assertEquals(Action.PAIR, paired.bluetoothResult.action)
        assertEquals(Outcome.DONE, paired.bluetoothResult.outcome)
        assertEquals(listOf("discover 12", "createBond ${airpods.address}", "connect ${airpods.address}"), fake.calls)
        assertEquals(setOf(first.text), setOf(scanned.text, paired.text))
        assertEquals("Paired and connected Sam's AirPods Pro.", out!!.speech.text)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `an empty scan is a report, not a failure`() {
        server.enqueue(command(Action.SCAN))
        server.enqueue(answer("Put them in pairing mode."))
        Uploader(app).sendText("pair my airpods")
        sent()
        val r = sent().bluetoothResult
        assertEquals(Outcome.DONE, r.outcome)
        assertEquals(0, r.discoveredCount)
    }

    @Test
    fun `a cancelled pairing request is PAIR_DECLINED`() {
        fake.nearby = listOf(airpods)
        fake.personAccepts = false
        server.enqueue(command(Action.SCAN))
        server.enqueue(command(Action.PAIR, airpods))
        server.enqueue(answer("Pairing was cancelled."))
        Uploader(app).sendText("pair my airpods")
        sent(); sent()
        assertEquals(Outcome.PAIR_DECLINED, sent().bluetoothResult.outcome)
    }

    @Test
    fun `a third command for one utterance is ignored`() {
        fake.nearby = listOf(airpods)
        server.enqueue(command(Action.SCAN))
        server.enqueue(command(Action.PAIR, airpods))
        server.enqueue(command(Action.CONNECT, airpods))
        val out = Uploader(app).sendText("pair my airpods")
        assertEquals(3, server.requestCount)
        assertNotNull(out)
        assertFalse(out!!.hasBluetooth())
    }

    @Test
    fun `a stopped turn is not sent again`() {
        fake.answers = false
        fake.onConnect = { StreamingCancel.cancelInFlight() }
        server.enqueue(command(Action.CONNECT, shokz))
        val out = Uploader(app).sendText("connect my shokz")
        assertNull(out)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a check-in never carries out a command`() {
        server.enqueue(command(Action.CONNECT, shokz))
        Uploader(app).reportGeofenceCrossings()
        assertEquals(1, server.requestCount)
        assertTrue(fake.calls.isEmpty())
    }

    // ── the waits, on a clock the test turns ────────────────────────────────────────────────

    private fun commands(stack: BluetoothStack): BluetoothCommands {
        var now = 0L
        return BluetoothCommands(app, stack, nowMs = { now }, sleep = { now += it })
    }

    private fun cmd(action: Action, d: BluetoothStack.Dev?) = BluetoothCommand.newBuilder()
        .setCommandId("c").setAction(action).setDeviceId(d?.let { idOf(it) } ?: "").setScanS(5).build()

    @Test
    fun `a device that does not answer in 15 s is NOT_IN_RANGE`() {
        fake.answers = false
        assertEquals(Outcome.NOT_IN_RANGE, commands(fake).run(cmd(Action.CONNECT, shokz)).outcome)
    }

    @Test
    fun `pair is only for a device from the last scan`() {
        val r = commands(fake).run(cmd(Action.PAIR, airpods))
        assertEquals(Outcome.FAILED, r.outcome)
        assertEquals("not from the last scan", r.detail)
    }

    @Test
    fun `an unanswered pairing request ends as PAIR_DECLINED, and bonded but silent is PAIRED_NOT_CONNECTED`() {
        fake.nearby = listOf(airpods)
        val c = commands(fake)
        c.run(cmd(Action.SCAN, null))
        fake.personAccepts = null
        assertEquals(Outcome.PAIR_DECLINED, c.run(cmd(Action.PAIR, airpods)).outcome)

        fake.personAccepts = true
        fake.answers = false
        assertEquals(Outcome.PAIRED_NOT_CONNECTED, c.run(cmd(Action.PAIR, airpods)).outcome)
    }

    @Test
    fun `kind comes from the device class, never the name`() {
        fun k(cls: Int, name: String = "Bose") = BluetoothAudio.kindOf(
            BluetoothStack.Dev("A", name, cls, BluetoothClass.Device.Major.AUDIO_VIDEO))
        assertEquals("headphones", k(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
        assertEquals("headset", k(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE))
        assertEquals("speaker", k(BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, "Headphones"))
        assertEquals("car", k(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO))
        assertEquals("other", k(BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR))
        assertEquals("hearing_aid", BluetoothAudio.kindOf(BluetoothStack.Dev("A", "x",
            uuids = setOf("0000fdf0-0000-1000-8000-00805f9b34fb"))))
    }

    // ── the real stack, on Robolectric's shadow Bluetooth ───────────────────────────────────

    private fun adapter(): BluetoothAdapter =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun classOf(deviceClass: Int): BluetoothClass =
        BluetoothClass::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(deviceClass)

    private fun shadowDevice(address: String, name: String, deviceClass: Int): BluetoothDevice =
        ShadowBluetoothDevice.newInstance(address).also {
            shadowOf(it).setName(name)
            shadowOf(it).setBluetoothClass(classOf(deviceClass))
            shadowOf(it).setBondState(BluetoothDevice.BOND_BONDED)
        }

    /** A profile proxy with [connected] connected. */
    private class Profile(val connected: Set<String>) : BluetoothProfile {
        override fun getConnectedDevices(): List<BluetoothDevice> = emptyList()
        override fun getDevicesMatchingConnectionStates(states: IntArray?): List<BluetoothDevice> = emptyList()
        override fun getConnectionState(device: BluetoothDevice?): Int =
            if (device?.address in connected) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED
    }

    @Test
    fun `the real stack lists bonded audio devices with alias, kind and connection from the profiles`() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        val a = adapter()
        shadowOf(a).setEnabled(true)
        val phones = shadowDevice("00:11:22:33:44:55", "OpenRun Pro", BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES)
        shadowOf(phones).setAlias("My Shokz")
        val kb = shadowDevice("00:11:22:33:44:66", "Keyboard", BluetoothClass.Device.PERIPHERAL_KEYBOARD)
        shadowOf(a).setBondedDevices(setOf(phones, kb))
        shadowOf(a).setProfileProxy(BluetoothProfile.A2DP, Profile(setOf("00:11:22:33:44:55")))

        val state = BluetoothAudio.state(app, AndroidBluetoothStack(app))

        assertTrue(state.adapterOn)
        assertTrue(state.permission)
        val d = state.devicesList.single()
        assertEquals("My Shokz", d.name)
        assertEquals("headphones", d.kind)
        assertTrue(d.connected)
        assertEquals(BluetoothAudio.idOf(app, "00:11:22:33:44:55"), d.id)
    }

    @Test
    fun `the real stack reports no permission when Nearby devices is not granted`() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(adapter()).setEnabled(true)
        val state = BluetoothAudio.state(app, AndroidBluetoothStack(app))
        assertFalse(state.permission)
        assertEquals(0, state.devicesCount)
    }

    @Test
    fun `the real stack switches Bluetooth on`() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(adapter()).setEnabled(false)
        val stack = AndroidBluetoothStack(app)
        assertFalse(stack.isOn())
        assertTrue(stack.requestEnable())
        assertTrue(stack.isOn())
    }

    @Test
    fun `pairing on the real stack starts a bond and never answers the confirmation`() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(adapter()).setEnabled(true)
        val device = ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:01")
        shadowOf(device).setCreatedBond(true)
        shadowOf(adapter()).setBondedDevices(setOf(device))
        val stack = AndroidBluetoothStack(app)
        stack.bonded()

        assertTrue(stack.createBond("AA:BB:CC:DD:EE:01"))

        assertNull("setPairingConfirmation never called", shadowOf(device).pairingConfirmation)
    }
}
