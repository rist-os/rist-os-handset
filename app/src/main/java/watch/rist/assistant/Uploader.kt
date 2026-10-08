package watch.rist.assistant

import android.content.Context
import android.util.Log
import com.google.protobuf.ByteString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import rist.v1.AudioInput
import rist.v1.DeviceRequest
import rist.v1.DeviceResponse
import rist.v1.ImageInput
import rist.v1.Confirmation
import rist.v1.Location

class Uploader(private val ctx: Context) {

    companion object {
        /** Statuses whose body is a DeviceResponse with a line to say. */
        internal val SPOKEN_ERRORS = setOf(401, 403, 503)

        // Null when no token is provisioned, so no Authorization header is sent. Never logged.
        internal fun bearer(c: Context): String? =
            Config.authToken(c).takeIf { it.isNotBlank() }?.let { "Bearer $it" }

        private const val TAG = "RistUploader"

        // The backend answers the first four photos of a turn and drops the rest with a log
        // line, so anything past this would be silently ignored rather than seen.
        internal const val MAX_PHOTOS_PER_TURN = 4

        // Must match RecordService's capture format.
        private const val SAMPLE_RATE = 16_000
        private const val BIT_DEPTH = 16
        private const val CHANNELS = 1
        private const val BYTES_PER_SAMPLE = 2

        // AudioInput.codec: empty and pcm_s16le are wire-equivalent raw PCM16; opus = Ogg-Opus container.
        const val CODEC_PCM = "pcm_s16le"
        const val CODEC_OPUS = "opus"

        private val PROTOBUF_MEDIA_TYPE = "application/x-protobuf".toMediaType()

        // Device-generated so the turn is cancellable from the instant it is sent.
        internal fun newRequestId(): String = java.util.UUID.randomUUID().toString()

        /**
         * One per thing the user said or tapped; resending that same request reuses it, so the
         * backend runs the turn once (v18). A new request always gets a new one.
         */
        internal fun newUtteranceId(): String = java.util.UUID.randomUUID().toString()

        // Zero bytes parse as a valid empty message, so emptiness is checked before parsing.
        internal fun parseOneOrNull(input: java.io.InputStream): DeviceResponse? {
            val stream = java.io.PushbackInputStream(input, 1)
            val first = stream.read()
            if (first == -1) return null
            stream.unread(first)
            return DeviceResponse.parseFrom(stream)
        }

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(okhttp3.ConnectionPool(4, 30, TimeUnit.SECONDS))
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                // No auto-retry: requests are not idempotent.
                .retryOnConnectionFailure(false)
                .build()
        }

        internal fun sharedClient(): OkHttpClient = client

        /**
         * Turns get three minutes between bytes, not thirty seconds. The backend now lets a turn
         * run as long as the work takes, and it does not stop a turn because our socket closed,
         * so a short timeout reported a failure for work that then went on to happen, and the
         * person said it again. Still no automatic retry: a retry repeats a real-world action.
         */
        internal const val TURN_READ_TIMEOUT_S = 180L

        /** Said when a turn's connection drops after the request was sent. */
        internal const val MAY_HAVE_HAPPENED =
            "I lost the connection, so that may still have gone through — check before asking again"
        private val turnClient: OkHttpClient by lazy {
            client.newBuilder().readTimeout(TURN_READ_TIMEOUT_S, TimeUnit.SECONDS).build()
        }
        internal fun turnClient(): OkHttpClient = turnClient

        // Best-effort and blocking; call off the main thread.
        fun sendProgress(ctx: Context, report: rist.v1.MediaProgress) {
            runCatching {
                // /v1/media/progress speaks JSON, not protobuf.
                val json = org.json.JSONObject()
                    .put("user_id", report.userId)
                    .put("session_id", report.sessionId)
                    .put("item_id", report.itemId)
                    .put("position_s", report.positionS)
                    .put("section", report.section)
                    .put("action", report.action)
                    .put("title", report.title)
                    .toString()
                val httpRequest = Request.Builder()
                    .url(Config.progressUrl(ctx))
                    .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("X-Rist-Device", Config.deviceId(ctx))
                    .apply { bearer(ctx)?.let { header("Authorization", it) } }
                    .build()
                client.newCall(httpRequest).execute().use { resp ->
                    // Best-effort, so no status here changes any state: a 402 or 403 is the turn's to act on.
                    if (!resp.isSuccessful) Log.w(TAG, "progress HTTP ${resp.code}")
                }
            }.onFailure { Log.w(TAG, "progress post failed (best-effort)", it) }
        }

        internal fun buildAudioInput(samples: ByteArray): AudioInput {
            val totalSamples = samples.size / BYTES_PER_SAMPLE
            val durationMs = if (SAMPLE_RATE > 0) (totalSamples.toLong() * 1000L / SAMPLE_RATE).toInt() else 0
            return AudioInput.newBuilder()
                .setSampleRate(SAMPLE_RATE)
                .setBitDepth(BIT_DEPTH)
                .setChannels(CHANNELS)
                .setDurationMs(durationMs)
                .setCodec(CODEC_PCM)
                .setSamples(ByteString.copyFrom(samples))
                .build()
        }

