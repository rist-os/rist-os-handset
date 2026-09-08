package watch.rist.assistant

import android.app.admin.DevicePolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockTaskFeaturesTest {

    private val home = DevicePolicyManager.LOCK_TASK_FEATURE_HOME   // 4
    private val global = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS   // 16
    private val sysinfo = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO   // 1
    private val keyguard = DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD   // 32
    private val notifications = DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS   // 2
    private val overview = DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW   // 8

    @Test
    fun noCredentialMeans21_theStateAShippedHandsetBootsIn() {
        assertEquals(21, KioskManager.lockTaskFeaturesFor(hasCredential = false))
    }

    @Test
    fun aCredentialAdds32AndNothingElse() {
        assertEquals(53, KioskManager.lockTaskFeaturesFor(hasCredential = true))
        assertEquals(
            "setting a PIN must add KEYGUARD and change nothing else",
            KioskManager.lockTaskFeaturesFor(false) or keyguard,
            KioskManager.lockTaskFeaturesFor(true)
        )
    }

    @Test
    fun theKeyguardBitIsTheOnlyThingThatVaries() {
        val off = KioskManager.lockTaskFeaturesFor(false)
        val on = KioskManager.lockTaskFeaturesFor(true)
        assertEquals("exactly one bit may differ", keyguard, off xor on)
    }

    @Test
    fun emergencyAccessDoesNotDependOnWhetherAPinWasSet() {
        for (hasCredential in listOf(false, true)) {
            assertTrue(
                "GLOBAL_ACTIONS missing when hasCredential=$hasCredential",
                KioskManager.lockTaskFeaturesFor(hasCredential) and global != 0
            )
        }
    }

    @Test
    fun homeAndSystemInfoAreAlwaysOn() {
        for (hasCredential in listOf(false, true)) {
            val f = KioskManager.lockTaskFeaturesFor(hasCredential)
            assertTrue("HOME missing", f and home != 0)
            assertTrue("SYSTEM_INFO missing", f and sysinfo != 0)
        }
    }

    @Test
    fun notificationsAndOverviewStayOff() {
        for (hasCredential in listOf(false, true)) {
            val f = KioskManager.lockTaskFeaturesFor(hasCredential)
            assertEquals("NOTIFICATIONS must stay off", 0, f and notifications)
            assertEquals("OVERVIEW must stay off", 0, f and overview)
        }
    }
}
