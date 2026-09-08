package watch.rist.assistant

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class OtaCheckTest {

    private class Stub(val respond: (String, Map<String, String>) -> Response) : Closeable {
        data class Response(
            val status: Int,
            val reason: String = "OK",
            val headers: Map<String, String> = emptyMap(),
            val body: ByteArray = ByteArray(0),
        )

        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val requests: MutableList<Pair<String, Map<String, String>>> =
            Collections.synchronizedList(ArrayList())
        private val stopped = AtomicBoolean(false)
        val base: String get() = "http://127.0.0.1:${server.localPort}"

        private val thread = Thread {
            while (!stopped.get()) {
                val sock = try { server.accept() } catch (e: Exception) { return@Thread }
                try { serve(sock) } catch (e: Exception) {  }
                try { sock.close() } catch (e: Exception) { }
            }
        }.apply { isDaemon = true; start() }

        private fun serve(sock: Socket) {
            val r = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = r.readLine() ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            requests.add(requestLine to headers)

            val res = respond(requestLine, headers)
            val isHead = requestLine.startsWith("HEAD ")
            val out = StringBuilder("HTTP/1.1 ${res.status} ${res.reason}\r\n")
            res.headers.forEach { (k, v) -> out.append("$k: $v\r\n") }
            if (res.status != 204) out.append("Content-Length: ${res.body.size}\r\n")
            out.append("Connection: close\r\n\r\n")
            val os = sock.getOutputStream()
            os.write(out.toString().toByteArray(Charsets.ISO_8859_1))
            if (!isHead && res.status != 204) os.write(res.body)
            os.flush()
        }

        override fun close() {
            stopped.set(true)
            try { server.close() } catch (e: Exception) { }
        }
    }

    private val client: OkHttpClient = OtaCheck.defaultClient()
    private var stub: Stub? = null
    private val now = 1_784_851_200_000L

    @After fun tearDown() { stub?.close() }

    private fun serving(respond: (String, Map<String, String>) -> Stub.Response): Stub =
        Stub(respond).also { stub = it }

    private fun poll(s: Stub, build: String = "2026072200") =
        OtaCheck.fetchManifest(client, s.base, OtaFixtures.DEVICE, "stable", build, now)

    @Test
    fun theCurrentBuildIsAlwaysSentSoTheServerCanAnswer204() {
        val s = serving { _, _ -> Stub.Response(204, "No Content") }
        assertEquals(OtaRetry.Outcome.UpToDate, poll(s))
        assertEquals("GET /v1/ota/stallion/stable?build=2026072200 HTTP/1.1", s.requests[0].first)
    }

    @Test
    fun aManifestComesBackAndParses() {
        val body = OtaFixtures.json()
        val s = serving { _, _ ->
            Stub.Response(200, headers = mapOf("Content-Type" to "application/json"),
                body = body.toByteArray())
        }
        val o = poll(s)
        assertTrue(o.toString(), o is OtaRetry.Outcome.Offered)
        val parsed = OtaManifest.parse((o as OtaRetry.Outcome.Offered).body)
        assertTrue(parsed.toString(), parsed is OtaManifest.Companion.Parsed.Ok)
    }

    @Test
    fun aBuildStringIsUrlEncodedRatherThanPastedIn() {
        val s = serving { _, _ -> Stub.Response(204, "No Content") }
        poll(s, build = "2026 07&22")
        assertEquals("GET /v1/ota/stallion/stable?build=2026+07%2622 HTTP/1.1", s.requests[0].first)
    }

    @Test
    fun theStreamCapRefusalIsHonouredNotIgnored() {
        val s = serving { _, _ ->
            Stub.Response(503, "Service Unavailable", mapOf("Retry-After" to "10"),
                """{"error": "too many concurrent downloads"}""".toByteArray())
        }
        assertEquals(OtaRetry.Outcome.BackOff(10L), poll(s))
    }

    @Test
    fun theByteBudgetRefusalIsHonouredToo() {
        val s = serving { _, _ ->
            Stub.Response(429, "Too Many Requests", mapOf("Retry-After" to "900"),
                """{"error": "rate limited"}""".toByteArray())
        }
        assertEquals(OtaRetry.Outcome.BackOff(900L), poll(s))
    }

    @Test
    fun anUnpublishedChannelIsNotAnError() {
        val s = serving { _, _ ->
            Stub.Response(404, "Not Found", body = """{"error": "no build for stallion/beta"}""".toByteArray())
        }
        assertTrue(poll(s) is OtaRetry.Outcome.NoBuild)
    }

    @Test
    fun aDeadEndpointIsTransientNotAThrow() {
        val dead = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = dead.localPort
        dead.close()
        val o = OtaCheck.fetchManifest(client, "http://127.0.0.1:$port", OtaFixtures.DEVICE,
            "stable", "2026072200", now)
        assertTrue(o.toString(), o is OtaRetry.Outcome.Transient)
    }

    @Test
    fun aBearerTokenIsSentWhenOneIsConfigured() {
        val s = serving { _, _ -> Stub.Response(204, "No Content") }
        OtaCheck.fetchManifest(client, s.base, OtaFixtures.DEVICE, "stable", "x", now, token = "sekrit")
        assertEquals("Bearer sekrit", s.requests[0].second["authorization"])
    }

    @Test
    fun aBadPathSegmentIsRefusedBeforeAnyRequestIsMade() {
        val s = serving { _, _ -> Stub.Response(200) }
        val o = OtaCheck.fetchManifest(client, s.base, "stallion/../etc", "stable", "x", now)
        assertTrue(o.toString(), o is OtaRetry.Outcome.Transient)
        assertTrue("no request should have been sent", s.requests.isEmpty())
    }

    private val pkgUrl get() = stub!!.base + "/pkg/stallion-ota_update-2026072400.zip"

    private fun preflight() = OtaCheck.preflight(
        client, pkgUrl, OtaFixtures.PAYLOAD_OFFSET, OtaFixtures.PAYLOAD_SIZE, now)

    private fun goodPackageServer() = serving { _, _ ->
        val cr = "bytes ${OtaFixtures.PAYLOAD_OFFSET}-${OtaFixtures.PAYLOAD_OFFSET + 3}/${OtaFixtures.ZIP_SIZE}"
        Stub.Response(206, "Partial Content", mapOf("Content-Range" to cr),
            OtaRange.PAYLOAD_MAGIC.toByteArray())
    }

    @Test
    fun aGoodPackageEndpointPassesAndCostsFourBytes() {
        val s = goodPackageServer()
        assertEquals(OtaCheck.Preflight.Ready(OtaFixtures.ZIP_SIZE), preflight())
        assertTrue(s.requests[0].first.startsWith("HEAD "))
        assertEquals("bytes=${OtaFixtures.PAYLOAD_OFFSET}-${OtaFixtures.PAYLOAD_OFFSET + 3}",
            s.requests[0].second["range"])
        assertTrue(s.requests[1].first.startsWith("GET "))
        assertEquals(2, s.requests.size)
    }

    @Test
    fun preflightBacksOffRatherThanHandingOverToAStall() {
        val s = serving { _, _ ->
            Stub.Response(503, "Service Unavailable", mapOf("Retry-After" to "10"),
                """{"error": "too many concurrent downloads"}""".toByteArray())
        }
        assertEquals(OtaCheck.Preflight.BackOff(10L), preflight())
        assertEquals(1, s.requests.size)
    }

    @Test
    fun aServerThatIgnoresRangeIsRejected() {
        serving { _, _ -> Stub.Response(200, body = ByteArray(64)) }
        val p = preflight()
        assertTrue(p.toString(), p is OtaCheck.Preflight.RangeUnsupported)
    }

    @Test
    fun anObjectThatIsNotThereIsReportedAsMissing() {
        serving { _, _ -> Stub.Response(404, "Not Found") }
        assertEquals(OtaCheck.Preflight.Missing, preflight())
    }

    @Test
    fun anOffsetPastTheEndOfTheObjectIsReported() {
        serving { _, _ -> Stub.Response(416, "Requested Range Not Satisfiable") }
        val p = preflight()
        assertTrue(p.toString(), p is OtaCheck.Preflight.RangeUnsupported)
    }

    @Test
    fun bytesThatAreNotAPayloadMeanTheManifestDescribesAnotherObject() {
        serving { _, _ ->
            val cr = "bytes ${OtaFixtures.PAYLOAD_OFFSET}-${OtaFixtures.PAYLOAD_OFFSET + 3}/${OtaFixtures.ZIP_SIZE}"
            Stub.Response(206, "Partial Content", mapOf("Content-Range" to cr),
                byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }
        assertEquals(OtaCheck.Preflight.WrongOffset, preflight())
    }

    @Test
    fun a206StartingElsewhereIsRejected() {
        serving { _, _ ->
            Stub.Response(206, "Partial Content",
                mapOf("Content-Range" to "bytes 0-3/${OtaFixtures.ZIP_SIZE}"),
                OtaRange.PAYLOAD_MAGIC.toByteArray())
        }
        val p = preflight()
        assertTrue(p.toString(), p is OtaCheck.Preflight.RangeUnsupported)
    }
}
