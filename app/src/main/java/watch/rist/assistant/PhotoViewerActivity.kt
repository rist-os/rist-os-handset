package watch.rist.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/** One received picture, full screen: pinch to zoom, drag, double-tap, hold to save. */
class PhotoViewerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "watch.rist.assistant.extra.PHOTO_PATH"
        private const val EXTRA_TITLE = "watch.rist.assistant.extra.PHOTO_TITLE"

        /** Largest edge decoded for the viewer: sharp at full zoom without a huge bitmap. */
        private const val MAX_EDGE = 2400

        fun intent(ctx: Context, photo: File, title: String): Intent =
            Intent(ctx, PhotoViewerActivity::class.java)
                .putExtra(EXTRA_PATH, photo.path)
                .putExtra(EXTRA_TITLE, title)

        /** Offers "Save to Photos" and does it. Shared by the feed and the viewer. */
        fun offerSave(activity: AppCompatActivity, photo: File) {
            val theme = Themes.byId(Config.themeId(activity))
            runCatching {
                RistDialog.choose(
                    activity = activity,
                    t = theme,
                    tf = ThemePaint.typefaceOf(activity, theme),
                    d = activity.resources.displayMetrics.density,
                    title = activity.getString(R.string.photo_menu_title),
                    options = listOf(activity.getString(R.string.photo_save)),
                ) { which ->
                    if (which == 0) {
                        val ok = ReceivedPhotos.saveToLibrary(activity, photo)
                        Toast.makeText(
                            activity,
                            if (ok) R.string.photo_saved else R.string.photo_save_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (ok) Haptics.ack(activity)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }
        // Only a picture this app kept; nothing else can be opened through here.
        val kept = file?.takeIf { it.exists() && it.canonicalPath.startsWith(File(filesDir, "received_photos").canonicalPath) }
        val bitmap = kept?.let { ReceivedPhotos.decode(it, MAX_EDGE, MAX_EDGE) }
        if (kept == null || bitmap == null) {
            Toast.makeText(this, R.string.photo_gone, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val d = resources.displayMetrics.density

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = ZoomImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            contentDescription = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.attach_image_desc) }
            setImageBitmap(bitmap)
            onLongPress = { offerSave(this@PhotoViewerActivity, kept) }
        }
        root.addView(image)

        // A plain white cross on a dark disc: big enough to hit at once, and visible over a
        // light photo as well as the black around it.
        val closeSize = (64 * d).toInt()
        val close = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = getString(R.string.photo_close)
            val pad = (16 * d).toInt()
            setPadding(pad, pad, pad, pad)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
            layoutParams = FrameLayout.LayoutParams(closeSize, closeSize, Gravity.TOP or Gravity.END).apply {
                val m = (12 * d).toInt()
                setMargins(m, m, m, m)
            }
        }
        root.addView(close)

        val caption = intent.getStringExtra(EXTRA_TITLE).orEmpty().trim()
        if (caption.isNotEmpty()) root.addView(TextView(this).apply {
            text = caption
            setTextColor(0xFFDDDDDD.toInt())
            setBackgroundColor(0x99000000.toInt())
            // The system face, not the theme's pixel one: a credit line has to be easy to read.
            typeface = android.graphics.Typeface.DEFAULT
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            // The end is kept clear of the floating home button, which sits in that corner.
            setPadding((16 * d).toInt(), (8 * d).toInt(), (100 * d).toInt(), (8 * d).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
    }
}
