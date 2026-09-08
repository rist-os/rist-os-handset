package watch.rist.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rist.v1.Component
import java.util.Base64

class ViewLogicTest {

    private fun component(
        type: String,
        props: Map<String, String> = emptyMap(),
        fallback: String = "",
        children: List<Component> = emptyList(),
    ): Component = Component.newBuilder()
        .setType(type)
        .putAllProps(props)
        .setFallbackText(fallback)
        .addAllChildren(children)
        .build()

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun kindOf_mapsKnownTypesCaseInsensitively_unknownDowngrades() {
        assertEquals(ViewLogic.Kind.STACK, ViewLogic.kindOf("stack"))
        assertEquals(ViewLogic.Kind.CARD, ViewLogic.kindOf("Card"))
        assertEquals(ViewLogic.Kind.TEXT, ViewLogic.kindOf(" TEXT "))
        assertEquals(ViewLogic.Kind.IMAGE, ViewLogic.kindOf("image"))
        assertEquals(ViewLogic.Kind.UNKNOWN, ViewLogic.kindOf("chart"))
        assertEquals(ViewLogic.Kind.UNKNOWN, ViewLogic.kindOf(null))
    }

    @Test
    fun resolveImageSource_prefersInlineBytes_overUrl() {
        val props = mapOf("data" to b64("PNGDATA"), "src" to "https://x/y.png")
        val src = ViewLogic.resolveImageSource(props, maxImageBytes = 1024)
        assertTrue(src is ViewLogic.ImageSource.Inline)
        assertArrayEquals("PNGDATA".toByteArray(), (src as ViewLogic.ImageSource.Inline).bytes)
    }

    @Test
    fun resolveImageSource_urlKeys_srcThenUrl() {
        assertEquals(
            ViewLogic.ImageSource.Url("https://a/1.png"),
            ViewLogic.resolveImageSource(mapOf("src" to "https://a/1.png"), 1024)
        )
        assertEquals(
            ViewLogic.ImageSource.Url("https://b/2.png"),
            ViewLogic.resolveImageSource(mapOf("url" to "https://b/2.png"), 1024)
        )
        assertEquals(
            ViewLogic.ImageSource.Url("https://a/1.png"),
            ViewLogic.resolveImageSource(mapOf("src" to "https://a/1.png", "url" to "https://b/2.png"), 1024)
        )
    }

    @Test
    fun resolveImageSource_inlineKeys_dataThenBytes() {
        assertTrue(ViewLogic.resolveImageSource(mapOf("bytes" to b64("Z")), 1024) is ViewLogic.ImageSource.Inline)
        val src = ViewLogic.resolveImageSource(mapOf("data" to b64("AA"), "bytes" to b64("BB")), 1024)
        assertArrayEquals("AA".toByteArray(), (src as ViewLogic.ImageSource.Inline).bytes)
    }

    @Test
    fun resolveImageSource_none_whenNoImageKeys() {
        assertEquals(ViewLogic.ImageSource.None, ViewLogic.resolveImageSource(mapOf("text" to "hi"), 1024))
        assertEquals(ViewLogic.ImageSource.None, ViewLogic.resolveImageSource(emptyMap(), 1024))
    }

    @Test
    fun resolveImageSource_oversizeInline_fallsThroughToUrl() {
        val big = b64("X".repeat(100))
        val src = ViewLogic.resolveImageSource(mapOf("data" to big, "src" to "https://x/y.png"), maxImageBytes = 4)
        assertEquals(ViewLogic.ImageSource.Url("https://x/y.png"), src)
    }

    @Test
    fun resolveImageSource_invalidInline_andNoUrl_isNone() {
        assertEquals(ViewLogic.ImageSource.None, ViewLogic.resolveImageSource(mapOf("data" to "@@@@"), 1024))
    }

    @Test
    fun decodeBase64_roundTrips_standardAlphabet() {
        val bytes = byteArrayOf(0, 1, 2, 3, 127, -1, -128)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        assertArrayEquals(bytes, ViewLogic.decodeBase64(encoded))
    }

    @Test
    fun decodeBase64_stripsDataUriPrefix() {
        val encoded = b64("hello")
        assertArrayEquals("hello".toByteArray(), ViewLogic.decodeBase64("data:image/png;base64,$encoded"))
    }

    @Test
    fun decodeBase64_toleratesWhitespaceAndUrlSafeAlphabet() {
        val mime = Base64.getMimeEncoder().encodeToString(ByteArray(60) { it.toByte() })
        assertArrayEquals(ByteArray(60) { it.toByte() }, ViewLogic.decodeBase64(mime))
        val raw = byteArrayOf(-5, -16, 63, 62)
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertArrayEquals(raw, ViewLogic.decodeBase64(urlSafe))
    }

    @Test
    fun decodeBase64_returnsNull_onGarbageOrEmpty() {
        assertNull(ViewLogic.decodeBase64("@@@not base64@@@!"))
        assertNull(ViewLogic.decodeBase64(""))
        assertNull(ViewLogic.decodeBase64("data:image/png;base64,"))
    }

    @Test
    fun textFor_prefersTextThenValueThenFallback() {
        assertEquals("A", ViewLogic.textFor(component("text", props = mapOf("text" to "A", "value" to "B"), fallback = "F")))
        assertEquals("B", ViewLogic.textFor(component("text", props = mapOf("value" to "B"), fallback = "F")))
        assertEquals("F", ViewLogic.textFor(component("text", fallback = "F")))
        assertEquals("", ViewLogic.textFor(component("text")))
    }

    @Test
    fun textFor_blankPropIsSkipped_forFallback() {
        assertEquals("F", ViewLogic.textFor(component("text", props = mapOf("text" to "   "), fallback = "F")))
    }

    @Test
    fun fallbackFor_returnsFallbackText() {
        assertEquals("downgrade", ViewLogic.fallbackFor(component("chart", fallback = "downgrade")))
    }

    @Test
    fun flatten_isPreOrder_includingRootAndAllDescendants() {
        val tree = component(
            "card", fallback = "root",
            children = listOf(
                component("text", props = mapOf("text" to "a")),
                component(
                    "stack",
                    children = listOf(
                        component("image", props = mapOf("src" to "https://x/y.png")),
                        component("text", props = mapOf("text" to "b")),
                    )
                ),
            )
        )
        val flat = ViewLogic.flatten(tree)
        assertEquals(listOf("card", "text", "stack", "image", "text"), flat.map { it.type })
        assertEquals(5, flat.size)
    }

    @Test
    fun flatten_nullRoot_isEmpty() {
        assertTrue(ViewLogic.flatten(null).isEmpty())
    }

    @Test
    fun flatten_singleLeaf_isSelfOnly() {
        assertEquals(1, ViewLogic.flatten(component("text")).size)
    }
}
