package dev.harness.core.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/** One search engine: how to ask it and how to read its answer. Both halves are pure so they can be tested on saved pages. */
abstract class SearchEngine {
    abstract val name: String
    open val category: String = "web"
    /** How much its ranking counts when results are merged. */
    open val weight: Double = 1.0
    open val maxResults: Int = 50
    /** Substrings that mean "this is a block or CAPTCHA page". */
    open val blockMarkers: List<String> = emptyList()

    abstract fun build(req: SearchRequest): Request
    abstract fun parse(text: String, req: SearchRequest): List<SearchHit>

    /** Throws when the answer is a block page or an error. */
    open fun check(code: Int, text: String) {
        if (code == 403 || code == 429 || code == 202) throw EngineBlocked("HTTP $code")
        if (code >= 400) throw EngineError("HTTP $code")
        val head = text.take(20_000).lowercase()
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(head)?.groupValues?.get(1).orEmpty()
        if ("captcha" in title || "are you a robot" in title) throw EngineBlocked("CAPTCHA page")
        blockMarkers.firstOrNull { it in head }?.let { throw EngineBlocked("block page ($it)") }
    }

    protected fun url(base: String, params: Map<String, String?>): HttpUrl =
        base.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) } }.build()

    protected fun html(text: String): Document {
        if (text.isBlank()) throw EngineError("empty response")
        return Jsoup.parse(text)
    }
}

// ---- helpers ----------------------------------------------------------------------------------

internal fun clean(s: String?): String = s.orEmpty().let { Parser.unescapeEntities(it, false) }.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
internal fun stripTags(s: String?): String = clean(s.orEmpty().replace(Regex("<[^>]+>"), " "))
internal fun Element?.txt(): String = this?.text()?.let(::clean).orEmpty()
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private val DATE_PREFIX = Regex(
    "^((?:\\d{1,2} \\w{3,9},? \\d{4})|(?:\\w{3,9} \\d{1,2}, \\d{4})|(?:\\d+ (?:hours?|days?|weeks?|mins?|minutes?) ago))\\s*[·\\-–—]\\s*",
    RegexOption.IGNORE_CASE,
)

/** Many engines start a snippet with a date ("20 Jun 2026 · …"). */
internal fun splitDatePrefix(snippet: String): Pair<String?, String> {
    val m = DATE_PREFIX.find(snippet) ?: return null to snippet
    return m.groupValues[1] to snippet.substring(m.range.last + 1)
}

internal fun queryParam(url: String, key: String): String? = runCatching {
    url.substringAfter('?', "").split('&').firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "")
        ?.let { URLDecoder.decode(it, "UTF-8") }
}.getOrNull()

/** //duckduckgo.com/l/?uddg=<target>&rut=… → target. */
internal fun unwrapDdg(href: String): String {
    val h = if (href.startsWith("//")) "https:$href" else href
    if (Regex("^https?://([a-z]+\\.)?duckduckgo\\.com/l/").containsMatchIn(h)) queryParam(h, "uddg")?.let { return it }
    return h
}

/** https://www.bing.com/ck/a?…&u=a1<base64url> → target. */
internal fun unwrapBing(href: String): String {
    if (!Regex("^https?://([a-z]+\\.)?bing\\.com/ck/a").containsMatchIn(href)) return href
    val u = queryParam(href, "u") ?: return href
    if (u.length <= 2) return href
    val b64 = u.substring(2)
    return runCatching { String(Base64.getUrlDecoder().decode(b64 + "=".repeat((4 - b64.length % 4) % 4))) }.getOrDefault(href)
}

/** https://r.search.yahoo.com/…/RU=<target>/RK=…/RS=… → target. */
internal fun unwrapYahoo(href: String): String {
    if ("/RU=" !in href) return href
    val t = href.substringAfter("/RU=").substringBefore("/RK=").substringBefore("/RS=")
    return runCatching { URLDecoder.decode(t, "UTF-8") }.getOrDefault(t)
}

// ---- engines ----------------------------------------------------------------------------------

class DuckDuckGo : SearchEngine() {
    override val name = "duckduckgo"
    override val weight = 1.2
    override val blockMarkers = listOf("anomaly-modal", "anomaly.js", "challenge-form")
    private val safe = mapOf("strict" to "1", "moderate" to "-1", "off" to "-2")
    private val time = mapOf("day" to "d", "week" to "w", "month" to "m", "year" to "y")

