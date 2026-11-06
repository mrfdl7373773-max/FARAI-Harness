package dev.harness

import dev.harness.core.search.Bing
import dev.harness.core.search.BingNews
import dev.harness.core.search.Brave
import dev.harness.core.search.DuckDuckGo
import dev.harness.core.search.EngineBlocked
import dev.harness.core.search.EngineStatus
import dev.harness.core.search.GoogleNews
import dev.harness.core.search.Mojeek
import dev.harness.core.search.Ranking
import dev.harness.core.search.SearchConfig
import dev.harness.core.search.SearchEngine
import dev.harness.core.search.SearchHit
import dev.harness.core.search.SearchRequest
import dev.harness.core.search.SearchResponse
import dev.harness.core.search.SearchTrace
import dev.harness.core.search.WebSearch
import dev.harness.core.search.Wikipedia
import dev.harness.core.search.Yahoo
import dev.harness.core.search.splitRegion
import dev.harness.core.search.unwrapBing
import dev.harness.core.search.unwrapDdg
import dev.harness.core.search.unwrapYahoo
import dev.harness.tools.WebSearchTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class WebSearchTest {
    private val req = SearchRequest("kotlin coroutines")
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("search/$name")!!.readText()

    private fun parses(engine: SearchEngine, file: String, min: Int): List<SearchHit> {
        val hits = engine.parse(fixture(file), req)
        assertTrue("${engine.name}: expected ≥$min results, got ${hits.size}", hits.size >= min)
        hits.forEach {
            assertTrue("${engine.name}: bad url ${it.url}", it.url.startsWith("http"))
            assertTrue("${engine.name}: empty title", it.title.isNotBlank())
            assertFalse("${engine.name}: unwrapped redirect ${it.url}", "duckduckgo.com/l/" in it.url || "bing.com/ck/a" in it.url || "/RU=" in it.url)
        }
        return hits
    }

    @Test fun duckduckgo() { parses(DuckDuckGo(), "duckduckgo.html", 5).let { assertTrue(it.any { h -> h.content.isNotBlank() }) } }
    @Test fun bing() { parses(Bing(), "bing.html", 5).let { assertEquals("Coroutines | Kotlin Documentation", it[0].title); assertEquals("Sep 1, 2026", it[0].publishedDate) } }
    @Test fun yahoo() { parses(Yahoo(), "yahoo.html", 5).let { assertEquals("Coroutines | Kotlin Documentation", it[0].title) } }
    @Test fun brave() { parses(Brave(), "brave.html", 1) }
    @Test fun mojeek() { parses(Mojeek(), "mojeek.html", 1) }
    @Test fun wikipedia() { parses(Wikipedia(), "wikipedia.json", 1).forEach { assertTrue(it.url.contains("wikipedia.org/wiki/")) } }
    @Test fun googleNews() { parses(GoogleNews(), "google_news.xml", 5).let { assertTrue(it.any { h -> h.publishedDate != null && h.source != null }) } }
    @Test fun bingNews() { parses(BingNews(), "bing_news.xml", 1) }

    @Test fun blockPagesAreDetected() {
        try { DuckDuckGo().check(200, "<html><div class=\"anomaly-modal\"></div></html>"); fail() } catch (_: EngineBlocked) {}
        try { Bing().check(429, ""); fail() } catch (_: EngineBlocked) {}
        try { Yahoo().check(200, "<title>Are you a robot?</title>"); fail() } catch (_: EngineBlocked) {}
    }

    @Test fun unwrapsRedirects() {
        assertEquals("https://kotlinlang.org/docs/", unwrapDdg("//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2F&rut=abc"))
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString("https://example.com/a?b=1".toByteArray())
        assertEquals("https://example.com/a?b=1", unwrapBing("https://www.bing.com/ck/a?!&&p=x&u=a1$b64&ntb=1"))
        assertEquals("https://example.org/x", unwrapYahoo("https://r.search.yahoo.com/_ylt=A/RV=2/RE=1/RO=10/RU=https%3a%2f%2fexample.org%2fx/RK=2/RS=abc-"))
    }

    @Test fun regions() {
        assertEquals("us" to "en", splitRegion("us-en"))
        assertEquals("id" to "id", splitRegion("id-id"))
        assertEquals("us" to "en", splitRegion("en-US"))
        assertEquals("us" to "fr", splitRegion("fr"))
    }

    @Test fun mergeRanksAgreementFirstAndDedupes() {
        val merged = Ranking.merge(
            mapOf(
                "duckduckgo" to listOf(SearchHit("A", "https://a.com/x"), SearchHit("B", "https://www.b.com/y/?utm_source=z")),
                "bing" to listOf(SearchHit("B", "http://b.com/y", "longer snippet here"), SearchHit("C", "https://c.com")),
            ),
            mapOf("duckduckgo" to 1.2, "bing" to 1.0),
        )
        assertEquals(3, merged.size)
        assertEquals("B", merged[0].title)
        assertEquals(listOf("duckduckgo", "bing"), merged[0].engines)
        assertEquals("longer snippet here", merged[0].content)
        assertEquals(1.0, merged[0].score, 0.0001)
        assertTrue(Ranking.domainMatches("https://docs.python.org/3/", listOf("python.org")))
        assertFalse(Ranking.domainMatches("https://notpython.org/", listOf("python.org")))
    }

    @Test fun configFromMarkdown() {
        val md = "# x\n```json\n{\n  // comment\n  \"engines\": [\"bing\", \"nope\", \"duckduckgo\"],\n  \"region\": \"id-id\",\n  \"max_results\": 5,\n  \"mode\": \"fast\",\n  \"endpoint\": \"http://10.0.0.2:8000\",\n}\n```"
        val c = SearchConfig.parse(md)
        assertEquals(listOf("bing", "duckduckgo"), c.engines)
        assertEquals("id-id", c.region)
        assertEquals(5, c.maxResults)
        assertEquals(25, SearchConfig.parse("```json\n{\"max_results\": 99}\n```").maxResults)
        assertEquals(5, SearchConfig.parse("```json\n{\"max_results\": 2}\n```").maxResults)
        assertEquals("fast", c.mode)
        assertEquals("http://10.0.0.2:8000", c.endpoint)
        assertEquals(SearchConfig(), SearchConfig.parse(null))
    }

    @Test fun endpointJson() {
        val res = WebSearch.parseEndpoint(
            """{"query":"q","answer":"42","results":[{"title":"T","url":"https://t.dev","content":"c","engines":["duckduckgo","brave"]},{"title":"x","url":"ftp://no"}]}""",
            SearchRequest("q"), "json",
        )
        assertEquals(1, res.results.size)
        assertEquals("42", res.answer)
        assertEquals(listOf("duckduckgo", "brave"), res.results[0].engines)
        assertEquals("A", WebSearch.parseDdgAnswer("""{"Answer":"","AbstractText":"A"}"""))
    }

    @Test fun toolOutputRoundTripsIntoTheTrace() {
        val res = SearchResponse(
            "kotlin flow", listOf(
                SearchHit("Flow | Kotlin", "https://kotlinlang.org/docs/flow.html", "Asynchronous flow", engines = listOf("duckduckgo", "bing")),
                SearchHit("No snippet", "https://example.com/p", "", "2026-10-01T00:00:00Z", engines = listOf("brave")),
                SearchHit("Third", "https://www.third.io/", "s", source = "Third News", engines = listOf("bing")),
            ),
            answer = "Flows are cold streams.",
            engines = listOf(EngineStatus("duckduckgo", true, 10), EngineStatus("brave", false, error = "resting (blocked)")),
            elapsedMs = 1234,
        )
        val text = WebSearchTool.format(res, SearchRequest("kotlin flow"))
        val t = SearchTrace.parse("kotlin flow", text)
        assertEquals(3, t.sources.size)
        assertEquals("Flow | Kotlin", t.sources[0].title)
        assertEquals("example.com", t.sources[1].domain)
        assertEquals("2026-10-01T00:00:00Z", t.sources[1].date)
        assertEquals("third.io", t.sources[2].domain)
        assertEquals("Flows are cold streams.", t.answer)
        assertEquals("1.2", t.seconds)
        assertEquals(2, t.engines.size)
        assertTrue(t.engines[0].ok)
        assertFalse(t.engines[1].ok)
        assertNotNull(SearchTrace.fetchedTitle("URL: x\n\n# Hello page\ntext"))
    }

    /** Real engines over the network; opt in with FARAI_LIVE_SEARCH=1. */
    @Test fun liveSearch() {
        org.junit.Assume.assumeTrue(System.getenv("FARAI_LIVE_SEARCH") == "1")
        val ws = WebSearch(okhttp3.OkHttpClient())
        val res = kotlinx.coroutines.runBlocking { ws.search(SearchRequest("kotlin coroutines flow", maxResults = 8), SearchConfig(cacheMinutes = 0)) }
        println(WebSearchTool.format(res, SearchRequest("kotlin coroutines flow")))
        val news = kotlinx.coroutines.runBlocking { ws.search(SearchRequest("artificial intelligence", type = "news", maxResults = 5), SearchConfig(cacheMinutes = 0)) }
        println(WebSearchTool.format(news, SearchRequest("artificial intelligence", type = "news")))
        assertTrue(res.results.isNotEmpty())
    }
}
