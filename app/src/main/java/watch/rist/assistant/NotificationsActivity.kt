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

/**
 * Every notification, full screen: what the Notifications tile opens. While the tile row is
 * shown this is the only place notifications are listed (see [CommsFeedView.leftToTile]); the
 * feed is drawn by [CommsFeedView] into a host of its own, and clearing a row here updates the
 * tile's count.
 */
class NotificationsActivity : AppCompatActivity(), CommsFeedView.Watcher {

    private val rt by lazy { Themes.current(this) }
    private fun px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private lateinit var feed: LinearLayout
    private lateinit var empty: TextView

    private val countsChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    private val designChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (!isFinishing && !isDestroyed) recreate() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pixelTf: Typeface? = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(this, R.font.pixel)
        }.getOrNull()
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
            tag = TAG_TITLE
            text = getString(R.string.notifications_title)
            typeface = pixelTf; isAllCaps = true; letterSpacing = 0.1f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(rt.ink)
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(FrameLayout(this).apply {
            tag = TAG_CLOSE
            contentDescription = getString(R.string.boxes_close)
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
            addView(ImageView(this@NotificationsActivity).apply {
                setImageDrawable(ContextCompat.getDrawable(this@NotificationsActivity, R.drawable.ic_box_x)?.mutate())
                setColorFilter(rt.ink)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(px(26f), px(26f), Gravity.CENTER)
            })
        }, LinearLayout.LayoutParams(px(48f), px(48f)))
        root.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22f), 0, px(22f), px(16f))
        }
        empty = TextView(this).apply {
            tag = TAG_EMPTY
            text = getString(R.string.notifications_empty)
            typeface = ThemePaint.typefaceOf(this@NotificationsActivity, rt)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Themes.readableMuted(rt))
            visibility = View.GONE
        }
        body.addView(empty)
        // The id CommsFeedView draws into, as on the home screen.
        feed = LinearLayout(this).apply {
            id = R.id.commsFeed
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        body.addView(feed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onStart() {
        super.onStart()
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.registerReceiver(countsChanged, android.content.IntentFilter(NotificationHub.ACTION_COUNTS_CHANGED))
        lbm.registerReceiver(designChanged, android.content.IntentFilter(DesignSync.ACTION_CHANGED))
        render()
    }

    override fun onStop() {
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.unregisterReceiver(countsChanged)
        lbm.unregisterReceiver(designChanged)
        super.onStop()
    }

    private fun render() = CommsFeedView.render(this)

    /** Every draw of the feed, including one a row's own clear starts, ends here. */
    override fun onFeedWaiting(waiting: Int, listed: Int) {
        empty.visibility = if (feed.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    companion object {
        const val TAG_TITLE = "notifications-title"
        const val TAG_CLOSE = "notifications-close"
        const val TAG_EMPTY = "notifications-empty"

        fun intent(ctx: Context): Intent = Intent(ctx, NotificationsActivity::class.java)
    }
}
