package watch.rist.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import rist.v1.AlarmCommand
import rist.v1.StopwatchCommand
import rist.v1.TimerCommand

object DeviceCommands {

    private const val TAG = "RistCmd"
    const val ACTION_STATE_CHANGED = "watch.rist.assistant.action.CMD_STATE"

    /** endsAtEpochMs is wall-clock because it is persisted across reboot. */
    private data class Countdown(
        val key: String,
        val label: String,
        var endsAtEpochMs: Long,
        var remainingMs: Long,
        var paused: Boolean,
        val originalMs: Long = 0L,
    )

    private val timers = LinkedHashMap<String, Countdown>()

    val timerLabel: String get() = timers.values.firstOrNull()?.label.orEmpty()

    private var swStartedAtMs = 0L
    private var swAccumulatedMs = 0L
    private var swRunning = false
    private var swPresent = false

    private val appliedKeys = LinkedHashSet<String>()
    private const val APPLIED_MEMORY = 16

    private fun commandKey(reply: rist.v1.DeviceResponse): String {
        val id = reply.requestId.orEmpty()
        if (id.isNotBlank()) return id
        return buildString {
            if (reply.hasTimer()) append("t:${reply.timer.action}/${reply.timer.durationS}/${reply.timer.label};")
            if (reply.hasStopwatch()) append("s:${reply.stopwatch.action};")
            if (reply.hasAlarm()) append("a:${reply.alarm.action}/${reply.alarm.fireAtEpochS}/${reply.alarm.alarmId};")
            if (reply.hasComms()) append("c:${reply.comms.action}/${reply.comms.number};")
        }
    }

    var ringingLabel: String = ""; private set

    fun ringing(): Boolean = ringingLabel.isNotBlank()

    fun setRinging(ctx: Context, label: String) {
        ringingLabel = label
        notifyUi(ctx)
    }

    @Synchronized
    fun handle(ctx: Context, reply: rist.v1.DeviceResponse): Boolean {
        if (!reply.hasTimer() && !reply.hasStopwatch() && !reply.hasAlarm() && !reply.hasComms()) return false
        val key = commandKey(reply)
        if (key.isNotBlank() && !appliedKeys.add(key)) {
            Log.i(TAG, "device commands for '$key' already applied; skipping")
            return false
        }
        while (appliedKeys.size > APPLIED_MEMORY) {
            appliedKeys.remove(appliedKeys.first())
        }
        var handled = false
        if (reply.hasTimer()) { timer(ctx, reply.timer); handled = true }
        if (reply.hasStopwatch()) { stopwatch(ctx, reply.stopwatch); handled = true }
        if (reply.hasAlarm()) { alarm(ctx, reply.alarm); handled = true }
        if (reply.hasComms()) { comms(ctx, reply.comms); handled = true }
        return handled
    }

    private fun keyFor(label: String) = label.trim().lowercase()

