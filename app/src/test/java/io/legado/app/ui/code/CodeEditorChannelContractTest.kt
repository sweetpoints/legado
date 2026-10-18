package io.legado.app.ui.code

import org.junit.Assert.*
import org.junit.Test

class CodeEditorChannelContractTest {
    @Test fun snapshotNeverSerializesFullTextOwnerFilesOrReceipt() {
        val session = CodeEditorSession("private-initial", text = "private-document",
            returnReceipt = CodeEditorReturnReceipt("private-receipt", 15, true,
                textFile = "/private/result"))
        val state = CodeEditorComposeState(session = session, engineOwner = "private-owner")
        val snapshot = codeEditorSnapshot(CodeEditorPresentation(state = state, generation = 3))
        assertEquals(3, snapshot["generation"])
        assertEquals(true, snapshot["hasSession"])
        listOf("private-initial", "private-document", "private-receipt", "/private/result", "private-owner")
            .forEach { assertFalse(it, snapshot.toString().contains(it)) }
        assertEquals(1, snapshot["schemaVersion"])
    }

    @Test fun allActionsAndOnlyExplicitNoArgumentCommandsAreAccepted() {
        CodeEditorAction.entries.forEach {
            assertEquals(it, parseCodeEditorCommand(mapOf("op" to "action", "name" to it.name)).action)
        }
        listOf("exit", "retry", "restart", "keepEditing", "discard").forEach {
            assertEquals(it, parseCodeEditorCommand(mapOf("op" to it)).op)
        }
    }

    private fun search() = mapOf<String, Any>("query" to "a😀", "replacement" to "中",
        "visible" to true, "replaceVisible" to false, "regex" to true, "ignoreCase" to false,
        "queryStart" to 3, "queryEnd" to 1, "replacementStart" to 1, "replacementEnd" to 0)

    @Test fun fullSearchRoundTripsUtf16SelectionsAndIgnoreCase() {
        val value = parseCodeEditorCommand(mapOf("op" to "search", "value" to search() + ("ignoreCase" to true))).search!!
        assertEquals("a😀", value.query)
        assertEquals(CodeEditorSelection(3, 1), value.querySelection)
        assertEquals(CodeEditorSelection(1, 0), value.replacementSelection)
        assertTrue(value.ignoreCase)
        assertFalse(parseCodeEditorCommand(mapOf("op" to "search", "value" to search())).search!!.ignoreCase)
    }

    @Test fun parserRejectsUntrustedOwnerRawDocumentPartialSearchAndInvalidTypes() {
        val invalid = listOf<Any?>(null, "SAVE", mapOf("op" to "save"),
            mapOf("op" to "action", "name" to "java.lang.Runtime"),
            mapOf("op" to "action", "name" to "SAVE", "owner" to "session"),
            mapOf("op" to "insert", "text" to "x", "originalJson" to "{}"),
            mapOf("op" to "search", "value" to mapOf("query" to "x")),
            mapOf("op" to "search", "value" to search() + ("queryEnd" to 4)),
            mapOf("op" to "search", "value" to search() + ("queryEnd" to 1L)),
            mapOf("op" to "search", "value" to search() + ("ignoreCase" to "false")),
            mapOf("op" to "search", "value" to search() + ("owner" to "private")),
            mapOf("op" to "search", "value" to search() + ("query" to "x".repeat(65537))),
            mapOf("op" to "insert", "text" to "中".repeat(3 * 1024 * 1024)))
        invalid.forEach {
            try { parseCodeEditorCommand(it); fail("Accepted invalid command") }
            catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
    }

    @Test fun privateSessionAndReceiptFileErrorsAreRedactedWithoutRemovingHttpDiagnostics() {
        val id = "12345678-1234-1234-1234-123456789abc"
        listOf("/data/user/0/app/files/code-editor-sessions/$id.json",
            "/private/tests/code-editor-sessions/$id.json.bak",
            "/private/cache/code-text-$id-$id.txt").forEach {
            val safe = codeEditorSafeError("FileNotFoundException: $it (Permission denied)")!!
            assertFalse(safe.contains(id))
            assertTrue(safe.contains("Permission denied"))
        }
        val url = "HTTP 403 https://example.org/code-text-$id-$id.txt"
        assertEquals(url, codeEditorSafeError(url))
    }

    @Test fun noSessionHasZeroGenerationAndAllSearchStateIsCodecSafe() {
        val snapshot = codeEditorSnapshot(CodeEditorPresentation())
        assertEquals(false, snapshot["hasSession"])
        assertEquals(0, snapshot["generation"])
        assertEquals(false, snapshot["writable"])
        assertTrue(snapshot["search"] is Map<*, *>)
        assertTrue(snapshot["engine"] is Map<*, *>)
    }
}
