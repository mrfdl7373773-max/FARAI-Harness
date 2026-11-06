package dev.harness.core.search

import dev.harness.net.HarnessJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Settings from prompts/search.md (its ```json block). Every key is optional. */
data class SearchConfig(
    val engines: List<String> = Engines.DEFAULT_WEB,
    val newsEngines: List<String> = Engines.DEFAULT_NEWS,
    val region: String = "us-en",
    val safesearch: String = "moderate",
    val maxResults: Int = 8,
    val timeoutMs: Long = 8_000,
    /** `full` waits for every engine and merges; `fast` returns once one engine has enough. */
    val mode: String = "full",
    val answer: Boolean = true,
    val cacheMinutes: Int = 10,
    /** Optional own server: a generic JSON search API, SearXNG or Tavily endpoint. Empty = built-in engines only. */
    val endpoint: String = "",
    /** `json`, `searxng` or `tavily`. */
    val endpointType: String = "json",
    val apiKey: String = "",
    /** When the endpoint fails, fall back to the built-in engines. */
    val fallback: Boolean = true,
) {
    companion object {
        /** Bounds for results per search (Farrel, 2026-11-06: at least 5, at most 25). */
        const val MIN_RESULTS = 5
        const val MAX_RESULTS = 25

        fun parse(markdown: String?): SearchConfig {
            if (markdown.isNullOrBlank()) return SearchConfig()
            val o = dev.harness.core.mcp.McpConfig.parseJsonBlocks(markdown).firstOrNull() ?: return SearchConfig()
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun list(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() }
                ?: s(k)?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            val d = SearchConfig()
            return SearchConfig(
                engines = list("engines")?.filter { Engines.byName(it)?.category == "web" }?.ifEmpty { null } ?: d.engines,
                newsEngines = list("news_engines")?.filter { Engines.byName(it)?.category == "news" }?.ifEmpty { null } ?: d.newsEngines,
                region = s("region")?.ifBlank { null } ?: d.region,
                safesearch = s("safesearch")?.takeIf { it in setOf("off", "moderate", "strict") } ?: d.safesearch,
                maxResults = (o["max_results"] as? JsonPrimitive)?.intOrNull?.coerceIn(MIN_RESULTS, MAX_RESULTS) ?: d.maxResults,
                timeoutMs = ((o["timeout_seconds"] as? JsonPrimitive)?.intOrNull?.coerceIn(2, 60)?.times(1000L)) ?: d.timeoutMs,
                mode = s("mode")?.takeIf { it == "fast" || it == "full" } ?: d.mode,
                answer = (o["answer"] as? JsonPrimitive)?.booleanOrNull ?: d.answer,
                cacheMinutes = (o["cache_minutes"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 1440) ?: d.cacheMinutes,
                endpoint = s("endpoint").orEmpty().trim(),
                endpointType = s("endpoint_type")?.lowercase()?.takeIf { it in setOf("json", "searxng", "tavily") } ?: d.endpointType,
                apiKey = s("api_key").orEmpty(),
                fallback = (o["fallback"] as? JsonPrimitive)?.booleanOrNull ?: d.fallback,
            )
        }
    }
}

/**
 * Runs a search across several engines at once and merges the results. Each engine has a
 * circuit breaker: one that blocks or keeps failing rests for a while and the others carry on,
 * so a single engine never breaks a search.
 */
class WebSearch(private val baseClient: OkHttpClient, private val now: () -> Long = System::currentTimeMillis) {

    private class Breaker { var failures = 0; var openUntil = 0L; var lastError: String? = null }
    private val breakers = ConcurrentHashMap<String, Breaker>()
    private val cache = object : LinkedHashMap<String, Pair<Long, SearchResponse>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, SearchResponse>>?) = size > 64
    }
    @Volatile private var uaIndex = 0

    private val client: OkHttpClient by lazy {
        baseClient.newBuilder().followRedirects(true).callTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    }

    /** Engines resting after a block or repeated failures, with seconds left. */
    fun cooling(): Map<String, Long> = breakers.filterValues { it.openUntil > now() }.mapValues { (it.value.openUntil - now()) / 1000 }

    suspend fun search(req: SearchRequest, config: SearchConfig = SearchConfig()): SearchResponse {
        val key = listOf(req, config.engines, config.newsEngines, config.endpoint).toString()
        if (config.cacheMinutes > 0) synchronized(cache) {
            cache[key]?.let { (at, res) -> if (now() - at < config.cacheMinutes * 60_000L) return res.copy(cached = true, elapsedMs = 0) }
        }
        val start = now()
        var endpointError: String? = null
        val res = if (config.endpoint.isNotBlank()) {
            runCatching { viaEndpoint(req, config) }.getOrElse { e ->
                endpointError = "${config.endpointType} endpoint: ${e.message ?: e.javaClass.simpleName}"
                if (!config.fallback) throw IOException(endpointError)
                null
            }
        } else null
        val out = (res ?: native(req, config)).let { r ->
            val endpointStatus = endpointError?.let { listOf(EngineStatus(config.endpointType, false, error = it)) }.orEmpty()
            r.copy(elapsedMs = now() - start, engines = endpointStatus + r.engines)
        }
        if (config.cacheMinutes > 0 && out.results.isNotEmpty()) synchronized(cache) { cache[key] = now() to out }
        return out
    }

    private suspend fun native(req: SearchRequest, config: SearchConfig): SearchResponse = coroutineScope {
        val names = if (req.type == "news") config.newsEngines else config.engines
        val engines = names.mapNotNull { Engines.byName(it) }
        val statuses = ArrayList<EngineStatus>()
        val byEngine = LinkedHashMap<String, List<SearchHit>>()
        val answer = if (config.answer && req.type == "web" && req.page == 1) async { runCatching { instantAnswer(req) }.getOrNull() } else null

        val ready = engines.filter { e ->
            val b = breakers[e.name]
            if (b != null && b.openUntil > now()) { statuses += EngineStatus(e.name, false, error = "resting (${b.lastError ?: "blocked"})"); false } else true
        }
        val channel = Channel<Triple<SearchEngine, Result<List<SearchHit>>, Long>>(Channel.UNLIMITED)
        val jobs = ready.map { e ->
            launch {
                val t0 = now()
                val r = withTimeoutOrNull(config.timeoutMs) { runCatching { runEngine(e, req) } } ?: Result.failure(EngineError("timed out"))
                channel.send(Triple(e, r, now() - t0))
            }
        }
        repeat(ready.size) {
            val (e, r, ms) = channel.receive()
            r.onSuccess { hits ->
                byEngine[e.name] = hits.take(e.maxResults)
                statuses += EngineStatus(e.name, true, hits.size, ms)
                breakers.remove(e.name)
            }.onFailure { err ->
                statuses += EngineStatus(e.name, false, 0, ms, err.message ?: err.javaClass.simpleName)
                trip(e.name, err)
            }
            if (config.mode == "fast" && byEngine.values.any { it.size >= req.maxResults }) {
                jobs.forEach { it.cancel() }
                return@coroutineScope finish(req, config, byEngine, statuses, answer?.await())
            }
        }
        finish(req, config, byEngine, statuses, answer?.await())
    }

    private fun finish(req: SearchRequest, config: SearchConfig, byEngine: Map<String, List<SearchHit>>, statuses: List<EngineStatus>, answer: String?): SearchResponse {
        val weights = Engines.all.associate { it.name to it.weight }
        val merged = Ranking.merge(byEngine, weights)
            .filter { req.includeDomains.isEmpty() || Ranking.domainMatches(it.url, req.includeDomains) }
            .filter { req.excludeDomains.isEmpty() || !Ranking.domainMatches(it.url, req.excludeDomains) }
            .take(req.maxResults)
        val order = (if (req.type == "news") config.newsEngines else config.engines)
        return SearchResponse(req.query, merged, answer, statuses.sortedBy { s -> order.indexOf(s.name).let { if (it < 0) 99 else it } })
    }

    private fun trip(name: String, err: Throwable) {
        val b = breakers.getOrPut(name) { Breaker() }
        b.failures++
        b.lastError = if (err is EngineBlocked) "blocked" else err.message
        // A block page means this IP is flagged for a while; plain errors get a few tries first.
        b.openUntil = when {
            err is EngineBlocked -> now() + minOf(30, 5 * b.failures) * 60_000L
            b.failures >= 3 -> now() + 2 * 60_000L
            else -> 0L
        }
    }

    private suspend fun runEngine(e: SearchEngine, req: SearchRequest): List<SearchHit> {
        val (country, lang) = splitRegion(req.region)
        val built = e.build(req)
        val r = built.newBuilder().apply {
            if (built.header("User-Agent") == null) header("User-Agent", BROWSER_UAS[(uaIndex++ and 0x7fffffff) % BROWSER_UAS.size])
            if (built.header("Accept") == null) header("Accept", if (e.category == "news") "application/rss+xml,application/xml;q=0.9,*/*;q=0.5" else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "$lang-${country.uppercase()},$lang;q=0.9,en;q=0.8")
        }.build()
        val (code, text) = fetch(r)
        e.check(code, text)
        return e.parse(text, req).filter { it.title.isNotBlank() && it.url.startsWith("http") }
            .mapIndexed { i, h -> h.copy(engine = e.name, engines = listOf(e.name), score = (i + 1).toDouble()) }
    }

    private suspend fun fetch(r: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(r)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val body = runCatching { response.use { it.body?.string().orEmpty() } }
                    body.onSuccess { if (cont.isActive) cont.resume(response.code to it) }.onFailure { if (cont.isActive) cont.resumeWithException(it) }
                }
            })
        }
    }

    // ---- instant answers ----------------------------------------------------------------------

    private suspend fun instantAnswer(req: SearchRequest): String? = withTimeoutOrNull(5_000) {
        val q = URLEncoder.encode(req.query, "UTF-8")
        runCatching {
            val (code, text) = fetch(Request.Builder().url("https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1&no_redirect=1")
                .header("User-Agent", BOT_UA).build())
            if (code == 200) parseDdgAnswer(text)?.let { return@withTimeoutOrNull it }
        }
        val (_, lang) = splitRegion(req.region)
        runCatching {
            val (c1, t1) = fetch(Request.Builder().url("https://$lang.wikipedia.org/w/api.php?action=opensearch&search=$q&limit=1&format=json&profile=fuzzy")
                .header("User-Agent", BOT_UA).header("Api-User-Agent", BOT_UA).build())
            if (c1 != 200) return@withTimeoutOrNull null
            val title = ((HarnessJson.parseToJsonElement(t1) as? JsonArray)?.getOrNull(1) as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: return@withTimeoutOrNull null
            val (c2, t2) = fetch(Request.Builder().url("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20"))
                .header("User-Agent", BOT_UA).header("Api-User-Agent", BOT_UA).build())
            if (c2 != 200) return@withTimeoutOrNull null
            val o = HarnessJson.parseToJsonElement(t2).jsonObject
            if ((o["type"] as? JsonPrimitive)?.contentOrNull == "disambiguation") null else (o["extract"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
        }.getOrNull()
    }

    // ---- own server ---------------------------------------------------------------------------

    private suspend fun viaEndpoint(req: SearchRequest, config: SearchConfig): SearchResponse {
        val base = config.endpoint.trimEnd('/')
        val request = when (config.endpointType) {
            "tavily" -> {
                val body = buildJsonObject {
                    put("query", req.query); put("max_results", req.maxResults); put("include_answer", config.answer)
                    put("topic", if (req.type == "news") "news" else "general")
                    req.timeRange?.let { put("time_range", it) }
                    if (config.apiKey.isNotBlank()) put("api_key", config.apiKey)
                }
                val url = if (base.endsWith("/search")) base else "$base/search"
                Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
                    .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }.build()
            }
            else -> {
                val url = (if (base.endsWith("/search")) base else "$base/search").toHttpUrlOrNull() ?: throw IOException("invalid endpoint URL")
                val u = url.newBuilder().addQueryParameter("q", req.query).apply {
                    if (config.endpointType == "searxng") {
                        addQueryParameter("format", "json")
                        if (req.type == "news") addQueryParameter("categories", "news")
                        req.timeRange?.let { addQueryParameter("time_range", it) }
                        addQueryParameter("language", splitRegion(req.region).second)
                        if (req.page > 1) addQueryParameter("pageno", req.page.toString())
                    } else {
                        addQueryParameter("max_results", req.maxResults.toString())
                        addQueryParameter("region", req.region)
                        addQueryParameter("safesearch", req.safesearch)
                        req.timeRange?.let { addQueryParameter("time_range", it) }
                        if (req.page > 1) addQueryParameter("page", req.page.toString())
                        if (config.answer) addQueryParameter("include_answer", "true")
                        if (req.includeDomains.isNotEmpty()) addQueryParameter("include_domains", req.includeDomains.joinToString(","))
                        if (req.excludeDomains.isNotEmpty()) addQueryParameter("exclude_domains", req.excludeDomains.joinToString(","))
                        if (req.type == "news") addQueryParameter("topic", "news")
                    }
                }.build()
                Request.Builder().url(u).header("Accept", "application/json")
                    .apply { if (config.apiKey.isNotBlank()) { header("Authorization", "Bearer ${config.apiKey}"); header("X-API-Key", config.apiKey) } }.build()
            }
        }
        val (code, text) = withTimeoutOrNull(config.timeoutMs + 7_000) { fetch(request) } ?: throw IOException("timed out")
        if (code >= 400) throw IOException("HTTP $code ${text.take(200)}")
        return parseEndpoint(text, req, config.endpointType)
    }

    companion object {
        /** Wikipedia and DuckDuckGo's answer API ask for an identifying agent. */
        const val BOT_UA = "FARAI-Harness/2.0 (Android coding agent)"
        val BROWSER_UAS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
        )

        fun parseDdgAnswer(text: String): String? = runCatching {
            val o = HarnessJson.parseToJsonElement(text).jsonObject
            listOf("Answer", "AbstractText", "Definition").firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null } }
        }.getOrNull()

        /** Reads a JSON, SearXNG or Tavily answer (they share `results[].title/url/content`). */
        fun parseEndpoint(text: String, req: SearchRequest, kind: String): SearchResponse {
            val o = runCatching { HarnessJson.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException("endpoint did not return JSON") }
            fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
            val results = (o["results"] as? JsonArray).orEmpty().mapNotNull { el ->
                val r = el as? JsonObject ?: return@mapNotNull null
                val url = r.s("url") ?: return@mapNotNull null
                val engines = (r["engines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: listOfNotNull(r.s("engine") ?: kind)
                SearchHit(clean(r.s("title")).ifBlank { url }, url, clean(r.s("content") ?: r.s("snippet")),
                    r.s("published_date") ?: r.s("publishedDate"), r.s("source"), engines.firstOrNull() ?: kind, engines)
            }.filter { it.url.startsWith("http") }.take(req.maxResults)
            val answer = o.s("answer") ?: (o["answers"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            return SearchResponse(req.query, results, answer?.ifBlank { null }, listOf(EngineStatus(kind, true, results.size)), via = kind)
        }
    }
}

/** URL normalisation, de-duplication and Reciprocal Rank Fusion. */
object Ranking {
    private const val RRF_K = 60
    private val TRACKING = setOf("fbclid", "gclid", "msclkid", "yclid", "mc_cid", "mc_eid", "ref_src", "srsltid", "_hsenc", "_hsmi")

    /** A comparison key that ignores scheme, www., fragment, tracking parameters and a trailing slash. */
    fun normalizeUrl(url: String): String = runCatching {
        val u = URI(url.trim())
        var host = (u.host ?: "").lowercase().removePrefix("www.")
        if (u.port != -1 && u.port != 80 && u.port != 443) host += ":${u.port}"
        val query = (u.rawQuery ?: "").split('&').filter { it.isNotEmpty() }
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .filter { (k, _) -> val l = URLDecoder.decode(k, "UTF-8").lowercase(); !l.startsWith("utm_") && l !in TRACKING }
            .sortedWith(compareBy({ it.first }, { it.second })).joinToString("&") { "${it.first}=${it.second}" }
        val path = (u.rawPath ?: "").trimEnd('/').ifEmpty { "/" }
        "//$host$path" + if (query.isNotEmpty()) "?$query" else ""
    }.getOrDefault(url)

    /** Links that only redirect to the real page (Google News article ids). */
    fun isRedirect(url: String): Boolean = domainOf(url) == "news.google.com"

    /** A loose key for spotting the same headline from two engines; null for short titles. */
    fun titleKey(title: String): String? =
        title.lowercase().filter { it.isLetterOrDigit() }.take(70).takeIf { it.length >= 28 }

    fun domainOf(url: String): String = runCatching { (URI(url).host ?: "").lowercase().removePrefix("www.") }.getOrDefault("")

    fun domainMatches(url: String, domains: List<String>): Boolean {
        val host = domainOf(url)
        return domains.any { raw ->
            val d = raw.lowercase().trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
            d.isNotEmpty() && (host == d || host.endsWith(".$d"))
        }
    }

    fun merge(byEngine: Map<String, List<SearchHit>>, weights: Map<String, Double>): List<SearchHit> {
        class Acc(var hit: SearchHit, var score: Double, var best: Double, val engines: MutableList<String>)
        val merged = LinkedHashMap<String, Acc>()
        val titles = HashMap<String, String>()
        for ((engine, hits) in byEngine) {
            val w = weights[engine] ?: 1.0
            hits.forEachIndexed { i, h ->
                if (h.title.isBlank() || !(h.url.startsWith("http://") || h.url.startsWith("https://"))) return@forEachIndexed
                val tkey = titleKey(h.title)
                val key = tkey?.let { titles[it] } ?: normalizeUrl(h.url)
                if (tkey != null) titles.putIfAbsent(tkey, key)
                val contrib = w / (RRF_K + i + 1)
                val cur = merged[key]
                if (cur == null) { merged[key] = Acc(h.copy(engine = engine), contrib, contrib, mutableListOf(engine)); return@forEachIndexed }
                cur.score += contrib
                if (engine !in cur.engines) cur.engines += engine
                var hit = cur.hit
                // Same story from two aggregators: keep the publisher's own link, not a redirect.
                if (isRedirect(hit.url) && !isRedirect(h.url)) hit = hit.copy(url = h.url)
                if (h.content.length > hit.content.length) hit = hit.copy(content = h.content)
                hit = hit.copy(publishedDate = hit.publishedDate ?: h.publishedDate, source = hit.source ?: h.source)
                if (contrib > cur.best) { cur.best = contrib; hit = hit.copy(engine = engine) }
                cur.hit = hit
            }
        }
        val ranked = merged.values.sortedByDescending { it.score }
        val top = ranked.firstOrNull()?.score ?: 1.0
        return ranked.map { a -> a.hit.copy(engines = a.engines.toList(), score = if (top > 0) Math.round(a.score / top * 10_000) / 10_000.0 else 0.0) }
    }
}
