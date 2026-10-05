package watch.rist.assistant

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import rist.v1.HomeBox

/**
 * The sheet that adds a box, and the one that edits a box.
 *
 * Adding is a turn addressed to the boxes tool, in the user's own words; the assistant makes the
 * box and says what it did, and the new list comes back with the reply. There is no confirmation
 * card: a box is cheap to make and easy to remove. Editing renames a box with a plain touch edit,
 * and a change to what the box does is again a turn, naming that box.
 *
 * There is no microphone here: the phone does not turn speech into text itself, so a spoken
 * request would arrive as audio and could not be marked as one for the boxes tool. Saying "add a
 * box that shows my next meeting" to the assistant anywhere does the same thing.
 */
object BoxSheet {

    const val TAG_TITLE = "box-sheet-title"
    const val TAG_DISPLAY = "box-sheet-display"
    const val TAG_COMMAND = "box-sheet-command"
    const val TAG_WORDS = "box-sheet-words"
    const val TAG_NAME = "box-sheet-name"
    const val TAG_SUBMIT = "box-sheet-submit"

    private class Kit(val activity: Activity) {
        val t = Themes.current(activity)
        val d = activity.resources.displayMetrics.density
        val tf: Typeface? = ThemePaint.typefaceOf(activity, t)
        val pixelTf: Typeface? = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.pixel)
        }.getOrNull()
        val muted = Themes.readableMuted(t)
        fun px(v: Float) = (v * d).toInt()

        fun text(s: String, sp: Float, colour: Int, face: Typeface? = tf) = TextView(activity).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemePaint.scaledSp(t, sp)); setTextColor(colour); typeface = face
        }

        fun field(lines: Int, label: TextView) = EditText(activity).apply {
            // Named by its label for TalkBack, which then still reads what is typed.
            id = View.generateViewId()
            label.labelFor = id
            setTextColor(t.ink); setHintTextColor(muted); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemePaint.scaledSp(t, 16f))
            background = GradientDrawable().apply {
                setColor(t.fieldFill ?: t.tileFill)
                setStroke(px(1.5f), t.fieldBorder)
                cornerRadius = px(14f).toFloat()
            }
            setPadding(px(14f), px(12f), px(14f), px(12f))
            gravity = Gravity.TOP or Gravity.START
            if (lines > 1) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minHeight = px(84f)
                maxLines = 5
                filters = arrayOf(InputFilter.LengthFilter(HomeBoxes.COMMAND_MAX))
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                isSingleLine = true
                minHeight = px(52f)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        fun button(label: String) = TextView(activity).apply {
            text = label
            tag = TAG_SUBMIT
            gravity = Gravity.CENTER
            typeface = Typeface.create(tf, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemePaint.scaledSp(t, 16f))
            minHeight = px(52f)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        fun paintButton(b: TextView, enabled: Boolean) {
            b.isEnabled = enabled
            b.setTextColor(if (enabled) onAccent() else muted)
            b.background = GradientDrawable().apply {
                setColor(if (enabled) t.accent else t.tileFill)
                if (!enabled) setStroke(px(1.5f), t.tileBorder)
                cornerRadius = px(26f).toFloat()
            }
        }

        fun onAccent(): Int = ThemePaint.onAccent(t)

        fun gap(dp: Float) = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(1, px(dp))
        }

        fun sheet(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22f), px(14f), px(22f), px(30f))
            background = GradientDrawable().apply {
                setColor(t.ground)
                val r = px(24f).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            addView(View(activity).apply {
                background = GradientDrawable().apply { setColor(t.tileBorder); cornerRadius = px(2f).toFloat() }
                layoutParams = LinearLayout.LayoutParams(px(36f), px(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            })
            addView(gap(14f))
        }

        fun title(s: String) = text(s, 18f, t.ink, pixelTf).apply {
            tag = TAG_TITLE
            letterSpacing = 0.1f
            isAllCaps = true
            ViewCompat.setAccessibilityHeading(this, true)
        }

        fun dialog(content: View): BottomSheetDialog = BottomSheetDialog(activity).apply {
            setContentView(content)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setOnShowListener {
                findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                    sheet.setBackgroundColor(Color.TRANSPARENT)
                    BottomSheetBehavior.from(sheet).apply {
                        skipCollapsed = true
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            }
        }
    }

    private fun sendable(s: String) = s.any { it.isLetterOrDigit() }

    /**
     * "+": choose display or command, say what it should do, and Add. A one-tap (command) tile is
     * made directly from the words as typed ([onAddCommand]); a display tile is asked for by a turn.
     */
    fun showAdd(
        activity: Activity,
        onAddCommand: (String) -> Unit,
        onTurn: (HomeBoxes.Turn) -> Unit,
    ): BottomSheetDialog {
        val k = Kit(activity)
        val t = k.t
        val root = k.sheet()
        root.addView(k.title(activity.getString(R.string.boxes_sheet_add_title)))
        root.addView(k.gap(16f))
        var kind = HomeBoxes.Kind.DISPLAY
        val prompt = k.text("", 13f, k.muted).apply { typeface = Typeface.create(k.tf, Typeface.BOLD) }
        val hint = k.text(activity.getString(R.string.boxes_sheet_display_hint), 12f, k.muted)
        val words = k.field(lines = 3, label = prompt).apply { tag = TAG_WORDS }
        val choices = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun choice(tag: String, name: Int, sub: Int) = LinearLayout(activity).apply {
            this.tag = tag
            orientation = LinearLayout.VERTICAL
            minimumHeight = k.px(76f)
            setPadding(k.px(12f), k.px(10f), k.px(12f), k.px(10f))
            isClickable = true; isFocusable = true
            addView(k.text(activity.getString(name), 15f, t.ink).apply { typeface = Typeface.create(k.tf, Typeface.BOLD) })
            addView(k.text(activity.getString(sub), 12f, k.muted))
            contentDescription = activity.getString(name) + ". " + activity.getString(sub)
        }
        val display = choice(TAG_DISPLAY, R.string.boxes_kind_display, R.string.boxes_kind_display_sub)
        val command = choice(TAG_COMMAND, R.string.boxes_kind_command, R.string.boxes_kind_command_sub)
        choices.addView(display, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = k.px(5f) })
        choices.addView(command, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = k.px(5f) })
        fun paintChoices() {
            for ((v, on) in listOf(display to (kind == HomeBoxes.Kind.DISPLAY), command to (kind == HomeBoxes.Kind.COMMAND))) {
                v.isSelected = on
                v.background = GradientDrawable().apply {
                    setColor(t.tileFill)
                    setStroke(k.px(if (on) 2.5f else 1.5f), if (on) t.accent else t.tileBorder)
                    cornerRadius = k.px(14f).toFloat()
                }
                ViewCompat.setStateDescription(v, activity.getString(if (on) R.string.boxes_selected else R.string.boxes_not_selected))
            }
            val display = kind == HomeBoxes.Kind.DISPLAY
            prompt.text = activity.getString(if (display) R.string.boxes_sheet_display_prompt else R.string.boxes_sheet_command_prompt)
            hint.visibility = if (display) View.VISIBLE else View.GONE
        }
        display.setOnClickListener { kind = HomeBoxes.Kind.DISPLAY; paintChoices() }
        command.setOnClickListener { kind = HomeBoxes.Kind.COMMAND; paintChoices() }
        root.addView(choices)
        root.addView(k.gap(16f))
        root.addView(prompt)
        root.addView(k.gap(6f))
        root.addView(words)
        root.addView(k.gap(6f))
        root.addView(hint)
        root.addView(k.gap(16f))
        val add = k.button(activity.getString(R.string.boxes_sheet_add_button))
        root.addView(add)
        root.addView(k.gap(12f))
        root.addView(k.text(activity.getString(R.string.boxes_sheet_footer), 12f, k.muted).apply { gravity = Gravity.CENTER })
        paintChoices()
        k.paintButton(add, false)
        words.doAfterTextChanged { k.paintButton(add, sendable(it?.toString().orEmpty())) }
        val dialog = k.dialog(root)
        add.setOnClickListener {
            val w = words.text?.toString()?.trim().orEmpty()
            if (!sendable(w)) return@setOnClickListener
            dialog.dismiss()
            if (kind == HomeBoxes.Kind.COMMAND) onAddCommand(w) else onTurn(HomeBoxes.addTurn(kind, w))
        }
        dialog.show()
        return dialog
    }

    /**
     * ✎: the box's name, and what it does. A new name is a touch edit; new words for what it does
     * are a turn to the boxes tool naming this box. Either, both or neither may change.
     */
    fun showEdit(
        activity: Activity,
        box: HomeBox,
        onRename: (String) -> Unit,
        onTurn: (HomeBoxes.Turn) -> Unit,
    ): BottomSheetDialog {
        val k = Kit(activity)
        val root = k.sheet()
        root.addView(k.title(activity.getString(R.string.boxes_sheet_edit_title)))
        root.addView(k.gap(16f))
        val nameLabel = k.text(activity.getString(R.string.boxes_sheet_name), 13f, k.muted).apply {
            typeface = Typeface.create(k.tf, Typeface.BOLD)
        }
        val name = k.field(lines = 1, label = nameLabel).apply {
            tag = TAG_NAME
            filters = arrayOf(InputFilter.LengthFilter(HomeBoxes.TITLE_MAX))
            setText(box.title)
        }
        val command = HomeBoxes.kindOf(box) == HomeBoxes.Kind.COMMAND
        // The box's own defining words when the backend sent them; a command box falls back to
        // its command, and a display box without them starts empty.
        val before = box.sourceWords.ifBlank { if (command) box.command else "" }
        val prompt = k.text(
            activity.getString(if (command) R.string.boxes_sheet_change_command else R.string.boxes_sheet_change_display),
            13f, k.muted,
        ).apply { typeface = Typeface.create(k.tf, Typeface.BOLD) }
        val words = k.field(lines = 3, label = prompt).apply {
            tag = TAG_WORDS
            setText(before.take(HomeBoxes.COMMAND_MAX))
        }
        root.addView(nameLabel); root.addView(k.gap(6f)); root.addView(name)
        root.addView(k.gap(16f))
        root.addView(prompt); root.addView(k.gap(6f)); root.addView(words)
        root.addView(k.gap(16f))
        val save = k.button(activity.getString(R.string.boxes_sheet_save))
        root.addView(save)
        k.paintButton(save, true)
        val dialog = k.dialog(root)
        save.setOnClickListener {
            val newName = name.text?.toString()?.trim().orEmpty()
            val newWords = words.text?.toString()?.trim().orEmpty()
            dialog.dismiss()
            if (sendable(newName) && newName != box.title) onRename(newName)
            if (sendable(newWords) && newWords != before.trim()) onTurn(HomeBoxes.changeTurn(box, newWords))
        }
        dialog.show()
        return dialog
    }
}
