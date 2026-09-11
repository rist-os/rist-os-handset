package watch.rist.assistant

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.AlarmCommand

/**
 * The bug: setting an alarm and restarting the phone silently cancelled it.
 *
 * Android drops every AlarmManager alarm on reboot, and nothing on the device recorded that
 * the alarm had ever been set, so there was nothing to restore it from.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmRebootTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun nowS() = System.currentTimeMillis() / 1000

    private fun armCommand(id: String, atEpochS: Long, label: String = "wake up") =
        AlarmCommand.newBuilder()
            .setAction("arm").setAlarmId(id).setKind("alarm")
            .setFireAtEpochS(atEpochS).setLabel(label)
            .setSound(true).setVibrate(true)
            .build()

    private fun scheduled() =
        shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms

    /** What the OS does across a restart: every pending alarm is dropped. */
    private fun simulateReboot() {
        val am = app.getSystemService(android.app.AlarmManager::class.java)
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) }
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    /** Stands in for the encrypted store, which a unit test has no keystore to open. */
    private object Memory : Alarms.Store {
        var json = "[]"
        override fun read(ctx: android.content.Context) = json
        override fun write(ctx: android.content.Context, json: String) { this.json = json }
    }

    private lateinit var realStore: Alarms.Store

    @Before
    fun useMemoryStore() {
        realStore = Alarms.store
        Memory.json = "[]"
        Alarms.store = Memory
    }

    @After
    fun restoreStore() {
        // Alarms is an object, so the swap would otherwise leak into every later test class.
        Alarms.store = realStore
        Memory.json = "[]"
    }

    @Test
    fun `an alarm set before a restart is still armed after it`() {
        val at = nowS() + 3600
        DeviceCommands.alarm(app, armCommand("a1", at))
        assertEquals(1, scheduled().size)

        simulateReboot()

        assertEquals("the alarm should be back after the reboot", 1, scheduled().size)
        assertEquals(at, Alarms.held(app).single().fireAtEpochS)
    }

    @Test
    fun `the stored alarm keeps its label and its settings`() {
        DeviceCommands.alarm(app, armCommand("a1", nowS() + 60, label = "take the pills"))
        val held = Alarms.held(app).single()
        assertEquals("take the pills", held.label)
        assertEquals("a1", held.id)
        assertTrue(held.sound)
        assertTrue(held.vibrate)
    }

    @Test
    fun `a cancelled alarm does not come back after a restart`() {
        DeviceCommands.alarm(app, armCommand("a1", nowS() + 3600))
        DeviceCommands.alarm(
            app,
            AlarmCommand.newBuilder().setAction("cancel").setAlarmId("a1").build(),
        )
        assertTrue("nothing should be stored", Alarms.held(app).isEmpty())

        simulateReboot()

        assertTrue("a cancelled alarm must stay cancelled", scheduled().isEmpty())
    }

    @Test
    fun `an alarm missed by moments still rings`() {
        // Set for 07:00:00; the phone reached BOOT_COMPLETED at 07:00:20. Discarding that is
        // the one thing an alarm clock must never do.
        Memory.json =
            """[{"id":"close","at":${nowS() - 20},"label":"x","sound":true,"vibrate":true}]"""
        simulateReboot()
        assertEquals("it should be armed to ring now", 1, scheduled().size)
        assertEquals(1, Alarms.held(app).size)
    }

    @Test
    fun `an alarm whose time passed while the phone was off does not fire late`() {
        // Waking someone at 09:00 for an alarm they set for 07:00 is worse than silence.
        Memory.json =
            """[{"id":"old","at":${nowS() - 3600},"label":"x","sound":true,"vibrate":true}]"""
        simulateReboot()
        assertTrue(scheduled().isEmpty())
        assertTrue("and it should be dropped from the store", Alarms.held(app).isEmpty())
    }

    @Test
    fun `several alarms all survive a restart`() {
        val t = nowS()
        DeviceCommands.alarm(app, armCommand("a1", t + 600))
        DeviceCommands.alarm(app, armCommand("a2", t + 1200))
        DeviceCommands.alarm(app, armCommand("a3", t + 1800))

        simulateReboot()

        assertEquals(3, scheduled().size)
        assertEquals(setOf("a1", "a2", "a3"), Alarms.held(app).map { it.id }.toSet())
    }

    @Test
    fun `re-arming the same id replaces it rather than duplicating it`() {
        DeviceCommands.alarm(app, armCommand("a1", nowS() + 600))
        DeviceCommands.alarm(app, armCommand("a1", nowS() + 900))
        assertEquals(1, Alarms.held(app).size)
    }

    @Test
    fun `a snooze survives a restart at the snoozed time, not the original`() {
        val original = nowS() + 30
        DeviceCommands.alarm(app, armCommand("a1", original))
        DeviceCommands.alarm(
            app,
            AlarmCommand.newBuilder()
                .setAction("snooze").setAlarmId("a1").setLabel("wake up")
                .setSound(true).setVibrate(true).build(),
        )
        val stored = Alarms.held(app).single()
        assertTrue(
            "snoozed time ${stored.fireAtEpochS} should be later than the original $original",
            stored.fireAtEpochS > original,
        )

        simulateReboot()
        assertEquals(1, scheduled().size)
    }

    @Test
    fun `an alarm that has gone off is no longer stored`() {
        DeviceCommands.alarm(app, armCommand("a1", nowS() + 5))
        AlarmReceiver().onReceive(
            app,
            Intent(AlarmReceiver.ACTION_FIRE)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, "a1")
                .putExtra(AlarmReceiver.EXTRA_LABEL, "wake up"),
        )
        assertTrue("a fired alarm must not be re-armed later", Alarms.held(app).isEmpty())
    }

    @Test
    fun `a corrupt store does not crash the boot path`() {
        Memory.json = "{not json"
        assertTrue(Alarms.held(app).isEmpty())
        simulateReboot() // must not throw
    }

    @Test
    fun `rebooting with no alarms set does nothing`() {
        simulateReboot()
        assertTrue(scheduled().isEmpty())
    }

    @Test
    fun `the alarm store never falls back to plaintext`() {
        // Labels are personal ("take the pills"), so the key belongs with the other secrets.
        assertNotNull(Config.SECRET_KEYS.firstOrNull { it == "alarms" })
    }

    @Test
    fun `recurrence is preserved so it can be honoured later`() {
        DeviceCommands.alarm(
            app,
            AlarmCommand.newBuilder()
                .setAction("arm").setAlarmId("a1").setFireAtEpochS(nowS() + 600)
                .setLabel("wake up").setRecurrence("daily")
                .setSound(true).setVibrate(true).build(),
        )
        assertEquals("daily", Alarms.held(app).single().recurrence)
    }

    @Test
    fun `installing a new APK re-arms the alarms`() {
        // Installing an APK cancels every PendingIntent the package holds, so an app update
        // disarms alarms exactly the way a reboot does. This is the dev loop, so it happens often.
        val at = nowS() + 3600
        DeviceCommands.alarm(app, armCommand("a1", at))
        val am = app.getSystemService(android.app.AlarmManager::class.java)
        shadowOf(am).scheduledAlarms.toList().forEach { am.cancel(it.operation) }
        assertTrue(scheduled().isEmpty())

        AlarmReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertEquals("the alarm should be back after the update", 1, scheduled().size)
        assertEquals(at, Alarms.held(app).single().fireAtEpochS)
    }

    @Test
    fun `an unrelated broadcast does not touch the alarms`() {
        DeviceCommands.alarm(app, armCommand("a1", nowS() + 600))
        BootReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        assertEquals(1, Alarms.held(app).size)
    }
}
