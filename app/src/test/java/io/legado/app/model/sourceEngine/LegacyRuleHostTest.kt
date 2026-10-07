package io.legado.app.model.sourceEngine

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hard-coded goldens exercise the existing Java parsers, without networking or a V8 fake. */
class LegacyRuleHostTest {
    private val html =
        "<section id='books'><a id='a' href='/one'>One</a><a id='b' href='/two'>Two</a><a id='c' href='/three'>Three</a></section>"

    private fun request(
        rule: String,
        input: Any = html,
        mode: String = "scalar",
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        mapOf(
            "rule" to rule,
            "input" to input,
            "mode" to mode,
            "source" to
                mapOf(
                    "bookSourceUrl" to "https://untrusted.invalid",
                    "bookSourceName" to "Fixture",
                ),
            "operation" to "search",
            "baseUrl" to "https://fixture.invalid/books/",
            "isUrl" to false,
            "unescape" to true,
            "variables" to emptyMap<String, Any?>(),
        ) + extra

    private fun host() = LegacyRuleHost("https://fixture.invalid", EmptyCoroutineContext)

    @Test
    fun legacyCssNegativeIndexExclusionAndFallbackUseActualJsoupGrammar() {
        val host = host()
        assertEquals(
            listOf("Three", "One"),
            host.evaluate(request("tag.a[-1,0]@text", mode = "list"))["value"],
        )
        assertEquals(
            listOf("Two"),
            host.evaluate(request("tag.a!0:2@text", mode = "list"))["value"],
        )
        assertEquals("One", host.evaluate(request(".missing@text||tag.a.0@text"))["value"])
    }

    @Test
    fun javaXPathPredicateSiblingAxisAndTextProjectionStayExact() {
        val result =
            host()
                .evaluate(
                    request(
                        "@XPath://a[@id='a']/following-sibling::a[position()=last()]/text()",
                        mode = "list",
                    )
                )
        assertEquals(listOf("Three"), result["value"])
        val task = host()
        val rows =
            task.evaluate(request("@XPath://a[@id='b']", mode = "elements"))["value"] as List<*>
        val row = rows.single() as Map<*, *>
        assertEquals(setOf(LegacyRuleHost.NODE_REF), row.keys)
        assertEquals("/two", task.evaluate(request("tag.a@href", row))["value"])
    }

    @Test
    fun regexRecordsKeepAllCapturesAndJavaLookbehind() {
        val result =
            host().evaluate(request(":(?<=#)([0-9]+):([A-Za-z]+)", "#12:One #34:Two", "elements"))
        assertEquals(
            listOf(listOf("12:One", "12", "One"), listOf("34:Two", "34", "Two")),
            result["value"],
        )
        assertEquals("One", host().evaluate(request("\$2", listOf("12:One", "12", "One")))["value"])
    }

    @Test
    fun jsonPathFiltersAndMapFieldsTransportOnlyJson() {
        val input =
            mapOf(
                "items" to
                    listOf(
                        mapOf("name" to "One", "rating" to 1),
                        mapOf("name" to "Two", "rating" to 3),
                    )
            )
        assertEquals(
            listOf("Two"),
            host()
                .evaluate(request("@Json:\$.items[?(@.rating > 1)].name", input, "list"))["value"],
        )
        assertEquals("Book", host().evaluate(request("name", mapOf("name" to "Book")))["value"])
    }

    @Test
    fun scalarUrlResolutionAndUnescapeFlagUseLegacyBehaviour() {
        assertEquals(
            "https://fixture.invalid/one",
            host().evaluate(request("tag.a.0@href", extra = mapOf("isUrl" to true)))["value"],
        )
        val encoded = "<p>&amp;lt;Z&amp;gt;</p>"
        assertEquals("<Z>", host().evaluate(request("tag.p@text", encoded))["value"])
        assertEquals(
            "&lt;Z&gt;",
            host()
                .evaluate(request("tag.p@text", encoded, extra = mapOf("unescape" to false)))[
                    "value"],
        )
    }

    @Test
    fun taskWritesSurviveStaleSnapshotsAndTravelToNextPageWithoutContextLeakage() {
        val first = host()
        val saved =
            first.evaluate(
                request(
                    "@put:{\"saved\":\"tag.a.1@text\"}tag.a.0@text",
                    extra = mapOf("variables" to mapOf("page" to 1, "taskId" to "private-task")),
                )
            )
        assertEquals("One", saved["value"])
        assertEquals(mapOf("saved" to "Two"), saved["variables"])
        assertEquals(
            "Two",
            first
                .evaluate(
                    request("@get:{saved}", extra = mapOf("variables" to mapOf("saved" to "stale")))
                )["value"],
        )
        assertEquals(
            "2",
            first
                .evaluate(request("@get:{page}", extra = mapOf("variables" to mapOf("page" to 2))))[
                    "value"],
        )
        val second = host()
        assertEquals(
            "Two",
            second
                .evaluate(
                    request("@get:{saved}", extra = mapOf("variables" to saved["variables"]))
                )["value"],
        )
    }

    @Test
    fun supportsPureGrammarDoesNotEnableJsOrPipelineHooks() {
        for (rule in
            listOf(
                "tag.a[-1,0]@text",
                "@XPath://a/text()",
                ":(?<=#)([0-9]+)",
                "@Json:\$.items[*]",
                "@get:{saved}",
            )) {
            assertTrue(rule, LegacyRuleHost.supportsRule(rule))
        }
        for (rule in
            listOf(
                "@js: result",
                "<js>result</js>",
                "@webjs:document.body",
                "{{java.get('saved')}}",
                "@put:{\"saved\":\"@js: result\"}tag.a@text",
            )) {
            assertFalse(rule, LegacyRuleHost.supportsRule(rule))
            val error =
                assertThrows(SourceScriptException::class.java) { host().evaluate(request(rule)) }
            assertEquals("nested_script_requires_migration", error.code)
        }
    }

    @Test
    fun cancelledTaskAndNonJsonPayloadAreRejectedBeforeParsing() {
        val job = Job().also { it.cancel() }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            LegacyRuleHost("https://fixture.invalid", job).evaluate(request("tag.a@text"))
        }
        assertThrows(BookSourceBindingsUnsupportedException::class.java) {
            host().evaluate(request("tag.a@text", extra = mapOf("input" to Any())))
        }
    }

