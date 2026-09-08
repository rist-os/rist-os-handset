package watch.rist.assistant

import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
class AttachmentViewTest {

    companion object {
        const val PHONE = "w411dp-h891dp-xxhdpi"
    }

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clean() {
        Config.setThemeId(ctx(), "ledger")
        // Font scale is shared state: two tests push it to 2x and nothing else resets it.
        setFontScale(1f)
    }

    private fun setFontScale(scale: Float) {
        val res = ctx().resources
        res.configuration.fontScale = scale
        @Suppress("DEPRECATION")
        res.updateConfiguration(res.configuration, res.displayMetrics)
    }

    @After
    fun restoreCardLayout() {
        AttachmentView.cardLayout = R.layout.attachment_card
    }

    private fun be(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(t); update(data) }.value.toInt()
        return be(data.size) + t + data + be(crc)
    }

    private fun png(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        out.write(chunk("IHDR", be(w) + be(h) + byteArrayOf(8, 0, 0, 0, 0)))
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw, Deflater(Deflater.BEST_SPEED)).use { dfl ->
            val row = ByteArray(1 + w)
            for (y in 0 until h) dfl.write(row)
        }
        out.write(chunk("IDAT", raw.toByteArray()))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun truncatedPng(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        out.write(chunk("IHDR", be(64) + be(48) + byteArrayOf(8, 0, 0, 0, 0)))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun attachment(
        kind: String,
        mime: String = "application/octet-stream",
        title: String = "Test attachment",
        text: String = "",
        bytes: ByteArray? = null,
        error: String? = null,
    ) = RistAttachment(kind, mime, title, text, bytes, "tool-1", error)

    private fun home(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).create().get()

    private fun replyContainer(a: MainActivity): LinearLayout =
        requireNotNull(a.findViewById<LinearLayout>(R.id.replyContainer)) {
            "R.id.replyContainer is missing from activity_main.xml — attachments have no host"
        }

    private fun children(g: ViewGroup): List<View> = (0 until g.childCount).map { g.getChildAt(it) }

    private fun cards(g: ViewGroup): List<View> =
        children(g).filter { it.id == R.id.attachmentCard }

    private fun renderOnHome(vararg items: RistAttachment): Pair<MainActivity, List<View>> {
        val a = home()
        val host = replyContainer(a)
        AttachmentView.render(host, items.toList())
        return a to cards(host)
    }

    private fun only(cards: List<View>): View {
        assertEquals("expected exactly one attachment card, got ${cards.size}", 1, cards.size)
        return cards[0]
    }

    private fun tv(card: View, id: Int): TextView = card.findViewById(id)
    private fun shown(v: View): Boolean = v.visibility == View.VISIBLE

    @Test
    fun `an image attachment is drawn as a visible ImageView carrying a real picture`() {
        val (_, cards) = renderOnHome(
            attachment("image", mime = "image/png", title = "Chart", bytes = png(200, 120))
        )
        val card = only(cards)
        val image = card.findViewById<ImageView>(R.id.attachmentImage)

        assertTrue("the picture is in the tree but not visible", shown(image))
        assertNotNull(
            "the ImageView has no drawable — the bytes never reached it, so the card is an " +
                "empty frame with a heading",
            image.drawable
        )
        assertEquals(200, image.drawable.intrinsicWidth)
        assertEquals(120, image.drawable.intrinsicHeight)
        assertFalse(
            "a picture that decoded fine is also showing a failure line",
            shown(tv(card, R.id.attachmentError))
        )
        assertEquals("IMAGE", tv(card, R.id.attachmentKind).text.toString())
    }

    @Test
    fun `the card lands in the visible reply container, not in a detached view`() {
        val a = home()
        val host = replyContainer(a)
        AttachmentView.render(host, listOf(attachment("image", bytes = png(40, 40))))

        assertEquals("nothing was added to the reply container", 1, cards(host).size)
        assertEquals(
            "the reply container is hidden, so the attachment was drawn where nobody can see it",
            View.VISIBLE, host.visibility
        )
        assertEquals(View.VISIBLE, cards(host)[0].visibility)
    }

    @Test
    fun `a huge picture is subsampled at decode time rather than decoded at full size`() {
        val bytes = png(4096, 3072)
        val declared = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, declared)
        assertEquals("the fixture is not the huge image this test needs", 4096, declared.outWidth)
        assertEquals(3072, declared.outHeight)

        val (a, cards) = renderOnHome(attachment("image", mime = "image/png", bytes = bytes))
        val image = only(cards).findViewById<ImageView>(R.id.attachmentImage)
        val drawable = requireNotNull(image.drawable) { "the huge picture did not decode at all" }

        val screenW = a.resources.displayMetrics.widthPixels
        val maxH = (AttachmentView.MAX_IMAGE_HEIGHT_DP * a.resources.displayMetrics.density).toInt()
        assertTrue(
            "the picture decoded at ${drawable.intrinsicWidth}x${drawable.intrinsicHeight}, " +
                "which is not bounded by the ${screenW}x$maxH it can possibly be shown at — " +
                "inSampleSize is not being applied and this allocation is the sender's to choose",
            drawable.intrinsicWidth <= screenW && drawable.intrinsicHeight <= maxH
        )
        assertTrue(
            "the picture was sampled down to ${drawable.intrinsicWidth}px wide, which is unusable",
            drawable.intrinsicWidth >= screenW / 2
        )
    }

    @Test
    fun `a tall picture is bounded by the drawn-height ceiling, not just by the screen width`() {
        val bytes = png(100, 4000)
        val declared = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, declared)
        assertEquals("the fixture is not the tall image this test needs", 4000, declared.outHeight)

        val (a, cards) = renderOnHome(attachment("image", mime = "image/png", bytes = bytes))
        val image = only(cards).findViewById<ImageView>(R.id.attachmentImage)
        val drawable = requireNotNull(image.drawable) { "the tall picture did not decode at all" }
        val d = a.resources.displayMetrics.density
        val maxH = (AttachmentView.MAX_IMAGE_HEIGHT_DP * d).toInt()

        assertTrue(
            "the picture decoded ${drawable.intrinsicHeight}px tall against a ${maxH}px ceiling — " +
                "the height rule is not bounding anything, and a long scan is decoded at whatever " +
                "size its sender chose",
            drawable.intrinsicHeight <= maxH
        )
        assertEquals(
            "attachment_card.xml's android:maxHeight and MAX_IMAGE_HEIGHT_DP have drifted apart, so " +
                "the picture is decoded to one size and drawn at another",
            maxH, image.maxHeight
        )
    }

    @Test
    fun `the drawn-height ceiling is a ceiling a phone screen can actually hold`() {
        assertTrue(
            "MAX_IMAGE_HEIGHT_DP is ${AttachmentView.MAX_IMAGE_HEIGHT_DP}dp — taller than the screen " +
                "it is meant to bound, so a picture still pushes the record control off the bottom",
            AttachmentView.MAX_IMAGE_HEIGHT_DP in 160..480
        )
    }

    @Test
    fun `a note shows its title as a heading and its body underneath`() {
        val body = "Take one tablet with food each morning. Ring the surgery if the rash returns."
        val (_, cards) = renderOnHome(
            attachment("text", mime = "text/plain", title = "From the pharmacy", text = body)
        )
        val card = only(cards)

        assertEquals("From the pharmacy", tv(card, R.id.attachmentTitle).text.toString())
        assertEquals("NOTE", tv(card, R.id.attachmentKind).text.toString())
        assertEquals(body, tv(card, R.id.attachmentText).text.toString())
        assertTrue(
            "the body is in the tree but its container is not visible",
            shown(card.findViewById<View>(R.id.attachmentNoteBlock))
        )
        assertFalse(
            "the note is selectable, which raises the platform text-selection toolbar over " +
                "attacker-influenced prose and can offer to CALL a number injected into it",
            tv(card, R.id.attachmentText).isTextSelectable
        )
        assertFalse(
            "a note that arrived intact is also showing a failure line",
            shown(tv(card, R.id.attachmentError))
        )
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = PHONE)
    fun `no part of the note card is a scroller nested inside the reply surface`() {
        val long = (1..400).joinToString(" ") { "line $it of a very long note" }
        val (_, cards) = renderOnHome(attachment("text", title = "Long", text = long))
        val card = only(cards)

        val block = card.findViewById<View>(R.id.attachmentNoteBlock)
        assertTrue("the note block is missing, so this test proves nothing", shown(block))

        val scrollers = mutableListOf<String>()
        fun walk(v: View) {
            if (v is android.widget.ScrollView || v is android.widget.HorizontalScrollView) {
                scrollers += v.javaClass.name
            }
            if (v is ViewGroup) children(v).forEach(::walk)
        }
        walk(card)

        assertEquals(
            "the attachment card contains a scroller ($scrollers) inside replyScroll, which is " +
                "itself a ScrollView. It will call requestDisallowInterceptTouchEvent on any drag " +
                "past the touch slop and never hand the gesture back, so a finger that lands on " +
                "the note stops the home screen scrolling at all",
            emptyList<String>(), scrollers
        )
    }

    @Test
    fun `a long note is collapsed to the line count and shows that it was cut`() {
        val long = (1..400).joinToString(" ") { "line $it of a very long note" }
        val (_, cards) = renderOnHome(attachment("text", title = "Long", text = long))
        val body = tv(only(cards), R.id.attachmentText)

        assertEquals(
            "the note is not capped at AttachmentView.COLLAPSED_LINES lines — the layout and the " +
                "code disagree, or the cap was dropped, and a 400-line note now takes the whole " +
                "reply surface",
            AttachmentView.COLLAPSED_LINES, body.maxLines
        )
        assertEquals(
            "the collapsed note does not ellipsize, so it stops mid-sentence with nothing saying " +
                "there is more of it",
            android.text.TextUtils.TruncateAt.END, body.ellipsize
        )
        assertEquals(
            "the note was truncated in the VIEW rather than only in the display — the full text " +
                "must still be there for the expand control to reveal",
            long, body.text.toString()
        )
    }

    @Test
    fun `a short note shows no expand control at all`() {
        val (_, cards) = renderOnHome(
            attachment("text", title = "Pharmacy", text = "Ready to collect.")
        )
        val card = only(cards)

        assertTrue(
            "the note block is not visible",
            shown(card.findViewById<View>(R.id.attachmentNoteBlock))
        )
        assertFalse(
            "a seventeen-character note is offering to show the rest of itself",
            shown(tv(card, R.id.attachmentExpand))
        )
    }

    @Test
    fun `a note too long to fit offers a way to see the rest of it`() {
        val prose = "x".repeat(AttachmentView.EXPAND_CHAR_BUDGET + 1)
        val (_, proseCards) = renderOnHome(attachment("text", title = "Long", text = prose))
        assertTrue(
            "a note over the character budget was cut with no way to reach the rest of it",
            shown(tv(only(proseCards), R.id.attachmentExpand))
        )

        val list = (1..12).joinToString("\n") { "9:0$it" }
        assertTrue(
            "this fixture is over the character budget, so it would pass on the prose rule alone " +
                "and proves nothing about lists",
            list.length <= AttachmentView.EXPAND_CHAR_BUDGET
        )
        val (_, listCards) = renderOnHome(attachment("text", title = "Times", text = list))
        assertTrue(
            "a twelve-line list of times is under the character budget, so it was collapsed to " +
                "eight lines and silently lost the last four with no control to reveal them",
            shown(tv(only(listCards), R.id.attachmentExpand))
        )
    }

    @Test
    fun `pressing the control expands the note in place and takes the control away`() {
        val long = (1..400).joinToString(" ") { "line $it of a very long note" }
        val (_, cards) = renderOnHome(attachment("text", title = "Long", text = long))
        val card = only(cards)
        val body = tv(card, R.id.attachmentText)
        val expand = tv(card, R.id.attachmentExpand)

        assertTrue("nothing to press, so this proves nothing", shown(expand))
        assertTrue(
            "the control is not clickable, so nothing was ever wired to it",
            expand.performClick()
        )

        assertEquals(
            "the note is still capped after the control was pressed, so the rest of it is still " +
                "unreachable and the press did nothing a user can see",
            Integer.MAX_VALUE, body.maxLines
        )
        assertNull(
            "the expanded note still ellipsizes, so complete text is still marked as cut short",
            body.ellipsize
        )
        assertFalse(
            "the control is still on the card after it has been used, offering to show a note " +
                "that is already whole",
            shown(expand)
        )
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = PHONE)
    fun `the collapsed note is the same eight lines at any font size`() {
        val long = (1..400).joinToString(" ") { "line $it of a very long note" }
        val a = home()
        val host = replyContainer(a)
        AttachmentView.render(host, listOf(attachment("text", title = "T", text = long)))
        val at1x = host.findViewById<TextView>(R.id.attachmentText).maxLines

        setFontScale(2f)
        val b = home()
        val host2 = replyContainer(b)
        AttachmentView.render(host2, listOf(attachment("text", title = "T", text = long)))
        val at2x = host2.findViewById<TextView>(R.id.attachmentText).maxLines

        assertEquals(
            "the collapsed note is $at1x lines at the normal font size and $at2x at 2x — the cap " +
                "is a length again rather than a line count, which is what made large type show a " +
                "third of the same note",
            at1x, at2x
        )
        assertEquals(AttachmentView.COLLAPSED_LINES, at2x)
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = PHONE)
    fun `a short note is drawn at its own height, not padded out to a fixed box`() {
        val (a, cards) = renderOnHome(
            attachment("text", title = "Pharmacy", text = "Ready to collect.")
        )
        val card = only(cards)
        val block = card.findViewById<ViewGroup>(R.id.attachmentNoteBlock)
        val body = tv(card, R.id.attachmentText)

        val wSpec = View.MeasureSpec.makeMeasureSpec(
            a.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY
        )
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        block.measure(wSpec, hSpec)

        assertTrue(
            "the note measured nothing at all, so this proves nothing",
            body.measuredHeight > 0
        )
        assertEquals(
            "the note block measured ${block.measuredHeight}px around ${body.measuredHeight}px of " +
                "text — it is being padded out to a box of its own, so a one-line note floats at " +
                "the top of an empty panel and reads as content that failed to load",
            body.measuredHeight, block.measuredHeight
        )
    }

    @Test
    fun `the expand control meets the touch target and text floor, and is themed, on every theme`() {
        val long = (1..400).joinToString(" ") { "line $it of a very long note" }
        for (t in Themes.ALL) {
            Config.setThemeId(ctx(), t.id)
            assertEquals("the theme did not stick", t.id, Config.themeId(ctx()))
            val (a, cards) = renderOnHome(attachment("text", title = "Long", text = long))
            val expand = tv(only(cards), R.id.attachmentExpand)
            val m = a.resources.displayMetrics

            assertTrue("[${t.id}] the control is not on the card at all", shown(expand))
            assertEquals(
                "[${t.id}] the expand control is not the theme's accent, so on this theme it can " +
                    "be the platform default against the card and disappear",
                t.accent, expand.currentTextColor
            )
            val target = 48f * m.density
            assertTrue(
                "[${t.id}] the control's touch target is ${expand.minHeight}px where 48dp is " +
                    "${target}px — under the platform minimum, on the only control this card has",
                expand.minHeight >= target - 0.5f
            )
            val floor = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 15f, m
            )
            assertTrue(
                "[${t.id}] the control is ${expand.textSize}px where the app's 15sp floor is " +
                    "${floor}px",
                expand.textSize >= floor - 0.5f
            )
        }
    }

    @Test
    fun `the expand control is announced as a button, and says what it will show`() {
        val long = (1..400).joinToString(" ") { "line $it of a very long note" }
        val (a, cards) = renderOnHome(attachment("text", title = "Long", text = long))
        val expand = tv(only(cards), R.id.attachmentExpand)

        assertEquals(
            "the label on the screen is not the string resource, so it cannot be translated",
            a.getString(R.string.attach_show_all), expand.text.toString()
        )
        assertEquals(
            "the control is read out as its two-word label, which on its own does not say what " +
                "would be shown",
            a.getString(R.string.attach_show_all_desc), expand.contentDescription?.toString()
        )

        val info = expand.createAccessibilityNodeInfo()
        assertNotNull("the control produced no accessibility node at all", info)
        assertEquals(
            "the control is announced as plain text, so a screen-reader user is given no reason " +
                "to think the rest of the note can be reached",
            android.widget.Button::class.java.name, info!!.className?.toString()
        )
    }

    @Test
    fun `a file card states its size in words a person can read`() {
        val (_, cards) = renderOnHome(
            attachment(
                "data", mime = "application/pdf", title = "Blood results",
                bytes = ByteArray(1_500_000),
            )
        )
        val card = only(cards)
        val meta = tv(card, R.id.attachmentMeta).text.toString()

        assertTrue("the file card shows no type or size line at all", shown(tv(card, R.id.attachmentMeta)))
        assertTrue("expected a readable size in \"$meta\"", meta.contains("1.5 MB"))
        assertFalse("the raw byte count is being shown to the user: \"$meta\"", meta.contains("1500000"))
        assertTrue("the type is not named in \"$meta\"", meta.contains("application/pdf"))
        assertEquals("FILE", tv(card, R.id.attachmentKind).text.toString())
        assertEquals("Blood results", tv(card, R.id.attachmentTitle).text.toString())
    }

    @Test
    fun `a file card says plainly that it cannot be opened here, and offers no control that would fail`() {
        val (_, cards) = renderOnHome(
            attachment("data", mime = "application/pdf", bytes = ByteArray(2048))
        )
        val card = only(cards)
        assertTrue(
            "the file card's notice line is in the tree but GONE, so the one sentence telling the " +
                "user what can be done with this file is invisible",
            shown(tv(card, R.id.attachmentError))
        )
        assertTrue(
            "the file card does not say what can be done with the file",
            tv(card, R.id.attachmentError).text.toString().contains("cannot be opened")
        )
        assertFalse(
            "the file card is clickable, so it is offering an action this device cannot honour",
            card.isClickable
        )
    }

    @Test
    fun `the card's copy is read from resources, with its arguments filled in`() {
        val a = home()
        val host = replyContainer(a)

        AttachmentView.render(
            host, listOf(attachment("image", mime = "image/heif", title = "Scan", bytes = truncatedPng()))
        )
        assertEquals(
            "the \"this phone cannot read that format\" line does not name the format it could not " +
                "read, so the one fact in the sentence is missing",
            a.getString(R.string.attach_bad_format, "image/heif"),
            tv(cards(host)[0], R.id.attachmentError).text.toString()
        )

        AttachmentView.render(
            host, listOf(attachment("data", mime = "application/pdf", bytes = ByteArray(2_048)))
        )
        assertEquals(
            a.getString(R.string.attach_meta_fmt, "application/pdf", "2.0 KB"),
            tv(cards(host)[0], R.id.attachmentMeta).text.toString()
        )
        assertEquals(
            a.getString(R.string.attach_cannot_open),
            tv(cards(host)[0], R.id.attachmentError).text.toString()
        )

        AttachmentView.render(host, listOf(attachment("text", title = "Summary", text = "  ")))
        assertEquals(
            a.getString(R.string.attach_empty_note),
            tv(cards(host)[0], R.id.attachmentError).text.toString()
        )
    }

    @Test
    fun `an attachment of an unknown kind is still shown rather than silently dropped`() {
        val (_, cards) = renderOnHome(
            attachment("hologram", mime = "model/gltf-binary", title = "Scan", bytes = ByteArray(64))
        )
        val card = only(cards)
        assertEquals("Scan", tv(card, R.id.attachmentTitle).text.toString())
        assertEquals("FILE", tv(card, R.id.attachmentKind).text.toString())
    }

    @Test
    fun `an attachment that failed shows the reason, and no broken picture frame`() {
        val (_, cards) = renderOnHome(
            attachment(
                "image", mime = "image/jpeg", title = "Prescription",
                bytes = null, error = "the file was too large to fetch",
            )
        )
        val card = only(cards)
        val error = tv(card, R.id.attachmentError)

        assertTrue("the failure is not visible; the attachment vanished", shown(error))
        assertTrue(
            "the reason is not on the screen: \"${error.text}\"",
            error.text.toString().contains("too big to show")
        )
        assertFalse(
            "the ingest layer's own wording reached the card: \"${error.text}\"",
            error.text.toString().contains("fetch") || error.text.toString().contains("KiB")
        )
        assertEquals(
            "the title is gone too, so the user cannot tell WHICH attachment failed",
            "Prescription", tv(card, R.id.attachmentTitle).text.toString()
        )
        assertFalse(
            "a failed picture is still showing an ImageView — a blank frame reads as a broken app",
            shown(card.findViewById<View>(R.id.attachmentImage))
        )
        assertFalse(shown(card.findViewById<View>(R.id.attachmentNoteBlock)))
    }

    @Test
    fun `bytes that are not a picture are reported instead of leaving an empty card`() {
        val (_, cards) = renderOnHome(
            attachment("image", mime = "image/png", title = "Photo", bytes = truncatedPng())
        )
        val card = only(cards)
        assertTrue(
            "an undecodable picture produced no explanation at all",
            shown(tv(card, R.id.attachmentError))
        )
        assertFalse(shown(card.findViewById<View>(R.id.attachmentImage)))
    }

    @Test
    fun `a note that arrived empty says so rather than drawing a blank card`() {
        val (_, cards) = renderOnHome(attachment("text", title = "Summary", text = "   "))
        val card = only(cards)
        assertTrue(shown(tv(card, R.id.attachmentError)))
        assertFalse(shown(card.findViewById<View>(R.id.attachmentNoteBlock)))
    }

    @Test
    fun `a picture that arrived with no bytes says so, instead of blaming the format`() {
        val (_, cards) = renderOnHome(
            attachment("image", mime = "image/png", title = "Scan", bytes = ByteArray(0))
        )
        val card = only(cards)
        val error = tv(card, R.id.attachmentError)

        assertTrue("nothing on the card explains why there is no picture", shown(error))
        assertTrue(
            "an empty byte array is being reported as \"${error.text}\" — the user is told the " +
                "format is unreadable when in fact nothing arrived to read",
            error.text.toString().contains("did not arrive")
        )
        assertFalse(
            "an empty picture still drew an ImageView, which is a heading over a void",
            shown(card.findViewById<View>(R.id.attachmentImage))
        )
    }

    @Test
    fun `an attachment whose card cannot be built is still drawn, not silently dropped`() {
        AttachmentView.cardLayout = android.R.layout.simple_list_item_1
        val a = home()
        val host = replyContainer(a)

        AttachmentView.render(
            host,
            listOf(attachment("image", mime = "image/png", title = "Prescription", bytes = png(30, 30)))
        )

        val drawn = cards(host)
        assertEquals(
            "the attachment was dropped: the reply still refers to a picture that is nowhere on " +
                "this device, and the only record of why is in a logcat the user cannot reach",
            1, drawn.size
        )
        assertEquals(
            "the reply container is hidden, so the fallback was drawn where nobody can see it",
            View.VISIBLE, host.visibility
        )
        assertEquals(View.VISIBLE, drawn[0].visibility)

        val said = (drawn[0] as TextView).text.toString()
        assertTrue("the fallback card does not name which attachment failed: \"$said\"", said.contains("Prescription"))
        assertTrue("the fallback card does not say anything went wrong: \"$said\"", said.contains("could not be shown"))
        assertEquals(
            "the fallback line is not the theme's accent, so the one thing on the card is not the " +
                "colour this app uses to say something is wrong",
            Themes.byId("ledger").accent, (drawn[0] as TextView).currentTextColor
        )
    }

    @Test
    fun `humanSize spells out every magnitude in words a person can read`() {
        val r = ctx().resources
        assertEquals("0 bytes", AttachmentView.humanSize(r, 0L))
        assertEquals("847 bytes", AttachmentView.humanSize(r, 847L))
        assertEquals("999 bytes", AttachmentView.humanSize(r, 999L))

        assertEquals("1.0 KB", AttachmentView.humanSize(r, 1_000L))
        assertEquals("1.5 KB", AttachmentView.humanSize(r, 1_500L))

        assertEquals("1.0 MB", AttachmentView.humanSize(r, 1_000_000L))
        assertEquals("1.5 MB", AttachmentView.humanSize(r, 1_500_000L))

        assertEquals("1.0 GB", AttachmentView.humanSize(r, 1_000_000_000L))
        assertEquals("2.5 GB", AttachmentView.humanSize(r, 2_500_000_000L))

        assertEquals("size unknown", AttachmentView.humanSize(r, -1L))
        assertEquals("size unknown", AttachmentView.humanSize(r, -9_999L))
        assertEquals("size unknown", AttachmentView.humanSize(r, Long.MIN_VALUE))
    }

    @Test
    fun `a megabyte on the attachment card is the same megabyte as everywhere else on the phone`() {
        val r = ctx().resources
        assertEquals(
            "1,000,000 bytes is not being called a megabyte, so this card counts in binary while " +
                "the update screen and the user's data allowance count in decimal",
            "1.0 MB", AttachmentView.humanSize(r, 1_000_000L)
        )
        assertEquals(
            "the same file is 1.5 MB on the update screen and something else here",
            "1.5 MB", AttachmentView.humanSize(r, 1_500_000L)
        )
        assertEquals("1.0 KB", AttachmentView.humanSize(r, 1_000L))
        assertEquals(
            "1,000,000,000 bytes is not being called a gigabyte, so a size quoted here is 7% " +
                "short of the one the carrier bills for",
            "1.0 GB", AttachmentView.humanSize(r, 1_000_000_000L)
        )
    }

    @Test
    fun `a single byte is singular`() {
        val r = ctx().resources
        assertEquals("1 byte", AttachmentView.humanSize(r, 1L))
        assertEquals("2 bytes", AttachmentView.humanSize(r, 2L))
    }

    @Test
    fun `an empty list draws nothing and leaves no empty container behind`() {
        val a = home()
        val host = replyContainer(a)
        AttachmentView.render(host, emptyList())

        assertEquals("something was drawn for an empty list", 0, cards(host).size)
        assertEquals(
            "the reply container is left VISIBLE with no children — its 12dp top padding then " +
                "draws as a gap under the arrivals feed",
            View.GONE, host.visibility
        )
    }

    @Test
    fun `rendering twice does not stack a second copy of every attachment`() {
        val a = home()
        val host = replyContainer(a)
        val items = listOf(
            attachment("text", title = "One", text = "first"),
            attachment("data", mime = "application/pdf", bytes = ByteArray(10)),
        )
        AttachmentView.render(host, items)
        assertEquals("the first pass did not draw both attachments", 2, cards(host).size)

        AttachmentView.render(host, items)
        assertEquals(
            "a second render stacked duplicates: ${cards(host).size} cards for 2 attachments",
            2, cards(host).size
        )

        AttachmentView.render(host, emptyList())
        assertEquals("re-rendering with nothing left the old cards behind", 0, cards(host).size)
    }

    @Test
    fun `rendering does not delete the reply text sharing the same container`() {
        val a = home()
        val host = replyContainer(a)
        val reply = TextView(a).apply { text = "Here is the photo you asked for." }
        host.addView(reply)

        AttachmentView.render(host, listOf(attachment("image", bytes = png(30, 30))))
        AttachmentView.render(host, listOf(attachment("image", bytes = png(30, 30))))

        assertTrue(
            "the reply text was destroyed by the attachment viewer",
            children(host).contains(reply)
        )
        assertEquals(1, cards(host).size)
    }

    @Test
    fun `every colour on the card comes from the active theme, on every theme`() {
        for (t in Themes.ALL) {
            Config.setThemeId(ctx(), t.id)
            assertEquals(
                "the theme did not stick, so this iteration proves nothing", t.id, Config.themeId(ctx())
            )
            val (_, cards) = renderOnHome(
                attachment("data", mime = "application/pdf", title = "Report", bytes = ByteArray(4096))
            )
            val card = only(cards)

            assertEquals(
                "[${t.id}] the title is not the theme's ink",
                t.ink, tv(card, R.id.attachmentTitle).currentTextColor
            )
            assertEquals(
                "[${t.id}] the failure/notice line is not the theme's accent",
                t.accent, tv(card, R.id.attachmentError).currentTextColor
            )
            assertNotNull("[${t.id}] the card has no themed face at all", card.background)

            val muted = tv(card, R.id.attachmentKind).currentTextColor
            assertEquals(
                "[${t.id}] the type/size line and the kind label disagree about the muted tone",
                muted, tv(card, R.id.attachmentMeta).currentTextColor
            )
            assertNotEquals(
                "[${t.id}] the muted label is the same colour as the body ink, so the card has " +
                    "no visual hierarchy left",
                t.ink, muted
            )
        }
    }

    @Test
    fun `the secondary text on a card is legible against the card, on every theme`() {
        for (t in Themes.ALL) {
            Config.setThemeId(ctx(), t.id)
            val (_, cards) = renderOnHome(
                attachment("data", mime = "application/pdf", bytes = ByteArray(1024))
            )
            val card = only(cards)
            for (id in listOf(R.id.attachmentKind, R.id.attachmentMeta)) {
                val ratio = contrast(tv(card, id).currentTextColor, t.tileFill)
                assertTrue(
                    "[${t.id}] secondary text sits at %.2f:1 on the card face, under the 4.5:1 " .format(ratio) +
                        "floor — it is present but not readable at arm's length",
                    ratio >= 4.5
                )
            }
            val ink = contrast(tv(card, R.id.attachmentTitle).currentTextColor, t.tileFill)
            assertTrue("[${t.id}] the title is at %.2f:1 on the card face".format(ink), ink >= 4.5)
            val accent = contrast(tv(card, R.id.attachmentError).currentTextColor, t.tileFill)
            assertTrue(
                "[${t.id}] the failure line is at %.2f:1 on the card face — the one line that " .format(accent) +
                    "must be read is the one that cannot be",
                accent >= 4.5
            )
        }
    }

    @Test
    fun `the card is bounded by something a person can see, on every theme`() {
        for (t in Themes.ALL) {
            Config.setThemeId(ctx(), t.id)
            val (_, cards) = renderOnHome(
                attachment("data", mime = "application/pdf", bytes = ByteArray(1024))
            )
            val bg = only(cards).background
            val face = when (bg) {
                is android.graphics.drawable.LayerDrawable ->
                    bg.getDrawable(0) as android.graphics.drawable.GradientDrawable
                else -> bg as android.graphics.drawable.GradientDrawable
            }
            val stroke = org.robolectric.Shadows.shadowOf(face).strokeColor
            val edge = contrast(stroke, t.tileFill)
            val fill = contrast(t.tileFill, t.ground)

            if (fill >= 1.05) {
                assertEquals(
                    "[${t.id}] the card face already stands away from the ground at %.2f:1, so the " .format(fill) +
                        "stroke should be the one the theme authored — a card boxed in with a line " +
                        "as dark as its own secondary text is a heavier card than this design wants",
                    t.tileBorder, stroke
                )
            } else {
                assertTrue(
                    "[${t.id}] tileFill is the ground (%.2f:1), so the stroke is the entire card — " .format(fill) +
                        "and it sits at %.2f:1, under the 3:1 WCAG 1.4.11 asks of a boundary. " .format(edge) +
                        "The card is not a card; it is loose text under the reply",
                    edge >= AttachmentView.CARD_EDGE_MIN
                )
            }
        }
    }

    @Test
    fun `a picture is described for a screen reader, and an unnamed one still is`() {
        val (_, named) = renderOnHome(
            attachment("image", title = "Your prescription", bytes = png(50, 50))
        )
        assertEquals(
            "Your prescription",
            only(named).findViewById<ImageView>(R.id.attachmentImage).contentDescription?.toString()
        )

        val (_, unnamed) = renderOnHome(attachment("image", title = "  ", bytes = png(50, 50)))
        val fallback = only(unnamed)
            .findViewById<ImageView>(R.id.attachmentImage).contentDescription?.toString()
        assertTrue(
            "an untitled picture has no description at all, so a screen reader says nothing",
            !fallback.isNullOrBlank()
        )
    }

    @Test
    fun `an untitled attachment is still given a heading`() {
        val (_, cards) = renderOnHome(attachment("text", title = "", text = "some words"))
        assertTrue(
            "the heading is empty for an untitled note",
            tv(only(cards), R.id.attachmentTitle).text.toString().isNotBlank()
        )
    }

    @Test
    fun `card text grows with the system font scale`() {
        val a = home()
        val host = replyContainer(a)
        AttachmentView.render(host, listOf(attachment("text", title = "T", text = "body")))
        val before = tv(cards(host)[0], R.id.attachmentTitle).textSize

        a.resources.configuration.fontScale = 2f
        a.resources.updateConfiguration(a.resources.configuration, a.resources.displayMetrics)
        val b = home()
        val host2 = replyContainer(b)
        AttachmentView.render(host2, listOf(attachment("text", title = "T", text = "body")))
        val after = tv(cards(host2)[0], R.id.attachmentTitle).textSize

        assertTrue(
            "the heading measured ${before}px at 1x and ${after}px at 2x — the size is not in sp, " +
                "so someone who needs large type gets none",
            after > before * 1.5f
        )
    }

    @Test
    fun `a card that arrives after the reply was spoken is announced, politely`() {
        val (_, cards) = renderOnHome(
            attachment("text", title = "From the pharmacy", text = "Ready to collect.")
        )
        assertEquals(
            "the card root is not a live region, so a card that paints after the reply was read " +
                "out says nothing to a screen reader and the attachment does not exist for a " +
                "blind user",
            View.ACCESSIBILITY_LIVE_REGION_POLITE, only(cards).accessibilityLiveRegion
        )
    }

    @Test
    fun `an attachment that failed is spoken, not only drawn`() {
        val am = ctx().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        org.robolectric.Shadows.shadowOf(am).setEnabled(true)

        val a = home()
        val host = replyContainer(a)
        val spoken = mutableListOf<String>()
        host.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onRequestSendAccessibilityEvent(
                h: ViewGroup,
                child: View,
                e: android.view.accessibility.AccessibilityEvent,
            ): Boolean {
                if (e.eventType == android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT) {
                    spoken += e.text.joinToString(" ")
                }
                return super.onRequestSendAccessibilityEvent(h, child, e)
            }
        }

        AttachmentView.render(
            host,
            listOf(
                attachment(
                    "image", mime = "image/jpeg", title = "Prescription",
                    bytes = null, error = "the file was too large to fetch",
                )
            )
        )

        assertTrue(
            "a failed attachment was drawn and never spoken: $spoken — the assistant has just said " +
                "\"here is your prescription\" and a blind user has no way to learn that it is not " +
                "on the screen",
            spoken.any { it.contains("Prescription") && it.contains("too big to show") }
        )
    }

    @Test
    fun `a file that arrived intact is not announced as a failure`() {
        val am = ctx().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        org.robolectric.Shadows.shadowOf(am).setEnabled(true)

        val a = home()
        val host = replyContainer(a)
        val spoken = mutableListOf<String>()
        host.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onRequestSendAccessibilityEvent(
                h: ViewGroup,
                child: View,
                e: android.view.accessibility.AccessibilityEvent,
            ): Boolean {
                if (e.eventType == android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT) {
                    spoken += e.text.joinToString(" ")
                }
                return super.onRequestSendAccessibilityEvent(h, child, e)
            }
        }

        AttachmentView.render(
            host,
            listOf(attachment("data", mime = "application/pdf", title = "Blood results",
                bytes = ByteArray(2048)))
        )

        assertTrue(
            "a file that arrived intact interrupted the reply to announce itself: $spoken",
            spoken.isEmpty()
        )
    }

    @Test
    fun `an untitled card does not read its kind twice to a screen reader`() {
        val (_, untitled) = renderOnHome(attachment("image", title = "  ", bytes = png(20, 20)))
        val blank = tv(only(untitled), R.id.attachmentKind)
        assertEquals(
            "the kind label was removed from the screen, not just from the spoken tree — on Night " +
                "that is the only thing left saying what this card holds",
            "IMAGE", blank.text.toString()
        )
        assertEquals(
            "an untitled card reads \"IMAGE. Picture.\" — the same word twice, because the heading " +
                "fell back to the kind and the label is still in the accessibility tree",
            View.IMPORTANT_FOR_ACCESSIBILITY_NO, blank.importantForAccessibility
        )

        val (_, titled) = renderOnHome(attachment("image", title = "Chart", bytes = png(20, 20)))
        assertEquals(
            "a card with a real title has lost its kind from the spoken tree too, so a screen " +
                "reader no longer says whether \"Chart\" is a picture, a note or a file",
            View.IMPORTANT_FOR_ACCESSIBILITY_YES,
            tv(only(titled), R.id.attachmentKind).importantForAccessibility
        )
    }

    @Test
    fun `no text on the card is smaller than the app's 15sp floor`() {
        val (a, cards) = renderOnHome(
            attachment("data", mime = "application/pdf", title = "Blood results", bytes = ByteArray(4096))
        )
        val card = only(cards)
        val floor = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 15f, a.resources.displayMetrics
        )
        for (id in listOf(R.id.attachmentKind, R.id.attachmentMeta, R.id.attachmentError)) {
            val size = tv(card, id).textSize
            assertTrue(
                "a line on the card is ${size}px where 15sp is ${floor}px — below the floor the " +
                    "rest of this phone holds to, on the screen most likely to be read by someone " +
                    "who cannot see it well",
                size >= floor - 0.5f
            )
        }
    }

    @Test
    fun `every attachment in a reply gets its own card, in order`() {
        val (_, cards) = renderOnHome(
            attachment("text", title = "First", text = "one"),
            attachment("image", title = "Second", bytes = png(20, 20)),
            attachment("data", title = "Third", mime = "application/pdf", bytes = ByteArray(10)),
            attachment("image", title = "Fourth", bytes = null, error = "not fetched"),
        )
        assertEquals("an attachment went missing", 4, cards.size)
        assertEquals(
            listOf("First", "Second", "Third", "Fourth"),
            cards.map { tv(it, R.id.attachmentTitle).text.toString() }
        )
    }

    @Test
    fun `predecode attaches the bitmap so the main thread never decodes`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val item = attachment("image", mime = "image/png", bytes = png(8, 8))

        val out = AttachmentView.predecode(ctx, listOf(item))

        assertNotNull(
            "the picture reached the viewer undecoded, so BitmapFactory runs on the main thread " +
                "over bytes the device did not choose",
            out.single().bitmap
        )
    }

    @Test
    fun `predecode leaves notes, files and failures alone`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val out = AttachmentView.predecode(
            ctx,
            listOf(
                attachment("text", title = "note", text = "hello"),
                attachment("data", mime = "application/pdf", bytes = ByteArray(64)),
                attachment("image", mime = "image/png", bytes = null, error = "did not arrive"),
            )
        )
        out.forEach { assertNull("nothing but a picture should carry a bitmap", it.bitmap) }
    }

    @Test
    fun `predecode of nothing is nothing`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(AttachmentView.predecode(ctx, emptyList()).isEmpty())
    }

    @Test
    fun `predecode survives bytes that are not a picture`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val out = AttachmentView.predecode(
            // A truncated PNG, not junk: Robolectric invents a 100x100 bitmap for junk instead of returning null.
            ctx, listOf(attachment("image", mime = "image/png", bytes = truncatedPng()))
        )
        assertNull("undecodable bytes must yield no bitmap rather than throwing", out.single().bitmap)
    }
}
