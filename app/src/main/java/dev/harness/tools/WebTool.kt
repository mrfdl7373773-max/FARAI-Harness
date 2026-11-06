package dev.harness.tools

import dev.harness.core.web.PageReader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reads one page or many at once. Pages are fetched in parallel with a short deadline each,
 * aggregator links (Google News, Bing, DuckDuckGo) are unwrapped first, and HTML is cut down to
 * the article itself unless `format` asks for the whole page.
 */
class WebTool : Tool {
    override val name = "web_fetch"
    override val promptPath = "tools/web.md"
    override val readOnly = true
    override val parameters: JsonObject = schema {
        string("url")
        array("urls", buildJsonObject { put("type", "string") })
        string("format", enum = listOf("article", "full", "text", "html"))
        integer("max_chars")
    }
    override val fallbackDescription: String
        get() = "Read web pages. Pass `urls` (up to 25) to read them all in parallel; returns each page's main text."

    private var reader: PageReader? = null

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        dev.harness.net.Connectivity.awaitOnline() // paused while offline, carries on when the connection is back
        val raw = buildList {
            args.str("url")?.let { add(it) }
            when (val v = args["urls"]) {
                is JsonArray -> v.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(::add) }
                is JsonPrimitive -> v.contentOrNull?.split(',', ' ', '\n')?.let(::addAll)
                else -> {}
            }
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (raw.isEmpty()) return ToolResult.error("give `url` or `urls`")
        val bad = raw.filter { it.toHttpUrlOrNull() == null }
        if (bad.isNotEmpty()) return ToolResult.error("invalid URL: ${bad.joinToString(", ")}")
        val urls = raw.take(PageReader.MAX_URLS)
        val dropped = raw.size - urls.size
        val many = urls.size > 1
        val format = args.str("format") ?: "article"
        val defaultMax = PageReader.perPageChars(urls.size)
        val max = (args.int("max_chars") ?: defaultMax).coerceIn(500, 200_000)
        val hosts = urls.map { it.toHttpUrlOrNull()!!.host }.distinct()
        ctx.permission.check(
            "web_fetch", hosts,
            if (many) "Read ${urls.size} pages?" else "Fetch ${hosts.first()}?",
            urls.joinToString("\n"), hosts,
        )?.let { return ToolResult.error(it) }

        val mode = when (format) { "full", "text" -> PageReader.Mode.FULL; "html" -> PageReader.Mode.HTML; else -> PageReader.Mode.ARTICLE }
        val r = reader ?: PageReader(ctx.http).also { reader = it }
        val pages = r.readAll(urls, max, mode)
        if (pages.none { it.ok }) {
            return ToolResult.error(pages.joinToString("\n") { "${it.requested}: ${it.error}" } +
                "\nTry another source from the search results instead of retrying the same URL.")
        }
        val out = buildString {
            if (many) append("Read ${pages.count { it.ok }} of ${pages.size} pages in parallel\n\n")
            pages.forEachIndexed { i, p ->
                if (i > 0) append("\n\n---\n\n")
                append(formatPage(p, plain = format == "text"))
            }
            if (dropped > 0) append("\n\n($dropped more URLs skipped: at most ${PageReader.MAX_URLS} per call.)")
        }
        return ToolResult(out)
    }

    companion object {
        /** One page as the model reads it. The transcript reads `# title` and `URL:` back for its rows. */
        fun formatPage(p: PageReader.Page, plain: Boolean = false): String = buildString {
            if (!p.ok) {
                append("# \u2717 ").append(PageReader.hostOf(p.requested)).append('\n')
                append("URL: ").append(p.requested).append('\n')
                append("Error: ").append(p.error)
                return@buildString
            }
            append("# ").append(p.title.replace('\n', ' ')).append('\n')
            append("URL: ").append(p.url).append('\n')
            val meta = listOfNotNull(
                p.site, p.published?.let { "published $it" }, "${p.words} words",
                "%.1fs".format(java.util.Locale.US, p.ms / 1000.0), p.via?.let { "via $it" },
            )
            append(meta.joinToString(" · ")).append("\n\n")
            val body = if (plain) p.text.replace(Regex("(?m)^#+ "), "").replace("**", "") else p.text
            append(body.ifBlank { "(no readable text on this page)" })
            if (p.truncated > 0) append("\n\n\u2026 [cut, ${p.truncated} more characters; raise max_chars or fetch this URL alone for all of it]")
        }
    }
}

/** Lightweight HTML → readable text conversion. */
object HtmlText {
    fun toText(html: String, baseUrl: String = ""): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|head|template)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?is)<a\\s[^>]*href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>")) { m ->
            val href = m.groupValues[1]
            val label = m.groupValues[2].replace(Regex("(?s)<[^>]+>"), "").trim()
            val abs = absolutize(href, baseUrl)
            if (label.isEmpty()) "" else if (abs.isEmpty() || abs.startsWith("javascript:") || abs.startsWith("#")) label else "$label ($abs)"
        }
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)<li[^>]*>"), "\n- ")
        s = s.replace(Regex("(?i)<h([1-6])[^>]*>")) { "\n\n" + "#".repeat(it.groupValues[1].toInt()) + " " }
        s = s.replace(Regex("(?i)</(p|div|section|article|header|footer|h[1-6]|ul|ol|table|tr|pre|blockquote)>"), "\n")
        s = s.replace(Regex("(?i)<(p|div|section|article|tr|pre|blockquote)[^>]*>"), "\n")
        s = s.replace(Regex("(?i)</t[dh]>"), "\t")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = decodeEntities(s)
        return s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .fold(mutableListOf<String>()) { acc, line ->
                if (!(line.isEmpty() && acc.lastOrNull().isNullOrEmpty())) acc += line
                acc
            }.joinToString("\n").trim()
    }

    private fun absolutize(href: String, base: String): String {
        if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) return href
        val b = base.toHttpUrlOrNull() ?: return href
        return b.resolve(href)?.toString() ?: href
    }

    fun decodeEntities(s: String): String {
        val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "mdash" to "—", "ndash" to "–", "hellip" to "…", "copy" to "©", "reg" to "®", "rsquo" to "’", "lsquo" to "‘",
            "rdquo" to "”", "ldquo" to "“", "bull" to "•", "middot" to "·")
        return s.replace(Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")) { m ->
            val v = m.groupValues[1]
            when {
                v.startsWith("#x") -> v.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                v.startsWith("#") -> v.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> named[v.lowercase()] ?: m.value
            }
        }
    }
}