    override fun build(req: SearchRequest): Request {
        val (country, lang) = splitRegion(req.region)
        val form = FormBody.Builder().add("q", req.query).add("b", "").add("kl", "$country-$lang").add("kp", safe[req.safesearch] ?: "-1")
        req.timeRange?.let { time[it] }?.let { form.add("df", it) }
        if (req.page > 1) { form.add("s", (10 + (req.page - 2) * 15).toString()); form.add("dc", (11 + (req.page - 2) * 15).toString()) }
        return Request.Builder().url("https://html.duckduckgo.com/html/").post(form.build())
            .header("Referer", "https://html.duckduckgo.com/").header("Origin", "https://html.duckduckgo.com").build()
    }

    override fun parse(text: String, req: SearchRequest): List<SearchHit> = html(text).select("div.result").mapNotNull { item ->
        if (item.hasClass("result--ad") || item.hasClass("result--no-result")) return@mapNotNull null
        val a = item.selectFirst("a.result__a") ?: return@mapNotNull null
        val url = unwrapDdg(a.attr("href"))
        if (!url.startsWith("http") || "duckduckgo.com/y.js" in url) return@mapNotNull null
        SearchHit(a.txt(), url, item.selectFirst(".result__snippet").txt())
    }
}

class Brave : SearchEngine() {
    override val name = "brave"
    override val weight = 1.1
    override val blockMarkers = listOf("pow-captcha", "/search/captcha")
    private val time = mapOf("day" to "pd", "week" to "pw", "month" to "pm", "year" to "py")

    override fun build(req: SearchRequest): Request {
        val (country, _) = splitRegion(req.region)
        val u = url("https://search.brave.com/search", mapOf(
            "q" to req.query, "source" to "web",
            "offset" to (req.page - 1).takeIf { it > 0 }?.toString(), "tf" to req.timeRange?.let { time[it] },
        ))
        return Request.Builder().url(u).header("Cookie", "safesearch=${req.safesearch}; country=$country; useLocation=0").build()
    }

    override fun parse(text: String, req: SearchRequest): List<SearchHit> = html(text).select("div[data-type=web]").mapNotNull { item ->
        val a = item.selectFirst("a[href^=http]") ?: return@mapNotNull null
        val title = item.selectFirst(".title").txt().ifBlank { a.txt() }
        val desc = item.selectFirst("div.generic-snippet div.content") ?: item.selectFirst("div.generic-snippet") ?: item.selectFirst(".snippet-description")
        val (date, snippet) = splitDatePrefix(desc.txt())
        SearchHit(title, a.attr("href"), snippet, date)
    }
}

class Bing : SearchEngine() {
    override val name = "bing"
    override val weight = 1.0
    override val blockMarkers = listOf("/turing/captcha", "b_captcha", "challenges.cloudflare")

    override fun build(req: SearchRequest): Request {
        val (country, lang) = splitRegion(req.region)
        val filter = req.timeRange?.let {
            val day = System.currentTimeMillis() / 86_400_000L
            mapOf("day" to "ez1", "week" to "ez2", "month" to "ez3")[it] ?: "ez5_${day - 365}_$day"
        }
        val u = url("https://www.bing.com/search", mapOf(
            "q" to req.query, "setlang" to lang, "cc" to country, "adlt" to req.safesearch,
            "first" to if (req.page > 1) ((req.page - 1) * 10 + 1).toString() else null,
            "FORM" to if (req.page > 1) "PERE" else null,
            "filters" to filter?.let { "ex1:\"$it\"" },
        ))
        return Request.Builder().url(u)
            .header("Cookie", "_EDGE_CD=m=$lang-$country&u=$lang-$country; _EDGE_S=mkt=$lang-$country&ui=$lang-$country; SRCHHPGUSR=ADLT=${req.safesearch.uppercase()}")
            .build()
    }

