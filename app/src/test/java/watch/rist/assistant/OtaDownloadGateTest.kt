package watch.rist.assistant

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class OtaDownloadGateTest {

    private val applyNotificationId = 1004
    private val offerNotificationId = 1005

    private val offerChannelId = "rist_ota_offer"

    private val build = OtaFixtures.BUILD
    private val otherBuild = "2026080100"

    private var server: MockWebServer? = null

    private fun app(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        OtaState.clearOffer(app())
        OtaState.clearApprovals(app())
        wifi()
    }

    @After
    fun tearDown() {
        runCatching { server?.shutdown() }
        server = null
    }

    @Test
    fun `an unapproved build is never handed to update_engine, even on free wifi`() {
        val s = packageServer()
        val next = OtaScheduler.offer(app(), routedTo(s), manifest(), NOW, null)

        assertNull(
            "OtaScheduler handed an UNAPPROVED build to OtaService. Nobody was asked, and " +
                "update_engine is now streaming 1.6 GB — the exact shipped behaviour the consent " +
                "gate was added to end.",
            shadowOf(app() as android.app.Application).nextStartedService
        )
        assertEquals(
            "a held offer must not even probe the package URL: the gate is in front of the " +
                "preflight so an unapproved download costs the user zero bytes",
            0, s.requestCount
        )
        assertEquals("a held offer re-polls on the ordinary interval",
            OtaScheduler.POLL_INTERVAL_SECONDS, next)
    }

    @Test
    fun `a held build is recorded and offered to the user`() {
        OtaScheduler.offer(app(), routedTo(packageServer()), manifest(), NOW, null)

        assertEquals("the held build must be stored for the UI to ask about",
            build, OtaState.offeredBuild(app()))
        assertEquals(
            "the byte count must be stored too: 'an update is available' is not a question, " +
                "'this uses about 1.7 GB' is",
            OtaFixtures.PAYLOAD_SIZE, OtaState.offeredBytes(app())
        )
    }

    @Test
    fun `a held build posts no notification to nag with`() {
        OtaScheduler.offer(app(), routedTo(packageServer()), manifest(), NOW, null)

        assertNull(
            "the poll posted an update offer into the shade. Nobody on a locked handset can see " +
                "it, and everybody who dismisses one gets it again six hours later.",
            shadowOf(notifications()).getNotification(offerNotificationId)
        )
    }

    @Test
    fun `a legacy offer notification is taken down by the next hold`() {
        postLegacyOfferNotification()
        assertNotNull("fixture: the legacy notification should be up",
            shadowOf(notifications()).getNotification(offerNotificationId))

        OtaScheduler.offer(app(), routedTo(packageServer()), manifest(), NOW, null)

        assertNull(
            "an offer notification from an older RistOS is still standing in a shade this device " +
                "never shows, and nothing will ever take it down",
            shadowOf(notifications()).getNotification(offerNotificationId)
        )
        assertNull(
            "the abandoned channel is still registered, so system Settings still lists a toggle " +
                "for a notification that can no longer be posted",
            notifications().getNotificationChannel(offerChannelId)
        )
    }

    private fun postLegacyOfferNotification() {
        val nm = notifications()
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                offerChannelId, "System update available",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.notify(
            offerNotificationId,
            android.app.Notification.Builder(app(), offerChannelId)
                .setContentTitle("System update available")
                .setContentText("Uses about 1.7 GB. Tap to review.")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .build()
        )
    }

    @Test
    fun `an approval for another build does not hand this one over`() {
        OtaState.approveBuild(app(), otherBuild, allowMetered = true)
        val s = packageServer()
        OtaScheduler.offer(app(), routedTo(s), manifest(), NOW, null)

        assertNull(
            "a stored approval for a DIFFERENT build opened the gate. That is the boolean " +
                "'always update' flag OtaState.approvedBuild exists to not be.",
            shadowOf(app() as android.app.Application).nextStartedService
        )
        assertEquals(0, s.requestCount)
    }

    @Test
    fun `an approval given on wifi does not spend mobile data at the hand-off`() {
        OtaState.approveBuild(app(), build, allowMetered = false)
        cellular()
        val s = packageServer()
        OtaScheduler.offer(app(), routedTo(s), manifest(), NOW, null)

        assertNull(
            "a Wi-Fi approval was spent on cellular. The suitability must be read at the " +
                "hand-off, never taken from the approval.",
            shadowOf(app() as android.app.Application).nextStartedService
        )
        assertEquals(0, s.requestCount)
    }

    @Test
    fun `with no network there is nothing to hand over`() {
        OtaState.approveBuild(app(), build, allowMetered = true)
        offline()
        OtaScheduler.offer(app(), routedTo(packageServer()), manifest(), NOW, null)

        assertNull(shadowOf(app() as android.app.Application).nextStartedService)
    }

    @Test
    fun `an approved build on wifi is handed to the service with its payload`() {
        OtaState.approveBuild(app(), build, allowMetered = false)
        val s = packageServer()
        val next = OtaScheduler.offer(app(), routedTo(s), manifest(), NOW, null)

        val started = shadowOf(app() as android.app.Application).nextStartedService
        assertNotNull(
            "an APPROVED build on unmetered Wi-Fi did not reach OtaService. The gate now refuses " +
                "everything, which is a handset that silently never takes a security patch.",
            started
        )
        assertEquals(OtaService::class.java.name, started!!.component?.className)
        assertEquals(build, started.getStringExtra(OtaService.EXTRA_BUILD))
        assertEquals(OtaFixtures.URL, started.getStringExtra("url"))
        assertEquals(OtaFixtures.PAYLOAD_OFFSET, started.getLongExtra("offset", -1L))
        assertEquals(OtaFixtures.PAYLOAD_SIZE, started.getLongExtra("size", -1L))
        assertEquals("the URL was probed before the hand-off", 2, s.requestCount)
        assertEquals(OtaScheduler.POLL_INTERVAL_SECONDS, next)
    }

    @Test
    fun `an explicit yes to mobile data lets this build through on cellular`() {
        OtaState.approveBuild(app(), build, allowMetered = true)
        cellular()
        OtaScheduler.offer(app(), routedTo(packageServer()), manifest(), NOW, null)

        assertNotNull(
            "the user said yes to spending mobile data on this exact build and the download " +
                "still did not start",
            shadowOf(app() as android.app.Application).nextStartedService
        )
    }

    @Test
    fun `handing off clears the offer notification`() {
        postLegacyOfferNotification()
        assertNotNull("fixture: the notification should be up before the hand-off",
            shadowOf(notifications()).getNotification(offerNotificationId))

        OtaState.approveBuild(app(), build, allowMetered = false)
        OtaScheduler.offer(app(), routedTo(packageServer()), manifest(), NOW, null)

        assertNull(
            "the offer notification survived the hand-off, so the shade now shows a question " +
                "about a download that has already started",
            shadowOf(notifications()).getNotification(offerNotificationId)
        )
    }

    @Test
    fun `clearing the offer notification does not touch the apply notification`() {
        postLegacyOfferNotification()
        notifications().notify(
            applyNotificationId,
            android.app.Notification.Builder(app(), offerChannelId)
                .setContentTitle("System update")
                .setContentText("Downloading update (12%)")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .build()
        )

        OtaScheduler.offer(app(), routedTo(packageServer()), manifest(), NOW, null)

        assertNull("the legacy offer notification was not cleared",
            shadowOf(notifications()).getNotification(offerNotificationId))
        assertNotNull(
            "the cleanup cancelled OtaService's apply notification (1004) as well, so a download " +
                "in flight lost the foreground notification that keeps it alive",
            shadowOf(notifications()).getNotification(applyNotificationId)
        )
    }

    @Test
    fun `a successful install spends the approval`() {
        OtaState.recordOffer(app(), build, OtaFixtures.PAYLOAD_SIZE)
        OtaState.approveBuild(app(), build, allowMetered = true)
        postLegacyOfferNotification()

        result(build, OtaService.VERDICT_APPLIED)

        assertEquals("the build is staged and waiting for a restart", build, OtaState.readyBuild(app()))
        assertEquals(
            "the approval outlived the download it authorised. That is a standing yes to every " +
                "future release, from one tap.",
            "", OtaState.approvedBuild(app())
        )
        assertEquals("", OtaState.meteredApprovedBuild(app()))
        assertEquals("", OtaState.offeredBuild(app()))
        assertNull(shadowOf(notifications()).getNotification(offerNotificationId))
    }

    @Test
    fun `a retryable failure keeps the approval so the user is not asked again`() {
        OtaState.recordOffer(app(), build, OtaFixtures.PAYLOAD_SIZE)
        OtaState.approveBuild(app(), build, allowMetered = true)

        result(build, OtaService.VERDICT_RETRY, "connection reset")

        assertEquals("a dropped connection is not a withdrawn consent",
            build, OtaState.approvedBuild(app()))
        assertEquals(build, OtaState.meteredApprovedBuild(app()))
        assertEquals("the offer is still on the table", build, OtaState.offeredBuild(app()))
        assertEquals("a retry must not mark the build permanently refused",
            "", OtaState.refusedBuild(app()))
    }

    @Test
    fun `an unappliable payload spends the approval without refusing the build forever`() {
        for (verdict in listOf(OtaService.VERDICT_NEEDS_USER, OtaService.VERDICT_CORRUPTED)) {
            OtaState.recordOffer(app(), build, OtaFixtures.PAYLOAD_SIZE)
            OtaState.approveBuild(app(), build, allowMetered = true)

            result(build, verdict, "no space")

            assertEquals(
                "$verdict left the approval standing, so the next alarm re-downloads gigabytes " +
                    "over cellular on a consent that has already failed once",
                "", OtaState.approvedBuild(app())
            )
            assertEquals("", OtaState.meteredApprovedBuild(app()))
            assertEquals("", OtaState.offeredBuild(app()))
            assertEquals(
                "$verdict is not permanent: the build must stay offerable once the user has " +
                    "cleared space or the slot has been repaired",
                "", OtaState.refusedBuild(app())
            )
        }
    }

    @Test
    fun `a permanent failure spends the approval and refuses the build`() {
        OtaState.recordOffer(app(), build, OtaFixtures.PAYLOAD_SIZE)
        OtaState.approveBuild(app(), build, allowMetered = true)

        result(build, OtaService.VERDICT_PERMANENT, "payload mismatch")

        assertEquals(build, OtaState.refusedBuild(app()))
        assertEquals("keeping the approval would be a stored yes to a build that can only fail",
            "", OtaState.approvedBuild(app()))
        assertEquals("", OtaState.offeredBuild(app()))
    }

    @Test
    fun `the service still reads neither the store nor the consent`() {
        val bytes = classBytes("OtaService")
        assertTrue(
            "OtaService now references OtaState. SharedPreferences is not multi-process safe and " +
                "this class runs in :ota; whatever it reads there is a guess.",
            !bytes.references("watch/rist/assistant/OtaState")
        )
        assertTrue(
            "OtaService now references OtaConsent, which reads OtaState — same problem, one hop " +
                "further away and harder to see.",
            !bytes.references("watch/rist/assistant/OtaConsent")
        )
    }

    private val NOW = OtaFixtures.TIMESTAMP + 3600

    private fun manifest(): OtaManifest = OtaFixtures.manifest()

    private fun notifications(): NotificationManager =
        app().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun result(build: String, verdict: String, detail: String = "") {
        OtaResultReceiver().onReceive(
            app(),
            Intent(OtaService.ACTION_RESULT)
                .putExtra(OtaService.EXTRA_BUILD, build)
                .putExtra(OtaService.EXTRA_VERDICT, verdict)
                .putExtra(OtaService.EXTRA_DETAIL, detail)
        )
    }

    private fun packageServer(): MockWebServer {
        val cr = "bytes ${OtaFixtures.PAYLOAD_OFFSET}-${OtaFixtures.PAYLOAD_OFFSET + 3}/" +
            "${OtaFixtures.ZIP_SIZE}"
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val r = MockResponse().setResponseCode(206).setHeader("Content-Range", cr)
                return if (request.method == "HEAD") r else r.setBody(OtaRange.PAYLOAD_MAGIC)
            }
        }
        s.start()
        server = s
        return s
    }

    private fun routedTo(s: MockWebServer): OkHttpClient =
        OtaCheck.defaultClient().newBuilder().addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(
                req.newBuilder()
                    .url(req.url.newBuilder().scheme("http").host(s.hostName).port(s.port).build())
                    .build()
            )
        }.build()

    private fun wifi() = activeNetwork(
        NetworkCapabilities.NET_CAPABILITY_INTERNET,
        NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
    )

    private fun cellular() = activeNetwork(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    private fun offline() {
        val cm = app().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, null)
    }

    private fun activeNetwork(vararg capabilities: Int) {
        val cm = app().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        for (c in capabilities) shadowOf(caps).addCapability(c)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    private fun ByteArray.references(marker: String): Boolean =
        String(this, Charsets.ISO_8859_1).contains(marker)

    private fun classBytes(simpleName: String): ByteArray {
        val entry = "watch/rist/assistant/$simpleName.class"
        val url = OtaState::class.java.getResource("OtaState.class")
        assertNotNull("cannot locate the compiled classes to scan", url)
        val bytes: ByteArray? = if (url!!.protocol == "jar") {
            val jarPath = java.net.URLDecoder.decode(
                url.path.substringAfter("file:").substringBefore("!"), "UTF-8"
            )
            java.util.jar.JarFile(jarPath).use { jar ->
                jar.getJarEntry(entry)?.let { jar.getInputStream(it).use { s -> s.readBytes() } }
            }
        } else {
            File(File(url.toURI()).parentFile, "$simpleName.class").takeIf { it.isFile }?.readBytes()
        }
        assertNotNull("$entry is not on the test classpath — this scan would prove nothing", bytes)
        assertTrue("$entry is empty", bytes!!.isNotEmpty())
        assertEquals("$entry is not a class file", 0xCA.toByte(), bytes[0])
        return bytes
    }
}
