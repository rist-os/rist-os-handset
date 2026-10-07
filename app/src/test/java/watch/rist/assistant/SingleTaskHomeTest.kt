package watch.rist.assistant

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** The home screen is one instance: a second launch reaches the one already up. */
@RunWith(RobolectricTestRunner::class)
class SingleTaskHomeTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = Config.usePlainPrefsForTest(app)

    @After
    fun tidy() = Config.forgetPrefsForTest()

    @Test
    fun `the home screen is declared singleTask`() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
    }

    @Test
    fun `a HOME press while home is up is taken by the same activity`() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        c.newIntent(home)
        assertSame(home, c.get().intent)
        c.pause().resume()
    }
}