        // bit_depth 0 and duration_ms 0 = unknown for a compressed stream; the backend derives duration on decode.
        internal fun buildOpusAudioInput(oggOpus: ByteArray, durationMs: Int = 0): AudioInput =
            AudioInput.newBuilder()
                .setSampleRate(SAMPLE_RATE)
                .setBitDepth(0)
                .setChannels(CHANNELS)
                .setDurationMs(durationMs)
                .setCodec(CODEC_OPUS)
                .setSamples(ByteString.copyFrom(oggOpus))
                .build()

        // Fails closed: releases messages only when optedIn and the caller has not already populated the field.
        internal fun smsToCarry(
            optedIn: Boolean,
            alreadyOnRequest: Int,
            pending: List<InboundSms>
        ): List<InboundSms> = if (optedIn && alreadyOnRequest == 0) pending else emptyList()

        internal fun attachInboundSms(req: DeviceRequest, carry: List<InboundSms>): DeviceRequest =
            if (carry.isEmpty()) req else req.toBuilder().addAllInboundSms(carry.map {
                rist.v1.InboundSms.newBuilder()
                    .setId(it.id).setFrom(it.from).setBody(it.body).setSentAtMs(it.sentAtMs).build()
            }).build()

        /** The answer to inbound_sms_request: the flag, and the calls of the window. */
        internal fun attachTextsAnswer(req: DeviceRequest, calls: List<RecentCall>): DeviceRequest =
            req.toBuilder().setInboundSmsAnswered(true).addAllRecentCalls(calls.map {
                rist.v1.RecentCall.newBuilder().setId(it.id).setNumber(it.number).setAtMs(it.atMs)
                    .setKind(it.kind).setDurationS(it.durationS).build()
            }).build()

        // Matched on the exact tool id `message`, never on transcript content.
        internal fun releasesInboundSms(toolId: String): Boolean =
            toolId.trim().lowercase() == "message"

        internal fun smsUnreadableFailure(why: String): String =
            "I cannot read your messages right now: " +
                why.ifBlank { "this phone would not let Rist open its message store" }

        // Unconditional, including an empty list: an empty state is what makes the backend re-arm.
        internal fun attachGeofenceState(req: DeviceRequest, ids: List<String>): DeviceRequest =
            req.toBuilder().addAllGeofenceState(ids).build()

        internal fun attachGeofenceEvents(
            req: DeviceRequest,
            crossings: List<GeofenceCrossing>,
            timezone: String,
            nowMs: Long,
        ): DeviceRequest =
            if (crossings.isEmpty()) req else req.toBuilder().addAllGeofenceEvents(crossings.map {
                rist.v1.GeofenceEvent.newBuilder()
                    .setId(it.id)
                    .setFenceId(it.fenceId)
                    .setDirection(it.direction)
                    // Crossing time, never send time.
                    .setAtMs(it.atMs)
                    .setFix(
                        Location.newBuilder()
                            .setLat(it.lat).setLon(it.lon)
                            .setAccuracyM(it.accuracyM)
                            .setTimestamp(it.fixTimeMs)
                            .setAgeS(((nowMs - it.fixTimeMs) / 1000L).toInt().coerceAtLeast(0))
                            .setTimezone(timezone)
                    )
                    .build()
            }).build()

        internal fun buildRequest(
            deviceId: String,
            sessionId: String,
            timestamp: Long,
            authToken: String,
            caps: rist.v1.Capabilities,
            audio: AudioInput
        ): DeviceRequest =
            DeviceRequest.newBuilder()
                .setDeviceId(deviceId)
                .setSessionId(sessionId)
                .setTimestamp(timestamp)
                .setRequestId(newRequestId())
                .setUtteranceId(newUtteranceId())
                .setAudio(audio)
                .setAuthToken(authToken)
                .setCaps(caps)
                .build()

        /**
         * Photos with an optional caption: the v13 shape. `images` sits outside the `input`
         * oneof, so the caption rides in `text` beside it; a bare photo leaves `text` unset,
         * which is how the backend tells a wordless picture from an empty utterance.
         */
        internal fun buildPhotosRequest(
            deviceId: String,
            sessionId: String,
            timestamp: Long,
            authToken: String,
            caps: rist.v1.Capabilities,
            images: List<ImageInput>,
            caption: String,
        ): DeviceRequest =
            DeviceRequest.newBuilder()
                .setDeviceId(deviceId)
                .setSessionId(sessionId)
                .setTimestamp(timestamp)
                .setRequestId(newRequestId())
                .setUtteranceId(newUtteranceId())
                .apply { if (caption.isNotBlank()) setText(caption) }
                .addAllImages(images)
                .setAuthToken(authToken)
                .setCaps(caps)
                .build()

        // The pre-v13 shape: `image` shares the `input` oneof with audio/text, so it went alone
        // and could carry no caption. Kept because the backend still reads it and the contract
        // tests pin its layout; nothing on the device sends it any more.
        internal fun buildImageRequest(
            deviceId: String,
            sessionId: String,
            timestamp: Long,
            authToken: String,
            caps: rist.v1.Capabilities,
            image: ImageInput
        ): DeviceRequest =
            DeviceRequest.newBuilder()
                .setDeviceId(deviceId)
                .setSessionId(sessionId)
                .setTimestamp(timestamp)
                .setRequestId(newRequestId())
                .setUtteranceId(newUtteranceId())
                .setImage(image)
                .setAuthToken(authToken)
                .setCaps(caps)
                .build()

