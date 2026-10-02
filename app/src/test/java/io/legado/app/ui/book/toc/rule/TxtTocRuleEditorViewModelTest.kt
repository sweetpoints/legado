package io.legado.app.ui.book.toc.rule

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.TxtTocRuleEditorRepository
import io.legado.app.data.repository.TxtTocRuleSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TxtTocRuleEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle(), id: Long? = null) = TxtTocRuleEditorViewModel(repo, saved, id, dispatcher)
    @Test fun loadingRetainsEveryEditableFieldAndIdMetadata() = runTest(dispatcher) {
        val rule = TxtTocRuleSnapshot(-12, "name", "^Chapter (.+)$", "@js:result", "Chapter One", 42, false)
        val model = model(Fake(rule), id = rule.id); runCurrent()
        assertEquals(rule, model.currentRule()); assertFalse(model.state.value.loading)
    }
    @Test fun saveUsesStableNewIdValidatesAndEmitsCallbackOnlyOnce() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved)
        model.setInput(TxtTocEditorField.Name, "new", 3); model.setInput(TxtTocEditorField.Regex, "^Chapter", 8)
        val id = model.currentRule().id; model.save(); model.save(); runCurrent()
        assertEquals(1, repo.writes.size); assertEquals(id, repo.writes.single().id)
        assertTrue(model.state.value.finished); assertEquals(repo.writes.single(), model.consumeSavedRule()); assertNull(model.consumeSavedRule())
    }
    @Test fun failedSaveKeepsDraftAndDoesNotNotifyOrClose() = runTest(dispatcher) {
        val repo = Fake().apply { write = { error("database failed") } }; val model = model(repo)
        model.setInput(TxtTocEditorField.Name, "draft", 5); model.save(); runCurrent()
        assertEquals("database failed", model.state.value.error); assertEquals("draft", model.state.value.name)
        assertFalse(model.state.value.finished); assertFalse(model.state.value.saving); assertNull(model.consumeSavedRule())
    }
    @Test fun emptyNameAndInvalidMultilineRegexCannotWrite() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo)
        model.save(); runCurrent(); assertEquals("名称不能为空", model.state.value.error)
        model.setInput(TxtTocEditorField.Name, "name", 4); model.setInput(TxtTocEditorField.Regex, "[", 1)
        model.save(); runCurrent(); assertTrue(model.state.value.error!!.startsWith("正则语法错误")); assertTrue(repo.writes.isEmpty())
    }
    @Test fun metadataReturnedBySaveIsUsedByCallbackAndRestoration() = runTest(dispatcher) {
        val old = TxtTocRuleSnapshot(123, "name", "regex", serialNumber = 2, enable = false)
        val repo = Fake(old).apply { write = { it.copy(serialNumber = 77, enable = true) } }; val saved = SavedStateHandle(); val model = model(repo, saved, 123); runCurrent()
        model.save(); runCurrent(); val delivered = model.consumeSavedRule()!!
        assertEquals(77, delivered.serialNumber); assertTrue(delivered.enable); assertEquals(123, delivered.id)
    }
    @Test fun pasteChangesOnlyEditableFieldsAndKeepsOriginalIdentityAndOrder() = runTest(dispatcher) {
        val old = TxtTocRuleSnapshot(-5, "old", "old regex", serialNumber = 42, enable = false)
        val model = model(Fake(old), id = old.id); runCurrent()
        model.paste("{\"id\":987,\"name\":\"pasted\",\"rule\":\"new regex\",\"replacement\":\"$1\",\"example\":\"sample\",\"serialNumber\":99,\"enable\":true}"); runCurrent()
        assertEquals(TxtTocRuleSnapshot(-5, "pasted", "new regex", "$1", "sample", 42, false), model.currentRule())
    }
    @Test fun malformedPasteNeverPartiallyReplacesInput() = runTest(dispatcher) {
        val model = model(Fake()); model.setInput(TxtTocEditorField.Name, "draft", 5)
        model.paste("{\"name\":\"partial\",\"rule\":null}"); runCurrent()
        assertEquals("draft", model.state.value.name); assertEquals("格式不对", model.state.value.error)
    }
    @Test fun latePasteAndLatePasteErrorCannotOverwriteTypingOrCurrentFeedback() = runTest(dispatcher) {
        val parse = StandardTestDispatcher(testScheduler)
        val model = TxtTocRuleEditorViewModel(Fake(), SavedStateHandle(), null, parse)
        model.paste("{\"name\":\"pasted\"}"); model.setInput(TxtTocEditorField.Name, "typed", 5); runCurrent()
        assertEquals("typed", model.state.value.name)
        model.paste("not json"); model.setInput(TxtTocEditorField.Name, "newer", 5); runCurrent()
        assertEquals("newer", model.state.value.name); assertNull(model.state.value.error)
    }
    @Test fun asyncLoadDoesNotOverwriteEarlierInput() = runTest(dispatcher) {
        val gate = CompletableDeferred<TxtTocRuleSnapshot?>(); val repo = Fake().apply { read = { gate.await() } }
        val model = model(repo, id = 123); runCurrent(); model.setInput(TxtTocEditorField.Replacement, "typed", 3)
        gate.complete(TxtTocRuleSnapshot(123, "loaded", "regex", "old", "example", 7, false)); runCurrent()
        assertEquals("typed", model.state.value.replacement); assertEquals("loaded", model.state.value.name); assertEquals(7, model.currentRule().serialNumber)
    }
    @Test fun loadFailureAndDeletedOriginalCannotSaveOverExistingOrCreateReplacement() = runTest(dispatcher) {
        val repo = Fake().apply { read = { error("read failed") } }; val model = model(repo, id = 123); runCurrent()
        model.save(); runCurrent(); assertTrue(model.state.value.loadFailed); assertTrue(repo.writes.isEmpty())
        repo.read = { null }; model.load(); runCurrent(); assertEquals("规则不存在", model.state.value.error); model.save(); runCurrent(); assertTrue(repo.writes.isEmpty())
        repo.read = { TxtTocRuleSnapshot(123, "retry") }; model.load(); runCurrent(); assertEquals("retry", model.state.value.name); assertFalse(model.state.value.loadFailed)
    }
    @Test fun draftAndCursorRestoreWithoutReloadAndNewIdStaysStable() = runTest(dispatcher) {
        val saved = SavedStateHandle(); val repo = Fake(); val first = model(repo, saved)
        first.setInput(TxtTocEditorField.Name, "draft", 2); first.setInput(TxtTocEditorField.Replacement, "return result", 4); first.focus(TxtTocEditorField.Replacement)
        val restored = model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })); runCurrent()
        assertEquals(first.currentRule(), restored.currentRule()); assertEquals(4, restored.state.value.cursors[2]); assertEquals(TxtTocEditorField.Replacement, restored.state.value.focused); assertEquals(0, repo.reads)
    }
    @Test fun codeRequestRestoresPendingFieldAndIgnoresDuplicateLaunchOrCanceledReply() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved)
        first.setInput(TxtTocEditorField.Replacement, "draft", 3); first.focus(TxtTocEditorField.Replacement)
        assertEquals(TxtTocEditorCodeRequest(TxtTocEditorField.Replacement, "draft", 3), first.codeRequest()); assertNull(first.codeRequest())
        val restored = model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertTrue(restored.codeResult("new", 2)); assertEquals("new", restored.state.value.replacement); assertEquals(2, restored.state.value.cursors[2])
        restored.codeRequest(); restored.codeCancelled(); assertFalse(restored.codeResult("obsolete", 0)); assertEquals("new", restored.state.value.replacement)
    }
    @Test fun dirtyExitKeepsDraftOrDiscardsWithoutSaving() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); model.setInput(TxtTocEditorField.Example, "sample", 3)
        model.requestClose(); assertTrue(model.state.value.confirmExit); assertFalse(model.state.value.finished)
        model.keepEditing(); assertEquals("sample", model.state.value.example); assertFalse(model.state.value.confirmExit)
        model.requestClose(); model.discard(); assertTrue(model.state.value.finished); assertTrue(repo.writes.isEmpty()); assertNull(model.consumeSavedRule())
    }
    @Test fun unchangedNullableExampleClosesWithoutDirtyPrompt() = runTest(dispatcher) {
        val model = model(Fake(TxtTocRuleSnapshot(123, "name", "regex", example = null)), id = 123); runCurrent()
        model.requestClose(); assertTrue(model.state.value.finished); assertFalse(model.state.value.confirmExit)
    }
    @Test fun restoredFinishedClosesWithoutWritingOrDuplicateCallback() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved)
        first.setInput(TxtTocEditorField.Name, "saved", 5); first.save(); runCurrent(); first.consumeSavedRule()
        val restored = model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })); runCurrent()
        assertTrue(restored.state.value.finished); assertNull(restored.consumeSavedRule()); assertEquals(1, repo.writes.size)
    }
    @Test fun restoredPendingSuccessCallbackRetainsFullMetadataAndDeliversOnce() = runTest(dispatcher) {
        val old = TxtTocRuleSnapshot(123, "old", "regex", serialNumber = 42, enable = false)
        val repo = Fake(old); val saved = SavedStateHandle(); val first = model(repo, saved, 123); runCurrent()
        first.setInput(TxtTocEditorField.Name, "saved", 5); first.save(); runCurrent()
        val restored = model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), 123); runCurrent()
        assertEquals(old.copy(name = "saved", example = ""), restored.consumeSavedRule()); assertNull(restored.consumeSavedRule()); assertEquals(1, repo.writes.size)
    }
    @Test fun existingDraftRequiresOriginalAtSaveAndKeepsInputOnConcurrentDeletion() = runTest(dispatcher) {
        val repo = Fake(TxtTocRuleSnapshot(123, "old", "regex")); val model = model(repo, id = 123); runCurrent()
        repo.write = { error("规则不存在") }
        model.setInput(TxtTocEditorField.Name, "draft", 5); model.save(); runCurrent()
        assertEquals(listOf(true), repo.existingRequirements); assertTrue(repo.writes.isEmpty())
        assertEquals("draft", model.state.value.name); assertEquals("规则不存在", model.state.value.error)
        assertFalse(model.state.value.finished); assertNull(model.consumeSavedRule())
        val newRepo = Fake(); val new = model(newRepo); new.setInput(TxtTocEditorField.Name, "new", 3); new.save(); runCurrent()
        assertEquals(listOf(false), newRepo.existingRequirements)
    }
    @Test fun copyJsonRoundTripsAllDraftFieldsAndMetadata() = runTest(dispatcher) {
        val old = TxtTocRuleSnapshot(123, "old", "regex", "@js:result", "example", 42, false)
        val model = model(Fake(old), id = 123); runCurrent(); model.setInput(TxtTocEditorField.Name, "draft", 5)
        val json = com.google.gson.JsonParser.parseString(model.copyJson()).asJsonObject
        assertEquals(123, json["id"].asLong); assertEquals("draft", json["name"].asString); assertEquals("regex", json["rule"].asString)
        assertEquals("@js:result", json["replacement"].asString); assertEquals("example", json["example"].asString); assertEquals(42, json["serialNumber"].asInt); assertFalse(json["enable"].asBoolean)
    }
    private class Fake(val initial: TxtTocRuleSnapshot? = null) : TxtTocRuleEditorRepository {
        val existingRequirements = mutableListOf<Boolean>()
        var reads = 0; val writes = mutableListOf<TxtTocRuleSnapshot>()
        var read: suspend () -> TxtTocRuleSnapshot? = { initial }
        var write: suspend (TxtTocRuleSnapshot) -> TxtTocRuleSnapshot = { it }
        override suspend fun load(id: Long): TxtTocRuleSnapshot? { reads++; return read() }
        override suspend fun save(rule: TxtTocRuleSnapshot, requireExisting: Boolean): TxtTocRuleSnapshot { existingRequirements += requireExisting; val value = write(rule); writes += value; return value }
    }
}
