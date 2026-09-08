package watch.rist.assistant

import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object OtaSignature {

    // Line 2 of the minisign .pub: base64 of 2-byte alg | 8-byte key id (LE) | 32-byte Ed25519 key.
    const val PUBLIC_KEY = "RWThr8fGz/71qbSM4R8F9UvI7KYW++0i8dAQwyU+Fz24xqirceHqqHUX"

    const val PLACEHOLDER = "UNSET-NO-RELEASE-KEY-COMPILED-IN"

    const val MAX_LIFETIME_SECONDS = 30L * 24 * 3600

    enum class Fault {
        NO_PUBLIC_KEY,
        BAD_PUBLIC_KEY,
        NO_SIGNATURE,
        MALFORMED_SIGNATURE,
        UNSUPPORTED_ALGORITHM,
        WRONG_KEY,
        BAD_SIGNATURE,
        BAD_GLOBAL_SIGNATURE,
        NO_CRYPTO,
        NO_EXPIRY,
        BAD_EXPIRY,
        EXPIRED,
        LIFETIME_TOO_LONG,
    }

    sealed class Verdict {
        data class Trusted(val expiresAtSeconds: Long) : Verdict()
        data class Rejected(val fault: Fault, val detail: String) : Verdict()
    }

    fun verify(
        body: String?,
        signature: String?,
        nowSeconds: Long,
        publicKey: String = PUBLIC_KEY,
    ): Verdict {
        val key = parsePublicKey(publicKey) ?: return when {
            publicKey.isBlank() || publicKey.trim() == PLACEHOLDER ->
                Verdict.Rejected(Fault.NO_PUBLIC_KEY,
                    "no release key compiled in; refusing every update (see OtaSignature.PUBLIC_KEY)")
            else -> Verdict.Rejected(Fault.BAD_PUBLIC_KEY, "not a 42-byte minisign public key")
        }
        if (body.isNullOrEmpty()) return Verdict.Rejected(Fault.MALFORMED_SIGNATURE, "empty body")
        if (signature.isNullOrBlank()) {
            return Verdict.Rejected(Fault.NO_SIGNATURE, "no .minisig served alongside the manifest")
        }

        val sig = parseSignature(signature)
            ?: return Verdict.Rejected(Fault.MALFORMED_SIGNATURE, "not a minisign signature file")

        if (!sig.keyId.contentEquals(key.keyId)) {
            return Verdict.Rejected(Fault.WRONG_KEY,
                "signed by ${hex(sig.keyId)}, this build trusts ${hex(key.keyId)}")
        }

        val bytes = body.toByteArray(Charsets.UTF_8)
        val signed = when (sig.algorithm) {
            ALG_PREHASHED -> OtaBlake2b.digest512(bytes)
            ALG_LEGACY -> bytes
            else -> return Verdict.Rejected(Fault.UNSUPPORTED_ALGORITHM, sig.algorithm)
        }

        val primaryOk = try {
            ed25519Verify(key.publicKey, signed, sig.signature)
        } catch (e: GeneralSecurityException) {
            return Verdict.Rejected(Fault.NO_CRYPTO, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        if (!primaryOk) return Verdict.Rejected(Fault.BAD_SIGNATURE, "Ed25519 rejected the manifest")

        val globalOk = try {
            ed25519Verify(key.publicKey,
                sig.signature + sig.trustedComment.toByteArray(Charsets.UTF_8), sig.globalSignature)
        } catch (e: GeneralSecurityException) {
            return Verdict.Rejected(Fault.NO_CRYPTO, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        if (!globalOk) {
            return Verdict.Rejected(Fault.BAD_GLOBAL_SIGNATURE, "trusted comment is not signed")
        }

        return checkExpiry(body, nowSeconds)
    }

    internal fun checkExpiry(body: String, nowSeconds: Long): Verdict {
        val o = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return Verdict.Rejected(Fault.MALFORMED_SIGNATURE,
                "signature verified but the body is not JSON: ${e.message}")
        }
        if (!o.has("expires") || o.isNull("expires")) {
            return Verdict.Rejected(Fault.NO_EXPIRY,
                "signed manifest has no 'expires'; tools/ota_sign.py always writes one")
        }
        val expires = o.optLong("expires", -1L)
        if (expires <= 0L) return Verdict.Rejected(Fault.BAD_EXPIRY, "expires=${o.opt("expires")}")
        if (nowSeconds > expires) {
            return Verdict.Rejected(Fault.EXPIRED,
                "expired ${nowSeconds - expires}s ago (expires=$expires now=$nowSeconds)")
        }
        if (expires - nowSeconds > MAX_LIFETIME_SECONDS) {
            return Verdict.Rejected(Fault.LIFETIME_TOO_LONG,
                "expires in ${expires - nowSeconds}s, cap is $MAX_LIFETIME_SECONDS")
        }
        return Verdict.Trusted(expires)
    }

    // Public-key files carry `Ed` even when the signatures made with them are prehashed.
    private const val ALG_LEGACY = "Ed"

    // Ed25519 over BLAKE2b-512 of the message.
    private const val ALG_PREHASHED = "ED"

    private const val KEY_ID_BYTES = 8
    private const val ED25519_PUBLIC_KEY_BYTES = 32
    private const val ED25519_SIGNATURE_BYTES = 64

    internal data class PublicKey(val keyId: ByteArray, val publicKey: ByteArray)

    internal data class SignatureFile(
        val algorithm: String,
        val keyId: ByteArray,
        val signature: ByteArray,
        val trustedComment: String,
        val globalSignature: ByteArray,
    )

    internal fun parsePublicKey(raw: String?): PublicKey? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == PLACEHOLDER) return null
        val blob = text.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() && !it.startsWith("untrusted comment:") }
            ?: return null
        val d = decodeBase64(blob) ?: return null
        if (d.size != 2 + KEY_ID_BYTES + ED25519_PUBLIC_KEY_BYTES) return null
        if (String(d, 0, 2, Charsets.US_ASCII) != ALG_LEGACY) return null
        return PublicKey(
            keyId = d.copyOfRange(2, 2 + KEY_ID_BYTES),
            publicKey = d.copyOfRange(2 + KEY_ID_BYTES, d.size),
        )
    }

    // Four lines: untrusted comment, base64(alg|keyid|sig), "trusted comment: ...",
    // base64(global sig over sig||trusted comment). The trusted comment is signed untrimmed.
    internal fun parseSignature(raw: String?): SignatureFile? {
        val lines = raw?.replace("\r\n", "\n")?.replace('\r', '\n')?.split('\n')
            ?.filter { it.isNotEmpty() } ?: return null
        if (lines.size < 4) return null

        val sig = decodeBase64(lines[1].trim()) ?: return null
        if (sig.size != 2 + KEY_ID_BYTES + ED25519_SIGNATURE_BYTES) return null

        val tcPrefix = "trusted comment: "
        val tcLine = lines[2]
        if (!tcLine.startsWith(tcPrefix)) return null
        val trustedComment = tcLine.substring(tcPrefix.length)

        val global = decodeBase64(lines[3].trim()) ?: return null
        if (global.size != ED25519_SIGNATURE_BYTES) return null

        return SignatureFile(
            algorithm = String(sig, 0, 2, Charsets.US_ASCII),
            keyId = sig.copyOfRange(2, 2 + KEY_ID_BYTES),
            signature = sig.copyOfRange(2 + KEY_ID_BYTES, sig.size),
            trustedComment = trustedComment,
            globalSignature = global,
        )
    }

    // java.util.Base64, not android.util.Base64: the android stub returns null on the unit-test classpath.
    private fun decodeBase64(s: String): ByteArray? = try {
        Base64.getDecoder().decode(s)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun ed25519Verify(publicKey: ByteArray, message: ByteArray, sig: ByteArray): Boolean {
        if (sig.size != ED25519_SIGNATURE_BYTES) return false
        // X.509 SubjectPublicKeyInfo prefix for id-Ed25519 (RFC 8410).
        val der = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        ) + publicKey

        var last: GeneralSecurityException? = null
        for (name in listOf("Ed25519", "EdDSA")) {
            try {
                val key = KeyFactory.getInstance(name).generatePublic(X509EncodedKeySpec(der))
                val v = Signature.getInstance(name)
                v.initVerify(key)
                v.update(message)
                // Some providers throw SignatureException for a malformed signature instead of returning false.
                return try {
                    v.verify(sig)
                } catch (e: java.security.SignatureException) {
                    false
                }
            } catch (e: GeneralSecurityException) {
                last = e
            }
        }
        throw last ?: GeneralSecurityException("no Ed25519 provider")
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
