package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushBackoffTest {

    @Test
    fun beforeAnySuccess_theCeilingIsTheLongOne() {
        var b = PushService.BACKOFF_MIN_MS
        repeat(40) { b = nextPushBackoffMs(b, everConnected = false) }
        assertEquals(PushService.NEVER_CONNECTED_MAX_MS, b)
    }

    @Test
    fun afterASuccess_theCeilingIsSixtySeconds() {
        var b = PushService.BACKOFF_MIN_MS
        repeat(40) { b = nextPushBackoffMs(b, everConnected = true) }
        assertEquals(PushService.BACKOFF_MAX_MS, b)
    }

    @Test
    fun aDeadPeerCostsAnOrderOfMagnitudeFewerWakesThanALiveOne() {
        val dead = 86_400_000.0 / PushService.NEVER_CONNECTED_MAX_MS
        val live = 86_400_000.0 / PushService.BACKOFF_MAX_MS
        assertEquals(48.0, dead, 0.001)
        assertEquals(1440.0, live, 0.001)
        assertTrue("a never-connected channel must be at least 10x cheaper", dead * 10 <= live)
    }

    @Test
    fun itNeverReturnsZeroAndNeverShrinks() {
        for (connected in listOf(true, false)) {
            assertEquals(PushService.BACKOFF_MIN_MS, nextPushBackoffMs(0L, connected))
            assertEquals(PushService.BACKOFF_MIN_MS, nextPushBackoffMs(-5L, connected))
            var b = PushService.BACKOFF_MIN_MS
            repeat(20) {
                val next = nextPushBackoffMs(b, connected)
                assertTrue("backoff went backwards: $b -> $next", next >= b)
                b = next
            }
        }
    }

    @Test
    fun theFirstStepDoubles() {
        assertEquals(2 * PushService.BACKOFF_MIN_MS, nextPushBackoffMs(PushService.BACKOFF_MIN_MS, false))
        assertEquals(2 * PushService.BACKOFF_MIN_MS, nextPushBackoffMs(PushService.BACKOFF_MIN_MS, true))
    }
}
