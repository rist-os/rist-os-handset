package watch.rist.assistant

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AttachmentsTest {

    private lateinit var backend: MockWebServer
    private lateinit var foreign: MockWebServer

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val realPng: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    private fun truncatedPng(): ByteArray {
        fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun chunk(type: String, data: ByteArray): ByteArray {
            val t = type.toByteArray(Charsets.US_ASCII)
            val crc = java.util.zip.CRC32().apply { update(t); update(data) }.value.toInt()
            return be(data.size) + t + data + be(crc)
        }
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) +
            chunk("IHDR", be(64) + be(48) + byteArrayOf(8, 0, 0, 0, 0)) +
            chunk("IEND", ByteArray(0))
    }

    private val token = "ristd_test.secret"

    @Before
    fun start() {
        backend = MockWebServer().also { it.start() }
        foreign = MockWebServer().also { it.start() }
        debuggable(true)
        // Must come first: Config.resolveBackend drops a cleartext endpoint whenever a TLS default is compiled in.
        Config.setDeployDefaultsForTest("", "")
        Config.setBackendEndpoint(ctx(), backend.url("/v1/device").toString())
        assertEquals(
            "the fixture's endpoint did not take effect -- Config.resolveBackend discarded it, so " +
                "these tests would be talking to the compiled-in host, not the MockWebServer",
            backend.url("/v1/device").toString(),
            Config.backendUrl(ctx())
        )
        Attachments.bearerSource = { token }
    }

    @After
    fun stop() {
        runCatching { backend.shutdown() }
        runCatching { foreign.shutdown() }
        // Process-wide cache: unpin it, or every later class in this JVM sees a blank endpoint.
        Config.clearDeployDefaultsForTest()
        Attachments.bearerSource = { Config.authToken(it) }
        Attachments.connectTimeoutS = 10
        Attachments.readTimeoutS = 15
        Attachments.callTimeoutS = 30
    }

    private fun debuggable(on: Boolean) {
        val ai = ctx().applicationInfo
        ai.flags =
            if (on) ai.flags or ApplicationInfo.FLAG_DEBUGGABLE
            else ai.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    private fun att(
        kind: String = "",
        mime: String = "",
        title: String = "",
        text: String = "",
        data: ByteArray? = null,
        uri: String = "",
        toolId: String = "",
    ): rist.v1.Attachment = rist.v1.Attachment.newBuilder()
        .setKind(kind).setMime(mime).setTitle(title).setText(text)
        .setData(if (data == null) ByteString.EMPTY else ByteString.copyFrom(data))
        .setUri(uri).setToolId(toolId)
        .build()

    private fun resolve(vararg a: rist.v1.Attachment) = Attachments.resolve(ctx(), a.toList())

    private fun url(s: String) = s.toHttpUrl()

    @Test
    fun `empty list in, empty list out`() {
        assertEquals(emptyList<RistAttachment>(), Attachments.resolve(ctx(), emptyList()))
        assertEquals(0, backend.requestCount)
        assertEquals(0, foreign.requestCount)
    }

    @Test
    fun `inline bytes pass through untouched, and no fetch is made`() {
        val blob = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
        val out = resolve(att(kind = "data", mime = "application/json", title = "raw", data = blob, toolId = "t1"))

        assertEquals(1, out.size)
        assertNull(out[0].error)
        assertEquals("data", out[0].kind)
        assertEquals("raw", out[0].title)
        assertEquals("t1", out[0].toolId)
        assertTrue(blob.contentEquals(out[0].bytes))
        assertEquals(0, backend.requestCount)
        assertEquals(0, foreign.requestCount)
    }

    @Test
    fun `inline wins over uri when the backend sends both`() {
        val blob = ByteArray(32) { 7 }
        val out = resolve(att(kind = "data", data = blob, uri = foreign.url("/blob").toString()))
        assertNull(out[0].error)
        assertTrue(blob.contentEquals(out[0].bytes))
        assertEquals(0, foreign.requestCount)
    }

    @Test
    fun `kind=text carries its text and an unrecognised kind degrades to data, never to image`() {
        val out = resolve(
            att(kind = "TEXT ", mime = "text/markdown", text = "# hello"),
            att(kind = "video", data = ByteArray(16)),
        )
        assertEquals("text", out[0].kind)
        assertEquals("# hello", out[0].text)
        assertNull(out[0].error)
        assertEquals("data", out[1].kind)
    }

    @Test
    fun `an attachment with neither bytes nor uri is an error, not a silent blank`() {
        val out = resolve(att(kind = "image", mime = "image/png"))
        assertNotNull(out[0].error)
        assertNull(out[0].bytes)
    }

    @Test
    fun `the size and count caps are the documented numbers, not whatever the code now says`() {
        assertEquals("per-attachment cap", 8 * 1024 * 1024, Attachments.MAX_BYTES_PER_ATTACHMENT)
        assertEquals("whole-response cap", 24 * 1024 * 1024, Attachments.MAX_BYTES_PER_RESPONSE)
        assertEquals("attachments per reply", 8, Attachments.MAX_PER_RESPONSE)
    }

    @Test
    fun `inline at exactly eight mebibytes is accepted and one byte over is refused`() {
        val atCap = resolve(att(kind = "data", data = ByteArray(8 * 1024 * 1024)))
        assertNull("an attachment of exactly 8 MiB must be accepted", atCap[0].error)
        assertEquals(8 * 1024 * 1024, atCap[0].bytes?.size)

        val over = resolve(att(kind = "data", data = ByteArray(8 * 1024 * 1024 + 1)))
        assertNotNull("one byte over 8 MiB must be refused", over[0].error)
        assertNull(over[0].bytes)
    }

    @Test
    fun `the response budget is spent down, and an attachment past it is refused rather than served`() {
        val eightMiB = 8 * 1024 * 1024
        val out = Attachments.resolve(
            ctx(),
            (1..4).map { att(kind = "data", title = "blob $it", data = ByteArray(eightMiB)) },
        )

        assertEquals(4, out.size)
        assertNull("the first attachment fits the budget outright", out[0].error)
        assertNull(out[1].error)
        assertNull("three 8 MiB attachments are exactly the 24 MiB budget", out[2].error)
        assertNotNull(
            "the fourth 8 MiB attachment was served on a budget that three 8 MiB attachments had " +
                "already spent — the whole-response cap is not being decremented, so a reply can " +
                "carry 64 MiB of bitmaps",
            out[3].error,
        )
        assertNull(out[3].bytes)
        assertEquals("the refusal must still occupy its slot", "blob 4", out[3].title)
    }

    @Test
    fun `an attachment under the per-attachment ceiling is still refused when the response has no room`() {
        val sevenMiB = 7 * 1024 * 1024
        val out = Attachments.resolve(
            ctx(),
            listOf(
                att(kind = "data", title = "one", data = ByteArray(sevenMiB)),
                att(kind = "data", title = "two", data = ByteArray(sevenMiB)),
                att(kind = "data", title = "three", data = ByteArray(sevenMiB)),
                att(kind = "data", title = "four", data = ByteArray(5 * 1024 * 1024)),
            ),
        )

        assertNull(out[0].error)
        assertNull(out[1].error)
        assertNull("21 MiB is inside the 24 MiB response budget", out[2].error)
        assertNotNull(
            "a 5 MiB attachment was accepted with 3 MiB of the response budget left: the cap being " +
                "applied is the per-attachment ceiling alone, not the smaller of the two",
            out[3].error,
        )
        assertNull(out[3].bytes)
    }

    @Test
    fun `a kind=text note over the byte cap is refused like any other oversized payload`() {
        val over = resolve(att(kind = "text", mime = "text/plain", title = "essay", text = "x".repeat(8 * 1024 * 1024 + 1)))
        assertNotNull(
            "an 8 MiB-plus note was accepted: the text field is an uncapped path past every byte " +
                "limit in this file",
            over[0].error,
        )
        assertEquals("a refused note must not also carry its text", "", over[0].text)

        val ordinary = resolve(att(kind = "text", mime = "text/plain", title = "note", text = "y".repeat(1024)))
        assertNull("a 1 KiB note is not oversized by any reading", ordinary[0].error)
        assertEquals(1024, ordinary[0].text.length)
    }

    @Test(timeout = 10_000)
    fun `readBounded stops mid-stream on an endless body instead of reading it all`() {
        val endless = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 0)
                return len
            }
        }
        assertNull(Attachments.readBounded(endless, 64 * 1024))
    }

    @Test
    fun `readBounded accepts exactly the cap`() {
        val bytes = ByteArray(1000) { it.toByte() }
        assertEquals(1000, Attachments.readBounded(bytes.inputStream(), 1000)?.size)
        assertNull(Attachments.readBounded(bytes.inputStream(), 999))
    }

    @Test
    fun `an oversize fetched body is refused with no Content-Length to warn us`() {
        val body = Buffer().apply { write(ByteArray(Attachments.MAX_BYTES_PER_ATTACHMENT + 1024)) }
        backend.enqueue(MockResponse().setChunkedBody(body, 64 * 1024))

        val out = resolve(att(kind = "data", uri = backend.url("/huge").toString()))
        assertNotNull(out[0].error)
        assertNull(out[0].bytes)
    }

    @Test
    fun `no more than eight attachments are rendered, and they are the first eight`() {
        val many = (1..12).map { att(kind = "text", title = "note $it", text = "body $it") }
        val out = Attachments.resolve(ctx(), many)
        assertEquals(8, out.size)
        assertEquals("note 1", out.first().title)
        assertEquals("note 8", out.last().title)
        assertEquals(
            listOf("note 1", "note 2", "note 3", "note 4", "note 5", "note 6", "note 7", "note 8"),
            out.map { it.title },
        )
    }

    @Test
    fun `schemeRefusal is a whitelist of https, plus http only in a debug build`() {
        assertNull(Attachments.schemeRefusal("https://example.test/a.png", allowHttp = false))
        assertNull(Attachments.schemeRefusal("HTTPS://example.test/a.png", allowHttp = false))
        assertNotNull(Attachments.schemeRefusal("http://example.test/a.png", allowHttp = false))
        assertNull(Attachments.schemeRefusal("http://example.test/a.png", allowHttp = true))
        assertNotNull(Attachments.schemeRefusal("file:///data/data/watch.rist.assistant/shared_prefs/rist.cfg.xml", true))
        assertNotNull(Attachments.schemeRefusal("content://sms/inbox", true))
        assertNotNull(Attachments.schemeRefusal("data:image/png;base64,AAAA", true))
        assertNotNull(Attachments.schemeRefusal("ftp://example.test/a.png", true))
        assertNotNull(Attachments.schemeRefusal("javascript:alert(1)", true))
        assertNotNull(Attachments.schemeRefusal("not a uri at all", true))
    }

    @Test
    fun `a file uri is refused outright and never becomes a request`() {
        val out = resolve(att(kind = "data", uri = "file:///data/data/watch.rist.assistant/shared_prefs/rist.cfg.xml"))
        assertNotNull(out[0].error)
        assertNull(out[0].bytes)
        assertEquals(0, backend.requestCount)
        assertEquals(0, foreign.requestCount)
    }

    @Test
    fun `a data uri is refused rather than treated as inline bytes`() {
        val out = resolve(att(kind = "image", mime = "image/png", uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(realPng)))
        assertNotNull(out[0].error)
        assertNull(out[0].bytes)
    }

    @Test
    fun `http is fetched in a debug build`() {
        debuggable(true)
        backend.enqueue(MockResponse().setBody("ok"))
        val out = resolve(att(kind = "data", uri = backend.url("/a.bin").toString()))
        assertNull(out[0].error)
        assertEquals("ok", String(out[0].bytes!!))
    }

    @Test
    fun `http is refused in a release build, before any connection is made`() {
        val url = backend.url("/a.bin").toString()
        debuggable(false)
        val out = resolve(att(kind = "data", uri = url))
        assertNotNull(out[0].error)
        assertNull(out[0].bytes)
        assertEquals(0, backend.requestCount)
    }

    @Test
    fun `a redirect to a refused scheme is refused, not followed`() {
        backend.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "file:///etc/hosts"))
        val out = resolve(att(kind = "data", uri = backend.url("/start").toString()))
        assertNotNull(out[0].error)
        assertNull(out[0].bytes)
    }

    @Test(timeout = 30_000)
    fun `a redirect loop is abandoned after three hops, not followed forever`() {
        backend.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(302).setHeader("Location", "/round-again")
        }

        val out = resolve(att(kind = "data", uri = backend.url("/start").toString()))
        assertNotNull("an endless redirect loop resolved successfully", out[0].error)
        assertNull(out[0].bytes)
        assertEquals(
            "the handset made ${backend.requestCount} requests chasing one attachment; the hop " +
                "budget is not bounding the loop",
            4, backend.requestCount,
        )
    }

    @Test
    fun `a 404 is an error and its body never becomes the attachment`() {
        backend.enqueue(MockResponse().setResponseCode(404).setBody("<html>no such file</html>"))

        val out = resolve(att(kind = "data", title = "Report", uri = backend.url("/gone.bin").toString()))
        assertNotNull("a 404 resolved as a successful attachment", out[0].error)
        assertNull("the error page became the attachment's bytes", out[0].bytes)
        assertTrue("the status is not in \"${out[0].error}\"", out[0].error!!.contains("404"))
        assertEquals("Report", out[0].title)
    }

    @Test
    fun `a 500 is an error too, and so is anything else outside the 2xx range`() {
        backend.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))
        assertNotNull(resolve(att(kind = "data", uri = backend.url("/boom").toString()))[0].error)

        backend.enqueue(MockResponse().setResponseCode(401).setBody("who are you"))
        val unauthorised = resolve(att(kind = "data", uri = backend.url("/private").toString()))
        assertNotNull(unauthorised[0].error)
        assertNull("an auth challenge body became the attachment", unauthorised[0].bytes)
    }

    @Test
    fun `a Content-Length over the cap is refused on the declaration, before the body is read`() {
        backend.enqueue(MockResponse().setHeader("Content-Length", "104857600"))

        val out = resolve(att(kind = "data", uri = backend.url("/big.bin").toString()))
        assertNotNull(out[0].error)
        assertNull(out[0].bytes)
        assertTrue(
            "a 100 MiB declared body produced \"${out[0].error}\" — the declared length was not " +
                "consulted, so the whole body is read before the size is known",
            out[0].error!!.startsWith("too large"),
        )
        assertTrue(
            "the refusal does not name the size it refused: \"${out[0].error}\"",
            out[0].error!!.contains("102400 KiB"),
        )
        assertEquals(1, backend.requestCount)
    }

    @Test
    fun `bytes that are not an image are refused even when the mime says image`() {
        val notAnImage = "PK this is a zip, or anything else".toByteArray()
        val out = resolve(att(kind = "image", mime = "image/png", title = "chart", data = notAnImage))
        assertNotNull("a mislabelled blob must not reach the decoder", out[0].error)
        assertNull(out[0].bytes)
        assertEquals("chart", out[0].title)
    }

    @Test
    fun `a real png is accepted as an image`() {
        val out = resolve(att(kind = "image", mime = "image/png", data = realPng))
        assertNull(out[0].error)
        assertTrue(realPng.contentEquals(out[0].bytes))
    }

    @Test
    fun `looksLikeImage recognises the containers Android decodes and nothing else`() {
        assertTrue(Attachments.looksLikeImage(realPng))
        assertTrue(Attachments.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(16)))
        assertTrue(Attachments.looksLikeImage("GIF89a".toByteArray() + ByteArray(16)))
        assertFalse(Attachments.looksLikeImage("GIF89b".toByteArray() + ByteArray(16)))
        assertFalse(Attachments.looksLikeImage(ByteArray(64)))
        assertFalse(Attachments.looksLikeImage(byteArrayOf(0x89.toByte(), 0x50)))
        assertFalse(
            "an 11-byte buffer passed the sniff — the length floor is below the widest header this " +
                "function reads",
            Attachments.looksLikeImage("GIF89a".toByteArray() + ByteArray(5)),
        )
        assertFalse(Attachments.looksLikeImage("RIFF".toByteArray() + ByteArray(7)))
    }

    @Test
    fun `a PNG header with no picture behind it is refused at the decoder, not just at the sniff`() {
        val headerOnly = truncatedPng()
        assertTrue(
            "the fixture does not pass the magic-byte sniff, so it never reaches the check under test",
            Attachments.looksLikeImage(headerOnly),
        )
        assertFalse(
            "bytes that BitmapFactory sizes at 0x0 were accepted as a decodable image",
            Attachments.decodesAsImage(headerOnly),
        )

        val out = resolve(att(kind = "image", mime = "image/png", title = "chart", data = headerOnly))
        assertNotNull("a PNG signature over an empty file resolved as a picture", out[0].error)
        assertNull(out[0].bytes)
        assertEquals("chart", out[0].title)
    }

    @Test(timeout = 30_000)
    fun `a server that never answers produces an error, not a hang and not a crash`() {
        Attachments.connectTimeoutS = 1
        Attachments.readTimeoutS = 1
        Attachments.callTimeoutS = 2
        backend.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val out = resolve(att(kind = "data", uri = backend.url("/slow.bin").toString()))
        assertEquals(1, out.size)
        assertNotNull(out[0].error)
        assertNull(out[0].bytes)
    }

    @Test
    fun `isBackendOrigin compares scheme, host and port, not host alone`() {
        val b = url("https://api.example.test:8443/v1/device")
        assertTrue(Attachments.isBackendOrigin(b, url("https://api.example.test:8443/files/1")))
        assertTrue(Attachments.isBackendOrigin(b, url("https://API.EXAMPLE.TEST:8443/files/1")))
        assertFalse(Attachments.isBackendOrigin(b, url("https://api.example.test:9999/files/1")))
        assertFalse(Attachments.isBackendOrigin(b, url("https://evil.example.test:8443/files/1")))
        assertFalse(Attachments.isBackendOrigin(null, url("https://api.example.test:8443/x")))
    }

    @Test
    fun `the bearer goes to the backend and is withheld from a foreign host`() {
        backend.enqueue(MockResponse().setBody("mine"))
        foreign.enqueue(MockResponse().setBody("theirs"))

        val out = resolve(
            att(kind = "data", uri = backend.url("/mine.bin").toString()),
            att(kind = "data", uri = foreign.url("/theirs.bin").toString()),
        )
        assertNull(out[0].error)
        assertNull(out[1].error)

        val mine = backend.takeRequest(5, TimeUnit.SECONDS)!!
        val theirs = foreign.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer $token", mine.getHeader("Authorization"))
        assertNull("the device token must never reach a host the backend named", theirs.getHeader("Authorization"))
    }

    @Test
    fun `a redirect off the backend drops the bearer on the next hop`() {
        backend.enqueue(MockResponse().setResponseCode(302).setHeader("Location", foreign.url("/elsewhere.bin").toString()))
        foreign.enqueue(MockResponse().setBody("theirs"))

        val out = resolve(att(kind = "data", uri = backend.url("/start.bin").toString()))
        assertNull(out[0].error)

        val first = backend.takeRequest(5, TimeUnit.SECONDS)!!
        val second = foreign.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer $token", first.getHeader("Authorization"))
        assertNull("a redirect must not carry the bearer off the backend", second.getHeader("Authorization"))
    }

    @Test
    fun `refused bytes are charged to the response budget, not waved through`() {
        val overCeiling = ByteArray(8 * 1024 * 1024 + 1)
        val out = resolve(
            att(kind = "data", title = "a", data = overCeiling),
            att(kind = "data", title = "b", data = overCeiling),
            att(kind = "data", title = "c", data = overCeiling),
            att(kind = "data", title = "d", data = overCeiling),
        )

        assertEquals(4, out.size)
        out.forEach { assertNotNull("every one of these is over the ceiling", it.error) }

        val outOfRoom = out.count { it.error?.contains("no room") == true }
        assertTrue(
            "no attachment was refused for lack of room, so the 24 MiB response budget was never " +
                "charged for the refused transfers -- four 8 MiB bodies crossed the wire and the " +
                "accounting recorded none of them",
            outOfRoom > 0
        )
    }

    @Test
    fun `two attachments from one host share a connection instead of dialling twice`() {
        backend.enqueue(MockResponse().setBody("one"))
        backend.enqueue(MockResponse().setBody("two"))

        val out = resolve(
            att(kind = "data", uri = backend.url("/one.bin").toString()),
            att(kind = "data", uri = backend.url("/two.bin").toString()),
        )
        assertNull(out[0].error)
        assertNull(out[1].error)

        backend.takeRequest(5, TimeUnit.SECONDS)!!
        val second = backend.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(
            "the second attachment opened a new connection to a host we were already connected " +
                "to, so every attachment is still carrying its own pool and its own cleanup thread",
            second.sequenceNumber > 0
        )
    }

    @Test(timeout = 30_000)
    fun `the shared client still takes its timeouts from the seam, after it has been used once`() {
        backend.enqueue(MockResponse().setBody("warm"))
        assertNull(resolve(att(kind = "data", uri = backend.url("/warm.bin").toString()))[0].error)

        Attachments.connectTimeoutS = 1
        Attachments.readTimeoutS = 1
        Attachments.callTimeoutS = 1
        backend.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val startedAt = System.nanoTime()
        val out = resolve(att(kind = "data", uri = backend.url("/slow.bin").toString()))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNotNull(out[0].error)
        assertTrue(
            "the fetch took ${elapsedMs}ms against a one-second timeout, so the shared client is " +
                "using timeouts fixed when it was built rather than the ones set for this call",
            elapsedMs < 5_000
        )
    }

    private fun logLines(): List<String> = ShadowLog.getLogsForTag("RistAttach").map { it.msg }

    @Test
    fun `originLabel names the host only in a debuggable build`() {
        val b = url("https://api.example.test:8443/v1/device")

        assertEquals("api.example.test:8443", Attachments.originLabel(url("https://api.example.test:8443/f/1"), b, true))
        assertEquals("files.cdn.example:443", Attachments.originLabel(url("https://files.cdn.example/f/1"), b, true))

        assertEquals("the backend", Attachments.originLabel(url("https://api.example.test:8443/f/1"), b, false))
        assertEquals("a third-party host", Attachments.originLabel(url("https://files.cdn.example/f/1"), b, false))
        assertEquals("a third-party host", Attachments.originLabel(url("https://files.cdn.example/f/1"), null, false))
    }

    @Test
    fun `a debuggable build logs the host it fetched from`() {
        debuggable(true)
        ShadowLog.clear()
        foreign.enqueue(MockResponse().setBody("bytes"))

        resolve(att(kind = "data", uri = foreign.url("/pic.bin").toString()))

        val target = "${foreign.url("/pic.bin").host}:${foreign.port}"
        assertTrue(
            "a debug build must still name the host, or there is no way to see where an " +
                "attachment actually went. Logged: ${logLines()}",
            logLines().any { it.contains(target) }
        )
    }

    @Test(timeout = 30_000)
    fun `a release build never writes the host to the log`() {
        debuggable(false)
        Attachments.connectTimeoutS = 1
        Attachments.readTimeoutS = 1
        Attachments.callTimeoutS = 2
        ShadowLog.clear()

        val target = foreign.url("/pic.bin")
        val out = resolve(att(kind = "data", uri = "https://${target.host}:${target.port}/pic.bin"))
        assertNotNull("precondition: the fetch must have been attempted and failed", out[0].error)

        val lines = logLines()
        assertTrue("precondition: the fetch logged nothing at all, so this proves nothing", lines.isNotEmpty())
        assertTrue(
            "a release build recorded which host the assistant made this phone contact. Over a " +
                "session that list is a browsing history, and logcat leaves the device in every " +
                "bug report. Logged: $lines",
            lines.none { it.contains(target.host) || it.contains("${target.port}") }
        )
        assertTrue(
            "the release line must still say which SIDE of the wire it was, or the operator is " +
                "left with nothing at all. Logged: $lines",
            lines.any { it.contains("a third-party host") }
        )
    }

    @Test
    fun `the status code stays in the log beside the origin label`() {
        debuggable(true)
        backend.enqueue(MockResponse().setResponseCode(404))
        ShadowLog.clear()

        resolve(att(kind = "data", uri = backend.url("/missing.bin").toString()))

        assertTrue(
            "the status code is the diagnostic half and must not be redacted with the host. " +
                "Logged: ${logLines()}",
            logLines().any { it.contains("404") }
        )
    }

    @Test
    fun `an oversized note is charged even though it is refused`() {
        val huge = "x".repeat(8 * 1024 * 1024 + 1)
        val out = resolve(
            att(kind = "text", title = "a", text = huge),
            att(kind = "text", title = "b", text = huge),
            att(kind = "text", title = "c", text = huge),
            att(kind = "text", title = "d", text = huge),
        )
        assertTrue(
            "refused notes did not spend the response budget",
            out.count { it.error?.contains("no room") == true } > 0
        )
    }
}
