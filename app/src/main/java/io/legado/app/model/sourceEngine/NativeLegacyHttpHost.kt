package io.legado.app.model.sourceEngine

import io.legado.app.help.JsExtensions
import io.legado.app.help.http.StrResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ensureActive
import org.jsoup.Connection
import org.jsoup.HttpStatusException

/** Explicit legacy transport RPC; success uses original clients, failures never become HTTP 200. */
object NativeLegacyHttpHost {
    val methods = setOf("javaHttp.ajax", "javaHttp.get", "javaHttp.post", "javaHttp.head", "javaHttp.connect", "javaHttp.ajaxAll")

    interface Requests {
        fun ajax(url: String, timeout: Long?, extensions: JsExtensions): StrResponse
        fun connect(url: String, headers: String?, timeout: Long?, extensions: JsExtensions): StrResponse
    }
    private object Original : Requests {
        override fun ajax(url: String, timeout: Long?, extensions: JsExtensions) = AnalyzeUrl(
            url, source = extensions.getSource(), callTimeout = timeout,
            coroutineContext = extensions.getSourceNavigationContext(),
        ).getStrResponse()
        override fun connect(url: String, headers: String?, timeout: Long?, extensions: JsExtensions) = AnalyzeUrl(
            url, headerMapF = GSON.fromJsonObject<Map<String, String>>(headers).getOrNull(),
            source = extensions.getSource(), callTimeout = timeout,
            coroutineContext = extensions.getSourceNavigationContext(),
        ).getStrResponse()
    }

    fun call(method: String, args: List<Any?>, extensions: JsExtensions, requests: Requests = Original): Any? {
        val context = extensions.getSourceNavigationContext()
        context.ensureActive()
        fun arity(min: Int, max: Int = min) = require(args.size in min..max) { "Invalid native legacy HTTP overload" }
        fun text(index: Int) = args[index] as? String ?: error("Legacy HTTP string required")
        fun integer(index: Int): Long? {
            val value = args.getOrNull(index) ?: return null
            val number = value as? Number ?: error("Legacy HTTP timeout required")
            if (number is Long || number is Int) return number.toLong()
            val numeric = number.toDouble()
            require(numeric.isFinite() && numeric >= Long.MIN_VALUE.toDouble() && numeric < 9223372036854775808.0 && numeric == kotlin.math.floor(numeric)) { "Legacy HTTP integer timeout required" }
            return numeric.toLong()
        }
        fun timeout(index: Int): Int? = integer(index)?.also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }?.toInt()
        try {
            val result = when (method) {
                "javaHttp.ajax" -> {
                    arity(1, 2)
                    val value = args[0]
                    val url = if (value is List<*>) value.firstOrNull().toString() else value.toString()
                    requests.ajax(url, integer(1), extensions).body
                }
                "javaHttp.connect" -> {
                    arity(1, 3)
                    val headers = args.getOrNull(1)
                    require(headers == null || headers is String) { "Legacy connect header JSON required" }
                    strResponse(requests.connect(text(0), headers as String?, integer(2), extensions))
                }
                "javaHttp.get" -> { arity(2, 3); jsoupResponse(extensions.get(text(0), args[1], timeout(2))) }
                "javaHttp.post" -> { arity(3, 4); jsoupResponse(extensions.post(text(0), text(1), args[2], timeout(3))) }
                "javaHttp.head" -> { arity(2, 3); jsoupResponse(extensions.head(text(0), args[1], timeout(2))) }
                "javaHttp.ajaxAll" -> {
                    arity(1, 2)
                    val urls = args[0] as? List<*> ?: error("Legacy URL list required")
                    require(urls.all { it is String }) { "Legacy URL strings required" }
                    val skip = args.getOrNull(1) ?: false
                    require(skip is Boolean) { "Legacy skip rate limit flag required" }
                    extensions.ajaxAll(urls.map { it as String }.toTypedArray(), skip).map(::strResponse)
                }
                else -> error("Unsupported native legacy HTTP API")
            }
            context.ensureActive()
            return result
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpStatusException) {
            context.ensureActive()
            throw SourceScriptException("legacy.http_error", "HTTP ${error.statusCode}", error)
        } catch (error: IOException) {
            context.ensureActive()
            throw SourceScriptException("network_error", "Legacy HTTP transport failed", error)
        }
    }

    private fun strResponse(value: StrResponse): Map<String, Any?> {
        val response = value.raw
        val headers = response.headers.toMultimap()
        return mapOf("__legacyResponseKind" to "str", "url" to value.url(), "status" to response.code,
            "message" to response.message, "body" to value.body, "headers" to headers.mapValues { it.value.lastOrNull().orEmpty() },
            "multiHeaders" to headers, "callTime" to value.callTime, "bytes" to value.body?.toByteArray()?.map { it.toInt() },
            "cookieMap" to emptyMap<String, String>())
    }
    private fun jsoupResponse(response: Connection.Response): Map<String, Any?> = mapOf(
        "__legacyResponseKind" to "jsoup", "url" to response.url().toString(), "status" to response.statusCode(),
        "message" to response.statusMessage(), "body" to response.body(), "headers" to response.headers(),
        "multiHeaders" to response.multiHeaders(), "cookieMap" to response.cookies(), "bytes" to response.bodyAsBytes().map { it.toInt() },
    )
}
