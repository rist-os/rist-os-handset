package watch.rist.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsAcquisitionBoundaryTest {

    @Test
    fun theManifest_registersNoSmsDeliveryReceiver_andHoldsNoReceivePermission() {
        val xml = manifestText()

        assertFalse(
            "AndroidManifest.xml registers for the SMS delivery broadcast; Rist reads the message " +
                "store on request only (see SmsInbox).",
            xml.contains("SMS_RECEIVED") || xml.contains("SmsReceiver")
        )

        assertFalse(
            "AndroidManifest.xml declares RECEIVE_SMS again. Nothing receives that broadcast any " +
                "more, so holding the permission buys no behaviour at all -- it only makes a " +
                "re-added receiver work silently. If some new feature genuinely needs it, that " +
                "feature is an acquisition and needs legal review first.",
            xml.contains("android.permission.RECEIVE_SMS")
        )

        assertTrue(
            "AndroidManifest.xml no longer declares READ_SMS, so the ask-time read cannot work at " +
                "all and every question about messages will fail out loud.",
            xml.contains("android.permission.READ_SMS")
        )
    }

    @Test
    fun noCompiledClass_touchesTheDeliveryBroadcastOrWatchesTheStore() {
        val classes = appClasses()
        assertTrue(
            "no compiled classes found -- this scan proves nothing, which is its own worst failure",
            classes.size >= 20
        )

        val forbidden = listOf(
            "android.provider.Telephony.SMS_RECEIVED",
            "SMS_RECEIVED_ACTION",
            "android.provider.Telephony.WAP_PUSH_RECEIVED",
            "android.intent.action.DATA_SMS_RECEIVED",
            "getMessagesFromIntent",
            "android/telephony/SmsMessage",
            "android/provider/Telephony\$Sms\$Intents",
            "android/database/ContentObserver",
            "registerContentObserver",
        )

        for ((name, bytes) in classes) {
            // ISO-8859-1 is byte-preserving; UTF-8 would mangle constant-pool bytes.
            val text = String(bytes, Charsets.ISO_8859_1)
            for (marker in forbidden) {
                assertFalse(
                    "$name references '$marker'. Rist must not learn about a text as it arrives. " +
                        "It reads the platform's message store at the moment the user asks and " +
                        "keeps nothing -- see SmsInbox.read.",
                    text.contains(marker)
                )
            }
        }
    }

    @Test
    fun theBodyRead_hasOneCallSite_andItIsBehindTheOptIn() {
        val sources = sourceFiles()
        assertTrue("no Kotlin sources found -- this scan proves nothing", sources.size >= 20)

        val callers = sources.filter { it.readText().contains("SmsInbox.read(") }
        assertEquals(
            "SmsInbox.read() must have exactly one call site (Uploader.post, behind " +
                "includeInboundSms). Found: ${callers.map { it.name }}. Every extra caller is a " +
                "read of somebody else's message bodies that no user asked for.",
            listOf("Uploader.kt"), callers.map { it.name }.sorted()
        )

        val uploader = sources.first { it.name == "Uploader.kt" }.readText()
        assertTrue(
            "the guard on the body read is gone. It must read " +
                "`if (includeInboundSms) SmsInbox.read(ctx)` so an ordinary turn does not open the " +
                "message store at all -- nothing should have to trust a filter downstream of the " +
                "read.",
            uploader.replace(Regex("\\s+"), " ").contains("if (includeInboundSms) SmsInbox.read(ctx)")
        )

        val feed = sources.first { it.name == "CommsFeedView.kt" }.readText()
        assertFalse(
            "CommsFeedView calls the body read. The feed repaints on the minute tick, so a body " +
                "read there is a continuous acquisition with no request behind it -- the exact " +
                "posture deleting the broadcast receiver was meant to escape. It uses " +
                "SmsInbox.arrivals(), whose projection has no body column.",
            feed.contains("SmsInbox.read(")
        )
        val inbox = sources.first { it.name == "SmsInbox.kt" }.readText()
        assertFalse(
            "SmsInbox.arrivals() now asks for the message body. That projection is the privacy " +
                "boundary for the home screen: one extra column puts a continuous read of other " +
                "people's message contents back on the minute tick.",
            inbox.substringAfter("fun arrivals(").substringBefore("fun purgeLegacyStore")
                .contains("Telephony.Sms.BODY")
        )
    }

    @Test
    fun theDeviceOwnerGrant_asksForReadSms_andNotForReceiveSms() {
        val kiosk = sourceFiles().first { it.name == "KioskManager.kt" }.readText()
        assertTrue(
            "KioskManager no longer self-grants READ_SMS, so on a flashed handset the ask-time " +
                "read is refused and every question about messages fails.",
            kiosk.contains("Manifest.permission.READ_SMS")
        )
        assertFalse(
            "KioskManager self-grants RECEIVE_SMS again, with nothing left to receive.",
            kiosk.contains("Manifest.permission.RECEIVE_SMS")
        )
    }

    private fun row(n: Int, ageMs: Long, now: Long) = SmsInbox.StoreRow(
        from = "+1555555000$n", body = "your verification code is 00$n", sentAtMs = now - ageMs
    )

    @Test
    fun theAskTimeRead_returnsWhatTheStoreHolds_newestFirst() {
        val now = 1_700_000_000_000L
        val rows = listOf(row(1, 60_000L, now), row(2, 5_000L, now), row(3, 3_600_000L, now))
        val out = SmsInbox.fromStore(rows, now)

        assertEquals(3, out.size)
        assertEquals("newest first, like everything else that reports arrivals",
            listOf(now - 5_000L, now - 60_000L, now - 3_600_000L), out.map { it.sentAtMs })
        assertEquals(rows[1].body, out[0].body)
        assertEquals(rows[1].from, out[0].from)
        assertEquals(
            SmsInbox.stableId(rows[1].from, rows[1].sentAtMs, rows[1].body), out[0].id
        )
        assertEquals(out.map { it.id }, SmsInbox.fromStore(rows, now).map { it.id })
    }

    @Test
    fun theAskTimeRead_appliesTheWindowToTheAnswer_notJustToTheQuery() {
        val now = 1_700_000_000_000L
        val day = CommsFeed.MAX_AGE_MS
        val out = SmsInbox.fromStore(
            listOf(row(1, day - 1_000L, now), row(2, day + 1_000L, now), row(3, 400L * day, now)),
            now
        )
        assertEquals("only the message inside the window may leave", 1, out.size)
        assertEquals(now - (day - 1_000L), out[0].sentAtMs)
    }

    @Test
    fun theAskTimeRead_isCapped_andDropsBlankSenders() {
        val now = 1_700_000_000_000L
        val many = (1..250).map {
            SmsInbox.StoreRow("+1555000$it", "body $it", now - it * 1_000L)
        } + SmsInbox.StoreRow("", "a message from nobody", now - 500L)
        val out = SmsInbox.fromStore(many, now)
        assertEquals(100, out.size)
        assertTrue("the cap must keep the NEWEST, not whatever order the cursor came back in",
            out.first().sentAtMs > out.last().sentAtMs)
        assertTrue(out.none { it.from.isBlank() })
    }

    @Test
    fun aRefusedPermission_producesALoudDiagnosableFailure_neverAnEmptyList() {
        val why = "this phone has not given Rist permission to read your messages"
        val said = Uploader.smsUnreadableFailure(why)

        assertTrue("the reason must survive into what the user is told: $said", said.contains(why))
        assertTrue("it must say Rist could not read, not that there is nothing to read: $said",
            said.contains("cannot read your messages"))
        for (lie in listOf("no messages", "no texts", "nothing", "empty")) {
            assertFalse(
                "the refusal reads as an answer ('$lie'): $said. An empty inbox and a refused " +
                    "permission are the same empty list and mean opposite things; this sentence is " +
                    "the only thing that keeps them apart on the handset.",
                said.lowercase().contains(lie)
            )
        }

        assertTrue(Uploader.smsUnreadableFailure("").length > 30)
        assertTrue(Uploader.smsUnreadableFailure("").contains("cannot read your messages"))
    }

    @Test
    fun theReadResult_cannotBeMistakenForAnEmptyInbox() {
        val empty: SmsRead = SmsRead.Held(emptyList())
        val refused: SmsRead = SmsRead.Unreadable("nope")
        assertTrue(empty is SmsRead.Held && empty.messages.isEmpty())
        assertTrue(refused is SmsRead.Unreadable)
        assertFalse("a refusal must never be reachable as an empty success", refused is SmsRead.Held)
    }

    @Test
    fun theOptInGate_stillRefuses_evenWhenTheStoreIsFull() {
        val now = 1_700_000_000_000L
        val held = SmsInbox.fromStore((1..5).map { row(it, it * 1_000L, now) }, now)
        assertEquals(5, held.size)

        assertTrue(
            "the ask-time read changed WHERE the messages come from and must not have changed WHO " +
                "may have them",
            Uploader.smsToCarry(optedIn = false, alreadyOnRequest = 0, pending = held).isEmpty()
        )
        assertEquals(5, Uploader.smsToCarry(true, 0, held).size)
        assertTrue(Uploader.smsToCarry(optedIn = true, alreadyOnRequest = 1, pending = held).isEmpty())
    }

    private fun manifestText(): String {
        val f = repoFile("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")
        assertNotNull("cannot find AndroidManifest.xml -- this test would pass on nothing", f)
        val stripped = f!!.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(
            "stripping comments removed the whole manifest -- this test would pass on nothing",
            stripped.contains("<application")
        )
        return stripped
    }

    private fun sourceFiles(): List<File> {
        val dir = repoFile(
            "app/src/main/java/watch/rist/assistant", "src/main/java/watch/rist/assistant"
        )
        assertNotNull("cannot find the Kotlin sources -- this test would pass on nothing", dir)
        return dir!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun repoFile(vararg candidates: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val here = dir ?: return null
            for (c in candidates) {
                val f = File(here, c)
                if (f.exists()) return f
            }
            dir = here.parentFile
        }
        return null
    }

    private fun appClasses(): List<Pair<String, ByteArray>> {
        val anchor = SmsInbox::class.java.getResource("SmsInbox.class")
        assertNotNull("cannot locate the compiled classes to scan", anchor)
        val url = anchor!!

        if (url.protocol == "jar") {
            val jarPath = java.net.URLDecoder.decode(
                url.path.substringAfter("file:").substringBefore("!"), "UTF-8"
            )
            return java.util.jar.JarFile(jarPath).use { jar ->
                jar.entries().toList()
                    .filter { it.name.startsWith("watch/rist/assistant/") && it.name.endsWith(".class") }
                    .map { it.name to jar.getInputStream(it).use { s -> s.readBytes() } }
            }
        }
        val dir = File(url.toURI()).parentFile ?: return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.name to it.readBytes() }
            .toList()
    }
}
