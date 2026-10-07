package watch.rist.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import rist.v1.BoxSet
import rist.v1.HomeBox
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** A box's icon: a bundled Material Symbols name, else a small PNG mask, else none. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoxIconsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        BoxIcons.clearForTest()
        HomeBoxes.resetForTest(ctx)
    }

    /** A real greyscale+alpha PNG of [w] x [h], padded with [pad] bytes of an ignored chunk. */
    private fun png(w: Int, h: Int, pad: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            out.write(int(data.size))
            val td = type.toByteArray(Charsets.US_ASCII) + data
            out.write(td)
            out.write(int(CRC32().apply { update(td) }.value.toInt()))
        }
        val ihdr = ByteArrayOutputStream().apply {
            fun int(v: Int) = write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
            int(w); int(h); write(8); write(4); write(0); write(0); write(0)
        }.toByteArray()
        chunk("IHDR", ihdr)
        if (pad > 0) chunk("teXt", ByteArray(pad) { 'a'.code.toByte() })
        val raw = ByteArrayOutputStream()
        repeat(h) { raw.write(0); repeat(w) { raw.write(0); raw.write(255) } }
        val def = Deflater().apply { setInput(raw.toByteArray()); finish() }
        val buf = ByteArray(65536)
        val z = ByteArrayOutputStream()
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun box(icon: String = "", image: ByteArray? = null) = HomeBox.newBuilder().setId("b").setIcon(icon)
        .apply { if (image != null) setIconImage(ByteString.copyFrom(image)) }.build()

    @Test
    fun `a named icon resolves to its Material Symbols codepoint`() {
        assertEquals(0xe159, BoxIcons.codepoint(ctx, "mail"))
        assertEquals(0xf172, BoxIcons.codepoint(ctx, "partly_cloudy_day"))
        for (n in listOf("newspaper", "monitoring", "event", "voicemail", "alarm")) {
            assertNotNull(n, BoxIcons.codepoint(ctx, n))
        }
        assertEquals(BoxIcons.Source.Named(0xe159), BoxIcons.resolve(ctx, box(icon = "Mail")))
        assertNotNull("the bundled font loads", BoxIcons.typeface(ctx))
    }

    @Test
    fun `a custom image is drawn in place of the name, and a bad one falls back to the name`() {
        val s = BoxIcons.resolve(ctx, box(icon = "mail", image = png(24, 24)))
        assertTrue("icon_image replaces icon", s is BoxIcons.Source.Image)
        assertEquals(BoxIcons.Source.Named(0xe159), BoxIcons.resolve(ctx, box(icon = "mail", image = png(200, 200))))
    }

    @Test
    fun `warming decodes custom icons ahead of the first draw`() {
        val b = box(icon = "", image = png(32, 32))
        BoxIcons.warm(ctx, listOf(b))
        assertTrue(BoxIcons.resolve(ctx, b) is BoxIcons.Source.Image)
    }

    @Test
    fun `an unknown name falls back to the image, and with neither there is no icon`() {
        val s = BoxIcons.resolve(ctx, box(icon = "not_a_symbol_at_all", image = png(48, 48)))
        assertTrue(s is BoxIcons.Source.Image)
        assertEquals(48, (s as BoxIcons.Source.Image).mask.width)
        assertEquals(BoxIcons.Source.None, BoxIcons.resolve(ctx, box(icon = "not_a_symbol_at_all")))
        assertEquals(BoxIcons.Source.None, BoxIcons.resolve(ctx, box()))
    }

    @Test
    fun `an oversize or undecodable image is dropped`() {
        assertTrue(BoxIcons.imageAcceptable(png(96, 96)))
        assertFalse("too wide", BoxIcons.imageAcceptable(png(97, 10)))
        assertFalse("too many bytes", BoxIcons.imageAcceptable(png(16, 16, pad = 17 * 1024)))
        assertFalse("not a PNG", BoxIcons.imageAcceptable("GIF89a-not-a-png-at-all-really".toByteArray()))
        val broken = png(32, 32).copyOf(40)
        assertEquals(BoxIcons.Source.None, BoxIcons.resolve(ctx, box(image = broken)))
        assertEquals(BoxIcons.Source.None, BoxIcons.resolve(ctx, box(image = png(200, 200))))
    }

    @Test
    fun `an oversize image is not even kept with the box`() {
        HomeBoxes.apply(ctx, BoxSet.newBuilder().setVersion(1)
            .addBoxes(box(image = png(200, 200)))
            .addBoxes(box(image = png(20, 20)).toBuilder().setId("ok")).build())
        assertTrue(HomeBoxes.find(ctx, "b")!!.iconImage.isEmpty)
        assertFalse(HomeBoxes.find(ctx, "ok")!!.iconImage.isEmpty)
        assertNull(BoxIcons.codepoint(ctx, ""))
    }
}