    private fun timer(ctx: Context, c: TimerCommand) {
        val key = keyFor(c.label)
        when (c.action) {
            "start" -> {
                if (c.durationS <= 0) {
                    Log.w(TAG, "timer start ignored: duration was ${c.durationS}s")
                    return
                }
                val ms = c.durationS * 1000L
                timers[key] = Countdown(key, c.label, System.currentTimeMillis() + ms, 0L, false, ms)
                TimerAlarm.schedule(ctx, ms, c.label, key)
            }
            "cancel" -> {
                val target = timers[key] ?: if (c.label.isBlank()) soonest() else null
                if (target == null) Log.w(TAG, "timer cancel: no timer '${c.label}'")
                else removeTimer(ctx, target.key)
            }
            "pause" -> resolve(c.label, key)?.let { t ->
                if (!t.paused && t.endsAtEpochMs > 0L) {
                    t.remainingMs = (t.endsAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    t.paused = true; t.endsAtEpochMs = 0L
                    TimerAlarm.cancel(ctx, t.key)
                }
            }
            "resume" -> resolve(c.label, key)?.let { t ->
                if (t.paused) {
                    t.endsAtEpochMs = System.currentTimeMillis() + t.remainingMs
                    t.paused = false
                    TimerAlarm.schedule(ctx, t.remainingMs, t.label, t.key)
                }
            }
            "restart" -> resolve(c.label, key)?.let { t ->
                val ms = if (t.originalMs > 0L) t.originalMs else t.remainingMs
                if (ms > 0L) {
                    t.endsAtEpochMs = System.currentTimeMillis() + ms
                    t.remainingMs = 0L
                    t.paused = false
                    TimerAlarm.schedule(ctx, ms, t.label, t.key)
                }
            }
            "extend" -> resolve(c.label, key)?.let { t ->
                val add = c.extendS * 1000L
                if (t.paused) t.remainingMs += add
                else if (t.endsAtEpochMs > 0L) {
                    t.endsAtEpochMs += add
                    TimerAlarm.schedule(ctx, t.endsAtEpochMs - System.currentTimeMillis(), t.label, t.key)
                }
            }
            else -> Log.w(TAG, "unknown timer action '${c.action}'")
        }
        persist(ctx)
        Log.i(TAG, "timer ${c.action} label='${c.label}' -> ${timers.size} running")
        notifyUi(ctx)
    }

    private fun resolve(label: String, key: String): Countdown? =
        timers[key] ?: if (label.isBlank()) soonest() else null

    private fun soonest(): Countdown? = timers.values.minByOrNull {
        if (it.paused) Long.MAX_VALUE - it.remainingMs else it.endsAtEpochMs
    }

    private fun removeTimer(ctx: Context, key: String) {
        timers.remove(key)
        TimerAlarm.cancel(ctx, key)
    }

    fun clearTimer(ctx: Context, key: String = "") {
        if (key.isNotBlank() || timers.size == 1) {
            val k = if (key.isNotBlank()) key else timers.keys.first()
            timers.remove(k)
            TimerAlarm.cancel(ctx, k)
        } else {
            timers.keys.toList().forEach { TimerAlarm.cancel(ctx, it) }
            timers.clear()
        }
        persist(ctx)
        notifyUi(ctx)
    }

    data class TimerView(val key: String, val label: String, val text: String, val paused: Boolean)

    fun timerViews(): List<TimerView> = timers.values
        .sortedBy { if (it.paused) Long.MAX_VALUE else it.endsAtEpochMs }
        .map { t ->
            val ms = if (t.paused) t.remainingMs
                     else (t.endsAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val total = ms / 1000
            TimerView(t.key, t.label, "%d:%02d".format(total / 60, total % 60), t.paused)
        }

    fun timerText(): String = timerViews().firstOrNull()?.text.orEmpty()

    private fun persist(ctx: Context) = runCatching {
        val arr = org.json.JSONArray()
        timers.values.forEach { t ->
            arr.put(org.json.JSONObject().apply {
                put("k", t.key); put("l", t.label)
                put("e", t.endsAtEpochMs); put("r", t.remainingMs); put("p", t.paused)
                put("o", t.originalMs)
            })
        }
        Config.setTimers(ctx, arr.toString())
    }.let { }

    fun restoreTimers(ctx: Context) = runCatching {
        if (timers.isNotEmpty()) return@runCatching
        val arr = org.json.JSONArray(Config.timers(ctx).ifBlank { "[]" })
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = Countdown(o.optString("k"), o.optString("l"),
                o.optLong("e"), o.optLong("r"), o.optBoolean("p"), o.optLong("o"))
            if (!t.paused && t.endsAtEpochMs <= now) continue
            timers[t.key] = t
            if (!t.paused) TimerAlarm.schedule(ctx, t.endsAtEpochMs - now, t.label, t.key)
        }
        if (timers.isNotEmpty()) Log.i(TAG, "restored ${timers.size} timer(s)")
    }.let { }

    private var lastDialAtMs = 0L
    private val recentDials = ArrayDeque<Long>()
    private var lastSendAtMs = 0L
    private val recentSends = ArrayDeque<Long>()

    private fun comms(ctx: Context, c: rist.v1.CommsCommand) {
        val raw = c.number.trim()
        if (raw.isBlank()) { Log.w(TAG, "comms: no number"); return }
        // SmsManager needs a digits-only destination; tel: URIs tolerate formatting.
        val number = normaliseNumber(raw)
        if (number.isBlank()) { Log.w(TAG, "comms: number had no digits (${raw.length} chars)"); return }
        val who = c.displayName.ifBlank { raw }

        when (c.action.lowercase()) {
            "send_sms" -> {
                if (c.body.isBlank()) { Log.w(TAG, "comms: send_sms with an empty body"); return }
                if (isEmergency(ctx, number)) { Log.w(TAG, "comms: refusing to text an emergency number"); return }
                val now = SystemClock.elapsedRealtime()
                recentSends.removeAll { now - it > 60_000L }
                if (now - lastSendAtMs < 10_000L || recentSends.size >= 3) {
                    Log.w(TAG, "comms: send rate limited"); return
                }
                lastSendAtMs = now
                recentSends.addLast(now)
                SmsResultReceiver.register(ctx)
                val queued = runCatching {
                    val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        ctx.getSystemService(android.telephony.SmsManager::class.java)
                    else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                    val sentPi = android.app.PendingIntent.getBroadcast(
                        ctx.applicationContext, ("sms:" + number).hashCode(),
                        Intent(SmsResultReceiver.ACTION_SENT)
                            .setPackage(ctx.packageName)
                            .putExtra(SmsResultReceiver.EXTRA_WHO, who)
                            .putExtra(SmsResultReceiver.EXTRA_CID, c.correlationId),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_MUTABLE
                    )
                    // Long messages must be split or the send silently fails past ~160 chars.
                    val parts = sm.divideMessage(c.body)
                    if (parts.size > 1) {
                        val pis = ArrayList<android.app.PendingIntent>(parts.size)
                        repeat(parts.size) { pis.add(sentPi) }
                        sm.sendMultipartTextMessage(number, null, parts, pis, null)
                    } else {
                        sm.sendTextMessage(number, null, c.body, sentPi, null)
                    }
                    true
                }.getOrElse { Log.w(TAG, "comms: send failed outright", it); false }

                if (queued) {
                    Log.i(TAG, "comms: queued to ${maskNumber(number)} (${c.body.length} chars); awaiting result")
                    toast(ctx, "Sending to $who\n${c.body}")
                } else {
                    toast(ctx, "Could not send to $who")
                    CommsResults.record(ctx, c.correlationId, "send_sms", false,
                        "the message could not be handed to the radio")
                }
            }
            "sms" -> {
                val opened = open(ctx, Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$number")).apply {
                    if (c.body.isNotBlank()) putExtra("sms_body", c.body)
                })
                Log.i(TAG, "comms: composing to $who")
                CommsResults.record(ctx, c.correlationId, "sms", opened,
                    if (opened) "" else "could not open the composer")
            }
            "call" -> {
                if (isEmergency(ctx, number)) {
                    Log.w(TAG, "comms: refusing to auto-dial an emergency number; opening the dialer")
                    toast(ctx, "Emergency number — press call yourself")
                    open(ctx, Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number")))
                    CommsResults.record(ctx, c.correlationId, "call", false,
                        "emergency number, opened the dialer instead")
                    return
                }
                val now = SystemClock.elapsedRealtime()
                recentDials.removeAll { now - it > 60_000L }
                if (now - lastDialAtMs < 10_000L || recentDials.size >= 3) {
                    Log.w(TAG, "comms: rate limited (${recentDials.size} in the last minute)")
                    CommsResults.record(ctx, c.correlationId, "call", false, "rate limited")
                    return
                }
                lastDialAtMs = now
                recentDials.addLast(now)
                toast(ctx, "Calling $who\n$number")
                Log.i(TAG, "comms: dialing ${maskNumber(number)}")
                val placed = open(ctx, Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:$number")))
                CommsResults.record(ctx, c.correlationId, "call", placed,
                    if (placed) "" else "the platform refused to place the call")
            }
            "dial" -> {
                toast(ctx, "$who\n$number")
                val opened = open(ctx, Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number")))
                CommsResults.record(ctx, c.correlationId, "dial", opened,
                    if (opened) "" else "could not open the dialer")
            }
            else -> {
                Log.w(TAG, "unknown comms action '${c.action}'")
                CommsResults.record(ctx, c.correlationId, c.action.lowercase(), false,
                    "unknown comms action")
            }
        }
    }

    private fun maskNumber(n: String): String = watch.rist.assistant.maskNumber(n)

    private fun normaliseNumber(raw: String): String {
        val plus = raw.trimStart().startsWith("+")
        val digits = raw.filter { it.isDigit() }
        return if (digits.isBlank()) "" else if (plus) "+$digits" else digits
    }

    internal fun isEmergency(ctx: Context, number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        if (digits in setOf("911", "112", "999", "000", "110", "119", "118", "122", "911911")) return true
        return runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            tm.isEmergencyNumber(number)
        }.getOrDefault(false)
    }

    private fun open(ctx: Context, intent: Intent): Boolean = runCatching {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.onFailure { Log.w(TAG, "comms: could not start ${intent.action}", it) }.getOrDefault(false)

    private fun toast(ctx: Context, msg: String) = runCatching {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(ctx.applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }.let { }

    private fun stopwatch(ctx: Context, c: StopwatchCommand) {
        when (c.action) {
            "start" -> if (!swRunning) {
                swStartedAtMs = SystemClock.elapsedRealtime(); swRunning = true; swPresent = true
            }
            // "stop" pauses (banks elapsed); "start" resumes; "reset" returns to 0:00.
            "stop" -> if (swRunning) {
                swAccumulatedMs += SystemClock.elapsedRealtime() - swStartedAtMs
                swRunning = false
            }
            "reset" -> { swAccumulatedMs = 0L; swStartedAtMs = SystemClock.elapsedRealtime(); swPresent = true }
            else -> Log.w(TAG, "unknown stopwatch action '${c.action}'")
        }
        Log.i(TAG, "stopwatch ${c.action} -> ${stopwatchText()}")
        notifyUi(ctx)
    }

    fun stopwatchAction(ctx: Context, action: String) =
        stopwatch(ctx, StopwatchCommand.newBuilder().setAction(action).build())

    fun clearStopwatch(ctx: Context) {
        swRunning = false; swAccumulatedMs = 0L; swStartedAtMs = 0L; swPresent = false
        notifyUi(ctx)
    }

    fun stopwatchPresent(): Boolean = swPresent
    fun stopwatchRunning(): Boolean = swRunning
    fun timerPresent(): Boolean = timers.isNotEmpty()
    fun timerIsPaused(): Boolean = timerViews().firstOrNull()?.paused == true

    fun timerAction(ctx: Context, action: String, label: String = "") =
        timer(ctx, TimerCommand.newBuilder().setAction(action).setLabel(label).build())

    fun stopwatchText(): String {
        if (!swPresent) return ""
        val ms = swAccumulatedMs + if (swRunning) SystemClock.elapsedRealtime() - swStartedAtMs else 0L
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    fun anythingRunning(): Boolean = timers.isNotEmpty() || stopwatchText().isNotBlank()

    private fun alarm(ctx: Context, c: AlarmCommand) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPendingIntent(ctx, c.alarmId, c.label, c.sound, c.vibrate)
        when (c.action) {
            "arm" -> runCatching {
                val atMs = c.fireAtEpochS * 1000L
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                Log.i(TAG, "alarm armed id='${c.alarmId}' at=${c.fireAtEpochS} label='${c.label}'")
            }.onFailure { Log.w(TAG, "alarm arm failed", it) }
            "cancel" -> { runCatching { am.cancel(pi) }; Log.i(TAG, "alarm cancelled id='${c.alarmId}'") }
            "snooze" -> runCatching {
                val atMs = System.currentTimeMillis() + 9 * 60_000L
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                Log.i(TAG, "alarm snoozed id='${c.alarmId}' 9m")
            }.onFailure { Log.w(TAG, "alarm snooze failed", it) }
            else -> Log.w(TAG, "unknown alarm action '${c.action}'")
        }
    }

    private fun alarmPendingIntent(ctx: Context, id: String, label: String, sound: Boolean, vibrate: Boolean) =
        PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FIRE)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, id)
                .putExtra(AlarmReceiver.EXTRA_LABEL, label)
                .putExtra(AlarmReceiver.EXTRA_SOUND, sound)
                .putExtra(AlarmReceiver.EXTRA_VIBRATE, vibrate),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notifyUi(ctx: Context) {
        LocalBroadcastManager.getInstance(ctx.applicationContext)
            .sendBroadcast(Intent(ACTION_STATE_CHANGED))
    }
}
