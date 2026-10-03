package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Base64
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import rist.v1.DesignSpec
import rist.v1.DesignState
import rist.v1.SettingsValue
import java.util.concurrent.Executors

/**
 * The phone's look, as the backend sends it.
 *
 * Users change how the phone looks by asking the assistant ("make the background dark blue",
 * "use a serif font", "bigger text"). The backend answers with a [DesignSpec]: the whole look,
 * every time, as a base plus token overrides from a fixed catalogue ([TOKENS]). The phone checks
 * every token, keeps the spec, redraws at once, and tells the backend on the next turn what it did
 * with anything it did not apply exactly as sent ([DesignState]).
 *
 * The phone has the final say on safety, whatever arrives:
 *  - text must reach 4.5:1 against the background, tiles and fields, and the accent 3:1; a colour
 *    that fails is moved the least amount that passes, and reported ADJUSTED with the value used;
 *  - body text never drops below 14 sp, and nothing a token can set changes a touch target;
 *  - nothing hides, moves or disguises the talk button, the settings gear or emergency calling;
 *  - tokens only: no markup, addresses, images, font files or code;
 *  - a spec that cannot be read, or that will not draw, leaves the previous look or the factory
 *    look on screen;
 *  - "Reset to default" in Settings works offline and returns to [Themes.FACTORY].
 */
object DesignSync {

    private const val TAG = "RistDesign"

    /**
     * Whether looks come from the backend. While false the phone keeps its two built-in themes
     * and the theme picker exactly as before, declares nothing and ignores any spec; while true
     * the built-in themes are gone and the factory look is only the floor.
     */
    const val SHIPPED = false

    /** Lets a test exercise the shipped behaviour while [SHIPPED] is false. */
    @Volatile internal var shippedForTest = false

    fun declared(): Boolean = SHIPPED || shippedForTest

    const val COMPONENT = "design_v1"
    const val CATALOGUE = 1
    const val FACTORY_BASE = "ledger"

    /** Base labels the backend uses; the tokens alone say what to draw. */
    internal val KNOWN_BASES = setOf("ledger", "night", "high_contrast")

    /** The key a spec refused whole is reported under. */
    const val WHOLE = "*"
    const val MAX_SPEC_BYTES = 4096
    const val MAX_TOKENS = 64
    const val NAME_MAX = 24

    const val ACTION_CHANGED = "watch.rist.assistant.DESIGN_CHANGED"

    const val TEXT_CONTRAST = 4.5
    const val ACCENT_CONTRAST = 3.0

    // Mirrors rist.v1.SettingsValue.Outcome.
    internal val APPLIED = SettingsValue.Outcome.APPLIED
    internal val REFUSED = SettingsValue.Outcome.REFUSED
    internal val UNKNOWN = SettingsValue.Outcome.UNKNOWN_KEY
    internal val INVALID = SettingsValue.Outcome.INVALID_VALUE
    internal val ADJUSTED = SettingsValue.Outcome.ADJUSTED

    // ---- the token catalogue (version 1) ----

    internal sealed class Kind {
        object Hex : Kind()
        object FontId : Kind()
        data class OneOf(val values: Set<String>) : Kind()
        data class Range(val min: Float, val max: Float, val clamp: Boolean = false) : Kind()
    }

    private fun oneOf(vararg v: String) = Kind.OneOf(v.toSet())

    internal val TOKENS: Map<String, Kind> = mapOf(
        "color.ground" to Kind.Hex,
        "color.ink" to Kind.Hex,
        "color.ink_muted" to Kind.Hex,
        "color.ink_faint" to Kind.Hex,
        "color.accent" to Kind.Hex,
        "color.tile_fill" to Kind.Hex,
        "color.tile_border" to Kind.Hex,
        "color.field_fill" to Kind.Hex,
        "color.field_border" to Kind.Hex,
        "color.clock" to Kind.Hex,
        "font.body" to Kind.FontId,
        "font.display" to Kind.FontId,
        "type.scale" to Kind.Range(0.9f, 1.6f, clamp = true),
        "type.weight" to oneOf("normal", "bold"),
        "type.clock_size" to oneOf("sm", "md", "lg"),
        "type.label_caps" to oneOf("on", "off"),
        "type.date_style" to oneOf("long", "short"),
        "shape.tile_radius" to Kind.Range(0f, 28f),
        "shape.field_radius" to Kind.Range(0f, 36f),
        "shape.border_width" to Kind.Range(0f, 3f),
        "shape.density" to oneOf("compact", "normal", "roomy"),
        "style.knob" to oneOf("ring", "pixel", "dome"),
        "style.hold_label" to oneOf("ink", "accent"),
        "style.tile" to oneOf("on", "off"),
        "style.press" to oneOf("flood", "none"),
        "style.feed" to oneOf("plain", "cards"),
        "style.boxes" to oneOf("tile", "outline"),
        "effect.glow" to oneOf("none", "knob", "knob_and_tiles"),
        "effect.scanlines" to oneOf("on", "off"),
    )

