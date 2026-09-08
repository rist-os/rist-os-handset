package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

class DeployAssetsTest {

    private fun releaseAssetIsAllowed(declaredVariant: String, backend: String, push: String): Boolean =
        when (declaredVariant.trim().lowercase()) {
            "provisioned" -> true
            "public", "" -> backend.isBlank() && push.isBlank()
            else -> false
        }

    @Test
    fun anUndeclaredReleaseMayNotCarryAnEndpoint() {
        assertFalse(releaseAssetIsAllowed("", "https://api.example.test/v1/device", ""))
        assertFalse(releaseAssetIsAllowed("", "", "wss://push.example/v0/stream"))
        assertTrue(releaseAssetIsAllowed("", "", ""))
    }

    @Test
    fun aPublicReleaseMayNotCarryAnEndpointEvenWhenOneIsConfigured() {
        assertFalse(releaseAssetIsAllowed("public", "https://api.example.test/v1/device", ""))
        assertTrue(releaseAssetIsAllowed("public", "", ""))
    }

    @Test
    fun aProvisionedReleaseIsSupposedToCarryOne() {
        assertTrue(releaseAssetIsAllowed("provisioned", "https://api.example.test/v1/device", "wss://p/v0"))
    }

    @Test
    fun anUnrecognisedVariantIsRefusedRatherThanTreatedAsPublic() {
        assertFalse(releaseAssetIsAllowed("prod", "", ""))
        assertFalse(releaseAssetIsAllowed("private", "", ""))
    }

    @Test
    fun theVariantIsReadCaseAndWhitespaceInsensitively_becauseAHumanTypesIt() {
        assertTrue(releaseAssetIsAllowed("  Provisioned  ", "https://x/y", ""))
        assertTrue(releaseAssetIsAllowed("PUBLIC", "", ""))
    }

    @Test
    fun noReleaseArtefactInThisTreeCarriesAnUndeclaredEndpoint() {
        val root = repoRoot() ?: run {
            println("[deploy-asset] repo root not found from ${File("").absolutePath}; nothing opened")
            return
        }
        val declared = declaredReleaseVariant(root)
        val scanned = mutableListOf<String>()

        File(root, "app/build/outputs/apk/release")
            .listFiles { f -> f.isFile && f.name.endsWith(".apk") }
            .orEmpty()
            .forEach { apk ->
                val text = ZipFile(apk).use { zip ->
                    zip.getEntry("assets/deploy.properties")?.let { e ->
                        zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                    }
                } ?: return@forEach
                scanned += apk.name
                check(apk.path, declared, text)
            }

        File(root, "app/build/intermediates/assets/release")
            .walkTopDown()
            .filter { it.isFile && it.name == "deploy.properties" }
            .forEach { f ->
                scanned += f.relativeTo(root).path
                check(f.path, declared, f.readText())
            }

        if (scanned.isEmpty()) {
            println("[deploy-asset] no release artefact on disk; nothing opened. " +
                "genDeployAssetsRelease in app/build.gradle.kts is the enforcement.")
        } else {
            println("[deploy-asset] declared variant='${declared.ifBlank { "(none)" }}'; " +
                "opened ${scanned.size}: ${scanned.joinToString(", ")}")
        }
    }

    private fun check(where: String, declared: String, content: String) {
        val p = Properties().apply { load(content.byteInputStream()) }
        val backend = p.getProperty("backendUrl").orEmpty().trim()
        val push = p.getProperty("pushUrl").orEmpty().trim()
        if (!releaseAssetIsAllowed(declared, backend, push)) {
            fail(
                "$where carries assets/deploy.properties with a compiled-in endpoint " +
                "(backendUrl ${present(backend)}, pushUrl ${present(push)}) while the declared " +
                "release variant is '${declared.ifBlank { "(none)" }}'.\n" +
                "A release artefact may carry an endpoint ONLY under rist.releaseVariant=provisioned. " +
                "Set rist.releaseVariant in local.properties (or RIST_RELEASE_VARIANT), rebuild, and " +
                "delete this artefact — it is a live server address inside a zip that " +
                ".publish-denylist cannot see into."
            )
        }
    }

    private fun present(s: String) = if (s.isBlank()) "empty" else "SET"

    private fun declaredReleaseVariant(root: File): String {
        val f = File(root, "local.properties")
        val fromProps = if (f.exists()) {
            f.inputStream().use { s -> Properties().apply { load(s) }.getProperty("rist.releaseVariant") }
        } else null
        return (fromProps ?: System.getenv("RIST_RELEASE_VARIANT") ?: "").trim()
    }

    private fun repoRoot(): File? {
        var d: File? = File("").absoluteFile
        while (d != null) {
            if (File(d, "settings.gradle.kts").isFile) return d
            d = d.parentFile
        }
        return null
    }

    @Test
    fun theEmptyAssetStillParsesToTwoBlankValues() {
        val p = Properties().apply { load("backendUrl=\npushUrl=\n".byteInputStream()) }
        assertEquals("" to "", Config.parseDeploy(p))
    }
}
