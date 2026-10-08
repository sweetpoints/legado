package io.legado.app.model.sourceEngine

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Test

/** Real project Jsoup parsing and constructors, without networking or a fake parser. */
class NativeOrgJsoupHostTest {
    private val host = NativeOrgJsoupHost()

    private suspend fun node(method: String, vararg args: Any?) =
        LegacyDomHost.restore(
            host.call("registered-owner", method, args.toList(), EmptyCoroutineContext) as Map<*, *>
        )

    @Test
    fun htmlParseKeepsRelativeLinksAndDocumentStructure(): Unit = runBlocking {
        val html =
            "<title>Fixture</title><table><tr><td>A</td></tr></table><a href='../book'>Link</a>"
        val base = "https://fixture.invalid/list/page"
        val actual = node("orgJsoup.parse", html, base) as Document
        val original = Jsoup.parse(html, base)
        assertEquals(original.outerHtml(), actual.outerHtml())
        assertEquals("https://fixture.invalid/book", actual.selectFirst("a")!!.absUrl("href"))
        assertEquals("A", actual.select("table > tbody > tr > td").text())
        assertEquals("Fixture", actual.title())
    }

    @Test
    fun xmlParserMarkerUsesActualXmlParsing(): Unit = runBlocking {
        val xml = "<Root><MixedCase id='A'/></Root>"
        val actual = node("orgJsoup.parse", xml, "", mapOf("__legacyOrgParser" to "xml"))
        assertEquals(Jsoup.parse(xml, "", Parser.xmlParser()).outerHtml(), actual.outerHtml())
        assertTrue(actual.outerHtml().contains("MixedCase"))
    }

    @Test
    fun fragmentAndConstructorsRetainTheirDifferentNativeShapes(): Unit = runBlocking {
        val fragment =
            node("orgJsoup.parseBodyFragment", "<p>Fragment</p>", "https://fixture.invalid/")
        assertEquals(
            Jsoup.parseBodyFragment("<p>Fragment</p>", "https://fixture.invalid/").outerHtml(),
            fragment.outerHtml(),
        )
        val empty = node("orgJsoup.newDocument", "https://fixture.invalid/") as Document
        assertEquals(Document("https://fixture.invalid/").outerHtml(), empty.outerHtml())
        val shell = node("orgJsoup.newDocument", "https://fixture.invalid/", "shell") as Document
        assertEquals(
            Document.createShell("https://fixture.invalid/").outerHtml(),
            shell.outerHtml(),
        )
        val element = node("orgJsoup.newElement", "div", "https://fixture.invalid/") as Element
        assertEquals(Element("div", "https://fixture.invalid/").outerHtml(), element.outerHtml())
        assertEquals("https://fixture.invalid/", element.baseUri())
    }

    @Test
    fun unimplementedReflectionAndWrongTypedOverloadsAreRejected(): Unit = runBlocking {
        val error = runCatching {
            host.call(
                "owner",
                "orgJsoup.forName",
                listOf("java.lang.System"),
                EmptyCoroutineContext,
            )
        }.exceptionOrNull()
        assertEquals("legacy.unsupported_org_api", (error as SourceScriptException).code)
        assertEquals(
            "invalid_request",
            (runCatching { node("orgJsoup.parse", mapOf("html" to "wrong")) }.exceptionOrNull()
                    as SourceScriptException)
                .code,
        )
        val job = Job().apply { cancel() }
        assertTrue(
            runCatching { host.call("owner", "orgJsoup.parse", listOf("<p>x</p>"), job) }
                .exceptionOrNull() is kotlinx.coroutines.CancellationException
        )
    }
}
