package watch.rist.assistant

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.VoicemailContract.Voicemails as VmContract
import android.util.Log

object SystemVoicemail {

    private const val TAG = "RistVmSys"

    private fun sourceUri(ctx: Context): Uri = VmContract.buildSourceUri(ctx.packageName)

    private const val CARRIER_ROW = "carrier-waiting"

    // Legacy suffix; only used to recognise and rewrite old rows.
    private const val HINT = " - hold 1 to listen"

    // The phone app dials this column, so it must hold a bare number and nothing else.
    private fun title(number: String?): String? = number?.takeIf { it.isNotBlank() }

    fun setCarrierWaiting(ctx: Context, waiting: Boolean) {
        val existing = existingIds(ctx) ?: return
        val rowId = existing[CARRIER_ROW]
        Log.i(TAG, "carrier row: waiting=$waiting, existingRow=${rowId != null}, rowsFromUs=${existing.size}")
        if (waiting && rowId == null) {
            runCatching {
                val likely = likelyCaller(ctx)
                ctx.contentResolver.insert(sourceUri(ctx), ContentValues().apply {
                    put(VmContract.SOURCE_PACKAGE, "watch.rist.assistant")
                    put(VmContract.SOURCE_DATA, CARRIER_ROW)
                    title(likely)?.let { put(VmContract.NUMBER, it) }
                    put(VmContract.DATE, System.currentTimeMillis())
                    put(VmContract.DURATION, 0L)
                    put(VmContract.NEW, 1)
                    put(VmContract.IS_READ, 0)
                })
                Log.i(TAG, "published the carrier waiting row to the phone app")
            }.onFailure { Log.w(TAG, "could not publish the carrier waiting row", it) }
        } else if (waiting && rowId != null) {
            runCatching {
                val uri = withId(ctx, rowId)
                val current = ctx.contentResolver
                    .query(uri, arrayOf(VmContract.NUMBER), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
                    .orEmpty()
                if (current.contains(HINT) || current.isBlank()) {
                    val named = current.substringBefore(HINT).filter { it.isDigit() }
                    val src = if (named.length >= 10) named else likelyCaller(ctx)
                    val clean = title(src)
                    if (clean != null) {
                        ctx.contentResolver.update(
                            uri, ContentValues().apply { put(VmContract.NUMBER, clean) }, null, null
                        )
                        Log.i(TAG, "rewrote the waiting row's number to a dialable one")
                    }
                }
                val flags = ctx.contentResolver
                    .query(uri, arrayOf(VmContract.NEW, VmContract.IS_READ), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else null }
                if (flags != null && (flags.first == 0 || flags.second != 0)) {
                    ctx.contentResolver.update(uri, ContentValues().apply {
                        put(VmContract.NEW, 1)
                        put(VmContract.IS_READ, 0)
                    }, null, null)
                    Log.i(TAG, "phone app marked the waiting row read on view; restored it to unread")
                }
            }.onFailure { Log.w(TAG, "could not attribute the waiting row", it) }
        } else if (!waiting && rowId != null) {
            runCatching {
                ctx.contentResolver.delete(withId(ctx, rowId), null, null)
                Log.i(TAG, "carrier mailbox is clear; removed the waiting row")
            }.onFailure { Log.w(TAG, "could not remove the carrier waiting row", it) }
        }
    }

    fun sync(ctx: Context, messages: List<Voicemails.Voicemail>) {
        val existing = existingIds(ctx) ?: return
        val ours = messages.filter { !it.carrierHeld }
        val wanted = ours.map { it.id }.toSet()

        ours.forEach { vm ->
            if (existing.containsKey(vm.id)) update(ctx, existing.getValue(vm.id), vm)
            else insert(ctx, vm)
        }
        existing.filterKeys { it !in wanted && it != CARRIER_ROW }.forEach { (id, rowId) ->
            runCatching {
                ctx.contentResolver.delete(withId(ctx, rowId), null, null)
                Log.i(TAG, "removed published voicemail $id")
            }.onFailure { Log.w(TAG, "could not remove $id", it) }
        }
    }

    private fun likelyCaller(ctx: Context): String? = runCatching {
        val cutoff = System.currentTimeMillis() - ATTRIBUTION_WINDOW_MS
        ctx.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE),
            "${android.provider.CallLog.Calls.TYPE} = ? AND ${android.provider.CallLog.Calls.DATE} > ?",
            arrayOf(android.provider.CallLog.Calls.MISSED_TYPE.toString(), cutoff.toString()),
            "${android.provider.CallLog.Calls.DATE} DESC"
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.onFailure {
        Log.w(TAG, "could not read the call log to attribute the voicemail", it)
    }.getOrNull()

    private const val ATTRIBUTION_WINDOW_MS = 6L * 60L * 60L * 1000L

    fun carrierRowPresent(ctx: Context): Boolean? = runCatching {
        existingIds(ctx)?.containsKey(CARRIER_ROW)
    }.getOrNull()

    @Suppress("unused")
    fun carrierRowUnread(ctx: Context): Boolean? = runCatching {
        ctx.contentResolver.query(
            sourceUri(ctx),
            arrayOf(VmContract.NEW),
            "${VmContract.SOURCE_DATA} = ?",
            arrayOf(CARRIER_ROW),
            null
        )?.use { if (it.moveToFirst()) it.getInt(0) != 0 else null }
    }.getOrNull()

    private fun existingIds(ctx: Context): Map<String, Long>? = runCatching {
        val out = mutableMapOf<String, Long>()
        ctx.contentResolver.query(
            sourceUri(ctx), arrayOf(VmContract._ID, VmContract.SOURCE_DATA), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(1) ?: continue
                out[key] = c.getLong(0)
            }
        }
        out
    }.onFailure {
        Log.w(TAG, "voicemail provider unavailable; not publishing to the phone app", it)
    }.getOrNull()