    /** Known and valid, but this build does not draw them yet: kept, and reported REFUSED. */
    internal val NOT_DRAWN = setOf("shape.density", "style.feed", "style.boxes")
    private const val NOT_DRAWN_DETAIL = "not drawn by this version of the phone"

    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    internal fun note(key: String, value: String, outcome: SettingsValue.Outcome, detail: String = ""): SettingsValue =
        SettingsValue.newBuilder().setKey(key).setValue(value).setOutcome(outcome).setDetail(detail).build()

    internal fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

    data class Resolution(val theme: RistTheme, val notes: List<SettingsValue>)

    /**
     * The look [spec] describes, made safe, and a note for every token not applied as sent.
     * [fontOk] says whether this phone can draw a font id. Pure: no storage, no drawing.
     */
    internal fun resolve(spec: DesignSpec, fontOk: (String) -> Boolean): Resolution {
        val notes = mutableListOf<SettingsValue>()
        val base = spec.baseTheme.trim().lowercase()
        if (base.isNotEmpty() && base !in KNOWN_BASES) {
            notes += note("base_theme", spec.baseTheme, UNKNOWN, "drawn over the factory look")
        }
        if (spec.catalogue > CATALOGUE) {
            notes += note("catalogue", spec.catalogue.toString(), UNKNOWN, "this phone knows catalogue $CATALOGUE")
        }
        var t = Themes.FACTORY.copy(id = "design", name = "Design")
        val sent = mutableMapOf<String, String>()
        for ((key, raw) in spec.tokensMap.toSortedMap()) {
            val kind = TOKENS[key]
            if (kind == null) { notes += note(key, raw, UNKNOWN, "not in the catalogue"); continue }
            val v = raw.trim()
            val ok: String? = when (kind) {
                Kind.Hex -> if (HEX.matches(v)) v.uppercase() else null
                Kind.FontId -> Fonts.canonical(v).takeIf { fontOk(it) }
                is Kind.OneOf -> v.lowercase().takeIf { it in kind.values }
                is Kind.Range -> v.toFloatOrNull()?.takeIf { !it.isNaN() }?.let { f ->
                    when {
                        f in kind.min..kind.max -> f.toString()
                        kind.clamp -> {
                            val c = f.coerceIn(kind.min, kind.max)
                            notes += note(key, c.toString(), ADJUSTED, "kept within ${kind.min} to ${kind.max}")
                            c.toString()
                        }
                        else -> null
                    }
                }
            }
            if (ok == null) {
                notes += note(key, raw, INVALID, when (kind) {
                    Kind.Hex -> "expected #RRGGBB"
                    Kind.FontId -> "not a font this phone has"
                    is Kind.OneOf -> "expected one of " + kind.values.sorted().joinToString(", ")
                    is Kind.Range -> "expected a number from ${kind.min} to ${kind.max}"
                })
                continue
            }
            sent[key] = ok
            if (key in NOT_DRAWN) notes += note(key, ok, REFUSED, NOT_DRAWN_DETAIL)
            t = withToken(t, key, ok)
        }
        if ("font.body" in sent && "font.display" !in sent) t = t.copy(displayFont = t.font)
        val safe = makeSafe(t, sent.keys)
        notes += safe.notes
        return Resolution(safe.theme, notes)
    }

    private fun color(v: String): Int = Color.parseColor(v)

