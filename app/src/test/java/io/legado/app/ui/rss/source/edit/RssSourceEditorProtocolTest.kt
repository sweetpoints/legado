package io.legado.app.ui.rss.source.edit

import io.legado.app.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class RssSourceEditorProtocolTest {
    private fun command(op: String, vararg args: Pair<String, Any?>) =
        mapOf("generation" to 7, "op" to op) + args
    private fun reject(args: Any?) {
        assertTrue(runCatching { parseRssEditorCommand(args, 7) }.isFailure)
    }
    private fun snapshot(state: RssSourceEditorState) = rssSourceEditorSnapshot(state, 7,
        { it.key }, listOf("base", "start", "list", "web"), listOf("a", "b", "c"),
        listOf("a", "b", "c", "d", "e"))

    @Test fun fieldKeepsEmojiAndReverseUtf16SelectionWithoutTrimming() {
        val parsed = parseRssEditorCommand(command("field", "key" to "sourceName",
            "value" to " 😀abc ", "start" to 6, "end" to 1), 7)
        assertEquals(" 😀abc ", parsed.text)
        assertEquals(6, parsed.start)
        assertEquals(1, parsed.end)
        assertEquals(RssSourceEditorField.SourceName, parsed.field)
    }
    @Test fun invalidShapeOwnerNumericTypesKeysAndSelectionAreRejected() {
        reject(command("field", "key" to "SourceName", "value" to "x", "start" to 0, "end" to 0))
        reject(command("field", "key" to "sourceName", "value" to "x", "start" to -1, "end" to 0))
        reject(command("field", "key" to "sourceName", "value" to "x", "start" to 0L, "end" to 0))
        reject(command("focus", "key" to "sourceName", "path" to "private"))
        reject(command("focus", "key" to "sourceName") + ("generation" to 8))
        reject(command("focus", "key" to "sourceName") + ("generation" to 7L))
        reject(command("save", "action" to "SavedClose"))
        reject(command("native", "action" to "Editor"))
        reject(command("tab", "index" to 4))
    }
    @Test fun allFieldsAppearOnceInCorrectTabUsingWireKeys() {
        val tabs = snapshot(RssSourceEditorState())["tabs"] as List<*>
        val fields = tabs.flatMap { tab ->
            val map = tab as Map<*, *>
            (map["fields"] as List<*>).map { value ->
                val field = value as Map<*, *>
                val known = RssSourceEditorField.entries.single { it.key == field["key"] }
                assertEquals(known.tab, map["index"])
                known
            }
        }
        assertEquals(RssSourceEditorField.entries.toSet(), fields.toSet())
        assertEquals(33, fields.size)
    }
    @Test fun longAndCombiningFieldsStayPrivateAndCannotBecomeInlineWrites() {
        val huge = "secret".repeat(3000)
        val combining = "a" + "\u0301".repeat(8)
        val draft = RssSourceEditorDraft().with(RssSourceEditorField.StartJs, RssSourceEditorText(huge))
            .with(RssSourceEditorField.Header, RssSourceEditorText(combining))
        val result = snapshot(RssSourceEditorState(draft = draft))
        assertFalse(result.toString().contains(huge))
        assertFalse(result.toString().contains(combining))
        val all = (result["tabs"] as List<*>).flatMap { ((it as Map<*, *>)["fields"] as List<*>) }
        for (key in listOf("startJs", "header")) {
            val field = all.map { it as Map<*, *> }.single { it["key"] == key }
            assertEquals(true, field["external"])
            assertNull(field["text"])
            assertEquals(0, field["start"])
            assertEquals(0, field["end"])
        }
        reject(command("field", "key" to "startJs", "value" to huge, "start" to 0, "end" to 0))
        reject(command("field", "key" to "header", "value" to combining, "start" to 0, "end" to 0))
    }
    @Test fun optionsAreSinglePropertyStrictTypedUpdates() {
        rssEditorBooleanOptions.forEach { key ->
            assertEquals(key, parseRssEditorCommand(command("option", "key" to key, "value" to false), 7).key)
            reject(command("option", "key" to key, "value" to 0))
        }
        assertEquals(2, parseRssEditorCommand(command("option", "key" to "type", "value" to 2), 7).value)
        assertEquals(4, parseRssEditorCommand(command("option", "key" to "articleStyle", "value" to 4), 7).value)
        reject(command("option", "key" to "type", "value" to 3))
        reject(command("option", "key" to "articleStyle", "value" to 5))
        reject(command("option", "key" to "unknown", "value" to true))
    }
    @Test fun pendingEffectsPathsAndOwnerIdsNeverEnterProjectionAndErrorsAreSafe() {
        val private = "/data/user/0/pkg/files/rss-source-editor/12345678-1234-1234-1234-123456789abc.json"
        val state = RssSourceEditorState(error = private,
            effects = listOf(RssSourceEditorEffect("secret-owner", RssSourceEditorEffectKind.Editor, private)))
        val projected = snapshot(state).toString()
        assertFalse(projected.contains(private))
        assertFalse(projected.contains("secret-owner"))
        assertFalse(projected.contains("effects="))
        assertEquals("ordinary parser failure", rssEditorSafeError("ordinary parser failure"))
        assertTrue(runCatching { requireRssEditorBudget(mapOf("x" to "big".repeat(100)), 50) }.isFailure)
    }
}
