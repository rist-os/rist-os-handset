package watch.rist.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsApplyRefusalTest {

    @Test
    fun deviceOwnedSettingsAreNotRefused() {
        assertFalse(SettingsApply.isBackendOwned("alarms.volume"))
        assertFalse(SettingsApply.isBackendOwned("assistant.voice_playback"))
    }

    @Test
    fun unknownKeysAreNotBackendOwned() {
        assertFalse(SettingsApply.isBackendOwned("something.new"))
        assertFalse(SettingsApply.isBackendOwned(""))
    }
}
