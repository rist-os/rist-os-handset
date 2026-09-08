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
        // Null when no token is provisioned, so no Authorization header is sent. Never logged.
        internal fun bearer(c: Context): String? =
            Config.authToken(c).takeIf { it.isNotBlank() }?.let { "Bearer $it" }

        private const val TAG = "RistUploader"

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
                .setAudio(audio)
                .setAuthToken(authToken)
                .setCaps(caps)
                .build()

        // image shares the `input` oneof with audio/text, so it is sent alone.
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
                .setText(text)
                .setAuthToken(authToken)
                .setCaps(caps)
                .build()
    }

    // Blocking; call on IO.
    fun sendText(
        text: String,
        onLocationInterim: ((DeviceResponse) -> Unit)? = null
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
        )
        return post(requestProto, onLocationInterim = onLocationInterim)
    }

    // Blocking; call on IO.
    fun sendToolCall(toolId: String, text: String = ""): DeviceResponse? {
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
        ).toBuilder().setTargetToolId(toolId).build()
        val releaseSms = releasesInboundSms(toolId)
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

    // Blocking; call on IO.
    fun sendImage(
        jpeg: ByteArray,
        width: Int,
        height: Int,
        format: String = "jpeg",
        onLocationInterim: ((DeviceResponse) -> Unit)? = null
    ): DeviceResponse? {
        if (jpeg.isEmpty()) {
            Log.w(TAG, "sendImage: empty image, nothing to send")
            return null
        }
        val cap = DeviceProfile.maxImageBytes()
        if (jpeg.size > cap) {
            Log.w(TAG, "sendImage: ${jpeg.size} bytes exceeds the advertised cap of $cap; not sending")
            return null
        }
        val image = ImageInput.newBuilder()
            .setFormat(format)
            .setWidth(maxOf(0, width))
            .setHeight(maxOf(0, height))
            .setData(ByteString.copyFrom(jpeg))
            .build()

        val requestProto = buildImageRequest(
            deviceId = Config.deviceId(ctx),
            sessionId = Config.sessionId(ctx),
            timestamp = System.currentTimeMillis(),
            authToken = Config.authToken(ctx),
            caps = DeviceProfile.capabilities(ctx),
            image = image
        )
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

    private fun post(
        requestProto: DeviceRequest,
        includeInboundSms: Boolean = false,
        isResend: Boolean = false,
        onLocationInterim: ((DeviceResponse) -> Unit)? = null
    ): DeviceResponse? {
        lastFailure = ""
        var req = requestProto
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
            req = req.toBuilder()
                .setLocation(if (fix != null) protoLocation(fix) else protoTimezoneOnly())
                .build()
        } else if (req.location.timezone.isBlank()) {
            req = req.toBuilder()
                .setLocation(req.location.toBuilder().setTimezone(deviceTimezone()))
                .build()
        }

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
        Log.i("RistAuthDbg", "OUT tokenChars=${req.authToken.length} bearerSent=${bearer(ctx) != null} deviceId=${req.deviceId}")
        Log.i("RistNavDbg", "OUT isResend=$isResend hasLocation=${req.hasLocation()} " +
            (if (req.hasLocation() && req.location.lat != 0.0) "fix acc=${req.location.accuracyM}m age=${req.location.ageS}s" else "NO-FIX") +
            " tz=${req.location.timezone}")
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
        val call = client.newCall(httpRequest)
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
                    // 401 = credential dead (re-enrol); 403 = revoked (never enrol); 503 = retry.
                    lastFailure = when (httpResp.code) {
                        401 -> {
                            Enrolment.onCredentialDead(ctx)
                            "this device is setting itself up again"
                        }
                        403 -> {
                            Enrolment.onRevoked(ctx)
                            "this device's access has been turned off"
                        }
                        503 -> "the assistant is briefly unavailable — trying again shortly"
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
                is java.net.SocketTimeoutException ->
                    if (streamed) "that's taking longer than it should — try me again"
                    else "the network timed out"
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
        if (resp.smsAckCount > 0) Log.i(TAG, "backend acked ${resp.smsAckCount} SMS; nothing held to clear")
        // Ack before arm.
        if (resp.geofenceAckCount > 0) Geofences.ackCrossings(ctx, resp.geofenceAckList)
        // Presence is the instruction: absent = keep, present-but-empty = hold none.
        if (resp.hasGeofences()) {
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
        if (settingsValues.isNotEmpty()) SettingsApply.clear(ctx)
        if (resp.hasSettings()) SettingsApply.handle(ctx, resp.settings)
        if (resp.voicemailsCount > 0) {
            Voicemails.upsert(
                ctx,
                resp.voicemailsList.map {
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
            if (!LocationProvider.hasPermission(ctx)) return resp
            val lr = resp.locationRequest
            try { onLocationInterim?.invoke(resp) } catch (t: Throwable) { Log.w(TAG, "interim hook threw", t) }

            val fix = LocationProvider.freshBlocking(ctx, lr.maxAgeS, lr.minAccuracyM)
            Log.i("RistNavDbg", "RE-ASK freshBlocking(maxAge=${lr.maxAgeS},minAcc=${lr.minAccuracyM}) -> " +
                (fix?.let { "fix acc=${it.accuracyM}m" } ?: "NULL (could not get a fix) -> giving up, returning location_request"))
            if (fix == null) return resp

            // Re-POST exactly once; isResend=true prevents a loop.
            val resendReq = requestProto.toBuilder().setLocation(protoLocation(fix)).build()
            return post(resendReq, includeInboundSms = includeInboundSms, isResend = true) ?: resp
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
