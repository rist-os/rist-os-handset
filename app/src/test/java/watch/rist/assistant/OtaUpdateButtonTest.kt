package watch.rist.assistant

import android.Manifest
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class OtaUpdateButtonTest {

    private val build = "stallion-2026090401"
    private val bytes = 2_100_000_000L

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        OtaState.clearOffer(ctx())
        OtaState.clearApprovals(ctx())
        // A staged build outranks every other state; clearApprovals is what resets consent.
        OtaState.setReadyBuild(ctx(), "")
        Config.setThemeId(ctx(), "ledger")
        setNetwork(connected = true, metered = false)
    }

    @After
    fun tearDown() {
        OtaState.clearOffer(ctx())
        OtaSection.stopRefresh()
    }

    // Robolectric's default active network is unmetered Wi-Fi; dropping the capability makes it cellular.
    private fun setNetwork(connected: Boolean, metered: Boolean) {
        val cm = ctx().getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        if (!connected) {
            shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, null)
            return
        }
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!metered) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    // Resumed, not merely created: SettingsActivity.onResume is what builds this section.
    private fun activity(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().get()

    private fun host(a: SettingsActivity): LinearLayout =
        a.findViewById<LinearLayout>(R.id.themePicker)?.parent as LinearLayout

    private fun rebuild(a: SettingsActivity) =
        OtaSection.build(a, host(a), a.findViewById(R.id.sectionsAnchor))

    // Tags are literals, not reads of the constants, so the assertion can fail when a value moves.
    private fun section(a: SettingsActivity): ViewGroup? =
        taggedIn(a.window.decorView, "rist_ota_section") as ViewGroup?

    private fun button(a: SettingsActivity): TextView? =
        taggedIn(a.window.decorView, "ota_update_button") as TextView?

    private fun sectionText(a: SettingsActivity): String {
        val box = section(a) ?: throw AssertionError("SYSTEM UPDATES is not on the settings screen")
        val out = StringBuilder()
        fun walk(v: View) {
            if (v is TextView) out.append(v.text).append('\n')
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(box)
        return out.toString()
    }

    private fun latestDialog(): AlertDialog? = ShadowAlertDialog.getLatestAlertDialog()

    private fun dialogMessage(): String = dialogView("rist_dialog_message").text.toString()

    private fun dialogTitle(): String = dialogView("rist_dialog_title").text.toString()

    private fun dialogView(tag: String): TextView {
        val d = latestDialog() ?: throw AssertionError("no dialog was shown at all")
        val root = d.window?.decorView ?: throw AssertionError("the dialog has no window")
        val v = taggedIn(root, tag) ?: throw AssertionError(
            "no view tagged '$tag' in the dialog, so this is the platform's own dialog -- painted " +
                "from Theme.RistAssistant and not from the theme the device is wearing"
        )
        return v as TextView
    }

    private fun taggedIn(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            taggedIn(v.getChildAt(i), tag)?.let { return it }
        }
        return null
    }

    private fun showingDialogs(): List<android.app.Dialog> =
        ShadowDialog.getShownDialogs().filter { it.isShowing }

    private fun dismissAnyDialog() {
        latestDialog()?.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // AlertController posts the click through its own handler; the paused looper must be idled.
    private fun press(which: Int) {
        val d = latestDialog() ?: throw AssertionError("no dialog to answer")
        d.getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clickPositive() = press(DialogInterface.BUTTON_POSITIVE)

    private fun clickNegative() = press(DialogInterface.BUTTON_NEGATIVE)

    private fun s(id: Int, vararg args: Any): String = ctx().getString(id, *args)

    @Test
    fun `nothing is offered, so no button is drawn`() {
        val a = activity()
        assertNotNull("the section itself must still be on the screen", section(a))
        assertNull(
            "a handset with no pending update must show no button. Absent, never greyed: a " +
                "disabled control invites pressing and then explains itself in a toast.",
            button(a)
        )
    }

    @Test
    fun `an offer puts the button on the real settings screen`() {
        OtaState.recordOffer(ctx(), build, bytes)
        val a = activity()
        val b = button(a) ?: throw AssertionError(
            "OtaSection builds an update button but SettingsActivity does not put it on screen, " +
                "so the user can never consent and the update never installs"
        )
        assertEquals("UPDATE", b.text.toString().uppercase())
        assertNotNull("the button is not inside the SYSTEM UPDATES block", section(a))
        assertTrue(
            "the button is not inside the SYSTEM UPDATES block, so it is loose on the screen",
            taggedIn(section(a)!!, "ota_update_button") === b
        )
    }

    @Test
    fun `the button carries the tag the settings screen is searched by`() {
        assertEquals("ota_update_button", OtaSection.BUTTON_TAG)
        OtaState.recordOffer(ctx(), build, bytes)
        assertEquals("ota_update_button", button(activity())!!.tag)
    }

    @Test
    fun `the button is tappable and meets the minimum touch target`() {
        OtaState.recordOffer(ctx(), build, bytes)
        val b = button(activity())!!
        assertTrue("the update button must be clickable", b.isClickable)
        assertEquals("48dp minimum touch target", 48, b.minHeight)
    }

    @Test
    fun `the button is painted as a button, not as another line of text`() {
        OtaState.recordOffer(ctx(), build, bytes)
        val a = activity()
        val t = Themes.byId(Config.themeId(ctx()))
        val d = a.resources.displayMetrics.density
        val bg = button(a)!!.background as? android.graphics.drawable.GradientDrawable
            ?: throw AssertionError(
                "the update control has no background of its own, so it renders as one more line " +
                    "of text among the status lines above it"
            )
        assertEquals("the button is not on the theme's tile", t.tileFill, bg.color!!.defaultColor)
        assertEquals("the button has no accent outline, which is the only thing marking it out",
            14f * d, bg.cornerRadius, 0.01f)
        assertEquals(
            "the label is not in the theme's ink",
            t.ink, button(a)!!.currentTextColor
        )
        assertTrue("the label must be shouted in caps, like every other control on this phone",
            button(a)!!.isAllCaps)
    }

    @Test
    fun `rebuilding replaces the button rather than stacking a second one`() {
        OtaState.recordOffer(ctx(), build, bytes)
        val a = activity()
        rebuild(a)
        rebuild(a)

        var buttons = 0
        fun walk(v: View) {
            if (v.tag == "ota_update_button") buttons++
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.window.decorView)
        assertEquals(
            "the settings screen now carries $buttons update buttons; every one of them can " +
                "approve the download, and the older ones are painted from a stale snapshot",
            1, buttons
        )
    }

    @Test
    fun `the section states the download size and names the build`() {
        OtaState.recordOffer(ctx(), build, bytes)
        val text = sectionText(activity())
        val size = OtaConsent.formatBytes(bytes)
        assertTrue("the size must appear; expected '$size' in: $text", text.contains(size))
        assertTrue("the build number must appear in: $text", text.contains(build))
    }

    @Test
    fun `on mobile data the section recommends wifi rather than inviting a one-tap install`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = true)
        val a = activity()
        val text = sectionText(a)

        assertTrue("the metered case must recommend Wi‑Fi somewhere, got: $text",
            text.contains(s(R.string.ota_wifi_hint)))
        assertNotNull("there is still an update to install, so there is still a button", button(a))
    }

    @Test
    fun `the wifi recommendation is there on wifi too`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = false)
        assertTrue(sectionText(activity()).contains(s(R.string.ota_wifi_hint)))
    }

    @Test
    fun `with no network the section says to connect rather than recommending wifi`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = false, metered = false)
        val text = sectionText(activity())
        assertTrue("expected a connect prompt, got: $text", text.contains(s(R.string.ota_offline_body)))
        assertTrue(
            "the connect prompt and the recommendation are both on screen, saying the same thing " +
                "twice in two sizes: $text",
            !text.contains(s(R.string.ota_wifi_hint))
        )
    }

    @Test
    fun `an approved build reports that it is downloading, rather than nothing`() {
        OtaState.recordOffer(ctx(), build, bytes)
        OtaState.approveBuild(ctx(), build, allowMetered = false)

        val a = activity()
        val text = sectionText(a)
        assertTrue("expected a downloading status, got: $text", text.contains("is downloading"))
        assertFalse(
            "the offer is being repeated after it was accepted, which reads as though the press " +
                "did nothing. Got: $text",
            text.contains("is ready to install")
        )
        val b = button(a) ?: throw AssertionError(
            "the downloading state is a dead end -- if the download never starts, the user is " +
                "left looking at a sentence that will never change and cannot be acted on"
        )
        assertEquals(s(R.string.ota_downloading_stop).uppercase(), b.text.toString().uppercase())
    }

    @Test
    fun `stopping the wait returns the phone to offering the update`() {
        OtaState.recordOffer(ctx(), build, bytes)
        OtaState.approveBuild(ctx(), build, allowMetered = false)
        setNetwork(connected = true, metered = false)
        val a = activity()

        button(a)!!.performClick()
        assertNotNull("pressing STOP WAITING explained nothing", latestDialog())
        clickPositive()

        assertEquals(
            "stopping the wait must release the approval, or the state comes straight back",
            "", OtaState.approvedBuild(ctx())
        )
        rebuild(a)
        assertTrue("the update should be on offer again; got: ${sectionText(a)}",
            sectionText(a).contains("is ready to install"))
    }

    @Test
    fun `an approval that mobile data has blocked asks instead of claiming to download`() {
        OtaState.recordOffer(ctx(), build, bytes)
        OtaState.approveBuild(ctx(), build, allowMetered = false)
        setNetwork(connected = true, metered = true)

        val a = activity()
        val text = sectionText(a)
        assertFalse(
            "the phone says it is downloading while the gate is holding for an answer it never " +
                "asks for. Got: $text",
            text.contains("is downloading")
        )
        assertTrue("expected the state to name mobile data; got: $text",
            text.contains("needs mobile data"))
        val b = button(a) ?: throw AssertionError("no control to release the gate with")
        assertEquals("UPDATE", b.text.toString().uppercase())

        b.performClick()
        assertEquals("the press must raise the mobile-data question, not something else",
            s(R.string.ota_metered_title), dialogTitle())
    }

    @Test
    fun `a staged build asks for the restart that finishes it, and offers nothing to press`() {
        OtaState.recordOffer(ctx(), build, bytes)
        OtaState.setReadyBuild(ctx(), build)

        val a = activity()
        val text = sectionText(a)
        assertTrue("expected a restart prompt, got: $text", text.contains("Restart the phone to finish"))
        assertTrue(
            "the footnote that says HOW to restart is missing, on a device with no navigation " +
                "bar and no shade. Got: $text",
            text.contains(s(R.string.ota_ready_body))
        )
        assertFalse(
            "a staged build must not still be offering to install; it is already installed. " +
                "Got: $text",
            text.contains("is ready to install")
        )
        assertNull(
            "a restart is not something a button on this screen can perform, so offering one " +
                "would be a control that cannot do what it says",
            button(a)
        )
    }

    @Test
    fun `no button is drawn while an apply is running`() {
        OtaState.recordOffer(ctx(), build, bytes)
        OtaState.approveBuild(ctx(), build, allowMetered = false)
        OtaState.setApplyingBuild(ctx(), build, OtaScheduler.trustedNowSeconds())

        val a = activity()
        assertNull("a control beside a live 1.5 GB download can only do harm", button(a))
        assertTrue("the bar's caption is missing, so the space is simply empty again",
            sectionText(a).contains(s(R.string.ota_progress_caption)))
    }

    @Test
    fun `pressing on wifi approves this build and only this build`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = false)
        button(activity())!!.performClick()

        assertEquals("the pressed build must be approved", build, OtaState.approvedBuild(ctx()))
        assertEquals(
            "a Wi-Fi press must NOT record metered consent -- that is a separate question the " +
                "user was never asked",
            "", OtaState.meteredApprovedBuild(ctx())
        )
    }

    @Test
    fun `pressing on mobile data does not approve anything on its own`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = true)
        button(activity())!!.performClick()

        assertEquals(
            "a single press on a metered network approved a multi-gigabyte download; the user " +
                "must be asked about mobile data first",
            "", OtaState.approvedBuild(ctx())
        )
        assertEquals("", OtaState.meteredApprovedBuild(ctx()))
    }

    @Test
    fun `pressing on mobile data opens the question instead of doing nothing`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = true)
        button(activity())!!.performClick()

        assertNotNull(
            "the metered press opened no dialog. The button is a dead control: the user presses " +
                "it and the phone does nothing at all, forever.",
            latestDialog()
        )
        val message = dialogMessage()
        assertTrue(
            "the mobile-data question must quote what it would spend; got: $message",
            message.contains(OtaConsent.formatBytes(bytes))
        )
        assertEquals(
            "the question must be the mobile-data one, not some other dialog",
            s(R.string.ota_metered_body, OtaConsent.formatBytes(bytes)), message
        )
    }

    @Test
    fun `agreeing to mobile data records consent for this build`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = true)
        button(activity())!!.performClick()
        clickPositive()

        assertEquals("the pressed build must be approved", build, OtaState.approvedBuild(ctx()))
        assertEquals(
            "the user pressed 'Use mobile data' and no metered consent was recorded, so the " +
                "download they just authorised can never start",
            build, OtaState.meteredApprovedBuild(ctx())
        )
        assertEquals(
            OtaConsent.Gate.Go,
            OtaConsent.decide(build, OtaState.approvedBuild(ctx()),
                OtaState.meteredApprovedBuild(ctx()), OtaNetwork.Suitability.Metered)
        )
    }

    @Test
    fun `waiting for wifi records nothing and leaves the offer standing`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = true)
        val a = activity()
        button(a)!!.performClick()
        clickNegative()

        assertEquals("", OtaState.approvedBuild(ctx()))
        assertEquals("", OtaState.meteredApprovedBuild(ctx()))
        assertEquals("the offer must survive a 'not now' so the user can come back to it",
            build, OtaState.offeredBuild(ctx()))
        rebuild(a)
        assertNotNull("the button must still be drawn after declining mobile data", button(a))
    }

    @Test
    fun `with no network the press explains rather than silently doing nothing`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = false, metered = false)
        button(activity())!!.performClick()

        assertNotNull("the offline press opened no dialog and approved nothing: a dead control",
            latestDialog())
        assertEquals(s(R.string.ota_offline_body), dialogMessage())
        assertEquals("nothing may be approved with no network to download over",
            "", OtaState.approvedBuild(ctx()))
    }

    @Test
    fun `the network is read at the press, not when the button was drawn`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = false)
        val a = activity()
        val b = button(a)!!

        setNetwork(connected = true, metered = true)
        b.performClick()

        assertEquals(
            "the button was drawn on Wi-Fi and pressed on cellular, and the press approved the " +
                "download outright. The suitability must be re-read at the press.",
            "", OtaState.approvedBuild(ctx())
        )
        assertNotNull("the mobile-data question was never asked", latestDialog())
        assertEquals(
            s(R.string.ota_metered_body, OtaConsent.formatBytes(bytes)), dialogMessage()
        )
    }

    @Test
    fun `a scheduled start is reported as started`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = false)
        button(activity())!!.performClick()

        assertEquals(
            "the update was scheduled and the user was told it could not be started",
            s(R.string.ota_offer_started), dialogMessage()
        )
    }

    @Test
    fun `a refusal after the approval is reported as a refusal`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = true)
        button(activity())!!.performClick()

        setNetwork(connected = false, metered = false)
        clickPositive()

        assertEquals(
            "the start was refused and the user was told the update is starting, so they will " +
                "wait for a download that is not happening",
            s(R.string.ota_offer_refused), dialogMessage()
        )
    }

    @Test
    fun `approving repaints the section instead of leaving the offer standing`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = false)
        val a = activity()
        button(a)!!.performClick()

        // Deliberately no rebuild() here: the press itself has to have caused the repaint.
        val text = sectionText(a)
        assertTrue(
            "the screen still offers the update that was just approved, so the user's press " +
                "looks like it did nothing and the obvious next move is to press it again. " +
                "Got: $text",
            text.contains("is downloading")
        )
        assertNotNull(
            "the answered state must still lead somewhere, or a download that never starts traps " +
                "the user on a sentence that cannot change",
            button(a)
        )
    }

    @Test
    fun `an acted-on button stops being a control immediately`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = false)
        val b = button(activity())!!
        b.performClick()
        assertFalse(
            "the button that was just acted on is still clickable, so a second press re-approves " +
                "the same build and asks the scheduler for another check",
            b.isClickable
        )
    }

    @Test
    fun `a second press does not stack a second mobile-data question`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = true)
        val b = button(activity())!!
        b.performClick()
        b.performClick()

        assertEquals(
            "two presses opened two dialogs: ${showingDialogs().size} are on screen at once",
            1, showingDialogs().size
        )
    }

    @Test
    fun `a second press on wifi does not raise a second dialog`() {
        OtaState.recordOffer(ctx(), build, bytes)
        setNetwork(connected = true, metered = false)
        val b = button(activity())!!
        b.performClick()
        b.performClick()

        assertEquals(
            "the outcome dialog was reported twice, one on top of the other",
            1, showingDialogs().size
        )
    }

    private fun paintedFromTheme(t: RistTheme, a: SettingsActivity) {
        val floor = 15f * a.resources.displayMetrics.scaledDensity
        val body = dialogView("rist_dialog_message")
        assertEquals(
            "${t.name}: the dialog body is not the theme's ink, so it is painted from " +
                "Theme.RistAssistant rather than from the theme on the device",
            t.ink, body.currentTextColor
        )
        assertTrue(
            "${t.name}: the dialog body is ${body.textSize}px, below this product's 15sp floor " +
                "($floor px). That floor exists for the people this handset is built for.",
            body.textSize >= floor
        )
        val ok = latestDialog()!!.getButton(DialogInterface.BUTTON_POSITIVE)
        assertEquals(
            "${t.name}: the dialog's buttons are not in the theme's accent, so the one coloured " +
                "thing on the screen is not the thing you press",
            t.accent, ok.currentTextColor
        )
        assertTrue("${t.name}: the button text is below the 15sp floor", ok.textSize >= floor)

        val bg = latestDialog()!!.window!!.decorView.background
        val inset = bg as? android.graphics.drawable.InsetDrawable
            ?: throw AssertionError(
                "${t.name}: the dialog window still carries the platform's own background, so the " +
                    "question is asked on a white card no matter what the phone is wearing"
            )
        val face = inset.drawable as android.graphics.drawable.GradientDrawable
        assertEquals(
            "${t.name}: the dialog is not painted on the theme's surface",
            t.tileFill, face.color!!.defaultColor
        )
    }

    @Test
    fun `the mobile-data question is painted from the runtime theme on both shipping themes`() {
        for (t in Themes.ALL) {
            OtaState.clearApprovals(ctx())
            OtaState.recordOffer(ctx(), build, bytes)
            Config.setThemeId(ctx(), t.id)
            setNetwork(connected = true, metered = true)
            val a = activity()
            button(a)!!.performClick()

            assertEquals(
                "${t.name}: this is not the mobile-data question",
                s(R.string.ota_metered_title), dialogTitle()
            )
            paintedFromTheme(t, a)
            dismissAnyDialog()
        }
    }

    @Test
    fun `the offline and outcome dialogs are painted from the runtime theme too`() {
        for (t in Themes.ALL) {
            OtaState.clearApprovals(ctx())
            OtaState.recordOffer(ctx(), build, bytes)
            Config.setThemeId(ctx(), t.id)

            setNetwork(connected = false, metered = false)
            val a = activity()
            button(a)!!.performClick()
            assertEquals("${t.name}: this is not the offline dialog",
                s(R.string.ota_offline_body), dialogMessage())
            paintedFromTheme(t, a)
            dismissAnyDialog()

            setNetwork(connected = true, metered = false)
            rebuild(a)
            button(a)!!.performClick()
            assertEquals("${t.name}: this is not the outcome dialog",
                s(R.string.ota_offer_started), dialogMessage())
            paintedFromTheme(t, a)
            dismissAnyDialog()
        }
    }

    @Test
    fun `every dialog offers a button that dismisses it`() {
        OtaState.recordOffer(ctx(), build, bytes)
        Config.setThemeId(ctx(), "night")

        setNetwork(connected = true, metered = true)
        val a = activity()
        button(a)!!.performClick()
        val metered = latestDialog()!!
        assertTrue("the mobile-data question has no way out",
            metered.getButton(DialogInterface.BUTTON_POSITIVE).isShown)
        assertTrue("'Wait for Wi-Fi' is gone, so declining is impossible",
            metered.getButton(DialogInterface.BUTTON_NEGATIVE).isShown)
        clickNegative()
        assertFalse("the dialog did not close", metered.isShowing)

        setNetwork(connected = false, metered = false)
        rebuild(a)
        button(a)!!.performClick()
        val offline = latestDialog()!!
        assertTrue("the offline dialog has no way out",
            offline.getButton(DialogInterface.BUTTON_POSITIVE).isShown)
        clickPositive()
        assertFalse("the offline dialog did not close", offline.isShowing)
    }

    private fun home() = Robolectric.buildActivity(MainActivity::class.java).create().get()

    private fun aHealthyQuietHandset() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.READ_SMS)
        Config.setCredentialRejected(ctx(), false)
        Config.setCarrierVoicemailWaiting(ctx(), false)
    }

    @Test
    fun `an update offer alone no longer draws the comms feed`() {
        aHealthyQuietHandset()
        val a = home()
        CommsFeedView.render(a)
        assertEquals(
            "fixture check: this handset must have nothing else to show, or the assertion below " +
                "proves nothing",
            View.GONE, a.findViewById<ViewGroup>(R.id.commsFeed)!!.visibility
        )

        OtaState.recordOffer(ctx(), build, bytes)
        CommsFeedView.render(a)
        assertEquals(
            "an update offer opened the comms feed on the home screen. Nothing is drawn in it any " +
                "more, so this is an empty container -- and if something IS drawn in it, there are " +
                "two update surfaces again.",
            View.GONE, a.findViewById<ViewGroup>(R.id.commsFeed)!!.visibility
        )
    }

    @Test
    fun `a staged build does not draw the comms feed either`() {
        aHealthyQuietHandset()
        OtaState.recordOffer(ctx(), build, bytes)
        OtaState.setReadyBuild(ctx(), build)
        val a = home()
        CommsFeedView.render(a)
        assertEquals(
            "a staged build opened the comms feed. The restart prompt is on the settings screen " +
                "now, and this container has nothing to put in it.",
            View.GONE, a.findViewById<ViewGroup>(R.id.commsFeed)!!.visibility
        )
    }

    @Test
    fun `the comms feed no longer knows anything about updates`() {
        val code = String(classBytes("CommsFeedView"), Charsets.ISO_8859_1)
        assertFalse(
            "CommsFeedView references OtaConsent again. The home screen is not an update surface: " +
                "there is one control and it is in Settings; two places asking about the " +
                "same download is how one of them forgets the metered question.",
            code.contains("watch/rist/assistant/OtaConsent")
        )
        assertFalse(
            "CommsFeedView reads OtaState again -- the same defect one layer lower, and the shape " +
                "the visibility gate had before it was removed.",
            code.contains("watch/rist/assistant/OtaState")
        )
    }

    private fun classBytes(simpleName: String): ByteArray {
        val url = OtaState::class.java.getResource("OtaState.class")
        assertTrue("cannot locate the compiled classes to scan", url != null)
        val bytes: ByteArray? = if (url!!.protocol == "jar") {
            val jarPath = java.net.URLDecoder.decode(
                url.path.substringAfter("file:").substringBefore("!"), "UTF-8"
            )
            java.util.jar.JarFile(jarPath).use { jar ->
                jar.getJarEntry("watch/rist/assistant/$simpleName.class")
                    ?.let { jar.getInputStream(it).use { s -> s.readBytes() } }
            }
        } else {
            java.io.File(java.io.File(url.toURI()).parentFile, "$simpleName.class")
                .takeIf { it.isFile }?.readBytes()
        }
        assertTrue("$simpleName.class is not on the test classpath — this scan would prove nothing",
            bytes != null && bytes.isNotEmpty())
        assertEquals("$simpleName.class is not a class file", 0xCA.toByte(), bytes!![0])
        return bytes
    }
}
