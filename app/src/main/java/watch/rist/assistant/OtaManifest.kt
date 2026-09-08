package watch.rist.assistant

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class OtaManifest(
    val device: String,
    val channel: String,
    val build: String,
    val fingerprint: String,
    val timestampSeconds: Long,
    val incrementalFrom: String?,
    val url: String,
    val payloadOffset: Long,
    val payloadSize: Long,
    val payloadProperties: List<String>,
    val zipSize: Long,
    val securityPatch: String,
) {

    fun properties(): Map<String, String> =
        payloadProperties.mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()

    companion object {

        const val FILE_HASH = "FILE_HASH"
        const val FILE_SIZE = "FILE_SIZE"
        const val METADATA_HASH = "METADATA_HASH"
        const val METADATA_SIZE = "METADATA_SIZE"

        val REQUIRED_PROPERTIES = listOf(FILE_HASH, FILE_SIZE, METADATA_HASH, METADATA_SIZE)

        enum class Fault {
            NOT_JSON,
            MISSING_FIELD,
            BAD_VALUE,
            INSECURE_URL,
            BAD_PROPERTIES,
            INCONSISTENT,
        }

        sealed class Parsed {
            data class Ok(val manifest: OtaManifest) : Parsed()
            data class Bad(val fault: Fault, val detail: String) : Parsed()
        }

        fun parse(body: String?): Parsed {
            if (body.isNullOrBlank()) return Parsed.Bad(Fault.NOT_JSON, "empty body")
            val o = try {
                JSONObject(body)
            } catch (e: JSONException) {
                return Parsed.Bad(Fault.NOT_JSON, e.message ?: "not a JSON object")
            }

            for (k in listOf("device", "channel", "build", "timestamp", "url",
                             "payload_offset", "payload_size", "payload_properties")) {
                if (!o.has(k) || o.isNull(k)) return Parsed.Bad(Fault.MISSING_FIELD, k)
            }

            // opt*, not get*: org.json getters throw on a type mismatch.
            val timestamp = o.optLong("timestamp", -1L)
            val offset = o.optLong("payload_offset", -1L)
            val size = o.optLong("payload_size", -1L)
            val zipSize = o.optLong("zip_size", 0L)

            if (timestamp <= 0L) return Parsed.Bad(Fault.BAD_VALUE, "timestamp=$timestamp")
            if (offset < 0L) return Parsed.Bad(Fault.BAD_VALUE, "payload_offset=$offset")
            // applyPayload treats payload_size 0 as "read to the end of the stream".
            if (size <= 0L) return Parsed.Bad(Fault.BAD_VALUE, "payload_size=$size")

            val url = o.optString("url").trim()
            if (!url.startsWith("https://")) return Parsed.Bad(Fault.INSECURE_URL, url)

            val propsArray: JSONArray = o.optJSONArray("payload_properties")
                ?: return Parsed.Bad(Fault.BAD_PROPERTIES, "payload_properties is not an array")
            val props = ArrayList<String>(propsArray.length())
            for (i in 0 until propsArray.length()) props.add(propsArray.optString(i))

            val m = OtaManifest(
                device = o.optString("device"),
                channel = o.optString("channel"),
                // Trimmed here because OtaState.approveBuild trims; both sides must compare one string.
                build = o.optString("build").trim(),
                fingerprint = o.optString("fingerprint"),
                timestampSeconds = timestamp,
                // optString returns the string "null" for an explicit JSON null.
                incrementalFrom = if (o.isNull("incremental_from")) null
                else o.optString("incremental_from").ifBlank { null },
                url = url,
                payloadOffset = offset,
                payloadSize = size,
                payloadProperties = props,
                zipSize = zipSize,
                securityPatch = o.optString("security_patch"),
            )

            for (k in listOf(m.device to "device", m.channel to "channel", m.build to "build")) {
                if (k.first.isBlank()) return Parsed.Bad(Fault.MISSING_FIELD, k.second)
            }

            return validate(m)
        }

        fun validate(m: OtaManifest): Parsed {
            if (m.payloadProperties.size != REQUIRED_PROPERTIES.size) {
                return Parsed.Bad(Fault.BAD_PROPERTIES,
                    "expected ${REQUIRED_PROPERTIES.size} properties, got ${m.payloadProperties.size}")
            }

            val seen = LinkedHashMap<String, String>()
            for (line in m.payloadProperties) {
                val i = line.indexOf('=')
                if (i <= 0) return Parsed.Bad(Fault.BAD_PROPERTIES, "not KEY=VALUE: '$line'")
                val key = line.substring(0, i)
                // update_engine splits at the first '=' and rejects the whole call on a repeated key.
                if (seen.put(key, line.substring(i + 1)) != null) {
                    return Parsed.Bad(Fault.BAD_PROPERTIES, "repeated key: $key")
                }
            }
            for (k in REQUIRED_PROPERTIES) {
                if (k !in seen) return Parsed.Bad(Fault.BAD_PROPERTIES, "missing property: $k")
            }

            // Both are base64, not hex, and must be non-empty: GetPayloadId() is
            // FILE_HASH + METADATA_HASH and an empty one disables resume.
            for (k in listOf(FILE_HASH, METADATA_HASH)) {
                val v = seen.getValue(k)
                if (v.isBlank()) return Parsed.Bad(Fault.BAD_PROPERTIES, "$k is empty")
                if (!isBase64(v)) return Parsed.Bad(Fault.BAD_PROPERTIES, "$k is not base64: $v")
            }

            val fileSize = seen.getValue(FILE_SIZE).toLongOrNull()
                ?: return Parsed.Bad(Fault.BAD_PROPERTIES, "FILE_SIZE is not a number")
            val metadataSize = seen.getValue(METADATA_SIZE).toLongOrNull()
                ?: return Parsed.Bad(Fault.BAD_PROPERTIES, "METADATA_SIZE is not a number")

            if (fileSize != m.payloadSize) {
                return Parsed.Bad(Fault.INCONSISTENT,
                    "FILE_SIZE=$fileSize but payload_size=${m.payloadSize}")
            }
            // The metadata is the front of payload.bin, so it cannot exceed it.
            if (metadataSize <= 0L || metadataSize > m.payloadSize) {
                return Parsed.Bad(Fault.INCONSISTENT,
                    "METADATA_SIZE=$metadataSize against payload_size=${m.payloadSize}")
            }

            if (m.zipSize > 0L) {
                if (m.payloadOffset >= m.zipSize) {
                    return Parsed.Bad(Fault.INCONSISTENT,
                        "payload_offset=${m.payloadOffset} is past zip_size=${m.zipSize}")
                }
                // Subtraction, not payloadOffset + payloadSize: the sum of two attacker-chosen Longs can overflow.
                if (m.payloadSize > m.zipSize - m.payloadOffset) {
                    return Parsed.Bad(Fault.INCONSISTENT,
                        "payload ${m.payloadOffset}+${m.payloadSize} overruns zip_size=${m.zipSize}")
                }
            }

            return Parsed.Ok(m)
        }

        // Hand-rolled: the platform and JVM Base64 decoders differ on malformed input.
        internal fun isBase64(s: String): Boolean {
            if (s.isEmpty() || s.length % 4 != 0) return false
            val body = s.trimEnd('=')
            if (s.length - body.length > 2) return false
            return body.all { (it.code < 128 && it.isLetterOrDigit()) || it == '+' || it == '/' }
        }
    }
}
