package watch.rist.assistant

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.view.Surface
import android.hardware.SensorManager
import android.hardware.SensorEventListener
import android.hardware.SensorEvent
import android.hardware.Sensor
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rist.v1.DeviceResponse
import rist.v1.Maneuver
import rist.v1.MediaCommand
import rist.v1.NavCommand
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var titleText: View
    private lateinit var talkButton: View
    private lateinit var recordLabel: TextView
    private lateinit var torchButton: View
    private lateinit var torchIcon: ImageView
    private lateinit var torchSlider: View
    private lateinit var torchSliderFill: View
    private lateinit var torchSliderThumb: View
    private lateinit var textInput: EditText
    private lateinit var sendButton: View
    private lateinit var statusText: TextView
    private lateinit var replyContainer: LinearLayout
    private var activeEntryId: Long = 0L
    private var pendingActionId: String = ""
    private var pendingPrompt: String = ""
    private var pendingSetAtMs: Long = 0L
    private val pendingHandler = Handler(Looper.getMainLooper())
    private lateinit var clearButton: TextView

    private lateinit var nowPlayingCard: View
    private lateinit var npTitle: TextView
    private lateinit var npAuthor: TextView
    private lateinit var npSection: TextView
    private lateinit var npProgress: SeekBar
    private lateinit var npElapsed: TextView
    private lateinit var npDuration: TextView
    private lateinit var npPlayPause: ImageView

    @Volatile private var isScrubbing = false

    private lateinit var navBox: View
    private lateinit var navPill: TextView
    private lateinit var navMap: CorridorMapView
    private lateinit var navBanner: TextView
    private lateinit var navLabel: TextView
    private lateinit var navEta: TextView
    private lateinit var navDest: TextView
    private lateinit var navHere: TextView
    private lateinit var navMode: TextView
    private lateinit var navMinimize: TextView
    private lateinit var navClose: TextView
    private var currentNav: NavCommand? = null
    private var navTurns: List<Maneuver> = emptyList()
    private var navNextTurn: Int = 0
    private var navRnHandle: Long = 0L
    private var navRouteLoaded = false
    private val navRnOut = DoubleArray(9)
    private val navRoutePts = DoubleArray(4096)   // navRnOut = {snap_lat,snap_lon,along,remaining,cross,off_route,next_turn,dist_to_turn,bearing}
    private var navLastSpokenTurn = -1
    private var navLocationListener: LocationListener? = null
    private var navSensorManager: SensorManager? = null
    private var navRotationSensor: Sensor? = null
    private var navMovingUntil = 0L
    private var compassSmoothed = Float.NaN
    private val rotMat = FloatArray(9)
    private val rotMatRemap = FloatArray(9)
    private val orientOut = FloatArray(3)
    @Suppress("DEPRECATION")
    private var cachedRotation: Int = 0
    private var lastFacingSent: Float = Float.NaN
    private val FACING_EPSILON_DEG = 1.5f

    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotMat, e.values)
            val rot = cachedRotation
            val ax: Int; val ay: Int
            when (rot) {
                Surface.ROTATION_90 -> { ax = SensorManager.AXIS_Y; ay = SensorManager.AXIS_MINUS_X }
                Surface.ROTATION_180 -> { ax = SensorManager.AXIS_MINUS_X; ay = SensorManager.AXIS_MINUS_Y }
                Surface.ROTATION_270 -> { ax = SensorManager.AXIS_MINUS_Y; ay = SensorManager.AXIS_X }
                else -> { ax = SensorManager.AXIS_X; ay = SensorManager.AXIS_Y }
            }
            SensorManager.remapCoordinateSystem(rotMat, ax, ay, rotMatRemap)
            SensorManager.getOrientation(rotMatRemap, orientOut)
            var az = Math.toDegrees(orientOut[0].toDouble()).toFloat()
            if (az < 0f) az += 360f
            compassSmoothed = if (compassSmoothed.isNaN()) az else {
                val d = (((az - compassSmoothed) % 360f) + 540f) % 360f - 180f
                (compassSmoothed + d * 0.15f + 360f) % 360f
            }
            if (::navMap.isInitialized && kotlin.math.abs(
                    (((compassSmoothed - lastFacingSent) % 360f) + 540f) % 360f - 180f
                ) >= FACING_EPSILON_DEG
            ) {
                lastFacingSent = compassSmoothed
                navMap.setFacing(compassSmoothed)
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
    private var lastNavFix: LocationProvider.Fix? = null
    private var navigating = false
    private var rerouting = false

    private var lastPhotoPath: String? = null

    private val isDebugBuild: Boolean
        get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var torchCameraId: String? = null
    private var torchOn = false

    private var torchBrightnessSupported = false
    private var torchMaxLevel = 1
    private var torchDefaultLevel = 1
    private var torchLevel = 1

    private val torchHandler = Handler(Looper.getMainLooper())
    private var torchLongPressRunnable: Runnable? = null
    private var torchGestureActive = false
    private var torchSliderEngaged = false
    private var torchDownX = 0f
    private var torchDownY = 0f
    private var torchTouchSlop = 0

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) { torchOn = enabled; updateTorchUi() }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == torchCameraId) { torchOn = false; updateTorchUi() }
        }
    }

    internal var attachmentGeneration = 0

    internal var lastAttachments: List<RistAttachment> = emptyList()

    internal var lastAttachmentsEntryId: Long = 0L

    private var attachmentJob: Job? = null

    private val uiScope = MainScope()
    private val viewRenderer by lazy { ViewRenderer(this, uiScope) }

    private val mediaHandler = Handler(Looper.getMainLooper())
    private var pendingMediaStart: Runnable? = null

    private val startupPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val micGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
                hasPermission(Manifest.permission.RECORD_AUDIO)
            if (!micGranted) status("Microphone permission denied — hold-to-talk disabled.")
            refreshTalkEnabled()
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera() else toast(getString(R.string.camera_permission_denied))
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) { status("camera cancelled"); return@registerForActivityResult }
            val path = result.data?.getStringExtra(CameraActivity.EXTRA_JPEG_PATH)
            if (path != null) onPhotoCaptured(path)
        }

    private val pushReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(PushService.EXTRA_MESSAGE).orEmpty()
            status("push ◀ $msg")
        }
    }

    private val mediaStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            status(intent.getStringExtra(PlaybackService.EXTRA_MEDIA_STATUS).orEmpty())
        }
    }

    private val streamProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bytes = intent.getByteArrayExtra(StreamingStatus.EXTRA_PROGRESS_PROTO) ?: return
            val progress = runCatching { rist.v1.Progress.parseFrom(bytes) }.getOrNull() ?: return
            val line = StreamingWire.renderLine(progress) ?: return
            statusText.text = line
            keepAwake(AWAKE_SHORT_MS)
        }
    }

    private val streamEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ending = intent.getStringExtra(StreamingStatus.EXTRA_ENDING).orEmpty()
            statusText.text = when (ending) {
                StreamingStatus.ENDING_CANCELLED -> getString(R.string.status_idle)
                StreamingStatus.ENDING_FINAL -> getString(R.string.status_idle)
                else -> "Sorry — " + StreamingStatus.failureFor(ending) + "."
            }
        }
    }

    private val nowPlayingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateNowPlaying(intent)
    }

    private val replyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(RecordService.EXTRA_REPLY_TEXT).orEmpty()
            keepAwake(awakeWindowFor(text))
            val st = intent.getStringExtra(RecordService.EXTRA_REPLY_STATUS).orEmpty()
            val proto = intent.getByteArrayExtra(RecordService.EXTRA_REPLY_PROTO)
            val reply = proto?.let { runCatching { DeviceResponse.parseFrom(it) }.getOrNull() }
            val userCancelled = reply == null && StreamingCancel.takeCancelledFlag()
            recordLabel.text = getString(R.string.record_label)
            setKnobRecording(false)
            if (activeEntryId != 0L) runCatching {
                val answered = reply != null || text.isNotBlank()
                Transcript.update(
                    this@MainActivity, activeEntryId,
                    state = if (answered) EntryState.ANSWERED else EntryState.FAILED,
                    answer = reply?.speech?.text?.takeIf { it.isNotBlank() } ?: text,
                    requestId = reply?.requestId.orEmpty(),
                    error = if (answered) "" else if (userCancelled) "cancelled" else st.ifBlank { "no reply" },
                )
            }
            activeEntryId = 0L
            if (userCancelled) statusText.text = getString(R.string.status_idle)
            else status(if (text.isBlank()) st else "$st\n  “$text”")
            renderReply(reply, fallbackText = text)
        }
    }

    private val awakeHandler = Handler(Looper.getMainLooper())
    private val AWAKE_SHORT_MS = 20_000L
    private val AWAKE_LONG_MS = 120_000L
    private val clearAwakeRunnable = Runnable {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun keepAwake(ms: Long = AWAKE_SHORT_MS) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        awakeHandler.removeCallbacks(clearAwakeRunnable)
        awakeHandler.postDelayed(clearAwakeRunnable, ms)
    }

    private fun awakeWindowFor(text: String): Long {
        if (pendingActionId.isNotBlank() || navigating || awaitingUserReply) return AWAKE_LONG_MS
        val read = 3_000L + (text.length * 65L)
        return read.coerceIn(AWAKE_SHORT_MS, AWAKE_LONG_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyTheme()

        val root = findViewById<View>(R.id.root)
        val basePad = root.paddingTop
        val talkTile = findViewById<View>(R.id.talkButton)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val bottomInset = maxOf(ime.bottom, nav.bottom)
            v.updatePadding(
                left = bars.left,
                top = basePad + bars.top,
                right = bars.right,
                bottom = basePad + bottomInset
            )
            talkTile.visibility =
                if (insets.isVisible(WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            insets
        }

        titleText = findViewById(R.id.titleText)
        talkButton = findViewById(R.id.talkButton)
        recordLabel = findViewById(R.id.recordLabel)
        torchButton = findViewById(R.id.torchButton)
        torchIcon = findViewById(R.id.torchIcon)
        torchSlider = findViewById(R.id.torchSlider)
        torchSliderFill = findViewById(R.id.torchSliderFill)
        torchSliderThumb = findViewById(R.id.torchSliderThumb)
        textInput = findViewById(R.id.textInput)
        sendButton = findViewById(R.id.sendButton)
        statusText = findViewById(R.id.statusText)
        statusText.setOnLongClickListener { startActivity(Intent(this, SettingsActivity::class.java)); true }
        statusText.setOnClickListener { cancelInFlightTurn() }
        findViewById<ImageView>(R.id.settingsGear)?.setOnClickListener { toggleAppDrawer() }
        wireAppDrawer()
        NotificationHub.ensureListenerEnabled(this)
        replyContainer = findViewById(R.id.replyContainer)
        clearButton = findViewById(R.id.clearButton)
        clearButton.setOnClickListener { clearReply() }

        nowPlayingCard = findViewById(R.id.nowPlayingCard)
        npTitle = findViewById(R.id.npTitle)
        npAuthor = findViewById(R.id.npAuthor)
        npSection = findViewById(R.id.npSection)
        npProgress = findViewById(R.id.npProgress)
        npElapsed = findViewById(R.id.npElapsed)
        npDuration = findViewById(R.id.npDuration)
        npPlayPause = findViewById(R.id.npPlayPause)
        npPlayPause.setOnClickListener { PlaybackService.togglePlayPause(this) }
        findViewById<TextView>(R.id.npClose)?.setOnClickListener {
            PlaybackService.closePlayer(this)
            nowPlayingCard.visibility = View.GONE
        }

        // Scrub bar units are seconds; no seek when max == 0.
        npProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) npElapsed.text = fmtTime(progress * 1000L)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) { isScrubbing = true }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isScrubbing = false
                if (seekBar.max > 0) PlaybackService.seekToSeconds(this@MainActivity, seekBar.progress)
            }
        })
        findViewById<View>(R.id.npNext).setOnClickListener { PlaybackService.skipNext(this) }
        findViewById<View>(R.id.npPrev).setOnClickListener { PlaybackService.skipPrevious(this) }
        findViewById<View>(R.id.npBack10).setOnClickListener { PlaybackService.skipBack10(this) }
        findViewById<View>(R.id.npForward10).setOnClickListener { PlaybackService.skipForward10(this) }

        navBox = findViewById(R.id.navBox)
        navPill = findViewById(R.id.navPill)
        navMap = findViewById(R.id.navMap)
        navBanner = findViewById(R.id.navBanner)
        navLabel = findViewById(R.id.navLabel)
        navEta = findViewById(R.id.navEta)
        navDest = findViewById(R.id.navDest)
        navHere = findViewById(R.id.navHere)
        navMode = findViewById(R.id.navMode)
        navMinimize = findViewById(R.id.navMinimize)
        navClose = findViewById(R.id.navClose)
        navMinimize.setOnClickListener { minimizeNav() }
        navPill.setOnClickListener { expandNav() }
        navClose.setOnClickListener { closeNav() }

        wireKioskUi()

        talkButton.contentDescription = getString(R.string.talk_button_desc)
        talkButton.setOnClickListener {
            if (accessibilityRecording) {
                accessibilityRecording = false
                stopRecord()
            } else {
                accessibilityRecording = true
                startRecord(); keepAwake()
            }
        }
        androidx.core.view.ViewCompat.replaceAccessibilityAction(
            talkButton, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            getString(R.string.talk_button_a11y_action), null
        )

        var talkSwallowedForIme = false
        var talkDraggedOut = false
        talkButton.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val imeUp = ViewCompat.getRootWindowInsets(v)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    if (imeUp) {
                        talkSwallowedForIme = true
                        hideKeyboard()
                    } else {
                        talkSwallowedForIme = false
                        talkDraggedOut = false
                        v.isPressed = true; startRecord(); keepAwake()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!talkSwallowedForIme) {
                        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                        val out = event.x < -slop || event.y < -slop ||
                            event.x > v.width + slop || event.y > v.height + slop
                        if (out != talkDraggedOut) {
                            talkDraggedOut = out
                            v.isPressed = !out
                            recordLabel.text =
                                if (out) getString(R.string.release_to_cancel)
                                else getString(R.string.recording_label)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!talkSwallowedForIme) {
                        v.isPressed = false
                        val heldMs = event.eventTime - event.downTime
                        if (event.actionMasked == MotionEvent.ACTION_CANCEL || talkDraggedOut ||
                            heldMs < MIN_RECORD_HOLD_MS) {
                            cancelRecord()
                        } else {
                            stopRecord()
                        }
                    }
                    talkSwallowedForIme = false
                    talkDraggedOut = false
                    true
                }
                else -> false
            }
        }

        setupTorch()

        sendButton.setOnClickListener { sendTypedText() }
        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTypedText(); true } else false
        }

        if (startupPermissions.any { !hasPermission(it) }) {
            permissionLauncher.launch(startupPermissions)
        }
        runCatching { DeviceCommands.restoreTimers(applicationContext) }
        Config.importTokenFileIfPresent(applicationContext)
        SmsResultReceiver.register(applicationContext)
        refreshTalkEnabled()
    }

    override fun onDestroy() {
        mediaHandler.removeCallbacksAndMessages(null)
        staleHandler.removeCallbacksAndMessages(null)
        torchHandler.removeCallbacksAndMessages(null)
        stopNavLocationUpdates()
        if (::navMap.isInitialized) navMap.release()
        pendingMediaStart = null
        torchCameraId?.let { id ->
            if (torchOn) runCatching { cameraManager.setTorchMode(id, false) }
            runCatching { cameraManager.unregisterTorchCallback(torchCallback) }
        }
        if (navRnHandle != 0L) { runCatching { Ristnav.nDestroy(navRnHandle) }; navRnHandle = 0L }
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.registerReceiver(pushReceiver, IntentFilter(PushService.ACTION_PUSH_MESSAGE))
        lbm.registerReceiver(replyReceiver, IntentFilter(RecordService.ACTION_ASSISTANT_REPLY))
        lbm.registerReceiver(mediaStatusReceiver, IntentFilter(PlaybackService.ACTION_MEDIA_STATUS))
        lbm.registerReceiver(nowPlayingReceiver, IntentFilter(PlaybackService.ACTION_NOW_PLAYING))
        lbm.registerReceiver(notifCountsReceiver, IntentFilter(NotificationHub.ACTION_COUNTS_CHANGED))
        lbm.registerReceiver(captureDiscardedReceiver, IntentFilter(RecordService.ACTION_CAPTURE_DISCARDED))
        lbm.registerReceiver(cmdStateReceiver, IntentFilter(DeviceCommands.ACTION_STATE_CHANGED))
        lbm.registerReceiver(streamProgressReceiver, IntentFilter(StreamingStatus.ACTION_PROGRESS))
        lbm.registerReceiver(streamEndedReceiver, IntentFilter(StreamingStatus.ACTION_STREAM_ENDED))
        runCatching {
            registerReceiver(timeTickReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            })
        }
        updateGlance()
    }

    private fun recordSetupReached() {
        if (!Enrolment.needed(this) && !Config.isSetupComplete(this)) {
            Config.setSetupComplete(this, true)
        }
    }

    override fun onResume() {
        super.onResume()
        Config.importTokenFileIfPresent(applicationContext)
        CarrierVoicemail.listen(this)
        CarrierVoicemail.refresh(this)
        if (navigating) runCatching {
            navRotationSensor?.let {
                @Suppress("DEPRECATION")
                run { cachedRotation = windowManager.defaultDisplay.rotation }
                navSensorManager?.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        applyTheme()
        refreshTalkEnabled()
        refreshGearBadge()
        recordSetupReached()
        runCatching {
            val v = Config.brightness(this)
            window.attributes = window.attributes.apply {
                screenBrightness = if (v < 0f) -1f else v.coerceIn(0.05f, 1f)
            }
        }
        renderTranscript()
        renderCommandStrip()
        CommsFeedView.render(this)
        cmdHandler.removeCallbacks(cmdTicker)
        if (DeviceCommands.anythingRunning()) cmdHandler.post(cmdTicker)
        enterKioskIfOwner()
        // Captured before askIfDue, which stamps asked-at and makes isDue() false.
        val networkQuestionWasDue = NetworkLocationConsent.isDue(this)
        NetworkLocationPromptActivity.askIfDue(this)
        if (!networkQuestionWasDue) BackgroundLocationPromptActivity.askIfDue(this)
    }

    private val pixelTf by lazy { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.pixel) }
    private fun dpPx(v: Float) = (v * resources.displayMetrics.density)
    private fun blend(x: Int, y: Int, f: Float): Int = android.graphics.Color.rgb(
        (android.graphics.Color.red(x) * (1 - f) + android.graphics.Color.red(y) * f).toInt(),
        (android.graphics.Color.green(x) * (1 - f) + android.graphics.Color.green(y) * f).toInt(),
        (android.graphics.Color.blue(x) * (1 - f) + android.graphics.Color.blue(y) * f).toInt())
    private fun themedTile(t: RistTheme): android.graphics.drawable.Drawable {
        if (!t.tile) {
            val clear = { android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT) }
            return android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), clear())
                addState(intArrayOf(), clear())
            }
        }
        val pressed = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (t.floodOnPress) t.accent else blend(t.tileFill, t.accent, 0.10f))
            cornerRadius = dpPx(t.tileRadiusDp)
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), Themes.tileFace(t, resources.displayMetrics.density))
        }
    }
    private fun themedField(t: RistTheme) = android.graphics.drawable.GradientDrawable().apply {
        setColor(t.fieldFill ?: android.graphics.Color.TRANSPARENT)
        setStroke(dpPx(t.borderWidthDp).toInt().coerceAtLeast(1), t.fieldBorder)
        cornerRadius = dpPx(t.fieldRadiusDp)
    }

    private fun themedPanel(t: RistTheme) = android.graphics.drawable.GradientDrawable().apply {
        setColor(t.ground)
        setStroke(dpPx(t.borderWidthDp).toInt().coerceAtLeast(1), t.fieldBorder)
        cornerRadius = dpPx(t.tileRadiusDp)
    }

    private fun applyTheme() = runCatching {
        val t = Themes.byId(Config.themeId(this))
        val tf = when (t.font) {
            "pixel" -> pixelTf
            "mono"  -> android.graphics.Typeface.MONOSPACE
            "serif" -> android.graphics.Typeface.SERIF
            else    -> android.graphics.Typeface.SANS_SERIF
        }
        val displayTf = when (t.displayFont) {
            "pixel" -> pixelTf
            "mono" -> android.graphics.Typeface.MONOSPACE
            "serif" -> android.graphics.Typeface.SERIF
            else -> android.graphics.Typeface.SANS_SERIF
        }
        val faint = t.inkFaint ?: blend(t.ink, t.ground, 0.5f)
        val muted = Themes.readableMuted(t)
        findViewById<View>(R.id.root)?.setBackgroundColor(t.ground)
        findViewById<ImageView>(R.id.titleText)?.setColorFilter(t.ink)
        findViewById<TextView>(R.id.statusText)?.apply { setTextColor(muted); typeface = tf }
        findViewById<TextView>(R.id.recordLabel)?.apply {
            setTextColor(if (t.holdOnAccent) t.accent else t.inkMuted)
            typeface = displayTf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (t.holdOnAccent) 14f else 15f)
        }
        findViewById<TextView>(R.id.clockText)?.apply {
            setTextColor(if (t.id == "night") 0xFFE9EFE4.toInt() else t.ink)
            typeface = displayTf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (t.id == "night") 45f else 48f)
        }
        findViewById<TextView>(R.id.dateText)?.apply {
            setTextColor(muted); typeface = tf
            letterSpacing = if (t.id == "night") 0.14f else 0.05f
            isAllCaps = (t.id == "night")
        }
        findViewById<TextView>(R.id.recordCancel)?.apply { setTextColor(muted); typeface = tf }
        findViewById<TextView>(R.id.clearButton)?.apply { setTextColor(muted); typeface = tf }
        findViewById<TextView>(R.id.commandText)?.apply { typeface = tf }
        updateGlance()
        findViewById<ImageView>(R.id.settingsGear)?.setColorFilter(t.inkMuted)
        (findViewById<View>(R.id.replyContainer) as? android.view.ViewGroup)?.let { rc ->
            for (i in 0 until rc.childCount) (rc.getChildAt(i) as? TextView)?.apply { setTextColor(t.ink); typeface = tf }
        }
        findViewById<View>(R.id.talkButton)?.background = themedTile(t)
        findViewById<ImageView>(R.id.recordGlyph)?.apply {
            setImageDrawable(null); clearColorFilter()
            knobDrawable = Themes.knob(
                t, resources.displayMetrics.density,
                androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_mic)?.mutate()
            ) as? KnobDrawable
            knobDrawable?.recording = recordingNow
            background = knobDrawable
        }
        findViewById<EditText>(R.id.textInput)?.apply {
            background = themedField(t); setTextColor(t.ink); setHintTextColor(faint); typeface = tf
        }
        updateTorchUi()
        findViewById<ImageView>(R.id.sendButton)?.apply {
            setImageResource(if (t.lineIcons) R.drawable.ic_send_line else R.drawable.ic_send)
            setColorFilter(t.accent)
        }
        retintUnthemedSubtree(findViewById(R.id.nowPlayingCard), t, faint, tf)
        retintUnthemedSubtree(findViewById(R.id.navBox), t, faint, tf)
        retintUnthemedSubtree(findViewById(R.id.navPill), t, faint, tf)
        retintUnthemedSubtree(findViewById(R.id.appDrawerList), t, faint, tf)
        findViewById<View>(R.id.appDrawer)?.background = themedPanel(t)
        findViewById<View>(R.id.navBox)?.background = themedField(t)
        findViewById<View>(R.id.navPill)?.background = themedField(t)
        findViewById<View>(R.id.navMap)?.setBackgroundColor(t.ground)

        window.statusBarColor = t.ground
        runCatching {
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .isAppearanceLightStatusBars = !t.dark
        }
    }

    private fun retintUnthemedSubtree(
        root: View?, t: RistTheme, faint: Int, tf: android.graphics.Typeface?,
    ) = ThemePaint.retint(this, root, t, faint, tf)

    private fun enterKioskIfOwner() {
        if (!KioskManager.isDeviceOwner(this)) return
        KioskManager.ensureConfigured(this)
        OverlayHomeService.setVisible(this, false)
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask failed", e)
        }
    }

    private val maintenanceHoldHandler = Handler(Looper.getMainLooper())
    private var maintenanceArmed: Runnable? = null

    private fun wireKioskUi() {
        findViewById<View>(R.id.maintenanceHotspot)?.apply {
            isClickable = false
            isFocusable = false
        }
    }

    private fun trackMaintenanceHold(ev: MotionEvent) {
        val hotspot = findViewById<View>(R.id.maintenanceHotspot) ?: return
        fun disarm() { maintenanceArmed?.let { maintenanceHoldHandler.removeCallbacks(it) }; maintenanceArmed = null }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = Rect().also { hotspot.getGlobalVisibleRect(it) }
                if (hotspot.isShown && r.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    disarm()
                    val run = Runnable { maintenanceArmed = null; promptMaintenanceExit() }
                    maintenanceArmed = run
                    maintenanceHoldHandler.postDelayed(run, MAINTENANCE_HOLD_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (maintenanceArmed != null) {
                    val r = Rect().also { hotspot.getGlobalVisibleRect(it) }
                    if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) disarm()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> disarm()
        }
    }

    private fun promptMaintenanceExit() {
        AlertDialog.Builder(this)
            .setTitle("Maintenance")
            .setMessage("Exit kiosk lock and open system Settings?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Exit kiosk") { _, _ ->
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask()
                } catch (e: Exception) {
                    Log.w(TAG, "stopLockTask failed", e)
                }
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w(TAG, "open Settings failed", e)
                }
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (KioskManager.isDeviceOwner(this)) OverlayHomeService.setVisible(this, true)
    }

    override fun onStop() {
        super.onStop()
        if (appDrawerOpen) closeAppDrawer()
        runCatching { navSensorManager?.unregisterListener(compassListener) }
        cmdHandler.removeCallbacks(cmdTicker)
        if (KioskManager.isDeviceOwner(this)) OverlayHomeService.setVisible(this, true)
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.unregisterReceiver(cmdStateReceiver)
        lbm.unregisterReceiver(captureDiscardedReceiver)
        lbm.unregisterReceiver(notifCountsReceiver)
        lbm.unregisterReceiver(pushReceiver)
        lbm.unregisterReceiver(replyReceiver)
        runCatching { unregisterReceiver(timeTickReceiver) }
        lbm.unregisterReceiver(mediaStatusReceiver)
        lbm.unregisterReceiver(nowPlayingReceiver)
        lbm.unregisterReceiver(streamProgressReceiver)
        lbm.unregisterReceiver(streamEndedReceiver)
    }

    private fun renderAwaitingReply() = runCatching {
        recordLabel.text =
            if (awaitingUserReply) getString(R.string.record_label_answer)
            else getString(R.string.record_label)
        if (awaitingUserReply) runCatching {
            talkButton.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }.let { }

    private fun updateGlance() = runCatching {
        val t = Themes.byId(Config.themeId(this))
        val now = java.util.Date()
        val locale = java.util.Locale.getDefault()
        val timePart = java.text.SimpleDateFormat("h:mm", locale).format(now)
        val marker = java.text.SimpleDateFormat("a", locale).format(now).lowercase(locale)
        findViewById<TextView>(R.id.clockText)?.text = if (marker.isBlank()) {
            // A locale whose 12-hour format has no marker: an empty span range would crash.
            timePart
        } else {
            val full = "$timePart $marker"
            android.text.SpannableString(full).apply {
                setSpan(
                    android.text.style.RelativeSizeSpan(0.4f),
                    timePart.length, full.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        findViewById<TextView>(R.id.dateText)?.text = java.text.SimpleDateFormat(
            if (t.id == "night") "EEE d MMM" else "EEEE, d MMMM", java.util.Locale.getDefault()
        ).format(now)
    }.let { }

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateGlance()
            CommsFeedView.render(this@MainActivity)
        }
    }

    private var knobDrawable: KnobDrawable? = null

    private var recordingNow = false

    private fun setKnobRecording(on: Boolean) {
        recordingNow = on
        knobDrawable?.recording = on
    }

    private fun startRecord() {
        cancelInFlightTurn()
        awaitingUserReply = false
        runCatching { Config.setAwaitingReply(this, false) }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            permissionLauncher.launch(startupPermissions); return
        }
        val svc = Intent(this, RecordService::class.java).setAction(RecordService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
        recordLabel.text = getString(R.string.recording_label)
        setKnobRecording(true)
        status("● recording…")
        findViewById<TextView>(R.id.recordCancel)?.apply {
            visibility = View.VISIBLE
            setOnClickListener { cancelRecord() }
        }
        window.decorView.post {
            activeEntryId = runCatching { Transcript.begin(this, "(voice)", EntryState.RECORDING) }.getOrDefault(0L)
            renderTranscript()
        }
    }

    private val cmdTicker = object : Runnable {
        override fun run() {
            runCatching { renderCommandStrip() }
            if (DeviceCommands.anythingRunning()) cmdHandler.postDelayed(this, 1000L)
        }
    }
    private val cmdHandler = Handler(Looper.getMainLooper())

    private fun renderCommandStrip() {
        val row = findViewById<LinearLayout>(R.id.commandStrip) ?: return
        val label = findViewById<TextView>(R.id.commandText) ?: return
        val holder = findViewById<LinearLayout>(R.id.commandButtons) ?: return
        val t = Themes.byId(Config.themeId(this))
        val tf = when (t.font) {
            "pixel" -> pixelTf; "mono" -> android.graphics.Typeface.MONOSPACE
            "serif" -> android.graphics.Typeface.SERIF; else -> android.graphics.Typeface.SANS_SERIF
        }
        val d = resources.displayMetrics.density
        val timer = DeviceCommands.timerText()
        val sw = DeviceCommands.stopwatchText()

        label.visibility = View.GONE
        holder.removeAllViews()

        val ctx = applicationContext
        fun chip(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(t.accent); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((10 * d).toInt(), 0, (2 * d).toInt(), 0)
        }
        fun button(glyph: String, desc: String, act: () -> Unit) = TextView(this).apply {
            text = glyph
            contentDescription = desc
            setTextColor(t.accent); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = android.view.Gravity.CENTER
            minWidth = (48 * d).toInt()
            minHeight = (48 * d).toInt()
            setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener {
                runCatching { act() }
                renderCommandStrip()
                cmdHandler.removeCallbacks(cmdTicker)
                if (DeviceCommands.anythingRunning()) cmdHandler.post(cmdTicker)
            }
        }

        var any = false
        fun row(): LinearLayout {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = android.view.Gravity.CENTER_VERTICAL
            holder.addView(r)
            return r
        }

        if (DeviceCommands.ringing()) {
            any = true
            val r = row()
            r.addView(chip("⏰ " + DeviceCommands.ringingLabel))
            r.addView(button("✕", "Stop alarm") { AlarmService.dismiss(ctx, "the stop button") })
        }

        if (DeviceCommands.stopwatchPresent()) {
            any = true
            val r = row()
            r.addView(chip("⏲ " + DeviceCommands.stopwatchText()))
            r.addView(
                if (DeviceCommands.stopwatchRunning())
                    button("❚❚", "Pause stopwatch") { DeviceCommands.stopwatchAction(ctx, "stop") }
                else
                    button("▶", "Resume stopwatch") { DeviceCommands.stopwatchAction(ctx, "start") }
            )
            r.addView(button("↺", "Reset stopwatch") { DeviceCommands.stopwatchAction(ctx, "reset") })
            r.addView(button("✕", "Delete stopwatch") { DeviceCommands.clearStopwatch(ctx) })
        }

        for (tv in DeviceCommands.timerViews()) {
            any = true
            val r = row()
            r.addView(chip("⏱ " + tv.text + (if (tv.label.isNotBlank()) " " + tv.label else "")))
            r.addView(
                if (tv.paused) button("▶", "Resume ${tv.label.ifBlank { "timer" }}") {
                    DeviceCommands.timerAction(ctx, "resume", tv.label)
                } else button("❚❚", "Pause ${tv.label.ifBlank { "timer" }}") {
                    DeviceCommands.timerAction(ctx, "pause", tv.label)
                }
            )
            r.addView(button("↺", "Restart ${tv.label.ifBlank { "timer" }}") {
                DeviceCommands.timerAction(ctx, "restart", tv.label)
            })
            r.addView(button("✕", "Cancel ${tv.label.ifBlank { "timer" }}") {
                DeviceCommands.timerAction(ctx, "cancel", tv.label)
            })
        }

        row.visibility = if (any) View.VISIBLE else View.GONE
    }

    private val cmdStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            renderCommandStrip()
            cmdHandler.removeCallbacks(cmdTicker)
            if (DeviceCommands.anythingRunning()) cmdHandler.post(cmdTicker)
        }
    }

    private var appDrawerOpen = false

    private var accessibilityRecording = false

    private var awaitingUserReply = false

    private fun wireAppDrawer() = runCatching {
        findViewById<View>(R.id.appDrawerScrim)?.setOnClickListener { closeAppDrawer() }
        fun launch(id: Int, go: () -> Unit) =
            findViewById<View>(id)?.setOnClickListener { closeAppDrawer(); go() }
        launch(R.id.drawerPhone) { AppLauncher.launchDialer(this) }
        launch(R.id.drawerMsg)   { AppLauncher.launchMessaging(this) }
        launch(R.id.drawerCam)   { AppLauncher.launchCamera(this) }
        launch(R.id.drawerPics)  { AppLauncher.launchGallery(this) }
        launch(R.id.drawerMaps)  { AppLauncher.launchMaps(this) }
        findViewById<View>(R.id.drawerSettings)?.setOnClickListener {
            closeAppDrawer()
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_HIDE_APPS, true)
            )
        }
        val cb = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { closeAppDrawer() }
        }
        backCallback = cb
        onBackPressedDispatcher.addCallback(this, cb)
    }.onFailure { Log.w(TAG, "app drawer wiring failed", it) }.let { }

    private var backCallback: androidx.activity.OnBackPressedCallback? = null

    private fun toggleAppDrawer() = if (appDrawerOpen) closeAppDrawer() else openAppDrawer()

    private fun openAppDrawer() = runCatching {
        val panel = findViewById<View>(R.id.appDrawer) ?: return@runCatching
        val scrim = findViewById<View>(R.id.appDrawerScrim)
        refreshDrawerBadges()
        appDrawerOpen = true
        backCallback?.isEnabled = true
        scrim?.apply { alpha = 0f; visibility = View.VISIBLE; animate().alpha(1f).setDuration(140).start() }
        findViewById<View>(R.id.gearBadge)?.visibility = View.GONE

        // INVISIBLE, not VISIBLE: lays the panel out for a measured width without drawing a frame.
        panel.visibility = View.INVISIBLE
        panel.post {
            val off = if (panel.width > 0) panel.width.toFloat()
                      else 112f * resources.displayMetrics.density
            panel.translationX = off
            panel.visibility = View.VISIBLE
            panel.animate().translationX(0f).setDuration(180).start()
        }
    }.let { }

    private fun closeAppDrawer() = runCatching {
        val panel = findViewById<View>(R.id.appDrawer) ?: return@runCatching
        val scrim = findViewById<View>(R.id.appDrawerScrim)
        appDrawerOpen = false
        backCallback?.isEnabled = false
        scrim?.animate()?.alpha(0f)?.setDuration(120)?.withEndAction { scrim.visibility = View.GONE }?.start()
        panel.animate().translationX(panel.width.toFloat()).setDuration(150)
            .withEndAction { panel.visibility = View.GONE; panel.translationX = 0f; refreshGearBadge() }.start()
    }.let { }

    private fun refreshDrawerBadges() = runCatching {
        // maxOf, not plus: both sources count the same arrival.
        val c = drawerCounts()

        NotificationHub.applyBadge(findViewById(R.id.drawerBadgePhone), c.phone)
        NotificationHub.applyBadge(findViewById(R.id.drawerBadgeMsg),  c.msg)
        NotificationHub.applyBadge(findViewById(R.id.drawerBadgeCam),  c.cam)
        NotificationHub.applyBadge(findViewById(R.id.drawerBadgePics), c.pics)
        NotificationHub.applyBadge(findViewById(R.id.drawerBadgeMaps), c.maps)
        NotificationHub.applyBadge(findViewById(R.id.drawerBadgeSet),  c.settings)
        findViewById<View>(R.id.drawerVoicemail)?.visibility = View.GONE
    }.let { }

    private fun drawerCounts(): DrawerBadges.Counts {
        val w = runCatching { CommsFeedView.waiting(this) }.getOrNull()
        return DrawerBadges.of(
            w,
            phone = NotificationHub.phone(this),
            messages = NotificationHub.messages(),
            camera = NotificationHub.camera(),
            gallery = NotificationHub.gallery(),
            maps = NotificationHub.maps(),
            settings = NotificationHub.settings(),
        )
    }

    private fun refreshGearBadge() = runCatching {
        val badge = findViewById<android.widget.TextView>(R.id.gearBadge) ?: return@runCatching
        if (appDrawerOpen) { badge.visibility = View.GONE; return@runCatching }
        NotificationHub.applyBadge(badge, drawerCounts().total)
    }

    private val captureDiscardedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (activeEntryId != 0L) {
                runCatching { Transcript.discard(this@MainActivity, activeEntryId) }
                activeEntryId = 0L
                renderTranscript()
            }
            recordLabel.text = getString(R.string.record_label)
            setKnobRecording(false)
            statusText.text = getString(R.string.status_idle)
        }
    }

    private val notifCountsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (appDrawerOpen) refreshDrawerBadges()
            refreshGearBadge()
            CommsFeedView.render(this@MainActivity)
            renderCommandStrip()
        }
    }

    private fun cancelInFlightTurn(): Boolean {
        val id = StreamingCancel.cancelInFlight()
        if (id.isEmpty()) return false
        statusText.text = getString(R.string.status_idle)
        uiScope.launch {
            withContext(Dispatchers.IO) { StreamingCancel.notifyBackend(applicationContext, id) }
        }
        return true
    }

    private fun cancelRecord() {
        accessibilityRecording = false
        val svc = Intent(this, RecordService::class.java).setAction(RecordService.ACTION_CANCEL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
        // INVISIBLE, not GONE: keeps the height so the ring does not shift.
        findViewById<TextView>(R.id.recordCancel)?.visibility = View.INVISIBLE
        if (activeEntryId != 0L) { runCatching { Transcript.discard(this, activeEntryId) }; activeEntryId = 0L; renderTranscript() }
        recordLabel.text = getString(R.string.record_label)
        setKnobRecording(false)
        statusText.text = getString(R.string.status_idle)
    }

    private fun stopRecord() {
        accessibilityRecording = false
        val svc = Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
        // INVISIBLE, not GONE: keeps the height so the ring does not shift.
        findViewById<TextView>(R.id.recordCancel)?.visibility = View.INVISIBLE
        if (activeEntryId != 0L) {
            runCatching { Transcript.update(this, activeEntryId, state = EntryState.WAITING) }
            renderTranscript()
        }
        recordLabel.text = getString(R.string.record_label)
        setKnobRecording(false)
        status("▲ sent — awaiting reply")
        Haptics.ack(this)
    }

    private fun refreshTalkEnabled() {
        val ok = hasPermission(Manifest.permission.RECORD_AUDIO)
        talkButton.isEnabled = ok
        talkButton.alpha = if (ok) 1f else 0.4f
    }

    private fun launchCamera() {
        cameraLauncher.launch(Intent(this, CameraActivity::class.java))
    }

    private fun setupTorch() {
        torchCameraId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()

        if (torchCameraId == null) {
            torchButton.visibility = View.GONE
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val chars = runCatching { cameraManager.getCameraCharacteristics(torchCameraId!!) }.getOrNull()
            val max = chars?.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            if (max > 1) {
                torchBrightnessSupported = true
                torchMaxLevel = max
                torchDefaultLevel = (chars?.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) ?: max)
                    .coerceIn(1, max)
                torchLevel = torchDefaultLevel
            }
        }
        Log.d(
            TAG,
            "setupTorch: cameraId=$torchCameraId brightnessSupported=$torchBrightnessSupported " +
                "maxLevel=$torchMaxLevel defaultLevel=$torchDefaultLevel"
        )

        torchTouchSlop = ViewConfiguration.get(this).scaledTouchSlop

        torchButton.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    torchDownX = e.x
                    torchDownY = e.y
                    torchGestureActive = false
                    v.isPressed = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    if (torchBrightnessSupported) {
                        val r = Runnable { torchLongPressRunnable = null; beginTorchBrightnessGesture() }
                        torchLongPressRunnable = r
                        torchHandler.postDelayed(r, TORCH_LONG_PRESS_MS)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - torchDownX
                    val dy = e.y - torchDownY
                    when {
                        torchGestureActive -> updateTorchBrightnessFromDrag(e.rawY)
                        torchBrightnessSupported && abs(dy) > torchTouchSlop && abs(dy) >= abs(dx) -> {
                            cancelTorchLongPress()
                            beginTorchBrightnessGesture()
                            updateTorchBrightnessFromDrag(e.rawY)
                        }
                        abs(dx) > torchTouchSlop -> cancelTorchLongPress()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelTorchLongPress()
                    v.isPressed = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (torchGestureActive) {
                        endTorchBrightnessGesture()
                    } else {
                        v.performClick()
                        toggleTorch()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelTorchLongPress()
                    v.isPressed = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    endTorchBrightnessGesture()
                    true
                }
                else -> false
            }
        }
        runCatching { cameraManager.registerTorchCallback(torchCallback, null) }
        updateTorchUi()
    }

    private fun cancelTorchLongPress() {
        torchLongPressRunnable?.let { torchHandler.removeCallbacks(it) }
        torchLongPressRunnable = null
    }

    private fun beginTorchBrightnessGesture() {
        if (!torchBrightnessSupported) return
        torchGestureActive = true
        torchSliderEngaged = false
        torchButton.parent?.requestDisallowInterceptTouchEvent(true)
        torchSlider.visibility = View.VISIBLE
        applyTorchLevel(torchLevel)
        renderTorchSlider()
    }

    private fun endTorchBrightnessGesture() {
        torchGestureActive = false
        torchSliderEngaged = false
        torchSlider.visibility = View.GONE
    }

    private fun updateTorchBrightnessFromDrag(rawY: Float) {
        if (!torchBrightnessSupported) return
        val loc = IntArray(2)
        torchSlider.getLocationOnScreen(loc)
        val sliderHeightPx = torchSlider.height
        if (sliderHeightPx <= 0) return
        val sliderBottomY = loc[1] + sliderHeightPx
        if (!torchSliderEngaged) {
            if (rawY < sliderBottomY) torchSliderEngaged = true else return
        }
        val fraction = ((sliderBottomY - rawY) / sliderHeightPx).coerceIn(0f, 1f)
        val level = (1 + Math.round(fraction * (torchMaxLevel - 1))).coerceIn(1, torchMaxLevel)
        if (level != torchLevel) applyTorchLevel(level)
        renderTorchSlider()
    }

    private fun renderTorchSlider() {
        if (torchSlider.visibility != View.VISIBLE) return
        val h = torchSlider.height
        if (h <= 0) { torchSlider.post { renderTorchSlider() }; return }
        val fraction = if (torchMaxLevel > 1) (torchLevel - 1).toFloat() / (torchMaxLevel - 1) else 1f
        val fillH = (fraction * h).roundToInt().coerceIn(0, h)
        torchSliderFill.layoutParams = torchSliderFill.layoutParams.also { it.height = fillH }
        torchSliderFill.requestLayout()
        // Thumb is bottom-gravity; lift it by the fill height.
        torchSliderThumb.translationY = -fillH.toFloat()
    }

    private fun applyTorchLevel(level: Int) {
        val id = torchCameraId ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            cameraManager.turnOnTorchWithStrengthLevel(id, level)
            torchOn = true
            torchLevel = level
            updateTorchUi()
            Log.d(TAG, "applyTorchLevel: level=$level / max=$torchMaxLevel")
            status(getString(R.string.torch_brightness_fmt, level, torchMaxLevel))
        } catch (e: CameraAccessException) {
            torchUnavailable()
        } catch (e: IllegalArgumentException) {
            torchUnavailable()
        }
    }

    private fun torchUnavailable() = toast(getString(R.string.torch_desc) + " unavailable")

    private fun toggleTorch() {
        val id = torchCameraId ?: return
        val next = !torchOn
        try {
            if (next && torchBrightnessSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cameraManager.turnOnTorchWithStrengthLevel(id, torchLevel)
            } else {
                cameraManager.setTorchMode(id, next)
            }
            torchOn = next
            updateTorchUi()
        } catch (e: CameraAccessException) {
            torchUnavailable()
        } catch (e: IllegalArgumentException) {
            torchUnavailable()
        }
    }

    private fun updateTorchUi() {
        val icon = findViewById<ImageView>(R.id.torchIcon) ?: return
        val t = Themes.byId(Config.themeId(this))
        if (t.lineIcons) icon.setImageResource(
            if (torchOn) R.drawable.ic_flashlight_line_on else R.drawable.ic_flashlight_line
        )
        else icon.setImageResource(if (torchOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight)
        findViewById<View>(R.id.torchButton)?.contentDescription =
            getString(if (torchOn) R.string.torch_on_desc else R.string.torch_off_desc)
        icon.setColorFilter(if (torchOn) t.accent else t.ink)
    }

    private fun onPhotoCaptured(path: String) {
        lastPhotoPath?.takeIf { it != path }?.let { runCatching { File(it).delete() } }
        lastPhotoPath = path
        status("📷 ${getString(R.string.photo_captured)}")
        uiScope.launch {
            val file = File(path)
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = file.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                    Triple(bytes, bmp.width, bmp.height) to ViewRenderer.toEink(bmp)
                }.getOrNull()
            }
            replyContainer.removeAllViews()
            if (decoded == null) { status("could not decode captured photo"); return@launch }
            val (meta, eink) = decoded
            val (jpeg, width, height) = meta

            replyContainer.addView(TextView(this@MainActivity).apply {
                text = "${getString(R.string.photo_captured)} · ${jpeg.size / 1024} KB"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            replyContainer.addView(ImageView(this@MainActivity).apply {
                setImageBitmap(eink)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            })
            updateClearButton()

            status("📷 sending photo — awaiting reply")
            val reply = withContext(Dispatchers.IO) {
                Uploader(applicationContext).sendImage(
                    jpeg, width, height, format = "jpeg",
                    onLocationInterim = { resp -> speakInterim(resp) }
                )
            }
            handleImageReply(reply)
        }
    }

    private fun handleImageReply(reply: DeviceResponse?) =
        handleReply(reply, subject = "photo", clear = false)

    private fun speakInterim(resp: DeviceResponse) {
        val speech = resp.speech ?: return
        val audio = speech.audio
        val hasAudio = audio != null && !audio.isEmpty
        val text = speech.text.orEmpty()
        runOnUiThread {
            status("📍 ${text.ifBlank { "getting your location…" }}")
            if (Config.isReplyVoiceEnabled(this) && hasAudio) {
                Playback.play(applicationContext, audio!!.toByteArray(), speech.audioCodec)
            }
        }
    }

    internal fun handleReply(reply: DeviceResponse?, subject: String, clear: Boolean) {
        if (reply == null) { status("$subject not sent / no reply (transport error)"); return }

        val speech = reply.speech
        val text = speech?.text.orEmpty()
        val audio = speech?.audio
        val hasAudio = audio != null && !audio.isEmpty

        val voiceOn = Config.isReplyVoiceEnabled(this)
        Log.i("RistReply", "speech: audio=${audio?.size() ?: 0}B codec='${speech?.audioCodec.orEmpty()}' " +
            "text=${text.length}c voiceOn=$voiceOn")
        if (voiceOn && hasAudio) Playback.play(applicationContext, audio!!.toByteArray(), speech.audioCodec)

        awaitingUserReply = reply.expectsReply
        runCatching { Config.setAwaitingReply(this, awaitingUserReply) }
        renderAwaitingReply()

        val parts = buildList {
            when {
                voiceOn && hasAudio -> add("▶ audio ${audio!!.size()}B")
                text.isNotBlank() -> add("◀ text")
            }
            if (reply.hasView()) add("🖼 view v${reply.view.schemaVersion}")
        }
        status(if (parts.isEmpty()) "$subject sent — reply had no speech" else parts.joinToString("  +  "))
        renderReply(reply, fallbackText = text, clear = clear)
    }

    private fun sendTypedText() {
        val text = textInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.none { it.isLetterOrDigit() }) {
            textInput.text?.clear()
            return
        }
        cancelInFlightTurn()
        hideKeyboard()
        textInput.text?.clear()
        status(getString(R.string.text_sending))
        val entryId = runCatching { Transcript.begin(this, text, EntryState.WAITING) }.getOrDefault(0L)
        renderTranscript()
        uiScope.launch {
            val uploader = Uploader(applicationContext)
            val reply = withContext(Dispatchers.IO) {
                uploader.sendText(text, onLocationInterim = { resp -> speakInterim(resp) })
            }
            if (reply == null) announceFailure(uploader.lastFailure)
            if (entryId != 0L) runCatching {
                val answer = reply?.speech?.text.orEmpty()
                Transcript.update(
                    this@MainActivity, entryId,
                    state = if (reply != null) EntryState.ANSWERED else EntryState.FAILED,
                    answer = answer,
                    requestId = reply?.requestId.orEmpty(),
                    error = if (reply != null) "" else uploader.lastFailure.ifBlank { "no reply" },
                )
            }
            handleReply(reply, subject = "message", clear = true)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(textInput.windowToken, 0)
        textInput.clearFocus()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val k = event.keyCode
        val isVolume = k == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            k == android.view.KeyEvent.KEYCODE_VOLUME_MUTE
        if (isVolume && DeviceCommands.ringing()) return super.dispatchKeyEvent(event)
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        trackMaintenanceHold(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val x = ev.rawX.toInt()
                val y = ev.rawY.toInt()
                val r = Rect()
                var onControl = false
                for (v in arrayOf<View?>(focused, findViewById(R.id.inputRow), findViewById(R.id.torchButton))) {
                    if (v != null && v.isShown) {
                        v.getGlobalVisibleRect(r)
                        if (r.contains(x, y)) { onControl = true; break }
                    }
                }
                if (!onControl) hideKeyboard()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun renderActions(actions: List<rist.v1.Action>) = runCatching {
        val t = Themes.byId(Config.themeId(this))
        val d = resources.displayMetrics.density
        val tf = when (t.font) {
            "pixel" -> pixelTf; "mono" -> android.graphics.Typeface.MONOSPACE
            "serif" -> android.graphics.Typeface.SERIF; else -> android.graphics.Typeface.SANS_SERIF
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * d).toInt() }
        }
        for (a in actions) {
            val label = a.requestText.ifBlank { a.toolId }.ifBlank { a.kind }
            if (label.isBlank()) continue
            row.addView(TextView(this).apply {
                text = "[ $label ]"
                setTextColor(t.accent); typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, (6 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
                isClickable = true; isFocusable = true
                setOnClickListener {
                    when (a.kind) {
                        "dismiss" -> renderTranscript()
                        "confirm" -> a.actionId.takeIf { it.isNotBlank() }?.let { sendActionConfirm(it) }
                        "tool_call" -> a.toolId.takeIf { it.isNotBlank() }
                            ?.let { sendActionToolCall(it, a.requestText) }
                        "open_view" -> renderTranscript()
                        else -> a.requestText.takeIf { it.isNotBlank() }?.let { sendActionText(it) }
                    }
                }
            })
        }
        if (row.childCount > 0) replyContainer.addView(row)
    }.onFailure { Log.w(TAG, "renderActions failed", it) }.let { }

    private fun sendActionText(text: String) {
        val entryId = runCatching { Transcript.begin(this, text, EntryState.WAITING) }.getOrDefault(0L)
        renderTranscript()
        uiScope.launch {
            val uploader = Uploader(applicationContext)
            val reply = withContext(Dispatchers.IO) { uploader.sendText(text) }
            if (reply == null) announceFailure(uploader.lastFailure)
            if (entryId != 0L) runCatching {
                Transcript.update(
                    this@MainActivity, entryId,
                    state = if (reply != null) EntryState.ANSWERED else EntryState.FAILED,
                    answer = reply?.speech?.text.orEmpty(),
                    requestId = reply?.requestId.orEmpty(),
                    error = if (reply != null) "" else uploader.lastFailure.ifBlank { "no reply" },
                )
            }
            handleReply(reply, subject = "action", clear = true)
        }
    }

    private fun sendActionConfirm(actionId: String) {
        val entryId = runCatching { Transcript.begin(this, "(confirmed)", EntryState.WAITING) }.getOrDefault(0L)
        renderTranscript()
        uiScope.launch {
            val uploader = Uploader(applicationContext)
            val reply = withContext(Dispatchers.IO) { uploader.sendConfirmation(actionId, true) }
            if (reply == null) announceFailure(uploader.lastFailure)
            finishActionEntry(entryId, reply, uploader.lastFailure)
            handleReply(reply, subject = "confirmation", clear = true)
        }
    }

    private fun sendActionToolCall(toolId: String, text: String) {
        val entryId = runCatching { Transcript.begin(this, text.ifBlank { toolId }, EntryState.WAITING) }.getOrDefault(0L)
        renderTranscript()
        uiScope.launch {
            val uploader = Uploader(applicationContext)
            val reply = withContext(Dispatchers.IO) { uploader.sendToolCall(toolId, text) }
            if (reply == null) announceFailure(uploader.lastFailure)
            finishActionEntry(entryId, reply, uploader.lastFailure)
            handleReply(reply, subject = "action", clear = true)
        }
    }

    private fun finishActionEntry(entryId: Long, reply: DeviceResponse?, failure: String) {
        if (entryId == 0L) return
        runCatching {
            Transcript.update(
                this, entryId,
                state = if (reply != null) EntryState.ANSWERED else EntryState.FAILED,
                answer = reply?.speech?.text.orEmpty(),
                requestId = reply?.requestId.orEmpty(),
                error = if (reply != null) "" else failure.ifBlank { "no reply" },
            )
        }
    }

    private fun announceFailure(reason: String) = runCatching {
        if (StreamingCancel.takeCancelledFlag()) {
            statusText.text = getString(R.string.status_idle)
            return@runCatching
        }
        status("Sorry — " + reason.ifBlank { "something went wrong reaching the network" } + ".")
    }.onFailure { Log.w(TAG, "announceFailure", it) }.let { }

    private fun renderPendingConfirmation() = runCatching {
        if (pendingActionId.isBlank()) return@runCatching
        val t = Themes.byId(Config.themeId(this))
        val d = resources.displayMetrics.density
        val tf = when (t.font) {
            "pixel" -> pixelTf; "mono" -> android.graphics.Typeface.MONOSPACE
            "serif" -> android.graphics.Typeface.SERIF; else -> android.graphics.Typeface.SANS_SERIF
        }
        fun button(label: String, approved: Boolean) = TextView(this).apply {
            text = label
            setTextColor(if (approved) t.accent else t.inkMuted)
            typeface = tf; isAllCaps = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, (6 * d).toInt(), (22 * d).toInt(), (6 * d).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { commitPending(approved) }
        }
        replyContainer.addView(TextView(this).apply {
            text = pendingPrompt
            setTextColor(t.ink); typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt() }
        })
        replyContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("[ yes ]", true)); addView(button("[ no ]", false))
        })
    }.onFailure { Log.w(TAG, "renderPendingConfirmation failed", it) }.let { }

    private fun armPendingExpiry() {
        pendingHandler.removeCallbacksAndMessages(null)
        pendingHandler.postDelayed({
            if (pendingActionId.isNotBlank()) {
                pendingActionId = ""; pendingPrompt = ""
                renderTranscript()
                status("that confirmation expired — ask again")
            }
        }, CONFIRM_TTL_MS)
    }

    private fun pendingExpired(): Boolean =
        pendingSetAtMs > 0L && SystemClock.elapsedRealtime() - pendingSetAtMs >= CONFIRM_TTL_MS

    private fun commitPending(approved: Boolean) {
        val actionId = pendingActionId
        if (actionId.isBlank()) return
        if (pendingExpired()) {
            pendingActionId = ""; pendingPrompt = ""
            pendingHandler.removeCallbacksAndMessages(null)
            renderTranscript()
            status("that confirmation expired — ask again")
            return
        }
        pendingHandler.removeCallbacksAndMessages(null)
        pendingActionId = ""; pendingPrompt = ""
        val entryId = runCatching {
            Transcript.begin(this, if (approved) "(confirmed)" else "(declined)", EntryState.WAITING)
        }.getOrDefault(0L)
        renderTranscript()
        uiScope.launch {
            val reply = withContext(Dispatchers.IO) {
                Uploader(applicationContext).sendConfirmation(actionId, approved)
            }
            if (entryId != 0L) runCatching {
                Transcript.update(
                    this@MainActivity, entryId,
                    state = if (reply != null) EntryState.ANSWERED else EntryState.FAILED,
                    answer = reply?.speech?.text.orEmpty(),
                    requestId = reply?.requestId.orEmpty(),
                    error = if (reply != null) "" else "no reply (transport error)",
                )
            }
            handleReply(reply, subject = "confirmation", clear = true)
        }
    }

    private val staleHandler = Handler(Looper.getMainLooper())

    private fun armStaleRepaint() {
        staleHandler.removeCallbacksAndMessages(null)
        val at = runCatching { Transcript.nextStaleAtMs(this) }.getOrDefault(0L)
        if (at <= 0L) return
        // +250ms so prune() is unambiguously past the deadline when it re-reads the clock.
        val delay = (at - System.currentTimeMillis() + 250L).coerceAtLeast(0L)
        staleHandler.postDelayed({ renderTranscript() }, delay)
    }

    private fun renderTranscript(): Unit {
      runCatching {
        replyContainer.removeAllViews()
        val t = Themes.byId(Config.themeId(this))
        val tf = when (t.font) {
            "pixel" -> pixelTf; "mono" -> android.graphics.Typeface.MONOSPACE
            "serif" -> android.graphics.Typeface.SERIF; else -> android.graphics.Typeface.SANS_SERIF
        }
        val d = resources.displayMetrics.density
        val muted = Themes.readableMuted(t)
        var attachmentsPainted = false
        val tsFmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        for ((idx, e) in Transcript.all(this).asReversed().withIndex()) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@MainActivity).apply {
                    text = "▸ " + e.prompt
                    setTextColor(muted); typeface = tf
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "  " + tsFmt.format(java.util.Date(e.at))
                    setTextColor(blend(t.inkMuted, t.ground, 0.35f)); typeface = tf
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                })
            })
            val body = when (e.state) {
                EntryState.RECORDING -> "● recording…"
                EntryState.SENT, EntryState.WAITING -> "… waiting for a reply"
                EntryState.FAILED -> "⚠ no answer" + (if (e.error.isNotBlank()) " (${e.error})" else "")
                EntryState.ANSWERED -> e.answer
            }
            if (body.isNotBlank()) col.addView(TextView(this).apply {
                text = body
                setTextColor(if (e.state == EntryState.FAILED) t.accent else t.ink)
                typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (e.state == EntryState.ANSWERED) 17f else 12f)
            })
            val dismiss = TextView(this).apply {
                text = "✕"
                setTextColor(muted); typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding((10 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (6 * d).toInt())
                isClickable = true; isFocusable = true
                contentDescription = "Clear this answer"
                setOnClickListener {
                    runCatching { Transcript.discard(this@MainActivity, e.localId) }
                    renderTranscript()
                }
            }
            replyContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * d).toInt(); bottomMargin = (8 * d).toInt() }
                addView(col); addView(dismiss)
            })

            if (idx == 0 && lastAttachments.isNotEmpty() && e.localId == lastAttachmentsEntryId) {
                runCatching { AttachmentView.render(replyContainer, lastAttachments, insertAfter = idx) }
                attachmentsPainted = true
            }
        }

        if (!attachmentsPainted && lastAttachments.isNotEmpty() && lastAttachmentsEntryId == 0L) {
            runCatching { AttachmentView.render(replyContainer, lastAttachments) }
        }

        if (pendingActionId.isNotBlank()) renderPendingConfirmation()
        findViewById<android.widget.ScrollView>(R.id.replyScroll)?.post {
            findViewById<android.widget.ScrollView>(R.id.replyScroll)?.fullScroll(View.FOCUS_UP)
        }
        findViewById<View>(R.id.clearButton)?.visibility = View.GONE
        armStaleRepaint()
      }.onFailure { Log.w(TAG, "renderTranscript failed", it) }
    }

    internal fun paintAttachmentsIfCurrent(generation: Int, items: List<RistAttachment>) {
        if (generation != attachmentGeneration) return
        if (isFinishing || isDestroyed) return
        lastAttachments = items
        lastAttachmentsEntryId = runCatching { Transcript.all(this).lastOrNull()?.localId ?: 0L }
            .getOrDefault(0L)
        runCatching { AttachmentView.render(replyContainer, items, insertAfter = 0) }
    }

    private fun renderReply(reply: DeviceResponse?, fallbackText: String, clear: Boolean = true) {
        // Must run before renderTranscript() below.
        lastAttachments = emptyList()

        // Bumped for EVERY reply, including one with no attachments.
        attachmentJob?.cancel()
        attachmentJob = null
        val generation = ++attachmentGeneration

        if (clear) renderTranscript()

        Haptics.final(this)

        if (reply != null && reply.hasMedia()) {
            dispatchMedia(reply.media, reply.toolId.orEmpty())
        }

        if (reply != null && reply.hasNav()) {
            showNav(reply.nav)
        }

        if (reply != null && reply.attachmentsCount > 0) {
            val pending = reply.attachmentsList
            attachmentJob = uiScope.launch {
                val items = withContext(Dispatchers.IO) {
                    runCatching {
                        AttachmentView.predecode(
                            this@MainActivity,
                            Attachments.resolve(this@MainActivity, pending) { isActive },
                        )
                    }.getOrElse { t ->
                        // getOrElse, not getOrDefault: an OOM must paint the fallback card; cancellation stays silent.
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.w(TAG, "attachments failed wholesale: ${t.javaClass.simpleName}")
                        pending.map { a ->
                            RistAttachment(
                                kind = Attachments.normaliseKind(a.kind),
                                mime = a.mime, title = a.title, text = "",
                                bytes = null, toolId = a.toolId,
                                error = "could not be loaded",
                            )
                        }
                    }
                }
                paintAttachmentsIfCurrent(generation, items)
            }
        }

        if (reply != null) runCatching { DeviceCommands.handle(applicationContext, reply) }

        if (reply != null && reply.actionsCount > 0) renderActions(reply.actionsList)

        if (reply != null && reply.hasConfirm() && reply.confirm.actionId.isNotBlank()) {
            pendingActionId = reply.confirm.actionId
            pendingPrompt = reply.confirm.prompt
            pendingSetAtMs = SystemClock.elapsedRealtime()
            armPendingExpiry()
            renderPendingConfirmation()
        }

        if (reply != null && reply.hasView()) {
            val host = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            replyContainer.addView(host)
            viewRenderer.render(reply.view, host)
        }

        updateClearButton()
    }

    private fun dispatchMedia(command: MediaCommand, toolId: String) {
        val isPlay = command.action.trim().lowercase() == "play"
        if (!isPlay || !isSpeechBusy()) {
            PlaybackService.sendCommand(this, command, toolId)
            return
        }
        pendingMediaStart?.let { mediaHandler.removeCallbacks(it) }
        val deadline = SystemClock.elapsedRealtime() + MEDIA_DEFER_TIMEOUT_MS
        val poll = object : Runnable {
            override fun run() {
                if (pendingMediaStart !== this) return
                if (!isSpeechBusy() || SystemClock.elapsedRealtime() >= deadline) {
                    pendingMediaStart = null
                    PlaybackService.sendCommand(this@MainActivity, command, toolId)
                } else {
                    mediaHandler.postDelayed(this, MEDIA_POLL_MS)
                }
            }
        }
        pendingMediaStart = poll
        mediaHandler.postDelayed(poll, MEDIA_POLL_MS)
    }

    private fun isSpeechBusy(): Boolean = Playback.isActive()

    private fun updateNowPlaying(intent: Intent) {
        val active = intent.getBooleanExtra(PlaybackService.EXTRA_NP_ACTIVE, false)
        if (!active) { nowPlayingCard.visibility = View.GONE; return }
        nowPlayingCard.visibility = View.VISIBLE

        val title = intent.getStringExtra(PlaybackService.EXTRA_NP_TITLE).orEmpty()
        val author = intent.getStringExtra(PlaybackService.EXTRA_NP_AUTHOR).orEmpty()
        val section = intent.getIntExtra(PlaybackService.EXTRA_NP_SECTION, 0)
        val positionMs = intent.getLongExtra(PlaybackService.EXTRA_NP_POSITION_MS, 0L)
        val durationMs = intent.getLongExtra(PlaybackService.EXTRA_NP_DURATION_MS, 0L)
        val isPlaying = intent.getBooleanExtra(PlaybackService.EXTRA_NP_PLAYING, false)
        val buffering = intent.getBooleanExtra(PlaybackService.EXTRA_NP_BUFFERING, false)
        val isPodcast = intent.getBooleanExtra(PlaybackService.EXTRA_NP_PODCAST, false)

        npTitle.text = title.ifBlank { getString(R.string.app_name) }
        npAuthor.visibility = if (author.isBlank()) View.GONE else View.VISIBLE
        npAuthor.text = author
        // `section` is 0-based from the backend; displayed 1-based.
        npSection.visibility = View.VISIBLE
        npSection.text = getString(
            if (isPodcast) R.string.np_episode_fmt else R.string.np_chapter_fmt, section + 1
        )

        npDuration.text = if (durationMs > 0) fmtTime(durationMs) else "--:--"
        // Scrub bar units are seconds (max = duration_s, progress = position_s).
        if (!isScrubbing) {
            npProgress.max = if (durationMs > 0) (durationMs / 1000L).toInt() else 0
            npProgress.progress = (positionMs / 1000L).toInt().coerceIn(0, maxOf(npProgress.max, 0))
            npElapsed.text = fmtTime(positionMs)
        }

        npPlayPause.setImageResource(
            if (isPlaying || buffering) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun fmtTime(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L)) / 1000L
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun showNav(nav: NavCommand) {
        val prev = currentNav
        currentNav = nav
        rerouting = false
        navLabel.text = nav.label.ifBlank { getString(R.string.app_name) }
        navDest.text = getString(R.string.nav_dest_fmt, nav.destLat, nav.destLon)
        navMode.text = getString(R.string.nav_mode_fmt, nav.mode.ifBlank { "driving" })

        if (isDebugBuild && nav.hasCorridor() && nav.corridor.tilesCount > 0) {
            runCatching {
                val img = nav.corridor.tilesList[0].image
                val out = java.io.File(getExternalFilesDir(null), "last_corridor.png")
                out.writeBytes(img.toByteArray())
                android.util.Log.i("RistNavDbg", "corridor dumped ${img.size()}B -> ${out.absolutePath} " +
                    "bounds=${nav.corridor.minLat},${nav.corridor.minLon}..${nav.corridor.maxLat},${nav.corridor.maxLon}")
            }.onFailure { android.util.Log.w("RistNavDbg", "corridor dump failed", it) }
        }
        if (nav.framesCount > 0) {
            navMap.setFrames(if (nav.hasCorridor()) nav.corridor else null, nav.framesList)
        } else if (nav.hasCorridor()) {
            navMap.setCorridor(nav.corridor)
        } else {
            val sameJourney = prev != null && (
                (nav.routeId.isNotBlank() && nav.routeId == prev.routeId) ||
                (nav.destLat == prev.destLat && nav.destLon == prev.destLon)
            )
            if (!sameJourney) navMap.setCorridor(null)
        }
        navMap.setTiles(nav.tilesList)
        navMap.setDestination(nav.destLat, nav.destLon)

        navLastSpokenTurn = -1
        navRouteLoaded = false
        if (nav.shape.isNotBlank() && nav.turnsCount > 0) {
            if (navRnHandle == 0L) navRnHandle = Ristnav.nCreate()
            val n = nav.turnsCount
            val tl = DoubleArray(n) { nav.turnsList[it].lat }
            val tlo = DoubleArray(n) { nav.turnsList[it].lon }
            val tt = IntArray(n) { nav.turnsList[it].type }
            val td = IntArray(n) { nav.turnsList[it].distanceM }
            navRouteLoaded = Ristnav.nSetRoute(navRnHandle, nav.shape, 6, tl, tlo, tt, td) > 0
            if (navRouteLoaded) navMap.setRoutePoints(navRoutePts, Ristnav.nGetRoutePoints(navRnHandle, navRoutePts))
        }
        navMap.setRistnavHandle(if (navRouteLoaded) navRnHandle else 0L)
        android.util.Log.i("RistNavDbg", "ROUTE loaded=$navRouteLoaded shapeLen=${nav.shape.length} turns=${nav.turnsCount}")

        navTurns = nav.turnsList
        navNextTurn = 0
        updateNavBannerAndTarget()

        updateNavEta()

        navMap.visibility = View.VISIBLE
        expandNav()
    }

    private fun updateNavBannerAndTarget() {
        val nav = currentNav ?: return
        if (navNextTurn < navTurns.size) {
            val m = navTurns[navNextTurn]
            navBanner.visibility = View.VISIBLE
            navBanner.text = if (m.street.isBlank()) m.instruction
            else getString(R.string.nav_banner_fmt, m.street, m.instruction)
            navMap.setTarget(m.lat, m.lon)
            navMap.setNextManeuver(navNextTurn, m.lat, m.lon)
            navMap.setTurnCard(m.shortInstruction.ifBlank { m.instruction }, m.street, m.type,
                m.exitNumber, m.exitBranch, m.exitToward)
        } else {
            navBanner.visibility = View.GONE
            navMap.setTarget(nav.destLat, nav.destLon)
            navMap.clearNextManeuver()
            navMap.setTurnCard("Arrive", nav.label, 10)
        }
    }

    private fun updateNavEta() {
        val nav = currentNav ?: return
        if (nav.distanceM == 0 && nav.durationS == 0) {
            val fix = lastNavFix ?: LocationProvider.cached(this)
            if (fix == null) {
                navEta.text = getString(R.string.nav_routing_unavailable)
                navMap.setStatusBar("", "", "")
            } else {
                val out = FloatArray(1)
                Location.distanceBetween(fix.lat, fix.lon, nav.destLat, nav.destLon, out)
                val meters = out[0]
                val etaMin = (meters / NAV_ASSUMED_SPEED_MPS / 60.0).roundToInt().coerceAtLeast(1)
                navEta.text = getString(R.string.nav_eta_local_fmt, meters / 1609.34, etaMin)
                navMap.setStatusBar("$etaMin min", String.format("%.1f mi", meters / 1609.34), etaClock(etaMin * 60))
            }
        } else {
            navEta.text = getString(R.string.nav_eta_fmt, nav.distanceM / 1609.34, (nav.durationS / 60.0).roundToInt())
            navMap.setStatusBar("${(nav.durationS / 60.0).roundToInt()} min",
                String.format("%.1f mi", nav.distanceM / 1609.34), etaClock(nav.durationS))
        }
    }

    private fun etaClock(seconds: Int): String {
        val d = java.util.Date(System.currentTimeMillis() + seconds * 1000L)
        return java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(d)
    }

    private fun expandNav() {
        if (currentNav == null) return
        navPill.visibility = View.GONE
        navBox.visibility = View.VISIBLE
        navMap.visibility = View.VISIBLE
        if (!navigating) startNavLocationUpdates() else updateNavHere()
    }

    private fun minimizeNav() {
        val nav = currentNav ?: return
        navPill.text = getString(R.string.nav_pill_fmt, nav.label.ifBlank { getString(R.string.app_name) })
        navBox.visibility = View.GONE
        navPill.visibility = View.VISIBLE
    }

    private fun closeNav() {
        stopNavLocationUpdates()
        navMap.release()
        currentNav = null
        navTurns = emptyList()
        navNextTurn = 0
        lastNavFix = null
        rerouting = false
        navMap.visibility = View.GONE
        navBanner.visibility = View.GONE
        navBox.visibility = View.GONE
        navPill.visibility = View.GONE
    }

    private fun startNavLocationUpdates() {
        stopNavLocationUpdates()
        if (!LocationProvider.hasPermission(this)) { navigating = false; updateNavHere(); return }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        LocationProvider.cached(this)?.let { onNavFix(it) }

        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                onNavFix(LocationProvider.Fix(
                    lat = loc.latitude, lon = loc.longitude,
                    accuracyM = if (loc.hasAccuracy()) loc.accuracy else 0f,
                    timeMs = loc.time,
                    altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                    speedMps = if (loc.hasSpeed()) loc.speed else null,
                    bearingDeg = if (loc.hasBearing()) loc.bearing else null
                ))
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        navLocationListener = listener
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    NAV_GPS_MIN_TIME_MS, NAV_GPS_MIN_DIST_M, listener, Looper.getMainLooper()
                )
                navigating = true
                navSensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                navRotationSensor = navSensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                navRotationSensor?.let {
            @Suppress("DEPRECATION")
            run { cachedRotation = windowManager.defaultDisplay.rotation }
            navSensorManager?.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
            } else {
                navigating = false
                updateNavHere()
            }
        } catch (e: SecurityException) {
            navigating = false
            updateNavHere()
        }
    }

    private fun stopNavLocationUpdates() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        navLocationListener?.let { l -> runCatching { lm?.removeUpdates(l) } }
        runCatching { navSensorManager?.unregisterListener(compassListener) }
        navLocationListener = null
        navigating = false
    }

    private fun onNavFix(fix: LocationProvider.Fix) {
        if (currentNav == null) return
        lastNavFix = fix
        if ((fix.speedMps ?: 0f) > 1.5f) navMovingUntil = System.currentTimeMillis() + 4000
        if (navRouteLoaded && navRnHandle != 0L) {
            Ristnav.nUpdate(navRnHandle, fix.lat, fix.lon, navRnOut)
            val nextTurn = navRnOut[6].toInt()
            if (nextTurn < 0) { status(getString(R.string.nav_arrived)); closeNav(); return }
            navNextTurn = nextTurn
            updateNavBannerAndTarget()
            val offR = navRnOut[5] != 0.0
            val mLat = if (offR) fix.lat else navRnOut[0]
            val mLon = if (offR) fix.lon else navRnOut[1]
            navMap.setFix(mLat, mLon, navRnOut[8].toFloat())
            updateNavHere()
            updateNavEta()
            if (navRnOut[7] <= TURN_TRIGGER_M && nextTurn != navLastSpokenTurn) {
                navTurns.getOrNull(nextTurn)?.instruction?.takeIf { it.isNotBlank() }?.let { speakNav(it) }
                navLastSpokenTurn = nextTurn
            }
            if (navRnOut[5] != 0.0) launchReroute(fix)
            return
        }
        navMap.setFix(fix.lat, fix.lon, fix.bearingDeg)
        updateNavHere()
        updateNavEta()
        advanceTurnByTurn(fix)
        checkReroute(fix)
    }

    private fun advanceTurnByTurn(fix: LocationProvider.Fix) {
        if (navNextTurn >= navTurns.size) return
        val m = navTurns[navNextTurn]
        val out = FloatArray(1)
        Location.distanceBetween(fix.lat, fix.lon, m.lat, m.lon, out)
        if (out[0] <= TURN_TRIGGER_M) {
            if (m.instruction.isNotBlank()) speakNav(m.instruction)
            if (m.type == MANEUVER_ARRIVE) {
                status(getString(R.string.nav_arrived))
                closeNav()
                return
            }
            navNextTurn++
            updateNavBannerAndTarget()
        }
    }

    private fun checkReroute(fix: LocationProvider.Fix) {
        val nav = currentNav ?: return
        if (rerouting || !nav.hasCorridor()) return
        val c = nav.corridor
        val outside = fix.lat < c.minLat - NAV_SNAP_TOL_DEG || fix.lat > c.maxLat + NAV_SNAP_TOL_DEG ||
            fix.lon < c.minLon - NAV_SNAP_TOL_DEG || fix.lon > c.maxLon + NAV_SNAP_TOL_DEG
        if (!outside) return
        launchReroute(fix)
    }

    private fun launchReroute(fix: LocationProvider.Fix?) {
        val nav = currentNav ?: return
        if (rerouting) return
        rerouting = true
        status(getString(R.string.nav_rerouting))
        val label = nav.label.ifBlank { getString(R.string.app_name) }
        val routeId = nav.routeId
        uiScope.launch {
            val reply = withContext(Dispatchers.IO) {
                Uploader(applicationContext).sendNav(
                    text = "navigate to $label", targetToolId = "map", fix = fix, routeId = routeId
                )
            }
            if (reply != null && reply.hasNav()) {
                reply.speech?.text?.takeIf { it.isNotBlank() }?.let { speakNav(it) }
                showNav(reply.nav)
            } else {
                rerouting = false
            }
        }
    }

    private fun speakNav(text: String) {
        Log.i(TAG, "nav cue: $text")
    }

    private fun updateNavHere() {
        val fix = lastNavFix ?: LocationProvider.cached(this)
        navHere.text = if (fix == null) {
            getString(R.string.nav_here_locating)
        } else {
            getString(R.string.nav_here_fmt, fix.lat, fix.lon, fix.accuracyM.roundToInt().coerceAtLeast(0))
        }
    }

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun status(msg: String) {
        statusText.text = "[${ts.format(Date())}] $msg"
    }

    private fun updateClearButton() {
        clearButton.visibility = View.GONE
    }

    private fun clearReply() {
        runCatching { Transcript.clear(this) }
        replyContainer.removeAllViews()
        lastPhotoPath?.let { runCatching { File(it).delete() } }
        lastPhotoPath = null
        status(getString(R.string.status_idle))
        updateClearButton()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        private const val TAG = "RistMain"

        // Safety cap after which a deferred `play` starts regardless.
        private const val MEDIA_POLL_MS = 200L
        private const val MEDIA_DEFER_TIMEOUT_MS = 20_000L

        private const val NAV_GPS_MIN_TIME_MS = 2_000L
        private const val NAV_GPS_MIN_DIST_M = 5f

        // Speak + advance a maneuver within this many meters of its point.
        private const val TURN_TRIGGER_M = 40f
        // Maneuver type 10 = arrive.
        private const val MANEUVER_ARRIVE = 10

        // On-device ETA fallback speed (~30 mph) when the backend sent distance_m/duration_s == 0.
        private const val NAV_ASSUMED_SPEED_MPS = 13.4

        // Degrees outside the corridor bbox before a reroute fires.
        private const val NAV_SNAP_TOL_DEG = 0.003

        // Hold this long to arm the torch brightness drag.
        private const val TORCH_LONG_PRESS_MS = 250L

        // Hold the hidden hotspot this long to raise the exit-kiosk confirmation.
        private const val MAINTENANCE_HOLD_MS = 3000L
        // Short presses are rejected by RecordService's energy gate.
        private const val MIN_RECORD_HOLD_MS = 300L
        // The backend refuses an expired action_id.
        private const val CONFIRM_TTL_MS = 120_000L
    }
}
