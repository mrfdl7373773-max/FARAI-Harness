package dev.harness.core.search

/**
 * Web search:
 * several public search engines are queried in parallel, straight from the phone, and their
 * results merged with Reciprocal Rank Fusion. No API key, no server.
 */
data class SearchRequest(
    val query: String,
    val maxResults: Int = 8,
    /** `<country>-<lang>`, e.g. `us-en`, `id-id`. */
    val region: String = "us-en",
    val safesearch: String = "moderate",
    /** null, `day`, `week`, `month` or `year`. */
    val timeRange: String? = null,
    /** `web` or `news`. */
    val type: String = "web",
    val includeDomains: List<String> = emptyList(),
    val excludeDomains: List<String> = emptyList(),
    val page: Int = 1,
)

data class SearchHit(
    val title: String,
    val url: String,
    val content: String = "",
    val publishedDate: String? = null,
    val source: String? = null,
    val engine: String = "",
    val engines: List<String> = emptyList(),
    val score: Double = 0.0,
)

data class EngineStatus(val name: String, val ok: Boolean, val results: Int = 0, val elapsedMs: Long = 0, val error: String? = null)

data class SearchResponse(
    val query: String,
    val results: List<SearchHit>,
    val answer: String? = null,
    val engines: List<EngineStatus> = emptyList(),
    val elapsedMs: Long = 0,
    val cached: Boolean = false,
    /** Where the results came from: `farai` (built-in engines) or the custom endpoint's kind. */
    val via: String = "farai",
)

class EngineBlocked(message: String) : Exception(message)
class EngineError(message: String) : Exception(message)

/** `us-en` → (us, en). Accepts `en-US` and a bare `en` too. */
fun splitRegion(region: String): Pair<String, String> {
    val parts = region.lowercase().replace('_', '-').split('-')
    if (parts.size == 1) return "us" to (parts[0].ifBlank { "en" })
    val (a, b) = parts[0] to parts[1]
    val langs = setOf("en", "de", "fr", "es", "it", "pt", "nl", "ru", "ja", "zh", "ko", "hi", "ar", "pl", "tr", "sv", "id", "ms", "th", "vi")
    return if (a in langs && b !in langs) b to a else a to b
}
