package watch.rist.assistant

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat

/**
 * Where a volume button press goes on the home screen, and what the answer is.
 *
 * Voice replies play on Android's assistant stream, which has no row in Android's own volume
 * panel, and this OS hides the panel's button for the other rows. So a phone could have its
 * voice at zero with no visible way to raise it. RIST takes the volume buttons while its home
 * screen is in front and shows every volume a person cares about, voice first.
 */
internal object VolumeKeys {

    /** Android's STREAM_ASSISTANT. The constant is hidden in the public SDK; the value is fixed. */
    const val STREAM_ASSISTANT = 11

    enum class Channel(val stream: Int, val label: String, val icon: Int) {
        VOICE(STREAM_ASSISTANT, "Voice", R.drawable.ic_vol_voice),
        MEDIA(AudioManager.STREAM_MUSIC, "Media", R.drawable.ic_vol_media),
        RINGER(AudioManager.STREAM_RING, "Ringer", R.drawable.ic_vol_ring),
        NOTIFICATIONS(AudioManager.STREAM_NOTIFICATION, "Alerts", R.drawable.ic_vol_notif),
        ALARM(AudioManager.STREAM_ALARM, "Alarm", R.drawable.ic_vol_alarm),
    }

    /**
     * The channel a press adjusts, or null to leave the press to Android.
     *
     * A call or a ringing phone keeps Android's behaviour: the buttons set call volume, or
     * silence the ringer, and nothing here should be in the way of that. A ringing alarm is the
     * alarm screen's to handle. Otherwise the channel the person picked in the open panel wins,
     * and the ringer otherwise.
     */
    fun route(
        callOrRinging: Boolean,
        alarmRinging: Boolean,
        picked: Channel?,
        voiceSounding: Boolean,
        mediaSounding: Boolean,
        voiceOwnVolume: Boolean = true,
    ): Channel? {
        // The buttons always start on the ringer (owner's choice, 2026-09-17), even while
        // something plays; a slider tapped in the open panel takes them over until it closes.
        return when {
            callOrRinging || alarmRinging -> null
            picked != null -> picked
            else -> Channel.RINGER
        }
    }

    /**
     * Whether voice replies can have a volume of their own. Android refuses changes to the
     * assistant volume from anything without MODIFY_AUDIO_SETTINGS_PRIVILEGED, and it grants that
     * to RIST only from the OS image, never to an app build installed over it. Without it,
     * replies play as media, scaled by RIST's own Voice level: separate from Media, but never
     * louder than it.
     */
    fun voiceHasOwnVolume(ctx: android.content.Context): Boolean =
        ctx.checkSelfPermission("android.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * The sliders to show. Voice is always its own slider: with the privileged permission it is
     * Android's assistant volume, without it RIST's own level for replies played as media.
     */
    @Suppress("UNUSED_PARAMETER")
    fun channels(voiceOwnVolume: Boolean): List<Channel> = Channel.values().toList()

    /** Ring → vibrate → silent → ring, the order Android's own button cycles in. */
    fun nextRingerMode(mode: Int): Int = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
        AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
        else -> AudioManager.RINGER_MODE_NORMAL
    }

    fun ringerName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_SILENT -> "silent"
        else -> "ring"
    }

    /**
     * Raising the ringer out of silent or vibrate is a wish to hear it ring: the mode goes back
     * to ring, which also clears the silent icon. Null when the mode should stay as it is.
     */
    fun ringerModeAfter(channel: Channel, index: Int, mode: Int): Int? =
        if (channel == Channel.RINGER && index > 0 && mode != AudioManager.RINGER_MODE_NORMAL)
            AudioManager.RINGER_MODE_NORMAL else null

    /** One button step from [current], kept inside [min]..[max]. */
    fun step(current: Int, min: Int, max: Int, raise: Boolean): Int =
        (current + if (raise) 1 else -1).coerceIn(min, max)
}

/**
 * The panel itself: a rounded card on the right edge, one vertical slider per channel, in the
 * shape of Android's own volume panel and in the colours of the current RIST theme.
 *
 * It lives in the activity's window, so it only exists while the home screen is showing.
 * It hides itself a few seconds after the last press or touch.
 */
internal class VolumePanel(private val activity: Activity) {

    private val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var card: LinearLayout? = null
    private val sliders = LinkedHashMap<VolumeKeys.Channel, Column>()

    /** The channel the buttons adjust while the panel is open; set by a press or a tap. */
    var target: VolumeKeys.Channel? = null
        private set

    val isShowing: Boolean get() = card?.isAttachedToWindow == true && card?.visibility == View.VISIBLE

