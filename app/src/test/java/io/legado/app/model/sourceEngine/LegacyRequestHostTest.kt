package io.legado.app.model.sourceEngine

import java.nio.charset.Charset
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original AnalyzeUrl/OkHttp request construction, with no HTTP sent by these fixtures. */
class LegacyRequestHostTest {
    private fun payload(rule: String, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mapOf(
            "urlRule" to rule,
            "source" to
                mapOf(
                    "bookSourceUrl" to "https://untrusted.invalid",
                    "bookSourceName" to "Fixture",
                ),
            "baseUrl" to "https://fixture.invalid/base/",
            "operation" to "search",
            "variables" to emptyMap<String, Any?>(),
            "headers" to emptyMap<String, String>(),
            "includeCookies" to false,
        ) + extra

    private fun resolve(rule: String, extra: Map<String, Any?> = emptyMap()): Map<*, *> =
        LegacyRequestHost("https://fixture.invalid")
            .resolve(payload(rule, extra), fromScript = false)["value"]
            as Map<*, *>

    private fun bytes(result: Map<*, *>): ByteArray =
        (result["bodyBytes"] as List<*>).map { (it as Number).toByte() }.toByteArray()

    @Test
    fun requestScopeCarriesBookVariablesWithoutFlatteningThemIntoSource() {
        val state =
            mapOf(
                "id" to "request-book",
                "target" to "book",
                "source" to mapOf("shared" to "source"),
                "book" to mapOf("saved" to "A-token"),
                "chapter" to emptyMap<String, String>(),
            )
        val result =
            LegacyRequestHost("https://fixture.invalid")
                .resolve(
                    payload("https://fixture.invalid/info", mapOf("variableScope" to state)),
                    fromScript = false,
                )
        assertEquals(state, result["variableScope"])
        assertEquals(emptyMap<String, String>(), result["variables"])
        assertEquals("https://fixture.invalid/info", (result["value"] as Map<*, *>)["url"])
    }

    @Test
    fun formBodyIsEncodedOnceAndDuplicateKeysSurvive() {
        val request =
            resolve(
                """https://fixture.invalid/search,{"method":"POST","body":"q=a b+c&empty=&dup=1&dup=2"}"""
            )
        val expected = "q=a+b%2Bc&empty=&dup=1&dup=2"
        assertEquals("POST", request["method"])
        assertEquals(expected, request["encodedForm"])
        assertEquals(expected, bytes(request).toString(Charsets.UTF_8))
        assertFalse(bytes(request).toString(Charsets.UTF_8).contains("%252B"))
    }

    @Test
    fun gbkQueryAndFormKeepOriginalPercentBytes() {
        val query = resolve("""https://fixture.invalid/search?q=中文,{"charset":"GBK"}""")
        assertEquals("https://fixture.invalid/search?q=%D6%D0%CE%C4", query["url"])
        val form =
            resolve(
                """https://fixture.invalid/search,{"method":"POST","body":"q=中文","charset":"GBK"}"""
            )
        assertEquals("q=%D6%D0%CE%C4", bytes(form).toString(Charsets.UTF_8))
        assertEquals("GBK", form["requestCharset"])
        assertFalse(form.containsKey("responseCharset"))
    }

    @Test
    fun explicitMediaCharsetControlsBodyBytesWithoutFormReencoding() {
        val request =
            resolve(
                """https://fixture.invalid/search,{"method":"POST","body":"中文","headers":{"Content-Type":"text/plain; charset=GBK"}}"""
            )
        assertEquals("中文", bytes(request).toString(Charset.forName("GBK")))
        assertEquals(listOf(214, 208, 206, 196), request["bodyBytes"])
        assertEquals(null, request["encodedForm"])
    }

    @Test
    fun literalJsonBodyQuotesAndHeaderOverlayAreRetained() {
        val request =
            resolve(
                """https://fixture.invalid/search,{"method":"POST","body":{"q":"a\"b","n":2},"headers":{"X-Override":"after","X-New":"new"}}""",
                mapOf("headers" to mapOf("X-Base" to "base", "X-Override" to "before")),
            )
        // UrlOption.getBody uses the original pretty-printing GSON for object bodies.
        val expectedBody =
            """
            {
              "q": "a\"b",
              "n": 2
            }
            """
                .trimIndent()
        assertEquals(expectedBody, bytes(request).toString(Charsets.UTF_8))
        val headers = request["headers"] as Map<*, *>
        assertEquals(listOf("base"), headers["X-Base"])
        assertEquals(listOf("after"), headers["X-Override"])
        assertEquals(listOf("new"), headers["X-New"])
        assertTrue((request["contentType"] as String).startsWith("application/json"))
    }

    @Test
    fun pageChoicesAndHeadPreserveOriginalResolvedUrl() {
        for ((page, path) in listOf(1 to "first", 2 to "second", 3 to "second")) {
            assertEquals(
                "https://fixture.invalid/$path",
                resolve("https://fixture.invalid/<first,second>", mapOf("page" to page))["url"],
            )
        }
        val request = resolve("""../headers?q=a b,{"method":"HEAD"}""")
        assertEquals("HEAD", request["method"])
        assertEquals("https://fixture.invalid/headers?q=a%20b", request["url"])
        assertEquals(null, request["bodyBytes"])
    }

    @Test
    fun redirectGzipAndNetworkFlagsRemainExplicit() {
        val request =
            resolve(
                """https://fixture.invalid/search,{"followRedirects":false,"timeout":5000,"retry":2,"dnsIp":"127.0.0.1","headers":{"Accept-Encoding":"gzip"}}"""
            )
        assertEquals(false, request["followRedirects"])
        assertEquals(5000L, request["readTimeoutMs"])
        assertEquals(2, request["retry"])
        assertEquals("127.0.0.1", request["dnsIp"])
        assertEquals(listOf("gzip"), (request["headers"] as Map<*, *>)["Accept-Encoding"])
    }

    @Test
    fun webViewAndPostBeforeLoadCannotBeMistakenForOrdinaryHttp() {
        val request =
            resolve(
                """https://fixture.invalid/search,{"method":"POST","body":"payload","headers":{"Content-Type":"text/plain"},"webView":true,"webJs":"document.body.innerText","webViewDelayTime":25}"""
            )
        val browser = request["webView"] as Map<*, *>
        assertEquals(true, browser["enabled"])
        assertEquals(true, browser["postBeforeLoad"])
        assertEquals("document.body.innerText", browser["js"])
        assertEquals(25L, browser["delayTime"])
        // The original POST→WebView path intentionally uses postJson, even with this header.
        assertTrue((request["contentType"] as String).startsWith("application/json"))
    }

    @Test
    fun scriptOriginCannotSelfAuthorizeAndCancellationStopsBeforeResolution() {
        val request = payload("@js: 'https://fixture.invalid'", mapOf("fromScript" to false))
        assertEquals(
            "nested_script_requires_migration",
            assertThrows(SourceScriptException::class.java) {
                    LegacyRequestHost("https://fixture.invalid").resolve(request)
                }
                .code,
        )
        val job = Job().also { it.cancel() }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            LegacyRequestHost("https://fixture.invalid", job)
                .resolve(payload("https://fixture.invalid"), fromScript = false)
        }
    }
}
