package watch.rist.assistant

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/** Photos wait above the message box and go with the next message, typed or spoken. */
@RunWith(RobolectricTestRunner::class)
class PendingPhotosTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))

    private fun staged(n: Int): List<File> {
        val dir = File(app.cacheDir, "photos").apply { mkdirs() }
        return (0 until n).map { i ->
            File(dir, "staged-test-$i.jpg").also { f ->
                val bmp = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
        }
    }

    private fun homeWith(files: List<File>): MainActivity {
        val state = Bundle().apply {
            putStringArrayList("staged_photos", ArrayList(files.map { it.absolutePath }))
        }
        return Robolectric.buildActivity(MainActivity::class.java).setup(state).get().also { settle() }
    }

    private fun MainActivity.v(id: Int): View = findViewById(id)

    @Test
    fun noPhotos_noStrip_andTheCameraIsOffered() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { settle() }
        assertEquals(View.GONE, a.v(R.id.pendingPhotosScroll).visibility)
        assertEquals(View.VISIBLE, a.v(R.id.photoButton).visibility)
        assertEquals(View.GONE, a.v(R.id.sendButton).visibility)
    }

    @Test
    fun aWaitingPhoto_showsInTheStrip_andCanBeSentWithoutWords() {
        val a = homeWith(staged(1))
        assertEquals(View.VISIBLE, a.v(R.id.pendingPhotosScroll).visibility)
        assertEquals(1, a.findViewById<LinearLayout>(R.id.pendingPhotos).childCount)
        assertEquals(View.VISIBLE, a.v(R.id.sendButton).visibility)
        // Another photo still fits, so the camera stays beside the send arrow.
        assertEquals(View.VISIBLE, a.v(R.id.photoButton).visibility)
    }

    @Test
    fun typing_keepsTheCamera_soAPhotoCanJoinTheWords() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { settle() }
        a.findViewById<TextView>(R.id.textInput).text = "what is this"
        settle()
        assertEquals(View.VISIBLE, a.v(R.id.photoButton).visibility)
        assertEquals(View.VISIBLE, a.v(R.id.sendButton).visibility)
    }

    @Test
    fun aFullStrip_hidesTheCamera() {
        val a = homeWith(staged(Uploader.MAX_PHOTOS_PER_TURN))
        assertEquals(Uploader.MAX_PHOTOS_PER_TURN, a.findViewById<LinearLayout>(R.id.pendingPhotos).childCount)
        assertEquals(View.GONE, a.v(R.id.photoButton).visibility)
    }

    @Test
    fun theCross_removesThePhoto_andDeletesItsFile() {
        val files = staged(2)
        val a = homeWith(files)
        val row = a.findViewById<LinearLayout>(R.id.pendingPhotos)
        val cross = (row.getChildAt(0) as FrameLayout).getChildAt(1)
        cross.performClick()
        settle()
        assertEquals(1, row.childCount)
        assertFalse(files[0].exists())

        ((row.getChildAt(0) as FrameLayout).getChildAt(1)).performClick()
        settle()
        assertEquals(View.GONE, a.v(R.id.pendingPhotosScroll).visibility)
        assertEquals(View.GONE, a.v(R.id.sendButton).visibility)
    }
}
