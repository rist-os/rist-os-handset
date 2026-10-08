package watch.rist.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import rist.v1.ContactRecord
import rist.v1.ContactSync
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Contact sync, the phone half: the backend's address book pulled into this phone.
 *
 * The backend is the record of truth and the phone holds a mirror, so caller ID works with no
 * network: the ring screen, the feed and the send toast look a number up locally ([CallerId]),
 * never on the network. The mirror is written twice: into the system Contacts provider under the
 * Rist account ([ContactsMirror]) so the dialer and messages apps see it, and into the app's own
 * [ContactIndex] so Rist's screens do not depend on the provider write having landed.
 *
 * Pull: `GET /v1/contacts?since=<cursor>`, paged while `more` is set, applied only once the last
 * page is in. An empty `since` (first run, "Sync now", or after a failed write) is a full pull
 * that replaces the mirror. Pulls run on boot, when the cursor on a wake or a turn differs from
 * the one last applied, once a day, and on "Sync now".
 *
 * Push: contacts added, edited or deleted on the phone go to the backend first, before every pull
 * ([ContactsPush]), so the pull that follows cannot write an older copy over them. A change in the
 * address book is looked for whenever Rist comes back to the front and on every cursor check
 * ([ContactsPush.nudge]).
 *
 * Contacts off, whether the owner's switch or the account's, stops syncing and nothing else: the
 * address book stays as it is, because caller ID has to work with no network.
 */
object ContactsSync {

    private const val TAG = "RistContacts"

    /** The answer a sync route gives when the account has contacts off (X-Rist-Feature: contacts-off). */
    const val FEATURE_OFF_STATUS = 409

    /** A full pull this large is not a phone book; stop rather than loop. */
    internal const val MAX_PAGES = 200

    /** Automatic pulls (wake, turn, boot, daily) are at least this far apart; "Sync now" is not held. */
    internal const val MIN_GAP_MS = 15_000L

    const val ACTION_DAILY = "watch.rist.assistant.CONTACTS_DAILY"
    const val ACTION_CHANGED = "watch.rist.assistant.CONTACTS_CHANGED"

    enum class Refusal { FEATURE_OFF, CREDENTIAL_DEAD, REVOKED, LAPSED, RETRY }

    internal fun classify(code: Int): Refusal? = when {
        code in 200..299 -> null
        code == FEATURE_OFF_STATUS -> Refusal.FEATURE_OFF
        code == 401 -> Refusal.CREDENTIAL_DEAD
        code == 403 -> Refusal.REVOKED
        code == Billing.PAYMENT_REQUIRED -> Refusal.LAPSED
        else -> Refusal.RETRY
    }

    /** The account has contacts, the owner's switch is on, and the backend has not said contacts are off. */
    fun allowed(ctx: Context): Boolean =
        Features.isOn(ctx, Features.Id.CONTACTS) && !Config.contactsSyncOff(ctx) && !Config.contactsRefused(ctx)

    /** The owner's "Sync contacts with Rist" switch. Off stops syncing and keeps the address book. */
    fun setEnabled(ctx: Context, on: Boolean) {
        val was = !Config.contactsSyncOff(ctx)
        Config.setContactsSyncOff(ctx, !on)
        if (on && !was) requestSync(ctx, full = false, reason = "switched on", manual = true)
    }

    fun onRefused(ctx: Context, code: Int) {
        when (classify(code)) {
            Refusal.FEATURE_OFF -> onFeatureOff(ctx)
            Refusal.CREDENTIAL_DEAD -> Enrolment.onCredentialDead(ctx)
            // The lapse and an explicit revocation are recorded where the reply was read (they need
            // the headers); a bare 403 changes nothing.
            Refusal.REVOKED, Refusal.LAPSED, Refusal.RETRY, null -> Unit
        }
    }

    fun onFeatureOff(ctx: Context) {
        if (!Config.contactsRefused(ctx)) Log.i(TAG, "contacts is off for this account; syncing stops, the address book stays")
        Config.setContactsRefused(ctx, true)
    }

    fun onFeatureOn(ctx: Context) {
        if (Config.contactsRefused(ctx)) Log.i(TAG, "contacts is on again; syncing may resume")
        Config.setContactsRefused(ctx, false)
    }

    // ---- the nudge ----

