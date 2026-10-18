package io.legado.app.ui.source.debug

import io.legado.app.data.repository.BookSourceDebugSort
import io.legado.app.data.repository.RssSourceDebugSort
import io.legado.app.ui.book.source.debug.BookSourceDebugIssue
import io.legado.app.ui.book.source.debug.BookSourceDebugState
import io.legado.app.ui.rss.source.debug.RssSourceDebugIssue
import io.legado.app.ui.rss.source.debug.RssSourceDebugState
import org.junit.Assert.*
import org.junit.Test

class SourceDebuggerProtocolTest {
    private fun command(op: String, vararg fields: Pair<String, Any?>) =
        mapOf<String, Any?>("generation" to 7, "op" to op, *fields)

    private fun rejected(payload: Any?, kind: String = "book", generation: Int = 7) {
        try {
            parseSourceDebuggerCommand(payload, kind, generation)
            fail("Accepted an invalid debugger command")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun queuedRunUsesNativeQueryInsteadOfAcceptingAnUnownedSourceOrSnapshot() {
        assertEquals("run", parseSourceDebuggerCommand(command("run"), "book", 7).op)
        listOf("query", "sourceJson", "sourceKey", "session", "owner", "html").forEach {
            rejected(command("run", it to "untrusted"))
        }
        rejected(command("html", "stage" to "search", "value" to "<html>untrusted</html>"))
    }

    @Test fun utf16QueriesRetainReversedSelectionsAndLongExistingInputs() {
        val text = "a😀中文" + "x".repeat(70_000)
        val parsed = parseSourceDebuggerCommand(command("query", "text" to text,
            "start" to text.length.toLong(), "end" to 1), "book", 7)
        assertEquals(text, parsed.text)
        assertEquals(text.length, parsed.start)
        assertEquals(1, parsed.end)
        rejected(command("query", "text" to "😀", "start" to 3, "end" to 0))
        rejected(command("query", "text" to "😀", "start" to 0, "end" to -1))
    }

    @Test fun detachedAndMalformedBindingsCannotExecuteCommands() {
        listOf(null, "run", emptyMap<String, Any>(), command("unknown"),
            command("run") + ("generation" to 6), command("run") + ("generation" to 0),
            command("run") + ("generation" to 7.0), command("run") + ("generation" to true),
            command("run") + ("generation" to Long.MAX_VALUE),
            command("sort", "index" to 1.5), command("sort", "index" to -1),
            command("sort", "index" to "1"), command("help", "value" to "true"),
            command("query", "text" to "x", "start" to 0),
            command("query", "text" to "x", "start" to 0, "end" to 1, "extra" to true))
            .forEach { rejected(it) }
        rejected(command("run"), generation = 0)
        rejected(command("run"), kind = "unknown")
    }

    @Test fun bookAndRssStageAndHelperCapabilitiesRemainDistinct() {
        listOf("search", "book", "toc", "content").forEach {
            assertEquals(it, parseSourceDebuggerCommand(command("html", "stage" to it), "book", 7).stage)
        }
        listOf("list", "content").forEach {
            assertEquals(it, parseSourceDebuggerCommand(command("html", "stage" to it), "rss", 7).stage)
        }
        rejected(command("html", "stage" to "list"))
        rejected(command("html", "stage" to "toc"), "rss")
        rejected(command("html", "stage" to "Search"))
        listOf("scan", "refreshExplore", "instructions", "detail").forEach {
            assertEquals(it, parseSourceDebuggerCommand(command(it), "book", 7).op)
            rejected(command(it), "rss")
        }
        listOf("++", "--").forEach {
            assertEquals(it, parseSourceDebuggerCommand(command("prefix", "value" to it), "book", 7).text)
            rejected(command("prefix", "value" to it), "rss")
        }
        rejected(command("prefix", "value" to "javascript:run()"))
    }

    @Test fun logNavigationAcceptsOnlyCompleteHttpUrls() {
        listOf("https://example.test/path?q=1#section", "http://localhost:8080/debug").forEach {
            assertEquals(it, parseSourceDebuggerCommand(command("openUrl", "url" to it), "rss", 7).url)
        }
        listOf("javascript:alert(1)", "file:///private/source.json", "intent://example.test",
            "https:///missing-host", "https://example.test\\path", "https://example.test\n").forEach {
            rejected(command("openUrl", "url" to it))
        }
        rejected(command("openUrl", "url" to "https://example.test", "source" to "private"))
    }

    @Test fun projectionRetainsVisibleDiagnosticsWithoutPrivateSessionOrRawHtmlFields() {
        val output = "old".repeat(10_000) + "HTTP 403 https://example.test/book"
        val snapshot = bookSourceDebuggerSnapshot(BookSourceDebugState(name = "Book",
            query = "😀", queryStart = 2, queryEnd = 0, output = output,
            sorts = listOf(BookSourceDebugSort("Category", "/category"),
                BookSourceDebugSort("ERROR: unavailable", "/error")),
            hasSearchHtml = true, hasTocHtml = true, issue = BookSourceDebugIssue.Interrupted), 7)
        assertEquals(output.takeLast(20_000), snapshot["output"])
        assertEquals(2, snapshot["queryStart"])
        assertEquals(0, snapshot["queryEnd"])
        assertEquals(listOf("search", "toc"), snapshot["htmlStages"])
        assertEquals("interrupted", snapshot["issue"])
        val sorts = snapshot["sorts"] as List<*>
        assertEquals("Category::/category", (sorts[0] as Map<*, *>)["query"])
        assertEquals(false, (sorts[1] as Map<*, *>)["enabled"])
        assertEquals(setOf("schemaVersion", "generation", "kind", "name", "keyword", "query",
            "queryStart", "queryEnd", "output", "help", "loading", "loaded", "running", "missing",
            "closed", "sorts", "selectedSort", "htmlStages", "error", "issue", "canRetry"), snapshot.keys)
    }

    @Test fun retryAndRssHtmlFlagsKeepExistingDifferentSemantics() {
        val book = bookSourceDebuggerSnapshot(BookSourceDebugState(loading = false, loaded = true,
            error = "write failed"), 7)
        val rss = rssSourceDebuggerSnapshot(RssSourceDebugState(loading = false, loaded = true,
            error = "write failed", hasContentHtml = true, issue = RssSourceDebugIssue.Busy,
            sorts = listOf(RssSourceDebugSort("News", "/news"))), 8)
        assertEquals(true, book["canRetry"])
        assertEquals(false, rss["canRetry"])
        assertEquals(listOf("content"), rss["htmlStages"])
        assertEquals("busy", rss["issue"])
        assertEquals("rss", rss["kind"])
        assertEquals(8, rss["generation"])
        assertEquals("", rss["keyword"])
    }

    @Test fun privateStorageFailureHidesSessionPathWithoutLosingPublicNetworkErrors() {
        val id = "12345678-1234-1234-1234-123456789abc"
        val error = bookSourceDebuggerSnapshot(BookSourceDebugState(error =
            "/data/user/0/com.legado.app/files/book-source-debug/$id.json (Permission denied)"), 7)["error"].toString()
        assertFalse(error.contains(id))
        assertFalse(error.contains("/data/user/"))
        assertTrue(error.contains("Permission denied"))
        val network = "HTTP 403 https://example.test/path"
        assertEquals(network, rssSourceDebuggerSnapshot(RssSourceDebugState(error = network), 7)["error"])
    }
}
