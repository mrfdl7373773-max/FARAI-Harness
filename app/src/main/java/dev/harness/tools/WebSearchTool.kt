package dev.harness.tools

import dev.harness.core.search.SearchConfig
import dev.harness.core.search.SearchRequest
import dev.harness.core.search.SearchResponse
import dev.harness.core.search.WebSearch
import dev.harness.core.web.PageReader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Searches the web with the built-in metasearch (DuckDuckGo, Brave, Bing, Yahoo, Wikipedia, and
 * Google/Bing News for news), or the user's own endpoint from prompts/search.md.
 */
class WebSearchTool(private val configSource: () -> String? = { null }) : Tool {
    override val name = "web_search"
    override val promptPath = "tools/web_search.md"
    override val readOnly = true
    override val parameters: JsonObject = schema {
        string("query", required = true)
        integer("max_results")
        string("type", enum = listOf("web", "news"))
        string("time_range", enum = listOf("day", "week", "month", "year"))
        string("region")
        array("include_domains", buildJsonObject { put("type", "string") })
        array("exclude_domains", buildJsonObject { put("type", "string") })
        integer("page")
        integer("read")
    }
    override val fallbackDescription: String
        get() = "Search the web. Returns ranked results (title, URL, snippet) merged from several engines. Follow up with web_fetch to read a page."

    private var search: WebSearch? = null

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        dev.harness.net.Connectivity.awaitOnline() // paused while offline, carries on when the connection is back
        val query = args.reqStr("query").trim()
        if (query.isEmpty()) return ToolResult.error("query is empty")
        val config = runCatching { SearchConfig.parse(configSource()) }.getOrDefault(SearchConfig())
        val req = SearchRequest(
            query = query.take(400),
            maxResults = (args.int("max_results") ?: config.maxResults).coerceIn(SearchConfig.MIN_RESULTS, SearchConfig.MAX_RESULTS),
            region = args.str("region")?.ifBlank { null } ?: config.region,
            safesearch = config.safesearch,
            timeRange = args.str("time_range")?.takeIf { it in setOf("day", "week", "month", "year") },
            type = if (args.str("type") == "news") "news" else "web",
            includeDomains = args.strings("include_domains"),
            excludeDomains = args.strings("exclude_domains"),
            page = (args.int("page") ?: 1).coerceIn(1, 10),
        )
        ctx.permission.check("web_search", listOf(query), "Search the web for \u201c$query\u201d?", query, listOf(query))
            ?.let { return ToolResult.error(it) }
        val engine = search ?: WebSearch(ctx.http).also { search = it }
        val res = try { engine.search(req, config) } catch (e: Exception) {
            return ToolResult.error("web search failed: ${e.message ?: e.javaClass.simpleName}")
        }
        if (res.results.isEmpty()) {
            val why = res.engines.filter { !it.ok }.joinToString("; ") { "${it.name}: ${it.error}" }
            return ToolResult.error("no results for \u201c$query\u201d" + if (why.isNotBlank()) " ($why)" else "" +
                ". Try different words, fewer quotes, or type=news for current events.")
        }
        val readDefault = if (args.str("type") == "news") PageReader.NEWS_DEFAULT_READ else 0
        val readN = (args.int("read") ?: readDefault).coerceIn(0, PageReader.MAX_URLS).coerceAtMost(res.results.size)
        if (readN == 0) return ToolResult(format(res, req))
        val r = reader ?: PageReader(ctx.http).also { reader = it }
        val pages = r.readAll(res.results.take(readN).map { it.url }, maxChars = PageReader.perPageChars(readN))
        val resolved = pages.filter { it.ok }.associate { it.requested to it.url }
        val shown = res.copy(results = res.results.map { h -> resolved[h.url]?.let { h.copy(url = it) } ?: h })
        return ToolResult(format(shown, req) + formatPages(pages))
    }

    private var reader: PageReader? = null

    private fun JsonObject.strings(key: String): List<String> = when (val v = this[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null } }
        is JsonPrimitive -> v.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        else -> emptyList()
    }

    companion object {
        /**
         * The text the model reads. The transcript parses the same text to draw the search trace,
         * so keep this shape: a header line, `Engines:`, optional `Answer:`, then numbered hits.
         */
        fun format(res: SearchResponse, req: SearchRequest): String = buildString {
            val secs = "%.1f".format(java.util.Locale.US, res.elapsedMs / 1000.0)
            append("Web search: \u201c").append(res.query).append("\u201d · ").append(res.results.size).append(" results")
            if (req.type == "news") append(" · news")
            req.timeRange?.let { append(" · past ").append(it) }
            append(if (res.cached) " · cached" else " · ${secs}s").append('\n')
            if (res.engines.isNotEmpty()) {
                append("Engines: ")
                append(res.engines.joinToString(" · ") { e -> if (e.ok) "${e.name} \u2713${e.results}" else "${e.name} \u2717 ${e.error ?: "failed"}" })
                append('\n')
            }
            res.answer?.let { append("Answer: ").append(it.replace('\n', ' ').take(700)).append('\n') }
            append('\n')
            res.results.forEachIndexed { i, h ->
                append(i + 1).append(". ").append(h.title.replace('\n', ' ')).append('\n')
                append("   ").append(h.url).append('\n')
                if (h.content.isNotBlank()) append("   ").append(h.content.replace('\n', ' ').take(400)).append('\n')
                val meta = listOfNotNull(
                    h.engines.takeIf { it.isNotEmpty() }?.joinToString(", ", "[", "]"),
                    h.source?.takeIf { it.isNotBlank() },
                    h.publishedDate?.takeIf { it.isNotBlank() },
                )
                if (meta.isNotEmpty()) append("   ").append(meta.joinToString(" · ")).append('\n')
            }
            append("\nRead the pages that matter with web_fetch before relying on them; snippets can be cut or stale.")
        }

        /** Marks where the read pages start; the trace parses hits only above it. */
        const val PAGES_MARK = "\n\n=== Page contents ==="

        fun formatPages(pages: List<PageReader.Page>): String = buildString {
            append(PAGES_MARK).append(" (").append(pages.count { it.ok }).append(" of ").append(pages.size).append(" top results, read in parallel)\n\n")
            pages.forEachIndexed { i, p ->
                if (i > 0) append("\n\n---\n\n")
                append(WebTool.formatPage(p))
            }
        }
    }
}
