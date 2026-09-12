package watch.rist.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * The moment between choosing a photo and sending it: look at it, drop it, or say what it is
 * about. Until this existed a photo left the phone the instant it was taken, with no way to
 * add a question to it and no way to take it back.
 *
 * Nothing here touches the network. On send the activity hands back the paths that survived
 * and the caption, and the caller sends them as one turn. On cancel it hands back nothing and
 * the caller deletes the files. A removed photo is deleted here, at once.
 */
class PhotoComposeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATHS = "watch.rist.assistant.extra.PHOTO_PATHS"
        const val EXTRA_CAPTION = "watch.rist.assistant.extra.PHOTO_CAPTION"
        private const val TAG = "RistPhotoCompose"
        private const val STATE_PATHS = "paths"
        private const val STATE_SELECTED = "selected"
        private const val THUMB_EDGE_PX = 256
        private const val PREVIEW_EDGE_PX = 1600
    }

    private val paths = ArrayList<String>()
    private var selected = 0
    private val thumbs = HashMap<String, Bitmap>()

    private lateinit var preview: ImageView
    private lateinit var thumbScroll: HorizontalScrollView
    private lateinit var thumbStrip: LinearLayout
    private lateinit var countText: TextView
    private lateinit var caption: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_compose)
        findViewById<View>(R.id.photoComposeRoot).let { root ->
            val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                // The keyboard must push the caption field up, not cover it.
                v.setPadding(
                    base[0] + bars.left, base[1] + bars.top, base[2] + bars.right,
                    base[3] + maxOf(bars.bottom, ime.bottom),
                )
                insets
            }
        }
        preview = findViewById(R.id.preview)
        thumbScroll = findViewById(R.id.thumbScroll)
        thumbStrip = findViewById(R.id.thumbStrip)
        countText = findViewById(R.id.countText)
        caption = findViewById(R.id.captionInput)
        // Single-line input class so the keyboard's action key is Send rather than Enter, but
        // wrapping kept on: a multi-line class would swap that key for a newline.
        caption.setHorizontallyScrolling(false)
        caption.maxLines = 4

        val incoming = savedInstanceState?.getStringArrayList(STATE_PATHS)
            ?: intent.getStringArrayListExtra(EXTRA_PATHS)
        paths.addAll(incoming.orEmpty().filter { File(it).isFile })
        selected = savedInstanceState?.getInt(STATE_SELECTED) ?: 0
        if (paths.isEmpty()) {
            Log.w(TAG, "opened with no readable photo")
            cancel()
            return
        }

        findViewById<View>(R.id.closeButton).setOnClickListener { cancel() }
        findViewById<View>(R.id.removeButton).setOnClickListener { removeSelected() }
        findViewById<View>(R.id.sendPhotoButton).setOnClickListener { send() }
        caption.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_PATHS, ArrayList(paths))
        outState.putInt(STATE_SELECTED, selected)
    }

    private fun render() {
        if (paths.isEmpty()) { cancel(); return }
        selected = selected.coerceIn(0, paths.size - 1)
        preview.setImageBitmap(decode(paths[selected], PREVIEW_EDGE_PX))
        val several = paths.size > 1
        countText.text = if (several) "${selected + 1} / ${paths.size}" else ""
        thumbScroll.visibility = if (several) View.VISIBLE else View.GONE
        thumbStrip.removeAllViews()
        if (!several) return
        val d = resources.displayMetrics.density
        paths.forEachIndexed { i, p ->
            thumbStrip.addView(ImageView(this).apply {
                setImageBitmap(thumbs.getOrPut(p) { decode(p, THUMB_EDGE_PX) ?: return@forEachIndexed })
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt())
                    .apply { rightMargin = (8 * d).toInt() }
                val pad = (3 * d).toInt()
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(if (i == selected) R.drawable.bg_photo_thumb_selected else 0)
                contentDescription = "Photo ${i + 1} of ${paths.size}"
                isClickable = true; isFocusable = true
                setOnClickListener { selected = i; render() }
            })
        }
    }

    private fun removeSelected() {
        if (paths.isEmpty()) { cancel(); return }
        val gone = paths.removeAt(selected.coerceIn(0, paths.size - 1))
        thumbs.remove(gone)
        runCatching { File(gone).delete() }
        if (paths.isEmpty()) { cancel(); return }
        if (selected >= paths.size) selected = paths.size - 1
        render()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun send() {
        if (paths.isEmpty()) { cancel(); return }
        setResult(
            RESULT_OK,
            Intent()
                .putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
                .putExtra(EXTRA_CAPTION, caption.text?.toString()?.trim().orEmpty()),
        )
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        cancel()
    }

    /** The staged files are already upright and re-encoded, so no orientation tag to honour. */
    private fun decode(path: String, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.onFailure { Log.w(TAG, "decode failed", it) }.getOrNull()
}
