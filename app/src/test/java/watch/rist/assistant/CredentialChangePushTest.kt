package watch.rist.assistant

import android.app.admin.DevicePolicyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialChangePushTest {

    private val global = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
    private val keyguard = DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD

    @Test
    fun settingAPinPushes53OverTheShipped21() {
        assertEquals(
            53,
            KioskManager.featuresToPushOnCredentialChange(currentFeatures = 21, hasCredential = true)
        )
    }

    @Test
    fun removingThePinPushes21BackOverAStale53() {
        assertEquals(
            21,
            KioskManager.featuresToPushOnCredentialChange(currentFeatures = 53, hasCredential = false)
        )
    }

    @Test
    fun changingOnePinForAnotherPushesNothing() {
        assertNull(KioskManager.featuresToPushOnCredentialChange(53, hasCredential = true))
    }

    @Test
    fun aBroadcastOnADeviceWithNoLockPushesNothing() {
        assertNull(KioskManager.featuresToPushOnCredentialChange(21, hasCredential = false))
    }

    @Test
    fun aPushIsOnlyEverOmittedWhenTheFrameworkAlreadyHoldsTheRightWord() {
        for (hasCredential in listOf(false, true)) {
            val desired = KioskManager.lockTaskFeaturesFor(hasCredential)
            for (current in listOf(0, 16, 20, 21, 37, 53, 55, 63)) {
                val push = KioskManager.featuresToPushOnCredentialChange(current, hasCredential)
                if (current == desired) {
                    assertNull("must not write when already correct (current=$current)", push)
                } else {
                    assertEquals(
                        "stale $current must be corrected to $desired",
                        desired, push
                    )
                }
            }
        }
    }

    @Test
    fun everyWordThisPathCanPushKeepsTheEmergencyButton() {
        for (hasCredential in listOf(false, true)) {
            for (current in listOf(0, 16, 21, 53, 63)) {
                val push = KioskManager.featuresToPushOnCredentialChange(current, hasCredential)
                    ?: continue
                assertTrue(
                    "GLOBAL_ACTIONS dropped pushing $push (current=$current, cred=$hasCredential)",
                    push and global != 0
                )
            }
        }
    }

    @Test
    fun thisPathCanOnlyEverMoveTheKeyguardBit() {
        val allowed = setOf(
            KioskManager.lockTaskFeaturesFor(false),
            KioskManager.lockTaskFeaturesFor(true)
        )
        assertEquals(setOf(21, 53), allowed)
        assertEquals(keyguard, 21 xor 53)
        for (hasCredential in listOf(false, true)) {
            for (current in 0..63) {
                val push = KioskManager.featuresToPushOnCredentialChange(current, hasCredential)
                    ?: continue
                assertTrue("pushed $push, which is neither 21 nor 53", push in allowed)
            }
        }
    }

    @Test
    fun theSameCredentialStateAlwaysAgreesWithTheStalenessCheckOnResume() {
        for (hasCredential in listOf(false, true)) {
            val desired = KioskManager.lockTaskFeaturesFor(hasCredential)
            val afterPush = KioskManager
                .featuresToPushOnCredentialChange(desired, hasCredential)
            assertNull("resume would have re-pushed what the receiver just wrote", afterPush)
        }
    }

    @Test
    fun duressReachabilityFollowsTheWordThisPathPushes() {
        val onSet = KioskManager.featuresToPushOnCredentialChange(21, hasCredential = true)
        assertNotNull(onSet)
        assertTrue("keyguard bit missing, so duress stays unreachable", onSet!! and keyguard != 0)
        assertTrue(KioskManager.duressCredentialIsReachable(hasCredential = true))

        val onRemove = KioskManager.featuresToPushOnCredentialChange(53, hasCredential = false)
        assertEquals(0, onRemove!! and keyguard)
        assertTrue(!KioskManager.duressCredentialIsReachable(hasCredential = false))
    }
}