    override fun parse(text: String, req: SearchRequest): List<SearchHit> = html(text).select("li.b_algo").mapNotNull { item ->
        // Older pages put the link inside <h2>; newer ones wrap the <h2> in the link.
        val a = item.selectFirst("h2 a[href]") ?: item.selectFirst(".b_algoheader a[href]") ?: item.selectFirst("h2")?.closest("a[href]")
            ?: return@mapNotNull null
        val url = unwrapBing(a.attr("href"))
        if (!url.startsWith("http") || "bing.com/aclick" in url) return@mapNotNull null
        val p = item.selectFirst("div.b_caption p") ?: item.selectFirst("p")
        val (date, snippet) = splitDatePrefix(p.txt())
        SearchHit(item.selectFirst("h2").txt().ifBlank { a.txt() }, url, snippet, date)
    }
}

class Yahoo : SearchEngine() {
    override val name = "yahoo"
    override val weight = 0.9
    override val blockMarkers = listOf("unusual traffic from your computer")
    private val time = mapOf("day" to "d", "week" to "w", "month" to "m", "year" to "y")
    private val safe = mapOf("strict" to "r", "moderate" to "i", "off" to "p")

    override fun build(req: SearchRequest): Request = Request.Builder().url(url("https://search.yahoo.com/search", mapOf(
        "p" to req.query, "vm" to (safe[req.safesearch] ?: "i"),
        "b" to if (req.page > 1) ((req.page - 1) * 7 + 1).toString() else null, "btf" to req.timeRange?.let { time[it] },
    ))).build()

    override fun parse(text: String, req: SearchRequest): List<SearchHit> = html(text).select(".algo").mapNotNull { item ->
        val a = item.selectFirst("div.compTitle a[href]") ?: item.selectFirst("h3 a[href]") ?: return@mapNotNull null
        val url = unwrapYahoo(a.attr("href"))
        if (!url.startsWith("http") || "/cbclk" in url || "bing.com/aclick" in url) return@mapNotNull null
        val h3 = item.selectFirst("h3")
        val spans = h3?.select("span").orEmpty()
        // The title is the link's own text (or its aria-label); the spans before it hold the display URL.
        val title = stripTags(a.attr("aria-label")).ifBlank { clean(a.ownText()) }
            .ifBlank { if (spans.isNotEmpty()) spans.last().txt() else h3.txt() }
        val (date, snippet) = splitDatePrefix(item.selectFirst("div.compText").txt())
        SearchHit(title.ifBlank { a.txt() }, url, snippet, date)
    }
}

class Mojeek : SearchEngine() {
    override val name = "mojeek"
    override val weight = 0.8

    override fun build(req: SearchRequest): Request {
        val (country, lang) = splitRegion(req.region)
        val u = url("https://www.mojeek.com/search", mapOf(
            "q" to req.query, "safe" to if (req.safesearch == "strict") "1" else null,
            "s" to if (req.page > 1) ((req.page - 1) * 10 + 1).toString() else null,
        ))
        return Request.Builder().url(u).header("Cookie", "arc=$country; lb=$lang").build()
    }

    override fun parse(text: String, req: SearchRequest): List<SearchHit> = html(text).select("ul.results-standard > li").mapNotNull { item ->
        val a = item.selectFirst("h2 a[href]") ?: item.selectFirst("a.title") ?: return@mapNotNull null
        SearchHit(a.txt(), a.attr("href"), item.selectFirst("p.s").txt())
    }
}

class Wikipedia : SearchEngine() {
    override val name = "wikipedia"
    override val weight = 0.7
    /** Encyclopedia hits complement web results instead of flooding them. */
    override val maxResults = 3

    override fun build(req: SearchRequest): Request {
        val (_, lang) = splitRegion(req.region)
        val limit = minOf(maxResults, req.maxResults)
        return Request.Builder().url(url("https://$lang.wikipedia.org/w/api.php", mapOf(
            "action" to "query", "list" to "search", "srsearch" to req.query, "format" to "json", "utf8" to "1",
            "srlimit" to limit.toString(), "sroffset" to ((req.page - 1) * limit).toString(), "srprop" to "snippet|timestamp",
        ))).header("User-Agent", WebSearch.BOT_UA).header("Api-User-Agent", WebSearch.BOT_UA).build()
    }

    override fun check(code: Int, text: String) { if (code >= 400) throw EngineError("HTTP $code") }

