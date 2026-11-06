package dev.harness.tools

/**
 * Finds `old_string` in a file the way a careful human would, in widening tiers, and only ever
 * accepts a unique answer from a fuzzy tier:
 *
 *  1. exact, on the LF view of the file (CRLF files match too and keep their endings)
 *  2. typographic variants folded: curly quotes, dashes, odd spaces (1:1, offsets survive)
 *  3. line by line, ignoring trailing whitespace
 *  4. line by line, ignoring indentation; the replacement is re-indented to fit
 *  5. line by line, ignoring all runs of whitespace inside lines
 *
 * When nothing matches it explains why: the new text is already there, or here are the closest
 * lines (numbered like `read` output), so the next attempt can succeed without re-reading.
 */
object TextMatch {

    data class Span(val start: Int, val end: Int)

    data class Found(
        val spans: List<Span>,
        /** Which tier matched: "exact", "unicode", "trailing-whitespace", "indentation", "whitespace". */
        val tier: String,
        /** Builds the replacement for one span (handles re-indent for tier 4). */
        val replacement: (Span) -> String,
    )

    sealed interface Result {
        data class Ok(val text: String, val count: Int, val tier: String, val firstLine: Int) : Result
        data class Fail(val message: String) : Result
    }

    fun normalizeChars(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(
            when (c) {
                '\u2018', '\u2019', '\u201A', '\u201B' -> '\''
                '\u201C', '\u201D', '\u201E', '\u201F' -> '"'
                '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-'
                '\u00A0', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A', '\u202F', '\u205F', '\u3000' -> ' '
                else -> c
            }
        )
        return sb.toString()
    }

    private fun occurrences(hay: String, needle: String): List<Span> {
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Span>()
        var i = hay.indexOf(needle)
        while (i >= 0) { out += Span(i, i + needle.length); i = hay.indexOf(needle, i + needle.length) }
        return out
    }

    private class Line(val start: Int, val end: Int, val text: String)

    private fun lines(view: String): List<Line> {
        val out = ArrayList<Line>()
        var s = 0
        while (s <= view.length) {
            val nl = view.indexOf('\n', s)
            if (nl < 0) { if (s < view.length) out += Line(s, view.length, view.substring(s)); break }
            out += Line(s, nl + 1, view.substring(s, nl))
            s = nl + 1
        }
        return out
    }

    private fun lineMatches(view: String, search: String, key: (String) -> String): List<Pair<Span, Int>> {
        val trailingNl = search.endsWith("\n")
        val expected = search.split("\n").let { if (trailingNl) it.dropLast(1) else it }
        if (expected.isEmpty() || expected.all { key(it).isEmpty() }) return emptyList()
        val ls = lines(view)
        val out = ArrayList<Pair<Span, Int>>()
        var i = 0
        while (i + expected.size <= ls.size) {
            var ok = true
            for (k in expected.indices) if (key(ls[i + k].text) != key(expected[k])) { ok = false; break }
            if (ok) {
                val last = ls[i + expected.size - 1]
                val end = if (trailingNl) last.end else last.start + last.text.length
                out += Span(ls[i].start, end) to i
                i += expected.size
            } else i++
        }
        return out
    }

    private fun indentOf(s: String) = s.takeWhile { it == ' ' || it == '\t' }

