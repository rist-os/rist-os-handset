package watch.rist.assistant

import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import android.view.View

@RunWith(RobolectricTestRunner::class)
class PairingScreenTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clean() {
        Config.setAuthToken(ctx(), "")
        Config.setEnrolRevoked(ctx(), false)
        Config.setSetupComplete(ctx(), false)
        Config.clearBackendOverride(ctx())
    }

    @After
    fun tidy() = clean()

    private fun settings(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).create().get()

    private fun settingsController() =
        Robolectric.buildActivity(SettingsActivity::class.java).create()

    @Test
    fun `the number and comms section is deliberately hidden`() {
        val a = settings()
        val root = a.findViewById<View>(R.id.settingsScroll)
        assertNull(
            "the number/comms section must not be drawn on this build",
            root.findViewWithTag<View>(a.MODE_SECTION_TAG)
        )
    }

    @Test
    fun `an unpaired device is offered the pairing field`() {
        val a = settings()
        val input = a.findViewById<EditText>(R.id.pairCodeInput)
        val submit = a.findViewById<TextView>(R.id.pairSubmit)
        val status = a.findViewById<TextView>(R.id.pairStatus)
        assertEquals("the code field must be visible on an unpaired device", View.VISIBLE, input.visibility)
        assertEquals(View.VISIBLE, submit.visibility)
        assertTrue("the prompt must say something", status.text.isNotBlank())
        assertEquals(
            "it must tell the user where a code comes from",
            a.getString(R.string.pair_prompt), status.text.toString()
        )
    }

    @Test
    fun `a token cannot be stored without a keystore, and the screen stays honest`() {
        Config.setAuthToken(ctx(), "ristd_test.secret")
        assertEquals(
            "the secret-filtering fallback is expected to drop this write silently",
            "", Config.authToken(ctx())
        )
        val a = settings()
        assertEquals(View.VISIBLE, a.findViewById<EditText>(R.id.pairCodeInput).visibility)
        assertNotEquals(
            "a device holding no credential must never render as connected",
            a.getString(R.string.pair_connected),
            a.findViewById<TextView>(R.id.pairStatus).text.toString()
        )
    }

    @Test
    fun `a revoked device says so, and is not offered a field that cannot help`() {
        Config.setEnrolRevoked(ctx(), true)
        val a = settings()
        val status = a.findViewById<TextView>(R.id.pairStatus).text.toString()
        assertEquals(a.getString(R.string.pair_revoked), status)
        assertNotEquals(
            "a revoked device must never read as connected",
            a.getString(R.string.pair_connected), status
        )
        assertEquals(View.GONE, a.findViewById<EditText>(R.id.pairCodeInput).visibility)
        assertEquals(View.GONE, a.findViewById<TextView>(R.id.pairSubmit).visibility)
    }

    @Test
    fun `the code field is left empty however the section is built`() {
        assertEquals("", settings().findViewById<EditText>(R.id.pairCodeInput).text.toString())
        Config.setEnrolRevoked(ctx(), true)
        assertEquals("", settings().findViewById<EditText>(R.id.pairCodeInput).text.toString())
    }

    @Test
    fun `the pairing status line is never blank in any state`() {
        for ((revoked, name) in listOf(false to "unpaired", true to "revoked")) {
            Config.setEnrolRevoked(ctx(), revoked)
            val text = settings().findViewById<TextView>(R.id.pairStatus).text.toString()
            assertTrue("the $name state renders a blank pairing status", text.isNotBlank())
        }
    }

    @Test
    fun `tapping connect with a code engages the in-flight guard`() {
        val a = settings()
        val input = a.findViewById<EditText>(R.id.pairCodeInput)
        val submit = a.findViewById<TextView>(R.id.pairSubmit)
        val status = a.findViewById<TextView>(R.id.pairStatus)

        input.setText("PAIR7F3K")
        submit.performClick()

        assertFalse(
            "submit must be disabled while a request is in flight, or a second tap spends the code again",
            submit.isEnabled
        )
        assertEquals(
            "the user must be told something is happening",
            a.getString(R.string.pair_working), status.text.toString()
        )
    }

    @Test
    fun `tapping connect with an empty code starts nothing`() {
        val a = settings()
        val submit = a.findViewById<TextView>(R.id.pairSubmit)
        val status = a.findViewById<TextView>(R.id.pairStatus)

        a.findViewById<EditText>(R.id.pairCodeInput).setText("")
        submit.performClick()

        assertTrue("an empty submit must leave the button usable", submit.isEnabled)
        assertEquals(
            "and must not claim to be connecting",
            a.getString(R.string.pair_prompt), status.text.toString()
        )
    }

    @Test
    fun `the keyboard Done key cannot bypass the in-flight guard`() {
        val a = settings()
        val input = a.findViewById<EditText>(R.id.pairCodeInput)
        val submit = a.findViewById<TextView>(R.id.pairSubmit)
        val status = a.findViewById<TextView>(R.id.pairStatus)

        input.setText("PAIR7F3K")
        submit.performClick()
        status.text = "SENTINEL"

        input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)

        assertEquals(
            "Done fired a second redemption of a single-use code past a disabled button",
            "SENTINEL", status.text.toString()
        )
    }

    @Test
    fun `the field comes back when a device returns to needing a code`() {
        Config.setEnrolRevoked(ctx(), true)
        val c = settingsController()
        val a = c.get()
        assertEquals(
            "precondition: a revoked device hides the field",
            View.GONE, a.findViewById<EditText>(R.id.pairCodeInput).visibility
        )

        Config.setEnrolRevoked(ctx(), false)
        c.resume()

        assertEquals(
            "the field never came back, so this device can never be re-paired from Settings",
            View.VISIBLE, a.findViewById<EditText>(R.id.pairCodeInput).visibility
        )
        assertEquals(View.VISIBLE, a.findViewById<TextView>(R.id.pairSubmit).visibility)
    }

    @Test
    fun `a rebuild clears a code left in the field`() {
        val c = settingsController()
        val a = c.get()
        a.findViewById<EditText>(R.id.pairCodeInput).setText("SPENT123")

        c.resume()

        assertEquals(
            "a spent code survived a rebuild and would persist into saved instance state",
            "", a.findViewById<EditText>(R.id.pairCodeInput).text.toString()
        )
    }

    private fun stalePairedUi(a: SettingsActivity) {
        a.findViewById<TextView>(R.id.pairStatus).text = a.getString(R.string.pair_connected)
        a.findViewById<EditText>(R.id.pairCodeInput).visibility = View.GONE
        a.findViewById<TextView>(R.id.pairSubmit).visibility = View.GONE
    }

    private fun latestDialog(): android.app.AlertDialog? =
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()

    /** AlertController posts button listeners through its handler; idle the looper. */
    private fun answer(which: Int) {
        val d = latestDialog() ?: throw AssertionError(
            "the endpoint control changed the backend with no confirmation at all"
        )
        d.getButton(which).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun confirm() = answer(android.content.DialogInterface.BUTTON_POSITIVE)

    private fun cancel() = answer(android.content.DialogInterface.BUTTON_NEGATIVE)

    private fun tap(a: SettingsActivity, id: Int) = assertTrue(
        "nothing is listening to this control, so this test proves nothing",
        a.findViewById<TextView>(id).performClick()
    )

    @Test
    fun `saving a new endpoint restores the pairing prompt and the code box`() {
        val a = settings()
        stalePairedUi(a)

        a.findViewById<EditText>(R.id.backendUrlInput).setText("https://new.example/v1/device")
        tap(a, R.id.backendSave)

        assertEquals(
            "the endpoint change did not take effect",
            "https://new.example/v1/device", Config.backendUrl(ctx())
        )
        assertEquals(
            "the screen still claims this device is authenticated by a backend that has never " +
                "seen it -- buildPairSection was not re-run after the endpoint moved",
            a.getString(R.string.pair_prompt),
            a.findViewById<TextView>(R.id.pairStatus).text.toString()
        )
        assertEquals(
            "the code box never came back, so the device cannot be paired to the new backend " +
                "from the only screen that offers to",
            View.VISIBLE, a.findViewById<EditText>(R.id.pairCodeInput).visibility
        )
        assertEquals(View.VISIBLE, a.findViewById<TextView>(R.id.pairSubmit).visibility)
    }

    @Test
    fun `resetting to the default endpoint restores the pairing prompt and the code box`() {
        Config.setBackendEndpoint(ctx(), "https://old.example/v1/device")
        val a = settings()
        stalePairedUi(a)

        tap(a, R.id.backendReset)

        assertNotEquals(
            "the override survived a reset",
            "https://old.example/v1/device", Config.backendUrl(ctx())
        )
        assertEquals(
            "a reset left the connected message standing",
            a.getString(R.string.pair_prompt),
            a.findViewById<TextView>(R.id.pairStatus).text.toString()
        )
        assertEquals(View.VISIBLE, a.findViewById<EditText>(R.id.pairCodeInput).visibility)
        assertEquals(View.VISIBLE, a.findViewById<TextView>(R.id.pairSubmit).visibility)
    }

    @Test
    fun `the endpoint move happens inside the confirm listener and nowhere else`() {
        val a = settings()
        var moved = 0
        val d = a.raiseBackendChangeDialog(a.getString(R.string.backend_change_confirm)) { moved++ }

        assertEquals("raising the warning already did the destructive work", 0, moved)

        d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Cancel ran the change the user just declined", 0, moved)

        val d2 = a.raiseBackendChangeDialog(a.getString(R.string.backend_change_confirm)) { moved++ }
        d2.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("confirming the warning did not run the change", 1, moved)
    }

    @Test
    fun `a handset with no credential moves the endpoint without being asked`() {
        assertTrue(
            "fixture is not in the unpaired state this test is about",
            Enrolment.needed(ctx()),
        )
        val a = settings()
        a.findViewById<EditText>(R.id.backendUrlInput).setText("https://new.example/v1/device")
        tap(a, R.id.backendSave)

        assertNull("a handset with nothing to lose was interrogated anyway", latestDialog())
        assertEquals(
            "the endpoint was not written, so the confirmation was skipped AND the save was lost",
            "https://new.example/v1/device",
            Config.backendUrl(ctx()),
        )
    }

    @Test
    fun `the warning names the loss and the remedy`() {
        val a = settings()
        val d = a.raiseBackendChangeDialog(a.getString(R.string.backend_change_confirm)) {}
        val body = taggedIn(d.window!!.decorView, "rist_dialog_message") as? TextView
            ?: throw AssertionError(
                "the warning is the platform's own dialog, painted from Theme.RistAssistant " +
                    "rather than from the theme the device is wearing"
            )
        val text = body.text.toString()
        assertTrue("the warning does not say the connection is lost: $text", text.contains("disconnect"))
        assertTrue("the warning does not say a new code is needed: $text", text.contains("pairing code"))
        assertNotNull(
            "the warning has no way to decline it",
            d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
        )
    }

    private fun taggedIn(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) {
            taggedIn(v.getChildAt(i), tag)?.let { return it }
        }
        return null
    }

    private fun countTextViews(v: View?): Int = when (v) {
        null -> 0
        is android.view.ViewGroup -> (0 until v.childCount).sumOf { countTextViews(v.getChildAt(it)) }
        is TextView -> 1
        else -> 0
    }
}
