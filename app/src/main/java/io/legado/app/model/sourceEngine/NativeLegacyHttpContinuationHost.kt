package io.legado.app.model.sourceEngine

import io.legado.app.help.JsExtensions
import kotlin.coroutines.CoroutineContext

/** Narrow JSON RPC facade for one registered task's original native request stacks. */
class NativeLegacyHttpContinuationHost(taskContext: CoroutineContext) {
    private val continuations = NativeScriptContinuationHost(taskContext)

    suspend fun call(method: String, args: List<Any?>, extensions: JsExtensions): Any? {
        fun text(index: Int) =
            args.getOrNull(index) as? String ?: invalid("HTTP continuation string required")
        fun sequence(): Int {
            val value =
                args.getOrNull(1) as? Number ?: invalid("HTTP continuation sequence required")
            val number = value.toDouble()
            if (
                !number.isFinite() ||
                    number != kotlin.math.floor(number) ||
                    number < 1 ||
                    number > Int.MAX_VALUE
            )
                invalid("Invalid HTTP continuation sequence")
            return number.toInt()
        }
        return when (method) {
            "javaHttp.begin" -> {
                require(args.size == 3) { "Invalid HTTP continuation begin" }
                val operation = text(0)
                if (operation !in setOf("ajax", "connect", "ajaxAll"))
                    invalid("Unsupported HTTP continuation operation")
                val original =
                    args[1] as? List<*> ?: invalid("HTTP continuation arguments required")
                val evaluations = args[2]
                if (evaluations != null && evaluations !is List<*>)
                    invalid("HTTP header evaluations must be a list")
                continuations.begin { context ->
                    val scoped =
                        object : JsExtensions by extensions {
                            override fun getSourceNavigationContext() = context
                        }
                    @Suppress("UNCHECKED_CAST")
                    if (evaluations == null)
                        NativeLegacyHttpHost.call(
                            "javaHttp.$operation",
                            original as List<Any?>,
                            scoped,
                        )
                    else
                        NativeLegacyHttpHost.call(
                            "javaHttp.${operation}Resolved",
                            listOf(original, evaluations),
                            scoped,
                        )
                }
            }
            "javaHttp.continue" -> {
                require(args.size == 3) { "Invalid HTTP continuation resume" }
                continuations.resume(
                    text(0),
                    sequence(),
                    args[2] as? Map<*, *> ?: invalid("Script outcome required"),
                )
            }
            "javaHttp.stepCall" -> {
                require(args.size == 4) { "Invalid HTTP continuation callback" }
                @Suppress("UNCHECKED_CAST")
                continuations.stepCall(
                    text(0),
                    sequence(),
                    text(2),
                    args[3] as? List<Any?> ?: invalid("Callback arguments required"),
                )
            }
            "javaHttp.abort" -> {
                require(args.size == 1) { "Invalid HTTP continuation abort" }
                continuations.abort(text(0))
                null
            }
            else -> invalid("Unsupported HTTP continuation method")
        }
    }

    fun close() = continuations.close()

    companion object {
        val methods =
            setOf("javaHttp.begin", "javaHttp.continue", "javaHttp.stepCall", "javaHttp.abort")

        private fun invalid(message: String): Nothing =
            throw SourceScriptException("invalid_request", message)
    }
}
