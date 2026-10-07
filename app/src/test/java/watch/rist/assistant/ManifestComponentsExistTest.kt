package watch.rist.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every component the manifest declares must be a class that exists.
 *
 * This is not hypothetical tidiness. Android instantiates a declared component when its filter
 * fires, and a manifest name is only a string, so the app compiles perfectly with a name that
 * resolves to nothing and then throws ClassNotFoundException at the moment the event arrives.
 */
class ManifestComponentsExistTest {

    private val manifest = File("src/main/AndroidManifest.xml")
        .takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")

    private val packageName = "watch.rist.assistant"

    /** Declared component names, as written — ".Foo", "com.other.Bar", or a bare name. */
    private fun declaredNames(): List<String> {
        val text = manifest.readText()
        val tag = Regex("""<(activity|service|receiver|provider)\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("""android:name\s*=\s*"([^"]+)"""")
        return tag.findAll(text).mapNotNull { name.find(it.value)?.groupValues?.get(1) }.toList()
    }

    private fun qualify(n: String): String = when {
        n.startsWith(".") -> packageName + n
        !n.contains('.') -> "$packageName.$n"
        else -> n
    }

    @Test
    fun `the manifest exists where the test expects it`() {
        assertTrue("cannot find AndroidManifest.xml from ${File(".").absolutePath}", manifest.exists())
    }

    @Test
    fun `every declared component resolves to a real class`() {
        val missing = declaredNames().map(::qualify).filter { fqcn ->
            runCatching { Class.forName(fqcn, false, javaClass.classLoader) }.isFailure
        }
        assertTrue(
            "the manifest declares components whose classes do not exist. Android instantiates " +
                "a declared component when its filter fires, so each of these is a crash waiting " +
                "for the right event: " + missing.joinToString(", "),
            missing.isEmpty(),
        )
    }

    /**
     * This receiver is the only thing that tells the app a call is ringing. Without it
     * IncomingCall.show has no callers and the IncomingCallActivity answer screen is unreachable. An
     * incoming call then rings, vibrates and shows nothing, because the stock dialer's alternative is
     * a full-screen intent that lock task suppresses.
     */
    @Test
    fun `the receiver that raises our own call screen is declared`() {
        assertTrue(
            "PhoneStateReceiver is not declared. Without it nothing calls IncomingCall.show(), " +
                "IncomingCallActivity is dead code, and an incoming call cannot be answered.",
            declaredNames().any { it.endsWith("PhoneStateReceiver") },
        )
    }

    /**
     * The call screen came up sideways on a handset that was not upright, because this activity was
     * the only one in the app that did not lock portrait. That is not cosmetic: in landscape the
     * column of title, caller, ANSWER and DECLINE is taller than the screen, DECLINE was pushed off
     * the bottom, and the lowest button a person could see and tap was ANSWER. A call meant to be
     * rejected was answered instead.
     */
    @Test
    fun `the call screen is locked to portrait`() {
        val text = manifest.readText()
        val block = Regex("""<activity[^>]*\.IncomingCallActivity.*?/>""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.value
        assertTrue("IncomingCallActivity is not declared at all", block != null)
        assertTrue(
            "IncomingCallActivity must set screenOrientation=\"portrait\". Without it the screen " +
                "follows the sensor, and in landscape DECLINE is pushed off the bottom -- so a tap " +
                "meant for it lands on ANSWER.",
            block!!.contains("android:screenOrientation=\"portrait\""),
        )
    }
}
