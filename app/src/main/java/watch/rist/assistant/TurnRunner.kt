package watch.rist.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rist.v1.DeviceResponse

/**
 * A turn belongs to the phone, not to the screen that sent it.
 *
 * The home screen used to run its turns in its own scope, which ends when the screen is
 * destroyed, and took spoken replies through a broadcast it only listens for while it is in front.
 * Opening a tile or the Messages app while a turn was out left the answer with nobody to take it:
 * the entry stayed at "waiting for a reply", and a turn whose screen was gone never sent its text
 * at all, though the assistant had already said it was sent.
 *
 * Here a turn finishes whatever the screen does. Its entry is closed in the [Transcript], the
 * reply's device commands run, and only then is the reply handed to the home screen: at once if
 * one exists, else to the next one that starts.
 */
object TurnRunner {

    private const val TAG = "RistTurn"

    /** Replies kept for a home screen that is not there yet; older ones are already in the feed. */
    private const val MAX_UNDELIVERED = 8

    class Outcome(
        /** The transcript entry this turn answers; 0 when it has none. */
        val entryId: Long,
        val reply: DeviceResponse?,
        /** Why there is no reply, when there is none. */
        val failure: String,
        val subject: String,
        /** A spoken turn: [RecordService] has already played the reply's speech. */
        val voice: Boolean = false,
        /** The person stopped this turn, so its missing reply is no failure. */
        val cancelled: Boolean = false,
        /** What a spoken reply carried, for the status line. */
        val status: String = "",
    ) {
        /** True once this outcome waited for a home screen; its speech is not played late. */
        var late: Boolean = false
            internal set
    }

    fun interface Host {
        fun onTurnOutcome(outcome: Outcome)
    }

    /** Outlives every screen: a turn is only ever stopped by the person, never by navigation. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val main = Handler(Looper.getMainLooper())
    private var host: Host? = null
    /** How long a reply waits for a home screen before it is left to the feed alone. */
    internal const val HELD_FOR_MS = 5 * 60_000L

    private class Held(val outcome: Outcome, val at: Long)

    private val undelivered = ArrayDeque<Held>()

    /**
     * Runs [send] off the main thread and finishes the turn for [entryId] with its reply. [after]
     * runs first, on the main thread, for the caller's own bookkeeping that needs no screen.
     */
    fun launch(
        ctx: Context,
        entryId: Long,
        subject: String,
        send: (Uploader) -> DeviceResponse?,
        after: (Uploader, DeviceResponse?) -> Unit = { _, _ -> },
    ): Job {
        val app = ctx.applicationContext
        Transcript.markLive(entryId)
        return scope.launch {
            val uploader = Uploader(app)
            val reply = try {
                withContext(Dispatchers.IO) { send(uploader) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "$subject turn failed", t)
                null
            }
            runCatching { after(uploader, reply) }.onFailure { Log.w(TAG, "$subject turn bookkeeping failed", it) }
            val cancelled = reply == null && StreamingCancel.takeCancelledFlag()
            finish(app, Outcome(entryId, reply, uploader.lastFailure, subject, cancelled = cancelled))
        }
    }

    /**
     * Closes the turn's entry, runs the reply's device commands, and hands the reply to the home
     * screen. Any thread. Device commands are idempotent per reply, so the screen applying the
     * same reply again does nothing twice.
     */
    fun finish(ctx: Context, outcome: Outcome) {
        val app = ctx.applicationContext
        if (outcome.entryId != 0L) closeEntry(app, outcome.entryId, outcome)
        outcome.reply?.let { reply ->
            runCatching { DeviceCommands.handle(app, reply) }
                .onFailure { Log.w(TAG, "device commands of a ${outcome.subject} reply failed", it) }
        }
        main.post { deliver(outcome) }
    }

    /** The entry leaves "waiting for a reply" for the answer, or for the reason there is none. */
    fun closeEntry(ctx: Context, entryId: Long, outcome: Outcome) {
        val reply = outcome.reply
        runCatching {
            Transcript.update(
                ctx, entryId,
                state = if (reply != null) EntryState.ANSWERED else EntryState.FAILED,
                answer = reply?.speech?.text.orEmpty(),
                requestId = reply?.requestId.orEmpty(),
                checklists = reply?.checklistsList,
                noteCards = reply?.noteCardsList,
                error = when {
                    reply != null -> ""
                    outcome.cancelled -> "cancelled"
                    else -> outcome.failure.ifBlank { "no reply" }
                },
            )
        }.onFailure { Log.w(TAG, "could not close entry $entryId", it) }
    }

    private fun deliver(outcome: Outcome) {
        val h = host
        if (h != null) {
            runCatching { h.onTurnOutcome(outcome) }.onFailure { Log.w(TAG, "the home screen could not show a reply", it) }
            return
        }
        outcome.late = true
        undelivered.addLast(Held(outcome, SystemClock.elapsedRealtime()))
        while (undelivered.size > MAX_UNDELIVERED) undelivered.removeFirst()
        Log.i(TAG, "${outcome.subject} reply kept for the home screen (${undelivered.size} waiting)")
    }

    /**
     * Main thread. [h] is shown every reply from now on, and first those that came while none was
     * and are still recent: an older one is only in the feed, so it cannot start its media, route
     * or confirmation long after it was asked for.
     */
    fun attach(h: Host) {
        host = h
        while (host === h && undelivered.isNotEmpty()) {
            val held = undelivered.removeFirst()
            if (SystemClock.elapsedRealtime() - held.at > HELD_FOR_MS) {
                Log.i(TAG, "${held.outcome.subject} reply waited too long for a home screen; left in the feed")
                continue
            }
            runCatching { h.onTurnOutcome(held.outcome) }.onFailure { Log.w(TAG, "the home screen could not show a reply", it) }
        }
    }

    /** Main thread. */
    fun detach(h: Host) {
        if (host === h) host = null
    }

    internal fun undeliveredForTest(): Int = undelivered.size

    internal fun resetForTest() {
        host = null
        undelivered.clear()
    }
}