    private fun withId(ctx: Context, rowId: Long): Uri =
        Uri.withAppendedPath(sourceUri(ctx), rowId.toString())

    private fun insert(ctx: Context, vm: Voicemails.Voicemail) {
        val uri = runCatching {
            ctx.contentResolver.insert(sourceUri(ctx), values(vm, forInsert = true))
        }.onFailure { Log.w(TAG, "insert failed for ${vm.id}", it) }.getOrNull() ?: return
        Log.i(TAG, "published voicemail ${vm.id} to the phone app")
        attachAudio(ctx, uri, vm)
    }

    private fun update(ctx: Context, rowId: Long, vm: Voicemails.Voicemail) {
        val uri = withId(ctx, rowId)
        runCatching {
            ctx.contentResolver.update(uri, values(vm, forInsert = false), null, null)
        }.onFailure { Log.w(TAG, "update failed for ${vm.id}", it) }
        if (!hasContent(ctx, uri)) attachAudio(ctx, uri, vm)
    }

    private fun values(vm: Voicemails.Voicemail, forInsert: Boolean) = ContentValues().apply {
        put(VmContract.NUMBER, vm.fromNumber)
        put(VmContract.DATE, vm.receivedAtMs)
        put(VmContract.DURATION, vm.durationS.toLong())
        put(VmContract.IS_READ, if (vm.heard || vm.heardPending) 1 else 0)
        // Written even when blank, so the platform column never outlives our store.
        put(VmContract.TRANSCRIPTION, vm.transcript)
        if (forInsert) {
            put(VmContract.SOURCE_PACKAGE, "watch.rist.assistant")
            put(VmContract.SOURCE_DATA, vm.id)
            put(VmContract.NEW, if (vm.heard || vm.heardPending) 0 else 1)
        }
    }

    private fun hasContent(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.query(uri, arrayOf(VmContract.HAS_CONTENT), null, null, null)
            ?.use { if (it.moveToFirst()) it.getInt(0) == 1 else false } ?: false
    }.getOrDefault(false)

    private fun attachAudio(ctx: Context, uri: Uri, vm: Voicemails.Voicemail) {
        if (!vm.playable) return
        val app = ctx.applicationContext
        Thread {
            val result = VoicemailAudio.fetch(app, vm.id)
            if (result !is VoicemailAudio.Result.Ready) {
                Log.i(TAG, "no audio to attach for ${vm.id} yet")
                return@Thread
            }
            runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    result.file.inputStream().use { it.copyTo(out) }
                }
                app.contentResolver.update(
                    uri, ContentValues().apply { put(VmContract.HAS_CONTENT, 1) }, null, null
                )
                Log.i(TAG, "attached audio for ${vm.id}")
            }.onFailure { Log.w(TAG, "could not attach audio for ${vm.id}", it) }
        }.start()
    }
}
