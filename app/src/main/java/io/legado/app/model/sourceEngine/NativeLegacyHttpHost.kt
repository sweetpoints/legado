package io.legado.app.model.sourceEngine

import io.legado.app.help.JsExtensions
import io.legado.app.data.entities.BaseSource
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.model.jsSource.JsSourceEngine
import io.legado.app.utils.mapAsync
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
    val methods = setOf("javaHttp.ajax", "javaHttp.get", "javaHttp.post", "javaHttp.head", "javaHttp.connect", "javaHttp.ajaxAll", "javaHttp.prepareHeader", "javaHttp.headerGet", "javaHttp.headerPut",
        "javaHttp.ajaxResolved", "javaHttp.connectResolved", "javaHttp.ajaxAllResolved")

    interface Requests {
        fun ajax(url: String, timeout: Long?, extensions: JsExtensions): StrResponse
        fun connect(url: String, headers: String?, timeout: Long?, extensions: JsExtensions): StrResponse
        fun batch(urls: List<String>, skipRateLimit: Boolean, extensions: List<JsExtensions>): List<StrResponse> =
            urls.mapIndexed { index, url -> ajax(url, null, extensions[index]) }
    }
    private object Original : Requests {
        override fun batch(urls: List<String>, skipRateLimit: Boolean, extensions: List<JsExtensions>): List<StrResponse> =
            runBlocking(extensions.first().getSourceNavigationContext()) {
                urls.withIndex().asFlow().mapAsync(AppConfig.threadCount) { (index, url) ->
                    AnalyzeUrl(url, source = extensions[index].getSource(), coroutineContext = coroutineContext)
                        .getStrResponseAwait(skipRateLimit = skipRateLimit)
                }.flowOn(IO).toList()
            }

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
        fun integer(index: Int): Long? = integerValue(args.getOrNull(index))
        fun timeout(index: Int): Int? = integer(index)?.also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }?.toInt()
        try {
            if (method == "javaHttp.prepareHeader") {
                arity(2)
                val name = text(0)
                val original = args[1] as? List<*> ?: error("Legacy HTTP arguments required")
                val count = validateOriginal(name, original)
                if (name == "connect" && GSON.fromJsonObject<Map<String, String>>(original.getOrNull(1) as String?).getOrNull() != null) return null
                val header = extensions.getSource()?.header?.trim().orEmpty()
                if (header.isBlank()) return null
                val matcher = AppPattern.JS_PATTERN.matcher(header)
                if (!matcher.matches()) return null
                return mapOf("script" to (matcher.group(1) ?: matcher.group(2)).trim(), "count" to count)
            }
            if (method == "javaHttp.headerGet" || method == "javaHttp.headerPut") {
                arity(if (method.endsWith("Get")) 1 else 2)
                val source = extensions.getSource() ?: error("Source header context required")
                return if (method.endsWith("Get")) source.get(args[0]?.toString().orEmpty())
                    else source.put(args[0]?.toString().orEmpty(), args[1]?.toString().orEmpty())
            }
            if (method in setOf("javaHttp.ajaxResolved", "javaHttp.connectResolved", "javaHttp.ajaxAllResolved")) {
                arity(2)
                val original = args[0] as? List<*> ?: error("Legacy HTTP arguments required")
                val name = method.removePrefix("javaHttp.").removeSuffix("Resolved")
                val count = validateOriginal(name, original)
                val evaluations = args[1] as? List<*> ?: error("Source header evaluations required")
                require(evaluations.size == count) { "Source header evaluation count differs" }
                val scoped = evaluations.map { resolvedExtensions(extensions, it as? Map<*, *> ?: error("Source header evaluation required")) }
                if (name == "ajaxAll") {
                    val urls = original[0] as List<*>
                    if (urls.isEmpty()) return emptyList<Map<String, Any?>>()
                    return requests.batch(urls.map { it as String }, original.getOrNull(1) as? Boolean ?: false, scoped).map(::strResponse)
                }
                return call("javaHttp.$name", original, scoped.single(), requests)
            }
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

    private fun integerValue(value: Any?): Long? {
        if (value == null) return null
        val number = value as? Number ?: error("Legacy HTTP timeout required")
        if (number is Long || number is Int) return number.toLong()
        val numeric = number.toDouble()
        require(numeric.isFinite() && numeric >= Long.MIN_VALUE.toDouble() && numeric < 9223372036854775808.0 && numeric == kotlin.math.floor(numeric)) { "Legacy HTTP integer timeout required" }
        return numeric.toLong()
    }

    private fun validateOriginal(method: String, args: List<*>): Int {
        when (method) {
            "ajax" -> { require(args.size in 1..2); integerValue(args.getOrNull(1)) }
            "connect" -> {
                require(args.size in 1..3 && args[0] is String)
                require(args.getOrNull(1) == null || args[1] is String)
                integerValue(args.getOrNull(2))
            }
            "ajaxAll" -> {
                require(args.size in 1..2 && args[0] is List<*>)
                val urls = args[0] as List<*>
                require(urls.all { it is String })
                require(args.getOrNull(1) == null || args[1] is Boolean)
                return urls.size
            }
            else -> error("Unsupported source header request")
        }
        return 1
    }

    private fun resolvedExtensions(original: JsExtensions, evaluation: Map<*, *>): JsExtensions {
        require(evaluation["failed"] is Boolean) { "Source header evaluation status required" }
        val actual = original.getSource() ?: error("Source header context required")
        val fields = if (evaluation["failed"] == true) {
            // BaseSource.getHeaderMap logs failed evaluation and still supplies UA/login.
            AppLog.put("执行请求头规则出错: active V8 header evaluation failed")
            emptyMap()
        } else {
            val json = JsSourceEngine.normalizeJsResult(evaluation["value"]).orEmpty()
            GSON.fromJsonObject<Map<String, String>>(json).getOrNull().orEmpty()
        }
        val source = object : BaseSource by actual {
            override fun getHeaderMap(hasLoginHeader: Boolean): HashMap<String, String> = HashMap<String, String>().apply {
                putAll(fields)
                if (keys.none { it.equals(AppConst.UA_NAME, ignoreCase = true) }) put(AppConst.UA_NAME, AppConfig.userAgent)
                // AnalyzeUrl supplies the original same-site decision. Its URL-option
                // header overlay runs afterwards; neither is duplicated in JS.
                if (hasLoginHeader) actual.getLoginHeaderMap()?.let(::putAll)
            }
        }
        return object : JsExtensions by original {
            override fun getSource(): BaseSource = source
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
