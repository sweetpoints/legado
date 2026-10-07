package io.legado.app.model.sourceEngine

import io.legado.app.help.JsExtensions
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
    private class Extensions : JsExtensions {
        val job = Job()
        override fun getSource() = null
        override fun getTag() = "fixture"
        override fun getSourceNavigationContext() = job
        override fun get(urlStr: String, headers: Any?, timeout: Int?): Connection.Response = throw Invocation("get", listOf(urlStr, headers, timeout))
        override fun post(urlStr: String, body: String, headers: Any?, timeout: Int?): Connection.Response = throw Invocation("post", listOf(urlStr, body, headers, timeout))
        override fun head(urlStr: String, headers: Any?, timeout: Int?): Connection.Response = throw Invocation("head", listOf(urlStr, headers, timeout))
    }
    private class Requests : NativeLegacyHttpHost.Requests {
        val calls = mutableListOf<List<Any?>>()
        var fail = false
        fun response(): StrResponse = StrResponse(Response.Builder()
            .request(Request.Builder().url("https://fixture.invalid/response").build())
            .protocol(Protocol.HTTP_1_1).code(201).message("Created")
            .addHeader("X-Fixture", "first").addHeader("X-Fixture", "last").build(), "native-body")
        override fun ajax(url: String, timeout: Long?, extensions: JsExtensions): StrResponse {
            calls.add(listOf(url, timeout)); if (fail) throw IOException("private-detail")
            return response()
        }
        override fun connect(url: String, headers: String?, timeout: Long?, extensions: JsExtensions): StrResponse {
            calls.add(listOf(url, headers, timeout)); return response()
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
}