    /** A button press: move [channel] one step and show the panel with it highlighted. */
    fun press(channel: VolumeKeys.Channel, raise: Boolean) {
        val now = level(channel)
        set(channel, VolumeKeys.step(now.current, now.min, now.max, raise))
        show(channel)
    }

    fun show(channel: VolumeKeys.Channel) {
        target = channel
        val c = card ?: build().also { card = it }
        c.visibility = View.VISIBLE
        refresh()
        keepOpen()
    }

    /** Removes the panel; the next press builds it again, in whatever theme is current then. */
    fun hide() {
        handler.removeCallbacks(hideRunnable)
        card?.let { c -> (c.parent as? ViewGroup)?.removeView(c) }
        card = null
        modeButton = null
        sliders.clear()
        target = null
    }

    /** True when a screen touch at raw coordinates lands on the panel. */
    fun contains(rawX: Int, rawY: Int): Boolean {
        val c = card ?: return false
        if (!isShowing) return false
        val r = android.graphics.Rect()
        return c.getGlobalVisibleRect(r) && r.contains(rawX, rawY)
    }

    private fun keepOpen() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    internal data class Level(val current: Int, val min: Int, val max: Int, val muted: Boolean)

    internal fun level(channel: VolumeKeys.Channel): Level {
        if (channel == VolumeKeys.Channel.VOICE && !VolumeKeys.voiceHasOwnVolume(activity)) {
            return Level(Config.voiceLevel(activity), 0, Config.VOICE_LEVEL_MAX, false)
        }
        val s = channel.stream
        return runCatching {
            Level(am.getStreamVolume(s), am.getStreamMinVolume(s), am.getStreamMaxVolume(s), am.isStreamMute(s))
        }.getOrElse { Level(0, 0, 1, false) }
    }

    private fun set(channel: VolumeKeys.Channel, index: Int) {
        if (channel == VolumeKeys.Channel.VOICE && !VolumeKeys.voiceHasOwnVolume(activity)) {
            Config.setVoiceLevel(activity, index)
            refresh()
            return
        }
        runCatching {
            // Mode first: while silent or vibrate, Android holds the ring volume at zero.
            VolumeKeys.ringerModeAfter(channel, index, am.ringerMode)?.let { am.ringerMode = it }
            // A muted stream ignores a new index until it is unmuted; media arrives muted here.
            if (index > 0 && am.isStreamMute(channel.stream)) {
                am.adjustStreamVolume(channel.stream, AudioManager.ADJUST_UNMUTE, 0)
            }
            am.setStreamVolume(channel.stream, index, 0)
        }.onFailure {
            // Setting the ringer to zero can be refused under Do Not Disturb; the panel then
            // simply shows the level Android kept.
            Log.w(TAG, "could not set ${channel.label} volume to $index", it)
        }
        refresh()
    }

    private fun refresh() {
        paintMode()
        for ((channel, col) in sliders) {
            val l = level(channel)
            col.slider.setLevel(if (l.muted) 0 else l.current, l.min, l.max)
            col.slider.highlighted = channel == target
            col.label.setTextColor(if (channel == target) theme.accent else Themes.readableMuted(theme))
            col.slider.contentDescription = "${channel.label} volume, ${if (l.muted) 0 else l.current} of ${l.max}"
        }
    }

    private val theme: RistTheme get() = Themes.byId(Config.themeId(activity))

    private class Column(val slider: VolumeSlider, val label: TextView)

