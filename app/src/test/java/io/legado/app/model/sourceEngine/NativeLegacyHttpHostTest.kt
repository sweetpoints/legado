package io.legado.app.model.sourceEngine

import io.legado.app.help.JsExtensions
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.StrResponse
import java.io.IOException
import kotlinx.coroutines.Job
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Connection
import org.junit.Assert.*
import org.junit.Test

class NativeLegacyHttpHostTest {
    private class Invocation(val method: String, val args: List<Any?>) : RuntimeException()
    private class HeaderSource : BaseSource by BookSource(bookSourceUrl = "https://fixture.invalid", bookSourceName = "Header fixture") {
        override var header: String? = "@js:globalThis.headerRuns++"
        val values = mutableMapOf<String, String>()
        override fun getSource(): BaseSource = this
        override fun getHeaderMap(hasLoginHeader: Boolean): HashMap<String, String> = throw AssertionError("Header script must not re-enter native eval")
        override fun getLoginHeaderMap(): Map<String, String> = mapOf("Authorization" to "fixture-login")
        override fun get(key: String) = values[key].orEmpty()
        override fun put(key: String, value: String): String { values[key] = value; return value }
    }
    private class Extensions(private val boundSource: BaseSource? = null) : JsExtensions {
        val job = Job()
        override fun getSource() = boundSource
        override fun getTag() = "fixture"
        override fun getSourceNavigationContext() = job
        override fun get(urlStr: String, headers: Any?, timeout: Int?): Connection.Response = throw Invocation("get", listOf(urlStr, headers, timeout))
        override fun post(urlStr: String, body: String, headers: Any?, timeout: Int?): Connection.Response = throw Invocation("post", listOf(urlStr, body, headers, timeout))
        override fun head(urlStr: String, headers: Any?, timeout: Int?): Connection.Response = throw Invocation("head", listOf(urlStr, headers, timeout))
    }
    private class Requests : NativeLegacyHttpHost.Requests {
        val calls = mutableListOf<List<Any?>>()
        var fail = false
        val resolvedSources = mutableListOf<BaseSource>()
        fun response(): StrResponse = StrResponse(Response.Builder()
            .request(Request.Builder().url("https://fixture.invalid/response").build())
            .protocol(Protocol.HTTP_1_1).code(201).message("Created")
            .addHeader("X-Fixture", "first").addHeader("X-Fixture", "last").build(), "native-body")
        override fun ajax(url: String, timeout: Long?, extensions: JsExtensions): StrResponse {
            calls.add(listOf(url, timeout)); extensions.getSource()?.let(resolvedSources::add); if (fail) throw IOException("private-detail")
            return response()
        }
        override fun connect(url: String, headers: String?, timeout: Long?, extensions: JsExtensions): StrResponse {
            calls.add(listOf(url, headers, timeout)); extensions.getSource()?.let(resolvedSources::add); return response()
        }
    }
    @Test fun rawAjaxOptionsAndConnectHeadersAreNotRecompiledOrRetried() {
        val ext = Extensions(); val requests = Requests()
        val raw = "https://fixture.invalid/search,{\"method\":\"POST\"}"
        assertEquals("native-body", NativeLegacyHttpHost.call("javaHttp.ajax", listOf(listOf(raw, "ignored"), 4000.0), ext, requests))
        val headers = "{\"X-Fixture\":\"exact\"}"
        val response = NativeLegacyHttpHost.call("javaHttp.connect", listOf(raw, headers, 5000), ext, requests) as Map<*, *>
        assertEquals(listOf(listOf(raw, 4000L), listOf(raw, headers, 5000L)), requests.calls)
        assertEquals(201, response["status"])
        assertEquals("str", response["__legacyResponseKind"])
        assertEquals("native-body", response["body"])
        assertEquals(listOf("first", "last"), (response["multiHeaders"] as Map<*, *>)["X-Fixture"] ?: (response["multiHeaders"] as Map<*, *>)["x-fixture"])
    }
    @Test fun jsoupGetPostHeadDelegateExactOriginalOverloads() {
        val ext = Extensions()
        val headers = mapOf("X-Fixture" to "value")
        val cases = listOf("get" to listOf("url", headers, 1234), "post" to listOf("url", "body", headers, 1234), "head" to listOf("url", headers, 1234))
        for ((method, args) in cases) {
            val failure = assertThrows(Invocation::class.java) { NativeLegacyHttpHost.call("javaHttp.$method", args, ext) }
            assertEquals(method, failure.method); assertEquals(args, failure.args)
            assertSame(headers, failure.args[if (method == "post") 2 else 1])
        }
    }
    @Test fun transportFailureIsFatalAndCancellationDoesNotBecomeSuccessBody() {
        val ext = Extensions(); val requests = Requests().apply { fail = true }
        val failure = assertThrows(SourceScriptException::class.java) { NativeLegacyHttpHost.call("javaHttp.ajax", listOf("url"), ext, requests) }
        assertEquals("network_error", failure.code)
        assertFalse(failure.message.orEmpty().contains("private-detail"))
        assertEquals(1, requests.calls.size)
        ext.job.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java) { NativeLegacyHttpHost.call("javaHttp.ajax", listOf("url"), ext, requests) }
        assertEquals(1, requests.calls.size)
    }
    @Test fun preparationUsesOriginalExplicitConnectJsonValidityAndPerUrlCount() {
        val ext = Extensions(HeaderSource())
        assertNull(NativeLegacyHttpHost.call("javaHttp.prepareHeader", listOf("connect", listOf("url", "{}")), ext))
        assertNull(NativeLegacyHttpHost.call("javaHttp.prepareHeader", listOf("connect", listOf("url", "{\"X-Fixture\":3}")), ext))
        val fallback = NativeLegacyHttpHost.call("javaHttp.prepareHeader", listOf("connect", listOf("url", "invalid")), ext) as Map<*, *>
        assertEquals("globalThis.headerRuns++", fallback["script"])
        assertEquals(1, fallback["count"])
        val batch = NativeLegacyHttpHost.call("javaHttp.prepareHeader", listOf("ajaxAll", listOf(listOf("first", "second"))), ext) as Map<*, *>
        assertEquals(2, batch["count"])
        assertThrows(IllegalArgumentException::class.java) { NativeLegacyHttpHost.call("javaHttp.prepareHeader", listOf("ajax", listOf("url", 1.5)), ext) }

    }
    @Test fun resolvedHeadersSkipNativeEvalAndPreserveOriginalLoginBooleanOverlay() {
        val ext = Extensions(HeaderSource()); val requests = Requests()
        val evaluation = mapOf("failed" to false, "value" to mapOf("User-Agent" to "fixture-UA", "X-Fixture" to "resolved"))
        assertEquals("native-body", NativeLegacyHttpHost.call("javaHttp.ajaxResolved", listOf(listOf("raw-options", 4000), listOf(evaluation)), ext, requests))
        val source = requests.resolvedSources.single()
        val sameSite = source.getHeaderMap(true); val otherSite = source.getHeaderMap(false)
        assertEquals("resolved", sameSite["X-Fixture"])
        assertEquals("fixture-UA", otherSite["User-Agent"])
        assertEquals("fixture-login", sameSite["Authorization"])
        assertFalse(otherSite.containsKey("Authorization"))
        assertEquals(listOf("raw-options", 4000L), requests.calls.single())
    }
    @Test fun sourceVariableRpcAndResolvedBatchKeepPerUrlStateAndRejectWrongCounts() {
        val original = HeaderSource(); val ext = Extensions(original); val requests = Requests()
        assertEquals("source-value", NativeLegacyHttpHost.call("javaHttp.headerPut", listOf("saved", "source-value"), ext))
        assertEquals("source-value", NativeLegacyHttpHost.call("javaHttp.headerGet", listOf("saved"), ext))
        assertEquals("2", NativeLegacyHttpHost.call("javaHttp.headerPut", listOf("counter", 2), ext))
        assertEquals("2", NativeLegacyHttpHost.call("javaHttp.headerGet", listOf("counter"), ext))
        assertEquals("", NativeLegacyHttpHost.call("javaHttp.headerPut", listOf(null, null), ext))

        val evaluations = listOf("first", "second").map { mapOf("failed" to false, "value" to mapOf("User-Agent" to "fixture-UA", "X-Fixture" to it)) }
        NativeLegacyHttpHost.call("javaHttp.ajaxAllResolved", listOf(listOf(listOf("first-url", "second-url"), true), evaluations), ext, requests)
        assertEquals(listOf("first", "second"), requests.resolvedSources.map { it.getHeaderMap(false)["X-Fixture"] })
        assertThrows(IllegalArgumentException::class.java) { NativeLegacyHttpHost.call("javaHttp.ajaxAllResolved", listOf(listOf(listOf("a", "b")), evaluations.take(1)), ext, requests) }
        assertEquals(2, requests.calls.size)
    }

}
