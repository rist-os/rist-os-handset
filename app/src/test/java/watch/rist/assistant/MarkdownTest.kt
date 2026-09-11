package watch.rist.assistant

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MarkdownTest {

    private fun render(src: String) = Markdown.render(src) as Spanned

    private inline fun <reified T> spanAt(s: Spanned, index: Int): T? =
        s.getSpans(index, index + 1, T::class.java).firstOrNull()

    /**
     * Asserts a span of type [T] covers exactly [text] and nothing more.
     *
     * Presence alone is not enough: a bold span that also swallows its own leading marker
     * is "present" at the right index while still being wrong.
     */
    private inline fun <reified T> assertSpans(s: Spanned, text: String, style: Int? = null) {
        val from = s.toString().indexOf(text)
        assertTrue("'$text' not in '${s}'", from >= 0)
        val match = s.getSpans(0, s.length, T::class.java).firstOrNull {
            (style == null || (it as? StyleSpan)?.style == style) &&
                s.getSpanStart(it) == from && s.getSpanEnd(it) == from + text.length
        }
        assertNotNull(
            "no ${T::class.java.simpleName}${style?.let { "/$it" } ?: ""} exactly over '$text'; " +
                "spans were " + s.getSpans(0, s.length, T::class.java).joinToString {
                    "${it!!::class.java.simpleName}[${s.getSpanStart(it)},${s.getSpanEnd(it)}]"
                },
            match,
        )
    }

    // ---- markers are consumed, spans land exactly ----

    @Test
    fun `bold marker is consumed and covers only the word`() {
        val s = render("say **loudly** now")
        assertEquals("say loudly now", s.toString())
        assertSpans<StyleSpan>(s, "loudly", Typeface.BOLD)
    }

    @Test
    fun `italic marker is consumed and covers only the word`() {
        val s = render("say *softly* now")
        assertEquals("say softly now", s.toString())
        assertSpans<StyleSpan>(s, "softly", Typeface.ITALIC)
    }

    @Test
    fun `triple marker gives bold and italic over the same run`() {
        val s = render("***very much***")
        assertEquals("very much", s.toString())
        assertSpans<StyleSpan>(s, "very much", Typeface.BOLD)
        assertSpans<StyleSpan>(s, "very much", Typeface.ITALIC)
    }

    @Test
    fun `inline code becomes monospace without backticks`() {
        val s = render("run `ls -la` here")
        assertEquals("run ls -la here", s.toString())
        assertSpans<TypefaceSpan>(s, "ls -la")
    }

    @Test
    fun `strikethrough is consumed`() {
        val s = render("~~gone~~ kept")
        assertEquals("gone kept", s.toString())
        assertSpans<StrikethroughSpan>(s, "gone")
    }

    @Test
    fun `nested emphasis yields a bold and an italic over different ranges`() {
        val s = render("**bold with *italic* inside**")
        assertEquals("bold with italic inside", s.toString())
        assertSpans<StyleSpan>(s, "bold with italic inside", Typeface.BOLD)
        assertSpans<StyleSpan>(s, "italic", Typeface.ITALIC)
    }

    // ---- block structure ----

    @Test
    fun `heading loses its hashes and gains size`() {
        val s = render("# Title")
        assertEquals("Title", s.toString())
        assertSpans<RelativeSizeSpan>(s, "Title")
        assertSpans<StyleSpan>(s, "Title", Typeface.BOLD)
    }

    @Test
    fun `deeper headings are smaller than shallower ones`() {
        val h1 = spanAt<RelativeSizeSpan>(render("# a"), 0)!!.sizeChange
        val h2 = spanAt<RelativeSizeSpan>(render("## a"), 0)!!.sizeChange
        assertTrue("h1 ($h1) should exceed h2 ($h2)", h1 > h2)
    }

    @Test
    fun `bullets become a bullet glyph`() {
        assertEquals("• one\n• two", render("- one\n- two").toString())
    }

    @Test
    fun `ordered list keeps the author's numbering`() {
        assertEquals("3. three\n4. four", render("3. three\n4. four").toString())
    }

    @Test
    fun `blockquote is marked and its text kept`() {
        assertEquals("│ quoted", render("> quoted").toString())
    }

    @Test
    fun `a dash rule becomes a rule`() {
        assertEquals("────────────", render("---").toString())
    }

    @Test
    fun `a dash rule with trailing space is still a rule`() {
        assertEquals("────────────", render("---  ").toString())
    }

    // ---- rule 1: an unclosed marker stays literal ----

    @Test
    fun `an unclosed bold marker stays literal`() {
        val s = render("this is **still arriving")
        assertEquals("this is **still arriving", s.toString())
        assertNull(spanAt<StyleSpan>(s, s.length - 1))
    }

    @Test
    fun `an unclosed backtick stays literal`() {
        assertEquals("the ` character", render("the ` character").toString())
    }

    @Test
    fun `a bare triple asterisk is not a rule`() {
        // "***" is the leading edge of "***bold***" still arriving. Painting a horizontal
        // rule here makes the pane flash a divider that then reflows into text.
        assertEquals("***", render("***").toString())
        assertEquals("___", render("___").toString())
        assertEquals("****", render("****").toString())
    }

    @Test
    fun `an unterminated code fence still renders as code`() {
        val s = render("```\nfun main() {")
        assertEquals("fun main() {", s.toString())
        assertSpans<TypefaceSpan>(s, "fun main() {")
    }

    // ---- rule 2: nothing is dropped, nothing renders blank ----

    @Test
    fun `plain prose passes through unchanged`() {
        val plain = "No markdown here. Just a sentence, 3 + 4 = 7."
        assertEquals(plain, render(plain).toString())
    }

    @Test
    fun `multiplication is not italics`() {
        // A space-flanked asterisk is arithmetic, not emphasis.
        val s = render("5 * 3 * 2 = 30")
        assertEquals("5 * 3 * 2 = 30", s.toString())
        assertNull(spanAt<StyleSpan>(s, s.indexOf("3")))
    }

    @Test
    fun `snake_case identifiers are not italicised`() {
        val s = render("call some_var_name now")
        assertEquals("call some_var_name now", s.toString())
        assertNull(spanAt<StyleSpan>(s, s.indexOf("var")))
    }

    @Test
    fun `intraword double underscore is left alone`() {
        assertEquals("foo__bar__baz", render("foo__bar__baz").toString())
    }

    @Test
    fun `a stray underscore does not reach into a later identifier`() {
        // The closer must not be glued to a word, or these two words get joined.
        assertEquals(
            "set _the value in max_retries",
            render("set _the value in max_retries").toString(),
        )
    }

    @Test
    fun `underscore emphasis still works at a word boundary`() {
        val s = render("say _softly_ now")
        assertEquals("say softly now", s.toString())
        assertSpans<StyleSpan>(s, "softly", Typeface.ITALIC)
    }

    @Test
    fun `dunder names still bold, matching CommonMark`() {
        assertEquals("init", render("__init__").toString())
    }

    @Test
    fun `a message that is only an opening fence never renders blank`() {
        assertTrue(render("```").toString().isNotEmpty())
        assertTrue(render("```python").toString().isNotEmpty())
    }

    @Test
    fun `empty input renders empty and is still a Spanned`() {
        assertEquals("", render("").toString())
    }

    @Test
    fun `a lone bracket is not treated as a link`() {
        assertEquals("array[0] is first", render("array[0] is first").toString())
    }

    @Test
    fun `link shows its label and hides its target`() {
        val s = render("see [the docs](https://example.com/x) for more")
        assertEquals("see the docs for more", s.toString())
        assertSpans<UnderlineSpan>(s, "the docs")
    }

    @Test
    fun `a link target containing parentheses is consumed whole`() {
        assertEquals("see x end", render("see [x](http://a.com/f(1)) end").toString())
    }

    // ---- fences and separators, the class of bug unit-per-feature tests miss ----

    @Test
    fun `fenced code block is monospace and keeps its inner markers literal`() {
        val s = render("```\nval x = a * b * c\n```")
        assertEquals("val x = a * b * c", s.toString())
        assertSpans<TypefaceSpan>(s, "val x = a * b * c")
        assertNull("no emphasis inside a code block", spanAt<StyleSpan>(s, s.indexOf("b")))
    }

    @Test
    fun `a code block that ends the reply leaves no trailing blank line`() {
        assertEquals("before\ncode", render("before\n```\ncode\n```").toString())
    }

    @Test
    fun `text after a code block is separated by exactly one newline`() {
        assertEquals("code\nafter", render("```\ncode\n```\nafter").toString())
    }

    @Test
    fun `blank lines are preserved as paragraph breaks`() {
        assertEquals("one\n\ntwo", render("one\n\ntwo").toString())
    }

    @Test
    fun `a realistic multi-block reply renders exactly`() {
        // The end-to-end guard. Any future change to block separators shows up here first.
        val src = """
            # Heading
            Some *emphasis* and `code`.

            - first
            - second

            ```
            run --now
            ```
            Trailing line.
        """.trimIndent()
        assertEquals(
            "Heading\nSome emphasis and code.\n\n• first\n• second\n\nrun --now\nTrailing line.",
            render(src).toString(),
        )
    }

    // ---- rule 3: bounded work ----

    @Test(timeout = 5_000)
    fun `a line of unmatched brackets does not hang the renderer`() {
        // Every '[' used to rescan to the end of the line looking for ']'.
        assertEquals(200_000, render("[".repeat(200_000)).length)
    }

    @Test(timeout = 5_000)
    fun `a line of unclosed emphasis openers does not hang either`() {
        // Every "**" opened, and each opener rescanned to end of line for a closer that the
        // flanking rule rejects. Same quadratic shape as the brackets, different marker.
        assertTrue(render("**a ".repeat(50_000)).length > 0)
        assertTrue(render("~~a ".repeat(50_000)).length > 0)
        assertTrue(render("_a ".repeat(50_000)).length > 0)
    }

    @Test(timeout = 5_000)
    fun `a line of brackets closed only at the very end does not hang`() {
        assertTrue(render("[".repeat(200_000) + "]").toString().isNotEmpty())
    }

    @Test
    fun `a very long reply is truncated rather than hanging the renderer`() {
        assertTrue(render("a".repeat(300_000)).length <= 200_000)
    }

    @Test
    fun `truncation never splits an emoji`() {
        val s = render("a".repeat(199_999) + "😀" + "b".repeat(100))
        assertTrue(
            "last char is a dangling high surrogate",
            !Character.isHighSurrogate(s[s.length - 1]),
        )
    }

    @Test
    fun `emoji survive and emphasis around them lands correctly`() {
        val s = render("hi 😀 **bold** 😀")
        assertEquals("hi 😀 bold 😀", s.toString())
        assertSpans<StyleSpan>(s, "bold", Typeface.BOLD)
    }

    // ---- tables ----

    @Test
    fun `a table becomes an aligned monospace grid`() {
        val s = render("| Name | Qty |\n|---|---|\n| apple | 3 |\n| fig | 12 |")
        assertEquals("Name  │ Qty\n──────┼────\napple │ 3\nfig   │ 12", s.toString())
        assertSpans<TypefaceSpan>(s, s.toString())
        assertSpans<StyleSpan>(s, "Name  │ Qty", Typeface.BOLD)
    }

    @Test
    fun `table cell markdown is rendered and does not throw off the padding`() {
        // The width must come from the RENDERED cell, not the source, or "**bold**"
        // reserves four columns it does not use.
        val s = render("| a | b |\n|---|---|\n| **bold** | x |")
        assertEquals("a    │ b\n─────┼──\nbold │ x", s.toString())
        assertSpans<StyleSpan>(s, "bold", Typeface.BOLD)
    }

    @Test
    fun `right and centre alignment markers are accepted`() {
        val s = render("| a | b |\n|---:|:---:|\n| 1 | 2 |")
        assertEquals("a │ b\n──┼──\n1 │ 2", s.toString())
    }

    @Test
    fun `a ragged row loses no cells`() {
        val s = render("| a | b |\n|---|---|\n| 1 |\n| 2 | 3 |")
        assertTrue(s.toString(), s.toString().contains("3"))
    }

    @Test
    fun `pipes without a delimiter row are not a table`() {
        assertEquals("a | b | c", render("a | b | c").toString())
    }

    @Test
    fun `text after a table is separated by one newline`() {
        val s = render("| a | b |\n|---|---|\n| 1 | 2 |\nafter")
        assertTrue(s.toString(), s.toString().endsWith("\nafter"))
    }

    @Test
    fun `a table inside a code fence stays literal`() {
        assertEquals(
            "| a | b |\n|---|---|",
            render("```\n| a | b |\n|---|---|\n```").toString(),
        )
    }
}
