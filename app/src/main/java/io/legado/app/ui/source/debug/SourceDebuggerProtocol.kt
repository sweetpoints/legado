package io.legado.app.ui.source.debug

import io.legado.app.ui.book.source.debug.BookSourceDebugState
import io.legado.app.ui.rss.source.debug.RssSourceDebugState
import java.net.URI

/** UI commands carry a binding generation, never a source snapshot or private session identity. */
data class SourceDebuggerCommand(
    val generation: Int,
    val op: String,
    val text: String? = null,
    val start: Int? = null,
    val end: Int? = null,
    val value: Boolean? = null,
    val index: Int? = null,
    val stage: String? = null,
    val url: String? = null,
)

fun parseSourceDebuggerCommand(arguments: Any?, kind: String, generation: Int): SourceDebuggerCommand {
    require(kind == "book" || kind == "rss")
    require(arguments is Map<*, *>)
    val op = arguments["op"] as? String ?: error("Missing operation")
    val extra = when (op) {
        "query" -> setOf("text", "start", "end")
        "help", "prefix" -> setOf("value")
        "sort" -> setOf("index")
        "html" -> setOf("stage")
        "openUrl" -> setOf("url")
        "run", "retry", "close" -> emptySet()
        "scan", "refreshExplore", "instructions", "detail" -> {
            require(kind == "book")
            emptySet()
        }
        else -> error("Unknown operation")
    }
    require(arguments.keys == setOf("op", "generation") + extra)
    fun integer(key: String): Int {
        val value = arguments[key]
        require(value is Int || value is Long)
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return number.toInt()
    }
    val incoming = integer("generation")
    require(incoming > 0 && incoming == generation) { "Stale debugger view" }
    return when (op) {
        "query" -> {
            val text = arguments["text"] as? String ?: error("Invalid query")
            val start = integer("start")
            val end = integer("end")
            require(start in 0..text.length && end in 0..text.length)
            SourceDebuggerCommand(incoming, op, text, start, end)
        }
        "help" -> SourceDebuggerCommand(incoming, op, value = arguments["value"] as? Boolean ?: error("Invalid help"))
        "prefix" -> {
            require(kind == "book")
            val text = arguments["value"] as? String
            require(text == "++" || text == "--")
            SourceDebuggerCommand(incoming, op, text = text)
        }
        "sort" -> SourceDebuggerCommand(incoming, op, index = integer("index").also { require(it >= 0) })
        "html" -> {
            val stage = arguments["stage"] as? String ?: error("Invalid stage")
            require(stage in if (kind == "book") setOf("search", "book", "toc", "content") else setOf("list", "content"))
            SourceDebuggerCommand(incoming, op, stage = stage)
        }
        "openUrl" -> {
            val url = arguments["url"] as? String ?: error("Invalid URL")
            require(url.none { it.isISOControl() || it == '\\' })
            val uri = URI(url)
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.rawAuthority.isNullOrBlank())
            require(!uri.host.isNullOrBlank())
            SourceDebuggerCommand(incoming, op, url = url)
        }
        else -> SourceDebuggerCommand(incoming, op)
    }
}

fun bookSourceDebuggerSnapshot(value: BookSourceDebugState, generation: Int): Map<String, Any?> =
    debuggerSnapshot(generation, "book", value.name, value.keyword, value.query, value.queryStart, value.queryEnd,
        value.output, value.help, value.loading, value.loaded, value.running, value.missing, value.closed,
        value.sorts.map { mapOf("name" to it.name, "query" to it.query, "enabled" to !it.name.startsWith("ERROR:")) },
        value.selectedSort, buildList {
            if (value.hasSearchHtml) add("search")
            if (value.hasBookHtml) add("book")
            if (value.hasTocHtml) add("toc")
            if (value.hasContentHtml) add("content")
        }, value.error, value.issue?.name?.lowercase(), !value.loading && (value.error != null || !value.loaded))

fun rssSourceDebuggerSnapshot(value: RssSourceDebugState, generation: Int): Map<String, Any?> =
    debuggerSnapshot(generation, "rss", value.name, "", value.query, value.queryStart, value.queryEnd,
        value.output, value.help, value.loading, value.loaded, value.running, value.missing, value.closed,
        value.sorts.map { mapOf("name" to it.name, "query" to it.query, "enabled" to !it.name.startsWith("ERROR:")) },
        value.selectedSort, buildList {
            if (value.hasListHtml) add("list")
            if (value.hasContentHtml) add("content")
        }, value.error, value.issue?.name?.lowercase(), !value.loading && !value.loaded)

private fun debuggerSnapshot(
    generation: Int, kind: String, name: String, keyword: String, query: String, start: Int, end: Int,
    output: String, help: Boolean, loading: Boolean, loaded: Boolean, running: Boolean, missing: Boolean,
    closed: Boolean, sorts: List<Map<String, Any>>, selectedSort: Int, html: List<String>, error: String?,
    issue: String?, canRetry: Boolean,
): Map<String, Any?> {
    require(generation > 0)
    return linkedMapOf(
        "schemaVersion" to 1, "generation" to generation, "kind" to kind, "name" to name,
        "keyword" to keyword, "query" to query, "queryStart" to start, "queryEnd" to end,
        "output" to output.takeLast(20_000), "help" to help, "loading" to loading, "loaded" to loaded,
        "running" to running, "missing" to missing, "closed" to closed, "sorts" to sorts,
        "selectedSort" to selectedSort, "htmlStages" to html, "error" to sourceDebuggerSafeError(error),
        "issue" to issue, "canRetry" to canRetry,
    )
}

/** Filesystem failures remain readable without exposing private debugger session filenames. */
internal fun sourceDebuggerSafeError(message: String?): String? = message
    ?.replace(Regex("""(?:/data/(?:user(?:_de)?/\d+|data)/[^/\s]+|/mnt/expand/[^/\s]+/user(?:_de)?/\d+/[^/\s]+)/(?:files|cache|no_backup|databases|shared_prefs)(?:/[^\s"'<>():,;]*)?"""), "Private debug data unavailable")
    ?.replace(Regex("""(?:book|rss)-source-debug/[0-9a-fA-F-]{36}\.(?:json(?:\.bak|\.new)?|closed)"""), "Private debug data unavailable")
