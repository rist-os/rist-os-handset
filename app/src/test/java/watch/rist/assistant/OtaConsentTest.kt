package watch.rist.assistant

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class OtaConsentTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<Context>()

    private val buildA = "2026072400"
    private val buildB = "2026080100"

    private val unmetered = OtaNetwork.Suitability.Unmetered
    private val metered = OtaNetwork.Suitability.Metered
    private val offline = OtaNetwork.Suitability.None

    private fun gate(
        build: String,
        approved: String,
        meteredApproved: String = "",
        net: OtaNetwork.Suitability = unmetered,
    ) = OtaConsent.decide(build, approved, meteredApproved, net)

    private fun heldFor(g: OtaConsent.Gate): OtaConsent.Hold {
        assertTrue("expected a hold, got $g", g is OtaConsent.Gate.Wait)
        return (g as OtaConsent.Gate.Wait).reason
    }

    @Test
    fun `an unapproved build does not download even on free wifi`() {
        assertEquals(OtaConsent.Hold.NOT_APPROVED, heldFor(gate(buildA, approved = "", net = unmetered)))
    }

    @Test
    fun `an approved build on unmetered proceeds`() {
        assertEquals(OtaConsent.Gate.Go, gate(buildA, approved = buildA, net = unmetered))
    }

    @Test
    fun `approval for one build does not authorise another`() {
        assertEquals(
            OtaConsent.Hold.APPROVED_OTHER_BUILD,
            heldFor(gate(buildB, approved = buildA, net = unmetered))
        )
    }

    @Test
    fun `agreeing to pay for one download does not pay for the next`() {
        assertEquals(
            OtaConsent.Hold.METERED_NOT_APPROVED,
            heldFor(gate(buildB, approved = buildB, meteredApproved = buildA, net = metered))
        )
    }

    @Test
    fun `a manifest with no build id is never approved by the empty default`() {
        assertEquals(OtaConsent.Hold.NOT_APPROVED, heldFor(gate("", approved = "", net = unmetered)))
        assertEquals(OtaConsent.Hold.NOT_APPROVED, heldFor(gate("", approved = "  ", net = unmetered)))
    }

    @Test
    fun `a manifest with no build id is refused even when something else is approved`() {
        assertEquals(
            OtaConsent.Hold.NOT_APPROVED,
            heldFor(gate("", approved = buildA, meteredApproved = buildA, net = unmetered))
        )
        assertEquals(
            OtaConsent.Hold.NOT_APPROVED,
            heldFor(gate("   ", approved = buildA, meteredApproved = buildA, net = metered))
        )
    }

    @Test
    fun `an approval given on wifi does not spend mobile data`() {
        assertEquals(
            OtaConsent.Hold.METERED_NOT_APPROVED,
            heldFor(gate(buildA, approved = buildA, meteredApproved = "", net = metered))
        )
    }

    @Test
    fun `an explicit yes to mobile data for this build proceeds`() {
        assertEquals(
            OtaConsent.Gate.Go,
            gate(buildA, approved = buildA, meteredApproved = buildA, net = metered)
        )
    }

    @Test
    fun `with no network there is nothing to download over`() {
        assertEquals(
            OtaConsent.Hold.NO_NETWORK,
            heldFor(gate(buildA, approved = buildA, meteredApproved = buildA, net = offline))
        )
    }

    @Test
    fun `an unapproved build on cellular reports the missing approval, not the metering`() {
        assertEquals(
            OtaConsent.Hold.NOT_APPROVED,
            heldFor(gate(buildA, approved = "", meteredApproved = "", net = metered))
        )
        assertEquals(
            OtaConsent.Hold.NOT_APPROVED,
            heldFor(gate(buildA, approved = "", meteredApproved = "", net = offline))
        )
    }

    @Test
    fun `an approval is written to disk, not held in memory`() {
        OtaConsent.approve(ctx(), buildA, allowMetered = true)
        assertEquals(buildA, OtaState.approvedBuild(ctx()))
        assertEquals(buildA, OtaState.meteredApprovedBuild(ctx()))

        val xml = prefsFileContaining("approved_build")
        assertTrue(
            "the approval is not in the preferences file, so it does not survive a process " +
                "restart — and the alarm that acts on it fires hours later, in a process the OS " +
                "may well have killed since: $xml",
            xml.contains(buildA)
        )
        assertTrue("the metered answer is not persisted either: $xml",
            xml.contains("approved_metered_build"))
    }

    @Test
    fun `declining mobile data removes an earlier consent to it`() {
        OtaState.approveBuild(ctx(), buildA, allowMetered = true)
        assertEquals(buildA, OtaState.meteredApprovedBuild(ctx()))

        OtaState.approveBuild(ctx(), buildA, allowMetered = false)
        assertEquals("", OtaState.meteredApprovedBuild(ctx()))
        assertEquals("the ordinary approval should be untouched", buildA, OtaState.approvedBuild(ctx()))
        assertEquals(
            OtaConsent.Hold.METERED_NOT_APPROVED,
            heldFor(gate(buildA, OtaState.approvedBuild(ctx()), OtaState.meteredApprovedBuild(ctx()), metered))
        )
    }

    @Test
    fun `a blank build id cannot be approved`() {
        OtaState.approveBuild(ctx(), "   ", allowMetered = true)
        assertEquals("", OtaState.approvedBuild(ctx()))
        assertEquals(OtaConsent.Hold.NOT_APPROVED, heldFor(gate("", OtaState.approvedBuild(ctx()))))
    }

    @Test
    fun `a blank build id does not wipe a standing approval`() {
        OtaState.approveBuild(ctx(), buildA, allowMetered = true)
        OtaState.approveBuild(ctx(), "   ", allowMetered = false)

        assertEquals(
            "a blank build id overwrote a real approval, so the update the user agreed to " +
                "silently stops happening",
            buildA, OtaState.approvedBuild(ctx())
        )
        assertEquals(buildA, OtaState.meteredApprovedBuild(ctx()))
    }

    @Test
    fun `a negative payload size is stored as unknown rather than as a negative number`() {
        OtaState.recordOffer(ctx(), buildA, -1L)
        assertEquals(0L, OtaState.offeredBytes(ctx()))
        assertEquals(0L, OtaConsent.pendingOffer(ctx())!!.payloadBytes)
        assertEquals("unknown size", OtaConsent.formatBytes(OtaState.offeredBytes(ctx())))
    }

    @Test
    fun `clearing forgets both answers`() {
        OtaState.approveBuild(ctx(), buildA, allowMetered = true)
        OtaState.clearApprovals(ctx())
        assertEquals("", OtaState.approvedBuild(ctx()))
        assertEquals("", OtaState.meteredApprovedBuild(ctx()))
    }

    @Test
    fun `a pending offer carries the build and what it costs`() {
        OtaState.recordOffer(ctx(), buildA, OtaFixtures.PAYLOAD_SIZE)
        val offer = OtaConsent.pendingOffer(ctx())
        assertNotNull("nothing to ask the user about", offer)
        assertEquals(buildA, offer!!.build)
        assertEquals(OtaFixtures.PAYLOAD_SIZE, offer.payloadBytes)
        assertTrue("an unanswered offer must not read as approved", !offer.approved)
        assertTrue(!offer.meteredApproved)

        OtaConsent.approve(ctx(), buildA, allowMetered = false)
        val answered = OtaConsent.pendingOffer(ctx())!!
        assertTrue(answered.approved)
        assertTrue(!answered.meteredApproved)
    }

    @Test
    fun `nothing is offered before a poll has found anything`() {
        assertNull(OtaConsent.pendingOffer(ctx()))
    }

    @Test
    fun `a staged build is no longer an offer`() {
        OtaState.recordOffer(ctx(), buildA, OtaFixtures.PAYLOAD_SIZE)
        OtaState.setReadyBuild(ctx(), buildA)
        assertNull(OtaConsent.pendingOffer(ctx()))
    }

    @Test
    fun `the size is quoted the way a carrier bills, in decimal GB`() {
        assertEquals("1.7 GB", OtaConsent.formatBytes(OtaFixtures.PAYLOAD_SIZE))
        assertEquals("2.1 GB", OtaConsent.formatBytes(2_100_000_000L))
        assertEquals("400 MB", OtaConsent.formatBytes(400_000_000L))
        assertEquals("unknown size", OtaConsent.formatBytes(0L))
    }

    @Test
    fun `startApprovedDownload refuses a build nobody approved`() {
        activeNetwork(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        )
        val start = OtaConsent.startApprovedDownload(ctx(), buildA)
        assertEquals(
            OtaConsent.Start.Refused(OtaConsent.Hold.NOT_APPROVED), start
        )
    }

    @Test
    fun `startApprovedDownload refuses to spend mobile data nobody agreed to`() {
        activeNetwork(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        OtaConsent.approve(ctx(), buildA, allowMetered = false)
        assertEquals(
            OtaConsent.Start.Refused(OtaConsent.Hold.METERED_NOT_APPROVED),
            OtaConsent.startApprovedDownload(ctx(), buildA)
        )
    }

    @Test
    fun `startApprovedDownload schedules a fresh poll once approved on wifi`() {
        activeNetwork(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        )
        OtaConsent.approve(ctx(), buildA, allowMetered = false)
        clearAlarms()

        assertEquals(OtaConsent.Start.Scheduled, OtaConsent.startApprovedDownload(ctx(), buildA))

        val alarm = shadowOf(alarms()).peekNextScheduledAlarm()
        assertNotNull(
            "nothing was scheduled. The UI says 'Update starting' and no poll has been armed.",
            alarm
        )
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm!!.type)
        val delayMs = alarm.triggerAtTime - SystemClock.elapsedRealtime()
        assertTrue(
            "the approval armed the ordinary six-hour poll rather than a check now (${delayMs}ms)",
            delayMs <= 60_000L
        )
    }

    @Test
    fun `a refused start schedules nothing`() {
        activeNetwork(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        OtaConsent.approve(ctx(), buildA, allowMetered = false)
        clearAlarms()

        assertEquals(
            OtaConsent.Start.Refused(OtaConsent.Hold.METERED_NOT_APPROVED),
            OtaConsent.startApprovedDownload(ctx(), buildA)
        )
        assertNull("a refused start armed a poll anyway",
            shadowOf(alarms()).peekNextScheduledAlarm())
    }

    @Test
    fun `approving takes the question out of the notification shade`() {
        postLegacyOfferNotification()
        assertNotNull("fixture: the offer should be posted before it is answered",
            shadowOf(notifications()).getNotification(OFFER_NOTIF_ID))

        OtaConsent.approve(ctx(), buildA, allowMetered = false)

        assertNull(
            "the offer notification survived the answer, so the shade still asks a question the " +
                "user has already answered",
            shadowOf(notifications()).getNotification(OFFER_NOTIF_ID)
        )
        assertNull(
            "the abandoned channel is still registered, so system Settings lists a toggle for a " +
                "notification nothing can post",
            notifications().getNotificationChannel(OFFER_CHANNEL_ID)
        )
    }

    private fun postLegacyOfferNotification() {
        val nm = notifications()
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                OFFER_CHANNEL_ID, "System update available",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.notify(
            OFFER_NOTIF_ID,
            android.app.Notification.Builder(ctx(), OFFER_CHANNEL_ID)
                .setContentTitle("System update available")
                .setContentText("Uses about 1.7 GB. Tap to review.")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .build()
        )
    }

    @Test
    fun `startApprovedDownload will not start a build the approval was not for`() {
        activeNetwork(
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        )
        OtaConsent.approve(ctx(), buildA, allowMetered = false)
        assertEquals(
            OtaConsent.Start.Refused(OtaConsent.Hold.APPROVED_OTHER_BUILD),
            OtaConsent.startApprovedDownload(ctx(), buildB)
        )
    }

    private val OFFER_NOTIF_ID = 1005

    private val OFFER_CHANNEL_ID = "rist_ota_offer"

    private fun alarms(): AlarmManager =
        ctx().getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun notifications(): NotificationManager =
        ctx().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun clearAlarms() {
        while (shadowOf(alarms()).peekNextScheduledAlarm() != null) {
            shadowOf(alarms()).getNextScheduledAlarm()
        }
    }

    private fun activeNetwork(vararg capabilities: Int) {
        val cm = ctx().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        for (c in capabilities) shadowOf(caps).addCapability(c)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    private fun prefsFileContaining(marker: String): String {
        val f = File(File(ctx().applicationInfo.dataDir, "shared_prefs"), "rist.ota.xml")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val text = if (f.isFile) f.readText() else ""
            if (text.contains(marker)) return text
            Thread.sleep(25)
        }
        return if (f.isFile) f.readText() else "<no rist.ota.xml on disk at all>"
    }
}
