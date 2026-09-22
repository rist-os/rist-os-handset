package watch.rist.assistant

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.AlarmCommand

/**
 * A "daily" alarm used to ring once and never again: the proto said the device handles the
 * re-arm and nothing read the field. The backend meanwhile keeps a recurring alarm in its
 * listings forever, so it was asserting the opposite of the truth.
 *
 * The vocabulary is the closed set the backend refuses to go outside of: daily, weekdays,
 * weekends, weekly:<dow>, or "" for a one-shot.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmRecurrenceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nz = ZoneId.of("Pacific/Auckland")

    private object Memory : Alarms.Store {
        var json = "[]"
        override fun read(ctx: android.content.Context) = json
        override fun write(ctx: android.content.Context, json: String) { this.json = json }
    }

    private lateinit var realStore: Alarms.Store

    @Before
    fun useMemory() {
        realStore = Alarms.store
        Memory.json = "[]"
        Alarms.store = Memory
    }

    @After
    fun restore() {
        Alarms.store = realStore
        Memory.json = "[]"
    }

    /** Epoch seconds for a local wall-clock time in [zone]. */
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, zone: ZoneId = nz): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toEpochSecond()

    private fun local(epochS: Long, zone: ZoneId = nz): LocalDateTime =
        java.time.Instant.ofEpochSecond(epochS).atZone(zone).toLocalDateTime()

    // ---- the enumeration ----

    @Test
    fun `daily moves to the same time tomorrow`() {
        val mon = at(2026, 3, 2, 7, 0) // Monday 07:00
        val next = Alarms.nextOccurrence(mon, "daily", mon, nz)!!
        assertEquals(LocalDateTime.of(2026, 3, 3, 7, 0), local(next))
    }

    @Test
    fun `weekdays skips the weekend`() {
        val fri = at(2026, 3, 6, 7, 0) // Friday
        val next = Alarms.nextOccurrence(fri, "weekdays", fri, nz)!!
        assertEquals("should land on Monday", LocalDateTime.of(2026, 3, 9, 7, 0), local(next))
    }

    @Test
    fun `weekends skips the working week`() {
        val sun = at(2026, 3, 8, 9, 0) // Sunday
        val next = Alarms.nextOccurrence(sun, "weekends", sun, nz)!!
        assertEquals(LocalDateTime.of(2026, 3, 14, 9, 0), local(next)) // the next Saturday
    }

    @Test
    fun `weekly on a named day lands a week later`() {
        val sun = at(2026, 3, 8, 8, 0)
        val next = Alarms.nextOccurrence(sun, "weekly:sun", sun, nz)!!
        assertEquals(LocalDateTime.of(2026, 3, 15, 8, 0), local(next))
    }

    @Test
    fun `every day of the week is accepted`() {
        val from = at(2026, 3, 2, 7, 0)
        for (d in listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")) {
            assertTrue(
                "weekly:$d should be honoured",
                Alarms.nextOccurrence(from, "weekly:$d", from, nz) != null,
            )
        }
    }

    // ---- refusing rather than guessing ----

    @Test
    fun `a one-shot has no next occurrence`() {
        val t = at(2026, 3, 2, 7, 0)
        assertNull(Alarms.nextOccurrence(t, "", t, nz))
    }

    @Test
    fun `an unrecognised rule is treated as a one-shot, not guessed at`() {
        val t = at(2026, 3, 2, 7, 0)
        assertNull(Alarms.nextOccurrence(t, "every second tuesday", t, nz))
        assertNull(Alarms.nextOccurrence(t, "weekly:funday", t, nz))
        assertNull(Alarms.nextOccurrence(t, "monthly", t, nz))
    }

    // ---- the reason this uses a calendar and not 86400 seconds ----

    @Test
    fun `a daily alarm keeps its wall-clock time across a daylight saving change`() {
        // Auckland leaves DST at 03:00 on Sunday 5 April 2026. A 07:00 alarm must stay 07:00.
        val sat = at(2026, 4, 4, 7, 0)
        val next = Alarms.nextOccurrence(sat, "daily", sat, nz)!!
        assertEquals(LocalDateTime.of(2026, 4, 5, 7, 0), local(next))
        assertTrue(
            "the gap should not be exactly 24h across the change",
            next - sat != 24 * 3600L,
        )
    }

    // ---- behaviour through the real paths ----

    @Test
    fun `a repeating alarm is re-armed after it goes off`() {
        val fireAt = System.currentTimeMillis() / 1000 + 60
        DeviceCommands.alarm(
            app,
            AlarmCommand.newBuilder().setAction("arm").setAlarmId("a1")
                .setFireAtEpochS(fireAt).setLabel("wake up").setRecurrence("daily")
                .setSound(true).setVibrate(true).build(),
        )
        AlarmReceiver().onReceive(
            app,
            Intent(AlarmReceiver.ACTION_FIRE)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, "a1")
                .putExtra(AlarmReceiver.EXTRA_LABEL, "wake up"),
        )
        val stored = Alarms.held(app).single()
        assertTrue("it should have moved forward", stored.fireAtEpochS > fireAt)
        assertEquals("daily", stored.recurrence)
    }

    @Test
    fun `a one-shot is still forgotten after it goes off`() {
        DeviceCommands.alarm(
            app,
            AlarmCommand.newBuilder().setAction("arm").setAlarmId("a1")
                .setFireAtEpochS(System.currentTimeMillis() / 1000 + 60).setLabel("once")
                .setSound(true).setVibrate(true).build(),
        )
        AlarmReceiver().onReceive(
            app,
            Intent(AlarmReceiver.ACTION_FIRE).putExtra(AlarmReceiver.EXTRA_ALARM_ID, "a1"),
        )
        assertTrue(Alarms.held(app).isEmpty())
    }

    @Test
    fun `a repeating alarm missed while the phone was off rolls forward, not away`() {
        val nowS = System.currentTimeMillis() / 1000
        Memory.json = """[{"id":"r","at":${nowS - 86_400},"label":"x",""" +
            """"sound":true,"vibrate":true,"recurrence":"daily"}]"""
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        val stored = Alarms.held(app).single()
        assertTrue("it must be in the future now", stored.fireAtEpochS > nowS)
        assertEquals(
            "and armed",
            1,
            shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms
                .count { shadowOf(it.operation).savedIntent.component?.className == AlarmReceiver::class.java.name },
        )
    }

    @Test
    fun `a one-shot missed while the phone was off is still dropped`() {
        val nowS = System.currentTimeMillis() / 1000
        Memory.json =
            """[{"id":"o","at":${nowS - 86_400},"label":"x","sound":true,"vibrate":true}]"""
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(Alarms.held(app).isEmpty())
    }

    // ---- the two ways a schedule used to get silently corrupted ----

    @Test
    fun `a daily alarm keeps its time of day across the spring-forward gap`() {
        // Los Angeles springs forward at 02:00 on Sunday 8 March 2026: 02:30 does not exist
        // that day. The gap day alone may shift; the day after must be 02:30 again.
        val la = ZoneId.of("America/Los_Angeles")
        val sat = at(2026, 3, 7, 2, 30, la)
        val tod = 2 * 3600 + 30 * 60

        val gapDay = Alarms.nextOccurrence(sat, tod, "daily", sat, la)!!
        assertEquals("the gap day resolves forward, as a clock does",
            LocalDateTime.of(2026, 3, 8, 3, 30), local(gapDay, la))

        // What onFired does next: anchor on the (shifted) gap-day instant, time of day kept.
        val dayAfter = Alarms.nextOccurrence(gapDay, tod, "daily", gapDay, la)!!
        assertEquals("the shift must not carry into the following day",
            LocalDateTime.of(2026, 3, 9, 2, 30), local(dayAfter, la))
    }

    @Test
    fun `a snooze keeps a daily alarm daily and returns it to the schedule`() {
        val nowS = System.currentTimeMillis() / 1000
        val scheduled = nowS + 30
        DeviceCommands.alarm(
            app,
            AlarmCommand.newBuilder().setAction("arm").setAlarmId("a1")
                .setFireAtEpochS(scheduled).setLabel("wake up").setRecurrence("daily")
                .setSound(true).setVibrate(true).build(),
        )
        // It rings on schedule: the store rolls to tomorrow.
        AlarmReceiver().onReceive(
            app, Intent(AlarmReceiver.ACTION_FIRE).putExtra(AlarmReceiver.EXTRA_ALARM_ID, "a1"),
        )
        val tomorrow = Alarms.held(app).single().scheduledEpochS
        assertTrue(tomorrow > scheduled)

        // The backend snoozes it, echoing only the id, as the proto documents.
        DeviceCommands.alarm(
            app, AlarmCommand.newBuilder().setAction("snooze").setAlarmId("a1").build(),
        )
        val snoozed = Alarms.held(app).single()
        assertEquals("a snooze must not turn a daily alarm into a one-shot", "daily", snoozed.recurrence)
        assertEquals("a snooze moves the next ring, not the schedule", tomorrow, snoozed.scheduledEpochS)
        assertTrue("the next ring is the snooze", snoozed.fireAtEpochS < tomorrow)

        // The snooze rings: it returns to the schedule instead of drifting nine minutes.
        AlarmReceiver().onReceive(
            app, Intent(AlarmReceiver.ACTION_FIRE).putExtra(AlarmReceiver.EXTRA_ALARM_ID, "a1"),
        )
        val back = Alarms.held(app).single()
        assertEquals("daily", back.recurrence)
        assertEquals(tomorrow, back.fireAtEpochS)
        assertEquals(tomorrow, back.scheduledEpochS)
    }

    @Test
    fun `repeats reads the field the way the backend writes it`() {
        assertTrue(Alarms.repeats("daily"))
        assertFalse(Alarms.repeats(""))
        assertFalse(Alarms.repeats("   "))
    }
}
