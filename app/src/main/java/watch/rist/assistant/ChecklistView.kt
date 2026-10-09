package watch.rist.assistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import rist.v1.Checklist
import rist.v1.ChecklistItem
import java.util.WeakHashMap

/**
 * How a checklist is drawn: one row per item, a checkbox and the item's stored text, verbatim,
 * struck through when checked. The same row serves the expanded tile and the reply card.
 */
object ChecklistView {

    const val TAG_ROW = "checklist-row"

    /** Rows on screen now, by item id, so a refused tick can put its box back. */
    private val live = WeakHashMap<CheckBox, String>()

    /**
     * One row. [onTap] gets the new state after the box has already changed; [checked] is what
     * the row shows when drawn.
     */
    fun row(
        ctx: Context, item: ChecklistItem, checked: Boolean, rt: RistTheme, tf: Typeface?,
        onTap: (CheckBox, Boolean) -> Unit,
    ): CheckBox {
        val d = ctx.resources.displayMetrics.density
        val muted = Themes.readableMuted(rt)
        return CheckBox(ctx).apply {
            tag = TAG_ROW
            text = item.text
            typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemePaint.scaledSp(rt, 16f))
            buttonTintList = ColorStateList.valueOf(rt.ink)
            minHeight = (48 * d).toInt()
            setPadding((4 * d).toInt(), 0, 0, 0)
            isChecked = checked
            look(this, checked, rt.ink, muted)
            onlyTapsToggle(this)
            setOnCheckedChangeListener { box, now ->
                look(this, now, rt.ink, muted)
                if (box.getTag(R.id.checklist_quiet) != true) onTap(this, now)
            }
            synchronized(live) { live[this] = item.id }
        }
    }

    /**
     * A checkbox alone, for a row whose text is a separate view (a tile row whose text opens an
     * editor). [onLook] gets every state it shows, a tap or a newer list; [onTap] only taps.
     */
    fun bareBox(
        ctx: Context, itemId: String, checked: Boolean, rt: RistTheme,
        onLook: (Boolean) -> Unit, onTap: (Boolean) -> Unit,
    ): CheckBox {
        val d = ctx.resources.displayMetrics.density
        return CheckBox(ctx).apply {
            tag = TAG_ROW
            buttonTintList = ColorStateList.valueOf(rt.ink)
            minHeight = (48 * d).toInt()
            minWidth = (48 * d).toInt()
            isChecked = checked
            onlyTapsToggle(this)
            setOnCheckedChangeListener { box, now ->
                onLook(now)
                if (box.getTag(R.id.checklist_quiet) != true) onTap(now)
            }
            synchronized(live) { live[this] = itemId }
        }
    }

    /**
     * A row ticks on a tap and on nothing else. Once the finger has moved past the touch slop
     * the press is cancelled, so a swipe or a drag that happens to start on a row, and ends
     * still over it, never changes the owner's list.
     */
    private fun onlyTapsToggle(box: CheckBox) {
        val slop = ViewConfiguration.get(box.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var moved = false
        box.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; moved = false; false }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    if (!moved && Math.hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()) > slop) {
                        moved = true
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        v.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    moved
                }
                else -> moved
            }
        }
    }

    private fun look(box: CheckBox, checked: Boolean, ink: Int, muted: Int) {
        box.paintFlags = if (checked) box.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else box.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        box.setTextColor(if (checked) muted else ink)
    }

    /** Sets a row's box without sending anything. */
    internal fun setQuietly(box: CheckBox, checked: Boolean) {
        if (box.isChecked == checked) return
        box.setTag(R.id.checklist_quiet, true)
        box.isChecked = checked
        box.setTag(R.id.checklist_quiet, null)
    }

    /** A tick was refused: every row of [itemId] on screen goes back to [checked]. */
    fun reverted(itemId: String, checked: Boolean) = reflect(mapOf(itemId to checked))

    /**
     * The latest known state of some items (a tap, a newer reply, a newer box list): every row
     * on screen holding one of them, on any card or tile, shows it. Nothing is sent.
     */
    fun reflect(states: Map<String, Boolean>) {
        if (states.isEmpty()) return
        val apply = {
            val boxes = synchronized(live) { live.entries.filter { it.value in states }.map { it.key to it.value } }
            boxes.forEach { (box, id) -> states[id]?.let { setQuietly(box, it) } }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else Handler(Looper.getMainLooper()).post(apply)
    }

    /** A list's heading, when it has one. */
    fun heading(ctx: Context, title: String, rt: RistTheme, tf: Typeface?): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = title
            typeface = tf
            isAllCaps = true
            letterSpacing = 0.05f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Themes.readableMuted(rt))
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }
    }

    /**
     * A reply card's lists under its answer. Every row the backend sent stays, ticked or not:
     * the card is history, and a second tap undoes a first.
     */
    fun addCards(parent: LinearLayout, entry: TranscriptEntry, rt: RistTheme, tf: Typeface?) {
        if (entry.checklists.isEmpty() || !Checklists.declared()) return
        val ctx = parent.context
        for (cl in entry.checklists) {
            if (cl.title.isNotBlank()) parent.addView(heading(ctx, cl.title, rt, tf))
            for (item in cl.itemsList) {
                if (item.id.isBlank()) continue
                val shown = Checklists.shown(ctx, item.id, item.checked)
                parent.addView(row(ctx, item, shown, rt, tf) { _, now ->
                    Haptics.ack(ctx)
                    Checklists.tap(ctx, item.id, now, !now)
                })
            }
        }
    }

    /** A tile row ticked while the view is open: kept on screen until the view closes. */
    data class Kept(val section: String, val index: Int, val item: ChecklistItem)

    /** The key a tile section's kept rows are filed under. */
    fun sectionKey(index: Int, cl: Checklist): String = "$index:${cl.list}"

    /**
     * Whether a section lists checked items as well. The wire names a `_all` section's list as
     * plainly as any other, so this is known only from a checked item in it.
     */
    internal fun showsChecked(cl: Checklist): Boolean = cl.itemsList.any { it.checked }

    /**
     * The rows a tile section draws, with the state each shows. A checked item shows only in a
     * section that lists checked items, or when it was ticked while this view has been open; a
     * row ticked here that a newer list no longer holds comes back at its old place.
     */
    internal fun tileRows(ctx: Context, key: String, cl: Checklist, kept: Map<String, Kept>): List<Pair<ChecklistItem, Boolean>> {
        val all = showsChecked(cl)
        val rows = cl.itemsList.filter { it.id.isNotBlank() }.mapNotNull { item ->
            val s = Checklists.shown(ctx, item.id, item.checked)
            if (!s || all || item.id in kept) item to s else null
        }.toMutableList()
        val missing = kept.values.filter { k -> k.section == key && rows.none { it.first.id == k.item.id } }
        for (k in missing.sortedBy { it.index }) {
            rows.add(k.index.coerceIn(0, rows.size), k.item to Checklists.shown(ctx, k.item.id, true))
        }
        return rows
    }

    internal fun resetForTest() = synchronized(live) { live.clear() }
}
