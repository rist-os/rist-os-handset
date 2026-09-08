package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class CommsFeedWaitingTest {

    private val now = 1_760_000_000_000L

    private fun item(
        id: String,
        kind: FeedKind,
        unread: Boolean = true,
        noticeKind: String = "",
    ) = FeedItem(
        id = id,
        kind = kind,
        number = "+12065550100",
        contactName = null,
        body = if (kind == FeedKind.TEXT) "hello" else "",
        atMs = now,
        unread = unread,
        noticeKind = noticeKind,
    )

    @Test
    fun theSplitAlwaysSumsToTheBadge() {
        for (sms in listOf(0, 1, 3)) {
            for (calls in listOf(0, 2)) {
                for (notices in listOf(0, 1)) {
                    for (read in listOf(0, 4)) {
                        for (vm in listOf(true, false)) {
                            for (mail in listOf(0, 5)) {
                                val items = buildList {
                                    repeat(sms) { add(item("s$it", FeedKind.TEXT)) }
                                    repeat(calls) { add(item("c$it", FeedKind.MISSED_CALL)) }
                                    repeat(notices) { add(item("n$it", FeedKind.NOTIFICATION)) }
                                    repeat(read) { add(item("r$it", FeedKind.TEXT, unread = false)) }
                                }
                                assertEquals(
                                    "the drawer boxes must sum to the gear badge " +
                                        "(sms=$sms calls=$calls notices=$notices read=$read vm=$vm mail=$mail)",
                                    CommsFeed.waitingCount(items, vm, mail),
                                    CommsFeed.waiting(items, vm, mail).total,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun eachKindLandsInItsOwnBoxAndNoOther() {
        val items = listOf(
            item("s1", FeedKind.TEXT),
            item("s2", FeedKind.TEXT),
            item("c1", FeedKind.MISSED_CALL),
            item("n1", FeedKind.NOTIFICATION),
        )
        val w = CommsFeed.waiting(items, voicemailWaiting = false, mailUnread = 0)
        assertEquals(2, w.sms)
        assertEquals(1, w.calls)
        assertEquals(1, w.notices)
        assertEquals(0, w.voicemail)
        assertEquals(0, w.mail)
        assertEquals(4, w.total)
    }

    @Test
    fun aReadRowIsInNoBoxAtAll() {
        val items = listOf(
            item("s1", FeedKind.TEXT, unread = false),
            item("c1", FeedKind.MISSED_CALL, unread = false),
            item("n1", FeedKind.NOTIFICATION, unread = false),
        )
        val w = CommsFeed.waiting(items, voicemailWaiting = false, mailUnread = 0)
        assertEquals(0, w.sms)
        assertEquals(0, w.calls)
        assertEquals(0, w.notices)
        assertEquals(0, w.total)
    }

    @Test
    fun aWaitingVoicemailIsOneHoweverMuchTheRadioKnows() {
        val w = CommsFeed.waiting(emptyList(), voicemailWaiting = true, mailUnread = 0)
        assertEquals(1, w.voicemail)
        assertEquals(1, w.total)
        assertEquals(0, CommsFeed.waiting(emptyList(), voicemailWaiting = false, mailUnread = 0).voicemail)
    }

    @Test
    fun mailGoesToTheMailBoxAndMatchesTheUnbadgedRule() {
        val items = listOf(item("n1", FeedKind.NOTIFICATION, noticeKind = "mail"))
        val w = CommsFeed.waiting(items, voicemailWaiting = false, mailUnread = 3)
        assertEquals(CommsFeed.unbadgedMail(items, 3), w.mail)
        assertEquals(CommsFeed.waitingCount(items, false, 3), w.total)
    }
}
