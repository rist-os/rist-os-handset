package watch.rist.assistant

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.AlarmCommand

/**
 * The bug: alarms are armed at an absolute instant, and nothing moved them when the clock changed
 * zone. A "7 AM every day" alarm set in New York rang at 4 AM after a flight to Los Angeles.
 *
 * The backend's rule (tools/alarms.py): a repeating alarm follows the phone to the same local
 * time; a one-shot keeps its instant.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmTimeZoneTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val ny = ZoneId.of("America/New_York")
    private val la = ZoneId.of("America/Los_Angeles")

    private object Memory : Alarms.Store {
        var json = "[]"
        override fun read(ctx: android.content.Context) = json
        override fun write(ctx: android.content.Context, json: String) { this.json = json }
    }

    private lateinit var realStore: Alarms.Store
    private lateinit var realZone: TimeZone

    @Before
    fun setUp() {
        realStore = Alarms.store
        realZone = TimeZone.getDefault()
        Memory.json = "[]"
        Alarms.store = Memory
        TimeZone.setDefault(TimeZone.getTimeZone(ny))
    }

    @After
    fun tearDown() {
        Alarms.store = realStore
        Memory.json = "[]"
        TimeZone.setDefault(realZone)
    }

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toEpochSecond()

    private fun local(epochS: Long, zone: ZoneId): LocalDateTime =
        java.time.Instant.ofEpochSecond(epochS).atZone(zone).toLocalDateTime()

    private fun arm(id: String, atEpochS: Long, recurrence: String = "") = DeviceCommands.alarm(
        app, AlarmCommand.newBuilder().setAction("arm").setAlarmId(id).setKind("alarm")
            .setFireAtEpochS(atEpochS).setLabel("wake").setSound(true).setVibrate(true)
            .setRecurrence(recurrence).build()
    )

    private fun armedAt(id: String): Long =
        shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms
            .filter { shadowOf(it.operation).savedIntent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID) == id }
            .maxOf { it.triggerAtTime } / 1000L

    private fun zoneChangesTo(zone: ZoneId) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        AlarmReceiver().onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED))
    }

    private fun far(): Long = System.currentTimeMillis() / 1000 + 30L * 86_400L

    @Test
    fun `a daily 7 AM alarm rings at 7 AM local after flying from New York to Los Angeles`() {
        // Thirty days out, so the test does not depend on today's date or time.
        val day = local(far(), ny).toLocalDate()
        val sevenNy = at(ny, day.year, day.monthValue, day.dayOfMonth, 7, 0)
        arm("daily", sevenNy, "daily")
        assertEquals(sevenNy, armedAt("daily"))

        zoneChangesTo(la)

        val held = Alarms.held(app).single { it.id == "daily" }
        assertEquals("rings at 07:00 on the clock in Los Angeles", 7 * 3600, Alarms.timeOfDay(held.fireAtEpochS, la))
        assertEquals(held.fireAtEpochS, held.scheduledEpochS)
        assertEquals("AlarmManager holds the moved time", held.fireAtEpochS, armedAt("daily"))
        assertEquals(7, local(armedAt("daily"), la).hour)
    }

    @Test
    fun `a weekday alarm keeps its days in the new zone`() {
        val day = local(far(), ny).toLocalDate().let { d ->
            generateSequence(d) { it.plusDays(1) }.first { it.dayOfWeek == java.time.DayOfWeek.MONDAY }
        }
        arm("wk", at(ny, day.year, day.monthValue, day.dayOfMonth, 6, 30), "weekdays")
        zoneChangesTo(ZoneId.of("Europe/London"))
        val moved = local(Alarms.held(app).single().fireAtEpochS, ZoneId.of("Europe/London"))
        assertEquals(6, moved.hour)
        assertEquals(30, moved.minute)
        assertEquals(true, moved.dayOfWeek.value in 1..5)
    }

    @Test
    fun `a one-shot alarm keeps its instant, as the backend does`() {
        val instant = far()
        arm("once", instant)
        zoneChangesTo(la)
        assertEquals(instant, Alarms.held(app).single().fireAtEpochS)
        assertEquals(instant, armedAt("once"))
    }

    @Test
    fun `a zone change that changes nothing moves nothing`() {
        val day = local(far(), ny).toLocalDate()
        val sevenNy = at(ny, day.year, day.monthValue, day.dayOfMonth, 7, 0)
        arm("daily", sevenNy, "daily")
        zoneChangesTo(ny)
        assertEquals(sevenNy, Alarms.held(app).single().fireAtEpochS)
    }

    @Test
    fun `a snoozed repeating alarm keeps its snooze and returns to the new zone's schedule`() {
        val now = System.currentTimeMillis() / 1000
        val snoozeAt = now + 9 * 60
        val a = Alarms.Armed("s", snoozeAt, "wake", true, true, "daily",
            scheduledEpochS = now - 60, todSec = 7 * 3600)
        val moved = Alarms.followZone(a, now, la)
        assertEquals(snoozeAt, moved.fireAtEpochS)
        assertEquals(7 * 3600, Alarms.timeOfDay(moved.scheduledEpochS, la))
        assertEquals(true, moved.scheduledEpochS > now)
    }
}
