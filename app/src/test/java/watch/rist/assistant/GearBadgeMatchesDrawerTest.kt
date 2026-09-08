package watch.rist.assistant

import android.Manifest
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class GearBadgeMatchesDrawerTest {

    // If a tile is added, add it here.
    private val tileIds = intArrayOf(
        R.id.drawerBadgePhone,
        R.id.drawerBadgeMsg,
        R.id.drawerBadgeCam,
        R.id.drawerBadgePics,
        R.id.drawerBadgeMaps,
        R.id.drawerBadgeSet,
    )

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clean() = reset()

    @After
    fun tearDown() = reset()

    private fun reset() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.READ_SMS)
        seed()
        Config.setMailUnread(ctx(), 0)
        // Every preference the feed reads must be cleared here.
        Config.setMailAcknowledged(ctx(), 0)
        Config.setCarrierVoicemailWaiting(ctx(), false)
        Config.setCredentialRejected(ctx(), false)
        Config.setEnrolRevoked(ctx(), false)
    }

    private fun seed(vararg pkgs: Pair<String, Int>) {
        NotificationHub.update(ApplicationProvider.getApplicationContext(), pkgs.toMap())
    }

    private fun seedVoicemail(vararg pkgs: Pair<String, Int>) {
        NotificationHub.update(
            ApplicationProvider.getApplicationContext(), pkgs.toMap(), voicemailPosted = true,
        )
    }

    // The local broadcast is posted, not delivered inline.
    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).create().start().resume().get()

    // Read the gear before calling this: opening the drawer sets gearBadge to GONE.
    private fun openDrawer(a: MainActivity) {
        requireNotNull(a.findViewById<View>(R.id.settingsGear)) { "no gear to tap" }.performClick()
        settle()
    }

    // applyBadge renders "9+" above nine; keep every seeded count and sum under ten.
    private fun shown(a: MainActivity, id: Int): Int {
        val v = a.findViewById<TextView>(id) ?: return 0
        if (v.visibility != View.VISIBLE) return 0
        val label = v.text.toString().trim()
        return label.toIntOrNull() ?: throw AssertionError(
            "badge label \"$label\" is not a plain number — applyBadge caps at \"9+\", so this " +
                "case has seeded ten or more and the gear/tile comparison it is making is " +
                "meaningless. Lower the seeded counts."
        )
    }

    private fun gear(a: MainActivity) = shown(a, R.id.gearBadge)
    private fun tiles(a: MainActivity) = tileIds.sumOf { shown(a, it) }

    private fun gearIsVisible(a: MainActivity) =
        a.findViewById<TextView>(R.id.gearBadge)?.visibility == View.VISIBLE

    private fun feedSays(a: MainActivity): String {
        val host = a.findViewById<LinearLayout>(R.id.commsFeed) ?: return ""
        if (host.visibility != View.VISIBLE) return ""
        val sb = StringBuilder()
        fun walk(g: ViewGroup) {
            for (i in 0 until g.childCount) {
                when (val child = g.getChildAt(i)) {
                    is ViewGroup -> walk(child)
                    is TextView -> sb.append(child.text).append(" | ")
                }
            }
        }
        walk(host)
        return sb.toString()
    }

    @Test
    fun `the fixture is clean, so an idle phone badges nothing anywhere`() {
        val a = open()
        assertEquals("an idle phone must not badge the gear", 0, gear(a))
        assertEquals("...and the feed must have nothing to say", "", feedSays(a))
        openDrawer(a)
        assertEquals("...nor any tile", 0, tiles(a))
    }

    @Test
    fun `no component of Waiting without a tile may reach the gear's total`() {
        val everything = CommsFeed.Waiting(sms = 1, calls = 2, notices = 4, voicemail = 8, mail = 16)
        val c = DrawerBadges.of(
            everything,
            phone = 0, messages = 0, camera = 0, gallery = 0, maps = 0, settings = 0,
        )
        assertEquals(
            "the gear's total must be sms + calls and nothing else: notices (+4), voicemail (+8) " +
                "and mail (+16) have no tile in the drawer, so anything they add here is a number " +
                "on the gear that opening the drawer cannot explain. The overshoot names the leak.",
            3, c.total,
        )
        assertEquals("sms belongs on the MSG tile", 1, c.msg)
        assertEquals("calls belong on the PHONE tile", 2, c.phone)
    }

    @Test
    fun `a SETTINGS notification lands on the settings tile, not only on the gear`() {
        seed(AppLauncher.PKG_SETTINGS to 1)
        val a = open()
        val onTheGear = gear(a)
        openDrawer(a)
        assertEquals(
            "a settings notification must show on the SETTINGS tile",
            1, shown(a, R.id.drawerBadgeSet),
        )
        assertEquals("and the gear must agree with the tiles", onTheGear, tiles(a))
    }

    @Test
    fun `unread MAIL has no tile, so it must be in the feed and not on the gear`() {
        Config.setMailUnread(ctx(), 3)
        val a = open()
        val onTheGear = gear(a)

        assertEquals(
            "unread mail has no tile, so it cannot be allowed to badge the gear",
            0, onTheGear,
        )
        val said = feedSays(a)
        assertTrue(
            "three unread emails are on this device and NOTHING says so: the gear is silent by " +
                "design and the feed drew nothing. Feed content was: \"$said\"",
            said.contains("unread email"),
        )
        assertTrue(
            "the feed must name the count it is announcing, not just that mail exists — \"$said\"",
            said.contains("3"),
        )

        openDrawer(a)
        assertEquals("and the gear must still agree with the tiles", onTheGear, tiles(a))
    }

    @Test
    fun `a waiting VOICEMAIL has no tile, so it must be in the feed and not on the gear`() {
        seedVoicemail()
        val a = open()
        assertTrue("this test needs a waiting voicemail or there is nothing to check",
            CarrierVoicemail.waiting(a))
        assertTrue(
            "this test assumes no row in the voicemail provider — with one, the PHONE tile would " +
                "legitimately carry the voicemail and the case under test would not exist",
            !CarrierVoicemail.unacknowledged(a),
        )

        val onTheGear = gear(a)
        assertEquals(
            "a waiting voicemail has no tile in the drawer, so it must not badge the gear",
            0, onTheGear,
        )
        val said = feedSays(a)
        assertTrue(
            "the voicemail is off the gear and must therefore be in the feed — \"$said\"",
            said.contains("VOICEMAIL"),
        )

        openDrawer(a)
        assertEquals("and the gear must still agree with the tiles", onTheGear, tiles(a))
    }

    @Test
    fun `the gear never shows more than the drawer can account for`() {
        seedVoicemail(
            AppLauncher.PKG_GALLERY to 3,
            AppLauncher.PKG_SETTINGS to 2,
            AppLauncher.PKG_MAPS to 1,
        )
        Config.setMailUnread(ctx(), 2)

        val a = open()
        assertTrue("this case needs the untiled voicemail present to be worth running",
            CarrierVoicemail.waiting(a))
        val onTheGear = gear(a)
        assertEquals(
            "the three TILED notifications and only those: the untiled voicemail (+1) and the two " +
                "untiled unread emails (+2) must not reach the gear",
            6, onTheGear,
        )
        openDrawer(a)
        assertEquals(
            "the gear promises the drawer's contents; anything it counts must have a tile to land " +
                "on, or the user opens the drawer and finds nothing",
            onTheGear, tiles(a),
        )
    }

    @Test
    fun `an arrival while the drawer is open cannot badge the gear over it`() {
        val a = open()
        openDrawer(a)
        assertEquals("precondition: the drawer opened empty", 0, tiles(a))

        seed(AppLauncher.PKG_CAMERA to 2)
        settle()

        assertTrue(
            "the gear badge repainted itself VISIBLE on top of an open drawer — the one thing " +
                "this whole invariant exists to prevent",
            !gearIsVisible(a),
        )
        assertEquals("a hidden gear counts as zero, and must be zero", 0, gear(a))
    }

    @Test
    fun `an arrival while the drawer is open lands on the tile straight away`() {
        val a = open()
        openDrawer(a)
        assertEquals("precondition: the drawer opened empty", 0, tiles(a))

        seed(AppLauncher.PKG_CAMERA to 2)
        settle()

        assertEquals(
            "a camera notification arrived while the drawer was open and the CAMERA tile still " +
                "reads zero: the panel in front of the user is showing stale counts",
            2, shown(a, R.id.drawerBadgeCam),
        )
        assertEquals("and nothing else moved", 2, tiles(a))
    }

    @Test
    fun `closing the drawer gives the gear its number back`() {
        val a = open()
        openDrawer(a)
        seed(AppLauncher.PKG_CAMERA to 2)
        settle()
        assertTrue("precondition: hidden while open", !gearIsVisible(a))

        a.onBackPressedDispatcher.onBackPressed()
        settle()
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()

        assertNotEquals(
            "the gear stayed silent after the drawer closed, so the arrival that landed while it " +
                "was open is now announced nowhere",
            0, gear(a),
        )
        assertEquals("and it is the tiles' number", 2, gear(a))
    }

    @Test
    fun `dismissing the mail notice removes it`() {
        seed()
        Config.setMailUnread(ctx(), 3)
        val a = open()
        assertTrue("the notice must be there to begin with", feedText(a).contains("email"))

        requireNotNull(findMailDismiss(a)) { "the mail notice has no dismiss control" }.performClick()

        assertTrue(
            "after dismissing, the feed must no longer announce unread email",
            !feedText(a).contains("email"),
        )
    }

    @Test
    fun `dismissing is a high-water mark, so the next email brings the notice back`() {
        seed()
        Config.setMailUnread(ctx(), 3)
        val a = open()
        requireNotNull(findMailDismiss(a)).performClick()
        assertTrue("dismissed", !feedText(a).contains("email"))

        Config.setMailUnread(ctx(), 4)
        CommsFeedView.render(a)
        assertTrue("a new email after a dismissal must be announced", feedText(a).contains("email"))
    }

    @Test
    fun `a mailbox that empties and refills re-arms rather than staying silent`() {
        seed()
        Config.setMailUnread(ctx(), 2)
        val a = open()
        requireNotNull(findMailDismiss(a)).performClick()

        Config.setMailUnread(ctx(), 0)
        CommsFeedView.render(a)
        assertTrue("nothing unread, nothing said", !feedText(a).contains("email"))

        Config.setMailUnread(ctx(), 1)
        CommsFeedView.render(a)
        assertTrue(
            "one new email after the mailbox emptied must be announced",
            feedText(a).contains("email"),
        )
    }

    private fun feedText(a: MainActivity): String {
        val host = a.findViewById<LinearLayout>(R.id.commsFeed) ?: return ""
        val sb = StringBuilder()
        fun walk(v: View) {
            if (v is TextView) sb.append(v.text).append(' ')
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(host)
        return sb.toString()
    }

    private fun findMailDismiss(a: MainActivity): View? {
        val host = a.findViewById<LinearLayout>(R.id.commsFeed) ?: return null
        var hit: View? = null
        fun walk(v: View) {
            if (v.contentDescription == "Dismiss the unread email notice") hit = v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(host)
        return hit
    }
}
