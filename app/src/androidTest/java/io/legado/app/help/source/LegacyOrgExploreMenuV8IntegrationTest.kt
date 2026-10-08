package io.legado.app.help.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.entities.BookSource
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** User-facing discovery entry, actual V8/org Jsoup, synthetic menus only. */
@RunWith(AndroidJUnit4::class)
class LegacyOrgExploreMenuV8IntegrationTest {
    private fun source() =
        BookSource(
            bookSourceUrl = "https://org-explore-${UUID.randomUUID()}.invalid/base/",
            bookSourceName = "Org discovery fixture",
        )

    private fun menu(documentExpression: String) =
        "JSON.stringify(($documentExpression).select('nav a').toArray().map(function(a){return {title:a.text(),url:a.attr('abs:href'),style:{layout_flexGrow:1,layout_flexShrink:0.5,layout_alignSelf:'center',layout_flexBasisPercent:0.5,layout_wrapBefore:true,layout_justifySelf:'center'}};}))"

    private suspend fun assertMenuAndCache(source: BookSource, native: Document) =
        withTimeout(20_000) {
            val links = native.select("nav a")
            repeat(2) {
                val kinds = source.exploreKinds()
                assertFalse(
                    "ERROR menus must never be accepted as successful discovery",
                    kinds.any { it.title.startsWith("ERROR:") },
                )
                assertEquals(links.size, kinds.size)
                for ((index, kind) in kinds.withIndex()) {
                    assertEquals(links[index].text(), kind.title)
                    assertEquals(links[index].attr("abs:href"), kind.url)
                    assertNotNull(kind.style)
                    val style = kind.style!!
                    assertEquals(1F, style.layout_flexGrow, 0F)
                    assertEquals(0.5F, style.layout_flexShrink, 0F)
                    assertEquals("center", style.layout_alignSelf)
                    assertEquals(0.5F, style.layout_flexBasisPercent, 0F)
                    assertTrue(style.layout_wrapBefore)
                    assertEquals("center", style.layout_justifySelf)
                }
            }
            val cached = JsonParser.parseString(source.exploreKindsJson()).asJsonArray
            assertEquals(links.size, cached.size())
            for (index in 0 until cached.size()) {
                val item = cached[index].asJsonObject
                assertEquals(links[index].text(), item["title"].asString)
                assertEquals(links[index].attr("abs:href"), item["url"].asString)
                assertEquals("center", item["style"].asJsonObject["layout_alignSelf"].asString)
            }
        }

    @Test
    fun taggedOrgParserDiscoveryReturnsExactStyledMenusWithTrailingComment(): Unit {
        runBlocking(Dispatchers.IO) {
            val source = source()
            source.exploreUrl =
                "<js>" +
                    menu(
                        "org.jsoup.Jsoup.parse(" +
                            GSON.toJson(HTML) +
                            "," +
                            GSON.toJson(source.bookSourceUrl) +
                            ")"
                    ) +
                    "</js>\n// trailing discovery comment"
            try {
                assertMenuAndCache(source, Jsoup.parse(HTML, source.bookSourceUrl))
            } finally {
                source.clearExploreKindsCache()
                DartSourceEngine.clearSourceState(source)
            }
        }
    }

    @Test
    fun packagesJsoupAliasInitializesInSourceLibraryBeforeDiscoveryScript(): Unit {
        runBlocking(Dispatchers.IO) {
            val source = source()
            source.jsLib =
                "var DiscoveryJsoup=Packages.org.jsoup.Jsoup;var discoveryHtml=" +
                    GSON.toJson(HTML) +
                    ";var discoveryBase=" +
                    GSON.toJson(source.bookSourceUrl) +
                    ";"
            source.exploreUrl = "@js:" + menu("DiscoveryJsoup.parse(discoveryHtml,discoveryBase)")
            try {
                assertMenuAndCache(source, Jsoup.parse(HTML, source.bookSourceUrl))
            } finally {
                source.clearExploreKindsCache()
                DartSourceEngine.clearSourceState(source)
            }
        }
    }

    @Test
    fun nativeConnectionDiscoveryParsesRealLocalNavigationAndCachesStyledMenus(): Unit {
        runBlocking(Dispatchers.IO) {
            val server =
                object : NanoHTTPD("127.0.0.1", 0) {
                    override fun serve(session: IHTTPSession): Response =
                        newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", HTML)
                }
            server.start()
            val url = "http://127.0.0.1:${server.listeningPort}/base/index.html"
            val source = source()
            source.exploreUrl =
                "@js:" + menu("org.jsoup.Jsoup.connect(" + GSON.toJson(url) + ").get()")
            try {
                assertMenuAndCache(source, Jsoup.parse(HTML, url))
            } finally {
                source.clearExploreKindsCache()
                DartSourceEngine.clearSourceState(source)
                server.stop()
            }
        }
    }

    companion object {
        private const val HTML =
            "<nav><a href='first'>文学 &amp; 古籍</a><a href='../second'>第二类</a></nav>"
    }
}
