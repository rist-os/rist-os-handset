package watch.rist.assistant

import android.content.Context
import android.graphics.Color
import rist.v1.HomeBox
import rist.v1.TileBlock
import rist.v1.TileRow

/**
 * A tile's expanded view as typed blocks: lists, checklists, a forecast strip, a big number, a
 * table, a progress bar, or a paragraph. Each block is drawn natively ([TileBlockView]); a kind
 * this phone does not know is drawn as the markdown the block also carries.
 *
 * What to draw, first match wins: blocks (when declared and sent), then checklists, then body.
 * The backend keeps sending body and checklists, so a phone that does not declare blocks sees
 * exactly what it did.
 */
object TileBlocks {

    /** Whether this build declares `tile_blocks_v1`. Without it the backend sends no blocks. */
    const val SHIPPED = true

    const val COMPONENT = "tile_blocks_v1"

    @Volatile internal var shippedForTest: Boolean? = null

    fun declared(): Boolean = shippedForTest ?: SHIPPED

    /** Every tile component this build declares, in the order they are listed. */
    fun components(): List<String> = buildList {
        if (declared()) add(COMPONENT)
        if (ItemEdits.declared()) add(ItemEdits.COMPONENT)
    }

    enum class Kind(val wire: String) {
        TEXT("text"), LIST("list"), CHECKLIST("checklist"), FORECAST("forecast"),
        STAT("stat"), TABLE("table"), PROGRESS("progress"),
        /** Anything else, "chart" included: draw the block's markdown. */
        FALLBACK(""),
    }

    fun kindOf(b: TileBlock): Kind {
        val k = b.kind.trim().lowercase()
        return Kind.values().firstOrNull { it != Kind.FALLBACK && it.wire == k } ?: Kind.FALLBACK
    }

    // ---- clamps (home_boxes.md section 10.2) ----

    const val TITLE_MAX = 48
    const val LABEL_MAX = 48
    const val SLOT_MAX = 400
    const val ICON_DESC_MAX = 60
    const val EDIT_TEXT_MAX = 20_000
    const val ROWS_MAX = 50
    const val BLOCKS_MAX = 6
    const val COLUMNS_MAX = 4

    private fun slot(s: String) = HomeBoxes.clip(s, SLOT_MAX)

    fun clamp(r: TileRow): TileRow {
        val c = r.toBuilder()
            .setIcon(HomeBoxes.clip(r.icon.trim(), SLOT_MAX))
            .setIconDesc(HomeBoxes.clip(r.iconDesc.trim(), ICON_DESC_MAX))
            .setIconTone(HomeBoxes.clip(r.iconTone.trim(), SLOT_MAX))
            .setLabel(HomeBoxes.clip(r.label, LABEL_MAX))
            .setText(slot(r.text)).setDetail(slot(r.detail)).setExtra(slot(r.extra))
            .setTarget(slot(r.target.trim())).setEditText(HomeBoxes.clip(r.editText, EDIT_TEXT_MAX))
            .clearCells().addAllCells(r.cellsList.take(COLUMNS_MAX).map { slot(it) })
        // A row the phone could act on needs an id to name it: without one it is read-only.
        if (r.id.isBlank()) c.setCheckable(false).setEditable(false).setDeletable(false)
        return c.build()
    }

    fun clamp(b: TileBlock): TileBlock = b.toBuilder()
        .setTitle(HomeBoxes.clip(b.title.trim(), TITLE_MAX))
        .setFallbackMarkdown(HomeBoxes.clipUtf8(b.fallbackMarkdown, HomeBoxes.BODY_MAX_BYTES))
        .setEmpty(slot(b.empty))
        .setAddTo(slot(b.addTo.trim()))
        .clearColumns().addAllColumns(b.columnsList.take(COLUMNS_MAX).map { slot(it) })
        .clearRows().addAllRows(b.rowsList.take(ROWS_MAX).map { clamp(it) })
        .build()

    fun clamp(box: HomeBox): HomeBox {
        if (box.blocksCount == 0 && box.iconTone.isEmpty()) return box
        return box.toBuilder()
            .clearBlocks().addAllBlocks(box.blocksList.take(BLOCKS_MAX).map { clamp(it) })
            .setIconTone(HomeBoxes.clip(box.iconTone.trim(), SLOT_MAX))
            .build()
    }

    /** The blocks to draw for [box]; empty means draw checklists or body as before. */
    fun toDraw(box: HomeBox): List<TileBlock> = if (declared()) box.blocksList else emptyList()

