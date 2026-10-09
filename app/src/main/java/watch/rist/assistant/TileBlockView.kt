package watch.rist.assistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import rist.v1.TileBlock
import rist.v1.TileRow

/**
 * Draws a tile's blocks with native views, one instance per open tile. It remembers, across
 * redraws, which row is open for editing and what has been typed there, and the rows ticked while
 * the view has been open, so a newer list arriving does not throw either away.
 */
class TileBlockView(private val ctx: Context, private val rt: RistTheme, private val tf: Typeface?) {

    private val d = ctx.resources.displayMetrics.density
    private fun px(v: Float): Int = (v * d).toInt()
    private val muted = Themes.readableMuted(rt)

    /** The row or add row open for editing ([ItemEdits.rowKey] / [ItemEdits.addKey]), and its text. */
    private var editing: String? = null
    private var draft: String = ""
    private var focusNext = false

    /** Rows ticked while open: kept on screen, at their place, until the view closes. */
    private data class Kept(val block: Int, val index: Int, val row: TileRow)
    private val kept = HashMap<String, Kept>()

    val isEditing: Boolean get() = editing != null

    /** Closes the open editor without saving; false when none was open. */
    fun closeEditor(): Boolean {
        if (editing == null) return false
        editing = null
        return true
    }

    fun draw(parent: LinearLayout, blocks: List<TileBlock>) {
        parent.removeAllViews()
        blocks.forEachIndexed { i, b -> drawBlock(parent, i, b) }
    }

