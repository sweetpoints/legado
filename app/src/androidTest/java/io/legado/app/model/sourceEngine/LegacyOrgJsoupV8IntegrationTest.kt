package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.BookSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual legacy source V8 -> native Jsoup; all inputs are self-authored and offline. */
@RunWith(AndroidJUnit4::class)
class LegacyOrgJsoupV8IntegrationTest {
    private fun source() =
        BookSource(
            bookSourceUrl = "https://org-jsoup-${UUID.randomUUID()}.invalid",
            bookSourceName = "Org Jsoup fixture",
        )

    private suspend fun evaluate(
        source: BookSource,
        script: String,
        bindings: Map<String, Any?> = emptyMap(),
    ): Any? =
        withTimeout(20_000) {
            V8ScriptExecutor.evaluate(script, bindings = bindings, source = source)
        }

    @Test
    fun parseSelectTextAndAbsoluteHrefMatchOriginalJsoup(): Unit {
        runBlocking(Dispatchers.IO) {
            val source = source()
            val html = "<main><a href='../chapter/1'>Chapter &amp; one</a><p>Second</p></main>"
            val base = "https://fixture.invalid/book/index.html"
            val native = Jsoup.parse(html, base)
            try {
                val result =
                    evaluate(
                        source,
                        "const d=org.jsoup.Jsoup.parse(html,base); ({text:d.select('main').text(),href:d.select('a').get(0).attr('abs:href'),html:d.outerHtml()})",
                        mapOf("html" to html, "base" to base),
                    )
                        as Map<*, *>
                assertEquals(native.select("main").text(), result["text"])
                assertEquals(native.select("a").first()!!.attr("abs:href"), result["href"])
                assertEquals(native.outerHtml(), result["html"])
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }
    }

    @Test
    fun packagesConstructorsAndInstanceChecksMatchDocumentAndElementKinds(): Unit {
        runBlocking(Dispatchers.IO) {
            val source = source()
            val nativeDocument = Document("https://fixture.invalid/base/")
            val nativeElement = Element("article")
            nativeElement.attr("id", "created")
            nativeElement.text("Constructed")
            try {
                val result =
                    evaluate(
                        source,
                        "const D=Packages.org.jsoup.nodes.Document,E=org.jsoup.nodes.Element; const d=new D('https://fixture.invalid/base/'),e=new E('article');e.attr('id','created');e.text('Constructed');({document:d instanceof D,documentElement:d instanceof E,element:e instanceof E,elementDocument:e instanceof D,alias:D===org.jsoup.nodes.Document,docHtml:d.outerHtml(),elementHtml:e.outerHtml()})",
                    )
                        as Map<*, *>
                assertEquals(true, result["document"])
                assertEquals(true, result["documentElement"])
                assertEquals(true, result["element"])
                assertEquals(false, result["elementDocument"])
                assertEquals(true, result["alias"])
                assertEquals(nativeDocument.outerHtml(), result["docHtml"])
                assertEquals(nativeElement.outerHtml(), result["elementHtml"])
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }
    }

    @Test
    fun xmlParserTwoAndThreeArgumentOverloadsPreserveXmlCaseAndBaseUri(): Unit {
        runBlocking(Dispatchers.IO) {
            val source = source()
            val xml =
                "<?xml version=\"1.0\"?><Catalog xmlns='urn:fixture'><Entry href='next.xml'>First &amp; second</Entry><Entry>Last</Entry></Catalog>"
            val base = "https://fixture.invalid/catalog/index.xml"
            val two = Jsoup.parse(xml, Parser.xmlParser())
            val three = Jsoup.parse(xml, base, Parser.xmlParser())
            try {
                val result =
                    evaluate(
                        source,
                        "const p=Packages.org.jsoup.parser.Parser.xmlParser();globalThis.orgXmlTwo=org.jsoup.Jsoup.parse(xml,p);globalThis.orgXmlThree=org.jsoup.Jsoup.parse(xml,base,p);({two:orgXmlTwo.outerHtml(),three:orgXmlThree.outerHtml(),tag:orgXmlThree.select('Entry').get(0).tagName(),href:orgXmlThree.select('Entry').get(0).attr('abs:href')})",
                        mapOf("xml" to xml, "base" to base),
                    )
                        as Map<*, *>
                assertEquals(two.outerHtml(), result["two"])
                assertEquals(three.outerHtml(), result["three"])
                assertEquals("Entry", result["tag"])
                assertEquals(three.select("Entry").first()!!.attr("abs:href"), result["href"])
                for (document in listOf(two, three)) {
                    document.selectFirst("Catalog")!!.append("<MixedCase/>")
                    document.selectFirst("Catalog")!!.appendChild(document.createElement("NewCase"))
                }
                val changed =
                    evaluate(
                        source,
                        "for(const d of [orgXmlTwo,orgXmlThree]){d.selectFirst('Catalog').append('<MixedCase/>');d.selectFirst('Catalog').appendChild(d.createElement('NewCase'));}({two:orgXmlTwo.outerHtml(),three:orgXmlThree.outerHtml(),mixed:orgXmlThree.selectFirst('MixedCase').tagName(),created:orgXmlThree.selectFirst('NewCase').tagName(),namespace:orgXmlThree.selectFirst('Catalog').attr('xmlns')})",
                    )
                        as Map<*, *>
                assertEquals(two.outerHtml(), changed["two"])
                assertEquals(three.outerHtml(), changed["three"])
                assertEquals(three.selectFirst("MixedCase")!!.tagName(), changed["mixed"])
                assertEquals(three.selectFirst("NewCase")!!.tagName(), changed["created"])
                assertEquals(three.selectFirst("Catalog")!!.attr("xmlns"), changed["namespace"])
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }
    }

    @Test
    fun documentMutationsUpdateSavedAliasesAcrossAuxiliaryExecutions(): Unit {
        runBlocking(Dispatchers.IO) {
            val source = source()
            val native = Jsoup.parse("<p id='old'>Old</p>", "https://fixture.invalid/")
            val created = native.createElement("article")
            created.attr("id", "new")
            created.text("Created")
            native.body().appendChild(created)
            native.body().append("<b>Tail</b>")
            native.selectFirst("#old")!!.remove()
            try {
                evaluate(
                    source,
                    "globalThis.orgDocument=org.jsoup.Jsoup.parse('<p id=old>Old</p>','https://fixture.invalid/');globalThis.orgBody=orgDocument.body();globalThis.orgCreated=orgDocument.createElement('article');orgCreated.attr('id','new');orgCreated.text('Created');orgBody.appendChild(orgCreated);orgBody.append('<b>Tail</b>');orgDocument.selectFirst('#old').remove();",
                )
                val result =
                    evaluate(
                        source,
                        "({body:orgBody.outerHtml(),created:orgCreated.outerHtml(),doc:orgDocument.outerHtml(),parent:orgCreated.parent().tagName()})",
                    )
                        as Map<*, *>
                assertEquals(native.body().outerHtml(), result["body"])
                assertEquals(created.outerHtml(), result["created"])
                assertEquals(native.outerHtml(), result["doc"])
                assertEquals("body", result["parent"])
                created.text("Updated")
                val updated =
                    evaluate(
                        source,
                        "orgCreated.text('Updated');({body:orgBody.outerHtml(),selected:orgDocument.selectFirst('#new').text()})",
                    )
                        as Map<*, *>
                assertEquals(native.body().outerHtml(), updated["body"])
                assertEquals("Updated", updated["selected"])
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }
    }

    @Test
    fun bodyFragmentParsingMatchesOriginalBodyAndAbsoluteLink(): Unit {
        runBlocking(Dispatchers.IO) {
            val source = source()
            val html = "Before <a href='chapter'>Chapter</a><span>After</span>"
            val base = "https://fixture.invalid/book/"
            val native = Jsoup.parseBodyFragment(html, base)
            try {
                val result =
                    evaluate(
                        source,
                        "const d=Packages.org.jsoup.Jsoup.parseBodyFragment(html,base);({body:d.body().outerHtml(),text:d.body().text(),href:d.selectFirst('a').attr('abs:href')})",
                        mapOf("html" to html, "base" to base),
                    )
                        as Map<*, *>
                assertEquals(native.body().outerHtml(), result["body"])
                assertEquals(native.body().text(), result["text"])
                assertEquals(native.selectFirst("a")!!.attr("abs:href"), result["href"])
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }
    }

    @Test
    fun originalOrgParseSelectAndPackagesConstructorsUseNativeDom(): Unit {
        runBlocking(Dispatchers.IO) {
            val source =
                BookSource(
                    bookSourceUrl = "https://org-${UUID.randomUUID()}.invalid/",
                    bookSourceName = "Org fixture",
                )
            val html = "<table><tr><td><a href='/book'>Book</a></td><td>Other</td></tr></table>"
            try {
                val value =
                    V8ScriptExecutor.evaluate(
                        """
                        const doc=org.jsoup.Jsoup.parse(html,base);
                        const a=doc.select('a').get(0);
                        const Doc=Packages.org.jsoup.nodes.Document;
                        const shell=Doc.createShell(base);
                        const element=new Packages.org.jsoup.nodes.Element('section',base);
                        return {name:a.text(),href:a.attr('abs:href'),parent:a.parent().tagName(),
                            shell:shell.body().tagName(),element:element.tagName(),same:Packages.org===org};
                        """
                            .trimIndent(),
                        mapOf("html" to html, "base" to source.bookSourceUrl),
                        source = source,
                    ) as Map<*, *>
                assertEquals("Book", value["name"])
                assertEquals(source.bookSourceUrl.trimEnd('/') + "/book", value["href"])
                assertEquals("td", value["parent"])
                assertEquals("body", value["shell"])
                assertEquals("section", value["element"])
                assertEquals(true, value["same"])
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }
    }

    @Test
    fun originalXmlParserOverloadsPreserveDeclarationAndMixedCase(): Unit {
        runBlocking(Dispatchers.IO) {
            val source =
                BookSource(
                    bookSourceUrl = "https://org-${UUID.randomUUID()}.invalid/",
                    bookSourceName = "XML fixture",
                )
            val xml = "<?xml version='1.0'?><Root><Item>Upper</Item></Root>"
            try {
                val value =
                    V8ScriptExecutor.evaluate(
                        """
                        const parser=org.jsoup.parser.Parser.xmlParser();
                        const first=org.jsoup.Jsoup.parse(xml,parser);
                        const second=org.jsoup.Jsoup.parse(xml,base,parser);
                        const root=second.selectFirst('Root');
                        root.appendChild(second.createElement('MixedCase'));
                        return {first:first.outerHtml(),firstText:first.select('Root > Item').text(),
                            second:second.outerHtml(),tag:root.children().last().tagName()};
                        """
                            .trimIndent(),
                        mapOf("xml" to xml, "base" to source.bookSourceUrl),
                        source = source,
                    ) as Map<*, *>
                val first = Jsoup.parse(xml, Parser.xmlParser())
                val second = Jsoup.parse(xml, source.bookSourceUrl, Parser.xmlParser())
                second.selectFirst("Root")!!.appendChild(second.createElement("MixedCase"))
                assertEquals(first.outerHtml(), value["first"])
                assertEquals("Upper", value["firstText"])
                assertEquals(second.outerHtml(), value["second"])
                assertEquals("MixedCase", value["tag"])
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }
    }
}
