package watch.rist.assistant

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EndpointNormalizeTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val turns = "https://api.rist.watch/v1/device"

    @Before
    fun setUp() {
        Config.setDeployDefaultsForTest("", "")
        Config.clearBackendOverride(ctx())
    }

    @After
    fun tearDown() {
        Config.clearBackendOverride(ctx())
        Config.clearDeployDefaultsForTest()
    }

    @Test
    fun `a bare host URL gets the turn path`() {
        assertEquals(turns, Config.normalizeEndpoint("https://api.rist.watch"))
    }

    @Test
    fun `a trailing slash gets the turn path`() {
        assertEquals(turns, Config.normalizeEndpoint("https://api.rist.watch/"))
        assertEquals(turns, Config.normalizeEndpoint("  https://api.rist.watch//  "))
    }

    @Test
    fun `the full form is unchanged and never doubled`() {
        assertEquals(turns, Config.normalizeEndpoint(turns))
        assertEquals(turns, Config.normalizeEndpoint("$turns/"))
        assertEquals(turns, Config.normalizeEndpoint(Config.normalizeEndpoint(turns)!!))
    }

    @Test
    fun `a host with no scheme is refused, not guessed`() {
        assertNull(Config.normalizeEndpoint("api.rist.watch"))
        assertNull(Config.normalizeEndpoint("ftp://api.rist.watch"))
        assertNull(Config.normalizeEndpoint(""))
        assertNull(Config.normalizeEndpoint("https://"))
    }

    @Test
    fun `a port and a cleartext dev host survive`() {
        assertEquals("http://10.0.2.2:8080/v1/device", Config.normalizeEndpoint("http://10.0.2.2:8080"))
    }

    @Test
    fun `a stored bare host sends turns to the device path and derives the rest from the host`() {
        Config.setBackendEndpoint(ctx(), "https://api.rist.watch")
        assertEquals(turns, Config.backendUrl(ctx()))
        assertEquals("https://api.rist.watch/v1/media/progress", Config.progressUrl(ctx()))
        assertEquals("https://api.rist.watch/v1/device/items", Checklists.itemsUrl(Config.backendUrl(ctx())))
        assertEquals("https://api.rist.watch/v1/device/wake",
            WakeLoop.wakeUrl(Config.backendUrl(ctx()), emptyList(), 1)?.substringBefore('?'))
    }

    @Test
    fun `re-saving the same host in another form is the same endpoint`() {
        Config.setBackendEndpoint(ctx(), "https://api.rist.watch")
        Config.setBackendEndpoint(ctx(), turns)
        assertEquals(turns, Config.backendUrl(ctx()))
    }

    @Test
    fun `a bare host saved by an older build is read back with the turn path`() {
        // Older builds stored the text as typed; backendUrl canonicalizes on read with this.
        assertEquals(turns, Config.canonical("https://api.rist.watch"))
        assertEquals(turns, Config.canonical("https://api.rist.watch/"))
        assertEquals("unparseable values are kept, not dropped", "api.rist.watch", Config.canonical("api.rist.watch"))
        assertNull(Config.canonical(""))
    }
}
