package watch.rist.assistant

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupCompleteGuardTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<Context>()

    private fun cacheField() = Config::class.java.getDeclaredField("cached").apply { isAccessible = true }

    private fun installUnfilteredPrefs(): SharedPreferences {
        val p = ctx().getSharedPreferences("rist_test_unfiltered", Context.MODE_PRIVATE)
        p.edit().clear().commit()
        cacheField().set(null, p)
        return p
    }

    @Before
    fun setUp() {
        installUnfilteredPrefs()
    }

    @After
    fun tearDown() {
        runCatching { ctx().getSharedPreferences("rist_test_unfiltered", Context.MODE_PRIVATE).edit().clear().commit() }
        cacheField().set(null, null)
    }

    @Test
    fun `the unfiltered store really does hold a token, or this file proves nothing`() {
        Config.setAuthToken(ctx(), "ristd_guard.secret")
        assertEquals("ristd_guard.secret", Config.authToken(ctx()))
        assertFalse("a device holding a token must not report needing one", Enrolment.needed(ctx()))
    }

    @Test
    fun `resuming the home screen records that setup has been reached`() {
        Config.setAuthToken(ctx(), "ristd_guard.secret")
        Config.setSetupComplete(ctx(), false)
        assertFalse("precondition", Config.isSetupComplete(ctx()))

        Robolectric.buildActivity(MainActivity::class.java).create().resume()

        assertTrue(
            "nothing called recordSetupReached(), so isSetupComplete stays false forever and a " +
                "device that loses its credential is told it was never set up",
            Config.isSetupComplete(ctx())
        )
    }

    @Test
    fun `a device with no credential is not recorded as set up`() {
        Config.setAuthToken(ctx(), "")
        Config.setSetupComplete(ctx(), false)

        Robolectric.buildActivity(MainActivity::class.java).create().resume()

        assertFalse(
            "an unpaired device must not be marked as having completed setup, or it would be told " +
                "its connection was LOST when it never had one",
            Config.isSetupComplete(ctx())
        )
    }
}
