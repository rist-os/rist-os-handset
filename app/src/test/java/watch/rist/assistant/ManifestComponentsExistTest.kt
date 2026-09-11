package watch.rist.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every component the manifest declares must be a class that exists.
 *
 * This is not hypothetical tidiness. A `.PhoneStateReceiver` was declared with a PHONE_STATE
 * filter and the class was nowhere in the repo, so Android tried to instantiate it on every
 * single phone call, threw ClassNotFoundException, and killed the app. It crashed on calls for
 * as long as that line was there, and nothing caught it: the app compiles fine, because a
 * manifest name is a string.
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

    @Test
    fun `the receiver that crashed every call is gone`() {
        assertTrue(
            "PhoneStateReceiver is declared again; the class has never existed in this repo",
            declaredNames().none { it.endsWith("PhoneStateReceiver") },
        )
    }
}
