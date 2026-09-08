package watch.rist.assistant

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretFilteringPrefsTest {

    private class Fake(val map: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(k: String?, v: String?) = map[k] as? String ?: v
        override fun getStringSet(k: String?, v: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST") (map[k] as? MutableSet<String> ?: v)
        override fun getInt(k: String?, v: Int) = map[k] as? Int ?: v
        override fun getLong(k: String?, v: Long) = map[k] as? Long ?: v
        override fun getFloat(k: String?, v: Float) = map[k] as? Float ?: v
        override fun getBoolean(k: String?, v: Boolean) = map[k] as? Boolean ?: v
        override fun contains(k: String?) = map.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = Ed(map)

        private class Ed(private val m: MutableMap<String, Any?>) : SharedPreferences.Editor {
            override fun putString(k: String?, v: String?) = apply { m[k!!] = v }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { m[k!!] = v }
            override fun putInt(k: String?, v: Int) = apply { m[k!!] = v }
            override fun putLong(k: String?, v: Long) = apply { m[k!!] = v }
            override fun putFloat(k: String?, v: Float) = apply { m[k!!] = v }
            override fun putBoolean(k: String?, v: Boolean) = apply { m[k!!] = v }
            override fun remove(k: String?) = apply { m.remove(k) }
            override fun clear() = apply { m.clear() }
            override fun commit() = true
            override fun apply() = Unit
        }
    }

    private fun wrap(f: Fake) = Config.SecretFilteringPrefs(f)

    @Test
    fun theAuthTokenIsNeverWrittenToPlaintext() {
        val f = Fake()
        wrap(f).edit().putString("auth_token", "secret-bearer").apply()
        assertFalse("the token reached the plaintext store", f.map.containsKey("auth_token"))
    }

    @Test
    fun everySecretKeyIsRefused() {
        for (k in Config.SECRET_KEYS) {
            val f = Fake()
            wrap(f).edit().putString(k, "x").apply()
            assertFalse("secret '$k' was persisted in plaintext", f.map.containsKey(k))
        }
    }

    @Test
    fun aSecretLeftOnDiskByAnEarlierBuildIsRemoved() {
        val f = Fake(mutableMapOf("auth_token" to "left-behind"))
        wrap(f).edit().putString("auth_token", "new").apply()
        assertFalse(f.map.containsKey("auth_token"))
    }

    @Test
    fun secretsReadBackAsAbsentSoTheDeviceLooksUnenrolled() {
        val f = Fake(mutableMapOf("auth_token" to "left-behind"))
        val p = wrap(f)
        assertNull(p.getString("auth_token", null))
        assertFalse(p.contains("auth_token"))
        assertFalse("getAll must not leak it either", p.all.containsKey("auth_token"))
    }

    @Test
    fun nonSecretSettingsStillWorkNormally() {
        val f = Fake()
        wrap(f).edit().putString("theme_id", "night").putInt("screen_brightness", 40).apply()
        assertEquals("night", wrap(f).getString("theme_id", null))
        assertEquals(40, wrap(f).getInt("screen_brightness", 0))
        assertTrue(wrap(f).contains("theme_id"))
    }
}
