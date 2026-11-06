package dev.harness.core.web

import dev.harness.net.HarnessJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Reads web pages fast and many at a time: each page gets a short deadline, pages are fetched in
 * parallel, redirect links from news aggregators (Google News, Bing, DuckDuckGo) are unwrapped
 * first, and HTML is cut down to the article itself (no menus, footers, "read also" boxes) so the
 * model reads a few thousand useful characters instead of a whole page.
 */
class PageReader(
    base: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Page(
        val requested: String,
        val url: String,
        val title: String,
        val site: String? = null,
        val published: String? = null,
        val text: String = "",
        val words: Int = 0,
        val ms: Long = 0,
        val error: String? = null,
        val via: String? = null,
        val truncated: Int = 0,
    ) {
        val ok get() = error == null
    }

    enum class Mode { ARTICLE, FULL, HTML }

    private val client = base.newBuilder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(PAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private class Cached(val page: Page, val at: Long)
    private val cache = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 60
    }

    /** Reads every URL at once (up to [MAX_PARALLEL] in flight); results keep the input order. */
    suspend fun readAll(urls: List<String>, maxChars: Int, mode: Mode = Mode.ARTICLE, readerFallback: Boolean = true): List<Page> = coroutineScope {
        val gate = Semaphore(MAX_PARALLEL)
        urls.map { u -> async { gate.withPermit { read(u, maxChars, mode, readerFallback) } } }.awaitAll()
    }

    suspend fun read(url: String, maxChars: Int, mode: Mode = Mode.ARTICLE, readerFallback: Boolean = true): Page {
        val key = "$mode|$maxChars|$url"
        synchronized(cache) { cache[key]?.takeIf { now() - it.at < CACHE_MS }?.let { return it.page } }
        val t0 = now()
        val page = withTimeoutOrNull(PAGE_TIMEOUT_MS + 4_000) {
            runCatching { readUncached(url, maxChars, mode, readerFallback) }
                .getOrElse { Page(url, url, hostOf(url), error = it.message ?: it.javaClass.simpleName) }
        } ?: Page(url, url, hostOf(url), error = "timed out after ${(PAGE_TIMEOUT_MS + 4_000) / 1000}s")
        val done = page.copy(ms = now() - t0)
        if (done.ok) synchronized(cache) { cache[key] = Cached(done, now()) }
        return done
    }

    private suspend fun readUncached(url: String, maxChars: Int, mode: Mode, readerFallback: Boolean): Page = withContext(Dispatchers.IO) {
        val target = unwrap(url)
        val http = target.toHttpUrlOrNull() ?: throw IOException("invalid URL: $target")
        val req = Request.Builder().url(http)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            .build()
        val (code, finalUrl, type, bytes) = client.newCall(req).execute().use { r ->
            val body = r.body ?: throw IOException("empty response")
            val src = body.source()
            src.request(MAX_BYTES)
            val b = src.buffer.let { buf -> buf.readByteArray(minOf(buf.size, MAX_BYTES)) }
            Quad(r.code, r.request.url.toString(), r.header("Content-Type").orEmpty(), b)
        }
        if (isBlockPage(finalUrl)) throw IOException("blocked by a consent or captcha page (${hostOf(finalUrl)})")
        if (code >= 400) {
            if (readerFallback && code in setOf(401, 403, 429, 451, 503)) return@withContext viaReader(url, target, maxChars, "HTTP $code")
            throw IOException("HTTP $code")
        }
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).trimStart()
        val isHtml = type.contains("html", true) || head.startsWith("<!") || head.startsWith("<html", true)
        if (!isHtml) {
            val text = String(bytes, charsetOf(type) ?: Charsets.UTF_8)
            return@withContext clip(Page(url, finalUrl, hostOf(finalUrl), text = text, words = words(text)), maxChars)
        }
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), charsetOf(type, null)?.name(), finalUrl)
        if (mode == Mode.HTML) return@withContext clip(Page(url, finalUrl, titleOf(doc, finalUrl), text = doc.outerHtml()), maxChars)
        val page = extract(doc, url, finalUrl, mode)
        if (readerFallback && mode == Mode.ARTICLE && page.words < 60 && looksScripted(doc))
            return@withContext runCatching { viaReader(url, finalUrl, maxChars, "page needs JavaScript") }.getOrElse { clip(page, maxChars) }
        clip(page, maxChars)
    }

    /** Pages that only render with JavaScript (or block bots) are read through r.jina.ai, a free reader. */
    private fun viaReader(requested: String, url: String, maxChars: Int, why: String): Page {
        val req = Request.Builder().url("https://r.jina.ai/$url").header("User-Agent", UA).header("X-Return-Format", "markdown").build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful || body.isBlank()) throw IOException("$why; reader fallback HTTP ${r.code}")
            val title = Regex("(?m)^Title: (.+)$").find(body)?.groupValues?.get(1)?.trim()
            val published = Regex("(?m)^Published Time: (.+)$").find(body)?.groupValues?.get(1)?.trim()
            val text = body.substringAfter("Markdown Content:", body).trim()
            return clip(Page(requested, url, title ?: hostOf(url), hostOf(url), published, text, words(text), via = "reader"), maxChars)
        }
    }

    // ---- Link unwrapping -------------------------------------------------------------------

    /** Turns aggregator redirect links into the publisher's own URL. */
    fun unwrap(url: String): String {
        val u = url.trim().toHttpUrlOrNull() ?: return url.trim()
        val host = u.host.lowercase()
        when {
            host.endsWith("bing.com") && (u.encodedPath.contains("apiclick") || u.encodedPath.startsWith("/ck/")) ->
                u.queryParameter("url")?.takeIf { it.startsWith("http") }?.let { return it }
            host.endsWith("duckduckgo.com") && u.encodedPath.startsWith("/l/") ->
                u.queryParameter("uddg")?.takeIf { it.startsWith("http") }?.let { return it }
            host == "www.google.com" && u.encodedPath == "/url" ->
                (u.queryParameter("q") ?: u.queryParameter("url"))?.takeIf { it.startsWith("http") }?.let { return it }
            host == "news.google.com" -> googleNewsId(u.encodedPathSegments)?.let { id -> decodeGoogleNews(id)?.let { return it } }
        }
        return url.trim()
    }

    private fun googleNewsId(segments: List<String>): String? {
        val i = segments.indexOfFirst { it == "articles" || it == "read" }
        return segments.getOrNull(i + 1)?.takeIf { i >= 0 && it.isNotBlank() }
    }

    /** Google News article ids: old ones carry the URL inside; newer ones ask Google to resolve them. */
    fun decodeGoogleNews(id: String): String? {
        legacyGoogleNews(id)?.let { return it }
        return runCatching {
            val page = Request.Builder().url("https://news.google.com/rss/articles/$id").header("User-Agent", UA)
                .header("Cookie", "CONSENT=YES+cb; SOCS=CAI").build()
            val html = client.newCall(page).execute().use { it.body?.string().orEmpty() }
            val node = Jsoup.parse(html).selectFirst("[data-n-a-sg][data-n-a-ts]") ?: return null
            val sig = node.attr("data-n-a-sg"); val ts = node.attr("data-n-a-ts")
            val inner = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null,null,null,null,0,1]," +
                "\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],\"$id\",$ts,\"$sig\"]"
            val freq = "[[[\"Fbv4je\",${HarnessJson.encodeToString(kotlinx.serialization.serializer<String>(), inner)},null,\"generic\"]]]"
            val post = Request.Builder().url("https://news.google.com/_/DotsSplashUi/data/batchexecute")
                .header("User-Agent", UA).post(FormBody.Builder().add("f.req", freq).build()).build()
            val text = client.newCall(post).execute().use { it.body?.string().orEmpty() }
            parseBatchUrl(text)
        }.getOrNull()
    }

    // ---- Extraction ------------------------------------------------------------------------

    fun extract(doc: Document, requested: String, finalUrl: String, mode: Mode = Mode.ARTICLE): Page {
        val title = titleOf(doc, finalUrl)
        val site = meta(doc, "og:site_name") ?: hostOf(finalUrl)
        val published = meta(doc, "article:published_time") ?: meta(doc, "og:published_time") ?: meta(doc, "pubdate")
            ?: meta(doc, "publishdate") ?: meta(doc, "date") ?: meta(doc, "dc.date")
            ?: doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { s ->
                Regex("\"datePublished\"\\s*:\\s*\"([^\"]+)\"").find(s.data())?.groupValues?.get(1)
            }
            ?: doc.selectFirst("time[datetime]")?.attr("datetime")?.ifBlank { null }
        doc.select(JUNK_TAGS).remove()
        val body = doc.body() ?: return Page(requested, finalUrl, title, site, published)
        val root = if (mode == Mode.FULL) body else {
            stripJunk(body)
            pickContent(body)
        }
        var text = Markdown.of(root, finalUrl, links = mode == Mode.FULL).trim()
        // Drop a leading copy of the title.
        text = text.lineSequence().dropWhile { it.isBlank() }.toList().let { lines ->
            if (lines.firstOrNull()?.trimStart('#', ' ')?.equals(title, true) == true) lines.drop(1) else lines
        }.joinToString("\n").trim()
        if (text.length < 200) meta(doc, "og:description")?.let { d -> if (d !in text) text = (d + "\n\n" + text).trim() }
        return Page(requested, finalUrl, title, site, published, text, words(text))
    }

    private fun stripJunk(body: Element) {
        val total = body.text().length.coerceAtLeast(1)
        body.select("[class], [id], [role], [aria-hidden=true], [hidden]").filter { el ->
            if (el.tagName() in setOf("body", "html", "main", "article")) return@filter false
            val role = el.attr("role")
            if (el.hasAttr("hidden") || el.attr("aria-hidden") == "true" || role in setOf("navigation", "banner", "contentinfo", "complementary", "dialog")) return@filter true
            val sig = (el.className() + " " + el.id()).lowercase()
            JUNK_NAMES.containsMatchIn(sig) && !KEEP_NAMES.containsMatchIn(sig) && el.text().length < total * 0.5
        }.forEach { if (it.parent() != null) it.remove() }
    }

    private fun pickContent(body: Element): Element {
        for (sel in CONTENT_SELECTORS) {
            val el = body.select(sel).maxByOrNull { it.text().length } ?: continue
            if (el.text().length >= 400) return el
        }
        // Readability-style: the block holding the most paragraph text wins.
        val score = HashMap<Element, Double>()
        for (p in body.select("p, pre, blockquote, li")) {
            val len = p.text().length
            if (len < 40) continue
            val pts = 1.0 + len / 100.0 + p.text().count { it == ',' } * 0.5
            p.parent()?.let { score[it] = (score[it] ?: 0.0) + pts }
            p.parent()?.parent()?.let { score[it] = (score[it] ?: 0.0) + pts / 2 }
        }
        val best = score.entries.maxByOrNull { (el, s) ->
            val linkLen = el.select("a").sumOf { it.text().length }.toDouble()
            val density = 1.0 - (linkLen / el.text().length.coerceAtLeast(1))
            s * density
        }?.key
        return best?.takeIf { it.text().length >= 250 } ?: body
    }

    private fun looksScripted(doc: Document): Boolean =
        doc.select("#__next, #root, #app, [ng-app], noscript").isNotEmpty() || doc.select("script").size > 15

    // ---- Small helpers ---------------------------------------------------------------------

    private fun clip(p: Page, maxChars: Int): Page {
        val t = p.text.replace(Regex("\n{3,}"), "\n\n")
        if (t.length <= maxChars) return p.copy(text = t, words = if (p.words == 0) words(t) else p.words)
        var cut = t.lastIndexOf("\n\n", maxChars).takeIf { it > maxChars * 0.7 } ?: maxChars
        if (cut > t.length) cut = t.length
        return p.copy(text = t.substring(0, cut).trimEnd(), truncated = t.length - cut, words = words(t))
    }

    private data class Quad(val code: Int, val url: String, val type: String, val bytes: ByteArray)

    companion object {
        const val MAX_PARALLEL = 12
        const val MAX_URLS = 25
        /** Pages a news search reads when the model does not say. */
        const val NEWS_DEFAULT_READ = 5

        /** Characters per page so a batch stays readable: fewer each as the batch grows. */
        fun perPageChars(n: Int): Int = when { n <= 1 -> 20_000; n <= 3 -> 6_000; n <= 10 -> 4_000; else -> 2_500 }
        const val PAGE_TIMEOUT_MS = 12_000L
        private const val CACHE_MS = 15 * 60_000L
        private const val MAX_BYTES = 3L * 1024 * 1024
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        private const val JUNK_TAGS = "script, style, noscript, template, svg, canvas, iframe, object, embed, form, button, input, select, " +
            "textarea, nav, header, footer, aside, dialog, link, meta"
        private val JUNK_NAMES = Regex(
            "(^|[\\s_-])(nav|navbar|menu|breadcrumbs?|sidebar|side-bar|footer|header|masthead|comments?|disqus|share|sharing|social|" +
                "related|recommend\\w*|popular|trending|most-read|terpopuler|baca-?juga|lihat-?juga|artikel-?terkait|berita-?terkait|" +
                "advert\\w*|ads?|ad-\\w+|sponsor\\w*|promo\\w*|newsletter|subscribe|signup|cookie\\w*|consent|gdpr|popup|modal|banner|" +
                "widget|tags?|author-box|pagination|skip-link|sr-only|visually-hidden|toolbar)([\\s_-]|$)"
        )
        private val KEEP_NAMES = Regex("(^|[\\s_-])(article|content|post|entry|story|detail|read__|body-text|main)")
        private val CONTENT_SELECTORS = listOf(
            "[itemprop=articleBody]", ".detail__body-text", ".read__content", ".article-content", ".article__content",
            ".article-body", ".articleBody", ".entry-content", ".post-content", ".post-body", ".story-body", ".content-body",
            ".markdown-body", "article", "main", "[role=main]", "#content", ".content",
        )

        fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")
        fun words(t: String) = t.split(Regex("\\s+")).count { it.isNotBlank() }
        private fun isBlockPage(url: String) = url.contains("google.com/sorry") || url.contains("consent.google.") ||
            url.contains("consent.yahoo.") || url.contains("/captcha")

        private fun meta(doc: Document, name: String): String? =
            doc.selectFirst("meta[property=$name], meta[name=$name], meta[itemprop=$name]")?.attr("content")?.trim()?.ifBlank { null }

        private fun titleOf(doc: Document, url: String): String =
            (meta(doc, "og:title") ?: doc.title().ifBlank { null } ?: doc.selectFirst("h1")?.text() ?: hostOf(url)).trim().take(200)

        private fun charsetOf(type: String, default: java.nio.charset.Charset? = Charsets.UTF_8): java.nio.charset.Charset? =
            Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
                ?.let { runCatching { charset(it) }.getOrNull() } ?: default

        /** Old-style ids are base64 protobufs holding the article URL. */
        fun legacyGoogleNews(id: String): String? = runCatching {
            val bytes = Base64.getUrlDecoder().decode(id.padEnd((id.length + 3) / 4 * 4, '='))
            val s = String(bytes, Charsets.ISO_8859_1)
            if ("AU_yqL" in s) return null
            Regex("https?://[\\x21-\\x7e]+").find(s)?.value?.trimEnd('\u00d2', '\u0001')
        }.getOrNull()

        /** Pulls the URL out of Google's batchexecute answer. */
        fun parseBatchUrl(text: String): String? {
            for (line in text.removePrefix(")]}'").lineSequence().map { it.trim() }.filter { it.startsWith("[") }) {
                val arr = runCatching { HarnessJson.parseToJsonElement(line).jsonArray }.getOrNull() ?: continue
                for (row in arr) {
                    val inner = ((row as? JsonArray)?.getOrNull(2) as? JsonPrimitive)?.contentOrNull ?: continue
                    val decoded = runCatching { HarnessJson.parseToJsonElement(inner).jsonArray }.getOrNull() ?: continue
                    (decoded.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") }?.let { return it }
                }
            }
            return Regex("garturlres\\\\\",\\\\\"(https?://[^\\\\\"]+)").find(text)?.groupValues?.get(1)
        }

        fun decodeQuery(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}

/** HTML element → compact Markdown the model reads well. */
object Markdown {
    fun of(root: Element, base: String, links: Boolean): String {
        val sb = StringBuilder()
        walk(root, sb, links, 0)
        return sb.toString().lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n")
    }

    private fun block(sb: StringBuilder) { if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append(if (sb.endsWith("\n")) "\n" else "\n\n") }

    private fun walk(node: Node, sb: StringBuilder, links: Boolean, listDepth: Int) {
        if (node is TextNode) {
            val t = node.text().replace('\u00a0', ' ').replace(Regex("\\s+"), " ")
            if (t.isNotBlank() || (t.isNotEmpty() && sb.isNotEmpty() && !sb.last().isWhitespace())) sb.append(t)
            return
        }
        val el = node as? Element ?: return
        when (val tag = el.tagName()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val t = el.text().trim(); if (t.isEmpty()) return
                block(sb); sb.append("#".repeat(tag[1].digitToInt().coerceAtLeast(2))).append(' ').append(t); block(sb)
            }
            "p", "div", "section", "article", "main", "figure", "header", "dl" -> { block(sb); el.childNodes().forEach { walk(it, sb, links, listDepth) }; block(sb) }
            "br" -> sb.append('\n')
            "hr" -> { block(sb) }
            "ul", "ol" -> {
                block(sb)
                var n = 1
                el.children().toList().filter { it.tagName() == "li" }.forEach { li ->
                    sb.append("  ".repeat(listDepth)).append(if (tag == "ol") "${n++}. " else "- ")
                    li.childNodes().forEach { walk(it, sb, links, listDepth + 1) }
                    if (!sb.endsWith("\n")) sb.append('\n')
                }
                block(sb)
            }
            "pre" -> { block(sb); sb.append("```\n").append(el.wholeText().trimEnd()).append("\n```"); block(sb) }
            "code" -> sb.append('`').append(el.text()).append('`')
            "blockquote" -> { block(sb); sb.append(el.text().lines().joinToString("\n") { "> $it" }); block(sb) }
            "strong", "b" -> { val t = el.text().trim(); if (t.isNotEmpty()) sb.append("**").append(t).append("**") }
            "em", "i" -> { val t = el.text().trim(); if (t.isNotEmpty()) sb.append('_').append(t).append('_') }
            "a" -> {
                val t = el.text().trim()
                val href = el.absUrl("href")
                if (links && t.isNotEmpty() && href.startsWith("http")) sb.append('[').append(t).append("](").append(href).append(')')
                else el.childNodes().forEach { walk(it, sb, links, listDepth) }
            }
            "img" -> { val alt = el.attr("alt").trim(); if (links && alt.length > 3) sb.append("[image: ").append(alt).append(']') }
            "table" -> { block(sb); table(el, sb); block(sb) }
            "figcaption" -> { block(sb); sb.append('_').append(el.text().trim()).append('_'); block(sb) }
            else -> el.childNodes().forEach { walk(it, sb, links, listDepth) }
        }
    }

    private fun table(t: Element, sb: StringBuilder) {
        val rows = t.select("tr").take(60).map { tr -> tr.select("th, td").map { it.text().replace('|', '/').trim().take(80) } }.filter { r -> r.any { it.isNotEmpty() } }
        if (rows.isEmpty()) return
        val w = rows.maxOf { it.size }
        rows.forEachIndexed { i, r ->
            sb.append("| ").append((r + List(w - r.size) { "" }).joinToString(" | ")).append(" |\n")
            if (i == 0) sb.append("|").append(" --- |".repeat(w)).append('\n')
        }
    }
}
