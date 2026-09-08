package watch.rist.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object Voicemails {

    private const val TAG = "RistVm"

    private const val MAX_CACHED = 200

    data class Voicemail(
        val id: String,
        val fromNumber: String,
        val displayName: String,
        val receivedAtMs: Long,
        val durationS: Int,
        val transcript: String,
        val audioUrl: String,
        val screened: Boolean,
        val heard: Boolean,
        val carrierHeld: Boolean = false,
        val acked: Boolean = false,
        val heardPending: Boolean = false,
    ) {
        val who: String get() = displayName.ifBlank { fromNumber }

        val playable: Boolean get() = !carrierHeld && audioUrl.isNotBlank()

        val audioDropped: Boolean get() = !carrierHeld && audioUrl.isBlank()
    }

    internal fun transcriptToStore(
        incoming: String,
        requested: Boolean,
        carrierHeld: Boolean,
        existing: String,
    ): String = when {
        carrierHeld -> incoming
        requested -> incoming.ifBlank { existing }
        else -> existing
    }

    private fun load(ctx: Context): MutableList<Voicemail> {
        val out = mutableListOf<Voicemail>()
        runCatching {
            val arr = JSONArray(Config.voicemails(ctx).ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Voicemail(
                        id = o.getString("id"),
                        fromNumber = o.optString("from"),
                        displayName = o.optString("name"),
                        receivedAtMs = o.optLong("at"),
                        durationS = o.optInt("dur"),
                        transcript = o.optString("text"),
                        audioUrl = o.optString("url"),
                        screened = o.optBoolean("screened"),
                        heard = o.optBoolean("heard"),
                        carrierHeld = o.optBoolean("carrier_held"),
                        acked = o.optBoolean("acked"),
                        heardPending = o.optBoolean("heard_pending"),
                    )
                )
            }
        }.onFailure { Log.w(TAG, "cache unreadable; starting empty", it) }
        return dropTranscriptsFetchedUnderTheOldRule(ctx, out)
    }

    private fun dropTranscriptsFetchedUnderTheOldRule(
        ctx: Context,
        loaded: MutableList<Voicemail>,
    ): MutableList<Voicemail> {
        if (Config.voicemailTranscriptsPurged(ctx)) return loaded
        // Flag set before the save so a failed save cannot re-enter this on every read.
        Config.setVoicemailTranscriptsPurged(ctx, true)
        val cleaned = loaded.map { if (it.carrierHeld) it else it.copy(transcript = "") }
        if (cleaned == loaded.toList()) return loaded
        save(ctx, cleaned)
        SystemVoicemail.sync(ctx, cleaned)
        Log.i(TAG, "upgrade: dropped ${cleaned.count { it.transcript.isBlank() }} pre-rule transcripts")
        return cleaned.toMutableList()
    }

    private fun save(ctx: Context, list: List<Voicemail>) = runCatching {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("from", it.fromNumber); put("name", it.displayName)
                put("at", it.receivedAtMs); put("dur", it.durationS)
                put("text", it.transcript); put("url", it.audioUrl)
                put("screened", it.screened); put("heard", it.heard)
                put("carrier_held", it.carrierHeld)
                put("acked", it.acked); put("heard_pending", it.heardPending)
            })
        }
        Config.setVoicemails(ctx, arr.toString())
    }.onFailure { Log.w(TAG, "cache not saved", it) }.let { }

    fun upsert(ctx: Context, incoming: List<Voicemail>, requestedIds: Set<String> = emptySet()) {
        if (incoming.isEmpty()) return
        val list = load(ctx)
        incoming.forEach { v ->
            val existing = list.firstOrNull { it.id == v.id }
            list.removeAll { it.id == v.id }
            list.add(v.copy(
                transcript = transcriptToStore(
                    incoming = v.transcript,
                    requested = v.id in requestedIds,
                    carrierHeld = v.carrierHeld,
                    existing = existing?.transcript ?: "",
                ),
                acked = false,
                heardPending = existing?.heardPending ?: false,
            ))
        }
        // Prune only heard AND acked; never anything unheard.
        val sorted = list.sortedByDescending { it.receivedAtMs }
        val keep = if (sorted.size <= MAX_CACHED) sorted else {
            val (droppable, protectedOnes) = sorted.partition { it.heard && it.acked }
            (protectedOnes + droppable.take((MAX_CACHED - protectedOnes.size).coerceAtLeast(0)))
                .sortedByDescending { it.receivedAtMs }
        }
        save(ctx, keep)
        VoicemailAudio.pruneTo(ctx, keep.map { it.id }.toSet())
        SystemVoicemail.sync(ctx, keep)
        Log.i(TAG, "upserted ${incoming.size}; ${keep.count { !it.heard }} unheard of ${keep.size}")
    }

    fun all(ctx: Context): List<Voicemail> = load(ctx).sortedByDescending { it.receivedAtMs }

    fun unheardCount(ctx: Context): Int = load(ctx).count { !it.heard && !it.heardPending }

    fun badgeCount(ctx: Context): Int {
        val authoritative = Config.voicemailCount(ctx)
        val justPlayed = load(ctx).count { it.heardPending && !it.heard }
        return (authoritative - justPlayed).coerceAtLeast(0)
    }

    fun pendingAcks(ctx: Context): List<String> = load(ctx).filter { !it.acked }.map { it.id }

    fun pendingHeard(ctx: Context): List<String> = load(ctx).filter { it.heardPending }.map { it.id }

    fun markAcked(ctx: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        save(ctx, load(ctx).map { if (it.id in ids) it.copy(acked = true) else it })
    }

    fun setTranscript(ctx: Context, id: String, text: String) {
        if (id.isBlank() || text.isBlank()) return
        val updated = load(ctx).map { if (it.id == id) it.copy(transcript = text) else it }
        save(ctx, updated)
        SystemVoicemail.sync(ctx, updated)
        Log.i(TAG, "stored a requested transcript for $id")
    }

    fun markHeardLocally(ctx: Context, id: String) {
        val updated = load(ctx).map { if (it.id == id) it.copy(heardPending = true) else it }
        save(ctx, updated)
        SystemVoicemail.sync(ctx, updated)
        Log.i(TAG, "marked heard locally: $id")
    }
}
