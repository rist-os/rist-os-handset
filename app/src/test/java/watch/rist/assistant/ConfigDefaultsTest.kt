package watch.rist.assistant

import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigDefaultsTest {

    @Test
    fun parseDeploy_readsBothKeys() {
        val p = Properties().apply {
            setProperty("backendUrl", " https://example.test:8000/v1/device ")
            setProperty("pushUrl", "wss://example.test/v0/stream")
        }
        assertEquals(
            "https://example.test:8000/v1/device" to "wss://example.test/v0/stream",
            Config.parseDeploy(p)
        )
    }

    @Test
    fun parseDeploy_missingKeysFallBackToEmpty() {
        assertEquals("" to "", Config.parseDeploy(Properties()))
    }
}
