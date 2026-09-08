package watch.rist.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceWordingTest {

    private val userFacing = listOf(
        "app/src/main/res/values/strings.xml",
        "app/src/main/java/watch/rist/assistant/Enrolment.kt",
        "app/src/main/java/watch/rist/assistant/Uploader.kt",
        "app/src/main/java/watch/rist/assistant/VoicemailTranscript.kt",
        "app/src/main/java/watch/rist/assistant/VoicemailAudio.kt",
    )

    private val allowed = listOf("mobile service", "assistant service")

    @Test
    fun `no user-facing string says "service" without saying which service`() {
        val offenders = mutableListOf<String>()
        for (rel in userFacing) {
            val text = sourceFile(rel)
            for (literal in literalsIn(rel, text)) {
                if (!WORD.containsMatchIn(literal)) continue
                if (allowed.any { literal.contains(it, ignoreCase = true) }) continue
                offenders += "$rel: $literal"
            }
        }
        assertTrue(
            "these say \"service\" where a phone owner hears \"cellular service\"; say " +
                "\"assistant service\":\n  " + offenders.joinToString("\n  "),
            offenders.isEmpty(),
        )
    }

    private val WORD = Regex("""\bservices?\b""")

    private fun literalsIn(rel: String, text: String): List<String> =
        if (rel.endsWith(".xml")) {
            Regex("""<string[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(text).map { it.groupValues[1] }.toList()
        } else {
            val code = text.lineSequence().joinToString("\n") { it.substringBefore("//") }
            Regex(""""([^"\\\n]|\\.)*"""").findAll(code).map { it.value.trim('"') }.toList()
        }

    private fun sourceFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, relative)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
