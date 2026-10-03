package watch.rist.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Every box, three across, scrolling down: the way to find one when the row is long. The order is
 * the row's order. Touch edits happen here directly; anything that is a turn (a command box, or
 * adding or changing a box) closes this screen and is sent from home, where its answer appears.
 */
class AllBoxesActivity : AppCompatActivity(), BoxBoard.Host {

    private val rt by lazy { Themes.byId(Config.themeId(this)) }
    private val pixelTf: Typeface? by lazy {
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.pixel) }.getOrNull()
    }
    private fun px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    internal lateinit var board: BoxBoard
    private lateinit var countView: TextView

    private val changed = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val muted = Themes.readableMuted(rt)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(rt.ground)
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(22f), px(24f), px(12f), px(10f))
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.boxes_all_title)
            typeface = pixelTf; isAllCaps = true; letterSpacing = 0.1f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(rt.ink)
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun iconButton(res: Int, desc: Int, tag: String, tint: Int, act: () -> Unit) = FrameLayout(this).apply {
            this.tag = tag
            contentDescription = getString(desc)
            isClickable = true; isFocusable = true
            setOnClickListener { act() }
            addView(ImageView(this@AllBoxesActivity).apply {
                setImageDrawable(ContextCompat.getDrawable(this@AllBoxesActivity, res)?.mutate())
                setColorFilter(tint)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(px(26f), px(26f), Gravity.CENTER)
            })
        }
        header.addView(iconButton(R.drawable.ic_box_plus, R.string.boxes_add, TAG_ADD, rt.accent) { board.openAddSheet() },
            LinearLayout.LayoutParams(px(48f), px(48f)))
        header.addView(iconButton(R.drawable.ic_box_x, R.string.boxes_close, TAG_CLOSE, rt.ink) { finish() },
            LinearLayout.LayoutParams(px(48f), px(48f)))
        root.addView(header)
        countView = TextView(this).apply {
            tag = TAG_COUNT
            typeface = ThemePaint.typefaceOf(this@AllBoxesActivity, rt)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(muted)
            setPadding(px(22f), 0, px(22f), px(10f))
        }
        root.addView(countView)
        val grid = RecyclerView(this).apply {
            tag = TAG_GRID
            setPadding(px(11f), 0, px(11f), px(16f))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val undo = TextView(this).apply {
            tag = TAG_UNDO
            visibility = View.GONE
            gravity = Gravity.CENTER
            minHeight = px(48f)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isClickable = true; isFocusable = true
        }
        root.addView(undo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        board = BoxBoard(this, grid, grid = true, host = this, undoBar = undo)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (board.editMode) board.setEditMode(false) else finish()
            }
        })
        render()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(changed, android.content.IntentFilter(HomeBoxes.ACTION_CHANGED))
        render()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(changed)
        super.onStop()
    }

    private fun render() {
        val n = HomeBoxes.boxes(this).size
        countView.text = resources.getQuantityString(R.plurals.boxes_count, n, n)
        board.render()
    }

    /** Turns go home to be sent, so the answer lands in the feed with everything else. */
    override fun onTurn(turn: HomeBoxes.Turn) {
        setResult(Activity.RESULT_OK, Intent()
            .putExtra(EXTRA_TEXT, turn.text)
            .putExtra(EXTRA_TOOL, turn.targetToolId)
            .putExtra(EXTRA_BOX, turn.boxId)
            .putExtra(EXTRA_PROMPT, turn.prompt))
        finish()
    }

    companion object {
        const val EXTRA_TEXT = "box_turn_text"
        const val EXTRA_TOOL = "box_turn_tool"
        const val EXTRA_BOX = "box_turn_box"
        const val EXTRA_PROMPT = "box_turn_prompt"
        const val TAG_ADD = "all-boxes-add"
        const val TAG_CLOSE = "all-boxes-close"
        const val TAG_COUNT = "all-boxes-count"
        const val TAG_GRID = "all-boxes-grid"
        const val TAG_UNDO = "all-boxes-undo"

        /** The turn a result carries, or null when the screen was just closed. */
        fun turnFrom(data: Intent?): HomeBoxes.Turn? {
            val text = data?.getStringExtra(EXTRA_TEXT).orEmpty()
            if (text.isBlank()) return null
            return HomeBoxes.Turn(
                text = text,
                targetToolId = data?.getStringExtra(EXTRA_TOOL).orEmpty(),
                boxId = data?.getStringExtra(EXTRA_BOX).orEmpty(),
                prompt = data?.getStringExtra(EXTRA_PROMPT).orEmpty().ifBlank { text },
            )
        }
    }
}
