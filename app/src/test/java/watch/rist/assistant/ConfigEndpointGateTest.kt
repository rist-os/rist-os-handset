package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigEndpointGateTest {

    private val DEBUGGABLE = 1 shl 1

    private val DEFAULT = "https://api.example.test/v1/device"
    private val ATTACKER = "https://evil.example/v1/device"

    @Test
    fun isDebuggable_trueOnlyWhenTheFlagIsSet() {
        assertTrue(Config.isDebuggable(DEBUGGABLE))
        assertFalse(Config.isDebuggable(0))
        assertFalse(Config.isDebuggable((1 shl 0) or (1 shl 2) or (1 shl 3)))
        assertTrue(Config.isDebuggable(DEBUGGABLE or (1 shl 0) or (1 shl 5)))
    }

    @Test
    fun debugBuild_honoursAnOverride() {
        assertEquals(
            Config.BackendChoice(ATTACKER, Config.Override.KEEP),
            Config.resolveBackend(stored = ATTACKER, default = DEFAULT, debuggable = true)
        )
    }

    @Test
    fun releaseBuild_neverHonoursAnOverride() {
        val choice = Config.resolveBackend(stored = ATTACKER, default = DEFAULT, debuggable = false)
        assertEquals(DEFAULT, choice.url)
    }

    @Test
    fun releaseBuild_clearsAnOverrideLeftBehindByADebugBuild() {
        assertEquals(
            Config.BackendChoice(DEFAULT, Config.Override.DROP_CLEARING_TOKEN),
            Config.resolveBackend(stored = ATTACKER, default = DEFAULT, debuggable = false)
        )
    }

    @Test
    fun releaseBuild_dropsButDoesNotDeauthWhenTheOverrideEqualledTheDefault() {
        assertEquals(
            Config.BackendChoice(DEFAULT, Config.Override.DROP_KEEPING_TOKEN),
            Config.resolveBackend(stored = DEFAULT, default = DEFAULT, debuggable = false)
        )
    }

    @Test
    fun releaseBuild_withNoCompiledInDefault_HONOURS_theOverride() {
        assertEquals(
            Config.BackendChoice(ATTACKER, Config.Override.KEEP),
            Config.resolveBackend(stored = ATTACKER, default = "", debuggable = false)
        )
    }

    @Test
    fun releaseBuild_withACompiledInDefault_stillClearsTheOverride() {
        assertEquals(
            Config.BackendChoice(DEFAULT, Config.Override.DROP_CLEARING_TOKEN),
            Config.resolveBackend(stored = ATTACKER, default = DEFAULT, debuggable = false)
        )
    }

    @Test
    fun releaseBuild_withNoOverride_isANoOp() {
        assertEquals(
            Config.BackendChoice(DEFAULT, Config.Override.KEEP),
            Config.resolveBackend(stored = null, default = DEFAULT, debuggable = false)
        )
        assertEquals(
            Config.BackendChoice(DEFAULT, Config.Override.KEEP),
            Config.resolveBackend(stored = "", default = DEFAULT, debuggable = false)
        )
        assertEquals(
            Config.BackendChoice(DEFAULT, Config.Override.KEEP),
            Config.resolveBackend(stored = "   ", default = DEFAULT, debuggable = false)
        )
    }

    @Test
    fun debugBuild_stillDropsACleartextOverrideForATlsDefault() {
        assertEquals(
            Config.BackendChoice(DEFAULT, Config.Override.DROP_KEEPING_TOKEN),
            Config.resolveBackend(stored = "http://192.0.2.5:8000/v1/device", default = DEFAULT, debuggable = true)
        )
    }

    @Test
    fun debugBuild_keepsACleartextOverrideWhenTheDefaultIsAlsoCleartext() {
        val laptop = "http://192.0.2.5:8000/v1/device"
        assertEquals(
            Config.BackendChoice(laptop, Config.Override.KEEP),
            Config.resolveBackend(stored = laptop, default = "http://dev.example:8000/v1/device", debuggable = true)
        )
    }
}
