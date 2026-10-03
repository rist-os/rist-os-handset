package watch.rist.assistant

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.util.Log

/**
 * The fonts a design may name, by id. Each bundled family ships in `assets/fonts/` as
 * `<id>-regular.ttf` and, where the family has one, `<id>-bold.ttf`, subset to Latin and
 * Latin-1, with its licence in `assets/fonts/licenses/<id>.txt`. `sans`, `serif` and `mono` are
 * the phone's own; `pixel` is the Silkscreen font in `res/font`.
 *
 * A design can only pick from [available]; the phone lists those ids to the backend so it never
 * names one the phone lacks. An id the phone cannot draw falls back to the system sans.
 */
object Fonts {

    private const val TAG = "RistFonts"

    const val SANS = "sans"
    const val SERIF = "serif"
    const val MONO = "mono"
    const val PIXEL = "pixel"

    /** Every id a design may name, in the order the backend's catalogue lists them. */
    val IDS: List<String> = listOf(
        "atkinson", "lexend", "opendyslexic",
        "source_sans", "ibm_plex_sans", "inter", "work_sans", "manrope", "dm_sans", "figtree",
        "outfit", "nunito", "rubik", "poppins", "montserrat", "raleway", "quicksand", "comfortaa",
        "fredoka",
        "source_serif", "ibm_plex_serif", "merriweather", "lora", "literata", "libre_baskerville",
        "eb_garamond", "crimson_pro", "fraunces", "playfair_display", "dm_serif_display",
        "ibm_plex_mono", "jetbrains_mono", "fira_code", "source_code", "space_mono",
        "oswald", "bebas_neue", "anton", "archivo_black",
        "caveat", "patrick_hand", "kalam",
        PIXEL, "press_start", "vt323", "pixelify",
        SANS, SERIF, MONO,
    )

    private val BUILT_IN = setOf(SANS, SERIF, MONO, PIXEL)

    /** Older theme ids for the system fonts. */
    private val ALIASES = mapOf("grot" to SANS, "sans-serif" to SANS, "monospace" to MONO)

    internal fun canonical(id: String?): String {
        val k = id?.trim()?.lowercase().orEmpty()
        return ALIASES[k] ?: k
    }

    private fun regularPath(id: String) = "fonts/$id-regular.ttf"
    private fun boldPath(id: String) = "fonts/$id-bold.ttf"

    @Volatile private var bundledCache: Set<String>? = null

    /** The bundled families whose files are in this build. */
    internal fun bundled(ctx: Context): Set<String> {
        bundledCache?.let { return it }
        val files = runCatching { ctx.assets.list("fonts")?.toSet() }.getOrNull().orEmpty()
        return IDS.filter { it !in BUILT_IN && "$it-regular.ttf" in files }.toSet()
            .also { bundledCache = it }
    }

    /** Ids this phone can draw, in catalogue order. */
    fun available(ctx: Context): List<String> {
        val b = bundled(ctx)
        return IDS.filter { it in BUILT_IN || it in b }
    }

    fun isAvailable(ctx: Context, id: String?): Boolean = canonical(id) in available(ctx)

    /** The `caps.components` entries that list [available]: one "font:<id>" per font. */
    fun capsEntries(ctx: Context): List<String> = capsEntries(available(ctx))

    internal const val CAPS_PREFIX = "font:"

    internal fun capsEntries(ids: List<String>): List<String> = ids.map { CAPS_PREFIX + it }

    private val cache = mutableMapOf<String, Typeface>()

    /**
     * The typeface for [id], never null: an unknown id, or a bundled family whose file will not
     * load, is the system sans.
     */
    @Synchronized
    fun typeface(ctx: Context, id: String?): Typeface {
        val k = canonical(id)
        when (k) {
            SANS -> return Typeface.SANS_SERIF
            SERIF -> return Typeface.SERIF
            MONO -> return Typeface.MONOSPACE
        }
        cache[k]?.let { return it }
        val tf = when {
            k == PIXEL -> runCatching {
                androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.pixel)
            }.getOrNull()
            k in bundled(ctx) -> load(ctx, k)
            else -> null
        } ?: return Typeface.SANS_SERIF
        cache[k] = tf
        return tf
    }

    private fun load(ctx: Context, id: String): Typeface? {
        val assets = ctx.assets
        val family = runCatching {
            val b = FontFamily.Builder(Font.Builder(assets, regularPath(id)).setWeight(400).build())
            runCatching { assets.open(boldPath(id)).close() }.onSuccess {
                b.addFont(Font.Builder(assets, boldPath(id)).setWeight(700).build())
            }
            Typeface.CustomFallbackBuilder(b.build()).setSystemFallback("sans-serif").build()
        }.onFailure { Log.w(TAG, "font $id did not load as a family", it) }.getOrNull()
        return family ?: runCatching { Typeface.createFromAsset(assets, regularPath(id)) }
            .onFailure { Log.w(TAG, "font $id did not load", it) }.getOrNull()
    }

    internal fun forgetForTest() {
        synchronized(this) { cache.clear() }
        bundledCache = null
    }
}