        internal fun buildTextRequest(
            deviceId: String,
            sessionId: String,
            timestamp: Long,
            authToken: String,
            caps: rist.v1.Capabilities,
            text: String
        ): DeviceRequest =
            DeviceRequest.newBuilder()
                .setDeviceId(deviceId)
                .setSessionId(sessionId)
                .setTimestamp(timestamp)
                .setRequestId(newRequestId())
                .setUtteranceId(newUtteranceId())
                .setText(text)
                .setAuthToken(authToken)
                .setCaps(caps)
                .build()
    }

    // Blocking; call on IO.
    // [boxId]: the command box this text came from, if any. It changes nothing about the turn.
    fun sendText(
        text: String,
        onLocationInterim: ((DeviceResponse) -> Unit)? = null,
        boxId: String = "",
    ): DeviceResponse? {
        if (text.isBlank()) {
            Log.w(TAG, "sendText: blank text, nothing to send")
            return null
        }
        val requestProto = buildTextRequest(
            deviceId = Config.deviceId(ctx),
            sessionId = Config.sessionId(ctx),
            timestamp = System.currentTimeMillis(),
            authToken = Config.authToken(ctx),
            caps = DeviceProfile.capabilities(ctx),
            text = text
        ).let { if (boxId.isBlank()) it else it.toBuilder().setBoxId(boxId).build() }
        return post(requestProto, onLocationInterim = onLocationInterim)
    }

    // Blocking; call on IO.
    fun sendToolCall(toolId: String, text: String = "", boxId: String = ""): DeviceResponse? {
        if (toolId.isBlank()) {
            Log.w(TAG, "sendToolCall: no tool_id, nothing to address")
            return null
        }
        val req = buildTextRequest(
            deviceId = Config.deviceId(ctx),
            sessionId = Config.sessionId(ctx),
            timestamp = System.currentTimeMillis(),
            authToken = Config.authToken(ctx),
            caps = DeviceProfile.capabilities(ctx),
            text = text,
        ).toBuilder().setTargetToolId(toolId).apply { if (boxId.isNotBlank()) setBoxId(boxId) }.build()
        // The owner's texts switch off means no texts leave the phone, a direct tool call included.
        val releaseSms = releasesInboundSms(toolId) && Config.textsOnAsk(ctx)
        Log.i(TAG, "tool_call target_tool_id='$toolId' inbound_sms=$releaseSms")
        return post(req, includeInboundSms = releaseSms)
    }

    // Blocking; call on IO.
    fun sendConfirmation(actionId: String, approved: Boolean): DeviceResponse? {
        if (actionId.isBlank()) {
            Log.w(TAG, "sendConfirmation: no action_id, nothing to commit")
            return null
        }
        val req = DeviceRequest.newBuilder()
            .setDeviceId(Config.deviceId(ctx))
            .setSessionId(Config.sessionId(ctx))
            .setTimestamp(System.currentTimeMillis())
            .setRequestId(newRequestId())
                .setUtteranceId(newUtteranceId())
            .setAuthToken(Config.authToken(ctx))
            .setCaps(DeviceProfile.capabilities(ctx))
            .setConfirm(Confirmation.newBuilder().setActionId(actionId).setApproved(approved))
            .build()
        Log.i(TAG, "confirm action_id='$actionId' approved=$approved")
        return post(req)
    }

    // Returns nothing by design; any command in the reply is dropped. Blocking; call on IO.
    fun reportGeofenceCrossings() {
        val req = DeviceRequest.newBuilder()
            .setDeviceId(Config.deviceId(ctx))
            .setSessionId(Config.currentSessionId(ctx))
            // Same clock as `GeofenceEvent.at_ms`.
            .setTimestamp(System.currentTimeMillis())
            .setRequestId(newRequestId())
                .setUtteranceId(newUtteranceId())
            .setAuthToken(Config.authToken(ctx))
            .setCaps(DeviceProfile.capabilities(ctx))
            .build()
        Log.i(TAG, "geofence check-in (no utterance)")
        post(req)
    }

    // Blocking; call on IO.
    fun sendNav(
        text: String,
        targetToolId: String,
        fix: LocationProvider.Fix?,
        routeId: String = "",
        onLocationInterim: ((DeviceResponse) -> Unit)? = null
    ): DeviceResponse? {
        if (text.isBlank()) {
            Log.w(TAG, "sendNav: blank text, nothing to send")
            return null
        }
        if (routeId.isNotBlank()) Log.d(TAG, "sendNav reroute: route_id=$routeId (echo pending backend field)")
        var req = buildTextRequest(
            deviceId = Config.deviceId(ctx),
            sessionId = Config.sessionId(ctx),
            timestamp = System.currentTimeMillis(),
            authToken = Config.authToken(ctx),
            caps = DeviceProfile.capabilities(ctx),
            text = text
        ).toBuilder()
            .setTargetToolId(targetToolId)
            .apply { if (fix != null) setLocation(protoLocation(fix)) }
            .build()
        return post(req, onLocationInterim = onLocationInterim)
    }

    /** One photo ready for the wire: already downscaled, upright, and re-encoded. */
    class Photo(val jpeg: ByteArray, val width: Int, val height: Int, val format: String = "jpeg")

    /**
     * Sends [photos] as one turn, with [caption] as the question about them. Blocking; call on IO.
     *
     * Refuses rather than trims an oversized photo: the caller sized it, so a photo over the cap
     * is a bug on this side, and silently dropping one of several would answer a different
     * question from the one asked.
     */
    fun sendPhotos(
        photos: List<Photo>,
        caption: String,
        onLocationInterim: ((DeviceResponse) -> Unit)? = null
    ): DeviceResponse? {
        if (photos.isEmpty()) {
            Log.w(TAG, "sendPhotos: no photos, nothing to send")
            return null
        }
        if (photos.size > MAX_PHOTOS_PER_TURN) {
            Log.w(TAG, "sendPhotos: ${photos.size} photos is over the per-turn limit of $MAX_PHOTOS_PER_TURN; not sending")
            return null
        }
        val cap = DeviceProfile.maxImageBytes()
        val over = photos.firstOrNull { it.jpeg.isEmpty() || it.jpeg.size > cap }
        if (over != null) {
            Log.w(TAG, "sendPhotos: a photo of ${over.jpeg.size} bytes is outside (0, $cap]; not sending")
            return null
        }
        val images = photos.map {
            ImageInput.newBuilder()
                .setFormat(it.format)
                .setWidth(maxOf(0, it.width))
                .setHeight(maxOf(0, it.height))
                .setData(ByteString.copyFrom(it.jpeg))
                .build()
        }
        val requestProto = buildPhotosRequest(
            deviceId = Config.deviceId(ctx),
            sessionId = Config.sessionId(ctx),
            timestamp = System.currentTimeMillis(),
            authToken = Config.authToken(ctx),
            caps = DeviceProfile.capabilities(ctx),
            images = images,
            caption = caption,
        )
        Log.i(TAG, "photos: ${images.size} image(s) caption=${caption.length}c")
        return post(requestProto, onLocationInterim = onLocationInterim)
    }

    // Blocking; call on IO.
    fun sendOpus(
        oggOpus: ByteArray,
        durationMs: Int = 0,
        onLocationInterim: ((DeviceResponse) -> Unit)? = null
    ): DeviceResponse? {
        if (oggOpus.isEmpty()) {
            Log.w(TAG, "sendOpus: empty audio, nothing to send")
            return null
        }
        val requestProto = buildRequest(
            deviceId = Config.deviceId(ctx),
            sessionId = Config.sessionId(ctx),
            timestamp = System.currentTimeMillis(),
            authToken = Config.authToken(ctx),
            caps = DeviceProfile.capabilities(ctx),
            audio = buildOpusAudioInput(oggOpus, durationMs)
        )
        return post(requestProto, onLocationInterim = onLocationInterim)
    }

    private fun protoLocation(fix: LocationProvider.Fix): Location =
        Location.newBuilder()
            .setLat(fix.lat)
            .setLon(fix.lon)
            .setAccuracyM(fix.accuracyM.roundToInt().coerceAtLeast(0))
            .setTimestamp(fix.timeMs)
            .setAgeS(((System.currentTimeMillis() - fix.timeMs) / 1000L).toInt().coerceAtLeast(0))
            .setTimezone(deviceTimezone())       // field 7 — required on EVERY request
            .build()

    private fun deviceTimezone(): String =
        runCatching { java.time.ZoneId.systemDefault().id }.getOrElse { java.util.TimeZone.getDefault().id }

    // lat/lon stay unset; 0,0 would be read as a real fix.
    private fun protoTimezoneOnly(): Location =
        Location.newBuilder().setTimezone(deviceTimezone()).build()

    var lastFailure: String = ""
        private set

    /** Set when the last send was refused for billing (402); the reply, if any, is the backend's line. */
    var lastLapse: Billing.Lapse? = null
        private set

    private fun post(
        requestProto: DeviceRequest,
        includeInboundSms: Boolean = false,
        isResend: Boolean = false,
        onLocationInterim: ((DeviceResponse) -> Unit)? = null,
        // The re-send answering inbound_sms_request: texts, calls, and the flag that an empty list means none.
        answeringTexts: Boolean = false,
    ): DeviceResponse? {
        lastFailure = ""
        lastLapse = null
        var req = requestProto
        if (req.utteranceId.isBlank()) req = req.toBuilder().setUtteranceId(newUtteranceId()).build()
        if (HomeBoxes.declared()) req = req.toBuilder().setBoxesVersion(HomeBoxes.version(ctx)).build()
        if (DesignSync.declared()) {
            DesignSync.migrateLegacyTheme(ctx)
            // A look picked on the phone that the backend has never seen goes first. If it cannot
            // go yet, DesignSync.apply keeps it on screen over whatever this turn brings back.
            if (DesignSync.version(ctx) == 0L && DesignSync.postPending(ctx)) runCatching { DesignSync.flush(ctx) }
        }
        val designState = if (DesignSync.declared()) DesignSync.pendingState(ctx) else null
        if (DesignSync.declared()) {
            req = req.toBuilder()
                .setDesignVersion(DesignSync.version(ctx))
                .setSettingsVersion(Config.settingsVersion(ctx))
                .apply { if (designState != null) setDesignState(designState) }
                .build()
        }
        val smsRead = if (includeInboundSms) SmsInbox.read(ctx) else SmsRead.Held(emptyList())
        if (smsRead is SmsRead.Unreadable) {
            lastFailure = smsUnreadableFailure(smsRead.why)
            Log.e(TAG, "inbound SMS asked for but unreadable: ${smsRead.why}")
            return null
        }
        val carriedSms = smsToCarry(includeInboundSms, req.inboundSmsCount, (smsRead as SmsRead.Held).messages)
        if (carriedSms.isNotEmpty()) {
            req = attachInboundSms(req, carriedSms)
            Log.i(TAG, "releasing ${carriedSms.size} inbound SMS on an opted-in request")
        }
        if (answeringTexts) {
            val calls = RecentCalls.read(ctx)
            req = attachTextsAnswer(req, calls)
            Log.i(TAG, "answering the backend's ask: ${carriedSms.size} text(s), ${calls.size} call(s)")
        }
        val carriedResults = if (req.commsResultsCount == 0) CommsResults.pending(ctx) else emptyList()
        if (carriedResults.isNotEmpty()) {
            req = req.toBuilder().addAllCommsResults(carriedResults.map {
                rist.v1.CommsResult.newBuilder()
                    .setCorrelationId(it.correlationId).setAction(it.action)
                    .setPerformed(it.performed).setError(it.error).build()
            }).build()
            Log.i(TAG, "carrying ${carriedResults.size} comms results")
        }
        val (settingsCmdId, settingsValues) = SettingsApply.pending(ctx)
        if (settingsValues.isNotEmpty() && !req.hasSettingsState()) {
            req = req.toBuilder().setSettingsState(
                rist.v1.SettingsState.newBuilder()
                    .setCommandId(settingsCmdId)
                    .addAllValues(settingsValues)
            ).build()
            Log.i(TAG, "carrying ${settingsValues.size} settings values for '$settingsCmdId'")
        }
        val vmAcks = if (req.voicemailAckCount == 0) Voicemails.pendingAcks(ctx) else emptyList()
        val vmHeard = if (req.voicemailHeardCount == 0) Voicemails.pendingHeard(ctx) else emptyList()
        if (vmAcks.isNotEmpty() || vmHeard.isNotEmpty()) {
            req = req.toBuilder().addAllVoicemailAck(vmAcks).addAllVoicemailHeard(vmHeard).build()
            Log.i(TAG, "carrying ${vmAcks.size} voicemail acks, ${vmHeard.size} heard")
        }
        // Persist, then ack: pendingAcks reads ids back off disk.
        val noticeAcks =
            if (req.notificationAckCount == 0) NotificationQueue.pendingAcks(ctx) else emptyList()
        if (noticeAcks.isNotEmpty()) {
            req = req.toBuilder().addAllNotificationAck(noticeAcks).build()
            Log.i(TAG, "carrying ${noticeAcks.size} notification ack(s)")
        }
        // geofence_state is attached unconditionally, including when empty.
        if (!LocationProvider.hasPermission(ctx)) Geofences.dropAll(ctx, "no location permission")
        req = attachGeofenceState(req, Geofences.heldIds(ctx))
        val crossings = if (req.geofenceEventsCount == 0) Geofences.pendingCrossings(ctx) else emptyList()
        if (crossings.isNotEmpty()) {
            req = attachGeofenceEvents(req, crossings, deviceTimezone(), System.currentTimeMillis())
            Log.i(TAG, "carrying ${crossings.size} geofence crossing(s)")
        }
        if (!req.hasLocation()) {
            val fix = if (LocationProvider.hasPermission(ctx)) LocationProvider.cached(ctx) else null
            runCatching { AutoTimeZone.consider(ctx, fix) }
            req = req.toBuilder()
                .setLocation(if (fix != null) protoLocation(fix) else protoTimezoneOnly())
                .build()
        } else if (req.location.timezone.isBlank()) {
            req = req.toBuilder()
                .setLocation(req.location.toBuilder().setTimezone(deviceTimezone()))
                .build()
        }
        // The account's location switch is off: only the time zone name leaves the phone.
        if (LocationSwitch.isOff(ctx)) req = LocationSwitch.scrub(req, deviceTimezone())

        if (endpoint.isBlank()) {
            lastFailure = "no assistant service is configured"
            Log.w(TAG, "no backend endpoint configured — set one in Rist Settings (gear \u203a SETTINGS)")
            return null
        }
        // A malformed URL makes Request.Builder.url() throw outside the try below.
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            lastFailure = "the assistant service address is not valid"
            Log.w(TAG, "backend endpoint is not a valid http(s) URL; refusing to send")
            return null
        }
        Log.i("RistNavDbg", "OUT isResend=$isResend hasLocation=${req.hasLocation()} " +
            (if (req.hasLocation() && req.location.lat != 0.0) "fix acc=${req.location.accuracyM}m age=${req.location.ageS}s" else "NO-FIX"))
        val body = req.toByteArray().toRequestBody(PROTOBUF_MEDIA_TYPE)
        val httpRequest = runCatching {
            Request.Builder()
                .url(endpoint)
                .post(body)
                .header("Content-Type", "application/x-protobuf")
                // The Accept header is the streaming opt-in; the plain type gets a single message.
                .header("Accept", StreamingWire.acceptHeader())
                .header("X-Rist-Device", Config.deviceId(ctx))
                .apply { bearer(ctx)?.let { header("Authorization", it) } }
                .build()
        }.getOrElse {
            lastFailure = "the assistant service address is not valid"
            Log.w(TAG, "could not build a request for endpoint '$endpoint'", it)
            return null
        }

        // Registered before execute so the send-to-first-byte window is cancellable.
        val reqId = req.requestId
        val call = turnClient.newCall(httpRequest)
        StreamingCancel.begin(reqId, call)
        var streamed = false
        val resp: DeviceResponse = try {
            call.execute().use { httpResp ->
                if (!httpResp.isSuccessful) {
                    // 413 carries a real DeviceResponse in its body.
                    if (httpResp.code == 413) {
                        val over = httpResp.body?.bytes()
                        val parsed = over?.takeIf { it.isNotEmpty() }
                            ?.let { runCatching { DeviceResponse.parseFrom(it) }.getOrNull() }
                        if (parsed != null) {
                            Log.w(TAG, "backend 413 (too large) req_id=${parsed.requestId} speech=${parsed.speech.text.length} chars")
                            return parsed
                        }
                        lastFailure = "that recording was too long"
                        Log.w(TAG, "backend 413 with an unparseable body")
                        return null
                    }
                    // 402 = pay: the body is a spoken line like the 413's, and nothing is cleared.
                    if (httpResp.code == Billing.PAYMENT_REQUIRED) {
                        val parsed = httpResp.body?.bytes()?.takeIf { it.isNotEmpty() }
                            ?.let { runCatching { DeviceResponse.parseFrom(it) }.getOrNull() }
                            ?.takeIf { it.speech.text.isNotBlank() }
                        // The feed's notice shows the backend's own sentence, not one of ours.
                        val lapse = Billing.lapseFrom(httpResp, parsed?.speech?.text)
                        lastLapse = lapse
                        Billing.onLapsed(ctx, lapse)
                        if (parsed != null) {
                            Log.w(TAG, "backend 402 (${lapse.reason}) req_id=${parsed.requestId}")
                            return parsed
                        }
                        lastFailure = Billing.lineFor(lapse)
                        Log.w(TAG, "backend 402 (${lapse.reason}) with an unparseable body")
                        return null
                    }
                    // 401, 403 and 503 each carry their own spoken line in a DeviceResponse body.
                    val spoken = if (httpResp.code in SPOKEN_ERRORS) {
                        httpResp.body?.bytes()?.takeIf { it.isNotEmpty() }
                            ?.let { runCatching { DeviceResponse.parseFrom(it) }.getOrNull() }
                            ?.takeIf { it.speech.text.isNotBlank() }
                    } else null
                    // A 503 is the backend briefly unable to check this phone; the credential is
                    // fine, so its line is played as the answer and nothing is cleared.
                    if (httpResp.code == 503 && spoken != null) {
                        Log.w(TAG, "backend 503 req_id=${spoken.requestId}; playing its line")
                        return spoken
                    }
                    // 401 = removed from its account or credential dead (pair again with a code);
                    // 403 + revoked header = revoked (pair again). Both stay failures so the pairing
                    // screen opens. A bare 403 (proxy, WAF) never latches.
                    lastFailure = when (httpResp.code) {
                        401 -> {
                            Enrolment.onCredentialDead(ctx)
                            spoken?.speech?.text?.trim()
                                ?: "this phone is no longer connected to your account — pair it again with a code"
                        }
                        403 -> if (Enrolment.isExplicitRevocation(403, httpResp.header(Enrolment.REVOKED_HEADER))) {
                            Enrolment.onRevoked(ctx)
                            spoken?.speech?.text?.trim()
                                ?: "this phone was removed from your account — pair it again in Settings"
                        } else {
                            spoken?.speech?.text?.trim() ?: "the assistant refused this request"
                        }
                        503 -> "the assistant can't be reached right now — try again in a moment"
                        404 -> "the assistant endpoint wasn't found"
                        429 -> "the assistant is busy — try again in a moment"
                        in 500..599 -> "the assistant is having trouble right now"
                        else -> "the assistant returned an error (${httpResp.code})"
                    }
                    Log.w(TAG, "backend HTTP ${httpResp.code} -> $lastFailure")
                    return null
                }
                val body = httpResp.body
                if (body == null) {
                    lastFailure = "the assistant sent an empty reply"
                    Log.w(TAG, "no response body")
                    return null
                }
                // Branch on the response Content-Type, not on the Accept we sent.
                if (StreamingWire.isStreamed(httpResp.header("Content-Type"))) {
                    streamed = true
                    val outcome = StreamingWire.consume(
                        body.byteStream(),
                        isCancelled = { StreamingCancel.isCancelled(reqId) },
                        onProgress = { StreamingStatus.publish(ctx, reqId, it) },
                    )
                    val ending = StreamingStatus.endingOf(outcome)
                    StreamingStatus.publishEnd(ctx, reqId, ending)
                    Log.i(TAG, "streamed turn ended: $ending")
                    when (outcome) {
                        is StreamingWire.Outcome.Completed -> outcome.final
                        else -> {
                            // A stream without a final frame is a dropped connection.
                            lastFailure = StreamingStatus.failureFor(ending)
                            return null
                        }
                    }
                } else {
                    val parsedOrNull = parseOneOrNull(body.byteStream())
                    if (parsedOrNull == null) {
                        lastFailure = "the assistant sent an empty reply"
                        Log.w(TAG, "empty response body")
                        return null
                    }
                    parsedOrNull
                }
            }
        } catch (t: Throwable) {
            if (StreamingCancel.isCancelled(reqId)) {
                lastFailure = ""
                StreamingStatus.publishEnd(ctx, reqId, StreamingStatus.ENDING_CANCELLED)
                Log.i(TAG, "request abandoned at the user's request")
                return null
            }
            if (streamed) StreamingStatus.publishEnd(ctx, reqId, StreamingStatus.ENDING_TRUNCATED)
            lastFailure = when (t) {
                // The request reached the backend, which keeps working after a socket drops. So
                // this is "unknown", not "failed": asking again could send the email twice.
                is java.net.SocketTimeoutException -> MAY_HAVE_HAPPENED
                is java.net.UnknownHostException, is java.net.ConnectException -> "I can't reach the network"
                is com.google.protobuf.InvalidProtocolBufferException -> "the reply was garbled"
                is javax.net.ssl.SSLException -> "the secure connection failed"
                else -> "the network failed"
            }
            Log.e(TAG, "upload/parse failed ($lastFailure) ${t.javaClass.simpleName}: ${t.message}", t)
            return null
        } finally {
            StreamingCancel.end(reqId)
        }

        Log.i("RistNavDbg", "IN  hasNav=${resp.hasNav()} hasLocationRequest=${resp.hasLocationRequest()} " +
            "hasComms=${resp.hasComms()} hasConfirm=${resp.hasConfirm()} " +
            "expectsReply=${resp.expectsReply} " +
            (if (resp.hasComms()) "commsAction='${resp.comms.action}' " else "") +
            "speechLen=${resp.speech.text.length} " +
            (if (resp.hasNav()) "dist=${resp.nav.distanceM}m dur=${resp.nav.durationS}s " +
                "corridor=${resp.nav.hasCorridor()} tiles=${if (resp.nav.hasCorridor()) resp.nav.corridor.tilesCount else 0} frames=${resp.nav.framesCount} tiles=${resp.nav.tilesCount} turns=${resp.nav.turnsCount} routeId='${resp.nav.routeId}'" else "") +
            (if (resp.hasLocationRequest()) "maxAge=${resp.locationRequest.maxAgeS} minAcc=${resp.locationRequest.minAccuracyM}" else ""))
        // sms_ack is ignored: nothing is held on the device to clear.
        runCatching { Billing.onServed(ctx, resp) }
        runCatching { Enrolment.onReinstated(ctx) }
        // Before the fences below: a turn that switches location off must not arm any.
        runCatching { LocationSwitch.onResponse(ctx, resp.hasLocationOff(), resp.locationOff) }
        runCatching { DeveloperMode.onResponse(ctx, resp.hasDeveloperMode(), resp.developerMode) }
        if (resp.hasFeatures()) runCatching { Features.apply(ctx, resp.features) }
        runCatching { ContactsSync.onCursor(ctx, resp.contactsCursor) }
        if (resp.hasBoxes()) runCatching { HomeBoxes.apply(ctx, resp.boxes) }
        if (designState != null) DesignSync.clearState(ctx, designState)
        if (resp.hasDesign()) runCatching { DesignSync.apply(ctx, resp.design) }
        if (resp.smsAckCount > 0) Log.i(TAG, "backend acked ${resp.smsAckCount} SMS; nothing held to clear")
        // Ack before arm.
        if (resp.geofenceAckCount > 0) Geofences.ackCrossings(ctx, resp.geofenceAckList)
        // Presence is the instruction: absent = keep, present-but-empty = hold none.
        if (resp.hasGeofences() && LocationSwitch.isOff(ctx)) {
            Geofences.dropAll(ctx, "the account's location switch is off")
        } else if (resp.hasGeofences()) {
            val fix = if (LocationProvider.hasPermission(ctx)) LocationProvider.cached(ctx) else null
            Geofences.arm(ctx, resp.geofences.fencesList.map {
                Geofences.arming(
                    id = it.id, lat = it.lat, lon = it.lon, radiusM = it.radiusM,
                    direction = it.direction, dwellS = it.dwellS, pollS = it.pollS,
                    expiresEpochS = it.expiresEpochS, requireExitFirst = it.requireExitFirst,
                    label = it.label,
                    nowLat = fix?.lat, nowLon = fix?.lon, nowAccuracyM = fix?.accuracyM ?: 0f,
                )
            })
            runCatching { GeofenceWatcher.reschedule(ctx) }
                .onFailure { Log.w(TAG, "could not reschedule the geofence wake", it) }
        }
        if (resp.commsResultsAckCount > 0) CommsResults.ack(ctx, resp.commsResultsAckList)
        // Order matters: mark acks before upserting what this response delivered.
        if (vmAcks.isNotEmpty()) Voicemails.markAcked(ctx, vmAcks)
        if (settingsValues.isNotEmpty()) SettingsApply.clear(ctx, settingsCmdId)
        if (resp.hasSettings()) SettingsApply.handle(ctx, resp.settings)
        if (resp.voicemailsCount > 0) {
            Voicemails.upsert(
                ctx,
                resp.voicemailsList.filter { VoicemailAudio.safeId(it.id) }.map {
                    Voicemails.Voicemail(
                        id = it.id, fromNumber = it.fromNumber, displayName = it.displayName,
                        receivedAtMs = it.receivedAtMs, durationS = it.durationS,
                        transcript = it.transcript, audioUrl = it.audioUrl,
                        screened = it.screened, heard = it.heard, carrierHeld = it.carrierHeld,
                    )
                },
                requestedIds = emptySet(),
            )
        }
        Config.setVoicemailCount(ctx, resp.voicemailCount)
        // Ack before store, same rule as voicemail.
        if (noticeAcks.isNotEmpty()) NotificationQueue.markAcked(ctx, noticeAcks)
        if (resp.notificationsCount > 0) NotificationQueue.store(ctx, resp.notificationsList)
        // Stored unconditionally, including zero.
        NotificationQueue.setMailUnread(ctx, resp.mailUnread)
        Log.i("RistReqId", "RESP req_id='${resp.requestId}' at=${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} status=${resp.status} spoken=${resp.speech.text.isNotBlank()}")
        Log.i("RistTest", "RESP tool='${resp.toolId}' status=${resp.status} nav=${resp.hasNav()} " +
            "media=${resp.hasMedia()} view=${resp.hasView()} loc_req=${resp.hasLocationRequest()} " +
            "confirm=${resp.hasConfirm()} actions=${resp.actionsCount} " +
            "speech='${resp.speech.text.replace("\n", " ").take(200)}'")
        if (resp.hasLocationRequest() && !isResend) {
            if (!LocationProvider.hasPermission(ctx) || LocationSwitch.isOff(ctx)) return resp
            val lr = resp.locationRequest
            try { onLocationInterim?.invoke(resp) } catch (t: Throwable) { Log.w(TAG, "interim hook threw", t) }

            val fix = LocationProvider.freshBlocking(ctx, lr.maxAgeS, lr.minAccuracyM)
            Log.i("RistNavDbg", "RE-ASK freshBlocking(maxAge=${lr.maxAgeS},minAcc=${lr.minAccuracyM}) -> " +
                (fix?.let { "fix acc=${it.accuracyM}m" } ?: "NULL (could not get a fix) -> giving up, returning location_request"))
            if (fix == null) return resp
            runCatching { AutoTimeZone.consider(ctx, fix) }

            // Re-POST exactly once; isResend=true prevents a loop.
            // A new turn, not a retry: the first one finished by asking for the location, and the
            // same utterance_id would only replay that question.
            val resendReq = requestProto.toBuilder().setLocation(protoLocation(fix))
                .setRequestId(newRequestId()).setUtteranceId(newUtteranceId()).build()
            return post(resendReq, includeInboundSms = includeInboundSms, isResend = true) ?: resp
        }
        if (resp.hasInboundSmsRequest() && !isResend) {
            // The backend asked for this turn's texts: the owner asked something that needs them.
            // Sent only while the owner's switch is on (it is what declared the component).
            if (!TextsOnRequest.declared(ctx)) {
                Log.w(TAG, "asked for texts without having offered them; not sending")
                return resp
            }
            // A new turn, not a retry, exactly as for a location: the same utterance_id would only
            // replay the ask. A failure here is the turn's failure (lastFailure), not the ask's words.
            val resendReq = requestProto.toBuilder()
                .setRequestId(newRequestId()).setUtteranceId(newUtteranceId()).build()
            return post(resendReq, includeInboundSms = true, isResend = true,
                onLocationInterim = onLocationInterim, answeringTexts = true)
        }

        return resp
    }

    private val endpoint: String = Config.backendUrl(ctx)

    private val pcm = ByteArrayOutputStream()
    @Volatile private var started = false

    fun beginStream() {
        if (started) return
        started = true
        pcm.reset()
    }

    // `n` = shorts valid in `buf`; little-endian on the wire.
    fun sendFrame(buf: ShortArray, n: Int) {
        if (!started) return
        val out = pcm
        synchronized(out) {
            for (i in 0 until n) {
                val s = buf[i].toInt()
                out.write(s and 0xFF)
                out.write((s shr 8) and 0xFF)
            }
        }
    }

    fun endStream(onLocationInterim: ((DeviceResponse) -> Unit)? = null): DeviceResponse? {
        if (!started) return null
        started = false

        val samples = synchronized(pcm) { pcm.toByteArray() }
        val audio = buildAudioInput(samples)

        val requestProto = buildRequest(
            deviceId = Config.deviceId(ctx),
            sessionId = Config.sessionId(ctx),
            timestamp = System.currentTimeMillis(),
            authToken = Config.authToken(ctx),
            caps = DeviceProfile.capabilities(ctx),
            audio = audio
        )

        return post(requestProto, onLocationInterim = onLocationInterim)
    }
}
