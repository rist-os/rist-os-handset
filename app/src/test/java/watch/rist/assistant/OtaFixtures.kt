package watch.rist.assistant

object OtaFixtures {

    const val FILE_HASH = "lURPCIkIAjtMOyB/EjQcl8zDzqtD6Ta3tJef6G/+z2k="
    const val METADATA_HASH = "tBvj43QOB0Jn++JojcpVdbRLz0qdAuL+uTkSy7hokaw="

    const val PAYLOAD_OFFSET = 4443L
    const val PAYLOAD_SIZE = 1662712935L
    const val ZIP_SIZE = 1662717378L
    const val METADATA_SIZE = 70604L

    const val TIMESTAMP = 1784851200L

    const val DEVICE = "stallion"
    const val BUILD = "2026072400"
    const val URL = "https://ota.example.com/pkg/stallion-ota_update-2026072400.zip"

    fun json(
        device: String = DEVICE,
        channel: String = "stable",
        build: String = BUILD,
        timestamp: Long = TIMESTAMP,
        incrementalFrom: String? = null,
        url: String = URL,
        payloadOffset: Long = PAYLOAD_OFFSET,
        payloadSize: Long = PAYLOAD_SIZE,
        zipSize: Long = ZIP_SIZE,
        properties: List<String> = listOf(
            "FILE_HASH=$FILE_HASH",
            "FILE_SIZE=$PAYLOAD_SIZE",
            "METADATA_HASH=$METADATA_HASH",
            "METADATA_SIZE=$METADATA_SIZE",
        ),
    ): String {
        val props = properties.joinToString(", ") { "\"" + it.replace("\"", "\\\"") + "\"" }
        val inc = if (incrementalFrom == null) "null" else "\"$incrementalFrom\""
        return """
        {
          "build": "$build",
          "channel": "$channel",
          "device": "$device",
          "filename": "stallion-ota_update-$build.zip",
          "fingerprint": "rist/stallion/stallion:16/RISTOS/$build:user/release-keys",
          "incremental_from": $inc,
          "payload_offset": $payloadOffset,
          "payload_size": $payloadSize,
          "payload_properties": [$props],
          "sdk_level": "36",
          "security_patch": "2026-07-05",
          "timestamp": $timestamp,
          "url": "$url",
          "zip_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
          "zip_size": $zipSize
        }
        """.trimIndent()
    }

    fun manifest(json: String = json()): OtaManifest =
        when (val p = OtaManifest.parse(json)) {
            is OtaManifest.Companion.Parsed.Ok -> p.manifest
            is OtaManifest.Companion.Parsed.Bad ->
                throw AssertionError("fixture is not a valid manifest: ${p.fault} ${p.detail}")
        }
}
