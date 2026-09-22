package watch.rist.assistant

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.Log

// Markdown -> styled text for the reply pane.
//
// Hand-written rather than vendored. The only library that produces a Spannable directly is
// Markwon, and it pins com.atlassian.commonmark 0.13.0 — a groupId abandoned in 2019 — so
// adopting it would freeze a dead parser into a shipped OS image. The maintained parsers
// (org.commonmark, JetBrains markdown) emit an AST, not spans, which leaves the span walker
// below still to write. See aosp/prebuilts/README.md for what vendoring costs here.
//
// Three rules shape the whole file:
//   1. An unclosed marker stays literal. Replies stream in, so half of "**bold" arrives
//      before the rest, and a partial line must never restyle the text that follows it.
//   2. Nothing is ever dropped, and a non-blank reply never renders blank.
//   3. Every scan is bounded. This runs on the UI thread for every message in the pane on
//      every repaint, so no input may make it quadratic.
object Markdown {

    private const val TAG = "RistMarkdown"

    private const val SPAN = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

    // Guards a pathological reply; well past any real answer.
    private const val MAX_CHARS = 200_000

    // Bounds the inline recursion that nested emphasis produces.
    private const val MAX_DEPTH = 12

    // A link label or target longer than this is not a link, it is prose containing a bracket.
    private const val MAX_LINK_LABEL = 256
    private const val MAX_LINK_TARGET = 1024

    /**
     * Never throws. renderTranscript() clears the reply pane before it repaints, so an
     * exception raised here would leave the user staring at an empty screen with every
     * message gone — a far worse outcome than an unstyled one.
     */
    fun render(src: String): CharSequence {
        if (src.isEmpty()) return SpannableStringBuilder()
        return runCatching { build(src) }.getOrElse {
            Log.w(TAG, "markdown render failed; showing raw text", it)
            SpannableStringBuilder(src)
        }
    }

    private fun build(src: String): CharSequence {
        val out = SpannableStringBuilder()
        val text = clip(src)

        var fenced = false
        var fenceStart = -1
        var wrote = false

        val lines = text.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            if (line.trimStart().startsWith("```")) {
                if (!fenced) {
                    fenced = true
                    fenceStart = -1
                } else {
                    fenced = false
                    if (fenceStart >= 0) styleFence(out, fenceStart, out.length)
                }
                i++
                continue
            }

            // A table is the one construct that needs lookahead: a header row is only a
            // header if the next line is its delimiter.
            if (!fenced && i + 1 < lines.size && line.contains('|') &&
                isDelimiterRow(lines[i + 1]) && cells(line).size >= 2
            ) {
                val rows = ArrayList<List<String>>()
                rows += cells(line)
                var j = i + 2
                while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                    rows += cells(lines[j])
                    j++
                }
                if (wrote) out.append("\n")
                wrote = true
                emitTable(out, rows, alignments(lines[i + 1]))
                i = j
                continue
            }