    @Test
    fun onlyExplicitOuterCapabilityAllowsJavascriptAndInlineExpressions() {
        for (rule in
            listOf(
                "@js: result",
                "<js>result</js>",
                "tag.a@text<js>result.toUpperCase()</js>",
                "{{result}}",
            )) {
            assertTrue(rule, LegacyRuleHost.supportsRule(rule, allowJs = true))
            assertFalse(rule, LegacyRuleHost.supportsRule(rule))
        }
        assertFalse(LegacyRuleHost.supportsRule("@webjs:document.body", allowJs = true))
        assertEquals(
            "legacy_requires_migration",
            assertThrows(SourceScriptException::class.java) {
                    host().evaluate(request("@webjs:document.body"), fromScript = false)
                }
                .code,
        )
    }

    @Test
    fun payloadCannotClaimToBeAnOuterRequestAndBypassDefaultNestedGuard() {
        val payload = request("@js: result", extra = mapOf("fromScript" to false))
        assertEquals(
            "nested_script_requires_migration",
            assertThrows(SourceScriptException::class.java) {
                    host().evaluate(payload)
                }
                .code,
        )
        assertEquals(
            "nested_script_requires_migration",
            assertThrows(SourceScriptException::class.java) {
                    host().evaluate(payload, fromScript = true)
                }
                .code,
        )
    }

    @Test
    fun bookAndChapterSnapshotNamesKeepLegacyGetSpecialCasesReadOnly() {
        val book = mapOf("name" to "Snapshot Book", "bookUrl" to "https://fixture.invalid/book")
        val chapter = mapOf("title" to "Snapshot Chapter", "index" to 7)
        val variables = mapOf("book" to book, "chapter" to chapter)
        val task = host()
        val name =
            task.evaluate(request("@get:{bookName}", extra = mapOf("variables" to variables)))
        val title = task.evaluate(request("@get:{title}", extra = mapOf("variables" to variables)))
        assertEquals("Snapshot Book", name["value"])
        assertEquals("Snapshot Chapter", title["value"])
        assertEquals(emptyMap<String, String>(), name["variables"])
        assertEquals(emptyMap<String, String>(), title["variables"])
        assertEquals("Snapshot Book", book["name"])
        assertEquals("Snapshot Chapter", chapter["title"])
    }

