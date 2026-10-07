package watch.rist.assistant

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import rist.v1.CommsCommand
import rist.v1.DeviceResponse
import watch.rist.assistant.DialPolicy.Verdict

/** The phone's own toll-fraud guard on backend-placed calls and texts. */
@RunWith(RobolectricTestRunner::class)
class DialPolicyTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        app.getSharedPreferences("rist_comms_quota", 0).edit().clear().commit()
        shadowOf(app).clearNextStartedActivities()
    }

    @Test
    fun `ordinary US and Canadian numbers pass`() {
        assertEquals(Verdict.OK, DialPolicy.classify("+12065550100"))
        assertEquals(Verdict.OK, DialPolicy.classify("2065550100"))
        assertEquals(Verdict.OK, DialPolicy.classify("12065550100"))
        assertEquals(Verdict.OK, DialPolicy.classify("+18005550100"))
        assertEquals(Verdict.OK, DialPolicy.classify("+14165550100"))
        assertEquals(Verdict.OK, DialPolicy.classify("5550100"))
    }

    @Test
    fun `premium, special-rate and NANP-foreign numbers are caught`() {
        assertEquals(Verdict.PREMIUM, DialPolicy.classify("+19005550100"))
        assertEquals(Verdict.PREMIUM, DialPolicy.classify("+12069760100"))
        assertEquals(Verdict.PREMIUM, DialPolicy.classify("9760100"))
        assertEquals(Verdict.SPECIAL, DialPolicy.classify("+15005550100"))
        assertEquals(Verdict.SPECIAL, DialPolicy.classify("+18815550100"))
        assertEquals(Verdict.INTERNATIONAL, DialPolicy.classify("+18765550100"))
        assertEquals(Verdict.INTERNATIONAL, DialPolicy.classify("+447700900123"))
        assertEquals(Verdict.INTERNATIONAL, DialPolicy.classify("011447700900123"))
        assertEquals(Verdict.SHORT_CODE, DialPolicy.classify("12345"))
        assertEquals(Verdict.UNRECOGNISED, DialPolicy.classify("+11235550100"))
    }

    @Test
    fun `emergency and free service numbers are never refused as short codes`() {
        assertEquals(Verdict.EMERGENCY, DialPolicy.classify("911"))
        assertEquals(Verdict.EMERGENCY, DialPolicy.classify("+1911"))
        assertEquals(Verdict.EMERGENCY, DialPolicy.classify("988"))
        assertEquals(Verdict.SERVICE, DialPolicy.classify("311"))
    }

    @Test
    fun `a contact unlocks international but never premium`() {
        assertTrue(DialPolicy.autoAllowed(Verdict.INTERNATIONAL, inContacts = true))
        assertFalse(DialPolicy.autoAllowed(Verdict.INTERNATIONAL, inContacts = false))
        assertFalse(DialPolicy.autoAllowed(Verdict.PREMIUM, inContacts = true))
        assertTrue(DialPolicy.autoAllowed(Verdict.OK, inContacts = false))
    }

    @Test
    fun `the window holds a gap, three a minute and the daily cap`() {
        val now = 10_000_000_000L
        assertEquals(DialPolicy.Quota.OK, DialPolicy.quota(emptyList(), now, 5))
        assertEquals(DialPolicy.Quota.TOO_SOON, DialPolicy.quota(listOf(now - 5_000), now, 5))
        assertEquals(DialPolicy.Quota.MINUTE,
            DialPolicy.quota(listOf(now - 50_000, now - 35_000, now - 20_000), now, 5))
        val day = (1..5).map { now - it * 3_600_000L }
        assertEquals(DialPolicy.Quota.DAY, DialPolicy.quota(day, now, 5))
        assertEquals(DialPolicy.Quota.OK, DialPolicy.quota(day.map { it - 86_400_000L }, now, 5))
    }

    @Test
    fun `the daily count survives a restart because it is stored`() {
        var t = 20_000_000_000L
        repeat(3) {
            assertEquals(DialPolicy.Quota.OK, DialPolicy.take(app, "call", 3, t))
            t += 120_000L
        }
        assertEquals(DialPolicy.Quota.DAY, DialPolicy.take(app, "call", 3, t))
        assertEquals(DialPolicy.Quota.OK, DialPolicy.take(app, "sms", 3, t))
    }

    private fun comms(action: String, number: String) = DeviceResponse.newBuilder()
        .setRequestId("r-$action-$number")
        .setComms(CommsCommand.newBuilder().setAction(action).setNumber(number).setBody("hi").setDisplayName("X"))
        .build()

    @Test
    fun `a backend call to a premium number only opens the dialer`() {
        DeviceCommands.handle(app, comms("call", "+19005550100"))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, started?.action)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `a backend text abroad opens the composer instead of sending`() {
        DeviceCommands.handle(app, comms("send_sms", "+447700900123"))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, started?.action)
        assertEquals("hi", started?.getStringExtra("sms_body"))
    }

    @Test
    fun `a backend call to an ordinary number is still placed`() {
        DeviceCommands.handle(app, comms("call", "+12065550123"))
        assertEquals(Intent.ACTION_CALL, shadowOf(app).nextStartedActivity?.action)
    }

    @Test
    fun `the emergency dial tool and crisis lines always reach the dialer`() {
        for (n in listOf("911", "988")) {
            DeviceCommands.handle(app, comms("dial", n))
            val dial = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_DIAL, dial?.action)
            assertEquals("tel:$n", dial?.dataString)
            DeviceCommands.handle(app, comms("call", n))
            assertEquals(Intent.ACTION_DIAL, shadowOf(app).nextStartedActivity?.action)
        }
    }
}
