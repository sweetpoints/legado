package io.legado.app.model.sourceEngine

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Explicit Jsoup entry points; JVM objects are returned only through the native DOM codec. */
class NativeOrgJsoupHost {
    suspend fun call(
        owner: String,
        method: String,
        args: List<Any?>,
        context: CoroutineContext,
    ): Any? {
        require(owner.isNotBlank()) { "Registered Jsoup owner required" }
        context.ensureActive()
        fun arity(min: Int, max: Int = min) {
            if (args.size !in min..max) invalid("Invalid org.jsoup overload")
        }
        fun text(index: Int): String =
            args.getOrNull(index) as? String ?: invalid("org.jsoup string argument required")
        val node =
            when (method) {
                "orgJsoup.parse" -> {
                    arity(1, 3)
                    val html = text(0)
                    when {
                        args.size == 1 -> Jsoup.parse(html)
                        args.size == 2 && args[1] is Map<*, *> -> Jsoup.parse(html, parser(args[1]))
                        args.size == 2 -> Jsoup.parse(html, text(1))
                        else -> Jsoup.parse(html, text(1), parser(args[2]))
                    }
                }
                "orgJsoup.parseBodyFragment" -> {
                    arity(1, 2)
                    if (args.size == 1) Jsoup.parseBodyFragment(text(0))
                    else Jsoup.parseBodyFragment(text(0), text(1))
                }
                "orgJsoup.newDocument" -> {
                    arity(1, 2)
                    if (args.size == 1) Document(text(0))
                    else {
                        if (text(1) != "shell") invalid("Unsupported Document factory")
                        Document.createShell(text(0))
                    }
                }
                "orgJsoup.newElement" -> {
                    arity(1, 2)
                    if (args.size == 1) Element(text(0)) else Element(text(0), text(1))
                }
                else ->
                    throw SourceScriptException(
                        "legacy.unsupported_org_api",
                        "Unsupported org.jsoup API",
                    )
            }
        context.ensureActive()
        return LegacyDomHost.snapshot(node)
    }

    companion object {
        val methods =
            setOf(
                "orgJsoup.parse",
                "orgJsoup.parseBodyFragment",
                "orgJsoup.newDocument",
                "orgJsoup.newElement",
                "orgJsoup.connect",
                "orgJsoup.connectionCall",
                "orgJsoup.responseCall",
                "orgJsoup.release",
            )

        private fun parser(value: Any?): Parser {
            val kind =
                (value as? Map<*, *>)?.get("__legacyOrgParser") as? String
                    ?: invalid("Explicit Jsoup parser marker required")
            return when (kind) {
                "html" -> Parser.htmlParser()
                "xml" -> Parser.xmlParser()
                else -> invalid("Unsupported Jsoup parser")
            }
        }

        private fun invalid(message: String): Nothing =
            throw SourceScriptException("invalid_request", message)
    }
}
