package io.legado.app.ui.book.toc.rule

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.TxtTocRuleEditorRepository
import io.legado.app.data.repository.TxtTocRuleSnapshot
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TxtTocRuleEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun show(repo: Fake, saved: SavedStateHandle = SavedStateHandle(), onSaved: (TxtTocRuleSnapshot) -> Unit = {}, close: () -> Unit = {}, paste: () -> String? = { null }, copy: (String) -> Unit = {}, code: (TxtTocEditorCodeRequest) -> Unit = {}): TxtTocRuleEditorViewModel {
        val model = TxtTocRuleEditorViewModel(repo, saved, repo.original?.id)
        compose.setContent { LegadoComposeTheme { TxtTocRuleEditorRoute(model, code, copy, paste, {}, onSaved, close) } }
        compose.waitUntil { !model.state.value.loading }
        return model
    }
    @Test fun saveAllFieldsWaitsForPersistenceThenNotifiesAndClosesOnce() {
        val repo = Fake(TxtTocRuleSnapshot(-12, "old", "regex", serialNumber = 42, enable = false)); val gate = CompletableDeferred<Unit>(); repo.wait = gate
        val delivered = mutableListOf<TxtTocRuleSnapshot>(); var closes = 0
        show(repo, onSaved = delivered::add, close = { closes++ })
        compose.onNodeWithTag("txt-toc-editor-Name").performTextReplacement("new")
        compose.onNodeWithTag("txt-toc-editor-Regex").performScrollTo().performTextReplacement("^Chapter (.+)$")
        compose.onNodeWithTag("txt-toc-editor-Replacement").performScrollTo().performTextReplacement("@js:result")
        compose.onNodeWithTag("txt-toc-editor-Example").performScrollTo().performTextReplacement("Chapter One")
        compose.onNodeWithTag("txt-toc-editor-save").performClick()
        compose.runOnIdle { assertTrue(delivered.isEmpty()); assertEquals(0, closes); gate.complete(Unit) }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { assertEquals(TxtTocRuleSnapshot(-12, "new", "^Chapter (.+)$", "@js:result", "Chapter One", 42, false), delivered.single()); assertEquals(1, repo.writes.size) }
    }
    @Test fun saveFailureLeavesDraftOpenAndDoesNotDeliverCallback() {
        val repo = Fake().apply { failure = true }; var delivered = 0; var closes = 0
        val model = show(repo, onSaved = { delivered++ }, close = { closes++ })
        compose.onNodeWithTag("txt-toc-editor-Name").performTextReplacement("draft")
        compose.onNodeWithTag("txt-toc-editor-save").performClick()
        compose.waitUntil { model.state.value.error != null }
        compose.onNodeWithTag("txt-toc-editor-Name").assertTextContains("draft")
        compose.runOnIdle { assertEquals(0, delivered); assertEquals(0, closes); assertTrue(repo.writes.isEmpty()) }
    }
    @Test fun pastedFieldsKeepIdAndCopyExportsCurrentDraftMetadata() {
        val repo = Fake(TxtTocRuleSnapshot(-12, "old", "regex", serialNumber = 42, enable = false)); var copied = ""
        show(repo, paste = { "{\"id\":999,\"name\":\"pasted\",\"rule\":\"^Chapter\",\"replacement\":\"$1\",\"example\":\"sample\",\"serialNumber\":99,\"enable\":true}" }, copy = { copied = it })
        compose.onNodeWithTag("txt-toc-editor-menu").performClick(); compose.onNodeWithTag("txt-toc-editor-paste").performClick()
        compose.waitUntil { compose.onAllNodesWithText("pasted", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("txt-toc-editor-menu").performClick(); compose.onNodeWithTag("txt-toc-editor-copy").performClick()
        compose.runOnIdle {
            val json = com.google.gson.JsonParser.parseString(copied).asJsonObject
            assertEquals(-12, json["id"].asLong); assertEquals(42, json["serialNumber"].asInt); assertFalse(json["enable"].asBoolean); assertEquals("pasted", json["name"].asString)
        }
    }
    @Test fun backgroundExitConfirmationCanKeepThenDiscardWithoutSaving() {
        val repo = Fake(); var closes = 0; show(repo, close = { closes++ })
        compose.onNodeWithTag("txt-toc-editor-Example").performScrollTo().performTextReplacement("draft sample")
        compose.onNodeWithTag("txt-toc-editor-backdrop").performTouchInput { click(Offset(8f, 8f)) }
        compose.onNodeWithTag("txt-toc-editor-keep").performClick()
        compose.onNodeWithTag("txt-toc-editor-Example").assertTextContains("draft sample")
        compose.onNodeWithTag("txt-toc-editor-backdrop").performTouchInput { click(Offset(8f, 8f)) }
        compose.onNodeWithTag("txt-toc-editor-discard").performClick()
        compose.waitUntil { closes == 1 }; compose.runOnIdle { assertTrue(repo.writes.isEmpty()) }
    }
    @Test fun fullscreenReplacementUsesFocusedFieldTextAndCursor() {
        var request: TxtTocEditorCodeRequest? = null; show(Fake(), code = { request = it })
        compose.onNodeWithTag("txt-toc-editor-Replacement").performScrollTo().performTextReplacement("return result")
        compose.onNodeWithTag("txt-toc-editor-fullscreen").performClick()
        compose.runOnIdle { assertEquals(TxtTocEditorField.Replacement, request?.field); assertEquals("return result", request?.text) }
    }
    @Test fun restoredFinishedWithoutPendingCallbackClosesOnly() {
        val repo = Fake(); var closes = 0; var delivered = 0
        show(repo, SavedStateHandle(mapOf("txt.toc.editor.loaded" to true, "txt.toc.editor.finished" to true)), onSaved = { delivered++ }, close = { closes++ })
        compose.waitUntil { closes == 1 }; compose.runOnIdle { assertEquals(0, delivered); assertTrue(repo.writes.isEmpty()) }
    }
    @Test fun restoredPendingSuccessDeliversMetadataWithoutAnotherWrite() {
        val repo = Fake(); val saved = SavedStateHandle(mapOf("txt.toc.editor.loaded" to true, "txt.toc.editor.hasOriginal" to true, "txt.toc.editor.finished" to true,
            "txt.toc.editor.pendingCallback" to true, "txt.toc.editor.id" to -12L, "txt.toc.editor.order" to 42, "txt.toc.editor.enable" to false,
            "txt.toc.editor.Name" to "saved", "txt.toc.editor.Regex" to "regex", "txt.toc.editor.Replacement" to "rep", "txt.toc.editor.Example" to "sample"))
        val delivered = mutableListOf<TxtTocRuleSnapshot>(); var closes = 0
        show(repo, saved, delivered::add, { closes++ })
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { assertEquals(TxtTocRuleSnapshot(-12, "saved", "regex", "rep", "sample", 42, false), delivered.single()); assertTrue(repo.writes.isEmpty()) }
    }
    private class Fake(val original: TxtTocRuleSnapshot? = null) : TxtTocRuleEditorRepository {
        var failure = false; var wait: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<TxtTocRuleSnapshot>()
        override suspend fun load(id: Long) = original
        override suspend fun save(rule: TxtTocRuleSnapshot, requireExisting: Boolean): TxtTocRuleSnapshot { wait?.await(); if (failure) error("failed"); writes += rule; return rule }
    }
}