    private fun drawBlock(parent: LinearLayout, index: Int, b: TileBlock) {
        val kind = TileBlocks.kindOf(b)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_BLOCK_PREFIX + (kind.wire.ifEmpty { "fallback" })
            setPadding(0, 0, 0, px(10f))
        }
        parent.addView(box)
        if (b.title.isNotBlank()) box.addView(ChecklistView.heading(ctx, b.title, rt, tf))
        val needsRows = kind != TileBlocks.Kind.TEXT && kind != TileBlocks.Kind.FALLBACK
        val rows = if (kind == TileBlocks.Kind.LIST || kind == TileBlocks.Kind.CHECKLIST) rowsOf(index, b)
        else b.rowsList.mapIndexed { i, r -> r to i }
        if (needsRows && rows.isEmpty() && ItemEdits.placeholders(b.addTo).isEmpty()) {
            if (b.empty.isNotBlank()) box.addView(text(b.empty, 15f, muted).apply { tag = TAG_EMPTY })
        }
        when (kind) {
            TileBlocks.Kind.TEXT, TileBlocks.Kind.FALLBACK -> box.addView(markdown(b.fallbackMarkdown, kind))
            TileBlocks.Kind.LIST, TileBlocks.Kind.CHECKLIST -> {
                rows.forEach { (row, at) -> box.addView(listRow(index, at, row, kind == TileBlocks.Kind.CHECKLIST)) }
                if (ItemEdits.declared() && b.addTo.isNotBlank()) {
                    ItemEdits.placeholders(b.addTo).forEach { box.addView(placeholder(it, kind == TileBlocks.Kind.CHECKLIST)) }
                    box.addView(addRow(b.addTo))
                }
            }
            TileBlocks.Kind.FORECAST -> if (b.rowsCount > 0) box.addView(forecast(b.rowsList))
            TileBlocks.Kind.STAT -> b.rowsList.forEach { box.addView(stat(it)) }
            TileBlocks.Kind.TABLE -> if (b.rowsCount > 0) box.addView(table(b.columnsList, b.rowsList))
            TileBlocks.Kind.PROGRESS -> b.rowsList.forEach { box.addView(progress(it)) }
        }
    }

    /** A block's rows with their places, laid over with what the phone knows the backend does not yet show. */
    private fun rowsOf(block: Int, b: TileBlock): List<Pair<TileRow, Int>> {
        val rows = b.rowsList.mapIndexed { i, r -> r to i }.toMutableList()
        val missing = kept.values.filter { k -> k.block == block && rows.none { it.first.id == k.row.id } }
        for (k in missing.sortedBy { it.index }) rows.add(k.index.coerceIn(0, rows.size), k.row to k.index)
        return rows
    }

    // ---- views ----

    private fun text(s: CharSequence, sp: Float, colour: Int) = TextView(ctx).apply {
        text = s
        typeface = tf
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemePaint.scaledSp(rt, sp))
        setTextColor(colour)
    }

    private fun markdown(md: String, kind: TileBlocks.Kind) = text(Markdown.render(md), 16f, rt.ink).apply {
        tag = if (kind == TileBlocks.Kind.TEXT) TAG_TEXT else TAG_FALLBACK
        setLineSpacing(0f, 1.3f)
        setTextIsSelectable(true)
        setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
        setPadding(px(4f), px(4f), 0, px(4f))
    }

    private fun icon(name: String, tone: String, sizeDp: Float): ImageView? {
        if (name.isBlank()) return null
        val dr = BoxIcons.named(ctx, name, TileTones.colour(rt, tone, rt.ground)) ?: return null
        return ImageView(ctx).apply {
            tag = TAG_ICON
            setImageDrawable(dr)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp))
        }
    }

    private fun statusLine(key: String): TextView? {
        val s = ItemEdits.statusOf(key) ?: return null
        val line = when (s.kind) {
            ItemEdits.Status.Kind.SAVING -> ctx.getString(R.string.tile_edit_saving)
            ItemEdits.Status.Kind.UNDO -> return null
            else -> s.line
        }
        if (line.isBlank()) return null
        return text(line, 13f, muted).apply {
            tag = TAG_STATUS
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(px(4f), 0, 0, px(6f))
        }
    }

    /** One row of a list or checklist: its words, a checkbox, editing and delete as the row allows. */
    private fun listRow(block: Int, at: Int, row: TileRow, checklist: Boolean): View {
        val key = ItemEdits.rowKey(row.id)
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; tag = TAG_ROW }
        if (row.id.isNotBlank() && ItemEdits.isHidden(row.id)) {
            if (ItemEdits.isUndoable(row.id)) wrap.addView(undoBar(row))
            statusLine(key)?.let { wrap.addView(it) }
            return wrap
        }
        val editable = ItemEdits.declared() && row.editable && row.id.isNotBlank()
        val deletable = ItemEdits.declared() && row.deletable && row.id.isNotBlank()
        if (editing == key && editable) {
            wrap.addView(editor(key, ItemEdits.maxText(row.target)) { typed -> ItemEdits.edit(ctx, row, typed) })
            return wrap
        }
        val shown = ItemEdits.shownText(row.id) ?: row.text
        val spokenRow = row.toBuilder().setText(shown).build()
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48f)
        }
        val words = LinearLayout(ctx).apply {
            tag = TAG_ROW_WORDS
            orientation = LinearLayout.VERTICAL
            setPadding(px(4f), px(6f), px(4f), px(6f))
        }
        val main = text(shown, 16f, rt.ink).apply { tag = TAG_ROW_TEXT }
        if (!checklist && row.label.isNotBlank()) words.addView(text(row.label, 13f, muted).apply { tag = TAG_ROW_LABEL })
        words.addView(main)
        if (!checklist) {
            val sub = listOf(row.detail, row.extra).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) words.addView(text(sub, 14f, muted).apply { tag = TAG_ROW_DETAIL })
        }
        fun look(checked: Boolean) {
            main.paintFlags = if (checked) main.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else main.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            main.setTextColor(if (checked) muted else rt.ink)
        }
        if (row.checkable && row.id.isNotBlank()) {
            val now = Checklists.shown(ctx, row.id, row.checked)
            look(now)
            line.addView(ChecklistView.bareBox(ctx, row.id, now, rt, onLook = ::look) { checked ->
                Haptics.ack(ctx)
                kept[row.id] = Kept(block, at, row)
                Checklists.tap(ctx, row.id, checked, !checked)
            }.apply { contentDescription = shown })
        } else if (!checklist) {
            icon(row.icon, row.iconTone, 24f)?.let { line.addView(it.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = px(12f) }) }
        }
        line.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // The words are one stop for a screen reader, with edit and delete as its actions.
        words.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        words.contentDescription = TileBlocks.spoken(spokenRow) +
            if (editable) ". " + ctx.getString(R.string.tile_edit_double_tap) else ""
        for (i in 0 until words.childCount) words.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (editable) {
            val open = { open(key, ItemEdits.draft(key) ?: ItemEdits.shownText(row.id) ?: row.editText) }
            words.isClickable = true; words.isFocusable = true
            words.setOnClickListener { open() }
            ViewCompat.addAccessibilityAction(words, ctx.getString(R.string.tile_edit_action)) { _, _ -> open(); true }
        } else {
            words.isFocusable = true
        }
        if (deletable) {
            line.addView(deleteButton(row, shown))
            ViewCompat.addAccessibilityAction(words, ctx.getString(R.string.tile_edit_delete)) { _, _ ->
                ItemEdits.deleteLater(ctx, row); true
            }
        }
        wrap.addView(line)
        statusLine(key)?.let { wrap.addView(it) }
        return wrap
    }

    private fun deleteButton(row: TileRow, shown: String) = FrameLayout(ctx).apply {
        tag = TAG_DELETE
        contentDescription = ctx.getString(R.string.tile_edit_delete_named, shown)
        isClickable = true; isFocusable = true
        setOnClickListener { Haptics.ack(ctx); ItemEdits.deleteLater(ctx, row) }
        val glyph = BoxIcons.named(ctx, "close", muted)
        if (glyph != null) addView(ImageView(ctx).apply {
            setImageDrawable(glyph)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER)
        }) else addView(text("×", 20f, muted).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        })
        layoutParams = LinearLayout.LayoutParams(px(48f), px(48f))
    }

    private fun undoBar(row: TileRow) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(48f)
        addView(text(ctx.getString(R.string.tile_edit_deleted), 15f, muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(px(4f), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(ctx.getString(R.string.tile_edit_undo), 14f, rt.ink).apply {
            tag = TAG_UNDO
            isAllCaps = true
            gravity = Gravity.CENTER
            minWidth = px(48f); minHeight = px(48f)
            setPadding(px(16f), 0, px(16f), 0)
            isClickable = true; isFocusable = true
            setOnClickListener { ItemEdits.undo(ctx, row.id) }
        })
    }

    /** A new item or note the backend has not yet shown in the list. */
    private fun placeholder(p: ItemEdits.Placeholder, checklist: Boolean): View {
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; tag = TAG_PLACEHOLDER }
        wrap.addView(text(p.text, 16f, muted).apply {
            minHeight = px(48f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (checklist) px(52f) else px(4f), 0, 0, 0)
        })
        statusLine(ItemEdits.placeholderKey(p.opId))?.let { wrap.addView(it) }
        return wrap
    }

    private fun addRow(addTo: String): View {
        val key = ItemEdits.addKey(addTo)
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (editing == key) {
            wrap.addView(editor(key, ItemEdits.maxText(addTo)) { typed -> ItemEdits.add(ctx, addTo, typed) })
        } else {
            wrap.addView(text("+  " + addLabel(addTo), 15f, rt.ink).apply {
                tag = TAG_ADD
                contentDescription = addLabel(addTo)
                minHeight = px(48f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(4f), 0, 0, 0)
                isClickable = true; isFocusable = true
                setOnClickListener { open(key, ItemEdits.draft(key).orEmpty()) }
            })
        }
        statusLine(key)?.let { wrap.addView(it) }
        return wrap
    }

    private fun addLabel(addTo: String): String = ctx.getString(when (addTo) {
        "todo" -> R.string.tile_add_todo
        "shopping" -> R.string.tile_add_shopping
        "notes" -> R.string.tile_add_notes
        else -> R.string.tile_add_other
    })

    // ---- the editor ----

    /** Opens [key] for editing with [start]; the caller redraws. */
    private var redraw: () -> Unit = {}

    fun onChange(r: () -> Unit) { redraw = r }

    private fun open(key: String, start: String) {
        editing = key
        draft = start
        focusNext = true
        redraw()
    }

    private fun close() {
        editing = null
        draft = ""
        redraw()
    }

    /** An editor: the text, the tick that saves it (only then is anything sent), and Cancel. */
    private fun editor(key: String, max: Int, save: (String) -> Boolean): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; tag = TAG_EDITOR }
        val edit = EditText(ctx).apply {
            tag = TAG_EDIT_TEXT
            setText(draft)
            typeface = tf
            setTextColor(rt.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemePaint.scaledSp(rt, 16f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            filters = arrayOf(InputFilter.LengthFilter(max))
            backgroundTintList = ColorStateList.valueOf(rt.ink)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (editing == key) draft = s?.toString().orEmpty() }
            })
        }
        box.addView(edit)
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(CheckBox(ctx).apply {
            tag = TAG_SAVE
            text = ctx.getString(R.string.tile_edit_save)
            typeface = tf
            setTextColor(rt.ink)
            buttonTintList = ColorStateList.valueOf(rt.ink)
            minHeight = px(48f)
            setOnCheckedChangeListener { b, now ->
                if (!now) return@setOnCheckedChangeListener
                val typed = edit.text.toString()
                if (typed.isBlank()) { b.isChecked = false; return@setOnCheckedChangeListener }
                Haptics.ack(ctx)
                hideKeyboard(edit)
                save(typed)
                close()
            }
        })
        buttons.addView(text(ctx.getString(R.string.tile_edit_cancel), 12f, muted).apply {
            tag = TAG_CANCEL
            isAllCaps = true
            setPadding(px(24f), px(12f), px(16f), px(12f))
            isClickable = true; isFocusable = true
            setOnClickListener { hideKeyboard(edit); close() }
        })
        box.addView(buttons)
        if (focusNext) {
            focusNext = false
            edit.requestFocus()
            edit.setSelection(edit.text.length)
            edit.post { runCatching { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) } }
        }
        return box
    }

    private fun hideKeyboard(v: View) {
        runCatching { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0) }
    }

    // ---- read-only kinds ----

    /** One column per day, scrolling sideways when it does not fit; nothing is cut short. */
    private fun forecast(rows: List<TileRow>): View {
        val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (r in rows) {
            val col = LinearLayout(ctx).apply {
                tag = TAG_FORECAST_DAY
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                minimumWidth = px(64f)
                setPadding(px(8f), px(6f), px(8f), px(6f))
                isFocusable = true
                contentDescription = TileBlocks.spoken(r)
            }
            fun add(v: View) { v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; col.addView(v) }
            if (r.label.isNotBlank()) add(text(r.label, 13f, muted).apply { gravity = Gravity.CENTER })
            icon(r.icon, r.iconTone, 28f)?.let { add(it.apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, px(4f), 0, px(4f)) }) }
            if (r.text.isNotBlank()) add(text(r.text, 16f, rt.ink).apply { gravity = Gravity.CENTER })
            if (r.detail.isNotBlank()) add(text(r.detail, 14f, muted).apply { gravity = Gravity.CENTER })
            if (r.extra.isNotBlank()) add(text(r.extra, 13f, muted).apply { gravity = Gravity.CENTER })
            strip.addView(col)
        }
        return HorizontalScrollView(ctx).apply {
            tag = TAG_FORECAST
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    /** One large value with its caption; it wraps rather than clips. */
    private fun stat(r: TileRow): View {
        val line = LinearLayout(ctx).apply {
            tag = TAG_STAT
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            contentDescription = TileBlocks.spoken(r)
            setPadding(px(4f), px(4f), 0, px(4f))
        }
        icon(r.icon, r.iconTone, 32f)?.let { line.addView(it.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = px(12f) }) }
        val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        words.addView(text(r.text, 34f, rt.ink).apply { tag = TAG_STAT_VALUE })
        if (r.label.isNotBlank()) words.addView(text(r.label, 13f, muted))
        line.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return line
    }

    private fun table(columns: List<String>, rows: List<TileRow>): View {
        val t = TableLayout(ctx).apply { tag = TAG_TABLE; isStretchAllColumns = true; isShrinkAllColumns = true }
        val width = maxOf(columns.size, rows.maxOfOrNull { it.cellsCount } ?: 0)
        fun cell(s: String, colour: Int, header: Boolean) = text(s, if (header) 13f else 15f, colour).apply {
            setPadding(px(4f), px(6f), px(8f), px(6f))
            if (header) isAllCaps = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (columns.isNotEmpty()) t.addView(TableRow(ctx).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            for (i in 0 until width) addView(cell(columns.getOrElse(i) { "" }, muted, true))
        })
        for (r in rows) t.addView(TableRow(ctx).apply {
            tag = TAG_TABLE_ROW
            isFocusable = true
            minimumHeight = px(40f)
            contentDescription = TileBlocks.spokenTableRow(columns, r)
            for (i in 0 until width) addView(cell(r.cellsList.getOrElse(i) { "" }, rt.ink, false))
        })
        return t
    }

    private fun progress(r: TileRow): View {
        val box = LinearLayout(ctx).apply {
            tag = TAG_PROGRESS
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            contentDescription = TileBlocks.spoken(r)
            setPadding(px(4f), px(4f), 0, px(6f))
        }
        if (r.label.isNotBlank()) box.addView(text(r.label, 14f, rt.ink).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val f = TileBlocks.fraction(r)
        if (f != null) line.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = TAG_PROGRESS_BAR
            max = 1000
            progress = (f * 1000).toInt()
            progressTintList = ColorStateList.valueOf(TileTones.colour(rt, r.iconTone.ifBlank { "accent" }, rt.ground))
            progressBackgroundTintList = ColorStateList.valueOf(rt.tileBorder)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, px(8f), 1f))
        if (r.text.isNotBlank()) line.addView(text(r.text, 14f, muted).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(px(12f), 0, 0, 0)
        })
        box.addView(line)
        return box
    }

    companion object {
        const val TAG_BLOCK_PREFIX = "tile-block-"
        const val TAG_ROW = "tile-row"
        const val TAG_ROW_TEXT = "tile-row-text"
        const val TAG_ROW_WORDS = "tile-row-words"
        const val TAG_ROW_LABEL = "tile-row-label"
        const val TAG_ROW_DETAIL = "tile-row-detail"
        const val TAG_ICON = "tile-row-icon"
        const val TAG_STATUS = "tile-row-status"
        const val TAG_DELETE = "tile-row-delete"
        const val TAG_UNDO = "tile-row-undo"
        const val TAG_EDITOR = "tile-row-editor"
        const val TAG_EDIT_TEXT = "tile-row-edit-text"
        const val TAG_SAVE = "tile-row-save"
        const val TAG_CANCEL = "tile-row-cancel"
        const val TAG_ADD = "tile-add"
        const val TAG_PLACEHOLDER = "tile-placeholder"
        const val TAG_EMPTY = "tile-empty"
        const val TAG_TEXT = "tile-text"
        const val TAG_FALLBACK = "tile-fallback"
        const val TAG_FORECAST = "tile-forecast"
        const val TAG_FORECAST_DAY = "tile-forecast-day"
        const val TAG_STAT = "tile-stat"
        const val TAG_STAT_VALUE = "tile-stat-value"
        const val TAG_TABLE = "tile-table"
        const val TAG_TABLE_ROW = "tile-table-row"
        const val TAG_PROGRESS = "tile-progress"
        const val TAG_PROGRESS_BAR = "tile-progress-bar"
    }
}
