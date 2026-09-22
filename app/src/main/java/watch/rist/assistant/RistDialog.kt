package watch.rist.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView

object RistDialog {

    const val TITLE_TAG = "rist_dialog_title"
    const val MESSAGE_TAG = "rist_dialog_message"

    private const val TITLE_SP = 20f
    private const val BODY_SP = 17f
    private const val BUTTON_SP = 17f

    fun ask(
        activity: Activity,
        t: RistTheme,
        tf: Typeface?,
        d: Float,
        title: String?,
        message: CharSequence,
        positive: String,
        onPositive: (() -> Unit)? = null,
        negative: String? = null,
    ): AlertDialog {
        val pad = (22 * d).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (10 * d).toInt())
            if (title != null) addView(TextView(activity).apply {
                tag = TITLE_TAG
                text = title
                setTextColor(t.ink)
                typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
                setPadding(0, 0, 0, (10 * d).toInt())
            })
            addView(TextView(activity).apply {
                tag = MESSAGE_TAG
                text = message
                setTextColor(t.ink)
                typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
                setLineSpacing(0f, 1.15f)
            })
        }

        val builder = AlertDialog.Builder(
            activity,
            if (t.dark) android.R.style.Theme_Material_Dialog_Alert
            else android.R.style.Theme_Material_Light_Dialog_Alert,
        )
            .setView(content)
            .setPositiveButton(positive) { _, _ -> onPositive?.invoke() }
        if (negative != null) builder.setNegativeButton(negative, null)

        val dialog = builder.create()
        dialog.setOwnerActivity(activity)
        // Painting below must run after show(): the window has no decor before.
        dialog.show()

        val face = GradientDrawable().apply {
            setColor(t.tileFill)
            cornerRadius = t.tileRadiusDp * d
            setStroke(Math.max(1, (t.borderWidthDp * d).toInt()), t.fieldBorder)
        }
        dialog.window?.setBackgroundDrawable(InsetDrawable(face, (16 * d).toInt()))
        for (which in intArrayOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE)) {
            dialog.getButton(which)?.apply {
                setTextColor(t.accent)
                typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_SP)
                setAllCaps(false)
            }
        }
        return dialog
    }

    /** A short list of choices, themed like the rest. Returns the index picked. */
    fun choose(
        activity: Activity,
        t: RistTheme,
        tf: Typeface?,
        d: Float,
        title: String,
        options: List<String>,
        onPick: (Int) -> Unit,
    ): AlertDialog {
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val pad = (22 * d).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (10 * d).toInt())
            addView(TextView(activity).apply {
                tag = TITLE_TAG
                text = title
                setTextColor(t.ink)
                typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
                setPadding(0, 0, 0, (10 * d).toInt())
            })
            addView(rows)
        }

        val dialog = AlertDialog.Builder(
            activity,
            if (t.dark) android.R.style.Theme_Material_Dialog_Alert
            else android.R.style.Theme_Material_Light_Dialog_Alert,
        ).setView(content).setNegativeButton("Cancel", null).create()

        options.forEachIndexed { i, label ->
            rows.addView(TextView(activity).apply {
                text = label
                setTextColor(t.ink)
                typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
                setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss(); onPick(i) }
            })
        }

        dialog.setOwnerActivity(activity)
        dialog.show()

        val face = GradientDrawable().apply {
            setColor(t.tileFill)
            cornerRadius = t.tileRadiusDp * d
            setStroke(Math.max(1, (t.borderWidthDp * d).toInt()), t.fieldBorder)
        }
        dialog.window?.setBackgroundDrawable(InsetDrawable(face, (16 * d).toInt()))
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.apply {
            setTextColor(t.accent)
            typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_SP)
            setAllCaps(false)
        }
        return dialog
    }

    /**
     * A scrolling wheel of [labels], opened on the current value.
     *
     * NumberPicker is the platform's wheel. Its own text colour comes from an internal
     * attribute that a plain AlertDialog theme does not carry, so on a dark theme the
     * numbers come out unreadable unless they are set explicitly — which is what the
     * API-29 setters below are for.
     */
    fun wheel(
        activity: Activity,
        t: RistTheme,
        tf: Typeface?,
        d: Float,
        title: String,
        labels: List<String>,
        selected: Int,
        positive: String = "Set",
        onPick: (Int) -> Unit,
    ): AlertDialog {
        val picker = android.widget.NumberPicker(activity).apply {
            minValue = 0
            maxValue = (labels.size - 1).coerceAtLeast(0)
            displayedValues = labels.toTypedArray()
            value = selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
            // The list has two ends; wrapping would let "Forever" sit under "30 seconds".
            wrapSelectorWheel = false
            // Without this a tap puts a cursor in the middle row and raises the keyboard.
            descendantFocusability = android.widget.NumberPicker.FOCUS_BLOCK_DESCENDANTS
            textColor = t.ink
            setSelectionDividerHeight(Math.max(1, (d).toInt()))
        }

        val pad = (22 * d).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (10 * d).toInt())
            addView(TextView(activity).apply {
                tag = TITLE_TAG
                text = title
                setTextColor(t.ink)
                typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
                setPadding(0, 0, 0, (10 * d).toInt())
            })
            addView(picker)
        }

        val dialog = AlertDialog.Builder(
            activity,
            if (t.dark) android.R.style.Theme_Material_Dialog_Alert
            else android.R.style.Theme_Material_Light_Dialog_Alert,
        )
            .setView(content)
            .setPositiveButton(positive) { _, _ -> onPick(picker.value) }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOwnerActivity(activity)
        dialog.show()

        val face = GradientDrawable().apply {
            setColor(t.tileFill)
            cornerRadius = t.tileRadiusDp * d
            setStroke(Math.max(1, (t.borderWidthDp * d).toInt()), t.fieldBorder)
        }
        dialog.window?.setBackgroundDrawable(InsetDrawable(face, (16 * d).toInt()))
        for (which in intArrayOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE)) {
            dialog.getButton(which)?.apply {
                setTextColor(t.accent)
                typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_SP)
                setAllCaps(false)
            }
        }
        return dialog
    }
}
