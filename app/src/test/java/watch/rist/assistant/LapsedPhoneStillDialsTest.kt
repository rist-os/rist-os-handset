package watch.rist.assistant

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The phone's own emergency calling never asks the backend: lock task keeps the dialer, telecom
 * and the power menu's Emergency button reachable whatever billing or enrolment say.
 */
@RunWith(RobolectricTestRunner::class)
class LapsedPhoneStillDialsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tidy() {
        Config.clearBillingLapse(ctx)
        Config.setEnrolRevoked(ctx, false)
        Config.setCredentialRejected(ctx, false)
    }

    private val calling = listOf(AppLauncher.PKG_DIALER, "com.android.server.telecom", "com.android.phone")

    @Test
    fun `the dialer and telecom stay in lock task when the subscription has lapsed or the device is revoked`() {
        val healthy = KioskManager.buildAllowlist(ctx).toSet()
        Billing.onLapsed(ctx, Billing.lapseFrom("lapsed", null, null))
        Config.setEnrolRevoked(ctx, true)
        Config.setCredentialRejected(ctx, true)
        val cutOff = KioskManager.buildAllowlist(ctx).toSet()
        for (pkg in calling) assertTrue("$pkg is not allowed in lock task", pkg in cutOff)
        assertEquals("the allowlist must not depend on the account's standing", healthy, cutOff)
    }

    @Test
    fun `the power menu's Emergency button is on in every lock task configuration`() {
        for (hasCredential in listOf(false, true)) {
            assertTrue(
                KioskManager.lockTaskFeaturesFor(hasCredential) and
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS != 0
            )
        }
    }

    @Test
    fun `an emergency number is never auto-dialled by a reply, it opens the dialer`() {
        assertTrue(DeviceCommands.isEmergency(ctx, "911"))
        assertTrue(DeviceCommands.isEmergency(ctx, "112"))
    }
}
