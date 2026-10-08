package watch.rist.assistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.util.WeakHashMap

/**
 * How a note card is drawn under a reply: the note's text, verbatim. A tap on the text opens it
 * for editing; the tick saves the whole text, Cancel (or Back) leaves it as it was.
 *
 * A note being edited is kept in [drafts] until it is saved or cancelled, so a feed repaint
 * while the keyboard is up does not throw away what the user typed.
 */
object NoteCardView {

    const val TAG_CARD = "note-card"
    const val TAG_TEXT = "note-card-text"
    const val TAG_EDIT = "note-card-edit"
    const val TAG_SAVE = "note-card-save"
    const val TAG_CANCEL = "note-card-cancel"

    /** Text being edited and not yet saved, by "entry:card index". */
    private val drafts = LinkedHashMap<String, String>()

    /** How each open editor on screen closes without saving, newest last; Back closes the newest. */
    private val closers = LinkedHashMap<String, () -> Unit>()

    private val backs = WeakHashMap<ComponentActivity, OnBackPressedCallback>()

    private fun key(entryId: Long, index: Int) = "$entryId:$index"

    /** Whether a card of [entryId] is open for editing; a swipe must not clear it then. */
    fun isEditing(entryId: Long): Boolean = synchronized(drafts) { drafts.keys.any { it.startsWith("$entryId:") } }

    internal fun draft(entryId: Long, index: Int): String? = synchronized(drafts) { drafts[key(entryId, index)] }

    /** A reply's note cards under its answer. */
    fun addCards(parent: LinearLayout, entry: TranscriptEntry, rt: RistTheme, tf: Typeface?) {
        if (entry.noteCards.isEmpty() || !NoteEdits.declared()) return
        val ctx = parent.context
        watchBack(ctx)
        entry.noteCards.forEachIndexed { index, card ->
            if (card.noteId.isBlank()) return@forEachIndexed
            parent.addView(card(ctx, entry.localId, index, card.title, rt, tf))
        }
    }

    private fun card(ctx: Context, entryId: Long, index: Int, title: String, rt: RistTheme, tf: Typeface?): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        val muted = Themes.readableMuted(rt)
        val box = LinearLayout(ctx).apply {
            tag = TAG_CARD
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        }
        if (title.isNotBlank()) box.addView(ChecklistView.heading(ctx, title, rt, tf))
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(body)
        val k = key(entryId, index)

        fun current() = Transcript.noteCard(ctx, entryId, index)

        lateinit var showText: () -> Unit
        lateinit var showEditor: (String) -> Unit

        showText = {
            synchronized(drafts) { closers.remove(k) }
            refreshBack(ctx)
            body.removeAllViews()
            body.addView(TextView(ctx).apply {
                tag = TAG_TEXT
                text = current()?.text.orEmpty()
                typeface = tf
                setTextColor(rt.ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemePaint.scaledSp(rt, 16f))
                minHeight = (48 * d).toInt()
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = ctx.getString(R.string.note_edit_tap_to_edit) + ". " + text
                setOnClickListener { showEditor(current()?.text.orEmpty()) }
            })
            body.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.note_edit_tap_to_edit)
                typeface = tf
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }

        showEditor = { start ->
            synchronized(drafts) { drafts[k] = start }
            body.removeAllViews()
            val edit = EditText(ctx).apply {
                tag = TAG_EDIT
                setText(start)
                typeface = tf
                setTextColor(rt.ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemePaint.scaledSp(rt, 16f))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                isSingleLine = false
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
                filters = arrayOf(InputFilter.LengthFilter(NoteEdits.MAX_TEXT))
                backgroundTintList = ColorStateList.valueOf(rt.ink)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        synchronized(drafts) { if (k in drafts) drafts[k] = s?.toString().orEmpty() }
                    }
                })
            }
            body.addView(edit)
            val close = {
                synchronized(drafts) { drafts.remove(k) }
                hideKeyboard(edit)
                showText()
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(CheckBox(ctx).apply {
                tag = TAG_SAVE
                text = ctx.getString(R.string.note_edit_save)
                typeface = tf
                setTextColor(rt.ink)
                buttonTintList = ColorStateList.valueOf(rt.ink)
                minHeight = (48 * d).toInt()
                setOnCheckedChangeListener { b, now ->
                    if (!now) return@setOnCheckedChangeListener
                    val text = edit.text.toString()
                    val card = current()
                    when {
                        card == null -> close()
                        text == card.text -> close()           // nothing changed: nothing to send
                        text.isBlank() -> {
                            b.isChecked = false
                            Toast.makeText(ctx, ctx.getString(R.string.note_edit_empty), Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            Haptics.ack(ctx)
                            NoteEdits.save(ctx, card.noteId, card.version, text)
                            close()
                        }
                    }
                }
            })
            row.addView(TextView(ctx).apply {
                tag = TAG_CANCEL
                text = ctx.getString(R.string.note_edit_cancel)
                typeface = tf
                isAllCaps = true
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding((24 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { close() }
            })
            body.addView(row)
            synchronized(drafts) { closers.remove(k); closers[k] = close }
            refreshBack(ctx)
            edit.requestFocus()
            edit.setSelection(edit.text.length)
            showKeyboard(edit)
        }

        val held = synchronized(drafts) { drafts[k] }
        if (held != null) showEditor(held) else showText()
        return box
    }

    /** Back closes the newest open editor without saving, while one is open. */
    private fun watchBack(ctx: Context) {
        val activity = ctx as? ComponentActivity ?: return
        if (backs.containsKey(activity)) return
        val cb = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val close = synchronized(drafts) { closers.values.lastOrNull() }
                if (close != null) close() else isEnabled = false
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, cb)
        backs[activity] = cb
    }

    private fun refreshBack(ctx: Context) {
        val activity = ctx as? ComponentActivity ?: return
        backs[activity]?.isEnabled = synchronized(drafts) { closers.isNotEmpty() }
    }

    private fun showKeyboard(v: View) {
        v.post {
            runCatching {
                (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun hideKeyboard(v: View) {
        runCatching {
            (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(v.windowToken, 0)
        }
    }

    internal fun resetForTest() = synchronized(drafts) {
        drafts.clear()
        closers.clear()
    }
}