    private fun withToken(t: RistTheme, key: String, v: String): RistTheme = when (key) {
        "color.ground" -> color(v).let { g -> t.copy(ground = g, dark = luminance(g) < 0.179) }
        "color.ink" -> t.copy(ink = color(v))
        "color.ink_muted" -> t.copy(inkMuted = color(v))
        "color.ink_faint" -> t.copy(inkFaint = color(v))
        "color.accent" -> t.copy(accent = color(v))
        "color.tile_fill" -> t.copy(tileFill = color(v))
        "color.tile_border" -> t.copy(tileBorder = color(v))
        "color.field_fill" -> t.copy(fieldFill = color(v))
        "color.field_border" -> t.copy(fieldBorder = color(v))
        "color.clock" -> t.copy(clock = color(v))
        "font.body" -> t.copy(font = v)
        "font.display" -> t.copy(displayFont = v)
        "type.scale" -> t.copy(typeScale = v.toFloat())
        "type.weight" -> t.copy(bold = v == "bold")
        "type.clock_size" -> t.copy(clockSize = v)
        "type.label_caps" -> t.copy(labelCaps = v == "on")
        "type.date_style" -> t.copy(dateShort = v == "short")
        "shape.tile_radius" -> t.copy(tileRadiusDp = v.toFloat())
        "shape.field_radius" -> t.copy(fieldRadiusDp = v.toFloat())
        "shape.border_width" -> t.copy(borderWidthDp = v.toFloat())
        "shape.density" -> t.copy(density = v)
        "style.knob" -> t.copy(knob = v)
        "style.hold_label" -> t.copy(holdOnAccent = v == "accent")
        "style.tile" -> t.copy(tile = v == "on")
        "style.press" -> t.copy(floodOnPress = v == "flood")
        "style.feed" -> t.copy(feed = v)
        "style.boxes" -> t.copy(boxes = v)
        "effect.glow" -> t.copy(knobGlow = v != "none", tileGlow = v == "knob_and_tiles")
        "effect.scanlines" -> t.copy(scan = v == "on")
        else -> t
    }

    /** Moves [c] toward [to] in the smallest step that reaches [ratio] against [against]. */
    internal fun nudge(c: Int, to: Int, against: Int, ratio: Double): Int {
        if (contrast(c, against) >= ratio) return c
        var f = 0.02f
        while (f < 1f) {
            val b = blend(c, to, f)
            if (contrast(b, against) >= ratio) return b
            f += 0.02f
        }
        return to
    }

    /** Black or white, whichever reads better on [ground]: always at least 4.58:1. */
    internal fun extremeFor(ground: Int): Int =
        if (contrast(Color.BLACK, ground) >= contrast(Color.WHITE, ground)) Color.BLACK else Color.WHITE

    /**
     * The contrast rails. Text colours that fail are moved toward black or white, fills toward the
     * background, and the accent toward the text colour, each by the least that passes. Each change
     * is reported ADJUSTED with the value used, under the token whose colour changed.
     */
    internal fun makeSafe(t0: RistTheme, sent: Set<String>): Resolution {
        val notes = mutableListOf<SettingsValue>()
        var t = t0
        fun adjusted(key: String, before: Int, after: Int, why: String) {
            if (before != after) notes += note(key, hex(after), ADJUSTED, why)
        }
        val ink = nudge(t.ink, extremeFor(t.ground), t.ground, TEXT_CONTRAST)
        adjusted("color.ink", t.ink, ink, "text kept readable on the background")
        t = t.copy(ink = ink)

        val tile = nudge(t.tileFill, t.ground, t.ink, TEXT_CONTRAST)
        adjusted("color.tile_fill", t.tileFill, tile, "text kept readable on tiles")
        t = t.copy(tileFill = tile)

        t.fieldFill?.let { ff ->
            val field = nudge(ff, t.ground, t.ink, TEXT_CONTRAST)
            adjusted("color.field_fill", ff, field, "text kept readable in fields")
            t = t.copy(fieldFill = field)
        }

        val accent = nudge(t.accent, t.ink, t.ground, ACCENT_CONTRAST)
        adjusted("color.accent", t.accent, accent, "accent kept visible on the background")
        t = t.copy(accent = accent)

        // Secondary text is raised only when the design touched it or what it sits on; the
        // factory look's own muted colour is raised at draw time by Themes.readableMuted.
        if ("color.ink_muted" in sent || "color.ground" in sent) {
            val muted = nudge(t.inkMuted, t.ink, t.ground, TEXT_CONTRAST)
            adjusted("color.ink_muted", t.inkMuted, muted, "secondary text kept readable")
            t = t.copy(inkMuted = muted)
        }
        t.clock?.let { c ->
            val clock = nudge(c, t.ink, t.ground, TEXT_CONTRAST)
            adjusted("color.clock", c, clock, "clock kept readable")
            t = t.copy(clock = clock)
        }
        return Resolution(t, notes)
    }

    // ---- what the phone holds ----

    @Volatile private var cachedTheme: RistTheme? = null
    @Volatile private var cachedSpec: DesignSpec? = null

