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
}
