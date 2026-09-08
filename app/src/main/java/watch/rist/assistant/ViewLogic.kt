package watch.rist.assistant

import rist.v1.Component
import java.util.Base64

object ViewLogic {

    private val URL_KEYS = listOf("src", "url")
    private val INLINE_KEYS = listOf("data", "bytes")
    private val TEXT_KEYS = listOf("text", "value")

    enum class Kind { STACK, CARD, TEXT, IMAGE, UNKNOWN }

    fun kindOf(type: String?): Kind = when (type?.trim()?.lowercase()) {
        "stack" -> Kind.STACK
        "card" -> Kind.CARD
        "text" -> Kind.TEXT
        "image" -> Kind.IMAGE
        else -> Kind.UNKNOWN
    }

    sealed interface ImageSource {
        data class Url(val url: String) : ImageSource
        class Inline(val bytes: ByteArray) : ImageSource
        object None : ImageSource
    }

    fun resolveImageSource(props: Map<String, String>, maxImageBytes: Int): ImageSource {
        firstNonBlank(props, INLINE_KEYS)?.let { b64 ->
            val decoded = decodeBase64(b64)
            if (decoded != null && decoded.isNotEmpty() && decoded.size <= maxImageBytes) {
                return ImageSource.Inline(decoded)
            }
        }
        firstNonBlank(props, URL_KEYS)?.let { return ImageSource.Url(it) }
        return ImageSource.None
    }

    fun decodeBase64(raw: String): ByteArray? {
        val payload = raw.substringAfter("base64,", raw).trim()
        if (payload.isEmpty()) return null
        // Order matters: MIME must go last, it silently drops chars outside the standard alphabet.
        return runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(payload) }.getOrNull()
            ?: runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull()
    }

    fun textFor(component: Component): String =
        firstNonBlank(component.propsMap, TEXT_KEYS) ?: component.fallbackText

    fun fallbackFor(component: Component): String = component.fallbackText

    fun flatten(root: Component?): List<Component> {
        if (root == null) return emptyList()
        val out = ArrayList<Component>()
        fun visit(c: Component) {
            out.add(c)
            c.childrenList.forEach(::visit)
        }
        visit(root)
        return out
    }

    private fun firstNonBlank(props: Map<String, String>, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key -> props[key]?.takeIf { it.isNotBlank() } }
}
