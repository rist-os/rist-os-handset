package watch.rist.assistant

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** The switches that would let a page reach past itself stay off, and only a web link gets in. */
@RunWith(RobolectricTestRunner::class)
class LockedBrowserTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a page gets no files, no content providers, no new windows and no mixed content`() {
        val settings = WebView(ctx).settings
        LockedBrowserActivity.harden(settings)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.supportMultipleWindows())
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
        assertTrue(settings.mediaPlaybackRequiresUserGesture)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
        assertEquals(WebSettings.LOAD_NO_CACHE, settings.cacheMode)
    }

    @Test
    fun `anything but a plain web link closes the browser before it opens`() {
        for (bad in listOf("intent://x#Intent;end", "file:///sdcard/x", "javascript:alert(1)", "")) {
            val activity = Robolectric.buildActivity(
                LockedBrowserActivity::class.java, LockedBrowserActivity.intent(ctx, bad)
            ).create().get()
            assertTrue(bad, activity.isFinishing)
        }
    }

    @Test
    fun `a web link opens, showing the site it is locked to`() {
        val activity = Robolectric.buildActivity(
            LockedBrowserActivity::class.java,
            LockedBrowserActivity.intent(ctx, "https://menu.example.co.uk/table/12"),
        ).create().get()
        assertFalse(activity.isFinishing)
    }
}
