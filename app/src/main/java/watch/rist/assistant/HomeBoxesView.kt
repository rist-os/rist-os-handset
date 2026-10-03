package watch.rist.assistant

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rist.v1.HomeBox

/**
 * Draws the boxes, on the home row (one line that scrolls sideways) and in the All boxes grid
 * (three across, scrolling down), and handles what a touch on them does. Both share one order.
 *
 * Every colour and font comes from the active theme. A long press enters edit mode: boxes can
 * then be dragged, removed (with a five-second undo) or edited. Each box also carries move,
 * edit and delete as accessibility actions, so nothing needs a drag.
 */
internal class BoxBoard(
    private val activity: AppCompatActivity,
    private val list: RecyclerView,
    private val grid: Boolean,
    private val host: Host,
    private val undoBar: TextView? = null,
    private val handle: View? = null,
) {

    interface Host {
        /** A box wants a turn sent: a command-box tap, or the add or change sheet. */
        fun onTurn(turn: HomeBoxes.Turn)
        /** The row's "All" tile. */
        fun onAll() {}
        fun onEditModeChanged(on: Boolean) {}
    }

    var editMode = false
        private set

    private val d = activity.resources.displayMetrics.density
    private fun px(v: Float): Int = (v * d).toInt()
    private val pixelTf: Typeface? by lazy {
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.pixel) }.getOrNull()
    }

    private val adapter = Adapter()
    private val undoHandler = Handler(Looper.getMainLooper())

    init {
        list.layoutManager = if (grid) GridLayoutManager(activity, COLUMNS)
        else LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        list.adapter = adapter
        list.itemAnimator = null
        ItemTouchHelper(DragCallback()).attachToRecyclerView(list)
    }

    fun setEditMode(on: Boolean) {
        if (editMode == on) return
        editMode = on
        render()
        host.onEditModeChanged(on)
    }

    /** Redraws from the held list: after any change, a theme change, or a minute passing. */
    @SuppressLint("NotifyDataSetChanged")
    fun render() {
        val boxes = HomeBoxes.boxes(activity)
        adapter.items = buildList {
            boxes.forEach { add(Item.Box(it)) }
            when {
                editMode -> add(Item.Done)
                grid -> add(Item.Add)
                boxes.isEmpty() -> add(Item.Empty)
                else -> { add(Item.Add); add(Item.All(boxes.size)) }
            }
        }
        adapter.notifyDataSetChanged()
        val t = theme()
        handle?.findViewWithTag<View>(HANDLE_BAR_TAG)?.background = GradientDrawable().apply {
            setColor(t.tileBorder); cornerRadius = px(2f).toFloat()
        }
        undoBar?.apply { setTextColor(t.accent); typeface = pixelTf }
    }

    private fun theme(): RistTheme = Themes.byId(Config.themeId(activity))

    // ---- what a touch does ----

    fun tap(b: HomeBox) {
        if (editMode) return
        when (HomeBoxes.kindOf(b)) {
            HomeBoxes.Kind.COMMAND -> HomeBoxes.commandTurn(b)?.let { host.onTurn(it) }
            // A display box only opens; it never sends anything.
            HomeBoxes.Kind.DISPLAY -> activity.startActivity(BoxExpandedActivity.intent(activity, b.id))
        }
    }

    fun openAddSheet() = BoxSheet.showAdd(activity) { host.onTurn(it) }

    fun openEditSheet(b: HomeBox) = BoxSheet.showEdit(
        activity, b,
        onRename = { title -> commit(HomeBoxes.renameEdit(activity, b.id, title)) },
        onTurn = { host.onTurn(it) },
    )

    fun delete(b: HomeBox) {
        val before = HomeBoxes.boxes(activity).map { it.id }
        commit(HomeBoxes.deleteEdit(activity, b.id))
        Haptics.ack(activity)
        showUndo(b.id, before)
    }

    fun move(b: HomeBox, by: Int) {
        val ids = HomeBoxes.boxes(activity).map { it.id }.toMutableList()
        val from = ids.indexOf(b.id)
        val to = (from + by).coerceIn(0, ids.size - 1)
        if (from < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        commit(HomeBoxes.reorderEdit(activity, ids))
    }

    private fun commit(edit: rist.v1.BoxEdit) {
        HomeBoxes.edit(activity, edit)
        HomeBoxes.flushSoon(activity)
        render()
    }

    private var undoing: Runnable? = null

    private fun showUndo(id: String, previousOrder: List<String>) {
        val bar = undoBar ?: return
        undoing?.let { undoHandler.removeCallbacks(it) }
        bar.text = activity.getString(R.string.boxes_removed_undo)
        bar.contentDescription = activity.getString(R.string.boxes_removed_undo_desc)
        bar.visibility = View.VISIBLE
        handle?.visibility = View.GONE
        bar.setOnClickListener {
            hideUndo()
            commit(HomeBoxes.restoreEdit(activity, id, previousOrder))
        }
        val hide = Runnable { hideUndo() }
        undoing = hide
        undoHandler.postDelayed(hide, HomeBoxes.UNDO_MS)
    }

    private fun hideUndo() {
        undoing?.let { undoHandler.removeCallbacks(it) }
        undoing = null
        undoBar?.visibility = View.GONE
        handle?.visibility = View.VISIBLE
    }

    // ---- items ----

    private sealed class Item {
        data class Box(val box: HomeBox) : Item()
        object Add : Item()
        data class All(val count: Int) : Item()
        object Done : Item()
        object Empty : Item()
    }

    private class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        val actions = ArrayList<Int>()
        var boxId: String = ""
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<Item> = emptyList()

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            is Item.Box -> TYPE_BOX
            else -> TYPE_OTHER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(activity))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val frame = h.frame
            frame.removeAllViews()
            frame.background = null
            frame.foreground = null
            frame.setOnClickListener(null)
            frame.setOnLongClickListener(null)
            frame.isClickable = false
            frame.isLongClickable = false
            frame.contentDescription = null
            frame.tag = null
            h.actions.forEach { ViewCompat.removeAccessibilityAction(frame, it) }
            h.actions.clear()
            h.boxId = ""
            val item = items[position]
            val wide = item is Item.Empty
            frame.layoutParams = RecyclerView.LayoutParams(
                when {
                    grid || wide -> ViewGroup.LayoutParams.MATCH_PARENT
                    item is Item.All -> px(ALL_W_DP)
                    else -> px(TILE_W_DP)
                },
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (grid) setMargins(px(5f), px(5f), px(5f), px(5f))
                else marginEnd = px(10f)
            }
            frame.minimumHeight = px(if (grid) GRID_H_DP else TILE_H_DP)
            when (item) {
                is Item.Box -> bindBox(h, item.box)
                Item.Add -> bindAdd(frame)
                is Item.All -> bindAll(frame, item.count)
                Item.Done -> bindDone(frame)
                Item.Empty -> bindEmpty(frame)
            }
        }
    }

    private fun tileBackground(fill: Int, stroke: Int, strokeDp: Float, dashed: Boolean = false): Drawable =
        GradientDrawable().apply {
            setColor(fill)
            val w = px(strokeDp).coerceAtLeast(1)
            if (dashed) setStroke(w, stroke, px(6f).toFloat(), px(4f).toFloat()) else setStroke(w, stroke)
            cornerRadius = px(16f).toFloat()
        }

    private fun label(text: String, colour: Int) = TextView(activity).apply {
        this.text = text
        isAllCaps = true
        typeface = pixelTf
        letterSpacing = 0.08f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTextColor(colour)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun small(text: String, colour: Int, tf: Typeface?) = TextView(activity).apply {
        this.text = text
        typeface = tf
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(colour)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun glyph(res: Int, colour: Int, sizeDp: Float) = ImageView(activity).apply {
        setImageDrawable(ContextCompat.getDrawable(activity, res)?.mutate())
        setColorFilter(colour)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp))
    }

    /** The box's icon, ~20dp, in [colour]; null when it has none. */
    private fun boxIcon(b: HomeBox, colour: Int): View? {
        val dr = BoxIcons.drawable(activity, b, colour) ?: return null
        return ImageView(activity).apply {
            tag = ICON_TAG
            setImageDrawable(dr)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(px(ICON_DP), px(ICON_DP)).apply { marginEnd = px(6f) }
        }
    }

    private fun column(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(12f), px(10f), px(12f), px(10f))
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun spacer() = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    }

    private fun bindBox(h: Holder, b: HomeBox) {
        val frame = h.frame
        h.boxId = b.id
        frame.tag = TILE_TAG_PREFIX + b.id
        val t = theme()
        val tf = ThemePaint.typefaceOf(activity, t)
        val muted = Themes.readableMuted(t)
        val nowS = System.currentTimeMillis() / 1000
        val face = HomeBoxes.face(b, nowS, sending = HomeBoxes.isSending(b.id))
        val col = column()
        if (face.kind == HomeBoxes.Kind.COMMAND) {
            val ink = if (face.sending) t.accent else onAccent(t)
            frame.background = if (face.sending) tileBackground(t.tileFill, t.accent, 2.5f)
            else tileBackground(t.accent, t.accent, 1.5f)
            // The box's own icon top left; the arrow (or the sending arc) beside it, top right.
            val top = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            boxIcon(b, ink)?.let { top.addView(it); top.addView(spacer()) }
            top.addView(glyph(if (face.sending) R.drawable.ic_box_sending else R.drawable.ic_box_arrow, ink, 18f))
            col.addView(top)
            col.addView(spacer())
            col.addView(TextView(activity).apply {
                text = face.value
                tag = VALUE_TAG
                typeface = Typeface.create(tf, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (grid) 14f else 15f)
                setTextColor(ink)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
            if (face.detail.isNotBlank()) col.addView(small(face.detail, ink, tf).apply { tag = DETAIL_TAG })
        } else {
            frame.background = tileBackground(t.tileFill, t.tileBorder, 1.5f)
            val top = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            boxIcon(b, t.ink)?.let { top.addView(it) }
            top.addView(label(face.label, muted).apply { tag = LABEL_TAG })
            col.addView(top)
            col.addView(spacer())
            col.addView(TextView(activity).apply {
                text = face.value
                tag = VALUE_TAG
                typeface = Typeface.create(tf, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSp(face.value))
                setTextColor(t.ink)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            })
            col.addView(spacer())
            if (face.detail.isNotBlank()) col.addView(small(face.detail, muted, tf).apply { tag = DETAIL_TAG })
            // Dimmed as well as reworded, so the state never rests on colour alone.
            col.alpha = if (face.dimmed) DIM_ALPHA else 1f
        }
        frame.addView(col)
        frame.contentDescription = face.description
        frame.isClickable = true
        frame.isFocusable = true
        frame.setOnClickListener { tap(b) }
        frame.isLongClickable = true
        frame.setOnLongClickListener {
            if (!editMode) { Haptics.ack(activity); setEditMode(true); true } else false
        }
        if (editMode) addEditControls(frame, b, t)
        val order = HomeBoxes.boxes(activity).map { it.id }
        val at = order.indexOf(b.id)
        fun action(label: Int, run: () -> Unit) {
            h.actions += ViewCompat.addAccessibilityAction(frame, activity.getString(label)) { _, _ -> run(); true }
        }
        if (at > 0) action(if (grid) R.string.boxes_move_earlier else R.string.boxes_move_left) { move(b, -1) }
        if (at in 0 until order.size - 1) action(if (grid) R.string.boxes_move_later else R.string.boxes_move_right) { move(b, +1) }
        action(R.string.boxes_edit) { openEditSheet(b) }
        action(R.string.boxes_delete) { delete(b) }
    }

    private fun addEditControls(frame: FrameLayout, b: HomeBox, t: RistTheme) {
        frame.foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(px(2f), t.accent, px(5f).toFloat(), px(3f).toFloat())
            cornerRadius = px(16f).toFloat()
        }
        fun corner(res: Int, desc: Int, tag: String, gravity: Int, act: () -> Unit) = FrameLayout(activity).apply {
            this.tag = tag
            contentDescription = activity.getString(desc)
            isClickable = true; isFocusable = true
            setOnClickListener { act() }
            layoutParams = FrameLayout.LayoutParams(px(48f), px(48f), gravity)
            addView(FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(t.ground); setStroke(px(1.5f), t.accent)
                }
                layoutParams = FrameLayout.LayoutParams(px(26f), px(26f), Gravity.CENTER)
                addView(ImageView(activity).apply {
                    setImageDrawable(ContextCompat.getDrawable(activity, res)?.mutate())
                    setColorFilter(t.accent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = FrameLayout.LayoutParams(px(14f), px(14f), Gravity.CENTER)
                })
            })
        }
        frame.addView(corner(R.drawable.ic_box_x, R.string.boxes_delete, DELETE_TAG, Gravity.TOP or Gravity.START) { delete(b) })
        frame.addView(corner(R.drawable.ic_box_edit, R.string.boxes_edit, EDIT_TAG, Gravity.TOP or Gravity.END) { openEditSheet(b) })
    }

    private fun bindAdd(frame: FrameLayout) {
        val t = theme()
        frame.tag = ADD_TAG
        frame.background = tileBackground(Color.TRANSPARENT, t.accent, 1.5f, dashed = true)
        frame.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(glyph(R.drawable.ic_box_plus, t.accent, 26f))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.boxes_add)
                setTextColor(t.accent)
                typeface = Typeface.create(ThemePaint.typefaceOf(activity, t), Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, px(6f), 0, 0)
            })
        })
        frame.contentDescription = activity.getString(R.string.boxes_add)
        frame.isClickable = true; frame.isFocusable = true
        frame.setOnClickListener { openAddSheet() }
    }

    private fun bindAll(frame: FrameLayout, count: Int) {
        val t = theme()
        frame.tag = ALL_TAG
        frame.background = tileBackground(Color.TRANSPARENT, t.tileBorder, 1.5f)
        frame.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(label(activity.getString(R.string.boxes_all_short), t.ink).apply { gravity = Gravity.CENTER })
            addView(small(count.toString(), Themes.readableMuted(t), ThemePaint.typefaceOf(activity, t)).apply {
                gravity = Gravity.CENTER
            })
        })
        frame.contentDescription = activity.resources.getQuantityString(R.plurals.boxes_all_desc, count, count)
        frame.isClickable = true; frame.isFocusable = true
        frame.setOnClickListener { host.onAll() }
    }

    private fun bindDone(frame: FrameLayout) {
        val t = theme()
        frame.tag = DONE_TAG
        frame.background = tileBackground(t.accent, t.accent, 1.5f)
        frame.addView(TextView(activity).apply {
            text = activity.getString(R.string.boxes_done)
            gravity = Gravity.CENTER
            typeface = pixelTf
            isAllCaps = true
            setTextColor(onAccent(t))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })
        frame.contentDescription = activity.getString(R.string.boxes_done_desc)
        frame.isClickable = true; frame.isFocusable = true
        frame.setOnClickListener { setEditMode(false) }
    }

    private fun bindEmpty(frame: FrameLayout) {
        val t = theme()
        val tf = ThemePaint.typefaceOf(activity, t)
        frame.tag = ADD_TAG
        frame.background = tileBackground(Color.TRANSPARENT, t.accent, 1.5f, dashed = true)
        val col = column().apply { setPadding(px(16f), px(12f), px(16f), px(12f)) }
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(activity).apply {
            text = activity.getString(R.string.boxes_add)
            typeface = Typeface.create(tf, Typeface.BOLD)
            setTextColor(t.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(glyph(R.drawable.ic_box_plus, t.accent, 22f))
        col.addView(top)
        col.addView(small(activity.getString(R.string.boxes_empty_hint), Themes.readableMuted(t), tf).apply {
            maxLines = 3
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, px(4f), 0, 0)
        })
        frame.addView(col)
        frame.contentDescription = activity.getString(R.string.boxes_add) + ". " +
            activity.getString(R.string.boxes_empty_hint)
        frame.isClickable = true; frame.isFocusable = true
        frame.setOnClickListener { openAddSheet() }
    }

    /** Words on an accent fill: the theme's ground where it reads, else whichever of black or white does. */
    private fun onAccent(t: RistTheme): Int = when {
        contrast(t.ground, t.accent) >= 3.0 -> t.ground
        contrast(Color.WHITE, t.accent) >= contrast(Color.BLACK, t.accent) -> Color.WHITE
        else -> Color.BLACK
    }

    // ---- dragging ----

    private inner class DragCallback : ItemTouchHelper.Callback() {
        private var moved = false

        override fun isLongPressDragEnabled() = editMode
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            if (!editMode || vh.itemViewType != TYPE_BOX) return 0
            val dirs = if (grid) ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            else ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            return makeMovementFlags(dirs, 0)
        }

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            if (target.itemViewType != TYPE_BOX) return false
            val from = vh.adapterPosition
            val to = target.adapterPosition
            if (from < 0 || to < 0) return false
            val items = adapter.items.toMutableList()
            items.add(to, items.removeAt(from))
            adapter.items = items
            adapter.notifyItemMoved(from, to)
            moved = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            if (!moved) return
            moved = false
            val order = adapter.items.filterIsInstance<Item.Box>().map { it.box.id }
            // Posted: committing redraws the list, which must not happen inside the drag's teardown.
            list.post { commit(HomeBoxes.reorderEdit(activity, order)) }
        }
    }

    companion object {
        const val COLUMNS = 3
        private const val TYPE_BOX = 1
        private const val TYPE_OTHER = 2
        const val TILE_W_DP = 112f
        const val TILE_H_DP = 92f
        const val GRID_H_DP = 104f
        const val ALL_W_DP = 64f
        const val DIM_ALPHA = 0.62f

        const val TILE_TAG_PREFIX = "box:"
        const val LABEL_TAG = "box-label"
        const val VALUE_TAG = "box-value"
        const val DETAIL_TAG = "box-detail"
        const val ADD_TAG = "box-add"
        const val ALL_TAG = "box-all"
        const val DONE_TAG = "box-done"
        const val DELETE_TAG = "box-delete"
        const val EDIT_TAG = "box-edit"
        const val HANDLE_BAR_TAG = "box-handle-bar"

        const val ICON_TAG = "box-icon"
        const val ICON_DP = 20f

        /** A short glance value is drawn large; a longer one smaller, so it still fits. */
        fun valueSp(value: String): Float = when {
            value.length <= 5 -> 28f
            value.length <= 8 -> 22f
            else -> 18f
        }
    }
}
