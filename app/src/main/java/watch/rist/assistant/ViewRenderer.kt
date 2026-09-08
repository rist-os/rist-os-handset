package watch.rist.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import rist.v1.Component
import rist.v1.ViewSpec
import java.util.concurrent.TimeUnit

class ViewRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val maxImageBytes: Int = DeviceProfile.maxImageBytes(),
    private val http: OkHttpClient = defaultClient,
) {

    companion object {
        private const val TAG = "RistViewRender"

        private const val MAX_DEPTH = 32

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        fun toEink(src: Bitmap, levels: Int = ImageGray.GRAY_LEVELS): Bitmap {
            val gray = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            Canvas(gray).drawBitmap(src, 0f, 0f, paint)

            if (levels > 1) {
                val px = IntArray(gray.width * gray.height)
                gray.getPixels(px, 0, gray.width, 0, 0, gray.width, gray.height)
                for (i in px.indices) {
                    val p = px[i]
                    val q = ImageGray.quantize((p shr 16) and 0xFF, levels)
                    px[i] = (p and 0xFF000000.toInt()) or (q shl 16) or (q shl 8) or q
                }
                gray.setPixels(px, 0, gray.width, 0, 0, gray.width, gray.height)
            }
            return gray
        }
    }

    fun render(spec: ViewSpec?, container: ViewGroup) {
        container.removeAllViews()
        val root = spec?.root ?: return
        container.addView(buildComponent(root, depth = 0))
    }

    private fun buildComponent(c: Component, depth: Int): View {
        if (depth > MAX_DEPTH) return fallbackView(c)
        return when (ViewLogic.kindOf(c.type)) {
            ViewLogic.Kind.STACK -> buildContainer(c, depth, card = false)
            ViewLogic.Kind.CARD -> buildContainer(c, depth, card = true)
            ViewLogic.Kind.TEXT -> buildText(c)
            ViewLogic.Kind.IMAGE -> buildImage(c)
            ViewLogic.Kind.UNKNOWN -> fallbackView(c)
        }
    }

    private fun buildContainer(c: Component, depth: Int, card: Boolean): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(4); bottomMargin = dp(4)
            }
            if (card) {
                val pad = dp(16)
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(0xFFEFEADF.toInt())
            }
        }
        c.childrenList.forEach { child ->
            layout.addView(buildComponent(child, depth + 1))
        }
        return layout
    }

    private fun buildText(c: Component): View =
        TextView(context).apply {
            text = ViewLogic.textFor(c)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

    private fun buildImage(c: Component): View {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(fallbackView(c))
        }
        when (val src = ViewLogic.resolveImageSource(c.propsMap, maxImageBytes)) {
            is ViewLogic.ImageSource.None -> Unit
            is ViewLogic.ImageSource.Inline -> loadImageAsync(holder, c) { src.bytes }
            is ViewLogic.ImageSource.Url -> loadImageAsync(holder, c) { fetch(src.url) }
        }
        return holder
    }

    private fun loadImageAsync(holder: LinearLayout, c: Component, fetchBytes: () -> ByteArray?) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = fetchBytes() ?: return@runCatching null
                    if (bytes.isEmpty() || bytes.size > maxImageBytes) return@runCatching null
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@runCatching null
                    toEink(decoded)
                }.getOrElse { Log.w(TAG, "image load failed", it); null }
            }
            if (bitmap != null) {
                holder.removeAllViews()
                holder.addView(ImageView(context).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = ViewLogic.fallbackFor(c).ifBlank { null }
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                })
            }
        }
    }

    private fun fetch(url: String): ByteArray? {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            if (body.contentLength() > maxImageBytes) return null
            val bytes = body.bytes()
            return if (bytes.size > maxImageBytes) null else bytes
        }
    }

    private fun fallbackView(c: Component): View =
        TextView(context).apply {
            text = ViewLogic.fallbackFor(c)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF6F6A61.toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
