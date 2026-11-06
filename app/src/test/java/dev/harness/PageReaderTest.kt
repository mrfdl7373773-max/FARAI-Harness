package dev.harness

import dev.harness.core.search.Ranking
import dev.harness.core.search.SearchHit
import dev.harness.core.search.SearchTrace
import dev.harness.core.web.PageReader
import dev.harness.tools.WebSearchTool
import dev.harness.tools.WebTool
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class PageReaderTest {
    private val reader = PageReader(OkHttpClient())

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("web/$name")!!.readText()

    @Test
    fun extractsDetikArticleWithoutChrome() {
        val url = "https://finance.detik.com/fintech/d-8739902/transaksi-kripto-ri-melonjak-31-96-dalam-sebulan-tembus-rp-30-56-triliun"
        val page = reader.extract(Jsoup.parse(fixture("detik-article.html"), url), url, url)
        assertTrue(page.title, page.title.contains("Kripto", ignoreCase = true))
        assertTrue("text too short: ${page.text.length}", page.words > 80)
        assertTrue(page.text.contains("30,56"))
        // Main content only: far smaller than the page, no menus.
        assertTrue("text ${page.text.length}", page.text.length < 12_000)
    }

    @Test
    fun genericReadabilityPicksTheDensestBlock() {
        val html = """
            <html><head><title>Hello post</title><meta property="article:published_time" content="2026-11-05T10:00:00Z"></head><body>
            <div class="menu"><a href="/a">Home</a> <a href="/b">News</a> <a href="/c">Sport</a></div>
            <div class="wrap"><div class="x">
              <p>${"First paragraph with real words, commas, and enough length to count as content. ".repeat(3)}</p>
              <p>${"Second paragraph that keeps going, adding detail, numbers like 42, and more prose. ".repeat(3)}</p>
              <p>${"Third paragraph closes the story, with a quote, a date, and a final point. ".repeat(3)}</p>
            </div><div class="baca-juga"><a href="/z">Baca juga: something else entirely</a></div></div>
            <div class="footer">Copyright footer text</div></body></html>
        """.trimIndent()
        val page = reader.extract(Jsoup.parse(html, "https://ex.com/p"), "https://ex.com/p", "https://ex.com/p")
        assertEquals("Hello post", page.title)
        assertEquals("2026-11-05T10:00:00Z", page.published)
        assertTrue(page.text.startsWith("First paragraph"))
        assertFalse(page.text.contains("Copyright"))
        assertFalse(page.text.contains("Baca juga"))
        assertFalse(page.text.contains("Sport"))
    }

    @Test
    fun unwrapsAggregatorLinks() {
        assertEquals("https://news.example.com/a?b=1",
            reader.unwrap("https://www.bing.com/news/apiclick.aspx?ref=FexRss&aid=&tid=x&url=https%3a%2f%2fnews.example.com%2fa%3fb%3d1&c=1"))
        assertEquals("https://site.org/x", reader.unwrap("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fsite.org%2Fx&rut=abc"))
        assertEquals("https://plain.com/", reader.unwrap("https://plain.com/"))
    }

    @Test
    fun decodesLegacyGoogleNewsIds() {
        val inner = "https://www.kompas.com/news/123"
        val bytes = byteArrayOf(0x08, 0x13, 0x22, inner.length.toByte()) + inner.toByteArray() + byteArrayOf(0xD2.toByte(), 0x01, 0x00)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertEquals(inner, PageReader.legacyGoogleNews(id))
        assertNull(PageReader.legacyGoogleNews(Base64.getUrlEncoder().encodeToString("\u0008\u0013\"\u00acAU_yqLOJB0W".toByteArray())))
    }

    @Test
    fun parsesBatchExecuteAnswer() {
        val text = ")]}'\n\n[[\"wrb.fr\",\"Fbv4je\",\"[\\\"garturlres\\\",\\\"https://www.cnbcindonesia.com/market/abc\\\",1]\",null,null,null,\"generic\"],[\"di\",42]]"
        assertEquals("https://www.cnbcindonesia.com/market/abc", PageReader.parseBatchUrl(text))
        assertNull(PageReader.parseBatchUrl("nothing here"))
    }

    @Test
    fun readsManyPagesInParallel() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = request.path!!.removePrefix("/p")
                if (n == "bad") return MockResponse().setResponseCode(404)
                val body = "<html><head><title>Page $n</title></head><body><article>" +
                    "<p>${"Content of page $n, written out at length so it counts as an article body. ".repeat(8)}</p></article></body></html>"
                return MockResponse().setBodyDelay(700, TimeUnit.MILLISECONDS).setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
            }
        }
        server.start()
        try {
            val urls = (1..20).map { server.url("/p$it").toString() } + server.url("/pbad").toString()
            val t0 = System.currentTimeMillis()
            val pages = runBlocking { reader.readAll(urls, 4_000) }
            val elapsed = System.currentTimeMillis() - t0
            assertTrue("took ${elapsed}ms, not parallel", elapsed < 3_500)
            assertEquals(21, pages.size)
            assertEquals("Page 3", pages[2].title)
            assertTrue(pages.take(20).all { it.ok })
            assertFalse(pages[20].ok)
            // Output shape round-trips into the transcript rows.
            val out = pages.joinToString("\n\n---\n\n") { WebTool.formatPage(it) }
            val rows = SearchTrace.fetchedPages(out)
            assertEquals(21, rows.size)
            assertEquals("Page 1", rows[0].title)
            assertTrue(rows[20].error)
        } finally { server.shutdown() }
    }

    @Test
    fun searchTraceIgnoresPageContentsWhenParsingHits() {
        val page = PageReader.Page("https://a.com/x", "https://a.com/x", "A title", "a.com", text = "1. Not a hit\n   https://fake.com")
        val text = "Web search: \u201cq\u201d \u00b7 1 results \u00b7 0.5s\nEngines: bing \u27131\n\n1. Real\n   https://a.com/x\n   snippet\n" +
            WebSearchTool.formatPages(listOf(page))
        val trace = SearchTrace.parse("q", text)
        assertEquals(1, trace.sources.size)
        assertEquals(1, trace.pages.size)
        assertEquals("A title", trace.pages[0].title)
    }

    @Test
    fun mergesSameHeadlineAndPrefersPublisherLink() {
        val g = SearchHit("Transaksi Kripto RI Melonjak 31,96% dalam Sebulan", "https://news.google.com/rss/articles/CBMiXYZ?oc=5", source = "detikFinance")
        val b = SearchHit("Transaksi Kripto RI Melonjak 31,96% dalam Sebulan", "https://finance.detik.com/fintech/d-8739902/x")
        val merged = Ranking.merge(mapOf("google_news" to listOf(g), "bing_news" to listOf(b)), emptyMap())
        assertEquals(1, merged.size)
        assertEquals("https://finance.detik.com/fintech/d-8739902/x", merged[0].url)
        assertNotNull(merged[0].source)
    }
}