    /** Re-indents [replacement] by the difference between how [search] and the matched region start. */
    private fun reindent(region: String, search: String, replacement: String): String {
        val regionIndent = region.lines().firstOrNull { it.isNotBlank() }?.let(::indentOf) ?: ""
        val searchIndent = search.lines().firstOrNull { it.isNotBlank() }?.let(::indentOf) ?: ""
        if (regionIndent == searchIndent) return replacement
        return replacement.split("\n").joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith(searchIndent) -> regionIndent + line.removePrefix(searchIndent)
                else -> regionIndent + line.trimStart()
            }
        }
    }

    fun find(view: String, search: String, replacement: String): Found? {
        val exact = occurrences(view, search)
        if (exact.isNotEmpty()) return Found(exact, "exact") { replacement }
        val uni = occurrences(normalizeChars(view), normalizeChars(search))
        if (uni.isNotEmpty()) return Found(uni, "unicode") { replacement }
        val trailing = lineMatches(view, search) { normalizeChars(it).trimEnd() }
        if (trailing.isNotEmpty()) return Found(trailing.map { it.first }, "trailing-whitespace") { replacement }
        val indent = lineMatches(view, search) { normalizeChars(it).trim() }
        if (indent.size == 1) return Found(indent.map { it.first }, "indentation") { span ->
            reindent(view.substring(span.start, span.end), search, replacement)
        }
        val ws = lineMatches(view, search) { normalizeChars(it).trim().replace(Regex("\\s+"), " ") }
        if (ws.size == 1) return Found(ws.map { it.first }, "whitespace") { span ->
            reindent(view.substring(span.start, span.end), search, replacement)
        }
        return null
    }

    /**
     * Applies one replacement to [source] (which may use CRLF). Returns the new text or a failure
     * message that helps the model fix its next call.
     */
    fun replace(source: String, oldString: String, newString: String, replaceAll: Boolean, displayPath: String): Result {
        if (oldString.isEmpty()) return Result.Fail("old_string is empty. Use write to create or overwrite a file.")
        if (oldString == newString) return Result.Fail("old_string and new_string are identical, so there is nothing to change.")
        val crlf = source.count { it == '\n' } > 0 && source.split("\r\n").size - 1 > (source.count { it == '\n' } / 2)
        val view = source.replace("\r\n", "\n")
        val search = oldString.replace("\r\n", "\n")
        val repl = newString.replace("\r\n", "\n")
        val found = find(view, search, repl)
            ?: return Result.Fail("old_string was not found in $displayPath. It must match the file exactly, including indentation." +
                (missHint(view, search, repl)?.let { "\n\n$it" } ?: ""))
        if (found.spans.size > 1 && !replaceAll) {
            val linesAt = found.spans.take(6).joinToString(", ") { s -> (view.substring(0, s.start).count { it == '\n' } + 1).toString() }
            return Result.Fail("old_string matches ${found.spans.size} places in $displayPath (lines $linesAt). " +
                "Include more surrounding lines so it is unique, or set replace_all to true to change every one.")
        }
        val targets = if (replaceAll) found.spans else found.spans.take(1)
        val sb = StringBuilder(view)
        for (span in targets.sortedByDescending { it.start }) sb.replace(span.start, span.end, found.replacement(span))
        var out = sb.toString()
        if (out == view) return Result.Fail("the edit leaves $displayPath unchanged.")
        if (crlf) out = out.replace("\n", "\r\n")
        val firstLine = view.substring(0, targets.minOf { it.start }).count { it == '\n' } + 1
        return Result.Ok(out, targets.size, found.tier, firstLine)
    }

    private const val HINT_LINES = 12

    /** "Already applied" or the closest lines, numbered like read output. */
    fun missHint(view: String, search: String, replacement: String): String? {
        if (replacement.trim().length >= 20 && normalizeChars(view).contains(normalizeChars(replacement)))
            return "new_string is already in the file, so this edit may already have been applied. Read the file to check."
        fun key(s: String) = normalizeChars(s).replace(Regex("\\s+"), " ").trim()
        fun distinct(s: String) = s.any { it.isLetterOrDigit() }
        val fileLines = view.split("\n")
        val wanted = search.split("\n")
        val positions = HashMap<String, MutableList<Int>>()
        fileLines.forEachIndexed { i, l -> val k = key(l); if (distinct(k)) positions.getOrPut(k) { ArrayList() } += i }
        val votes = HashMap<Int, Int>()
        var counted = 0
        wanted.forEachIndexed { off, l ->
            val k = key(l)
            if (!distinct(k)) return@forEachIndexed
            counted++
            positions[k]?.forEach { idx -> votes.merge(idx - off, 1, Int::plus) }
        }
        val best = votes.maxByOrNull { it.value } ?: return null
        if (best.value * 2 < counted) return null
        val from = best.key.coerceAtLeast(0)
        val to = minOf(fileLines.size, from + maxOf(wanted.size, 1) + 2, from + HINT_LINES)
        return "The closest lines in the file:\n" + (from until to).joinToString("\n") { i ->
            val l = fileLines[i]
            "${i + 1}: ${if (l.length > 200) l.take(200) + "…" else l}"
        }
    }
}
