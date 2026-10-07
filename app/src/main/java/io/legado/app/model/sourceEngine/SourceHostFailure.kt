package io.legado.app.model.sourceEngine

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/** An RPC failure with bounded type metadata; no source, URL, body or stack is serialized. */
class SourceHostException(
    val code: String,
    message: String,
    val exceptionTypes: List<String> = emptyList(),
) : IllegalStateException("$code: $message")

object SourceHostFailure {
    data class Encoded(val code: String, val message: String?, val details: Map<String, Any?>)

    fun encode(error: Throwable): Encoded {
        val chain = mutableListOf<Throwable>()
        val seen = IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = error
        while (current != null && chain.size < 8 && seen.put(current, true) == null) {
            chain += current
            current = current.cause
        }
        // Script failures keep their script classification even when their text or
        // nested cause mentions transport. Only actual native exception types qualify.
        val code =
            when {
                error is SourceScriptException -> error.code
                chain.any { it is CancellationException } -> "cancelled"
                chain.any { it is SocketTimeoutException } -> "network_timeout"
                chain.any {
                    it is UnknownHostException ||
                        it is ConnectException ||
                        it is NoRouteToHostException ||
                        it is SocketException ||
                        it is SSLException
                } -> "network_error"
                else -> "HOST_CALL_FAILED"
            }
        return Encoded(
            code,
            error.message,
            mapOf("schemaVersion" to 1, "exceptionTypes" to chain.map { it.javaClass.name }),
        )
    }

    fun decode(code: String, message: String?, details: Any?): Exception {
        if (code in setOf("script_error", "syntax_error", "nested_script_requires_migration")) {
            return SourceScriptException(code, message.orEmpty())
        }
        val metadata = details as? Map<*, *>
        val types =
            if (metadata?.get("schemaVersion") == 1) {
                (metadata["exceptionTypes"] as? List<*>)
                    ?.take(8)
                    ?.mapNotNull {
                        (it as? String)?.takeIf { name ->
                            name.length <= 256 && Regex("[A-Za-z_$][A-Za-z0-9_.$]*").matches(name)
                        }
                    }
                    .orEmpty()
            } else emptyList()
        return SourceHostException(code, message.orEmpty(), types)
    }
}
