package watch.rist.assistant

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
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
 * closes it. A running display box also has a refresh button, which asks the backend to bring it
 * up to date now and spins until the box's update time rises above what it was at the tap. The
 * wait itself is held by [BoxRefresh], so closing the view and opening it again picks it up.
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
    private lateinit var refreshButton: FrameLayout
    private lateinit var refreshIcon: ImageView
    private lateinit var refreshStatus: TextView
    private var spin: ObjectAnimator? = null

    private val main = Handler(Looper.getMainLooper())

    /** A wait for this box ending, wherever it ended: redraw, and say why when it did not work. */
    private val ended = object : BoxRefresh.Watcher {
        override fun ended(id: String, said: Int?) {
            if (id != boxId || isFinishing || isDestroyed) return
            drawRefresh()
            if (said != null) say(said)
        }

        override fun slowed(id: String) {
            if (id == boxId && !isFinishing && !isDestroyed) drawRefresh()
        }
    }
    private val clearStatus = Runnable { refreshStatus.text = ""; refreshStatus.visibility = View.GONE }
    private val rested = Runnable { drawRefresh() }

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
        refreshStatus = TextView(this).apply {
            tag = TAG_REFRESH_STATUS
            typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(muted)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = px(48f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        header.addView(refreshStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        refreshIcon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@BoxExpandedActivity, R.drawable.ic_box_refresh)?.mutate())
            setColorFilter(rt.ink)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(px(24f), px(24f), Gravity.CENTER)
        }
        refreshButton = FrameLayout(this).apply {
            tag = TAG_REFRESH
            contentDescription = getString(R.string.boxes_refresh)
            isClickable = true; isFocusable = true
            setOnClickListener { refresh() }
            addView(refreshIcon)
            visibility = View.GONE
        }
        header.addView(refreshButton, LinearLayout.LayoutParams(px(48f), px(48f)))
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
        BoxRefresh.watch(ended)
        fill()
    }

    override fun onStop() {
        BoxRefresh.unwatch(ended)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(changed)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(designChanged)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        spin?.cancel()
        super.onDestroy()
    }

    // ---- refresh ----

    private fun refresh() {
        val b = HomeBoxes.find(this, boxId) ?: return
        if (!BoxRefresh.start(this, b)) return
        main.removeCallbacks(clearStatus)
        clearStatus.run()
        drawRefresh()
    }

    /** A short word beside the button, gone again after a few seconds. */
    private fun say(res: Int) {
        refreshStatus.text = getString(res)
        refreshStatus.visibility = View.VISIBLE
        main.removeCallbacks(clearStatus)
        main.postDelayed(clearStatus, STATUS_MS)
    }

    private fun drawRefresh() {
        val b = HomeBoxes.find(this, boxId)
        val offered = b != null && BoxRefresh.offered(b)
        refreshButton.visibility = if (offered) View.VISIBLE else View.GONE
        val busy = BoxRefresh.isPending(boxId) && offered
        val resting = BoxRefresh.restingMs(boxId, System.currentTimeMillis())
        refreshButton.isEnabled = !busy && resting == 0L
        refreshButton.alpha = if (busy || resting == 0L) 1f else 0.4f
        ViewCompat.setStateDescription(refreshButton, if (busy) getString(R.string.boxes_refreshing_state) else null)
        main.removeCallbacks(rested)
        if (resting > 0) main.postDelayed(rested, resting)
        if (busy && BoxRefresh.motion()) {
            if (spin == null) spin = ObjectAnimator.ofFloat(refreshIcon, View.ROTATION, 0f, 360f).apply {
                duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                start()
            }
        } else {
            spin?.cancel(); spin = null
            refreshIcon.rotation = 0f
        }
        // A long wait says it is still working; with motion reduced the icon stays still, so the
        // wait is said in words from the start.
        val slow = busy && BoxRefresh.isSlow(boxId)
        if (slow || (busy && !BoxRefresh.motion())) {
            main.removeCallbacks(clearStatus)
            refreshStatus.text = getString(if (slow) R.string.boxes_refresh_slow else R.string.boxes_refreshing)
            refreshStatus.visibility = View.VISIBLE
        } else if (refreshStatus.text == getString(R.string.boxes_refreshing) ||
            refreshStatus.text == getString(R.string.boxes_refresh_slow)) {
            clearStatus.run()
        }
    }

    internal val isSpinning: Boolean get() = spin != null
    internal val isRefreshing: Boolean get() = BoxRefresh.isPending(boxId)

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
                updatedWhen(b.updatedAtEpochS.toLong(), System.currentTimeMillis())) +
                (if (HomeBoxes.isStale(b, nowS)) " · " + getString(R.string.boxes_stale) else "")
            else -> face.detail
        }
        // A box that sent no full text shows what it has: the glance value and its line.
        val body = b.body.ifBlank { listOf(face.value, face.detail).filter { it.isNotBlank() }.joinToString("\n\n") }
        bodyView.text = Markdown.render(body)
        BoxRefresh.observe(HomeBoxes.boxes(this))
        drawRefresh()
    }

    companion object {
        const val EXTRA_BOX_ID = "box_id"
        const val TAG_TITLE = "box-expanded-title"
        const val TAG_ICON = "box-expanded-icon"
        const val TAG_UPDATED = "box-expanded-updated"
        const val TAG_BODY = "box-expanded-body"
        const val TAG_CLOSE = "box-expanded-close"
        const val TAG_REFRESH = "box-expanded-refresh"
        const val TAG_REFRESH_STATUS = "box-expanded-refresh-status"
        const val STATUS_MS = 3_000L

        /** The time alone when it was today; with the date when it was not, so days-old reads as such. */
        fun updatedWhen(thenS: Long, nowMs: Long): String {
            val then = java.util.Calendar.getInstance().apply { timeInMillis = thenS * 1000 }
            val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
            val today = then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
                then.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
            val fmt = if (today) DateFormat.getTimeInstance(DateFormat.SHORT)
            else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            return fmt.format(Date(thenS * 1000))
        }

        fun intent(ctx: Context, boxId: String): Intent =
            Intent(ctx, BoxExpandedActivity::class.java).putExtra(EXTRA_BOX_ID, boxId)
    }
}
