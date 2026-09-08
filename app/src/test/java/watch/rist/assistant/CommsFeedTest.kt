package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class CommsFeedTest {

    private val tz: TimeZone = TimeZone.getTimeZone("America/Los_Angeles")
    private val locale = Locale.US

    private val now: Long = Calendar.getInstance(tz).apply {
        clear(); set(2026, Calendar.AUGUST, 27, 14, 0, 0)
    }.timeInMillis

    private fun item(
        id: String,
        atMs: Long,
        unread: Boolean = true,
        kind: FeedKind = FeedKind.TEXT,
        name: String? = null,
        body: String = "hello",
        number: String = "2065550134",
    ) = FeedItem(id, kind, number, name, if (kind == FeedKind.MISSED_CALL) "" else body, atMs, unread)

    private fun minutes(n: Long) = n * 60_000L
    private fun hours(n: Long) = n * 3_600_000L

    @Test
    fun assemble_ordersNewestFirst_regardlessOfUnread() {
        val out = CommsFeed.assemble(
            listOf(
                item("a", now - minutes(30), unread = false),
                item("b", now - minutes(90), unread = true),
                item("c", now - minutes(5), unread = false),
            ),
            now,
        )
        assertEquals(listOf("c", "a", "b"), out.map { it.id })
    }

    @Test
    fun assemble_dedupesById_keepingTheNewestCopy() {
        val out = CommsFeed.assemble(
            listOf(
                item("dup", now - hours(2)),
                item("dup", now - minutes(1)),
                item("other", now - minutes(10)),
            ),
            now,
        )
        assertEquals(2, out.size)
        assertEquals(now - minutes(1), out.first { it.id == "dup" }.atMs)
    }

    @Test
    fun assemble_dropsSeenItemsPastTheAgeWindow() {
        val out = CommsFeed.assemble(
            listOf(
                item("fresh", now - hours(1), unread = false),
                item("ancient", now - hours(72), unread = false),
            ),
            now,
        )
        assertEquals(listOf("fresh"), out.map { it.id })
    }

    @Test
    fun theAgeWindowIsOneDay_becauseItIsAlsoTheRetentionRule() {
        assertEquals(24L * 60L * 60L * 1000L, CommsFeed.MAX_AGE_MS)

        val out = CommsFeed.assemble(listOf(item("seen", now - hours(30), unread = false)), now)
        assertTrue(out.isEmpty())
    }

    @Test
    fun assemble_neverAgesOutAnUnreadItem() {
        val out = CommsFeed.assemble(
            listOf(item("old-unread", now - hours(24 * 7), unread = true)),
            now,
        )
        assertEquals(listOf("old-unread"), out.map { it.id })
    }

    @Test
    fun assemble_trimsSeenItemsToTheCap_butKeepsEveryUnreadOne() {
        val seen = (1..20).map { item("seen$it", now - minutes(it.toLong()), unread = false) }
        val unread = (1..9).map { item("new$it", now - hours(it.toLong()), unread = true) }
        val out = CommsFeed.assemble(seen + unread, now)

        assertEquals(9, out.count { it.unread })
        assertEquals(CommsFeed.MAX_READ, out.count { !it.unread })
        assertEquals(
            (1..CommsFeed.MAX_READ).map { "seen$it" },
            out.filter { !it.unread }.map { it.id },
        )
    }

    @Test
    fun assemble_hardCapPrefersUnread_thenStillRendersChronologically() {
        val unread = (1..5).map { item("new$it", now - hours(it.toLong()), unread = true) }
        val seen = (1..5).map { item("seen$it", now - minutes(it.toLong()), unread = false) }
        val out = CommsFeed.assemble(seen + unread, now, hardCap = 5)

        assertEquals(5, out.size)
        assertTrue(out.all { it.unread })
        assertEquals(out.map { it.atMs }.sortedDescending(), out.map { it.atMs })
    }

    @Test
    fun assemble_emptyInputIsEmpty_andDoesNotThrowOnZeroCaps() {
        assertTrue(CommsFeed.assemble(emptyList(), now).isEmpty())
        assertTrue(CommsFeed.assemble(listOf(item("a", now)), now, hardCap = 0).isEmpty())
        assertEquals(1, CommsFeed.assemble(listOf(item("a", now)), now, maxRead = 0).size)
    }

    @Test
    fun unreadCount_countsCandidates_notWhatFitOnScreen() {
        val many = (1..30).map { item("new$it", now - minutes(it.toLong()), unread = true) }
        assertEquals(30, CommsFeed.unreadCount(many))
        assertTrue(CommsFeed.assemble(many, now).size <= CommsFeed.HARD_CAP)
    }

    @Test
    fun waitingCount_foldsInAVoicemail_soTheBadgeAndTheFeedCannotDisagree() {
        val items = listOf(
            item("a", now - minutes(3), unread = true),
            item("b", now - minutes(9), unread = false),
        )
        assertEquals(1, CommsFeed.waitingCount(items, voicemailWaiting = false))
        assertEquals(2, CommsFeed.waitingCount(items, voicemailWaiting = true))
        assertEquals(1, CommsFeed.waitingCount(emptyList(), voicemailWaiting = true))
        assertEquals(0, CommsFeed.waitingCount(emptyList(), voicemailWaiting = false))
    }

    @Test
    fun waitingCount_countsAVoicemailOnce_howeverManyTimesItIsAsked() {
        val items = listOf(item("a", now, unread = true))
        assertEquals(
            CommsFeed.waitingCount(items, true),
            CommsFeed.waitingCount(items, true),
        )
        assertEquals(2, CommsFeed.waitingCount(items, true))
    }

    @Test
    fun canCallBack_onlyForAMissedCallWithANumberToDial() {
        assertTrue(CommsFeed.canCallBack(item("a", now, kind = FeedKind.MISSED_CALL)))
        assertFalse(CommsFeed.canCallBack(item("a", now, kind = FeedKind.TEXT)))
        assertFalse(
            CommsFeed.canCallBack(item("a", now, kind = FeedKind.MISSED_CALL, number = ""))
        )
    }

    @Test
    fun callBackLabel_namesWhoItWillRing() {
        val known = item("a", now, kind = FeedKind.MISSED_CALL, name = "Jane Miller")
        assertEquals("CALL BACK JANE MILLER", CommsFeed.callBackLabel(known) { CallerId.pretty(it) })
        val unknown = item("a", now, kind = FeedKind.MISSED_CALL, name = null)
        assertEquals(
            "CALL BACK (206) 555-0134",
            CommsFeed.callBackLabel(unknown) { CallerId.pretty(it) },
        )
    }

    @Test
    fun voicemailLines_sayWhatItIsAndWhatWillHappen_inWords() {
        assertEquals("VOICEMAIL", CommsFeed.voicemailKindLine())
        assertEquals("You have a voicemail", CommsFeed.voicemailSenderLine())
        assertFalse(CommsFeed.voicemailSenderLine().any { it.isDigit() })
    }

    @Test
    fun voicemailActionLine_promisesToListenOnlyWhenRistCanActuallySignIn() {
        assertTrue(CommsFeed.voicemailActionLine(oneTapReady = true).contains("listen", true))
        assertFalse(CommsFeed.voicemailActionLine(oneTapReady = false).contains("listen", true))
        assertTrue(CommsFeed.voicemailActionLine(oneTapReady = false).contains("mailbox", true))
    }

    @Test
    fun voicemailCallLabel_matchesTheActionItsRowDescribed() {
        assertEquals("LISTEN NOW", CommsFeed.voicemailCallLabel(oneTapReady = true))
        assertEquals("CALL MY MAILBOX", CommsFeed.voicemailCallLabel(oneTapReady = false))
    }

    @Test
    fun senderLine_prefersAContactName_andFallsBackToTheFormattedNumber() {
        assertEquals(
            "Jane Miller",
            CommsFeed.senderLine(item("a", now, name = "Jane Miller")) { CallerId.pretty(it) },
        )
        assertEquals(
            "(206) 555-0134",
            CommsFeed.senderLine(item("a", now, name = null)) { CallerId.pretty(it) },
        )
    }

    @Test
    fun senderLine_saysSoWhenTheCarrierGaveUsNoNumberAtAll() {
        val withheld = item("a", now, name = null, number = "")
        assertEquals("Number withheld", CommsFeed.senderLine(withheld) { CallerId.pretty(it) })
        assertTrue(CommsFeed.isUnknownSender(withheld))
    }

    @Test
    fun kindLine_namesTheKindInWords_andFlagsAnUnknownSender() {
        assertEquals("TEXT MESSAGE", CommsFeed.kindLine(item("a", now, name = "Jane Miller")))
        assertEquals("TEXT MESSAGE · UNKNOWN NUMBER", CommsFeed.kindLine(item("a", now)))
        assertEquals(
            "MISSED CALL",
            CommsFeed.kindLine(item("a", now, kind = FeedKind.MISSED_CALL, name = "Jane Miller")),
        )
        assertFalse(CommsFeed.isUnknownSender(item("a", now, name = "Jane Miller")))
        assertTrue(CommsFeed.isUnknownSender(item("a", now, name = "   ")))
    }

    @Test
    fun preview_collapsesNewlines_soOneTextCannotTakeOverTheFeed() {
        assertEquals("Line one Line two", CommsFeed.preview("Line one\n\n  Line two  "))
    }

    @Test
    fun preview_truncatesOnAWordBoundary_butNeverToNothing() {
        val words = CommsFeed.preview("alpha bravo charlie delta echo", maxChars = 14)
        assertEquals("alpha bravo…", words)
        val token = CommsFeed.preview("aaaaaaaaaaaaaaaaaaaaaaa", maxChars = 10)
        assertEquals("aaaaaaaaaa…", token)
    }

    @Test
    fun preview_leavesAShortBodyExactlyAsItIs() {
        assertEquals("Your code is 448122", CommsFeed.preview("Your code is 448122"))
    }

    @Test
    fun relativeTime_spellsOutRecentGapsInFullWords() {
        assertEquals("Just now", CommsFeed.relativeTime(now - 20_000L, now, tz, locale))
        assertEquals("1 minute ago", CommsFeed.relativeTime(now - minutes(1), now, tz, locale))
        assertEquals("12 minutes ago", CommsFeed.relativeTime(now - minutes(12), now, tz, locale))
        assertEquals("1 hour ago", CommsFeed.relativeTime(now - hours(1), now, tz, locale))
        assertEquals("3 hours ago", CommsFeed.relativeTime(now - hours(3), now, tz, locale))
    }

    @Test
    fun relativeTime_switchesToClockTimeOnceHoursStopBeingHowAnyoneSaysIt() {
        assertEquals("Today at 2:00 AM", CommsFeed.relativeTime(now - hours(12), now, tz, locale))
        assertEquals("Yesterday at 2:00 PM", CommsFeed.relativeTime(now - hours(24), now, tz, locale))
        assertEquals("Monday at 2:00 PM", CommsFeed.relativeTime(now - hours(24 * 3), now, tz, locale))
        assertEquals("20 August at 2:00 PM", CommsFeed.relativeTime(now - hours(24 * 7), now, tz, locale))
    }

    @Test
    fun relativeTime_usesCalendarDays_soLateLastNightIsYesterdayNotToday() {
        val lastNight = Calendar.getInstance(tz).apply {
            clear(); set(2026, Calendar.AUGUST, 26, 23, 30, 0)
        }.timeInMillis
        assertEquals("Yesterday at 11:30 PM", CommsFeed.relativeTime(lastNight, now, tz, locale))
    }

    @Test
    fun relativeTime_refusesToDoArithmeticOnAnArrivalFromTheFuture() {
        assertEquals("Just now", CommsFeed.relativeTime(now + minutes(5), now, tz, locale))
    }

    @Test
    fun seenIds_appendNewOnes_andRefreshingOneMovesItToTheNewestEnd() {
        assertEquals(
            listOf("a", "b", "c"),
            CommsFeed.seenIdsAfterAdding(listOf("a", "b"), listOf("c")),
        )
        assertEquals(
            listOf("b", "c", "a"),
            CommsFeed.seenIdsAfterAdding(listOf("a", "b"), listOf("c", "a")),
        )
    }

    @Test
    fun seenIds_evictOldestAcknowledgementsFirstAtTheCap() {
        val out = CommsFeed.seenIdsAfterAdding(listOf("a", "b", "c"), listOf("d"), cap = 2)
        assertEquals(listOf("c", "d"), out)
    }

    @Test
    fun seenIds_areIdempotent_soRepaintingDoesNotChurnTheSet() {
        val once = CommsFeed.seenIdsAfterAdding(listOf("a"), listOf("b"))
        assertEquals(once, CommsFeed.seenIdsAfterAdding(once, listOf("b")))
    }
}
