package watch.rist.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_JPEG_PATH = "watch.rist.assistant.extra.JPEG_PATH"
        private const val TAG = "RistCamera"
        private const val CAPTURE_FILE = "rist_capture.jpg"
        private const val TARGET_LONG_EDGE = 2560
    }

    private lateinit var textureView: TextureView

    private lateinit var flashButton: TextView
    private lateinit var switchButton: TextView
    private lateinit var liveLayout: View
    private lateinit var reviewOverlay: View
    private lateinit var reviewImage: ImageView

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private var cameraId: String? = null
    private var lensFacing = CameraMetadata.LENS_FACING_BACK
    private var sensorOrientation = 0
    private var jpegSize = Size(1280, 960)
    private var previewSize = Size(1280, 960)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewBuilder: CaptureRequest.Builder? = null

    private var zoomRatio = 1f
    private var zoomRange: Range<Float>? = null
    private var maxDigitalZoom = 1f
    private var activeArray: Rect? = null
    private var scaleDetector: ScaleGestureDetector? = null

    private enum class Flash { OFF, AUTO, ON }
    private var flashMode = Flash.OFF
    private var hasFlash = false

    private var reviewing = false
    private var capturedPath: String? = null

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        findViewById<View>(R.id.cameraRoot).let { root ->
            val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(base[0] + bars.left, base[1] + bars.top, base[2] + bars.right, base[3] + bars.bottom)
                insets
            }
        }
        textureView = findViewById(R.id.viewfinder)
        liveLayout = findViewById(R.id.liveLayout)
        reviewOverlay = findViewById(R.id.reviewOverlay)
        reviewImage = findViewById(R.id.reviewImage)
        flashButton = findViewById(R.id.flashButton)
        switchButton = findViewById(R.id.switchButton)

        findViewById<View>(R.id.shutterButton).setOnClickListener { captureStill() }
        findViewById<View>(R.id.cancelButton).setOnClickListener { finish() }
        switchButton.setOnClickListener { switchCamera() }
        flashButton.setOnClickListener { cycleFlash() }

        findViewById<View>(R.id.discardButton).setOnClickListener { discardReview() }
        findViewById<View>(R.id.useButton).setOnClickListener { acceptReview() }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoomRatio * detector.scaleFactor)
                return true
            }
        })
        textureView.setOnTouchListener { v, event ->
            scaleDetector?.onTouchEvent(event)
            v.performClick()
            true
        }

        switchButton.visibility = if (hasFrontAndBack()) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.camera_permission_denied)); finish(); return
        }
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                    configureTransform(w, h)
                    openCamera()
                }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                    configureTransform(w, h)
                }
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun hasFrontAndBack(): Boolean {
        var front = false
        var back = false
        for (id in cameraManager.cameraIdList) {
            when (cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_FRONT -> front = true
                CameraMetadata.LENS_FACING_BACK -> back = true
            }
        }
        return front && back
    }

    private fun openCamera() {
        try {
            val id = pickCameraId(lensFacing) ?: run {
                toast(getString(R.string.camera_unavailable)); finish(); return
            }
            cameraId = id
            val chars = cameraManager.getCameraCharacteristics(id)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            zoomRatio = 1f

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) configureSizes(map)

            runOnUiThread {
                flashButton.visibility = if (hasFlash) View.VISIBLE else View.GONE
                if (hasFlash) updateFlashLabel()
                configureTransform(textureView.width, textureView.height)
            }

            imageReader = ImageReader.newInstance(
                jpegSize.width, jpegSize.height, android.graphics.ImageFormat.JPEG, 1
            ).apply { setOnImageAvailableListener({ reader -> onJpegAvailable(reader) }, bgHandler) }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                finish(); return
            }
            cameraManager.openCamera(id, stateCallback, bgHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "openCamera failed", t)
            toast(getString(R.string.camera_unavailable)); finish()
        }
    }

    private fun pickCameraId(facing: Int): String? {
        for (id in cameraManager.cameraIdList) {
            if (cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == facing
            ) return id
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun configureSizes(map: StreamConfigurationMap) {
        val jpegSizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
        if (!jpegSizes.isNullOrEmpty()) jpegSize = chooseSize(jpegSizes.toList(), TARGET_LONG_EDGE)
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
        if (!previewSizes.isNullOrEmpty()) previewSize = choosePreviewSize(previewSizes.toList(), 1280, jpegSize)
    }

    private fun chooseSize(sizes: List<Size>, targetLongEdge: Int): Size {
        val fitting = sizes.filter { maxOf(it.width, it.height) <= targetLongEdge }
        return (fitting.ifEmpty { sizes }).maxByOrNull { it.width.toLong() * it.height } ?: sizes.first()
    }

    private fun choosePreviewSize(sizes: List<Size>, targetLongEdge: Int, target: Size): Size {
        val targetAspect = target.width.toDouble() / target.height
        val matching = sizes.filter {
            maxOf(it.width, it.height) <= targetLongEdge &&
                kotlin.math.abs(it.width.toDouble() / it.height - targetAspect) < 0.01
        }
        return matching.maxByOrNull { it.width.toLong() * it.height }
            ?: chooseSize(sizes, targetLongEdge)
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            startPreview()
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close(); cameraDevice = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            Log.e(TAG, "camera error $error")
            device.close(); cameraDevice = null
            runOnUiThread { toast(getString(R.string.camera_unavailable)); finish() }
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)
        val readerSurface = imageReader?.surface ?: return

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
        }
        previewBuilder = builder
        applyZoom(builder)
        applyFlash(builder, forStill = false)

        @Suppress("DEPRECATION")
        device.createCaptureSession(
            listOf(previewSurface, readerSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updateRepeatingRequest()
                    runOnUiThread { configureTransform(textureView.width, textureView.height) }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "capture session config failed")
                }
            },
            bgHandler
        )
    }

    private fun updateRepeatingRequest() {
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        runCatching {
            session.setRepeatingRequest(builder.build(), null, bgHandler)
        }.onFailure { Log.e(TAG, "repeating request failed", it) }
    }

    private fun setZoom(requested: Float) {
        val min = zoomRange?.lower ?: 1f
        val max = zoomRange?.upper ?: maxDigitalZoom.coerceAtLeast(1f)
        zoomRatio = requested.coerceIn(min, max)
        val builder = previewBuilder ?: return
        applyZoom(builder)
        updateRepeatingRequest()
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRange != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            val active = activeArray ?: return
            val z = zoomRatio.coerceIn(1f, maxDigitalZoom.coerceAtLeast(1f))
            val cropW = (active.width() / z).toInt()
            val cropH = (active.height() / z).toInt()
            val left = active.left + (active.width() - cropW) / 2
            val top = active.top + (active.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
        }
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            Flash.OFF -> Flash.AUTO
            Flash.AUTO -> Flash.ON
            Flash.ON -> Flash.OFF
        }
        updateFlashLabel()
        previewBuilder?.let { applyFlash(it, forStill = false) }
        updateRepeatingRequest()
    }

    private fun updateFlashLabel() {
        flashButton.text = getString(
            when (flashMode) {
                Flash.OFF -> R.string.camera_flash_off
                Flash.AUTO -> R.string.camera_flash_auto
                Flash.ON -> R.string.camera_flash_on
            }
        )
    }

    private fun applyFlash(builder: CaptureRequest.Builder, forStill: Boolean) {
        if (!hasFlash) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            return
        }
        when (flashMode) {
            Flash.OFF -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            Flash.AUTO ->
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            Flash.ON -> {
                if (forStill) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
        }
    }

    private fun switchCamera() {
        if (reviewing) return
        if (!hasFrontAndBack()) return
        lensFacing = if (lensFacing == CameraMetadata.LENS_FACING_BACK) {
            CameraMetadata.LENS_FACING_FRONT
        } else {
            CameraMetadata.LENS_FACING_BACK
        }
        zoomRatio = 1f
        flashMode = Flash.OFF
        closeCamera()
        openCamera()
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        if (textureView.surfaceTexture == null) return

        val rotation = @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)

        val scale = maxOf(
            viewHeight.toFloat() / previewSize.height,
            viewWidth.toFloat() / previewSize.width
        )
        matrix.postScale(scale, scale, centerX, centerY)

        when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 ->
                matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            Surface.ROTATION_180 ->
                matrix.postRotate(180f, centerX, centerY)
        }

        if (lensFacing == CameraMetadata.LENS_FACING_FRONT) {
            matrix.postScale(-1f, 1f, centerX, centerY)
        }

        textureView.setTransform(matrix)
    }

    private fun captureStill() {
        if (reviewing) return
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val readerSurface = imageReader?.surface ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                set(CaptureRequest.JPEG_QUALITY, 90.toByte())
            }
            applyZoom(builder)
            applyFlash(builder, forStill = true)
            session.capture(builder.build(), null, bgHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed", t)
        }
    }

    private fun jpegOrientation(): Int {
        val rotation = when (@Suppress("DEPRECATION") windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (lensFacing == CameraMetadata.LENS_FACING_FRONT) {
            (sensorOrientation - rotation + 360) % 360
        } else {
            (sensorOrientation + rotation + 360) % 360
        }
    }

    private fun onJpegAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        var savedPath: String? = null
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val file = File(cacheDir, CAPTURE_FILE)
            file.outputStream().use { it.write(bytes) }
            savedPath = file.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "saving JPEG failed", t)
        } finally {
            image.close()
        }
        savedPath?.let { path -> runOnUiThread { showReview(path) } }
    }

    private fun showReview(path: String) {
        capturedPath = path
        reviewing = true
        val bitmap = decodeUpright(path)
        if (bitmap != null) {
            reviewImage.setImageBitmap(bitmap)
        } else {
            reviewImage.setImageDrawable(null)
        }
        reviewOverlay.visibility = View.VISIBLE
        liveLayout.visibility = View.INVISIBLE
    }

    private fun discardReview() {
        capturedPath?.let { runCatching { File(it).delete() } }
        capturedPath = null
        reviewing = false
        reviewImage.setImageDrawable(null)
        reviewOverlay.visibility = View.GONE
        liveLayout.visibility = View.VISIBLE
    }

    private fun acceptReview() {
        val path = capturedPath ?: return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_JPEG_PATH, path))
        finish()
    }

    private fun decodeUpright(path: String): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 2048)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
            val orientation = ExifInterface(path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) bmp else android.graphics.Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(degrees) }, true
            )
        } catch (t: Throwable) {
            Log.e(TAG, "decode review bitmap failed", t)
            null
        }
    }

    override fun onBackPressed() {
        if (reviewing) discardReview() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun closeCamera() {
        runCatching { captureSession?.close() }; captureSession = null
        runCatching { cameraDevice?.close() }; cameraDevice = null
        runCatching { imageReader?.close() }; imageReader = null
        previewBuilder = null
    }

    private fun startBackgroundThread() {
        bgThread = HandlerThread("RistCamera").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBackgroundThread() {
        bgThread?.quitSafely()
        runCatching { bgThread?.join(500) }
        bgThread = null
        bgHandler = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
