package watch.rist.assistant

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
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.DateFormat
import java.util.Date

/**
 * A display box opened full screen: its title, when it was last updated, and the full text the
 * backend sent with the glance value, as markdown. Nothing is sent to open it, so it opens at
 * once and works offline. It follows the box: a newer list redraws it, and a box that has gone
 * closes it.
 */
class BoxExpandedActivity : AppCompatActivity() {

    private val rt by lazy { Themes.current(this) }
    private val tf: Typeface? by lazy { ThemePaint.typefaceOf(this, rt) }
    private val pixelTf: Typeface? by lazy {
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.pixel) }.getOrNull()
    }
    private fun px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private lateinit var iconView: ImageView
    private lateinit var titleView: TextView
    private lateinit var updatedView: TextView
    private lateinit var bodyView: TextView

    private val boxId: String get() = intent.getStringExtra(EXTRA_BOX_ID).orEmpty()

    private val changed = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = fill()
    }

    // A new look while open: draw this screen again in it.
    private val designChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (!isFinishing && !isDestroyed) recreate() }
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
            gravity = Gravity.TOP
            setPadding(px(22f), px(24f), px(12f), px(12f))
        }
        val heads = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            tag = TAG_TITLE
            typeface = pixelTf; isAllCaps = true; letterSpacing = 0.1f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(rt.ink)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        updatedView = TextView(this).apply {
            tag = TAG_UPDATED
            typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(muted)
            setPadding(0, px(6f), 0, 0)
        }
        iconView = ImageView(this).apply {
            tag = TAG_ICON
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconView, LinearLayout.LayoutParams(px(22f), px(22f)).apply { marginEnd = px(8f) })
            addView(titleView)
        }
        heads.addView(titleRow); heads.addView(updatedView)
        header.addView(heads, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(FrameLayout(this).apply {
            tag = TAG_CLOSE
            contentDescription = getString(R.string.boxes_close)
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
            addView(ImageView(this@BoxExpandedActivity).apply {
                setImageDrawable(ContextCompat.getDrawable(this@BoxExpandedActivity, R.drawable.ic_box_x)?.mutate())
                setColorFilter(rt.ink)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(px(24f), px(24f), Gravity.CENTER)
            })
        }, LinearLayout.LayoutParams(px(48f), px(48f)))
        root.addView(header)
        root.addView(View(this).apply { setBackgroundColor(rt.tileBorder) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1.5f).coerceAtLeast(1)))
        bodyView = TextView(this).apply {
            tag = TAG_BODY
            typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemePaint.scaledSp(rt, 16f))
            setTextColor(rt.ink)
            setLineSpacing(0f, 1.3f)
            setTextIsSelectable(true)
            // Selectable, but nothing in it is ever offered as a link to open.
            setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
            setPadding(px(22f), px(18f), px(22f), px(28f))
        }
        root.addView(ScrollView(this).apply { addView(bodyView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        fill()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(changed, android.content.IntentFilter(HomeBoxes.ACTION_CHANGED))
        LocalBroadcastManager.getInstance(this).registerReceiver(designChanged, android.content.IntentFilter(DesignSync.ACTION_CHANGED))
        fill()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(changed)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(designChanged)
        super.onStop()
    }

    private fun fill() {
        val b = HomeBoxes.find(this, boxId)
        if (b == null) { finish(); return }
        titleView.text = b.title
        val icon = BoxIcons.drawable(this, b, rt.ink)
        iconView.setImageDrawable(icon)
        iconView.visibility = if (icon != null) View.VISIBLE else View.GONE
        val nowS = System.currentTimeMillis() / 1000
        val face = HomeBoxes.face(b, nowS)
        updatedView.text = when {
            b.updatedAtEpochS > 0 -> getString(R.string.boxes_updated_at,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(b.updatedAtEpochS * 1000))) +
                (if (HomeBoxes.isStale(b, nowS)) " · " + getString(R.string.boxes_stale) else "")
            else -> face.detail
        }
        // A box that sent no full text shows what it has: the glance value and its line.
        val body = b.body.ifBlank { listOf(face.value, face.detail).filter { it.isNotBlank() }.joinToString("\n\n") }
        bodyView.text = Markdown.render(body)
    }

    companion object {
        const val EXTRA_BOX_ID = "box_id"
        const val TAG_TITLE = "box-expanded-title"
        const val TAG_ICON = "box-expanded-icon"
        const val TAG_UPDATED = "box-expanded-updated"
        const val TAG_BODY = "box-expanded-body"
        const val TAG_CLOSE = "box-expanded-close"

        fun intent(ctx: Context, boxId: String): Intent =
            Intent(ctx, BoxExpandedActivity::class.java).putExtra(EXTRA_BOX_ID, boxId)
    }
}