    // ---- words for a screen reader ----

    /** One row in words: label, icon meaning, text, detail, extra ("Friday, light rain, 60°, 44°, 40%"). */
    fun spoken(r: TileRow): String =
        listOf(r.label, r.iconDesc, r.text, r.detail, r.extra).map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(", ")

    /** A table row in words, each cell after its column name ("Stock: ACME, Price: 12.40"). */
    fun spokenTableRow(columns: List<String>, r: TileRow): String =
        r.cellsList.mapIndexed { i, cell ->
            val name = columns.getOrNull(i)?.trim().orEmpty()
            if (name.isEmpty()) cell else "$name: $cell"
        }.filter { it.isNotBlank() }.joinToString(", ")

    /** How full a progress row is, 0 to 1, or null when it has no usable maximum. */
    fun fraction(r: TileRow): Float? {
        if (!(r.max > 0.0) || r.value.isNaN()) return null
        return (r.value / r.max).coerceIn(0.0, 1.0).toFloat()
    }

    // ---- the box list version to ask with ----

    /**
     * A phone that has just started declaring blocks may already hold the current list without
     * them; until a list has arrived since, it asks with 0 so the backend sends the whole list.
     */
    fun needsWholeList(ctx: Context): Boolean = declared() && !Config.tileBlockBoxesSeen(ctx)

    /** A box list arrived while blocks were declared. */
    fun observe(ctx: Context) {
        if (declared() && !Config.tileBlockBoxesSeen(ctx)) Config.setTileBlockBoxesSeen(ctx, true)
    }

    internal fun resetForTest(ctx: Context) {
        shippedForTest = null
        Config.setTileBlockBoxesSeen(ctx, false)
    }
}

/**
 * Colour roles for icons. The backend names a role ("warm" for a sun, "cool" for rain), never a
 * colour; each role gets a hue here, chosen for the background it sits on and darkened or
 * lightened toward the theme's text colour until it reads at 3:1 or better. An empty or unknown
 * role is the text colour. Colour never carries meaning alone: the row's words always say it.
 */
object TileTones {

    val ROLES = listOf("warm", "cool", "cold", "neutral", "storm", "night", "alert", "good", "bad", "accent")

    /** The least contrast an icon may have against what it sits on. */
    const val MIN_CONTRAST = 3.0

    private fun c(h: String) = Color.parseColor(h)

    /** A hue for a light background and one for a dark one. */
    private val HUES: Map<String, Pair<Int, Int>> = mapOf(
        "warm" to (c("#B86E00") to c("#F2C14E")),
        "cool" to (c("#1F63B5") to c("#7FB2F5")),
        "cold" to (c("#167A8A") to c("#9BDDEB")),
        "storm" to (c("#6A3FA0") to c("#BBA0F0")),
        "night" to (c("#3E4A9E") to c("#B4BDF5")),
        "alert" to (c("#B4520A") to c("#F7A04B")),
        "good" to (c("#2E7D32") to c("#7DCB83")),
        "bad" to (c("#B3261E") to c("#F28B82")),
    )

    /** The colour an icon of role [tone] is drawn in, on [background] (the theme's ground by default). */
    fun colour(rt: RistTheme, tone: String, background: Int = rt.ground): Int {
        val role = tone.trim().lowercase()
        val wanted = when (role) {
            "neutral" -> Themes.readableMuted(rt)
            "accent" -> rt.accent
            else -> HUES[role]?.let { (light, dark) -> if (luminance(background) < 0.18) dark else light }
                ?: return rt.ink
        }
        return readable(wanted, rt.ink, background)
    }

    /** [colour], moved toward [ink] just far enough to reach [MIN_CONTRAST] on [background]. */
    internal fun readable(colour: Int, ink: Int, background: Int): Int {
        if (contrast(colour, background) >= MIN_CONTRAST) return colour
        var f = 0.1f
        while (f < 1.0f) {
            val mixed = blend(colour, ink, f)
            if (contrast(mixed, background) >= MIN_CONTRAST) return mixed
            f += 0.1f
        }
        return ink
    }

    /** The face icon's colour on a home tile: its role when blocks are declared, else the text colour. */
    fun face(rt: RistTheme, box: HomeBox, background: Int): Int =
        if (TileBlocks.declared() && box.iconTone.isNotBlank()) colour(rt, box.iconTone, background) else rt.ink
}
