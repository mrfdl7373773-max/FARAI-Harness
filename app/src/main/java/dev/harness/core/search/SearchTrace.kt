package dev.harness.core.search

/** What the transcript shows for one `web_search` call, read back from the tool's text output. */
data class SearchTrace(
    val query: String,
    val sources: List<Source>,
    val engines: List<EngineStatus>,
    val answer: String?,
    val seconds: String?,
    val cached: Boolean,
    val news: Boolean,
    /** Pages read in the same call (`web_search` with `read`). */
    val pages: List<FetchedPage> = emptyList(),
) {
    data class Source(val title: String, val url: String, val domain: String, val date: String?)
    data class FetchedPage(val title: String, val url: String, val domain: String, val error: Boolean)

    companion object {
        private val HIT = Regex("(?m)^(\\d+)\\. (.+)\\n {3}(https?://\\S+)(?:\\n {3}(?!https?://)(.*))?(?:\\n {3}(\\[.*))?")
        private val ENGINE_OK = Regex("^([a-z_]+) \u2713(\\d+)$")
        private val ENGINE_BAD = Regex("^([a-z_]+) \u2717 ?(.*)$")

        fun parse(query: String, output: String?): SearchTrace {
            val full = output.orEmpty()
            val cut = full.indexOf("=== Page contents ===")
            val text = if (cut >= 0) full.substring(0, cut) else full
            val header = text.lineSequence().firstOrNull { it.startsWith("Web search:") }.orEmpty()
            val engines = text.lineSequence().firstOrNull { it.startsWith("Engines: ") }?.removePrefix("Engines: ")
                ?.split(" · ")?.mapNotNull { part ->
                    ENGINE_OK.find(part.trim())?.let { EngineStatus(it.groupValues[1], true, it.groupValues[2].toIntOrNull() ?: 0) }
                        ?: ENGINE_BAD.find(part.trim())?.let { EngineStatus(it.groupValues[1], false, error = it.groupValues[2]) }
                }.orEmpty()
            val sources = HIT.findAll(text).map { m ->
                val url = m.groupValues[3]
                val meta = listOf(m.groupValues[5], m.groupValues[4]).firstOrNull { it.startsWith("[") }.orEmpty()
                val date = meta.split(" · ").drop(1).lastOrNull()?.takeIf { Regex("\\d{4}|ago").containsMatchIn(it) }
                Source(m.groupValues[2].trim(), url, Ranking.domainOf(url), date)
            }.toList()
            val answer = text.lineSequence().firstOrNull { it.startsWith("Answer: ") }?.removePrefix("Answer: ")
            val secs = Regex(" · ([0-9.]+)s$").find(header)?.groupValues?.get(1)
            val pages = if (cut >= 0) fetchedPages(full.substring(cut)) else emptyList()
            return SearchTrace(query, sources, engines, answer, secs, header.endsWith("· cached"), " · news" in header, pages)
        }

        /** The page title a `web_fetch` result names in its first heading, if any. */
        fun fetchedTitle(output: String?): String? = output.orEmpty().lineSequence().take(80)
            .firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.take(120)?.ifBlank { null }

        private val PAGE = Regex("(?m)^# (.+)\\nURL: (\\S+)")

        /** Every page a `web_fetch` (or a reading `web_search`) result holds, in order. */
        fun fetchedPages(output: String?): List<FetchedPage> = PAGE.findAll(output.orEmpty()).map { m ->
            val t = m.groupValues[1].trim()
            val err = t.startsWith("\u2717 ")
            FetchedPage(t.removePrefix("\u2717 ").take(120), m.groupValues[2], Ranking.domainOf(m.groupValues[2]), err)
        }.toList()

        /** Pretty engine names for the trace footer. */
        fun engineLabel(name: String): String = when (name) {
            "duckduckgo" -> "DuckDuckGo"
            "bing" -> "Bing"
            "brave" -> "Brave"
            "yahoo" -> "Yahoo"
            "wikipedia" -> "Wikipedia"
            "mojeek" -> "Mojeek"
            "google_news" -> "Google News"
            "bing_news" -> "Bing News"
            "json" -> "JSON endpoint"
            "searxng" -> "SearXNG"
            "tavily" -> "Tavily"
            else -> name.replaceFirstChar { it.uppercase() }
        }
    }
}