    private fun build(): LinearLayout {
        val t = theme
        val d = activity.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val surface = if (t.dark) blend(t.ground, Color.WHITE, 0.10f) else blend(t.ground, Color.WHITE, 0.60f)
        val tf = ThemePaint.typefaceOf(activity, t)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10f), dp(12f), dp(10f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = 28 * d
                setColor(surface)
                setStroke(dp(1f), withAlpha(t.ink, 0x22))
            }
            elevation = 12 * d
            // Touches on the card are the panel's; they must not reach the feed underneath.
            isClickable = true
            setOnTouchListener { _, _ -> keepOpen(); false }
        }
        // Ring / vibrate / silent, as the round button at the top of Android's own panel.
        modeButton = android.widget.ImageView(activity).apply {
            val size = dp(44f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { bottomMargin = dp(10f) }
            setPadding(dp(11f), dp(11f), dp(11f), dp(11f))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.accent) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val next = VolumeKeys.nextRingerMode(am.ringerMode)
                val ok = runCatching { am.ringerMode = next }
                    .onFailure { e -> Log.w(TAG, "ringer mode $next refused", e) }.isSuccess
                if (!ok) android.widget.Toast.makeText(activity,
                    "The phone would not switch to ${VolumeKeys.ringerName(next)}", android.widget.Toast.LENGTH_SHORT).show()
                refresh()
                keepOpen()
            }
        }
        card.addView(modeButton)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        // Wrap, not the vertical card's default match-parent: matched, the row took the width
        // of the ringer button above it and clipped every slider after the first.
        card.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val ownVoice = VolumeKeys.voiceHasOwnVolume(activity)
        for (channel in VolumeKeys.channels(ownVoice)) {
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4f), 0, dp(4f), 0)
            }
            val slider = VolumeSlider(activity, t, ContextCompat.getDrawable(activity, channel.icon)).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44f), dp(210f))
                onUserLevel = { index ->
                    target = channel
                    set(channel, index)
                    keepOpen()
                }
                ViewCompat.replaceAccessibilityAction(this, AccessibilityActionCompat.ACTION_SCROLL_FORWARD, "Louder") { _, _ ->
                    val l = level(channel); set(channel, VolumeKeys.step(l.current, l.min, l.max, true)); keepOpen(); true
                }
                ViewCompat.replaceAccessibilityAction(this, AccessibilityActionCompat.ACTION_SCROLL_BACKWARD, "Quieter") { _, _ ->
                    val l = level(channel); set(channel, VolumeKeys.step(l.current, l.min, l.max, false)); keepOpen(); true
                }
            }
            val label = TextView(activity).apply {
                text = channel.label
                typeface = tf
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                setPadding(0, dp(6f), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            col.addView(slider)
            col.addView(label)
            row.addView(col)
            sliders[channel] = Column(slider, label)
        }
        val host = activity.window.decorView as ViewGroup
        host.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL,
        ).apply { marginEnd = dp(10f) })
        return card
    }

    private var modeButton: android.widget.ImageView? = null

    private fun paintMode() {
        val b = modeButton ?: return
        val mode = am.ringerMode
        b.setImageResource(when (mode) {
            AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_vol_vibrate
            AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_vol_silent
            else -> R.drawable.ic_vol_ring
        })
        b.setColorFilter(theme.ground)
        b.contentDescription = "Ringer: ${VolumeKeys.ringerName(mode)}. Tap for ${VolumeKeys.ringerName(VolumeKeys.nextRingerMode(mode))}"
    }

    companion object {
        private const val TAG = "RistVolume"
        internal const val AUTO_HIDE_MS = 3_000L
    }
}

/**
 * One vertical volume track: rounded, filled from the bottom in the theme accent, with the
 * channel's icon inside at the foot, as Android draws its own. Drag or tap to set the level.
 */
internal class VolumeSlider(
    context: Context,
    private val t: RistTheme,
    private val icon: android.graphics.drawable.Drawable?,
) : View(context) {

    var onUserLevel: ((Int) -> Unit)? = null
    var highlighted: Boolean = false
        set(v) { field = v; invalidate() }

    private var current = 0
    private var min = 0
    private var max = 1

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blend(t.ground, t.ink, 0.14f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = t.accent
    }
    private val rect = RectF()

    init {
        isFocusable = true
        isClickable = true
    }

    fun setLevel(current: Int, min: Int, max: Int) {
        this.current = current; this.min = min; this.max = maxOf(max, min + 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = w / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val frac = ((current - min).toFloat() / (max - min)).coerceIn(0f, 1f)
        // The fill never shrinks below a circle, so a quiet level still reads as "some".
        val top = (h - maxOf(w, frac * h)).coerceAtLeast(0f)
        if (current > min) {
            rect.set(0f, top, w, h)
            canvas.drawRoundRect(rect, r, r, fill)
        }
        if (highlighted) {
            rect.set(d, d, w - d, h - d)
            canvas.drawRoundRect(rect, r - d, r - d, ring)
        }
        icon?.let {
            val s = (20 * d).toInt()
            val left = ((w - s) / 2).toInt()
            val bottom = (h - (w - s) / 2).toInt()
            it.setBounds(left, bottom - s, left + s, bottom)
            it.setTint(if (current > min) t.ground else t.ink)
            it.draw(canvas)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val frac = (1f - e.y / height).coerceIn(0f, 1f)
                val index = min + Math.round(frac * (max - min))
                if (index != current) {
                    current = index
                    invalidate()
                    onUserLevel?.invoke(index)
                } else if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    onUserLevel?.invoke(index)
                }
                return true
            }
            MotionEvent.ACTION_UP -> { performClick(); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
