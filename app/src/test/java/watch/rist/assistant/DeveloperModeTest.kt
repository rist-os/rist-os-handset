package watch.rist.assistant

import android.app.Application
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Developer mode (schema v28): USB debugging on a public image, only for an allowed account. */
@RunWith(RobolectricTestRunner::class)
class DeveloperModeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dbg = UserManager.DISALLOW_DEBUGGING_FEATURES

    private fun dpm() = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun secure(on: Boolean) =
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(on)

    // Robolectric's device policy shadow records restrictions on the user, not per admin.
    private fun restricted(): Boolean =
        app.getSystemService(UserManager::class.java).hasUserRestriction(dbg)

    private fun adb(): Int = Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, -1)

    @Before
    fun setUp() {
        app.getSharedPreferences("rist.developer.mode", Context.MODE_PRIVATE).edit().clear().commit()
        BuildVariant.override = true
        shadowOf(dpm()).setDeviceOwner(KioskManager.admin(app))
        dpm().addUserRestriction(KioskManager.admin(app), dbg)
        Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0)
        secure(true)
    }

    @After
    fun tearDown() {
        BuildVariant.override = null
        app.getSharedPreferences("rist.developer.mode", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ── the gates ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `it may be turned on only when every gate holds`() {
        for (public in listOf(true, false)) for (allowed in listOf(true, false))
            for (secure in listOf(true, false)) for (owner in listOf(true, false)) {
                val expected = public && allowed && secure && owner
                assertEquals("public=$public allowed=$allowed secure=$secure owner=$owner", expected,
                    DeveloperMode.mayTurnOn(public, allowed, secure, owner))
            }
    }

    @Test
    fun `it turns itself off when the account or the lock screen goes`() {
        assertFalse(DeveloperMode.mustTurnOff(on = true, allowed = true, secure = true))
        assertTrue(DeveloperMode.mustTurnOff(on = true, allowed = false, secure = true))
        assertTrue(DeveloperMode.mustTurnOff(on = true, allowed = true, secure = false))
        assertTrue(DeveloperMode.mustTurnOff(on = true, allowed = false, secure = false))
        assertFalse(DeveloperMode.mustTurnOff(on = false, allowed = false, secure = false))
    }

    @Test
    fun `the row is shown only on a public image to an allowed account`() {
        assertTrue(DeveloperMode.rowVisible(publicBuild = true, allowed = true))
        assertFalse(DeveloperMode.rowVisible(publicBuild = true, allowed = false))
        assertFalse(DeveloperMode.rowVisible(publicBuild = false, allowed = true))
        assertFalse(DeveloperMode.rowVisible(publicBuild = false, allowed = false))
    }

    @Test
    fun `the kiosk keeps debugging closed on a public image unless developer mode is on`() {
        assertTrue(dbg in KioskManager.kioskRestrictions(publicBuild = true, developerMode = false))
        assertFalse(dbg in KioskManager.kioskRestrictions(publicBuild = true, developerMode = true))
        assertFalse(dbg in KioskManager.kioskRestrictions(publicBuild = false, developerMode = false))
        for (on in listOf(true, false)) for (allowed in listOf(true, false)) for (secure in listOf(true, false))
            assertEquals(on && allowed && secure,
                DeveloperMode.debuggingLifted(publicBuild = true, on = on, allowed = allowed, secure = secure))
        assertFalse(DeveloperMode.debuggingLifted(publicBuild = false, on = true, allowed = true, secure = true))
    }

    @Test
    fun `present sets the backend's word and absent keeps it`() {
        assertTrue(DeveloperMode.next(current = false, present = true, value = true))
        assertFalse(DeveloperMode.next(current = true, present = true, value = false))
        assertTrue(DeveloperMode.next(current = true, present = false, value = false))
        assertFalse(DeveloperMode.next(current = false, present = false, value = true))
    }

    // ── on the phone ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a phone never told shows no row and cannot turn it on`() {
        assertFalse(DeveloperMode.isAllowed(app))
        assertFalse(DeveloperMode.rowVisible(app))
        assertFalse(DeveloperMode.turnOn(app))
        assertTrue(restricted())
        assertEquals(0, adb())
    }

    @Test
    fun `an allowed account with a lock screen turns it on and adb opens`() {
        DeveloperMode.onWake(app, present = true, value = true)
        assertTrue(DeveloperMode.rowVisible(app))
        assertTrue(DeveloperMode.turnOn(app))
        assertTrue(DeveloperMode.isOn(app))
        assertFalse(restricted())
        assertEquals(1, adb())
        assertEquals(1, Settings.Global.getInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0))
        assertTrue(DeveloperMode.debuggingLifted(app))
    }

    @Test
    fun `no lock screen means no developer mode`() {
        DeveloperMode.onWake(app, present = true, value = true)
        secure(false)
        assertFalse(DeveloperMode.turnOn(app))
        assertFalse(DeveloperMode.isOn(app))
        assertTrue(restricted())
    }

    @Test
    fun `a dev image has nothing to lift`() {
        BuildVariant.override = false
        DeveloperMode.onWake(app, present = true, value = true)
        assertFalse(DeveloperMode.rowVisible(app))
        assertFalse(DeveloperMode.turnOn(app))
    }

    @Test
    fun `without the device owner it cannot be turned on`() {
        shadowOf(dpm()).setDeviceOwner(null)
        DeveloperMode.onWake(app, present = true, value = true)
        assertFalse(DeveloperMode.turnOn(app))
        assertFalse(DeveloperMode.isOn(app))
    }

    @Test
    fun `the backend taking it away turns it off and closes adb`() {
        DeveloperMode.onResponse(app, present = true, value = true)
        assertTrue(DeveloperMode.turnOn(app))
        DeveloperMode.onResponse(app, present = false, value = false)
        assertTrue("an absent field keeps it", DeveloperMode.isOn(app))
        DeveloperMode.onResponse(app, present = true, value = false)
        assertFalse(DeveloperMode.isOn(app))
        assertFalse(DeveloperMode.rowVisible(app))
        assertTrue(restricted())
        assertEquals(0, adb())
    }

    @Test
    fun `removing the lock screen turns it off`() {
        DeveloperMode.onWake(app, present = true, value = true)
        assertTrue(DeveloperMode.turnOn(app))
        secure(false)
        DeveloperMode.enforce(app)
        assertFalse(DeveloperMode.isOn(app))
        assertTrue(restricted())
        assertEquals(0, adb())
    }

    @Test
    fun `turning it off in Settings restores the restriction`() {
        DeveloperMode.onWake(app, present = true, value = true)
        assertTrue(DeveloperMode.turnOn(app))
        DeveloperMode.turnOff(app, "test")
        assertFalse(DeveloperMode.isOn(app))
        assertTrue(restricted())
        assertEquals(0, adb())
        assertTrue("the row stays for an allowed account", DeveloperMode.rowVisible(app))
    }

    @Test
    fun `the section wording names no codes`() {
        for (on in listOf(true, false)) for (secure in listOf(true, false)) {
            val text = DeveloperModeSection.stateText(on = on, secure = secure)
            assertFalse(text, text.contains("DISALLOW") || text.contains("adb"))
        }
    }
}
