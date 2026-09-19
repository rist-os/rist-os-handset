package watch.rist.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkActivityTest {

    @Test
    fun `only the camera's scanned links open, and an unknown sender is refused`() {
        assertTrue(LinkActivity.opensFrom(AppLauncher.PKG_CAMERA))
        assertFalse(LinkActivity.opensFrom("com.android.messaging"))
        assertFalse(LinkActivity.opensFrom("watch.rist.assistant"))
        assertFalse(LinkActivity.opensFrom(null))
        assertFalse(LinkActivity.opensFrom(""))
    }
}