    private fun decode(b64: String): DesignSpec? =
        if (b64.isBlank()) null
        else runCatching { DesignSpec.parseFrom(Base64.decode(b64, Base64.NO_WRAP)) }
            .onFailure { Log.w(TAG, "stored design unreadable", it) }.getOrNull()

    private fun encode(spec: DesignSpec?): String =
        spec?.let { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) } ?: ""

    /** The applied spec, or null for the factory look. */
    fun held(ctx: Context): DesignSpec? {
        cachedSpec?.let { return it }
        return decode(Config.designSpec(ctx)).also { cachedSpec = it }
    }

    fun version(ctx: Context): Long = held(ctx)?.version ?: 0L

    /** The name the assistant gave the current look, if any. */
    fun name(ctx: Context): String = held(ctx)?.name.orEmpty()

    /** Whether the phone is drawing something other than the factory look. */
    fun custom(ctx: Context): Boolean = held(ctx)?.let { it.tokensCount > 0 } ?: false

    /** The look to draw. Never throws: anything unreadable is the factory look. */
    fun theme(ctx: Context): RistTheme {
        cachedTheme?.let { return it }
        // So the very first frame of a design build is still the user's Night.
        runCatching { migrateLegacyTheme(ctx, redraw = false) }
        val spec = held(ctx)
        val t = if (spec == null) Themes.FACTORY
        else runCatching { resolve(spec) { Fonts.isAvailable(ctx, it) }.theme }
            .onFailure { Log.w(TAG, "stored design could not be drawn; factory look", it) }
            .getOrDefault(Themes.FACTORY)
        cachedTheme = t
        return t
    }

    private fun store(ctx: Context, spec: DesignSpec?, previous: DesignSpec?) {
        Config.setDesignPrevious(ctx, encode(previous))
        Config.setDesignSpec(ctx, encode(spec))
        cachedSpec = spec
        cachedTheme = null
    }

    private fun announce(ctx: Context) = runCatching {
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx.applicationContext)
            .sendBroadcast(Intent(ACTION_CHANGED))
    }

    private fun plain(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return ""
        if (n.length > NAME_MAX || n.any { it.isISOControl() || it == '<' || it == '>' } ||
            n.contains("://") || n.contains("www.", ignoreCase = true)) return null
        return n
    }

    /**
     * Takes in a spec from a turn's reply or the wake. Returns true when the look changed.
     * A spec not newer than the one held is ignored; one too large or unreadable is refused
     * whole and the look stays as it was. Either way the outcome rides the next turn.
     */
    fun apply(ctx: Context, incoming: DesignSpec): Boolean {
        if (!declared()) return false
        val held = held(ctx)
        val heldVersion = held?.version ?: 0L
        if (incoming.version == 0L) return false
        if (heldVersion != 0L && incoming.version <= heldVersion) return false
        if (incoming.serializedSize > MAX_SPEC_BYTES || incoming.tokensCount > MAX_TOKENS) {
            queueState(ctx, DesignState.newBuilder().setVersion(incoming.version)
                .addValues(note(WHOLE, "", REFUSED, "larger than 4 KB or $MAX_TOKENS tokens")).build())
            return false
        }
        val res = runCatching { resolve(incoming) { Fonts.isAvailable(ctx, it) } }.getOrElse {
            Log.w(TAG, "design ${incoming.version} unreadable; refused", it)
            queueState(ctx, DesignState.newBuilder().setVersion(incoming.version)
                .addValues(note(WHOLE, "", REFUSED, "could not be read")).build())
            return false
        }
        val notes = res.notes.toMutableList()
        var spec = incoming
        plain(incoming.name).let { n ->
            if (n == null) {
                notes += note("name", incoming.name, INVALID, "plain text of at most $NAME_MAX characters")
                spec = spec.toBuilder().setName("").build()
            } else if (n != incoming.name) spec = spec.toBuilder().setName(n).build()
        }
        synchronized(this) {
            store(ctx, spec, held)
            cachedTheme = res.theme
            appliedAtMs = System.currentTimeMillis()
            renderFailures.clear()
        }
        queueState(ctx, DesignState.newBuilder().setVersion(spec.version).addAllValues(notes).build())
        Log.i(TAG, "design ${spec.version} applied: ${spec.tokensCount} token(s), ${notes.size} note(s)")
        announce(ctx)
        return true
    }

    /**
     * "Reset to default", offline: the factory look at once, the backend told on the next turn
     * and by [flush]. The version held does not change, so the backend does not send the old
     * design straight back; it numbers the reset and returns it.
     */
    fun reset(ctx: Context) {
        val held = held(ctx)
        val v = held?.version ?: 0L
        val factory = DesignSpec.newBuilder().setVersion(v).setBaseTheme(FACTORY_BASE)
            .setCatalogue(CATALOGUE).build()
        synchronized(this) {
            store(ctx, if (v == 0L) null else factory, held)
            cachedTheme = Themes.FACTORY
        }
        queueState(ctx, DesignState.newBuilder().setVersion(v).setResetOnDevice(true).build())
        Config.setDesignPost(ctx, encode(factory.toBuilder().setVersion(0).build()))
        Log.i(TAG, "reset to the factory look on the phone")
        announce(ctx)
        if (declared()) flushSoon(ctx)
    }

    /** The catalogue tokens that draw [t]; what the phone sends when it posts a look of its own. */
    internal fun tokensOf(t: RistTheme): Map<String, String> {
        fun n(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
        return mapOf(
            "color.ground" to hex(t.ground), "color.ink" to hex(t.ink), "color.ink_muted" to hex(t.inkMuted),
            "color.ink_faint" to hex(t.inkFaint ?: blend(t.ink, t.ground, 0.5f)),
            "color.accent" to hex(t.accent), "color.tile_fill" to hex(t.tileFill),
            "color.tile_border" to hex(t.tileBorder), "color.field_fill" to hex(t.fieldFill ?: t.ground),
            "color.field_border" to hex(t.fieldBorder), "color.clock" to hex(t.clockColor),
            "font.body" to Fonts.canonical(t.font), "font.display" to Fonts.canonical(t.displayFont),
            "type.clock_size" to t.clockSize, "type.label_caps" to if (t.labelCaps) "on" else "off",
            "type.date_style" to if (t.dateShort) "short" else "long",
            "shape.tile_radius" to n(t.tileRadiusDp), "shape.field_radius" to n(t.fieldRadiusDp),
            "shape.border_width" to n(t.borderWidthDp),
            "style.knob" to t.knob, "style.hold_label" to if (t.holdOnAccent) "accent" else "ink",
            "style.tile" to if (t.tile) "on" else "off", "style.press" to if (t.floodOnPress) "flood" else "none",
            "effect.glow" to when { t.tileGlow -> "knob_and_tiles"; t.knobGlow -> "knob"; else -> "none" },
            "effect.scanlines" to if (t.scan) "on" else "off",
        )
    }

    /**
     * The first run of a build with designs on, for a user who had picked Night on the phone: the
     * backend cannot see that choice, so the phone keeps drawing Night (held unnumbered) and posts
     * it, before any turn or wake declares designs, so the backend does not send Ledger instead.
     * Runs once; a Ledger user needs nothing (Ledger is the backend's default).
     */
    fun migrateLegacyTheme(ctx: Context, redraw: Boolean = true) {
        if (!declared() || Config.designMigrated(ctx)) return
        Config.setDesignMigrated(ctx, true)
        if (held(ctx) != null || Config.themeId(ctx) != "night") return
        val night = DesignSpec.newBuilder().setVersion(0).setBaseTheme("night").setCatalogue(CATALOGUE)
            .putAllTokens(tokensOf(Themes.byId("night"))).build()
        synchronized(this) { store(ctx, night, null) }
        Config.setDesignPost(ctx, encode(night))
        Log.i(TAG, "kept the Night theme picked on the phone; posting it")
        if (redraw) announce(ctx)
    }

    /** Whether a look made on the phone still waits to be posted. */
    fun postPending(ctx: Context): Boolean = Config.designPost(ctx).isNotBlank()

    // ---- crash guard ----

    private const val GUARD_WINDOW_MS = 60_000L
    @Volatile private var appliedAtMs = 0L
    private val renderFailures = mutableListOf<Long>()

    /**
     * Called when drawing the home screen in the current design fails. The second failure within a
     * minute of a new design puts the previous look back, keeps the version (so the backend does
     * not resend it) and reports the design REFUSED.
     */
    fun renderFailed(ctx: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!declared()) return false
        val failed = held(ctx) ?: return false
        val rollBack = synchronized(this) {
            if (nowMs - appliedAtMs > GUARD_WINDOW_MS) return false
            renderFailures += nowMs
            renderFailures.removeAll { nowMs - it > GUARD_WINDOW_MS }
            renderFailures.size >= 2
        }
        if (!rollBack) return false
        val previous = decode(Config.designPrevious(ctx))
        val back = (previous ?: DesignSpec.newBuilder().setBaseTheme(FACTORY_BASE).setCatalogue(CATALOGUE).build())
            .toBuilder().setVersion(failed.version).build()
        synchronized(this) {
            store(ctx, back, null)
            appliedAtMs = 0L
            renderFailures.clear()
        }
        queueState(ctx, DesignState.newBuilder().setVersion(failed.version)
            .addValues(note(WHOLE, "", REFUSED, "render failed")).build())
        Log.w(TAG, "design ${failed.version} failed to draw twice; previous look restored")
        announce(ctx)
        return true
    }

    // ---- the echo to the backend ----

    private fun queueState(ctx: Context, state: DesignState) {
        // A newer answer replaces an unsent older one; a reset made on the phone is kept.
        val before = pendingState(ctx)
        val merged = if (before != null && before.resetOnDevice && !state.resetOnDevice)
            state.toBuilder().setResetOnDevice(true).build() else state
        Config.setDesignState(ctx, Base64.encodeToString(merged.toByteArray(), Base64.NO_WRAP))
    }

    /** What the next turn reports, or null when there is nothing to say. */
    fun pendingState(ctx: Context): DesignState? {
        val raw = Config.designState(ctx)
        if (raw.isBlank()) return null
        return runCatching { DesignState.parseFrom(Base64.decode(raw, Base64.NO_WRAP)) }.getOrNull()
    }

    /** Clears [sent] once a turn carrying it was answered, unless a newer one replaced it. */
    fun clearState(ctx: Context, sent: DesignState) {
        if (pendingState(ctx) == sent) Config.setDesignState(ctx, "")
    }

    // ---- POST /v1/device/design, for a reset made on the phone ----

    internal fun designUrl(backendUrl: String): String? {
        val base = backendUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val url = if (base.endsWith("/v1/device")) "$base/design" else "$base/v1/device/design"
        return url.toHttpUrlOrNull()?.toString()
    }

    private val PROTOBUF = "application/x-protobuf".toMediaType()

    /**
     * Sends a look changed on the phone. Returns true when nothing is left to send. Blocking;
     * call off the main thread. The stored spec the backend answers with is applied.
     */
    fun flush(ctx: Context, http: OkHttpClient = Uploader.sharedClient()): Boolean {
        val pending = decode(Config.designPost(ctx)) ?: return true
        val url = designUrl(Config.backendUrl(ctx)) ?: return false
        val bearer = Uploader.bearer(ctx)
        val request = Request.Builder().url(url)
            .post(pending.toByteArray().toRequestBody(PROTOBUF))
            .header("Content-Type", "application/x-protobuf")
            .header("Accept", "application/x-protobuf")
            .apply { if (bearer != null) header("Authorization", bearer) }
            .header("X-Rist-Device", Config.deviceId(ctx))
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        Config.setDesignPost(ctx, "")
                        val body = resp.body?.bytes() ?: ByteArray(0)
                        runCatching { DesignSpec.parseFrom(body) }.getOrNull()?.let { apply(ctx, it) }
                        true
                    }
                    resp.code in setOf(401, 402, 403, 408, 429) || resp.code >= 500 -> false
                    else -> {
                        Log.w(TAG, "design change refused with HTTP ${resp.code}; dropped")
                        Config.setDesignPost(ctx, "")
                        true
                    }
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "design change waits (${t.javaClass.simpleName})")
            false
        }
    }

    private val flusher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rist-design").apply { isDaemon = true }
    }

    fun flushSoon(ctx: Context) {
        val app = ctx.applicationContext
        flusher.execute { runCatching { flush(app) }.onFailure { Log.w(TAG, "flush failed", it) } }
    }

    internal fun awaitFlushForTest() {
        flusher.submit {}.get()
    }

    internal fun resetForTest(ctx: Context) {
        shippedForTest = false
        synchronized(this) {
            cachedSpec = null
            cachedTheme = null
            appliedAtMs = 0L
            renderFailures.clear()
        }
        Config.setDesignSpec(ctx, "")
        Config.setDesignPrevious(ctx, "")
        Config.setDesignState(ctx, "")
        Config.setDesignPost(ctx, "")
        Config.setSettingsVersion(ctx, 0L)
        Config.setDesignMigrated(ctx, false)
    }

    /** Drops the in-memory copy, as a process restart would. */
    internal fun forgetCacheForTest() {
        cachedSpec = null
        cachedTheme = null
    }
}
