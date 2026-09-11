package watch.rist.assistant

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The screen between choosing a photo and sending it. Before it existed a photo left the
 * phone the moment it was taken: nothing to caption, nothing to take back.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoComposeActivityTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun photoFile(name: String): File {
        val f = File(app.cacheDir, name)
        val bmp = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return f
    }

    private fun launch(vararg files: File) = Robolectric.buildActivity(
        PhotoComposeActivity::class.java,
        Intent(app, PhotoComposeActivity::class.java).putStringArrayListExtra(
            PhotoComposeActivity.EXTRA_PATHS, ArrayList(files.map { it.absolutePath })
        ),
    ).setup()

    private fun paths(a: Activity): List<String>? =
        shadowOf(a).resultIntent?.getStringArrayListExtra(PhotoComposeActivity.EXTRA_PATHS)

    @Test
    fun `send hands back every photo and the trimmed caption`() {
        val a = photoFile("a.jpg")
        val b = photoFile("b.jpg")
        val act = launch(a, b).get()
        act.findViewById<EditText>(R.id.captionInput).setText("  what breed is this?  ")
        act.findViewById<View>(R.id.sendPhotoButton).performClick()

        assertEquals(Activity.RESULT_OK, shadowOf(act).resultCode)
        assertEquals(listOf(a.absolutePath, b.absolutePath), paths(act))
        assertEquals("what breed is this?",
            shadowOf(act).resultIntent.getStringExtra(PhotoComposeActivity.EXTRA_CAPTION))
        assertTrue("the caller sends them, so they must still exist", a.exists() && b.exists())
    }

    @Test
    fun `remove drops the selected photo and deletes its file`() {
        val a = photoFile("a.jpg")
        val b = photoFile("b.jpg")
        val act = launch(a, b).get()
        act.findViewById<View>(R.id.removeButton).performClick()

        assertFalse("a removed photo must not linger in the cache", a.exists())
        assertTrue(b.exists())
        act.findViewById<View>(R.id.sendPhotoButton).performClick()
        assertEquals(listOf(b.absolutePath), paths(act))
    }

    @Test
    fun `removing the only photo cancels`() {
        val a = photoFile("a.jpg")
        val act = launch(a).get()
        act.findViewById<View>(R.id.removeButton).performClick()

        assertTrue(act.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(act).resultCode)
        assertFalse(a.exists())
    }

    @Test
    fun `close cancels and leaves the files for the caller to clean up`() {
        val a = photoFile("a.jpg")
        val act = launch(a).get()
        act.findViewById<View>(R.id.closeButton).performClick()

        assertTrue(act.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(act).resultCode)
        assertTrue(a.exists())
    }

    @Test
    fun `a path that is not a file is ignored, and no photo at all cancels`() {
        val a = photoFile("a.jpg")
        val ghost = File(app.cacheDir, "ghost.jpg")
        val act = launch(ghost, a).get()
        act.findViewById<View>(R.id.sendPhotoButton).performClick()
        assertEquals(listOf(a.absolutePath), paths(act))

        assertTrue(launch(ghost).get().isFinishing)
    }

    @Test
    fun `the keyboard's send action sends`() {
        val a = photoFile("a.jpg")
        val act = launch(a).get()
        val field = act.findViewById<EditText>(R.id.captionInput)
        field.setText("hi")
        field.onEditorAction(EditorInfo.IME_ACTION_SEND)

        assertEquals(Activity.RESULT_OK, shadowOf(act).resultCode)
        assertEquals("hi", shadowOf(act).resultIntent.getStringExtra(PhotoComposeActivity.EXTRA_CAPTION))
    }
}
