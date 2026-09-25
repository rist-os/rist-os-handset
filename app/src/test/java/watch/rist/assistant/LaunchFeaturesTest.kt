package watch.rist.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import rist.v1.DeviceResponse
import rist.v1.FeatureSet
import rist.v1.FeatureState
import rist.v1.Speech
import rist.v1.WakeSignal

/**
 * Customers launch with the basics only. The backend says which features an account has
 * (pending field: DeviceResponse.features / WakeSignal.features); the phone hides the rest.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchFeaturesTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun set(vararg pairs: Pair<String, String>): FeatureSet =
        FeatureSet.newBuilder().addAllFeatures(pairs.map {
            FeatureState.newBuilder().setFeature(it.first).setState(it.second).build()
        }).build()

    private val launchSet get() = set(
        "email" to "unavailable", "texting" to "unavailable", "contacts" to "unavailable",
        "calendar" to "unavailable", "maps" to "unavailable", "media" to "unavailable",
        "sound_id" to "unavailable", "video_calls" to "unavailable",
        "call_screening" to "unavailable", "voicemail" to "unavailable",
    )

    @Before
    fun clean() {
        Config.setFeatures(ctx, "")
        Config.setContactsSyncOff(ctx, false)
        Config.setEnrolRevoked(ctx, false)
        Config.setCredentialRejected(ctx, false)
    }

    @After
    fun tidy() = clean()

    @Test
    fun `with no signal from the backend every feature stays as it is today`() {
        for (id in Features.Id.values()) assertTrue(id.wire, Features.isOn(ctx, id))
        assertTrue(ContactsSync.allowed(ctx))
    }

    @Test
    fun `the launch set hides every unlaunched feature`() {
        assertTrue(Features.apply(ctx, launchSet))
        for (id in Features.Id.values()) assertFalse(id.wire, Features.isOn(ctx, id))
        assertFalse("applying the same set again is not a change", Features.apply(ctx, launchSet))
    }

    @Test
    fun `the owner's set keeps everything`() {
        Features.apply(ctx, set(*Features.Id.values().map { it.wire to "on" }.toTypedArray()))
        for (id in Features.Id.values()) assertTrue(id.wire, Features.isOn(ctx, id))
    }

    @Test
    fun `once a set has arrived, a missing feature or an unknown state counts as not there`() {
        Features.apply(ctx, set("email" to "on", "maps" to "beta"))
        assertTrue(Features.isOn(ctx, Features.Id.EMAIL))
        assertEquals(Features.State.UNAVAILABLE, Features.state(ctx, Features.Id.MAPS))
        assertEquals(Features.State.UNAVAILABLE, Features.state(ctx, Features.Id.MEDIA))
    }

    @Test
    fun `a switch the customer turned off is off, not unavailable`() {
        Features.apply(ctx, set("email" to "off"))
        assertEquals(Features.State.OFF, Features.state(ctx, Features.Id.EMAIL))
        assertFalse(Features.isOn(ctx, Features.Id.EMAIL))
    }

    @Test
    fun `no video calls on the account means the phone does not offer to open one`() {
        assertTrue(DeviceProfile.capabilities(ctx).componentsList.contains(VideoCalls.COMPONENT))
        Features.apply(ctx, launchSet)
        val caps = DeviceProfile.capabilities(ctx)
        assertFalse(caps.componentsList.contains(VideoCalls.COMPONENT))
        assertEquals(DeviceProfile.RCS_SCHEMA_VERSION, caps.schemaVersion)
    }

    @Test
    fun `unread mail is not counted on an account without email`() {
        Config.setMailAcknowledged(ctx, 0)
        Config.setMailUnread(ctx, 3)
        assertEquals(3, CommsFeedView.pendingMail(ctx))
        Features.apply(ctx, launchSet)
        assertEquals(0, CommsFeedView.pendingMail(ctx))
        Config.setMailUnread(ctx, 0)
    }

    @Test
    fun `contacts off stops syncing and keeps the address book`() {
        Features.apply(ctx, launchSet)
        assertFalse(ContactsSync.allowed(ctx))
        Features.apply(ctx, set("contacts" to "on"))
        assertTrue(ContactsSync.allowed(ctx))
    }

    @Test
    fun `the contacts off answer is never a revocation`() {
        ContactsSync.onRefused(ctx, ContactsSync.FEATURE_OFF_STATUS)
        assertFalse("contacts off must not be read as a removed phone", Config.enrolRevoked(ctx))
        assertFalse(Config.credentialRejected(ctx))
        assertFalse(ContactsSync.allowed(ctx))
        assertEquals(ContactsSync.Refusal.REVOKED, ContactsSync.classify(403))
        assertEquals(ContactsSync.Refusal.FEATURE_OFF, ContactsSync.classify(409))
        assertNull(ContactsSync.classify(200))
    }

    @Test
    fun `a wake signal carrying a set is taken in`() {
        WakeLoop.apply(ctx, WakeSignal.newBuilder().setFeatures(launchSet).build(), emptyList())
        assertFalse(Features.isOn(ctx, Features.Id.MEDIA))
    }

    @Test
    fun `a wake signal with no set leaves what the phone holds`() {
        Features.apply(ctx, set("media" to "on"))
        WakeLoop.apply(ctx, WakeSignal.newBuilder().build(), emptyList())
        assertTrue(Features.isOn(ctx, Features.Id.MEDIA))
    }

    @Test
    fun `a turn reply carrying a set is taken in`() {
        val server = MockWebServer()
        server.start()
        try {
            Config.setDeployDefaultsForTest("", "")
            Config.setBackendEndpoint(ctx, server.url("/v1/device").toString())
            StreamingCancel.resetForTest()
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(
                DeviceResponse.newBuilder().setIsFinal(true)
                    .setSpeech(Speech.newBuilder().setText("ok"))
                    .setFeatures(launchSet).build().toByteArray()
            )))
            Uploader(ctx).sendText("hello")
            assertFalse(Features.isOn(ctx, Features.Id.VIDEO_CALLS))
        } finally {
            Config.clearDeployDefaultsForTest()
            runCatching { server.shutdown() }
        }
    }
}
