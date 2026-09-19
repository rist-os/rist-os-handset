package watch.rist.assistant

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A picture the assistant sent stays with its answer, and goes when the answer does. */
@RunWith(RobolectricTestRunner::class)
class ReceivedPhotosTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun wipe() = ReceivedPhotos.clearAll(ctx)

    private val png: ByteArray by lazy {
        val out = java.io.ByteArrayOutputStream()
        Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    // As it arrives from the backend, before the feed's own decode: bytes, no bitmap yet.
    private fun image(title: String = "Photo by Ana, CC BY 4.0", mime: String = "image/png") = RistAttachment(
        kind = "image", mime = mime, title = title, text = "",
        bytes = png, toolId = "image-search", error = null,
    )

    @Test
    fun `only a picture that decodes is kept, judged on the bytes as they arrived`() {
        assertTrue(ReceivedPhotos.isKeepable(image()))
        // Bytes that are not a picture cannot be tested here: Robolectric's decoder reports a
        // size for anything. On the phone the bounds decode is what rejects them.
        assertFalse(ReceivedPhotos.isKeepable(image().copy(bytes = null)))
        assertFalse(ReceivedPhotos.isKeepable(image().copy(error = "too large")))
        assertFalse(ReceivedPhotos.isKeepable(image().copy(kind = "text")))
    }

    @Test
    fun `pictures are kept under their answer, in order, with their credit`() {
        ReceivedPhotos.save(ctx, 42L, listOf(image("first", "image/jpeg"), image("second")))
        val kept = ReceivedPhotos.byEntry(ctx)[42L]!!
        assertEquals(listOf("first", "second"), kept.map { it.title })
        assertEquals(listOf("image/jpeg", "image/png"), kept.map { it.mime })
        assertTrue(kept.all { it.file.exists() })
    }

    @Test
    fun `a picture goes when its answer is cleared`() {
        ReceivedPhotos.save(ctx, 1L, listOf(image()))
        ReceivedPhotos.save(ctx, 2L, listOf(image()))
        ReceivedPhotos.prune(ctx, setOf(2L))
        val kept = ReceivedPhotos.byEntry(ctx)
        assertNull(kept[1L])
        assertEquals(1, kept[2L]!!.size)
    }

    @Test
    fun `no more than the cap is ever kept`() {
        for (entry in 1L..(ReceivedPhotos.MAX_KEPT + 5L)) ReceivedPhotos.save(ctx, entry, listOf(image()))
        assertEquals(ReceivedPhotos.MAX_KEPT, ReceivedPhotos.byEntry(ctx).values.sumOf { it.size })
    }

    @Test
    fun `nothing is kept without an answer to keep it under`() {
        ReceivedPhotos.save(ctx, 0L, listOf(image()))
        assertTrue(ReceivedPhotos.byEntry(ctx).isEmpty())
    }

    @Test
    fun `file names are read back, and nothing else is taken for a picture`() {
        assertEquals(12L to 3, ReceivedPhotos.parse("12-3.webp"))
        assertNull(ReceivedPhotos.parse("notes.jpg"))
        assertNull(ReceivedPhotos.parse("12.jpg"))
        assertEquals("gif", ReceivedPhotos.extensionFor("image/gif"))
        assertEquals("jpg", ReceivedPhotos.extensionFor("image/unknown"))
    }

    @Test
    fun `a zoomed picture never leaves a gap at an edge, and a small one is centred`() {
        // Larger than the view: held between the two edges.
        assertEquals(0f, ZoomImageView.clampOffset(50f, 2000f, 1000f))
        assertEquals(-1000f, ZoomImageView.clampOffset(-1500f, 2000f, 1000f))
        assertEquals(-300f, ZoomImageView.clampOffset(-300f, 2000f, 1000f))
        // Smaller than the view: centred, wherever it was dragged.
        assertEquals(250f, ZoomImageView.clampOffset(-80f, 500f, 1000f))
    }
}
