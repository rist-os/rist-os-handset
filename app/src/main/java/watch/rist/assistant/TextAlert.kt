package watch.rist.assistant

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Makes an incoming text noticeable while the kiosk is up.
 *
 * Why this exists: in lock task mode without `LOCK_TASK_FEATURE_NOTIFICATIONS` (which the kiosk
 * withholds, see [KioskManager.lockTaskFeaturesFor]) the system disables the status bar's
 * notification alerts, and the notification service reads that as "make no noise": every app's
 * notification, the Messages app's included, is posted without sound, vibration or heads-up. A text
 * arrived and only the badge count moved.
 *
 * So while the system has muted notifications, Rist does the alerting itself for the default SMS
 * app's notifications: its own banner at the top of the screen, and the sound and vibration Android
 * would have played, decided by the same rules (ringer mode, Do Not Disturb via the notification's
 * ranking, the channel's importance and vibration). When the system has not muted them it alerts
 * on its own and this does nothing, so a text never alerts twice.
 *
 * The text's sender and first line are taken from the Messages app's notification, shown on this
 * screen for a few seconds, and kept nowhere: nothing here is stored, logged or sent.
 */
object TextAlert {

    private const val TAG = "RistTextAlert"

    /** What Android's notification rules call for, decided from facts alone. */
    data class Alerting(val banner: Boolean, val sound: Boolean, val vibrate: Boolean) {
        val any: Boolean get() = banner || sound || vibrate

        companion object {
            val NONE = Alerting(banner = false, sound = false, vibrate = false)
        }
    }

    /** The parts of the notification's ranking the decision needs. Null when the ranking is unknown. */
    data class RankFacts(
        /** False when Do Not Disturb holds this notification back. */
        val matchesInterruptionFilter: Boolean,
        /** True when Do Not Disturb also hides it from view (no peeking). */
        val peekSuppressed: Boolean,
        val importance: Int,
        /** The channel's own sound; null when the channel is silent, or (without [channelKnown]) unknown. */
        val channelSound: Uri?,
        val channelVibrates: Boolean,
        val channelVibration: LongArray?,
        /** True when the facts come from the notification's channel, so a null sound means silent. */
        val channelKnown: Boolean = false,
    ) {
        /** A channel the person set to no sound makes none; Android plays nothing for it. */
        val makesSound: Boolean get() = !channelKnown || channelSound != null
    }

    /** The phone's state at the moment the text arrives. */
    interface Env {
        /** True when lock task has disabled notification sound and vibration system-wide. */
        fun systemAlertsMuted(ctx: Context): Boolean
        fun ringerMode(ctx: Context): Int
        /** Do Not Disturb as a whole lets ordinary notifications through; used only without a ranking. */
        fun interruptionFilterAllowsAll(ctx: Context): Boolean
        fun inCall(ctx: Context): Boolean
        fun screenOn(ctx: Context): Boolean
        fun locked(ctx: Context): Boolean
        fun defaultSmsPackage(ctx: Context): String?
    }

    /** Where the noise goes. Swapped in tests to record instead of play. */
    interface Effects {
        fun sound(ctx: Context, uri: Uri?)
        fun vibrate(ctx: Context, pattern: LongArray?)
    }

    /**
     * The rules. Android's own, applied by hand because the system has stood down:
     * - not muted by the system: Android alerts itself, so nothing here (no double alert);
     * - a channel the person turned down below default importance: silent, no banner;
     * - Do Not Disturb holding it back: no sound, no vibration, and no banner if it hides it too;
     * - ringer NORMAL: sound, plus vibration when the channel vibrates;
     * - ringer VIBRATE: vibration only; SILENT: neither;
     * - on a call: no sound or vibration over the call, the banner alone;
     * - screen off: no banner, which nobody would see.
     */
    fun decide(
        systemAlertsMuted: Boolean,
        facts: RankFacts,
        ringerMode: Int,
        inCall: Boolean,
        screenOn: Boolean,
    ): Alerting {
        if (!systemAlertsMuted) return Alerting.NONE
        val imp = facts.importance
        if (imp != NotificationManager.IMPORTANCE_UNSPECIFIED && imp < NotificationManager.IMPORTANCE_DEFAULT) {
            return Alerting.NONE
        }
        val banner = screenOn && !facts.peekSuppressed
        if (!facts.matchesInterruptionFilter || inCall) {
            return Alerting(banner = banner, sound = false, vibrate = false)
        }
        return when (ringerMode) {
            AudioManager.RINGER_MODE_NORMAL ->
                Alerting(banner = banner, sound = facts.makesSound, vibrate = facts.channelVibrates)
            AudioManager.RINGER_MODE_VIBRATE ->
                Alerting(banner = banner, sound = false, vibrate = facts.channelVibrates || facts.makesSound)
            else -> Alerting(banner = banner, sound = false, vibrate = false)
        }
    }

    /** The lock task state and features that make the system mute every notification. */
    internal fun alertsMutedBy(lockTaskState: Int, lockTaskFeatures: Int): Boolean = when (lockTaskState) {
        ActivityManager.LOCK_TASK_MODE_NONE -> false
        ActivityManager.LOCK_TASK_MODE_PINNED -> true
        else -> lockTaskFeatures and DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS == 0
    }

    /** What the banner says: the sender and the first line, or only "New message" on a locked phone. */
    data class Shown(val title: String, val body: String)

    internal fun shown(sender: String, text: String, locked: Boolean): Shown {
        if (locked) return Shown("New message", "")
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return Shown(
            title = sender.trim().ifEmpty { "New message" },
            body = CommsFeed.preview(firstLine),
        )
    }

    /** One message, as the Messages app's notification describes it. */
    data class IncomingText(val key: String, val sender: String, val text: String, val whenMs: Long)

    internal fun textOf(sbn: StatusBarNotification): IncomingText? {
        val n = sbn.notification ?: return null
        val x = n.extras ?: return null
        val sender = (x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: x.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_TEXT)
            ?: x.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        if (sender.isBlank() && text.isBlank()) return null
        return IncomingText(sbn.key, sender, text, n.`when`)
    }

    /** True for a notification from the phone's messaging app that stands for a message. */
    internal fun isText(sbn: StatusBarNotification, defaultSms: String?): Boolean {
        val pkg = sbn.packageName ?: return false
        if (pkg != defaultSms && pkg != AppLauncher.PKG_MESSAGING) return false
        if (sbn.isOngoing) return false
        val n = sbn.notification ?: return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // Sent-message failures and other non-message notices are not a new text.
        return n.category == null || n.category == Notification.CATEGORY_MESSAGE
    }

    // Per notification key, a fingerprint of the message it last alerted for. Hashes only: no text
    // is held. A re-post of the same message (a read-state update, a rebind) does not alert again.
    private const val MAX_KEYS = 64
    private val alerted = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > MAX_KEYS
    }

    internal fun firstTimeFor(t: IncomingText, onlyAlertOnce: Boolean): Boolean = synchronized(alerted) {
        val print = 31 * t.whenMs.hashCode() + t.text.hashCode()
        val prior = alerted[t.key]
        alerted[t.key] = print
        when {
            prior == null -> true
            onlyAlertOnce -> false
            else -> prior != print
        }
    }

    internal fun resetForTest() {
        synchronized(alerted) { alerted.clear() }
        envForTest = null
        effectsForTest = null
    }

    @Volatile internal var envForTest: Env? = null
    @Volatile internal var effectsForTest: Effects? = null

    private val env: Env get() = envForTest ?: RealEnv
    private val effects: Effects get() = effectsForTest ?: RealEffects

    /**
     * Called by [RistNotificationListener] for every posted notification. Returns what was done, for
     * the log and for tests.
     */
    fun onPosted(
        ctx: Context,
        sbn: StatusBarNotification,
        facts: RankFacts?,
        cancel: (String) -> Unit = {},
    ): Alerting = runCatching {
        val e = env
        if (!isText(sbn, e.defaultSmsPackage(ctx))) return@runCatching Alerting.NONE
        val muted = e.systemAlertsMuted(ctx)
        if (!muted) return@runCatching Alerting.NONE
        val t = textOf(sbn) ?: return@runCatching Alerting.NONE
        val n = sbn.notification
        val onlyOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        if (!firstTimeFor(t, onlyOnce)) return@runCatching Alerting.NONE

        val f = facts ?: RankFacts(
            matchesInterruptionFilter = e.interruptionFilterAllowsAll(ctx),
            peekSuppressed = !e.interruptionFilterAllowsAll(ctx),
            importance = NotificationManager.IMPORTANCE_UNSPECIFIED,
            channelSound = null,
            channelVibrates = legacyVibrates(n),
            channelVibration = null,
        )
        val a = decide(muted, f, e.ringerMode(ctx), e.inCall(ctx), e.screenOn(ctx))
        if (a.sound) effects.sound(ctx, f.channelSound ?: n.sound)
        if (a.vibrate) effects.vibrate(ctx, f.channelVibration ?: n.vibrate)
        if (a.banner) {
            val s = shown(t.sender, t.text, e.locked(ctx))
            val open = n.contentIntent
            val autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0
            TextAlertBanner.show(ctx, s.title, s.body) {
                openMessage(ctx, open)
                if (autoCancel) cancel(sbn.key)
            }
        }
        Log.i(TAG, "text from the messaging app: banner=${a.banner} sound=${a.sound} vibrate=${a.vibrate}")
        a
    }.onFailure { Log.w(TAG, "could not alert for an incoming text", it) }.getOrDefault(Alerting.NONE)

    private fun legacyVibrates(n: Notification): Boolean =
        n.defaults and Notification.DEFAULT_VIBRATE != 0 || n.vibrate != null

    /** The Messages app's own way into this conversation; the Messages app itself if it has none. */
    internal fun openMessage(ctx: Context, open: PendingIntent?) {
        val sent = open != null && runCatching {
            val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
            open.send(ctx, 0, null, null, null, null, opts.toBundle())
            true
        }.onFailure { Log.w(TAG, "the conversation would not open; opening Messages", it) }.getOrDefault(false)
        if (!sent) AppLauncher.launchMessaging(ctx)
    }

    private object RealEnv : Env {
        override fun systemAlertsMuted(ctx: Context): Boolean {
            val state = runCatching {
                ctx.getSystemService(ActivityManager::class.java)?.lockTaskModeState
            }.getOrNull() ?: ActivityManager.LOCK_TASK_MODE_NONE
            if (state == ActivityManager.LOCK_TASK_MODE_NONE) return false
            // Only the Device Owner can read the features; the kiosk is the Device Owner. Unknown
            // features are treated as the kiosk's own, which withhold notifications.
            val features = runCatching {
                KioskManager.dpm(ctx).getLockTaskFeatures(KioskManager.admin(ctx))
            }.getOrDefault(KioskManager.lockTaskFeaturesFor(false))
            return alertsMutedBy(state, features)
        }

        override fun ringerMode(ctx: Context): Int =
            ctx.getSystemService(AudioManager::class.java)?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        override fun interruptionFilterAllowsAll(ctx: Context): Boolean {
            val f = runCatching {
                ctx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
            }.getOrNull() ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            return f == NotificationManager.INTERRUPTION_FILTER_ALL ||
                f == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }

        override fun inCall(ctx: Context): Boolean = CallState.inCall(ctx)

        override fun screenOn(ctx: Context): Boolean =
            ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true

        override fun locked(ctx: Context): Boolean =
            ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false

        override fun defaultSmsPackage(ctx: Context): String? =
            runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()
    }

    private object RealEffects : Effects {
        private val DEFAULT_VIBRATION = longArrayOf(0L, 250L, 250L, 250L)

        override fun sound(ctx: Context, uri: Uri?) {
            runCatching {
                val r = RingtoneManager.getRingtone(ctx, uri ?: Settings.System.DEFAULT_NOTIFICATION_URI)
                    ?: return
                // Plays on the notification volume ("Alerts" in the volume panel).
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                r.play()
            }.onFailure { Log.w(TAG, "no sound for the text", it) }
        }

        override fun vibrate(ctx: Context, pattern: LongArray?) {
            runCatching {
                val v = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
                if (!v.hasVibrator()) return
                val p = pattern?.takeIf { it.isNotEmpty() } ?: DEFAULT_VIBRATION
                // Notification usage: Android applies the person's "vibrate for notifications"
                // strength to it, off included.
                v.vibrate(
                    VibrationEffect.createWaveform(p, -1),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION),
                )
            }.onFailure { Log.w(TAG, "no vibration for the text", it) }
        }
    }
}