    @Test
    fun xpathListThenFieldRetainsAncestorParentAndBothSiblingAxes() {
        val task = host()
        val row =
            (task.evaluate(request("@XPath://a[@id='b']", mode = "elements"))["value"] as List<*>)
                .single()!!
        val expected =
            mapOf(
                "@XPath:ancestor::section/@id" to listOf("books"),
                "@XPath:../@id" to listOf("books"),
                "@XPath:preceding-sibling::a/text()" to listOf("One"),
                "@XPath:following-sibling::a/text()" to listOf("Three"),
            )
        for ((rule, value) in expected) {
            assertEquals(rule, value, task.evaluate(request(rule, row, "list"))["value"])
        }
        // Legacy Jsoup selection stays inside the selected row's query root, even
        // while XPath axes retain the original DOM parent and sibling context.
        assertEquals("", task.evaluate(request("section > a#b@text", row))["value"])
        assertEquals("Two", task.evaluate(request("a#b@text", row))["value"])
        // The old JSON outerHtml boundary demonstrably detaches the same row.
        val fragment = Jsoup.parse(html).getElementById("b")!!.outerHtml()
        assertEquals(
            emptyList<String>(),
            host().evaluate(request("@XPath:ancestor::section/@id", fragment, "list"))["value"],
        )
        assertEquals(
            emptyList<String>(),
            host()
                .evaluate(request("@XPath:preceding-sibling::a/text()", fragment, "list"))["value"],
        )
    }

    @Test
    fun cssTableListThenFieldRetainsTrTdAndNthChildContext() {
        val table =
            "<table id='rows'><tbody><tr><td>One</td><td>Author 1</td></tr><tr><td>Two</td><td>Author 2</td></tr></tbody></table>"
        val task = host()
        val rows = task.evaluate(request("tag.tr", table, "elements"))["value"] as List<*>
        assertEquals(2, rows.size)
        val row = rows[1]!!
        assertEquals("Two", task.evaluate(request("tag.td.0@text", row))["value"])
        assertEquals("Two Author 2", task.evaluate(request("tr:nth-child(2)@text", row))["value"])
        assertEquals(
            listOf("rows"),
            task.evaluate(request("@XPath:ancestor::table/@id", row, "list"))["value"],
        )
        val fragment = Jsoup.parse(table).select("tr")[1].outerHtml()
        assertEquals("", host().evaluate(request("tag.td.0@text", fragment))["value"])
    }

    @Test
    fun nodeReferencesAreIdentityDeduplicatedAndCannotCrossTasksOrBeGuessed() {
        val task = host()
        val root =
            (task.evaluate(request("tag.section", mode = "elements"))["value"] as List<*>)
                .single()!!
        val first = task.evaluate(request("tag.a", root, "elements"))["value"]
        val repeated = task.evaluate(request("tag.a", root, "elements"))["value"]
        assertEquals(first, repeated)
        val ref = (first as List<*>).first() as Map<*, *>
        assertTrue(ref[LegacyRuleHost.NODE_REF] is String)
        for (input in
            listOf(
                ref,
                mapOf(LegacyRuleHost.NODE_REF to "not-a-known-token"),
                mapOf(LegacyRuleHost.NODE_REF to 7),
            )) {
            assertEquals(
                "invalid_request",
                assertThrows(SourceScriptException::class.java) {
                        host().evaluate(request("tag.a@text", input))
                    }
                    .code,
            )
        }
    }

    @Test
    fun ordinaryJsonRowsAreNeverConvertedToDomReferences() {
        val record = mapOf("name" to "Record", "url" to "/record")
        val task = host()
        val rows =
            task
                .evaluate(
                    request("@Json:\$.items[*]", mapOf("items" to listOf(record)), "elements")
                )["value"]
                as List<*>
        assertEquals(listOf(record), rows)
        assertEquals("Record", task.evaluate(request("name", rows.single()!!))["value"])
    }

    @Test
    fun completingOrCancellingTaskReleasesItsRetainedNodes() {
        for (cancel in listOf(false, true)) {
            val job = Job()
            val task = LegacyRuleHost("https://fixture.invalid", job)
            task.evaluate(request("tag.a", mode = "elements"))
            val registry =
                LegacyRuleHost::class.java.getDeclaredField("nodes").apply { isAccessible = true }
            assertEquals(3, (registry.get(task) as Map<*, *>).size)
            if (cancel) job.cancel() else job.complete()
            assertEquals(0, (registry.get(task) as Map<*, *>).size)
        }
    }

    @Test
    fun explicitTaskCloseInvalidatesRefsWithoutWaitingForLongLivedCallerJob() {
        val job = Job()
        val task = LegacyRuleHost("https://fixture.invalid", job)
        val row = (task.evaluate(request("tag.a", mode = "elements"))["value"] as List<*>).first()!!
        task.close()
        assertTrue(job.isActive)
        val registry =
            LegacyRuleHost::class.java.getDeclaredField("nodes").apply { isAccessible = true }
        assertEquals(0, (registry.get(task) as Map<*, *>).size)
        assertEquals(
            "invalid_request",
            assertThrows(SourceScriptException::class.java) {
                    task.evaluate(request("tag.a@text", row))
                }
                .code,
        )
        task.close()
        job.complete()
    }