    /**
     * The cursor a wake or a turn carried. Pulls when it differs from the one last applied. The
     * backend leaves it empty while contacts are off for the account, so an empty one is no news,
     * and a non-empty one means contacts are on.
     */
    fun onCursor(ctx: Context, cursor: String) {
        if (cursor.isBlank()) return
        scheduleDailyOnce(ctx)
        if (Config.contactsRefused(ctx)) onFeatureOn(ctx)
        if (Config.contactsSyncOff(ctx)) return
        // Same cursor: nothing new, unless an address book write is owed and can now land. Without
        // the permission that would be a full pull on every wake, so it waits for a real change.
        if (cursor == Config.contactsCursor(ctx) && !rebuildOwed(ctx)) {
            // Nothing new from the backend; something changed on the phone may still be waiting.
            ContactsPush.nudge(ctx, "changed on the phone")
            return
        }
        requestSync(ctx, full = false, reason = "cursor moved")
    }

    // ---- running a pull ----

    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "rist-contacts").apply { isDaemon = true } }
    private val autoQueued = AtomicBoolean(false)
    private val manualQueued = AtomicBoolean(false)
    @Volatile private var lastAutoAtMs = 0L
    @Volatile private var failures = 0
    @Volatile private var failedAtMs = 0L

    /** Repeated failures (network, 5xx, an address book that will not take the write) back off to this. */
    internal const val MAX_BACKOFF_MS = 60 * 60_000L

    /** The gap held between automatic pulls, in ms; a test shortens it. */
    @Volatile internal var minGapMs = MIN_GAP_MS

    /** The test store cannot hold a token (secret keys need the encrypted store), so a test hands one in. */
    @Volatile internal var bearerForTest: String? = null

    /** Lets a test run pulls inline, with no gap held between them. */
    @Volatile internal var runInlineForTest = false
        set(v) { field = v; lastAutoAtMs = 0L; failures = 0; failedAtMs = 0L; minGapMs = MIN_GAP_MS }

    internal fun backoffMs(failures: Int): Long =
        if (failures <= 0) 0L else (MIN_GAP_MS shl (failures - 1).coerceAtMost(12)).coerceAtMost(MAX_BACKOFF_MS)

    /** How long an automatic pull asked for at [now] waits: the gap after the last one, or the failure backoff. */
    internal fun waitMs(now: Long, lastAutoAt: Long, failures: Int, failedAt: Long, gap: Long = MIN_GAP_MS): Long {
        val gapEnd = if (lastAutoAt == 0L) 0L else lastAutoAt + gap
        val backoffEnd = if (failures <= 0) 0L else failedAt + backoffMs(failures)
        return (maxOf(gapEnd, backoffEnd) - now).coerceAtLeast(0L)
    }

    /**
     * Asks for a pull in the background. Requests made while one is waiting fold into it. An
     * automatic one asked for too soon after the last, or while failures are backing off, waits
     * its turn rather than being dropped, so a cursor that moved is never left unpulled. [full]
     * ("Sync now") rebuilds the mirror from scratch; a [manual] pull is never held back.
     */
    fun requestSync(ctx: Context, full: Boolean, reason: String, manual: Boolean = false) {
        val app = ctx.applicationContext
        if (full) Config.setContactsNeedsFull(app, true)
        if (runInlineForTest) { syncBlocking(app, manual); return }
        if (manual) {
            if (!manualQueued.compareAndSet(false, true)) return
            executor.execute { manualQueued.set(false); syncBlocking(app, true) }
            return
        }
        if (!autoQueued.compareAndSet(false, true)) return
        val delay = waitMs(SystemClock.elapsedRealtime(), lastAutoAtMs, failures, failedAtMs, minGapMs)
        executor.schedule({
            autoQueued.set(false)
            lastAutoAtMs = SystemClock.elapsedRealtime()
            val out = syncBlocking(app, false)
            if (out is Outcome.Failed) Log.i(TAG, "contact sync failed ($reason): ${out.why}")
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun noteOutcome(out: Outcome) {
        val failed = when (out) {
            is Outcome.Applied -> !out.mirrored
            is Outcome.Failed -> true
            // A 402 backs off like a failure, so automatic pulls do not keep asking an unpaid account.
            is Outcome.Refused -> classify(out.code).let { it == Refusal.RETRY || it == Refusal.LAPSED }
            else -> false
        }
        if (failed) { failures++; failedAtMs = SystemClock.elapsedRealtime() } else if (out is Outcome.Applied) failures = 0
    }

    sealed class Outcome {
        data class Applied(val full: Boolean, val written: Int, val removed: Int, val mirrored: Boolean = true, val pushed: Int = 0) : Outcome()
        object NotAllowed : Outcome()
        object NotReady : Outcome()
        data class Refused(val code: Int) : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    /** A pull's pages, merged. */
    internal data class Pulled(
        val cursor: String,
        val full: Boolean,
        val records: List<ContactRecord>,
        val deletedIds: Set<String>,
    )

    internal fun contactsUrl(backendUrl: String, since: String): String? {
        var base = backendUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        if (base.endsWith("/v1/device")) base = base.removeSuffix("/device") else if (!base.endsWith("/v1")) base += "/v1"
        val url = "$base/contacts".toHttpUrlOrNull() ?: return null
        return url.newBuilder().addQueryParameter("since", since).build().toString()
    }

    /**
     * Fetches every page from [since]. Returns the merged pull, or the status that stopped it
     * (0 for a transport failure). Within one pull a tombstone beats an edit.
     */
    internal fun fetch(
        http: OkHttpClient,
        backendUrl: String,
        bearer: String,
        device: String,
        since: String,
        onLapse: (Billing.Lapse) -> Unit = {},
        onRevoked: () -> Unit = {},
    ): Pair<Pulled?, Int> {
        val records = LinkedHashMap<String, ContactRecord>()
        val deleted = LinkedHashSet<String>()
        var full = false
        var cursor = since
        var pages = 0
        while (true) {
            if (++pages > MAX_PAGES) { Log.w(TAG, "more than $MAX_PAGES pages; giving up"); return null to 0 }
            val url = contactsUrl(backendUrl, cursor) ?: return null to 0
            val request = Request.Builder().url(url).get()
                .header("Accept", "application/x-protobuf")
                .header("Authorization", bearer)
                .header("X-Rist-Device", device)
                .build()
            val page = try {
                http.newCall(request).execute().use { resp ->
                    if (resp.code == Billing.PAYMENT_REQUIRED) runCatching { onLapse(Billing.lapseWithLine(resp)) }
                    // Only the backend's explicit revocation latches; a bare 403 (proxy, WAF) does not.
                    if (Enrolment.isExplicitRevocation(resp.code, resp.header(Enrolment.REVOKED_HEADER))) runCatching { onRevoked() }
                    if (resp.code != 200) return null to resp.code
                    ContactSync.parseFrom(resp.body?.bytes() ?: ByteArray(0))
                }
            } catch (t: Throwable) {
                Log.i(TAG, "contact pull failed: ${t.javaClass.simpleName}")
                return null to 0
            }
            if (page.full) {
                // A full pull replaces everything; any page that says so restarts the merge.
                if (!full) { records.clear(); deleted.clear() }
                full = true
            }
            for (r in page.contactsList) if (r.id.isNotBlank()) records[r.id] = r
            deleted.addAll(page.deletedIdsList.filter { it.isNotBlank() })
            if (page.more && page.cursor == cursor) {
                Log.w(TAG, "a page said more without moving the cursor; stopping")
                return null to 0
            }
            cursor = page.cursor
            if (!page.more) break
        }
        deleted.forEach { records.remove(it) }
        return Pulled(cursor, full, records.values.toList(), deleted) to 200
    }

    /** One pull, start to finish. Blocking; call off the main thread. Never throws. */
    @Synchronized
    fun syncBlocking(ctx: Context, manual: Boolean = false, http: OkHttpClient = Uploader.sharedClient()): Outcome {
        val out = runCatching { pull(ctx, manual, http) }.getOrElse {
            // The class only: a provider or parser message can quote what it was handed.
            Outcome.Failed(it.javaClass.simpleName)
        }
        noteOutcome(out)
        return out
    }

    /** The address book has to be rebuilt: a write owed, or the Rist account (and its rows) gone. */
    internal fun rebuildOwed(ctx: Context): Boolean =
        ContactsMirror.canWrite(ctx) && (Config.contactsNeedsFull(ctx) || !ContactsMirror.hasAccount(ctx))

    private fun pull(ctx: Context, manual: Boolean, http: OkHttpClient): Outcome {
        if (manual && Config.contactsRefused(ctx)) Config.setContactsRefused(ctx, false)
        if (!allowed(ctx)) return Outcome.NotAllowed
        val bearer = bearerForTest ?: Uploader.bearer(ctx) ?: return Outcome.NotReady
        val backend = Config.backendUrl(ctx).takeIf { it.isNotBlank() } ?: return Outcome.NotReady
        // What the owner changed on the phone goes first.
        var pushed = 0
        if (ContactsMirror.canWrite(ctx)) {
            when (val p = ContactsPush.run(ctx, http, backend, bearer, Config.deviceId(ctx), Config.contactsCursor(ctx),
                onLapse = { Billing.onLapsed(ctx, it) }, onRevoked = { Enrolment.onRevoked(ctx) })) {
                is ContactsPush.Result.Sent -> pushed = p.count
                is ContactsPush.Result.Refused -> { onRefused(ctx, p.code); return Outcome.Refused(p.code) }
                // Not pulled either: a pull now would write the backend's copy over the unsent edit.
                is ContactsPush.Result.Failed -> return Outcome.Failed("push: ${p.why}")
            }
        }
        // Removing the account drops its rows, so a delta would leave the address book empty.
        val since = if (Config.contactsNeedsFull(ctx) || rebuildOwed(ctx)) "" else Config.contactsCursor(ctx)
        val (pulled, code) = fetch(http, backend, bearer, Config.deviceId(ctx), since,
            onLapse = { Billing.onLapsed(ctx, it) }, onRevoked = { Enrolment.onRevoked(ctx) })
        if (pulled == null) {
            if (code != 0) {
                Log.i(TAG, "contact pull refused: HTTP $code")
                onRefused(ctx, code)
                return Outcome.Refused(code)
            }
            return Outcome.Failed("network")
        }
        // A delta against a mirror we do not hold would be a lie, so the backend never sends one;
        // a full answer to a delta request is honoured as a full.
        val indexed = ContactIndex.apply(ctx, pulled.full, pulled.records, pulled.deletedIds)
        val mirrored = ContactsMirror.apply(ctx, pulled.full, pulled.records, pulled.deletedIds)
        // Device contacts the backend now holds are in the Rist account too; keep one copy.
        if (mirrored) ContactsPush.adopt(ctx, pulled.records)
        // A write that did not land is retried as a full pull, so it cannot drift.
        Config.setContactsApplied(ctx, pulled.cursor, needsFull = !mirrored || !indexed)
        Config.setContactsSyncedAt(ctx, System.currentTimeMillis())
        CallerId.forget()
        runCatching {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx.applicationContext)
                .sendBroadcast(Intent(ACTION_CHANGED))
        }
        Log.i(TAG, "contacts synced: $pushed sent, full=${pulled.full}, ${pulled.records.size} changed, " +
            "${pulled.deletedIds.size} deleted, ${ContactIndex.size(ctx)} held, address book=${if (mirrored) "written" else "NOT written"}")
        return Outcome.Applied(pulled.full, pulled.records.size, pulled.deletedIds.size,
            mirrored = (mirrored || !ContactsMirror.canWrite(ctx)) && indexed, pushed = pushed)
    }

    // ---- daily ----

    @Volatile private var dailyScheduled = false

    private fun scheduleDailyOnce(ctx: Context) {
        if (dailyScheduled) return
        scheduleDaily(ctx)
    }

    /** A pull once a day, so a missed nudge is caught up. Inexact; survives until reboot. */
    fun scheduleDaily(ctx: Context) {
        dailyScheduled = true
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(ctx, ContactsDailyReceiver::class.java).setAction(ACTION_DAILY)
            // Already set (it outlives the process, not a reboot): setting it again would push the
            // first run a day out on every app start, and an app restarted daily would never pull.
            if (PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null) return
            val pi = PendingIntent.getBroadcast(
                ctx, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_DAY,
                AlarmManager.INTERVAL_DAY, pi,
            )
        }.onFailure { Log.w(TAG, "could not schedule the daily contact sync: ${it.javaClass.simpleName}") }
    }

    /** On boot: a pull now, and the daily one. */
    fun onBoot(ctx: Context) {
        scheduleDaily(ctx)
        requestSync(ctx, full = false, reason = "boot")
    }
}

class ContactsDailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ContactsSync.ACTION_DAILY) return
        ContactsSync.requestSync(context, full = false, reason = "daily")
    }
}
