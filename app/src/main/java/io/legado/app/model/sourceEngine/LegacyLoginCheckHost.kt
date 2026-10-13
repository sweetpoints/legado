package io.legado.app.model.sourceEngine

import io.legado.app.help.http.StrResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A login check resumes its original request; URL scripts run in the already active V8 VM. */
internal class LegacyLoginCheckHost(
    private val analyzed: AnalyzeUrl,
    private val context: CoroutineContext,
) {
    private val continuations = NativeScriptContinuationHost(context)

    suspend fun call(method: String, args: List<Any?>): Any? {
        context.ensureActive()
        fun text(index: Int) =
            args.getOrNull(index) as? String ?: invalid("Login callback string required")
        fun sequence(): Int {
            val n = args.getOrNull(1) as? Number ?: invalid("Login callback sequence required")
            val d = n.toDouble()
            if (!d.isFinite() || d != kotlin.math.floor(d) || d < 1 || d > Int.MAX_VALUE)
                invalid("Invalid login callback sequence")
            return d.toInt()
        }
        return when (method) {
            "loginCheck.begin" -> {
                require(args.size == 3 && args[1] is List<*>) { "Invalid login request begin" }
                val operation = text(0)
                val original = args[1] as List<*>
                require(original.isEmpty()) { "Login request method accepts no arguments" }
                if (operation !in setOf("initUrl", "getStrResponse"))
                    invalid("Unsupported login request method")
                continuations.begin { worker ->
                    analyzed.withScriptContext(worker) {
                        if (operation == "initUrl") {
                            analyzed.initUrl()
                            null
                        } else encode(analyzed.getStrResponse())
                    }
                }
            }
            "loginCheck.continue" -> {
                require(args.size == 3)
                continuations.resume(
                    text(0),
                    sequence(),
                    args[2] as? Map<*, *> ?: invalid("Login script outcome required"),
                )
            }
            "loginCheck.stepCall" -> {
                require(args.size == 4)
                @Suppress("UNCHECKED_CAST")
                continuations.stepCall(
                    text(0),
                    sequence(),
                    text(2),
                    args[3] as? List<Any?> ?: invalid("Login script arguments required"),
                )
            }
            "loginCheck.abort" -> {
                require(args.size == 1)
                continuations.abort(text(0))
                null
            }
            else -> invalid("Unsupported login callback")
        }
    }

    suspend fun evaluate(script: String, response: StrResponse): Map<String, Any?> {
        val parent = context[SourceHostCallbacks]
        val callbacks = SourceHostCallbacks { method, args ->
            if (method in methods) call(method, args)
            else parent?.call(method, args) ?: error("Unbound login callback: $method")
        }
        val wrapper =
            """
            (function(nativeJava,originalResult) {
                var result=__legacyResponseMaterialize(originalResult);
                var java=new Proxy(nativeJava,{get:(target,name)=>['initUrl','getStrResponse'].includes(String(name))
                    ? (...args)=>__legacyLoginRequest(String(name),args) : target[name]});
                return eval(__loginCheckScript);
            })(java,result)
            """
                .trimIndent()
        // Keep the analyzer's original key/page, library, source and scoped variable bindings.
        return try {
            withContext(callbacks) {
                val originalContext = kotlin.coroutines.coroutineContext
                analyzed.withScriptContext(originalContext) {
                    val input = encode(response)
                    val evaluated =
                        analyzed.evalJS(
                            "var __loginCheckScript=" +
                                io.legado.app.utils.GSON.toJson(script) +
                                ";" +
                                wrapper,
                            input,
                        )
                    originalContext.ensureActive()
                    validateResponse(evaluated)
                }
            }
        } finally {
            continuations.close()
        }
    }

    companion object {
        val methods =
            setOf(
                "loginCheck.begin",
                "loginCheck.continue",
                "loginCheck.stepCall",
                "loginCheck.abort",
            )

        fun encode(response: StrResponse): Map<String, Any?> =
            mapOf(
                "__legacyResponseKind" to "str",
                "url" to response.url,
                "body" to response.body.orEmpty(),
                "status" to response.raw.code,
                "message" to response.raw.message,
                "headers" to response.raw.headers.toMultimap(),
                "multiHeaders" to response.raw.headers.toMultimap(),
            )

        internal fun validateResponse(value: Any?): Map<String, Any?> {
            val result = value as? Map<*, *> ?: invalid("Login check must return a response")
            val url = result["url"] as? String ?: invalid("Login response URL required")
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty())
                invalid("Login response requires an absolute HTTP URL")
            val status = result["status"] as? Number ?: invalid("Login response status required")
            val number = status.toDouble()
            if (
                !number.isFinite() || number != kotlin.math.floor(number) || number !in 100.0..599.0
            )
                invalid("Invalid login response status")
            if (result["__legacyResponseKind"] != "str" || result["body"] !is String)
                invalid("Login check must return a string response facade")
            val headers =
                result["multiHeaders"] as? Map<*, *> ?: invalid("Login response headers required")
            headers.forEach { (key, values) ->
                if (key !is String || values !is List<*> || values.any { it !is String })
                    invalid("Invalid login response headers")
            }
            return mapOf(
                "__legacyResponseKind" to "str",
                "url" to url,
                "body" to result["body"],
                "status" to number.toInt(),
                "message" to result["message"],
                "multiHeaders" to headers,
            )
        }

        private fun invalid(message: String): Nothing =
            throw SourceScriptException("invalid_request", message)
    }
}
