package watch.rist.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import rist.v1.DesignSpec
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import rist.v1.SettingsCommand
import rist.v1.SettingsValue
import rist.v1.SettingsWrite
import rist.v1.Speech
import rist.v1.WakeSignal
import java.util.concurrent.TimeUnit

/** Looks from the backend: the catalogue, the safety rails, storage, the echo and the wire. */
@RunWith(RobolectricTestRunner::class)
class DesignSyncTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private var server: MockWebServer? = null

    @Before
    fun clean() {
        DesignSync.resetForTest(ctx)
        HomeBoxes.resetForTest(ctx)
        Fonts.forgetForTest()
        Config.setThemeId(ctx, "ledger")
        Config.setSettingsState(ctx, "")
    }

    @After
    fun tidy() {
        DesignSync.awaitFlushForTest()
        DesignSync.resetForTest(ctx)
        HomeBoxes.resetForTest(ctx)
        Config.setThemeId(ctx, "ledger")
        Config.setSettingsState(ctx, "")
        server?.shutdown()
    }

    private fun spec(version: Long, vararg tokens: Pair<String, String>, name: String = ""): DesignSpec =
        DesignSpec.newBuilder().setVersion(version).setBaseTheme("ledger").setCatalogue(1)
            .putAllTokens(tokens.toMap()).setName(name).build()

    private fun resolve(vararg tokens: Pair<String, String>) =
        DesignSync.resolve(spec(1, *tokens)) { Fonts.isAvailable(ctx, it) }

    private fun noteFor(notes: List<SettingsValue>, key: String) = notes.firstOrNull { it.key == key }

    private fun backend(): MockWebServer {
        val s = MockWebServer().also { it.start() }
        server = s
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(ctx, s.url("/v1/device").toString())
        StreamingCancel.resetForTest()
        return s
    }

    private fun protoBody(bytes: ByteArray) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/x-protobuf").setBody(Buffer().write(bytes))

    private fun ok() = protoBody(DeviceResponse.newBuilder().setIsFinal(true)
        .setSpeech(Speech.newBuilder().setText("ok")).build().toByteArray())

    // ---- the catalogue ----

    @Test
    fun `tokens replace what they name and nothing else`() {
        val r = resolve(
            "color.ground" to "#10203A", "color.ink" to "#F2F2F2", "font.body" to "serif",
            "shape.tile_radius" to "4", "style.knob" to "dome", "type.date_style" to "short",
        )
        assertEquals(Color.parseColor("#10203A"), r.theme.ground)
        assertEquals(Color.parseColor("#F2F2F2"), r.theme.ink)
        assertTrue("a dark ground is drawn as dark", r.theme.dark)
        assertEquals("serif", r.theme.font)
        assertEquals("the clock follows the body font when none is named", "serif", r.theme.displayFont)
        assertEquals(4f, r.theme.tileRadiusDp)
        assertEquals("dome", r.theme.knob)
        assertTrue(r.theme.dateShort)
        assertEquals(Themes.FACTORY.fieldRadiusDp, r.theme.fieldRadiusDp)
    }

    @Test
    fun `unknown and invalid tokens are skipped and reported, the rest applies`() {
        val r = resolve(
            "color.ground" to "#FFFFFF", "color.sparkle" to "#123456", "color.ink" to "red",
            "style.knob" to "<b>ring</b>", "shape.tile_radius" to "99", "font.body" to "http://x/f.ttf",
        )
        assertEquals(Color.WHITE, r.theme.ground)
        assertEquals(SettingsValue.Outcome.UNKNOWN_KEY, noteFor(r.notes, "color.sparkle")!!.outcome)
        assertEquals(SettingsValue.Outcome.INVALID_VALUE, noteFor(r.notes, "color.ink")!!.outcome)
        assertEquals(SettingsValue.Outcome.INVALID_VALUE, noteFor(r.notes, "style.knob")!!.outcome)
        assertEquals(SettingsValue.Outcome.INVALID_VALUE, noteFor(r.notes, "shape.tile_radius")!!.outcome)
        assertEquals(SettingsValue.Outcome.INVALID_VALUE, noteFor(r.notes, "font.body")!!.outcome)
        assertEquals(Themes.FACTORY.ink, r.theme.ink)
        assertEquals(Themes.FACTORY.knob, r.theme.knob)
        assertNull("applied tokens are not listed", noteFor(r.notes, "color.ground"))
    }

    @Test
    fun `an unknown base or newer catalogue falls back to the factory look and is reported`() {
        assertNull("night is a label the backend uses",
            noteFor(DesignSync.resolve(spec(1).toBuilder().setBaseTheme("night").build()) { true }.notes, "base_theme"))
        val r = DesignSync.resolve(spec(1).toBuilder().setBaseTheme("sepia").setCatalogue(2).build()) { true }
        assertEquals(Themes.FACTORY.ground, r.theme.ground)
        assertEquals(SettingsValue.Outcome.UNKNOWN_KEY, noteFor(r.notes, "base_theme")!!.outcome)
        assertEquals(SettingsValue.Outcome.UNKNOWN_KEY, noteFor(r.notes, "catalogue")!!.outcome)
    }

    @Test
    fun `tokens this build cannot draw yet are kept but reported refused`() {
        val r = resolve("shape.density" to "roomy", "style.feed" to "cards")
        assertEquals("roomy", r.theme.density)
        assertEquals(SettingsValue.Outcome.REFUSED, noteFor(r.notes, "shape.density")!!.outcome)
        assertEquals(SettingsValue.Outcome.REFUSED, noteFor(r.notes, "style.feed")!!.outcome)
    }

    // ---- safety rails ----

    @Test
    fun `unreadable text is adjusted the least that passes and reported ADJUSTED`() {
        // Dark ground; the factory ink is dark too.
        val r = resolve("color.ground" to "#101010")
        val n = noteFor(r.notes, "color.ink")!!
        assertEquals(SettingsValue.Outcome.ADJUSTED, n.outcome)
        assertEquals(DesignSync.hex(r.theme.ink), n.value)
        assertTrue(contrast(r.theme.ink, r.theme.ground) >= 4.5)
        assertTrue(contrast(r.theme.ink, r.theme.tileFill) >= 4.5)
        assertTrue(contrast(r.theme.ink, r.theme.fieldFill!!) >= 4.5)
        assertTrue(contrast(r.theme.accent, r.theme.ground) >= 3.0)
        assertTrue(contrast(r.theme.inkMuted, r.theme.ground) >= 4.5)
    }

    @Test
    fun `a text colour asked for on its own stays exactly as asked when it reads`() {
        val r = resolve("color.ground" to "#14284B", "color.ink" to "#F5F1E8", "color.tile_fill" to "#1C3560",
            "color.field_fill" to "#1C3560", "color.accent" to "#F2B84B", "color.ink_muted" to "#C9D2E3")
        assertTrue(r.notes.toString(), r.notes.none { it.outcome == SettingsValue.Outcome.ADJUSTED })
        assertEquals(Color.parseColor("#F5F1E8"), r.theme.ink)
    }

    @Test
    fun `fills, accent and clock that fail are moved and reported under their own tokens`() {
        val r = resolve("color.ground" to "#FFFFFF", "color.ink" to "#000000", "color.tile_fill" to "#111111",
            "color.accent" to "#FAFAFA", "color.clock" to "#F0F0F0")
        for (k in listOf("color.tile_fill", "color.accent", "color.clock")) {
            assertEquals(k, SettingsValue.Outcome.ADJUSTED, noteFor(r.notes, k)?.outcome)
        }
        assertTrue(contrast(r.theme.ink, r.theme.tileFill) >= 4.5)
        assertTrue(contrast(r.theme.accent, r.theme.ground) >= 3.0)
        assertTrue(contrast(r.theme.clockColor, r.theme.ground) >= 4.5)
        assertNull("ink was readable and is untouched", noteFor(r.notes, "color.ink"))
    }

    @Test
    fun `body text never drops below 14 sp, and the scale is clamped`() {
        val small = Themes.FACTORY.copy(typeScale = 0.9f)
        assertEquals(14f, ThemePaint.scaledSp(small, 15f))
        assertEquals("small labels are never shrunk further", 12f, ThemePaint.scaledSp(small, 12f))
        assertEquals(18f, ThemePaint.scaledSp(small, 20f), 0.001f)
        val big = Themes.FACTORY.copy(typeScale = 1.5f)
        assertEquals(24f, ThemePaint.scaledSp(big, 16f), 0.001f)
        val r = resolve("type.scale" to "3")
        assertEquals(1.6f, r.theme.typeScale)
        assertEquals(SettingsValue.Outcome.ADJUSTED, noteFor(r.notes, "type.scale")!!.outcome)
    }

    // ---- fonts ----

    @Test
    fun `font ids map to typefaces, and an unknown id is the system sans`() {
        assertSame(Typeface.SANS_SERIF, Fonts.typeface(ctx, "sans"))
        assertSame(Typeface.SERIF, Fonts.typeface(ctx, "serif"))
        assertSame(Typeface.MONOSPACE, Fonts.typeface(ctx, "mono"))
        assertSame(Typeface.SANS_SERIF, Fonts.typeface(ctx, "grot"))
        assertSame(Typeface.SANS_SERIF, Fonts.typeface(ctx, "comic_sans"))
        assertSame(Typeface.SANS_SERIF, Fonts.typeface(ctx, null))
        assertTrue(Fonts.isAvailable(ctx, "atkinson"))
        assertNotEquals(Typeface.SANS_SERIF, Fonts.typeface(ctx, "atkinson"))
        assertFalse(Fonts.isAvailable(ctx, "comic_sans"))
        val r = resolve("font.body" to "lora", "font.display" to "bebas_neue")
        assertEquals("lora", r.theme.font)
        assertEquals("bebas_neue", r.theme.displayFont)
    }

    @Test
    fun `every bundled family has its files and its licence, and caps list them in short entries`() {
        val files = ctx.assets.list("fonts")!!.toSet()
        val licences = ctx.assets.list("fonts/licenses")!!.toSet()
        val bundled = Fonts.IDS - setOf("sans", "serif", "mono", "pixel")
        for (id in bundled) {
            if ("$id-regular.ttf" !in files) continue
            assertTrue("licence for $id", "$id.txt" in licences)
        }
        val entries = Fonts.capsEntries(ctx)
        assertTrue(entries.all { it.startsWith("font:") })
        val listed = entries.map { it.removePrefix("font:") }
        assertEquals(Fonts.available(ctx), listed)
        assertTrue("sans" in listed && "pixel" in listed && "atkinson" in listed)
    }

    // ---- shipped or not ----

    @Test
    fun `before it ships the two themes work as before and no spec is taken`() {
        Config.setThemeId(ctx, "night")
        assertEquals("night", Themes.current(ctx).id)
        assertFalse(DesignSync.apply(ctx, spec(3, "color.ground" to "#14284B")))
        assertEquals("night", Themes.current(ctx).id)
        assertFalse(DeviceProfile.capabilities(ctx).componentsList.contains(DesignSync.COMPONENT))
        assertTrue(DeviceProfile.capabilities(ctx).componentsList.none { it.startsWith("font:") })
        assertNull(WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl().queryParameter("design"))
        // Night keeps its look through the tokens it now sets.
        val night = Themes.byId("night")
        assertTrue(night.labelCaps && night.dateShort)
        assertEquals(45f, night.clockSp)
        assertEquals(Color.parseColor("#E9EFE4"), night.clockColor)
        assertEquals(48f, Themes.byId("ledger").clockSp)
    }

    @Test
    fun `once shipped the picked theme is ignored and the factory look is the floor`() {
        DesignSync.shippedForTest = true
        Config.setDesignMigrated(ctx, true)
        Config.setThemeId(ctx, "night")
        assertEquals(Themes.FACTORY, Themes.current(ctx))
        val comps = DeviceProfile.capabilities(ctx).componentsList
        assertTrue(comps.contains(DesignSync.COMPONENT))
        assertTrue(comps.contains("font:atkinson") && comps.contains("font:pixel"))
        assertTrue("within the backend's 96", comps.size <= 96)
    }

    @Test
    fun `a Night user keeps Night at upgrade, posted before the first turn declares designs`() {
        val s = backend()
        Config.setThemeId(ctx, "night")
        DesignSync.shippedForTest = true
        // The first frame is still Night.
        val first = Themes.current(ctx)
        assertEquals(Themes.byId("night").ground, first.ground)
        assertEquals(Themes.byId("night").clockColor, first.clockColor)
        assertTrue(first.labelCaps)
        assertEquals(0L, DesignSync.version(ctx))
        s.enqueue(protoBody(spec(1).toBuilder().setBaseTheme("night")
            .putAllTokens(DesignSync.tokensOf(Themes.byId("night"))).build().toByteArray()))
        s.enqueue(ok())
        Uploader(ctx).sendText("hello")
        val post = s.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/device/design", post.path)
        val sent = DesignSpec.parseFrom(post.body.readByteArray())
        assertEquals(0L, sent.version)
        assertEquals("night", sent.baseTheme)
        val turn = DeviceRequest.parseFrom(s.takeRequest(5, TimeUnit.SECONDS)!!.body.readByteArray())
        assertEquals(1L, turn.designVersion)
        assertEquals(Themes.byId("night").ground, Themes.current(ctx).ground)
    }

    @Test
    fun `a Ledger user posts nothing at upgrade`() {
        DesignSync.shippedForTest = true
        assertEquals(Themes.FACTORY, Themes.current(ctx))
        DesignSync.migrateLegacyTheme(ctx)
        assertFalse(DesignSync.postPending(ctx))
    }

    // ---- storage ----

    @Test
    fun `a spec is applied, kept, and drawn again after a restart before any network`() {
        DesignSync.shippedForTest = true
        assertTrue(DesignSync.apply(ctx, spec(3, "color.ground" to "#14284B", "color.ink" to "#F5F1E8", name = "Evening")))
        assertEquals(Color.parseColor("#14284B"), Themes.current(ctx).ground)
        DesignSync.forgetCacheForTest()
        assertEquals(Color.parseColor("#14284B"), Themes.current(ctx).ground)
        assertEquals(3L, DesignSync.version(ctx))
        assertEquals("Evening", DesignSync.name(ctx))
        assertTrue(DesignSync.custom(ctx))
    }

    @Test
    fun `an older or equal version is ignored, a newer one replaces the whole look`() {
        DesignSync.shippedForTest = true
        DesignSync.apply(ctx, spec(5, "color.ground" to "#14284B", "color.ink" to "#F5F1E8"))
        assertFalse(DesignSync.apply(ctx, spec(5, "color.ground" to "#000000")))
        assertFalse(DesignSync.apply(ctx, spec(4, "color.ground" to "#000000")))
        assertFalse("0 is never a backend version", DesignSync.apply(ctx, spec(0, "color.ground" to "#000000")))
        assertTrue(DesignSync.apply(ctx, spec(6, "font.body" to "serif")))
        assertEquals("the whole set, never a delta", Themes.FACTORY.ground, Themes.current(ctx).ground)
        assertEquals("serif", Themes.current(ctx).font)
    }

    @Test
    fun `a spec too large is refused whole and the look stays`() {
        DesignSync.shippedForTest = true
        DesignSync.apply(ctx, spec(2, "color.ground" to "#14284B", "color.ink" to "#F5F1E8"))
        val many = (1..65).map { "x.t$it" to "1" }.toTypedArray()
        assertFalse(DesignSync.apply(ctx, spec(3, *many)))
        assertEquals(Color.parseColor("#14284B"), Themes.current(ctx).ground)
        val st = DesignSync.pendingState(ctx)!!
        assertEquals(3L, st.version)
        assertEquals(SettingsValue.Outcome.REFUSED, st.valuesList.single().outcome)
        assertEquals("refused whole", "*", st.valuesList.single().key)
    }

    @Test
    fun `a name that is not plain text is dropped and reported`() {
        DesignSync.shippedForTest = true
        DesignSync.apply(ctx, spec(2, name = "<a href=x>Night</a>"))
        assertEquals("", DesignSync.name(ctx))
        assertEquals(SettingsValue.Outcome.INVALID_VALUE,
            DesignSync.pendingState(ctx)!!.valuesList.single { it.key == "name" }.outcome)
    }

    @Test
    fun `a stored spec that cannot be read draws the factory look`() {
        DesignSync.shippedForTest = true
        Config.setDesignSpec(ctx, "not base64 protobuf!")
        DesignSync.forgetCacheForTest()
        assertEquals(Themes.FACTORY, Themes.current(ctx))
    }

    @Test
    fun `a design that fails to draw twice is undone and reported refused`() {
        DesignSync.shippedForTest = true
        DesignSync.apply(ctx, spec(2, "color.ground" to "#14284B", "color.ink" to "#F5F1E8"))
        DesignSync.apply(ctx, spec(3, "color.ground" to "#000000", "color.ink" to "#FFFFFF"))
        assertFalse(DesignSync.renderFailed(ctx))
        assertTrue(DesignSync.renderFailed(ctx))
        assertEquals(Color.parseColor("#14284B"), Themes.current(ctx).ground)
        assertEquals("the failed version is still held, so it is not sent again", 3L, DesignSync.version(ctx))
        val st = DesignSync.pendingState(ctx)!!
        assertEquals("render failed", st.valuesList.single().detail)
    }

    // ---- reset on the phone ----

    @Test
    fun `reset works offline, draws the factory look and is reported and sent`() {
        DesignSync.shippedForTest = true
        DesignSync.apply(ctx, spec(5, "color.ground" to "#14284B", "color.ink" to "#F5F1E8"))
        Config.setBackendEndpoint(ctx, "http://127.0.0.1:9/v1/device")
        DesignSync.reset(ctx)
        DesignSync.awaitFlushForTest()
        assertEquals(Themes.FACTORY.ground, Themes.current(ctx).ground)
        assertFalse(DesignSync.custom(ctx))
        assertEquals("the version held does not move", 5L, DesignSync.version(ctx))
        assertTrue(DesignSync.pendingState(ctx)!!.resetOnDevice)
        assertTrue("kept to send when back online", Config.designPost(ctx).isNotBlank())
        DesignSync.forgetCacheForTest()
        assertEquals(Themes.FACTORY.ground, Themes.current(ctx).ground)
    }

    @Test
    fun `a reset is posted unnumbered and the backend's stored spec is taken in`() {
        DesignSync.shippedForTest = true
        val s = backend()
        DesignSync.apply(ctx, spec(5, "color.ground" to "#14284B", "color.ink" to "#F5F1E8"))
        s.enqueue(protoBody(spec(6).toByteArray()))
        DesignSync.reset(ctx)
        DesignSync.awaitFlushForTest()
        val req = s.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/device/design", req.path)
        val sent = DesignSpec.parseFrom(req.body.readByteArray())
        assertEquals(0L, sent.version)
        assertEquals("ledger", sent.baseTheme)
        assertEquals(0, sent.tokensCount)
        assertEquals(6L, DesignSync.version(ctx))
        assertEquals("", Config.designPost(ctx))
    }

    // ---- the wire ----

    @Test
    fun `a turn carries the versions and the echo, and a reply's spec is taken in`() {
        DesignSync.shippedForTest = true
        val s = backend()
        Config.setSettingsVersion(ctx, 4)
        DesignSync.apply(ctx, spec(7, "color.ground" to "#101010"))
        s.enqueue(protoBody(DeviceResponse.newBuilder().setIsFinal(true)
            .setSpeech(Speech.newBuilder().setText("Done."))
            .setDesign(spec(8, "color.ground" to "#14284B", "color.ink" to "#F5F1E8")).build().toByteArray()))
        s.enqueue(ok())
        assertNotNull(Uploader(ctx).sendText("make it dark blue"))
        val first = DeviceRequest.parseFrom(s.takeRequest(5, TimeUnit.SECONDS)!!.body.readByteArray())
        assertEquals(7L, first.designVersion)
        assertEquals(4L, first.settingsVersion)
        assertEquals(7L, first.designState.version)
        val ink = first.designState.valuesList.single { it.key == "color.ink" }
        assertEquals(SettingsValue.Outcome.ADJUSTED, ink.outcome)
        assertEquals(Color.parseColor("#14284B"), Themes.current(ctx).ground)
        // The next turn answers version 8: its text read as sent, while the factory tiles and
        // fields it kept were darkened to fit the light text, and say so.
        Uploader(ctx).sendText("thanks")
        val second = DeviceRequest.parseFrom(s.takeRequest(5, TimeUnit.SECONDS)!!.body.readByteArray())
        assertEquals(8L, second.designVersion)
        assertEquals(8L, second.designState.version)
        val keys = second.designState.valuesList.map { it.key }
        assertTrue(keys.toString(), "color.ink" !in keys && "color.tile_fill" in keys)
        assertTrue(second.designState.valuesList.all { it.outcome == SettingsValue.Outcome.ADJUSTED })
    }

    @Test
    fun `before it ships a turn carries none of it`() {
        val s = backend()
        s.enqueue(ok())
        Uploader(ctx).sendText("hello")
        val req = DeviceRequest.parseFrom(s.takeRequest(5, TimeUnit.SECONDS)!!.body.readByteArray())
        assertEquals(0L, req.designVersion)
        assertEquals(0L, req.settingsVersion)
        assertFalse(req.hasDesignState())
    }

    @Test
    fun `the wake asks with both versions and takes a design and a settings resend`() {
        DesignSync.shippedForTest = true
        HomeBoxes.shippedForTest = true
        Config.setSettingsVersion(ctx, 2)
        DesignSync.apply(ctx, spec(3))
        Config.setBackendEndpoint(ctx, "https://api.example/v1/device")
        val url = WakeLoop.wakeUrlFor(ctx, emptyList())!!.toHttpUrl()
        assertEquals("3", url.queryParameter("design"))
        assertEquals("2", url.queryParameter("settings"))
        assertEquals("home_boxes,design_v1", url.queryParameter("components"))

        WakeLoop.apply(ctx, WakeSignal.newBuilder()
            .setDesign(spec(4, "color.ground" to "#14284B", "color.ink" to "#F5F1E8"))
            .setSettings(SettingsCommand.newBuilder().setCommandId("").setVersion(9).setFull(true)
                .addWrites(SettingsWrite.newBuilder().setKey("assistant.voice_playback").setValue("off")))
            .build(), emptyList())
        assertEquals(4L, DesignSync.version(ctx))
        assertEquals(9L, Config.settingsVersion(ctx))
        assertFalse(Config.isReplyVoiceEnabled(ctx))
        Config.setReplyVoiceEnabled(ctx, true)
    }
}
