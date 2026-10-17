package io.legado.app.ui.book.source.edit

import io.legado.app.data.entities.BookSource
import io.legado.app.model.sourceEngine.SourceMigrationPreview
import org.junit.Assert.*
import org.junit.Test

class SourceEditorChannelContractTest {
    @Test fun allNativeAndSaveActionsAreExplicitlyAccepted() {
        BookSourceNativeAction.entries.forEach {
            assertEquals(it, parseSourceEditorCommand(mapOf("op" to "native", "action" to it.name)).native)
        }
        BookSourceSaveAction.entries.forEach {
            assertEquals(it, parseSourceEditorCommand(mapOf("op" to "save", "action" to it.name)).save)
        }
    }

    @Test fun unicodeSelectionsUseUtf16OffsetsAndAllowReversedSelections() {
        val command = parseSourceEditorCommand(mapOf("op" to "field", "tab" to 0,
            "key" to "bookSourceName", "value" to "书😀", "start" to 3, "end" to 1))
        assertEquals(3, command.start)
        assertEquals(1, command.end)
        assertEquals("书😀", command.text)
    }

    @Test fun rejectsInvalidPayloadsBeforeDispatch() {
        val cases = listOf<Any?>(null, "save", mapOf("op" to "unknown"),
            mapOf("op" to "tab", "index" to 7), mapOf("op" to "tab", "index" to 1L),
            mapOf("op" to "save", "action" to "java.lang.Runtime"),
            mapOf("op" to "native", "action" to "EDITOR", "path" to "/private/file"),
            mapOf("op" to "cancel", "originalJson" to "{}"),
            mapOf("op" to "expanded", "value" to 1),
            mapOf("op" to "variableSave"),
            mapOf("op" to "field", "tab" to 0, "key" to "name", "value" to "a", "start" to -1, "end" to 0),
            mapOf("op" to "field", "tab" to 0, "key" to "name", "value" to "a", "start" to 0, "end" to 2),
            mapOf("op" to "options", "value" to mapOf("enabled" to true)),
            mapOf("op" to "insert", "text" to "中".repeat(3 * 1024 * 1024)))
        cases.forEach { args ->
            try { parseSourceEditorCommand(args); fail("Accepted invalid command") }
            catch (_: SourceEditorInvalidCommand) { }
        }
    }

    @Test fun optionsAreAtomicAndAllFiveOriginalTypesAreSupported() {
        val options = mapOf("enabled" to true, "enabledExplore" to false, "enabledCookieJar" to true,
            "enabledReview" to false, "eventListener" to true, "customButton" to false)
        (0..4).forEach { type ->
            assertEquals(type, parseSourceEditorCommand(mapOf("op" to "options", "value" to options + ("type" to type))).options?.type)
        }
        listOf(options + ("type" to 5), options + ("type" to 0) + ("unknown" to false),
            options + ("type" to 0) + ("enabled" to "true")).forEach {
            try { parseSourceEditorCommand(mapOf("op" to "options", "value" to it)); fail("Invalid options") }
            catch (_: SourceEditorInvalidCommand) { }
        }
    }

    @Test fun variableResetAndEmptyVariableRemainDistinct() {
        assertNull(parseSourceEditorCommand(mapOf("op" to "variableSave", "value" to null)).text)
        assertEquals("", parseSourceEditorCommand(mapOf("op" to "variableSave", "value" to "")).text)
    }

    @Test fun snapshotPreservesDraftFieldsButNeverExposesPrivateSessionAndCandidate() {
        val doc = BookSourceEditDocument.from(BookSource(bookSourceName = "draft"))
            .copy(originalJson = "private-original", ownedTransfers = listOf("/private/transfer"),
                nativeRequest = BookSourceNativeRequest("private-request-id", BookSourceNativeAction.EDITOR,
                    path = "/private/editor", text = "private-payload"))
        val state = BookSourceComposeState(document = doc, migrationReport = BookSourceMigrationReport(
            12, SourceMigrationPreview(emptyList(), "private-candidate", false, "unverified")))
        val snapshot = sourceEditorSnapshot(state) { "translated:" + it.key }
        val flat = snapshot.toString()
        listOf("private-original", "/private/transfer", "private-request-id", "/private/editor", "private-payload", "private-candidate")
            .forEach { assertFalse(it, flat.contains(it)) }
        val document = snapshot["document"] as Map<*, *>
        assertEquals(false, document["dirty"])
        val form = document["form"] as Map<*, *>
        val tabs = form["tabs"] as List<*>
        assertEquals(7, tabs.size)
        val field = (tabs[0] as List<*>).first() as Map<*, *>
        assertEquals("translated:" + field["key"], field["label"])
        assertEquals(true, (snapshot["migrationReport"] as Map<*, *>)["canApply"])
    }

