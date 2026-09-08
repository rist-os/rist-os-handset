package watch.rist.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class OtaNetworkTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<Context>()

    private fun cm() = ctx().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun activeWith(vararg capabilities: Int) {
        val caps = ShadowNetworkCapabilities.newInstance()
        for (c in capabilities) shadowOf(caps).addCapability(c)
        shadowOf(cm()).setNetworkCapabilities(cm().activeNetwork, caps)
    }

    @Test
    fun `home wifi is unmetered and may be spent`() {
        activeWith(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        )
        assertEquals(OtaNetwork.Suitability.Unmetered, OtaNetwork.current(ctx()))
    }

    @Test
    fun `anything the platform bills for is metered, tether and marked wifi included`() {
        activeWith(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        assertEquals(OtaNetwork.Suitability.Metered, OtaNetwork.current(ctx()))
    }

    @Test
    fun `no active network is None, not a free pass`() {
        shadowOf(cm()).setDefaultNetworkActive(false)
        assertEquals(
            "no network must not classify as unmetered — that is the isActiveNetworkMetered bug",
            OtaNetwork.Suitability.None, OtaNetwork.current(ctx())
        )
    }

    @Test
    fun `unmetered without INTERNET is not a network`() {
        activeWith(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        assertEquals(OtaNetwork.Suitability.None, OtaNetwork.current(ctx()))
    }

    @Test
    fun `classify covers every combination exactly once`() {
        assertEquals(OtaNetwork.Suitability.Unmetered, OtaNetwork.classify(true, true))
        assertEquals(OtaNetwork.Suitability.Metered, OtaNetwork.classify(true, false))
        assertEquals(OtaNetwork.Suitability.None, OtaNetwork.classify(false, false))
        assertEquals(OtaNetwork.Suitability.None, OtaNetwork.classify(false, true))
    }

    @Test
    fun `OtaNetwork reads the capability and never the deprecated boolean`() {
        val bytes = classBytes("OtaNetwork")
        assertTrue(
            "OtaNetwork no longer reads NetworkCapabilities. The metered question has exactly one " +
                "correct source and this is it.",
            bytes.references("android/net/NetworkCapabilities")
        )
        assertTrue(
            "OtaNetwork no longer calls hasCapability, so whatever it now decides on, it is not " +
                "the platform's own answer to 'do these bytes cost money'.",
            bytes.references("hasCapability")
        )
        assertTrue(
            "OtaNetwork calls isActiveNetworkMetered. That Boolean answers false for a phone with " +
                "no network at all, so a gate built on it reads 'no signal' as permission to start " +
                "a 1.6 GB download — the exact failure this file was added to prevent.",
            !bytes.references("isActiveNetworkMetered")
        )
        assertTrue(
            "OtaNetwork now decides on a TRANSPORT. A phone hotspot is TRANSPORT_WIFI and bills " +
                "cellular; a Wi-Fi the user marked metered is TRANSPORT_WIFI and bills them too.",
            !bytes.references("hasTransport")
        )
    }

    private fun ByteArray.references(marker: String): Boolean =
        String(this, Charsets.ISO_8859_1).contains(marker)

    private fun classBytes(simpleName: String): ByteArray {
        val entry = "watch/rist/assistant/$simpleName.class"
        val url = OtaState::class.java.getResource("OtaState.class")
        assertNotNull("cannot locate the compiled classes to scan", url)
        val bytes: ByteArray? = if (url!!.protocol == "jar") {
            val jarPath = java.net.URLDecoder.decode(
                url.path.substringAfter("file:").substringBefore("!"), "UTF-8"
            )
            java.util.jar.JarFile(jarPath).use { jar ->
                jar.getJarEntry(entry)?.let { jar.getInputStream(it).use { s -> s.readBytes() } }
            }
        } else {
            File(File(url.toURI()).parentFile, "$simpleName.class").takeIf { it.isFile }?.readBytes()
        }
        assertNotNull("$entry is not on the test classpath — this scan would prove nothing", bytes)
        assertTrue("$entry is empty", bytes!!.isNotEmpty())
        assertEquals("$entry is not a class file", 0xCA.toByte(), bytes[0])
        return bytes
    }
}
