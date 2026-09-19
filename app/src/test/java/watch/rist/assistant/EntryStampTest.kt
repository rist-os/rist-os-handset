package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** An answer still on the feed after midnight says which day it was from. */
class EntryStampTest {

    private val tz = TimeZone.getTimeZone("America/Mexico_City")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = Calendar.getInstance(tz).apply {
        clear(); set(y, mo - 1, d, h, mi)
    }.timeInMillis

    private fun stamp(atMs: Long, nowMs: Long) = CommsFeed.entryStamp(atMs, nowMs, tz, Locale.US)

    @Test
    fun `the same day is just the time`() {
        assertEquals("2:17 PM", stamp(at(2026, 9, 17, 14, 17), at(2026, 9, 17, 23, 59)))
    }

    @Test
    fun `one minute past midnight it is yesterday, however recent`() {
        assertEquals("Yesterday 11:58 PM", stamp(at(2026, 9, 17, 23, 58), at(2026, 9, 18, 0, 1)))
    }

    @Test
    fun `older than yesterday carries its date`() {
        assertEquals("Sep 12, 2:17 PM", stamp(at(2026, 9, 12, 14, 17), at(2026, 9, 18, 9, 0)))
    }

    @Test
    fun `another year carries the year`() {
        assertEquals("Dec 31, 2025, 9:05 AM", stamp(at(2025, 12, 31, 9, 5), at(2026, 1, 2, 9, 0)))
    }

    @Test
    fun `an entry ahead of a clock set backwards is still just a time`() {
        assertEquals("2:17 PM", stamp(at(2026, 9, 19, 14, 17), at(2026, 9, 18, 9, 0)))
    }

    @Test
    fun `the day is the phone's zone, not UTC`() {
        // 23:30 on the 17th in Mexico City is already the 18th in UTC.
        assertEquals("11:30 PM", stamp(at(2026, 9, 17, 23, 30), at(2026, 9, 17, 23, 45)))
    }

    @Test
    fun `the day key moves at midnight and not before`() {
        assertEquals(CommsFeed.dayKey(at(2026, 9, 17, 0, 0), tz), CommsFeed.dayKey(at(2026, 9, 17, 23, 59), tz))
        assertNotEquals(CommsFeed.dayKey(at(2026, 9, 17, 23, 59), tz), CommsFeed.dayKey(at(2026, 9, 18, 0, 0), tz))
    }
}
