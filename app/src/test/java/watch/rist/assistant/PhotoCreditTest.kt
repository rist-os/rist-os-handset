package watch.rist.assistant

import android.graphics.Bitmap
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A searched picture's credit is one short line under it: "Photo: site · author · licence". */
@RunWith(RobolectricTestRunner::class)
class PhotoCreditTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    private val commons = "Tour Eiffel vue depuis le Champ de Mars, Paris, au coucher du soleil " +
        "by Benh LIEU SONG (Wikimedia Commons, Public domain)"

    @Before fun clean() {
        Config.usePlainPrefsForTest(app)
        Transcript.clearForTest(app)
        ReceivedPhotos.clearAll(app)
    }

    @After fun tidy() {
        Transcript.clearForTest(app)
        ReceivedPhotos.clearAll(app)
        Config.forgetPrefsForTest()
    }

    @Test
    fun `the long credit is cut to site, author and licence`() {
        assertEquals("Photo: Wikimedia Commons · Benh LIEU SONG · Public domain", AttachmentView.shortCredit(commons))
        // A title with "by" in it: the author is after the last one.
        assertEquals("Photo: Wikimedia Commons · Ana · CC BY-SA 4.0",
            AttachmentView.shortCredit("Night by the river by Ana (Wikimedia Commons, CC BY-SA 4.0)"))
        assertEquals("Photo: Wikimedia Commons · Public domain",
            AttachmentView.shortCredit("Tour Eiffel (Wikimedia Commons, Public domain)"))
        assertEquals("Photo: Wikimedia Commons · Ana",
            AttachmentView.shortCredit("Tour Eiffel by Ana (Wikimedia Commons)"))
    }

    @Test
    fun `anything that is not the long form is kept as it came`() {
        assertEquals("Photo: theguardian.com", AttachmentView.shortCredit("  Photo: theguardian.com "))
        assertEquals("Tower (Paris)", AttachmentView.shortCredit("Tower (Paris)"))
        assertEquals("Photo by Ana, CC BY 4.0", AttachmentView.shortCredit("Photo by Ana, CC BY 4.0"))
        assertEquals("a b", AttachmentView.shortCredit("a\n  b"))
        assertEquals("", AttachmentView.shortCredit("   "))
    }

    private val png: ByteArray by lazy {
        val out = java.io.ByteArrayOutputStream()
        Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    private fun texts(v: View): List<TextView> =
        (if (v is TextView) listOf(v) else emptyList()) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList()

    @Test
    fun `a kept picture on the feed shows the short credit on at most two lines`() {
        val id = Transcript.begin(app, "show me the eiffel tower", EntryState.WAITING)
        Transcript.update(app, id, state = EntryState.ANSWERED, answer = "Here it is.")
        ReceivedPhotos.save(app, id, listOf(RistAttachment(
            kind = "image", mime = "image/png", title = commons, text = "",
            bytes = png, toolId = "image-search", error = null,
        )))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()

        val caption = texts(a.findViewById(R.id.replyContainer))
            .single { it.text.toString().startsWith("Photo:") }
        assertEquals("Photo: Wikimedia Commons · Benh LIEU SONG · Public domain", caption.text.toString())
        assertEquals(2, caption.maxLines)
        assertEquals(TextUtils.TruncateAt.END, caption.ellipsize)
        assertTrue("the long form is not on the feed",
            texts(a.findViewById(R.id.replyContainer)).none { it.text.toString().contains("Champ de Mars") })
    }
}
