package io.legado.app.model.sourceEngine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual V8 -> JSON -> Jsoup callbacks, with no network or native DOM registry. */
@RunWith(AndroidJUnit4::class)
class LegacyDomV8IntegrationTest {
    @Test
    fun nativeElementsKeepTableAncestorsAndSupportOriginalExtractionInputs() =
        runBlocking(Dispatchers.IO) {
            val source =
                BookSource(
                    bookSourceUrl = "https://dom.invalid/" + UUID.randomUUID(),
                    bookSourceName = "DOM fixture",
                )
            val parser =
                AnalyzeRule(source = source)
                    .setCoroutineContext(currentCoroutineContext())
                    .setContent(
                        "<section id='scope'><table><tr><td><a href='/first'>First</a></td><td><b>Second</b></td></tr></table></section>",
                        "https://dom.invalid/base",
                    )
            parser.setRedirectUrl("https://dom.invalid/base")
            try {
                // The original String parser has no DOM base URI; AnalyzeRule.baseUrl
                // is used by URL rules, not Jsoup attr("abs:href"). Compare the native
                // element with the V8 snapshot instead of inventing a different base.
                val nativeLink = (parser.getElements("tag.a").single() as Element).attr("abs:href")
                assertEquals("", nativeLink)
                val result =
                    withTimeout(20_000) {
                        parser.evalJS(
                            """
                            const rows = java.getElements('tag.tr');
                            const first = rows.get(0);
                            ({size:rows.size(), parent:first.parent().tagName(),
                                names:first.select('td').text(), link:first.select('a').get(0).attr('abs:href'),
                                text:java.getString('tag.td.1@text', first),
                                resolved:java.getString('tag.a.0@href', first, true)});
                            """
                                .trimIndent()
                        )
                    }
                        as Map<*, *>
                assertEquals(1, (result["size"] as Number).toInt())
                assertEquals("tbody", result["parent"])
                assertEquals("First Second", result["names"])
                assertEquals(nativeLink, result["link"])
                assertEquals("Second", result["text"])
                assertEquals("https://dom.invalid/first", result["resolved"])
                parser.setContent(
                    Jsoup.parse("<a href='/first'>First</a>", "https://dom.invalid/base"),
                    "https://dom.invalid/base",
                )
                assertEquals(
                    "https://dom.invalid/first",
                    withTimeout(20_000) {
                        parser.evalJS("java.getElements('tag.a').get(0).attr('abs:href')")
                    },
                )
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun storedDomObjectSurvivesAnotherAuxiliaryTaskAndUsesItsOriginalDocument() =
        runBlocking(Dispatchers.IO) {
            val source =
                BookSource(
                    bookSourceUrl = "https://dom.invalid/" + UUID.randomUUID(),
                    bookSourceName = "Retained DOM",
                )
            val parser =
                AnalyzeRule(source = source)
                    .setCoroutineContext(currentCoroutineContext())
                    .setContent(
                        "<section><a id='a'>Alpha</a><a id='b'>Beta</a></section>",
                        "https://dom.invalid/",
                    )
            try {
                withTimeout(20_000) {
                    assertEquals(
                        "Alpha",
                        parser.evalJS(
                            "globalThis.retainedDom=java.getElements('tag.a.0').get(0); retainedDom.text()"
                        ),
                    )
                    parser.setContent("<p>Replaced document</p>", "https://dom.invalid/other")
                    val result =
                        parser.evalJS(
                            "({text:retainedDom.text(),next:retainedDom.nextElementSibling().text(),parent:retainedDom.parent().tagName()})"
                        ) as Map<*, *>
                    assertEquals("Alpha", result["text"])
                    assertEquals("Beta", result["next"])
                    assertEquals("section", result["parent"])
                }
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }
}
