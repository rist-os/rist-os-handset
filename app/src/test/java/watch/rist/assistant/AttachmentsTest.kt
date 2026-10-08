package watch.rist.assistant

import com.google.protobuf.ByteString
import okhttp3.HttpUrl
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

    /** Stands in for imgs.search.brave.com: every allowed url is dialled here instead. */
    private lateinit var brave: MockWebServer

    /** Any other host. Nothing may ever reach it. */
    private lateinit var foreign: MockWebServer

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

    private val braveUrl = "https://imgs.search.brave.com/Zx9abc/rs:fit:500:0:0/g:ce/aHR0cHM6Ly9leGFtcGxl"

    @Before
    fun start() {
        brave = MockWebServer().also { it.start() }
        foreign = MockWebServer().also { it.start() }
        Attachments.dialForTest = { real: HttpUrl ->
            brave.url("/").newBuilder()
                .encodedPath(real.encodedPath)
                .encodedQuery(real.encodedQuery)
                .build()
        }
    }

    @After
    fun stop() {
        runCatching { brave.shutdown() }
        runCatching { foreign.shutdown() }
        Attachments.dialForTest = null
        Attachments.loadTimeoutMs = Attachments.REMOTE_LOAD_TIMEOUT_MS
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

    /** A Brave picture exactly as the backend sends it. */
    private fun bravePic(uri: String = braveUrl, title: String = "Photo: theguardian.com", kind: String = "image") =
        att(kind = kind, mime = "image/jpeg", title = title, uri = uri, toolId = "image-search")

    private fun resolve(vararg a: rist.v1.Attachment) = Attachments.resolve(a.toList())

    private fun pngResponse() = MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(realPng))

    // ---- rule 1: the host allowlist -------------------------------------------------------

    @Test
    fun `the allowlist is https on exactly imgs dot search dot brave dot com, port 443, no user-info`() {
        assertEquals("imgs.search.brave.com", Attachments.REMOTE_IMAGE_HOST)
        val allowed = listOf(
            braveUrl,
            "https://imgs.search.brave.com/x.jpg",
            "https://imgs.search.brave.com:443/x.jpg",
            "HTTPS://IMGS.SEARCH.BRAVE.COM/x.jpg",
            "  https://imgs.search.brave.com/x.jpg  ",
            "https://imgs.search.brave.com/x.jpg?w=500",
        )
        allowed.forEach { assertTrue("refused an allowed url: $it", Attachments.isAllowedImageUrl(it)) }

        val refused = listOf(
            "http://imgs.search.brave.com/x.jpg",
            "https://imgs.search.brave.com:8443/x.jpg",
            "https://imgs.search.brave.com:80/x.jpg",
            "https://a.imgs.search.brave.com/x.jpg",
            "https://search.brave.com/x.jpg",
            "https://brave.com/x.jpg",
            "https://imgs.search.brave.com.evil.test/x.jpg",
            "https://imgs.search.brave.com./x.jpg",
            "https://evilimgs.search.brave.com/x.jpg",
            "https://user@imgs.search.brave.com/x.jpg",
            "https://user:pw@imgs.search.brave.com/x.jpg",
            "https://imgs.search.brave.com@evil.test/x.jpg",
            "https://evil.test\\@imgs.search.brave.com/x.jpg",
            "https://evil.test/https://imgs.search.brave.com/x.jpg",
            "https://evil.test/?u=https://imgs.search.brave.com/x.jpg",
            "https://imgs%2esearch%2ebrave%2ecom/x.jpg",
            // Look-alikes: a Cyrillic "е", ideographic and fullwidth full stops (which IDNA maps
            // to "."), and a punycode name.
            "https://imgs.search.bravе.com/x.jpg",
            "https://imgs.search.brave。com/x.jpg",
            "https://imgs．search.brave.com/x.jpg",
            "https://xn--imgs-search-brave-com.example/x.jpg",
            "https://imgs.search.brave.com:0443x/x.jpg",
            "https://imgs.search.brave.com /x.jpg",
            "//imgs.search.brave.com/x.jpg",
            "/x.jpg",
            "imgs.search.brave.com/x.jpg",
            "file:///data/data/watch.rist.assistant/shared_prefs/rist.cfg.xml",
            "content://media/external/images/1",
            "data:image/png;base64,AAAA",
            "javascript:alert(1)",
            "ftp://imgs.search.brave.com/x.jpg",
            "wss://imgs.search.brave.com/x.jpg",
            "https://upload.wikimedia.org/x.jpg",
            "https://127.0.0.1/x.jpg",
            "",
            "not a uri at all",
        )
        refused.forEach { assertFalse("allowed a refused url: $it", Attachments.isAllowedImageUrl(it)) }
    }

    @Test
    fun `a picture on another host is skipped silently and never becomes a request`() {
        foreign.enqueue(pngResponse())
        Attachments.dialForTest = null
        val out = resolve(
            bravePic(uri = foreign.url("/pic.png").toString()),
            bravePic(uri = "https://${foreign.hostName}:${foreign.port}/pic.png"),
        )
        out.forEach {
            assertNotNull(it.error)
            assertNull(it.bytes)
            assertTrue("a refused url must render as nothing, not as an error card", it.remote)
        }
        assertEquals(0, foreign.requestCount)
        assertEquals(0, brave.requestCount)
    }

    @Test
    fun `a url on anything but an image is never fetched, from Brave or anywhere else`() {
        val out = resolve(
            bravePic(kind = "text"),
            bravePic(kind = "data"),
            bravePic(kind = "video"),
            att(kind = "data", mime = "application/pdf", title = "lease.pdf", uri = foreign.url("/lease.pdf").toString()),
        )
        // A note with only a link is an error card, as an empty note always was.
        assertNotNull(out[0].error)
        assertFalse(out[0].remote)
        assertEquals("", out[0].text)
        // A file sent as a link is the plain file card: no bytes, no error, not a loaded picture.
        out.drop(1).forEach {
            assertEquals("data", it.kind)
            assertNull(it.error)
            assertNull(it.bytes)
            assertFalse(it.remote)
        }
        assertEquals("lease.pdf", out[3].title)
        assertEquals(0, brave.requestCount)
        assertEquals(0, foreign.requestCount)
    }

    @Test
    fun `no credential is attached anywhere, the backend's own host included`() {
        val src = java.io.File("src/main/java/watch/rist/assistant/Attachments.kt").readText()
        assertFalse(src.contains("Authorization"))
        assertFalse(src.contains("authToken"))
    }

    @Test
    fun `a Brave picture with no credit is not loaded, because it could not be shown`() {
        val out = resolve(bravePic(title = "  "))
        assertNotNull(out[0].error)
        assertTrue(out[0].remote)
        assertEquals(0, brave.requestCount)
    }

    @Test
    fun `a Brave picture loads, held as bytes in memory and marked as loaded from a url`() {
        brave.enqueue(pngResponse())
        val out = resolve(bravePic())
        assertNull(out[0].error)
        assertTrue(out[0].remote)
        assertTrue(realPng.contentEquals(out[0].bytes))
        assertEquals("Photo: theguardian.com", out[0].title)
        assertEquals("image-search", out[0].toolId)
        val req = brave.takeRequest(30, TimeUnit.SECONDS)!!
        assertEquals("/Zx9abc/rs:fit:500:0:0/g:ce/aHR0cHM6Ly9leGFtcGxl", req.path)
    }

    @Test
    fun `isUrlOnly marks exactly the attachments whose content would come from the uri`() {
        assertTrue(Attachments.isUrlOnly(bravePic()))
        assertFalse(Attachments.isUrlOnly(bravePic(kind = "data")))
        assertFalse(Attachments.isUrlOnly(bravePic(kind = "text")))
        assertFalse(Attachments.isUrlOnly(att(kind = "image", data = realPng, uri = braveUrl)))
        assertFalse(Attachments.isUrlOnly(att(kind = "text", text = "hello", uri = braveUrl)))
        assertFalse(Attachments.isUrlOnly(att(kind = "image", data = realPng)))
    }

    // ---- rule 2: a plain GET ---------------------------------------------------------------

    @Test
    fun `the load is a plain GET with no Referer, no cookie and no credential`() {
        brave.enqueue(pngResponse().addHeader("Set-Cookie", "tracker=1; Path=/"))
        brave.enqueue(pngResponse())

        assertNull(resolve(bravePic())[0].error)
        assertNull(resolve(bravePic())[0].error)

        listOf(brave.takeRequest(30, TimeUnit.SECONDS)!!, brave.takeRequest(30, TimeUnit.SECONDS)!!).forEach { r ->
            assertEquals("GET", r.method)
            assertNull("a Referer was sent", r.getHeader("Referer"))
            assertNull("a cookie was sent", r.getHeader("Cookie"))
            assertNull("a credential was sent", r.getHeader("Authorization"))
            assertNull(r.getHeader("Proxy-Authorization"))
            assertEquals("a GET carries no body", 0L, r.bodySize)
        }
    }

    @Test
    fun `a 401 challenge is not answered with a credential`() {
        brave.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=x"))
        brave.enqueue(pngResponse())
        val out = resolve(bravePic())
        assertNotNull(out[0].error)
        assertTrue(out[0].remote)
        assertEquals("no second attempt with credentials", 1, brave.requestCount)
    }

    @Test
    fun `a redirect that stays on the host is followed, with no Referer on the next hop`() {
        brave.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://imgs.search.brave.com/next.png"))
        brave.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/last.png"))
        brave.enqueue(pngResponse())

        val out = resolve(bravePic())
        assertNull(out[0].error)
        assertTrue(realPng.contentEquals(out[0].bytes))

        brave.takeRequest(30, TimeUnit.SECONDS)!!
        val second = brave.takeRequest(30, TimeUnit.SECONDS)!!
        val third = brave.takeRequest(30, TimeUnit.SECONDS)!!
        assertEquals("/next.png", second.path)
        assertEquals("/last.png", third.path)
        listOf(second, third).forEach { assertNull(it.getHeader("Referer")) }
    }

    @Test
    fun `a redirect off the host is refused, not followed, and the picture shows nothing`() {
        val offHost = listOf(
            foreign.url("/elsewhere.png").toString(),
            "https://evil.test/x.png",
            "http://imgs.search.brave.com/x.png",
            "https://imgs.search.brave.com:8443/x.png",
            "https://cdn.imgs.search.brave.com/x.png",
            "https://u@imgs.search.brave.com/x.png",
            "file:///etc/hosts",
            "//evil.test/x.png",
        )
        offHost.forEach { loc ->
            brave.enqueue(MockResponse().setResponseCode(302).setHeader("Location", loc))
            val out = resolve(bravePic())
            assertNotNull("followed a redirect to $loc", out[0].error)
            assertNull(out[0].bytes)
            assertTrue(out[0].remote)
        }
        assertEquals(0, foreign.requestCount)
        assertEquals("only the first hop of each was made", offHost.size, brave.requestCount)
    }

    @Test(timeout = 30_000)
    fun `a redirect loop on the host is abandoned after three hops`() {
        brave.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(302).setHeader("Location", "/round-again")
        }
        val out = resolve(bravePic())
        assertNotNull(out[0].error)
        assertEquals(4, brave.requestCount)
    }

    @Test
    fun `the byte cap is eight mebibytes and the time cap is ten seconds`() {
        assertEquals(8 * 1024 * 1024, Attachments.MAX_BYTES_PER_ATTACHMENT)
        assertEquals(10_000L, Attachments.REMOTE_LOAD_TIMEOUT_MS)
        assertEquals(Attachments.REMOTE_LOAD_TIMEOUT_MS, Attachments.loadTimeoutMs)
    }

    @Test
    fun `a body over eight mebibytes is refused with no Content-Length to warn us`() {
        val body = Buffer().apply { write(realPng); write(ByteArray(Attachments.MAX_BYTES_PER_ATTACHMENT)) }
        brave.enqueue(MockResponse().setChunkedBody(body, 64 * 1024))
        val out = resolve(bravePic())
        assertNotNull(out[0].error)
        assertNull(out[0].bytes)
        assertTrue(out[0].remote)
    }

    @Test
    fun `a Content-Length over eight mebibytes is refused before the body is read`() {
        brave.enqueue(MockResponse().setHeader("Content-Length", "104857600"))
        val out = resolve(bravePic())
        assertNotNull(out[0].error)
        assertTrue(out[0].error!!.startsWith("too large"))
        assertTrue(out[0].remote)
        assertEquals(1, brave.requestCount)
    }

    @Test(timeout = 30_000)
    fun `a server that never answers fails inside the deadline`() {
        Attachments.loadTimeoutMs = 1_000
        brave.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val startedAt = System.nanoTime()
        val out = resolve(bravePic())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertNotNull(out[0].error)
        assertTrue(out[0].remote)
        assertTrue("took ${elapsedMs}ms against a 1 s deadline", elapsedMs < 5_000)
    }

    @Test(timeout = 30_000)
    fun `a body that dribbles past the deadline fails, the deadline covers the whole load`() {
        Attachments.loadTimeoutMs = 1_000
        val body = Buffer().apply { write(realPng); write(ByteArray(64 * 1024)) }
        // Each chunk arrives inside any per-read timeout; only a whole-load deadline stops it.
        brave.enqueue(pngResponse().setBody(body).throttleBody(1024, 200, TimeUnit.MILLISECONDS))
        val startedAt = System.nanoTime()
        val out = resolve(bravePic())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertNotNull(out[0].error)
        assertTrue("took ${elapsedMs}ms against a 1 s deadline", elapsedMs < 5_000)
    }

    @Test(timeout = 30_000)
    fun `redirect hops share one deadline rather than each getting a fresh one`() {
        fun slowChain() {
            brave.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/final.png" -> pngResponse()
                    "/mid" -> MockResponse().setResponseCode(302).setHeader("Location", "/final.png")
                    else -> MockResponse().setResponseCode(302).setHeader("Location", "/mid")
                }.setHeadersDelay(400, TimeUnit.MILLISECONDS)
            }
        }
        // Control: the same three 400 ms hops load fine with time to spare.
        Attachments.loadTimeoutMs = 5_000
        slowChain()
        assertNull("precondition: the chain loads when time allows", resolve(bravePic())[0].error)

        // 1.2 s in all against a 1 s budget, although every hop alone is well under it.
        Attachments.loadTimeoutMs = 1_000
        val out = resolve(bravePic())
        assertNotNull(out[0].error)
        assertTrue(out[0].remote)
    }

    // ---- what counts as a picture ---------------------------------------------------------

    @Test
    fun `a body that is not jpeg, png, webp or gif is a failed load`() {
        brave.enqueue(MockResponse().setBody("<html>not a picture</html>"))
        assertNotNull(resolve(bravePic())[0].error)

        // BMP is fine inline but not from a url: the rule names four formats.
        val bmp = "BM".toByteArray() + ByteArray(64)
        brave.enqueue(MockResponse().setBody(Buffer().write(bmp)))
        val out = resolve(bravePic())
        assertNotNull(out[0].error)
        assertTrue(out[0].remote)
        assertNull(out[0].bytes)
    }

    @Test
    fun `looksLikeWebImage accepts jpeg, png, webp and gif only`() {
        assertTrue(Attachments.looksLikeWebImage(realPng))
        assertTrue(Attachments.looksLikeWebImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(16)))
        assertTrue(Attachments.looksLikeWebImage("GIF89a".toByteArray() + ByteArray(16)))
        assertTrue(Attachments.looksLikeWebImage("RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(8)))
        assertFalse(Attachments.looksLikeWebImage("BM".toByteArray() + ByteArray(16)))
        assertFalse(Attachments.looksLikeWebImage(ByteArray(4) + "ftypheic".toByteArray() + ByteArray(8)))
        assertFalse(Attachments.looksLikeWebImage(ByteArray(64)))
    }

    @Test
    fun `a 404 or 500 is a failed load and its body never becomes the picture`() {
        brave.enqueue(MockResponse().setResponseCode(404).setBody(Buffer().write(realPng)))
        brave.enqueue(MockResponse().setResponseCode(500).setBody(Buffer().write(realPng)))
        brave.enqueue(MockResponse().setResponseCode(204))
        repeat(3) {
            val out = resolve(bravePic())
            assertNotNull(out[0].error)
            assertNull(out[0].bytes)
            assertTrue(out[0].remote)
        }
    }

    @Test
    fun `a failed load is not retried`() {
        brave.enqueue(MockResponse().setResponseCode(503))
        brave.enqueue(pngResponse())
        assertNotNull(resolve(bravePic())[0].error)
        assertEquals(1, brave.requestCount)
    }

    @Test
    fun `nothing is cached, so a picture the server says to keep is fetched again`() {
        brave.enqueue(pngResponse().setHeader("Cache-Control", "public, max-age=86400"))
        brave.enqueue(pngResponse().setHeader("Cache-Control", "public, max-age=86400"))
        assertNull(resolve(bravePic())[0].error)
        assertNull(resolve(bravePic())[0].error)
        assertEquals(2, brave.requestCount)
    }

    @Test
    fun `the log never carries the picture's path`() {
        ShadowLog.clear()
        brave.enqueue(MockResponse().setResponseCode(404))
        resolve(bravePic())
        val lines = ShadowLog.getLogsForTag("RistAttach").map { it.msg }
        assertTrue("logged nothing", lines.isNotEmpty())
        assertTrue("the status code is the useful half: $lines", lines.any { it.contains("404") })
        assertTrue("the url leaked into the log: $lines", lines.none { it.contains("Zx9abc") || it.contains("aHR0c") })
    }

    @Test
    fun `an inline Commons picture is unchanged, not marked as loaded, and makes no request`() {
        val out = resolve(att(kind = "image", mime = "image/png", title = "Photo by Ana, CC BY 4.0", data = realPng, toolId = "image-search"))
        assertNull(out[0].error)
        assertFalse(out[0].remote)
        assertTrue(realPng.contentEquals(out[0].bytes))
        assertEquals(0, brave.requestCount)
    }

    // ---- unchanged: inline bytes and notes ----------------------------------------------

    @Test
    fun `empty list in, empty list out`() {
        assertEquals(emptyList<RistAttachment>(), Attachments.resolve(emptyList()))
        assertEquals(0, brave.requestCount)
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
        assertEquals(0, brave.requestCount)
        assertEquals(0, foreign.requestCount)
    }

    @Test
    fun `inline wins over uri when the backend sends both`() {
        val blob = ByteArray(32) { 7 }
        val out = resolve(att(kind = "data", data = blob, uri = braveUrl))
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
    fun `no more than eight attachments are rendered, and they are the first eight`() {
        val many = (1..12).map { att(kind = "text", title = "note $it", text = "body $it") }
        val out = Attachments.resolve(many)
        assertEquals(8, out.size)
        assertEquals("note 1", out.first().title)
        assertEquals("note 8", out.last().title)
        assertEquals(
            listOf("note 1", "note 2", "note 3", "note 4", "note 5", "note 6", "note 7", "note 8"),
            out.map { it.title },
        )
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
