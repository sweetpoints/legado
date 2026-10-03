package io.legado.app.ui.code

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeEditorSessionTest {
    @Test
    fun completePrivateSessionRoundTripPreservesRawCodeAndReversedUtf16Selection() {
        val text = "😀\r\n@js:const value = 'e\u0301';"
        val session =
            CodeEditorSession(
                text,
                selection = CodeEditorSelection(text.length, 1),
                title = "rule",
                writable = false,
                checkJavaScriptSyntax = true,
                showDebugSource = true,
                showLoginSource = true,
                returnUnchangedText = true,
                useTextFile = true,
                search =
                    CodeEditorSearch(
                        visible = true,
                        replaceVisible = true,
                        query = "(value)",
                        replacement = "$1",
                        querySelection = CodeEditorSelection(4, 1),
                    ),
                returnReceipt =
                    CodeEditorReturnReceipt("owner", 3, true, "debugSource", "file", true),
                revision = 9,
            )
        val gson = Gson()
        assertEquals(session, gson.fromJson(gson.toJson(session), CodeEditorSession::class.java))
        assertFalse(session.dirty)
        assertEquals(CodeEditorSelection(text.length, 1), session.selection)
    }

    @Test
    fun editsPreserveRawTextAndBoundSelectionWithoutChangingItsDirection() {
        val original = CodeEditorSession("initial")
        val raw = "😀\r\n"
        val edited = original.edited(raw, CodeEditorSelection(99, -1))
        assertTrue(edited.dirty)
        assertEquals(raw, edited.text)
        assertEquals(CodeEditorSelection(raw.length, 0), edited.selection)
        assertEquals("initial", edited.initialText)
    }

    @Test
    fun closedSessionClearsCodeSearchAndReceiptPayload() {
        val closed =
            CodeEditorSession(
                    "secret",
                    search = CodeEditorSearch(query = "secret"),
                    returnReceipt = CodeEditorReturnReceipt("owner", 0, true),
                )
                .closed()
        assertTrue(closed.finished)
        assertEquals("", closed.text)
        assertEquals("", closed.initialText)
        assertEquals("", closed.search.query)
        assertEquals(null, closed.returnReceipt)
    }

    @Test
    fun htmlDetectionRetainsLegacyPriorityOverRequestedLanguage() {
        assertEquals(
            "text.html.basic",
            codeEditorLanguage("  <!DOCTYPE html><html></html>  ", "source.js"),
        )
        assertEquals("source.python", codeEditorLanguage("print('x')", "source.python"))
        assertEquals("source.js", codeEditorLanguage("@js:1+1", null))
    }
}