            // The separator goes before the line, not after it, so a closing fence
            // cannot leave a dangling newline behind.
            if (wrote) out.append("\n")
            wrote = true
            if (fenced) {
                if (fenceStart < 0) fenceStart = out.length
                out.append(line) // verbatim: no inline parsing inside a code block
            } else {
                emitBlock(out, line)
            }
            i++
        }

        // An unterminated fence still gets its styling, so a code block that is still
        // streaming in looks like code while it arrives.
        if (fenced && fenceStart >= 0) styleFence(out, fenceStart, out.length)

        // Rule 2. A reply that is only an opening fence would otherwise paint an empty view.
        if (out.isEmpty() && text.isNotBlank()) out.append(text)
        return out
    }

    /** Never split a surrogate pair, or the last visible character becomes a tofu box. */
    private fun clip(src: String): String {
        if (src.length <= MAX_CHARS) return src
        val end = if (Character.isHighSurrogate(src[MAX_CHARS - 1])) MAX_CHARS - 1 else MAX_CHARS
        return src.substring(0, end)
    }

    // ---- tables ----
    //
    // Rendered as a padded monospace grid. A proportional font cannot align columns at all,
    // and this screen is grayscale with no colour to separate cells, so the fixed-width grid
    // is the only layout that stays readable. A table wider than the screen wraps rather than
    // being truncated: losing a cell would break rule 2.

    private const val ALIGN_LEFT = -1
    private const val ALIGN_CENTER = 0
    private const val ALIGN_RIGHT = 1

    /** `|---|:--:|---:|` and friends — the row that makes the line above it a header. */
    private fun isDelimiterRow(line: String): Boolean {
        val parts = cells(line)
        if (parts.size < 2) return false
        return parts.all { c ->
            c.isNotEmpty() && c.contains('-') && c.all { it == '-' || it == ':' }
        }
    }

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.dropLast(1)
        return t.split('|').map { it.trim() }
    }

    private fun alignments(delimiter: String): List<Int> = cells(delimiter).map { c ->
        val left = c.startsWith(":")
        val right = c.endsWith(":")
        when {
            left && right -> ALIGN_CENTER
            right -> ALIGN_RIGHT
            else -> ALIGN_LEFT
        }
    }

    private fun emitTable(
        out: SpannableStringBuilder,
        rows: List<List<String>>,
        aligns: List<Int>,
    ) {
        val cols = rows.maxOf { it.size }

        // Render each cell first: a cell's padded width depends on its RENDERED length,
        // not its source length, or "**Name**" would pad four characters too wide.
        val grid = rows.map { row ->
            (0 until cols).map { c ->
                SpannableStringBuilder().also { inline(it, row.getOrElse(c) { "" }, 0) }
            }
        }
        val widths = IntArray(cols) { c -> grid.maxOf { it[c].length } }

        val start = out.length
        for ((rowIndex, row) in grid.withIndex()) {
            if (rowIndex > 0) out.append("\n")
            val rowStart = out.length
            for (c in 0 until cols) {
                if (c > 0) out.append(" │ ")
                pad(out, row[c], widths[c], aligns.getOrElse(c) { ALIGN_LEFT }, c == cols - 1)
            }
            if (rowIndex == 0) {
                out.setSpan(StyleSpan(Typeface.BOLD), rowStart, out.length, SPAN)
                out.append("\n")
                out.append(widths.joinToString("─┼─") { "─".repeat(it) })
            }
        }
        out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
    }

    /** Trailing padding on the final column is invisible, so [last] suppresses it. */
    private fun pad(
        out: SpannableStringBuilder,
        cell: CharSequence,
        width: Int,
        align: Int,
        last: Boolean,
    ) {
        val slack = width - cell.length
        val lead = when (align) {
            ALIGN_RIGHT -> slack
            ALIGN_CENTER -> slack / 2
            else -> 0
        }
        out.append(" ".repeat(lead))
        out.append(cell)
        if (!last) out.append(" ".repeat(slack - lead))
    }

    private fun styleFence(out: SpannableStringBuilder, start: Int, end: Int) {
        if (end <= start) return
        out.setSpan(TypefaceSpan("monospace"), start, end, SPAN)
        out.setSpan(LeadingMarginSpan.Standard(24), start, end, SPAN)
    }

    private fun emitBlock(out: SpannableStringBuilder, line: String) {
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        val bare = line.trim()

        // A line of nothing but one repeated marker. Only dashes are a rule: "***" and "___"
        // are the leading edge of a bold word that is still arriving, so they stay literal.
        if (bare.length >= 3 && bare.toSet().size == 1 &&
            bare[0].let { it == '-' || it == '*' || it == '_' }
        ) {
            if (bare[0] == '-') out.append("─".repeat(12)) else out.append(bare)
            return
        }

        // Heading.
        var hashes = 0
        while (hashes < trimmed.length && trimmed[hashes] == '#') hashes++
        if (hashes in 1..6 && hashes < trimmed.length && trimmed[hashes] == ' ') {
            val start = out.length
            inline(out, trimmed.substring(hashes + 1), 0)
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
            val scale = when (hashes) {
                1 -> 1.30f
                2 -> 1.15f
                else -> 1.05f
            }
            out.setSpan(RelativeSizeSpan(scale), start, out.length, SPAN)
            return
        }

        // Blockquote.
        if (trimmed.startsWith("> ") || trimmed == ">") {
            val start = out.length
            out.append("│ ")
            inline(out, trimmed.removePrefix(">").removePrefix(" "), 0)
            out.setSpan(LeadingMarginSpan.Standard(16), start, out.length, SPAN)
            return
        }

        // Bullet.
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            val start = out.length
            out.append(" ".repeat(indent)).append("• ")
            inline(out, trimmed.substring(2), 0)
            // The hanging indent grows with nesting depth, so a wrapped nested item lines up
            // under its own text rather than under the outer list.
            out.setSpan(LeadingMarginSpan.Standard(0, 16 + indent * 8), start, out.length, SPAN)
            return
        }

        // Ordered list: keep the author's own numbering rather than renumbering.
        val dot = trimmed.indexOf(". ")
        if (dot in 1..3 && trimmed.substring(0, dot).all { it.isDigit() }) {
            val start = out.length
            out.append(" ".repeat(indent)).append(trimmed.substring(0, dot + 2))
            inline(out, trimmed.substring(dot + 2), 0)
            out.setSpan(LeadingMarginSpan.Standard(0, 16), start, out.length, SPAN)
            return
        }

        inline(out, line, 0)
    }

    private fun inline(out: SpannableStringBuilder, s: String, depth: Int) {
        if (depth > MAX_DEPTH) {
            out.append(s)
            return
        }

        // Rule 3. Without these, a line of "[[[[..." rescans to the end of the line for every
        // bracket. Once we know there is no usable ']' ahead, we never look again.
        var noCloseAhead = false
        var knownBadClose = -1

        // The same rule for emphasis. Whether a marker can CLOSE depends only on the characters
        // around it, never on where the opener was, so once findCloser reports nothing usable
        // ahead for a needle, that stays true for every later opener of the same needle. A
        // line of "**a " repeated was quadratic without this.
        val noCloser = HashSet<String>(4)

        var i = 0
        while (i < s.length) {
            val c = s[i]

            // `code` — first, so markers inside code stay literal.
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    val start = out.length
                    out.append(s, i + 1, end)
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
                    i = end + 1
                    continue
                }
            }

            // [label](target) — the label is shown, the target is not.
            if (c == '[' && !noCloseAhead) {
                val close = if (knownBadClose > i) knownBadClose else s.indexOf(']', i + 1)
                if (close < 0) {
                    noCloseAhead = true
                } else if (close - i <= MAX_LINK_LABEL &&
                    close + 1 < s.length && s[close + 1] == '('
                ) {
                    val paren = closingParen(s, close + 2)
                    if (paren > close + 1) {
                        val start = out.length
                        inline(out, s.substring(i + 1, close), depth + 1)
                        out.setSpan(UnderlineSpan(), start, out.length, SPAN)
                        i = paren + 1
                        continue
                    }
                    knownBadClose = close
                } else {
                    knownBadClose = close
                }
            }

            // ~~strikethrough~~
            if (c == '~' && i + 1 < s.length && s[i + 1] == '~' && "~~" !in noCloser) {
                val end = findCloser(s, i + 2, "~~")
                if (end < 0) noCloser += "~~"
                if (end > i + 2) {
                    val start = out.length
                    inline(out, s.substring(i + 2, end), depth + 1)
                    out.setSpan(StrikethroughSpan(), start, out.length, SPAN)
                    i = end + 2
                    continue
                }
            }

            if (c == '*' || c == '_') {
                // Longest run first: *** is bold+italic, ** is bold, * is italic. Matching the
                // short form first would let a "**" opener close against its own second char.
                val run = runLength(s, i, c)
                var matched = false
                for (n in run downTo 1) {
                    if (!opens(s, i, c, n)) continue
                    val needle = s.substring(i, i + n)
                    if (needle in noCloser) continue
                    val end = findCloser(s, i + n, needle)
                    if (end < 0) { noCloser += needle; continue }
                    if (end <= i + n) continue
                    val start = out.length
                    inline(out, s.substring(i + n, end), depth + 1)
                    when (n) {
                        1 -> out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, SPAN)
                        2 -> out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                        else -> {
                            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                            out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, SPAN)
                        }
                    }
                    i = end + n
                    matched = true
                    break
                }
                if (matched) continue
            }

            out.append(c)
            i++
        }
    }

    private fun runLength(s: String, i: Int, c: Char): Int {
        var n = 0
        while (n < 3 && i + n < s.length && s[i + n] == c) n++
        return n
    }

    /**
     * Can a run of [n] copies of [c] at [i] OPEN emphasis?
     *
     * Whitespace after the marker means it is not emphasis at all — "5 * 3 * 2" is arithmetic,
     * not an italic 3. An underscore additionally has to sit on a word boundary, or every
     * snake_case identifier an LLM emits would come out italicised.
     */
    private fun opens(s: String, i: Int, c: Char, n: Int): Boolean {
        val next = if (i + n < s.length) s[i + n] else ' '
        if (next.isWhitespace()) return false
        if (c == '_') {
            val before = if (i == 0) ' ' else s[i - 1]
            if (before.isLetterOrDigit() || before == '_') return false
        }
        return true
    }

    /**
     * The first [needle] at or after [from] that can CLOSE emphasis: not preceded by
     * whitespace, and for an underscore, not glued to a following word character. The
     * second condition is what stops "_the value in max_retries" from swallowing the
     * underscore in max_retries and joining the two words.
     */
    private fun findCloser(s: String, from: Int, needle: String): Int {
        var j = from
        while (j < s.length) {
            val at = s.indexOf(needle, j)
            if (at < 0) return -1
            val before = if (at == 0) ' ' else s[at - 1]
            val afterAt = at + needle.length
            val after = if (afterAt < s.length) s[afterAt] else ' '
            val glued = needle[0] == '_' && (after.isLetterOrDigit() || after == '_')
            if (!before.isWhitespace() && !glued) return at
            j = at + needle.length
        }
        return -1
    }

    /** Balances nested parentheses so a target like `.../f(1)` is not cut at the inner `)`. */
    private fun closingParen(s: String, from: Int): Int {
        var depth = 1
        var k = from
        val limit = minOf(s.length, from + MAX_LINK_TARGET)
        while (k < limit) {
            when (s[k]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return k
                }
            }
            k++
        }
        return -1
    }
}
