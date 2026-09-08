package watch.rist.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationQueueTest {

    private class FakeDisk(var json: String = "", val writable: Boolean = true) {
        var writes = 0
        fun load(): List<Notice> = NotificationQueue.decode(json)
        fun save(list: List<Notice>) {
            if (!writable) return
            writes++
            json = NotificationQueue.encode(NotificationQueue.trim(list))
        }

        fun store(incoming: List<Notice>) = save(NotificationQueue.upsert(load(), incoming))

        fun pendingAcks(): List<String> = NotificationQueue.pendingAcks(load())

        fun markAcked(ids: Collection<String>) = save(NotificationQueue.markAcked(load(), ids))

        fun reboot() = FakeDisk(json, writable)
    }

    private val T0 = 1_800_000_000_000L
    private fun secs(ms: Long) = ms / 1000L

    private fun notice(
        id: String,
        title: String = "New mail from Sarah Chen.",
        kind: String = "mail",
        urgency: String = "active",
        atMs: Long = T0,
    ) = Notice(
        id = id, kind = kind, title = title, urgency = urgency,
        createdAtEpochS = secs(atMs), receivedAtMs = atMs, acked = false,
    )

    @Test
    fun `the same id arriving twice renders once`() {
        val disk = FakeDisk()
        disk.store(listOf(notice("n-1")))
        disk.store(listOf(notice("n-1")))

        val held = disk.load()
        assertEquals("one entry per id, never two", 1, held.size)

        val rows = NotificationQueue.toFeedItems(
            NotificationQueue.renderable(held, T0), emptySet()
        )
        assertEquals("one row on the feed", 1, rows.size)
        assertEquals(
            "and one row after the feed's own assembly",
            1,
            CommsFeed.assemble(rows, T0).size,
        )
    }

    @Test
    fun `a duplicate does not resurrect a row the user already read`() {
        val disk = FakeDisk()
        disk.store(listOf(notice("n-1")))
        val seen = setOf(NotificationQueue.feedId("n-1"))
        disk.store(listOf(notice("n-1")))

        val rows = NotificationQueue.toFeedItems(
            NotificationQueue.renderable(disk.load(), T0), seen
        )
        assertEquals(1, rows.size)
        assertFalse("a redelivery means 'you never told us', not 'it happened again'", rows[0].unread)
    }

    @Test
    fun `a redelivery makes the id pending again, because our ack was lost`() {
        val disk = FakeDisk()
        disk.store(listOf(notice("n-1")))
        disk.markAcked(disk.pendingAcks())
        assertEquals("nothing owed once acked", emptyList<String>(), disk.pendingAcks())

        disk.store(listOf(notice("n-1")))
        assertEquals(
            "the id must go out again or the redelivery repeats forever",
            listOf("n-1"), disk.pendingAcks(),
        )
    }

    @Test
    fun `a repeat keeps its original arrival time rather than resetting its age`() {
        val disk = FakeDisk()
        disk.store(listOf(notice("n-1").copy(createdAtEpochS = 0L, receivedAtMs = T0)))
        disk.store(listOf(notice("n-1").copy(createdAtEpochS = 0L, receivedAtMs = T0 + 60_000L)))
        assertEquals(T0, disk.load().single().receivedAtMs)
    }

    @Test
    fun `an id is not acked until it is persisted`() {
        val broken = FakeDisk(writable = false)
        broken.store(listOf(notice("n-1")))
        assertEquals(
            "a notification we could not store must never be claimed as durably held",
            emptyList<String>(), broken.pendingAcks(),
        )

        val good = FakeDisk()
        assertEquals(emptyList<String>(), good.pendingAcks())
        good.store(listOf(notice("n-1")))
        assertEquals(listOf("n-1"), good.pendingAcks())
    }

    @Test
    fun `acks are derived from the disk and never from what just arrived`() {
        val disk = FakeDisk()
        val arrived = listOf(notice("n-1"), notice("n-2"))
        assertEquals(emptyList<String>(), disk.pendingAcks())
        disk.store(arrived)
        assertEquals(listOf("n-1", "n-2"), disk.pendingAcks())
    }

    @Test
    fun `the ledger survives a simulated restart`() {
        val disk = FakeDisk()
        disk.store(listOf(notice("n-1"), notice("n-2", title = "New mail from the bank.")))
        disk.markAcked(listOf("n-1"))

        val afterReboot = disk.reboot()

        val held = afterReboot.load().associateBy { it.id }
        assertEquals("both records survive", 2, held.size)
        assertTrue("an ack survives, or we would ack twice", held.getValue("n-1").acked)
        assertFalse("a pending ack survives, or we would never ack at all", held.getValue("n-2").acked)
        assertEquals(
            "and the next request still owes exactly the one id",
            listOf("n-2"), afterReboot.pendingAcks(),
        )
        assertEquals(
            "the title is intact, so the row still says who it is from",
            "New mail from the bank.", held.getValue("n-2").title,
        )
        assertEquals(
            "and it still renders",
            1, NotificationQueue.renderable(afterReboot.load(), T0).count { it.id == "n-2" },
        )
    }

    @Test
    fun `an unacked notification survives a restart taken between the response and the render`() {
        val disk = FakeDisk()
        disk.store(listOf(notice("n-1")))
        val afterCrash = disk.reboot()
        assertEquals(1, NotificationQueue.renderable(afterCrash.load(), T0).size)
        assertEquals(listOf("n-1"), afterCrash.pendingAcks())
    }

    @Test
    fun `a malformed ledger file starts empty instead of throwing`() {
        for (junk in listOf("", "   ", "not json", "{}", "[", "[1,2,3]", "null")) {
            assertEquals("'$junk' must not throw", emptyList<Notice>(), NotificationQueue.decode(junk))
        }
    }

    @Test
    fun `one unreadable row does not take the rest of the file with it`() {
        val good = NotificationQueue.encode(listOf(notice("n-1")))
        val mixed = "[" + "\"nonsense\"," + good.removePrefix("[").removeSuffix("]") + "]"
        assertEquals(listOf("n-1"), NotificationQueue.decode(mixed).map { it.id })
    }

    @Test
    fun `a notification with no id is dropped, because it could never be silenced`() {
        val disk = FakeDisk()
        disk.store(listOf(notice(""), notice("  "), notice("n-1")))
        assertEquals(listOf("n-1"), disk.load().map { it.id })
    }

    @Test
    fun `an empty notification does not crash the feed and draws no row`() {
        val disk = FakeDisk()
        disk.store(listOf(notice("n-blank", title = ""), notice("n-ws", title = "   ")))

        assertEquals("nothing to draw", 0, NotificationQueue.renderable(disk.load(), T0).size)
        assertEquals(
            "but it is still held, and still acked, or the backend re-sends it all day",
            listOf("n-blank", "n-ws"), disk.pendingAcks(),
        )
        val rows = NotificationQueue.toFeedItems(
            NotificationQueue.renderable(disk.load(), T0), emptySet()
        )
        assertEquals(emptyList<FeedItem>(), CommsFeed.assemble(rows, T0))
    }

    @Test
    fun `a notice with no timestamp stays visible instead of ageing out at the epoch`() {
        val n = notice("n-1").copy(createdAtEpochS = 0L, receivedAtMs = T0)
        assertEquals(T0, NotificationQueue.atMs(n))
        assertFalse(NotificationQueue.isExpired(n, T0))
        assertEquals(1, NotificationQueue.renderable(listOf(n), T0).size)
    }

    @Test
    fun `an unknown kind is shown rather than dropped`() {
        val n = notice("n-1", kind = "something_invented_next_quarter", title = "A thing happened.")
        val row = NotificationQueue.toFeedItems(listOf(n), emptySet()).single()
        assertEquals(1, NotificationQueue.renderable(listOf(n), T0).size)
        assertEquals("A thing happened.", CommsFeed.senderLine(row) { it })
        assertEquals("NOTICE", CommsFeed.kindLine(row))
    }

    @Test
    fun `a notice ages out at twenty four hours even when it is unread`() {
        val old = notice("n-old", atMs = T0 - CommsFeed.MAX_AGE_MS)
        val fresh = notice("n-new", atMs = T0 - 60_000L)

        assertTrue(NotificationQueue.isExpired(old, T0))
        assertEquals(listOf("n-new"), NotificationQueue.renderable(listOf(old, fresh), T0).map { it.id })

        val rows = NotificationQueue.toFeedItems(listOf(old, fresh), emptySet())
        assertEquals(
            "assemble() would have kept the stale one, which is why expiry runs before it",
            2, CommsFeed.assemble(rows, T0).size,
        )
    }

    @Test
    fun `the device window and the backend shelf life are the same number`() {
        assertEquals(CommsFeed.MAX_AGE_MS, NotificationQueue.MAX_AGE_MS)
        assertEquals(24L * 60L * 60L * 1000L, NotificationQueue.MAX_AGE_MS)
    }

    @Test
    fun `an expired notice is still acked, so the backend stops sending it`() {
        val disk = FakeDisk()
        disk.store(listOf(notice("n-old", atMs = T0 - 2 * CommsFeed.MAX_AGE_MS)))
        assertEquals(0, NotificationQueue.renderable(disk.load(), T0).size)
        assertEquals(listOf("n-old"), disk.pendingAcks())
    }

    @Test
    fun `the ledger is bounded and evicts the oldest first`() {
        val many = (1..NotificationQueue.MAX_HELD + 10).map {
            notice("n-$it", atMs = T0 - it * 1000L)
        }
        val kept = NotificationQueue.trim(many)
        assertEquals(NotificationQueue.MAX_HELD, kept.size)
        assertTrue("the newest is kept", kept.any { it.id == "n-1" })
        assertFalse("the oldest is not", kept.any { it.id == "n-${NotificationQueue.MAX_HELD + 10}" })
    }

    @Test
    fun `never more rows than this build advertises it can render`() {
        val many = (1..NotificationQueue.MAX_HELD).map { notice("n-$it", atMs = T0 - it * 1000L) }
        assertEquals(
            CommsFeed.MAX_NOTIFICATIONS,
            NotificationQueue.renderable(many, T0).size,
        )
        assertTrue("and the cap is a real number, never zero", CommsFeed.MAX_NOTIFICATIONS > 0)
    }

    @Test
    fun `the display cap never permanently hides something the user has not seen`() {
        val stale = notice("n-unread", atMs = T0 - 10 * 60_000L)
        val newer = (1..CommsFeed.MAX_NOTIFICATIONS).map { notice("n-read-$it", atMs = T0 - it * 1000L) }
        val seen = newer.map { NotificationQueue.feedId(it.id) }.toSet()

        val shown = NotificationQueue.renderable(newer + stale, T0, seen)
        assertEquals(CommsFeed.MAX_NOTIFICATIONS, shown.size)
        assertTrue("the unread one keeps a slot", shown.any { it.id == "n-unread" })

        val rows = CommsFeed.assemble(NotificationQueue.toFeedItems(shown, seen), T0)
        assertEquals("notice:n-read-1", rows.first().id)
        assertEquals("notice:n-unread", rows.last().id)
    }

    @Test
    fun `mail_unread of zero clears the badge`() {
        val read = notice("n-1")
        val rows = NotificationQueue.toFeedItems(
            listOf(read), setOf(NotificationQueue.feedId("n-1"))
        )
        assertEquals(0, CommsFeed.waitingCount(rows, voicemailWaiting = false, mailUnread = 0))
        assertEquals(0, CommsFeed.unbadgedMail(rows, 0))
    }

    @Test
    fun `a badge that was set does not stick when the count returns to zero`() {
        val rows = NotificationQueue.toFeedItems(
            listOf(notice("n-1")), setOf(NotificationQueue.feedId("n-1"))
        )
        assertEquals(3, CommsFeed.waitingCount(rows, voicemailWaiting = false, mailUnread = 3))
        assertEquals(0, CommsFeed.waitingCount(rows, voicemailWaiting = false, mailUnread = 0))
    }

    @Test
    fun `one piece of mail is never counted twice`() {
        val unreadRow = NotificationQueue.toFeedItems(listOf(notice("n-1")), emptySet())
        assertEquals(
            "the row and the count describe the same message",
            1, CommsFeed.waitingCount(unreadRow, voicemailWaiting = false, mailUnread = 1),
        )
    }

    @Test
    fun `mail beyond what has a row of its own still counts`() {
        val unreadRow = NotificationQueue.toFeedItems(listOf(notice("n-1")), emptySet())
        assertEquals(5, CommsFeed.waitingCount(unreadRow, voicemailWaiting = false, mailUnread = 5))
    }

    @Test
    fun `a read notice still leaves its unread mail on the badge`() {
        val readRow = NotificationQueue.toFeedItems(
            listOf(notice("n-1")), setOf(NotificationQueue.feedId("n-1"))
        )
        assertEquals(2, CommsFeed.waitingCount(readRow, voicemailWaiting = false, mailUnread = 2))
    }

    @Test
    fun `a nonsense negative count cannot pull the badge below the other rows`() {
        val call = FeedItem("c-1", FeedKind.MISSED_CALL, "5551234", null, "", T0, unread = true)
        assertEquals(1, CommsFeed.waitingCount(listOf(call), voicemailWaiting = false, mailUnread = 0))
        assertEquals(0, CommsFeed.unbadgedMail(emptyList(), -4))
    }

    @Test
    fun `the badge still counts voicemail and arrivals alongside mail`() {
        val call = FeedItem("c-1", FeedKind.MISSED_CALL, "5551234", null, "", T0, unread = true)
        assertEquals(
            1  + 1  + 2 ,
            CommsFeed.waitingCount(listOf(call), voicemailWaiting = true, mailUnread = 2),
        )
    }

    @Test
    fun `a notification carries no number and can never offer an action`() {
        val row = NotificationQueue.toFeedItems(listOf(notice("n-1")), emptySet()).single()
        assertEquals("", row.number)
        assertEquals(null, row.contactName)
        assertEquals("a notification has no body, ever", "", row.body)
        assertFalse(
            "the worst a bad notification may do is put a wrong sentence on the screen",
            CommsFeed.canCallBack(row),
        )
        assertFalse(
            "and it is never accused of being an unknown caller",
            CommsFeed.isUnknownSender(row),
        )
    }

    @Test
    fun `the title is rendered verbatim and never reformatted`() {
        val phrased = "New mail from Sarah Chen."
        val row = NotificationQueue.toFeedItems(listOf(notice("n-1", title = phrased)), emptySet())
            .single()
        assertEquals(phrased, CommsFeed.senderLine(row) { fail -> fail })
        assertEquals("NEW MAIL", CommsFeed.kindLine(row))
    }

    @Test
    fun `urgency changes the words and nothing else`() {
        fun line(u: String) = CommsFeed.kindLine(
            NotificationQueue.toFeedItems(listOf(notice("n", urgency = u)), emptySet()).single()
        )
        assertEquals("NEW MAIL", line("passive"))
        assertEquals("NEW MAIL", line("active"))
        assertEquals("NEW MAIL · TIME SENSITIVE", line("time_sensitive"))
        assertEquals("NEW MAIL · URGENT", line("critical"))
        assertEquals("an unknown tier is not an error", "NEW MAIL", line("whatever"))

        val older = notice("urgent", urgency = "critical", atMs = T0 - 60_000L)
        val newer = notice("calm", urgency = "passive", atMs = T0)
        val rows = NotificationQueue.toFeedItems(
            NotificationQueue.renderable(listOf(older, newer), T0), emptySet()
        )
        assertEquals(listOf("calm", "urgent"), CommsFeed.assemble(rows, T0).map { it.id.removePrefix("notice:") })
    }

    @Test
    fun `a notice id can never collide with a call or text id`() {
        val row = NotificationQueue.toFeedItems(listOf(notice("abc123")), emptySet()).single()
        assertEquals("notice:abc123", row.id)
        assertTrue(row.id.startsWith("notice:"))
    }

    @Test
    fun `notices share the feed with calls and texts without disturbing the order`() {
        val call = FeedItem("c-1", FeedKind.MISSED_CALL, "5551234", "Mum", "", T0 - 30_000L, true)
        val text = FeedItem("s-1", FeedKind.TEXT, "5555678", null, "code 4821", T0 - 90_000L, true)
        val rows = NotificationQueue.toFeedItems(listOf(notice("n-1", atMs = T0 - 60_000L)), emptySet())
        val all = CommsFeed.assemble(listOf(call, text) + rows, T0)
        assertEquals(listOf("c-1", "notice:n-1", "s-1"), all.map { it.id })
    }

    @Test
    fun `the wire message maps onto the ledger field for field`() {
        val wire = rist.v1.Notification.newBuilder()
            .setId("srv-9")
            .setKind("mail")
            .setTitle("New mail from Sarah Chen.")
            .setUrgency("active")
            .setCreatedAtEpochS(secs(T0 - 3_600_000L))
            .build()
        val n = NotificationQueue.fromWire(listOf(wire), T0).single()
        assertEquals("srv-9", n.id)
        assertEquals("mail", n.kind)
        assertEquals("New mail from Sarah Chen.", n.title)
        assertEquals("active", n.urgency)
        assertEquals(secs(T0 - 3_600_000L), n.createdAtEpochS)
        assertEquals("stamped when we wrote it down, as the fallback clock", T0, n.receivedAtMs)
        assertFalse("nothing arrives pre-acked", n.acked)
        assertEquals("and it dates from when Rist decided it", T0 - 3_600_000L, NotificationQueue.atMs(n))
    }

    @Test
    fun `the request field carries exactly the ids we hold`() {
        val disk = FakeDisk()
        disk.store(
            NotificationQueue.fromWire(
                listOf(
                    rist.v1.Notification.newBuilder().setId("a").setTitle("one").build(),
                    rist.v1.Notification.newBuilder().setId("b").setTitle("two").build(),
                ),
                T0,
            )
        )
        val req = rist.v1.DeviceRequest.newBuilder()
            .addAllNotificationAck(disk.pendingAcks())
            .build()
        assertEquals(listOf("a", "b"), req.notificationAckList)

        val back = rist.v1.DeviceRequest.parseFrom(req.toByteArray())
        assertEquals(listOf("a", "b"), back.notificationAckList)
    }

    @Test
    fun `this build advertises that it can render notifications at all`() {
        val caps = DeviceProfile.capabilities(1080, 2400)
        assertEquals(CommsFeed.MAX_NOTIFICATIONS, caps.maxNotifications)
        assertTrue(caps.maxNotifications > 0)
        assertEquals("and the schema version the notifications ride on", 12, caps.schemaVersion)
    }
}
