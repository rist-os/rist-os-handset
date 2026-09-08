package watch.rist.assistant

import android.app.admin.DevicePolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuressReachabilityTest {

    private val keyguard = DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
    private val global = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS

    @Test
    fun aHandsetWithNoScreenLockCannotBeDuressWiped() {
        assertEquals(21, KioskManager.lockTaskFeaturesFor(hasCredential = false))
        assertEquals("no keyguard bit at 21", 0, 21 and keyguard)
        assertFalse(KioskManager.duressCredentialIsReachable(hasCredential = false))
    }

    @Test
    fun settingAPinArmsTheLockScreenAndWithItDuress() {
        assertEquals(53, KioskManager.lockTaskFeaturesFor(hasCredential = true))
        assertTrue(KioskManager.duressCredentialIsReachable(hasCredential = true))
    }

    @Test
    fun reachabilityIsTheKeyguardBitAndNothingElse() {
        for (hasCredential in listOf(false, true)) {
            val f = KioskManager.lockTaskFeaturesFor(hasCredential)
            assertEquals(
                "duress reachability must track KEYGUARD(32) exactly (hasCredential=$hasCredential)",
                f and keyguard != 0,
                KioskManager.duressCredentialIsReachable(hasCredential)
            )
        }
    }

    @Test
    fun theUserSettingAPinIsTheOnlyThingThatArmsIt() {
        assertFalse(KioskManager.duressCredentialIsReachable(false))
        assertTrue(KioskManager.duressCredentialIsReachable(true))
    }

    @Test
    fun nothingAboutDuressCostsTheEmergencyButton() {
        for (hasCredential in listOf(false, true)) {
            assertTrue(
                "GLOBAL_ACTIONS missing when hasCredential=$hasCredential",
                KioskManager.lockTaskFeaturesFor(hasCredential) and global != 0
            )
        }
    }

    @Test
    fun noLockTaskBitWasAddedForDuress() {
        assertEquals(21, KioskManager.lockTaskFeaturesFor(false))
        assertEquals(53, KioskManager.lockTaskFeaturesFor(true))
    }
}