    @Test
    fun infoInitPreservesWholeCssElementsContainerRatherThanFirstNode() {
        val task = host()
        val initial = task.evaluate(request("tag.a", mode = "element"))["value"]!!
        assertEquals(setOf(LegacyRuleHost.VALUE_REF), (initial as Map<*, *>).keys)
        assertEquals("Two", task.evaluate(request("tag.a.1@text", initial))["value"])
        assertEquals("Three", task.evaluate(request("tag.a.-1@text", initial))["value"])
        assertEquals(
            "invalid_request",
            assertThrows(SourceScriptException::class.java) {
                    host().evaluate(request("tag.a@text", initial))
                }
                .code,
        )
    }

    @Test
    fun infoInitPreservesXPathListAndRegexCapturesForLaterFields() {
        val task = host()
        val xpath = task.evaluate(request("@XPath://a", mode = "element"))["value"]!!
        assertEquals("Two", task.evaluate(request("tag.a.1@text", xpath))["value"])
        val regex = task.evaluate(request(":([0-9]+):([A-Za-z]+)", "12:Book", "element"))["value"]!!
        assertEquals("Book", task.evaluate(request("\$2", regex))["value"])
        assertEquals(
            "invalid_request",
            assertThrows(SourceScriptException::class.java) {
                    task.evaluate(request(":([0-9]+)", "nothing", "element"))
                }
                .code,
        )
    }

    @Test
    fun legacyContentFormattingKeepsImagesIndentationAndExactlyOneHtmlDecode() {
        val raw = "Start<p>A&nbsp;B</p><p>&lt;C&gt;</p><img src='../images/x.png'>"
        val result =
            host()
                .evaluate(
                    request(
                        "raw",
                        mapOf("raw" to raw),
                        "content",
                        mapOf(
                            "baseUrl" to "https://fixture.invalid/books/chapter.html",
                            "variables" to
                                mapOf(
                                    "__legacyContentFormat" to true,
                                    "__legacyAdaptSpecialStyle" to false,
                                ),
                        ),
                    )
                )
        assertEquals(
            "Start\n　　A B\n　　<C>\n　　<img src=\"https://fixture.invalid/images/x.png\">",
            result["value"],
        )
        val doubleEncoded =
            host()
                .evaluate(
                    request(
                        "raw",
                        mapOf("raw" to "Start<p>&amp;lt;C&amp;gt;</p>"),
                        "content",
                        mapOf(
                            "variables" to
                                mapOf(
                                    "__legacyContentFormat" to true,
                                    "__legacyAdaptSpecialStyle" to false,
                                )
                        ),
                    )
                )
        assertEquals("Start\n　　&lt;C&gt;", doubleEncoded["value"])
    }

    @Test
    fun mediaContentBypassesHtmlFormattingAndSpecialStyleUsesOriginalPlaceholderRule() {
        val raw = "https://fixture.invalid/audio?a=1&amp;b=2"
        for (type in listOf(32, 4)) {
            assertEquals(
                raw,
                host()
                    .evaluate(
                        request(
                            "raw",
                            mapOf("raw" to raw),
                            "content",
                            mapOf("variables" to mapOf("book" to mapOf("type" to type))),
                        )
                    )["value"],
            )
        }
        val protected = "<usehtml><b>A&amp;B</b></usehtml>"
        val result =
            host()
                .evaluate(
                    request(
                        "raw",
                        mapOf("raw" to protected),
                        "content",
                        mapOf(
                            "variables" to
                                mapOf(
                                    "__legacyContentFormat" to true,
                                    "__legacyAdaptSpecialStyle" to true,
                                )
                        ),
                    )
                )
        assertEquals(protected, result["value"])
    }

    @Test
    fun explicitTaskCloseAlsoReleasesWholeInitContainers() {
        val task = host()
        val initial = task.evaluate(request("tag.a", mode = "element"))["value"]!!
        val registry =
            LegacyRuleHost::class.java.getDeclaredField("storedValues").apply {
                isAccessible = true
            }
        assertEquals(1, (registry.get(task) as Map<*, *>).size)
        task.close()
        assertEquals(0, (registry.get(task) as Map<*, *>).size)
        assertEquals(
            "invalid_request",
            assertThrows(SourceScriptException::class.java) {
                    task.evaluate(request("tag.a@text", initial))
                }
                .code,
        )
    }
}
