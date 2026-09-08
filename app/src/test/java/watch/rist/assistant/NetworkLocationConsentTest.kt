package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import watch.rist.assistant.NetworkLocationConsent.Choice
import watch.rist.assistant.NetworkLocationConsent.MAX_ASKS
import watch.rist.assistant.NetworkLocationConsent.Outcome
import watch.rist.assistant.NetworkLocationConsent.RE_ASK_GAP_MS
import watch.rist.assistant.NetworkLocationConsent.Status
import watch.rist.assistant.NetworkLocationConsent.outcomeOf
import watch.rist.assistant.NetworkLocationConsent.shouldAsk
import watch.rist.assistant.NetworkLocationConsent.status

class NetworkLocationConsentTest {

    private val off = NetworkLocationConsent.VALUE_OFF
    private val proxy = NetworkLocationConsent.VALUE_PROXY
    private val now = 1_700_000_000_000L

    @Test
    fun theValueWrittenIsTheProxyAndNeverDirectApple() {
        assertEquals("network_location must be the GrapheneOS proxy, not direct Apple", 2, proxy)
        assertEquals(0, off)
        assertEquals("geocoder must be OpenStreetMap", 1, NetworkLocationConsent.GEOCODER_OSM)
    }

    @Test
    fun aFreshDeviceIsAsked() {
        assertTrue(shouldAsk(Choice.UNANSWERED, asks = 0, lastAskedAt = 0L, now = now, systemValue = off))
    }

    @Test
    fun anAnsweredDeviceIsNeverAskedAgain() {
        for (c in listOf(Choice.ON, Choice.OFF)) {
            assertFalse(
                "answered $c must not be asked again",
                shouldAsk(c, asks = 0, lastAskedAt = 0L, now = now, systemValue = off)
            )
        }
    }

    @Test
    fun anAlreadyEnabledDeviceIsNotAsked() {
        for (v in listOf(1, 2, 3)) {
            assertFalse(
                "systemValue=$v is on; nothing to ask",
                shouldAsk(Choice.UNANSWERED, asks = 0, lastAskedAt = 0L, now = now, systemValue = v)
            )
        }
    }

    @Test
    fun dismissingDoesNotReAskOnTheNextLaunch() {
        assertFalse(
            shouldAsk(Choice.UNANSWERED, asks = 1, lastAskedAt = now, now = now + 5_000, systemValue = off)
        )
        assertFalse(
            shouldAsk(
                Choice.UNANSWERED, asks = 1, lastAskedAt = now,
                now = now + RE_ASK_GAP_MS - 1, systemValue = off
            )
        )
    }

    @Test
    fun theQuestionComesBackOnceTheGapHasPassed() {
        assertTrue(
            shouldAsk(
                Choice.UNANSWERED, asks = 1, lastAskedAt = now,
                now = now + RE_ASK_GAP_MS, systemValue = off
            )
        )
    }

    @Test
    fun afterThreeDismissalsItStopsForever() {
        assertTrue(
            shouldAsk(
                Choice.UNANSWERED, asks = MAX_ASKS - 1, lastAskedAt = now,
                now = now + RE_ASK_GAP_MS * 10, systemValue = off
            )
        )
        assertFalse(
            shouldAsk(
                Choice.UNANSWERED, asks = MAX_ASKS, lastAskedAt = now,
                now = now + RE_ASK_GAP_MS * 10, systemValue = off
            )
        )
        assertFalse(
            shouldAsk(
                Choice.UNANSWERED, asks = MAX_ASKS + 7, lastAskedAt = now,
                now = now + RE_ASK_GAP_MS * 100, systemValue = off
            )
        )
    }

    @Test
    fun aClockThatWentBackwardsDoesNotSilenceTheQuestionForYears() {
        assertTrue(
            shouldAsk(
                Choice.UNANSWERED, asks = 1, lastAskedAt = now,
                now = now - RE_ASK_GAP_MS * 3, systemValue = off
            )
        )
    }

    @Test
    fun theGapIsAWeekAndTheCeilingIsThree() {
        assertEquals(7L * 24 * 60 * 60 * 1000, RE_ASK_GAP_MS)
        assertEquals(3, MAX_ASKS)
    }

    @Test
    fun outcomeComesFromTheReadBackNotTheRequest() {
        assertEquals(Outcome.APPLIED, outcomeOf(wanted = proxy, readBack = proxy))
        assertEquals(Outcome.REFUSED, outcomeOf(wanted = proxy, readBack = off))
        assertEquals(Outcome.REFUSED, outcomeOf(wanted = proxy, readBack = 1))
        assertEquals(Outcome.APPLIED, outcomeOf(wanted = off, readBack = off))
        assertEquals(Outcome.REFUSED, outcomeOf(wanted = off, readBack = proxy))
    }

    @Test
    fun sayingYesToAPlatformThatRefusesReadsAsRefusedNotAsOn() {
        assertEquals(Status.REFUSED, status(Choice.ON, off))
    }

    @Test
    fun theLivePlatformValueOutranksTheStoredAnswer() {
        assertEquals(Status.ON, status(Choice.OFF, proxy))
        assertEquals(Status.ON, status(Choice.UNANSWERED, proxy))
        assertEquals(Status.ON, status(Choice.ON, proxy))
    }

    @Test
    fun theOrdinaryStatesAreTheOrdinaryStates() {
        assertEquals(Status.UNANSWERED, status(Choice.UNANSWERED, off))
        assertEquals(Status.OFF, status(Choice.OFF, off))
    }

    @Test
    fun anUnrecognisedStoredAnswerMeansUnanswered() {
        assertEquals(Choice.UNANSWERED, Choice.parse(null))
        assertEquals(Choice.UNANSWERED, Choice.parse(""))
        assertEquals(Choice.UNANSWERED, Choice.parse("true"))
        assertEquals(Choice.UNANSWERED, Choice.parse("ON"))
    }

    @Test
    fun everyChoiceSurvivesTheRoundTrip() {
        for (c in Choice.values()) assertEquals(c, Choice.parse(c.stored))
    }
}