    @Test fun loadingSnapshotHasNullDocumentAndOversizeSnapshotIsRejected() {
        assertNull(sourceEditorSnapshot(BookSourceComposeState())["document"])
        try {
            sourceEditorSnapshot(BookSourceComposeState(error = "中".repeat(6 * 1024 * 1024)))
            fail("Oversize state accepted")
        } catch (_: SourceEditorInvalidCommand) { }
    }

    @Test fun errorProjectionRedactsPrivateAndroidPathsWithoutHidingParserContext() {
        val id = "12345678-1234-1234-1234-123456789abc"
        val paths = listOf(
            "/data/user/0/io.legado.app/files/book-source-edit-drafts/$id.json",
            "/data/user_de/10/io.legado.app/cache/code-text-$id.txt",
            "/data/data/io.legado.app/cache/code-text-$id.txt",
            "/mnt/expand/$id/user/0/io.legado.app/files/book-source-edit-drafts/$id-save.json",
            "/private/test/book-source-edit-drafts/$id.json.bak",
            "/private/test/cache/code-text-$id.txt",
            "book-source-edit-drafts/$id.json",
            "code-text-$id.txt",
        )
        paths.forEach { path ->
            val message = "FileNotFoundException: $path (Permission denied)"
            val safe = sourceEditorSafeError(message)!!
            assertFalse(path, safe.contains(path))
            assertFalse(path, safe.contains(id))
            assertTrue(safe, safe.contains("FileNotFoundException"))
            assertTrue(safe, safe.contains("Permission denied"))
            assertTrue(safe, safe.contains("私有编辑数据读写失败"))
        }
        listOf("HTTP 404 https://example.org/book/123", "Regex error: ^/book/(\\d+)/$",
            "Expected ] at $.ruleToc.chapterList", "https://example.org/data/user/0/app/files/a",
            "https://example.org/code-text-$id.txt").forEach {
            assertEquals(it, sourceEditorSafeError(it))
        }
        assertNull(sourceEditorSafeError(null))
        val snapshot = sourceEditorSnapshot(BookSourceComposeState(error = "Read ${paths[0]}",
            migrationError = "Write ${paths[1]}"))
        assertFalse(snapshot.toString().contains(id))
    }

    @Test fun utf8CounterMatchesJavaForAsciiCjkEmojiAndUnpairedSurrogates() {
        listOf("", "English", "中文", "a😀中", "\uD800", "\uDC00", "\uD800x\uDC00",
            "\uD800\uD800\uDC00").forEach { value ->
            assertEquals(value.toByteArray(Charsets.UTF_8).size.toLong(), sourceEditorUtf8Size(value))
        }
        assertEquals(4L, sourceEditorUtf8Size("😀".repeat(1000000), 3))
    }

    @Test fun wireBudgetAcceptsExactBoundaryAndCountsAggregateBeforeLaterFields() {
        requireSourceEditorBudget(listOf("中", "😀"), 8 + 8 + 3 + 8 + 4)
        try {
            requireSourceEditorBudget(listOf("中", "😀"), 30)
            fail("Aggregate budget exceeded")
        } catch (_: SourceEditorInvalidCommand) { }
        val enormous = "a".repeat(17 * 1024 * 1024)
        try { requireSourceEditorBudget(enormous); fail("Oversize ASCII accepted") }
        catch (_: SourceEditorInvalidCommand) { }
        try { requireSourceEditorBudget("中".repeat(6 * 1024 * 1024)); fail("Oversize CJK accepted") }
        catch (_: SourceEditorInvalidCommand) { }
    }

}
