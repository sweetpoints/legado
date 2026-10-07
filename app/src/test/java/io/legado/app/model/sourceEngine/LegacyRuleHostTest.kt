package io.legado.app.model.sourceEngine

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
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
        val rows =
            host().evaluate(request("@XPath://a[@id='b']", mode = "elements"))["value"] as List<*>
        assertEquals(listOf("<a id=\"b\" href=\"/two\">Two</a>"), rows)
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
            host().evaluate(request("@Json:\$.items[?(@.rating > 1)].name", input, "list"))["value"],
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
}
