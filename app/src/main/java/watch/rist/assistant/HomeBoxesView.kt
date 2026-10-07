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
 *
 * The first tile is always Notifications: built into the phone, not one of the backend's boxes.
 * It shows how many calls, texts and notices are waiting, the same count the feed's heading
 * shows, and opens the feed full screen. It cannot be moved, edited or deleted, and since it is
 * never in the held list it is never part of an edit sent to the backend.
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
        BoxRefresh.observe(boxes)
        BoxCreate.observe(boxes)
        val creating = BoxCreate.waiting()
        adapter.items = buildList {
            add(Item.Notifications(listedNow(), waitingNow()))
            boxes.forEach { add(Item.Box(it)) }
            creating.forEach { add(Item.Creating(it)) }
            when {
                editMode -> add(Item.Done)
                grid -> add(Item.Add)
                boxes.isEmpty() && creating.isEmpty() -> add(Item.Empty)
                else -> { add(Item.Add); add(Item.All(boxes.size)) }
            }
        }
        adapter.notifyDataSetChanged()
        val t = theme()
        handle?.findViewWithTag<View>(HANDLE_BAR_TAG)?.background = GradientDrawable().apply {
            setColor(t.tileBorder); cornerRadius = px(2f).toFloat()
        }
        undoBar?.apply { setTextColor(ThemePaint.accentTextOn(t, t.ground)); typeface = pixelTf }
    }

    private fun theme(): RistTheme = Themes.current(activity)

    private fun waitingNow(): Int = runCatching { CommsFeedView.waitingCount(activity) }.getOrDefault(0)

    private fun listedNow(): Int = runCatching { CommsFeedView.listedCount(activity) }.getOrDefault(0)

    /** The feed was just drawn listing [listed], [waiting] of them new: the tile follows at once. */
    fun showWaiting(waiting: Int, listed: Int) {
        val first = adapter.items.firstOrNull() as? Item.Notifications ?: return
        val now = Item.Notifications(listed, waiting)
        if (first == now) return
        adapter.items = listOf(now) + adapter.items.drop(1)
        adapter.notifyItemChanged(0)
    }

    fun openNotifications() {
        if (editMode) return
        activity.startActivity(NotificationsActivity.intent(activity))
    }

    // ---- what a touch does ----

    fun tap(b: HomeBox) {
        if (editMode) return
        when (HomeBoxes.kindOf(b)) {
            HomeBoxes.Kind.COMMAND -> HomeBoxes.commandTurn(b)?.let { host.onTurn(it) }
            // A display box only opens; it never sends anything.
            HomeBoxes.Kind.DISPLAY -> activity.startActivity(BoxExpandedActivity.intent(activity, b.id))
        }
    }

    fun openAddSheet() = BoxSheet.showAdd(activity, onAddCommand = { addCommand(it) }) { host.onTurn(it) }

    /** A one-tap tile: a touch edit, no turn; a placeholder stands in until the backend's list has it. */
    fun addCommand(words: String) {
        val edit = HomeBoxes.addEdit(activity, words)
        BoxCreate.startAdd(activity, words, edit.editId)
        commit(edit)
    }

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
        data class Notifications(val count: Int, val unread: Int) : Item()
        data class Box(val box: HomeBox) : Item()
        data class Creating(val pending: BoxCreate.Pending) : Item()
        object Add : Item()
        data class All(val count: Int) : Item()
        object Done : Item()
        object Empty : Item()
    }

    private class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        val actions = ArrayList<Int>()
        var boxId: String = ""
        var spin: android.animation.ObjectAnimator? = null
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<Item> = emptyList()

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            is Item.Box -> TYPE_BOX
            else -> TYPE_OTHER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(activity))

        override fun onViewRecycled(h: Holder) { h.spin?.cancel(); h.spin = null }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val frame = h.frame
            h.spin?.cancel(); h.spin = null
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
            frame.layoutParams = RecyclerView.LayoutParams(
                when {
                    grid -> ViewGroup.LayoutParams.MATCH_PARENT
                    item is Item.All -> px(ALL_W_DP)
                    else -> px(TILE_DP)
                },
                px(if (grid) GRID_H_DP else TILE_DP),
            ).apply {
                if (grid) setMargins(px(5f), px(5f), px(5f), px(5f))
                else marginEnd = px(10f)
            }
            when (item) {
                is Item.Notifications -> bindNotifications(frame, item.count, item.unread)
                is Item.Box -> bindBox(h, item.box)
                is Item.Creating -> bindCreating(h, item.pending)
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

    /**
     * A tile's label: one line that shrinks to fit (10 sp down to [LABEL_MIN_SP]) rather than
     * being cut to "WEATH…". Only a label still too long at the smallest size is ellipsized.
     * Shrinking needs a bounded width: in a row, the label takes the room left beside the icon.
     */
    private fun label(text: String, colour: Int) = TextView(activity).apply {
        this.text = text
        isAllCaps = true
        typeface = pixelTf
        letterSpacing = 0f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTextColor(colour)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setAutoSizeTextTypeUniformWithConfiguration(LABEL_MIN_SP, 10, 1, TypedValue.COMPLEX_UNIT_SP)
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

    /**
     * The glance value: it takes whatever height the label and the detail leave, and fits it, so
     * a long value or large system text never pushes the detail out of the square.
     */
    private fun value(text: String, maxSp: Float, colour: Int, tf: Typeface?, lines: Int, gravity: Int) =
        FitText(activity, sp(maxSp), sp(MIN_VALUE_SP), lines).apply {
            this.text = text
            tag = VALUE_TAG
            typeface = Typeface.create(tf, Typeface.BOLD)
            setTextColor(colour)
            ellipsize = TextUtils.TruncateAt.END
            this.gravity = gravity
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, activity.resources.displayMetrics)

    /** Two lines of detail fit under the value at ordinary text sizes; with large text, one. */
    private fun detailLines(): Int = if (activity.resources.configuration.fontScale >= LARGE_TEXT) 1 else 2

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
            val ink = if (face.sending) ThemePaint.accentTextOn(t, t.tileFill) else onAccent(t)
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
            col.addView(value(face.value, if (grid) 14f else 15f, ink, tf, 3, Gravity.BOTTOM or Gravity.START))
            if (face.detail.isNotBlank()) col.addView(small(face.detail, ink, tf).apply { tag = DETAIL_TAG; maxLines = detailLines() })
        } else {
            frame.background = tileBackground(t.tileFill, t.tileBorder, 1.5f)
            val top = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            boxIcon(b, t.ink)?.let { top.addView(it) }
            top.addView(label(face.label, muted).apply {
                tag = LABEL_TAG
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            col.addView(top)
            col.addView(value(face.value, valueSp(face.value), t.ink, tf, 2, Gravity.CENTER_VERTICAL or Gravity.START))
            if (face.detail.isNotBlank()) col.addView(small(face.detail, muted, tf).apply { tag = DETAIL_TAG; maxLines = detailLines() })
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

    /**
     * A tile asked for and not yet made: the request's first words under "New tile", and a spinner,
     * or with animations off the word "Creating…". A tap says it is still being made; a long press
     * removes it from the phone only.
     */
    private fun bindCreating(h: Holder, p: BoxCreate.Pending) {
        val frame = h.frame
        frame.tag = CREATING_TAG
        val t = theme()
        val tf = ThemePaint.typefaceOf(activity, t)
        val muted = Themes.readableMuted(t)
        frame.background = tileBackground(t.tileFill, t.accent, 1.5f, dashed = true)
        val col = column()
        col.addView(label(activity.getString(R.string.boxes_create_label), muted).apply { tag = LABEL_TAG })
        if (BoxRefresh.motion()) {
            // Fills the space between label and detail; the arc keeps its own size, centred, and
            // only shrinks when large text leaves less room than that.
            val arc = ImageView(activity).apply {
                tag = SPINNER_TAG
                setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_box_sending)?.mutate())
                setColorFilter(t.accent)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            col.addView(arc)
            h.spin = spinWhileAttached(arc) { h.spin }
        } else {
            col.addView(value(activity.getString(R.string.boxes_creating), 15f, t.ink, tf, 2,
                Gravity.CENTER_VERTICAL or Gravity.START))
        }
        val detail = BoxCreate.detailOf(p.words)
        if (detail.isNotBlank()) col.addView(small(detail, muted, tf).apply { tag = DETAIL_TAG; maxLines = detailLines() })
        frame.addView(col)
        frame.contentDescription = activity.getString(R.string.boxes_creating_desc, p.words)
        frame.isClickable = true; frame.isFocusable = true
        frame.setOnClickListener {
            android.widget.Toast.makeText(activity, R.string.boxes_create_still, android.widget.Toast.LENGTH_SHORT).show()
        }
        frame.isLongClickable = true
        frame.setOnLongClickListener { Haptics.ack(activity); BoxCreate.cancel(p); render(); true }
        h.actions += ViewCompat.addAccessibilityAction(frame, activity.getString(R.string.boxes_create_cancel)) { _, _ ->
            BoxCreate.cancel(p); render(); true
        }
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

    /** Drawn like a display box: label, the count large, and a word under it. */
    private fun bindNotifications(frame: FrameLayout, count: Int, unread: Int) {
        val t = theme()
        val tf = ThemePaint.typefaceOf(activity, t)
        val muted = Themes.readableMuted(t)
        frame.tag = NOTIFICATIONS_TAG
        frame.background = tileBackground(t.tileFill, if (unread > 0) t.accent else t.tileBorder, 1.5f)
        val col = column()
        // The longest built-in label: shrink it to fit rather than cut it to "NOTIFICATI…".
        col.addView(label(activity.getString(R.string.notifications_title), muted).apply {
            tag = LABEL_TAG
        })
        col.addView(value(count.toString(), valueSp(count.toString()), if (count > 0) t.ink else muted, tf, 1,
            Gravity.CENTER_VERTICAL or Gravity.START))
        col.addView(small(if (unread > 0) activity.getString(R.string.notifications_new, unread)
            else activity.getString(R.string.notifications_none_new),
            muted, tf).apply { tag = DETAIL_TAG; maxLines = 1 })
        frame.addView(col)
        frame.contentDescription = activity.resources.getQuantityString(R.plurals.notifications_tile_desc, count, count)
        frame.isClickable = true; frame.isFocusable = true
        frame.setOnClickListener { openNotifications() }
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
                setTextColor(ThemePaint.accentTextOn(t, t.ground))
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

    /** No boxes yet: the Add square, beside the Notifications tile, with a hint for a screen reader. */
    private fun bindEmpty(frame: FrameLayout) {
        bindAdd(frame)
        frame.contentDescription = activity.getString(R.string.boxes_add) + ". " +
            activity.getString(R.string.boxes_empty_hint)
    }

    private fun onAccent(t: RistTheme): Int = ThemePaint.onAccent(t)

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
        const val TILE_DP = 104f
        const val GRID_H_DP = 104f
        const val ALL_W_DP = 64f
        const val DIM_ALPHA = 0.62f
        const val MIN_VALUE_SP = 12f
        // The smallest a tile label shrinks to; the pixel face is still legible there.
        const val LABEL_MIN_SP = 6
        const val LARGE_TEXT = 1.3f

        const val TILE_TAG_PREFIX = "box:"
        const val NOTIFICATIONS_TAG = "box-notifications"
        const val LABEL_TAG = "box-label"
        const val VALUE_TAG = "box-value"
        const val DETAIL_TAG = "box-detail"
        const val ADD_TAG = "box-add"
        const val CREATING_TAG = "box-creating"
        const val SPINNER_TAG = "box-spinner"
        const val SPIN_MS = 900L

        /**
         * Turns [arc] endlessly, but only while it is on screen: an endless animator left running
         * after its window goes holds the activity and asks for every frame. [current] is the
         * holder's animator now; one replaced by a rebind does not start again. The phase follows
         * the clock, so a redraw (each list change rebinds) does not snap the arc back to the top.
         */
        internal fun spinWhileAttached(arc: View, current: () -> android.animation.Animator?): android.animation.ObjectAnimator {
            val spin = android.animation.ObjectAnimator.ofFloat(arc, View.ROTATION, 0f, 360f).apply {
                duration = SPIN_MS
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
            }
            fun go() {
                if (spin.isStarted || current() !== spin) return
                spin.start()
                spin.currentPlayTime = android.os.SystemClock.uptimeMillis() % SPIN_MS
            }
            arc.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = go()
                override fun onViewDetachedFromWindow(v: View) = spin.cancel()
            })
            if (arc.isAttachedToWindow) arc.post { go() }
            return spin
        }
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

/**
 * Text that fits the height it is given. The largest size, from [maxPx] down to [minPx], at
 * which all of it fits in the whole lines that height holds (never more than [cap]); at the
 * smallest, as many lines as fit, ellipsized after the last.
 */
internal class FitText(
    ctx: android.content.Context,
    private val maxPx: Float,
    private val minPx: Float,
    private val cap: Int,
) : TextView(ctx) {
    init {
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, maxPx)
        maxLines = cap
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val room = View.MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val width = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED &&
            View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED &&
            room > 0 && width > 0
        ) {
            var size = maxPx
            var lines: Int
            while (true) {
                if (textSize != size) setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                lines = (room / lineHeight).coerceIn(1, cap)
                if (size <= minPx || lineHeight <= room && linesAt(width) <= lines) break
                size = (size - 1f).coerceAtLeast(minPx)
            }
            if (maxLines != lines) maxLines = lines
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun linesAt(width: Int): Int {
        val t = text ?: return 0
        return android.text.StaticLayout.Builder.obtain(t, 0, t.length, paint, width)
            .setIncludePad(includeFontPadding)
            .setBreakStrategy(breakStrategy)
            .setHyphenationFrequency(hyphenationFrequency)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .build().lineCount
    }
}