    override fun parse(text: String, req: SearchRequest): List<SearchHit> {
        val (_, lang) = splitRegion(req.region)
        val root = runCatching { dev.harness.net.HarnessJson.parseToJsonElement(text).jsonObject }.getOrElse { throw EngineError("invalid JSON from Wikipedia") }
        val hits = (root["query"] as? JsonObject)?.get("search") as? JsonArray ?: return emptyList()
        return hits.mapNotNull { h ->
            val o = h as? JsonObject ?: return@mapNotNull null
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            SearchHit(
                title, "https://$lang.wikipedia.org/wiki/" + enc(title.replace(' ', '_')).replace("+", "%20"),
                stripTags((o["snippet"] as? JsonPrimitive)?.contentOrNull), (o["timestamp"] as? JsonPrimitive)?.contentOrNull, "Wikipedia",
            )
        }
    }
}

private fun rssItems(text: String): List<Element> {
    if (text.isBlank()) throw EngineError("empty feed")
    return Jsoup.parse(text, "", Parser.xmlParser()).getElementsByTag("item")
}
private fun Element.child(name: String): String? =
    children().firstOrNull { it.tagName().substringAfter(':').equals(name, ignoreCase = true) }?.text()

private fun rfc822ToIso(s: String?): String? {
    if (s.isNullOrBlank()) return null
    return runCatching {
        val d = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(s.trim().replace("GMT", "+0000"))!!
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
    }.getOrDefault(s)
}

class BingNews : SearchEngine() {
    override val name = "bing_news"
    override val category = "news"

    override fun build(req: SearchRequest): Request {
        val (country, lang) = splitRegion(req.region)
        val qft = req.timeRange?.let { mapOf("day" to "interval=\"7\"", "week" to "interval=\"8\"")[it] ?: "interval=\"9\"" }
        return Request.Builder().url(url("https://www.bing.com/news/search", mapOf(
            "q" to req.query, "format" to "rss", "setlang" to lang, "cc" to country, "qft" to qft,
            "first" to if (req.page > 1) ((req.page - 1) * 10 + 1).toString() else null,
        ))).build()
    }

    override fun check(code: Int, text: String) { if (code >= 400) throw EngineError("HTTP $code") }

    override fun parse(text: String, req: SearchRequest): List<SearchHit> = rssItems(text).mapNotNull { item ->
        var link = item.child("link").orEmpty()
        if ("bing.com" in link) queryParam(link, "url")?.let { link = it }
        if (!link.startsWith("http")) return@mapNotNull null
        SearchHit(clean(item.child("title")), link, stripTags(item.child("description")), rfc822ToIso(item.child("pubDate")), clean(item.child("Source")).ifBlank { null })
    }
}

class GoogleNews : SearchEngine() {
    override val name = "google_news"
    override val category = "news"
    override val weight = 1.1

    override fun build(req: SearchRequest): Request {
        val (country, lang) = splitRegion(req.region)
        val q = req.query + (req.timeRange?.let { " when:" + (mapOf("day" to "1d", "week" to "7d", "month" to "30d", "year" to "365d")[it] ?: "7d") } ?: "")
        val cc = country.uppercase()
        return Request.Builder().url(url("https://news.google.com/rss/search", mapOf("q" to q, "hl" to "$lang-$cc", "gl" to cc, "ceid" to "$cc:$lang"))).build()
    }

    override fun check(code: Int, text: String) { if (code >= 400) throw EngineError("HTTP $code") }

    override fun parse(text: String, req: SearchRequest): List<SearchHit> = rssItems(text).mapNotNull { item ->
        val source = clean(item.child("source")).ifBlank { null }
        var title = clean(item.child("title"))
        if (source != null && title.endsWith(" - $source")) title = title.dropLast(source.length + 3)
        val link = item.child("link").orEmpty()
        if (!link.startsWith("http")) return@mapNotNull null
        var desc = stripTags(item.child("description"))
        if (source != null && desc.endsWith(source)) desc = desc.dropLast(source.length).trim()
        SearchHit(title, link, desc, rfc822ToIso(item.child("pubDate")), source)
    }
}

object Engines {
    val all: List<SearchEngine> = listOf(DuckDuckGo(), Brave(), Bing(), Yahoo(), Wikipedia(), Mojeek(), BingNews(), GoogleNews())
    fun byName(name: String): SearchEngine? = all.firstOrNull { it.name == name.trim().lowercase() }
    val DEFAULT_WEB = listOf("duckduckgo", "brave", "bing", "yahoo", "wikipedia")
    val DEFAULT_NEWS = listOf("google_news", "bing_news")
}
