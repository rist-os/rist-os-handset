package watch.rist.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import rist.v1.SettingsCommand
import rist.v1.SettingsValue
import rist.v1.SettingsWrite

/** Settings changed from the backend: versions, the wipe restore, consents and what stays local. */
@RunWith(RobolectricTestRunner::class)
class SettingsRemoteTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        DesignSync.resetForTest(ctx)
        Config.setSettingsState(ctx, "")
        Config.setReplyVoiceEnabled(ctx, true)
        Config.setContactsSyncOff(ctx, false)
    }

    @After
    fun tidy() = clean()

    private fun cmd(id: String, vararg writes: Pair<String, String>, version: Long = 0, full: Boolean = false) =
        SettingsCommand.newBuilder().setCommandId(id).setVersion(version).setFull(full)
            .addAllWrites(writes.map { SettingsWrite.newBuilder().setKey(it.first).setValue(it.second).build() })
            .build()

    private fun answers(): Map<String, SettingsValue> = SettingsApply.pending(ctx).second.associateBy { it.key }

    @Test
    fun `after a wipe the full resend is applied and its version kept, but consents wait for the user`() {
        DesignSync.shippedForTest = true
        assertEquals(0L, Config.settingsVersion(ctx))
        SettingsApply.handle(ctx, cmd("c1",
            "assistant.voice_playback" to "off", "alarms.volume" to "40",
            "assistant.message_history" to Retention.CHOICES.last().id,
            "assistant.voice_level" to "7", "consent.contacts_sync" to "on",
            version = 9, full = true))
        val a = answers()
        assertEquals(SettingsValue.Outcome.APPLIED, a.getValue("assistant.voice_playback").outcome)
        assertEquals(SettingsValue.Outcome.APPLIED, a.getValue("alarms.volume").outcome)
        assertEquals(SettingsValue.Outcome.APPLIED, a.getValue("assistant.voice_level").outcome)
        assertEquals(SettingsValue.Outcome.REFUSED, a.getValue("consent.contacts_sync").outcome)
        assertFalse(Config.isReplyVoiceEnabled(ctx))
        assertEquals(40, Config.alarmVolumePercent(ctx))
        assertEquals(7, Config.voiceLevel(ctx))
        assertEquals(9L, Config.settingsVersion(ctx))
        // An older version never moves it back, and only a full command sets it.
        SettingsApply.handle(ctx, cmd("c2", "alarms.volume" to "50", version = 3, full = true))
        assertEquals(9L, Config.settingsVersion(ctx))
        SettingsApply.handle(ctx, cmd("c3", "alarms.volume" to "50", version = 12))
        assertEquals(9L, Config.settingsVersion(ctx))
        Config.setVoiceLevel(ctx, Config.VOICE_LEVEL_MAX)
        Config.setAlarmVolumePercent(ctx, 100)
    }

    @Test
    fun `a consent can be turned on and off when the user asks`() {
        DesignSync.shippedForTest = true
        SettingsApply.handle(ctx, cmd("c1", "consent.contacts_sync" to "off"))
        assertTrue(Config.contactsSyncOff(ctx))
        assertEquals("off", answers().getValue("consent.contacts_sync").value)
        SettingsApply.clear(ctx)
        SettingsApply.handle(ctx, cmd("c2", "consent.contacts_sync" to "on"))
        assertFalse(Config.contactsSyncOff(ctx))
        val on = answers().getValue("consent.contacts_sync")
        assertEquals(SettingsValue.Outcome.APPLIED, on.outcome)
        assertEquals("on", on.value)
        SettingsApply.clear(ctx)
        SettingsApply.handle(ctx, cmd("c3", "consent.place_triggers" to "on", "consent.network_location" to "maybe"))
        // The answer is the phone's own: applied, or refused when Android would not take it.
        assertNotEquals(SettingsValue.Outcome.UNKNOWN_KEY, answers().getValue("consent.place_triggers").outcome)
        assertEquals(SettingsValue.Outcome.INVALID_VALUE, answers().getValue("consent.network_location").outcome)
    }

    @Test
    fun `pairing, secrets, updates, emergency and Android settings are refused with a reason`() {
        DesignSync.shippedForTest = true
        val local = listOf("device.backend_url", "device.pairing", "device.auth_token", "voicemail.pin",
            "ota.auto_install", "device.updates", "device.kiosk", "emergency.number", "android.wifi",
            "system.screen_timeout", "comms.mode")
        SettingsApply.handle(ctx, cmd("c1", *local.map { it to "x" }.toTypedArray()))
        val a = answers()
        for (k in local) {
            assertEquals(k, SettingsValue.Outcome.REFUSED, a.getValue(k).outcome)
            assertTrue(k, a.getValue(k).detail.isNotBlank())
        }
        SettingsApply.clear(ctx)
        SettingsApply.handle(ctx, cmd("c2", "units.measurement" to "metric"))
        assertEquals(SettingsValue.Outcome.UNKNOWN_KEY, answers().getValue("units.measurement").outcome)
    }

    @Test
    fun `before it ships the new keys are refused as before and no version is kept`() {
        DesignSync.shippedForTest = false
        SettingsApply.handle(ctx, cmd("c1", "consent.contacts_sync" to "off", version = 5, full = true))
        val a = answers().getValue("consent.contacts_sync")
        assertEquals(SettingsValue.Outcome.REFUSED, a.outcome)
        assertEquals("not supported on this device", a.detail)
        assertFalse(Config.contactsSyncOff(ctx))
        assertEquals(0L, Config.settingsVersion(ctx))
        SettingsApply.reportLocal(ctx, "assistant.voice_playback")
        assertEquals(1, SettingsApply.pending(ctx).second.size)
    }

    @Test
    fun `the first run of a design build reports every setting the phone holds, once`() {
        DesignSync.shippedForTest = true
        Config.setReplyVoiceEnabled(ctx, false)
        Config.setAlarmVolumePercent(ctx, 35)
        DesignSync.migrateLegacyTheme(ctx)
        val (cmdId, values) = SettingsApply.pending(ctx)
        assertEquals("", cmdId)
        val byKey = values.associateBy { it.key }
        assertEquals(SettingsApply.SNAPSHOT_KEYS.toSet(), byKey.keys)
        assertTrue(values.all { it.outcome == SettingsValue.Outcome.REPORTED })
        assertEquals("off", byKey.getValue("assistant.voice_playback").value)
        assertEquals("35", byKey.getValue("alarms.volume").value)
        SettingsApply.clear(ctx, cmdId)
        DesignSync.migrateLegacyTheme(ctx)
        assertTrue(SettingsApply.pending(ctx).second.isEmpty())
        Config.setAlarmVolumePercent(ctx, 100)
    }

    @Test
    fun `before it ships no snapshot is reported`() {
        DesignSync.shippedForTest = false
        SettingsApply.reportSnapshot(ctx)
        assertTrue(SettingsApply.pending(ctx).second.isEmpty())
    }

    @Test
    fun `a change made on the phone is reported unsolicited, apart from command answers`() {
        DesignSync.shippedForTest = true
        SettingsApply.handle(ctx, cmd("c1", "alarms.volume" to "60"))
        Config.setReplyVoiceEnabled(ctx, false)
        SettingsApply.reportLocal(ctx, "assistant.voice_playback")
        val (first, firstValues) = SettingsApply.pending(ctx)
        assertEquals("c1", first)
        assertEquals(listOf("alarms.volume"), firstValues.map { it.key })
        SettingsApply.clear(ctx, first)
        val (second, secondValues) = SettingsApply.pending(ctx)
        assertEquals("", second)
        assertEquals(SettingsValue.Outcome.REPORTED, secondValues.single().outcome)
        assertEquals("off", secondValues.single().value)
        Config.setAlarmVolumePercent(ctx, 100)
    }
}
