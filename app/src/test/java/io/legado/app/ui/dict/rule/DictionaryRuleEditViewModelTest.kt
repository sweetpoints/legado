package io.legado.app.ui.dict.rule

import androidx.lifecycle.SavedStateHandle
import com.google.gson.JsonParser
import io.legado.app.data.repository.DictionaryRuleRepository
import io.legado.app.data.repository.DictionaryRuleSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryRuleEditViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun loadKeepsAllFieldsAndMetadata() = runTest(dispatcher) {
        val rule = DictionaryRuleSnapshot("old", "url", "show", false, 42)
        val model = DictionaryRuleEditViewModel(Fake(rule), SavedStateHandle(), "old"); runCurrent()
        assertEquals(rule, model.currentRule()); assertFalse(model.state.value.loading)
    }
    @Test fun renamePassesOldIdentityAndPreservesMetadata() = runTest(dispatcher) {
        val prefs = Fake(DictionaryRuleSnapshot("old", "url", "show", false, 42))
        val model = DictionaryRuleEditViewModel(prefs, SavedStateHandle(), "old"); runCurrent()
        model.setInput(DictionaryRuleField.Name, "new", 3); model.save(); model.save(); runCurrent()
        assertEquals(1, prefs.saved.size); assertEquals("old", prefs.saved.single().first)
        assertEquals(DictionaryRuleSnapshot("new", "url", "show", false, 42), prefs.saved.single().second)
        assertTrue(model.state.value.finished)
    }
    @Test fun saveFailureRetainsDraftAndDoesNotFinish() = runTest(dispatcher) {
        val prefs = Fake().apply { failure = IllegalStateException("write failed") }
        val model = DictionaryRuleEditViewModel(prefs, SavedStateHandle(), null)
        model.setInput(DictionaryRuleField.Name, "draft", 5); model.save(); runCurrent()
        assertFalse(model.state.value.finished); assertFalse(model.state.value.saving)
        assertEquals("draft", model.state.value.name); assertEquals("write failed", model.state.value.error)
    }
    @Test fun loadFailureCannotSaveOverExistingRowAndCanRetry() = runTest(dispatcher) {
        val prefs = Fake(DictionaryRuleSnapshot("old")).apply { read = { error("read failed") } }
        val model = DictionaryRuleEditViewModel(prefs, SavedStateHandle(), "old"); runCurrent()
        assertTrue(model.state.value.loadFailed); model.save(); runCurrent(); assertTrue(prefs.saved.isEmpty())
        prefs.read = { DictionaryRuleSnapshot("old", "retry") }; model.load(); runCurrent()
        assertFalse(model.state.value.loadFailed); assertEquals("retry", model.state.value.urlRule)
    }
    @Test fun loadedResultDoesNotOverwriteEarlierTyping() = runTest(dispatcher) {
        val deferred = CompletableDeferred<DictionaryRuleSnapshot?>()
        val prefs = Fake().apply { read = { deferred.await() } }
        val model = DictionaryRuleEditViewModel(prefs, SavedStateHandle(), "old"); runCurrent()
        model.setInput(DictionaryRuleField.ShowRule, "typed", 3)
        deferred.complete(DictionaryRuleSnapshot("old", "loaded-url", "loaded-show")); runCurrent()
        assertEquals("typed", model.state.value.showRule); assertEquals("loaded-url", model.state.value.urlRule)
    }
    @Test fun unchangedCloseFinishesAndEditedCloseAsksBeforeDiscard() = runTest(dispatcher) {
        val unchanged = DictionaryRuleEditViewModel(Fake(), SavedStateHandle(), null)
        unchanged.requestClose(); assertTrue(unchanged.state.value.finished)
        val prefs = Fake(); val model = DictionaryRuleEditViewModel(prefs, SavedStateHandle(), null)
        model.setInput(DictionaryRuleField.UrlRule, "unsaved", 7); model.requestClose()
        assertTrue(model.state.value.confirmExit); assertFalse(model.state.value.finished)
        model.keepEditing(); assertFalse(model.state.value.confirmExit)
        model.requestClose(); model.discard(); assertTrue(model.state.value.finished); assertTrue(prefs.saved.isEmpty())
    }
    @Test fun restoredDraftAndConfirmationSkipDatabaseLoad() = runTest(dispatcher) {
        val prefs = Fake(DictionaryRuleSnapshot("old", "url")); val handle = SavedStateHandle()
        val model = DictionaryRuleEditViewModel(prefs, handle, "old"); runCurrent()
        model.setInput(DictionaryRuleField.ShowRule, "draft", 4); model.requestClose()
        val restored = DictionaryRuleEditViewModel(prefs, snapshot(handle), "old"); runCurrent()
        assertEquals("draft", restored.state.value.showRule); assertTrue(restored.state.value.confirmExit)
        assertEquals(4, restored.state.value.selections[2]); assertEquals(1, prefs.reads)
    }
    @Test fun fullscreenResultTargetsSavedFieldAfterRestoration() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val model = DictionaryRuleEditViewModel(Fake(), handle, null)
        assertNull(model.fullEditRequest())
        model.setInput(DictionaryRuleField.ShowRule, "old", 2); model.focus(DictionaryRuleField.ShowRule)
        assertEquals(DictionaryFullEditRequest(DictionaryRuleField.ShowRule, "old", 2), model.fullEditRequest())
        val restored = DictionaryRuleEditViewModel(Fake(), snapshot(handle), null)
        assertTrue(restored.fullEditResult("returned", 99)); assertEquals("returned", restored.state.value.showRule)
        assertEquals(8, restored.state.value.selections[2]); assertFalse(restored.fullEditResult("wrong", 0))
    }
    @Test fun copyingSerializesLiveDraftAndKeepsEnabledAndSort() = runTest(dispatcher) {
        val model = DictionaryRuleEditViewModel(Fake(DictionaryRuleSnapshot("old", enabled = false, sortNumber = 12)), SavedStateHandle(), "old"); runCurrent()
        model.setInput(DictionaryRuleField.Name, "draft", 5)
        val json = JsonParser.parseString(model.copyJson()).asJsonObject
        assertEquals("draft", json["name"].asString); assertFalse(json["enabled"].asBoolean); assertEquals(12, json["sortNumber"].asInt)
    }
    @Test fun invalidOrEmptyPasteKeepsDraft() = runTest(dispatcher) {
        val model = DictionaryRuleEditViewModel(Fake(), SavedStateHandle(), null, dispatcher)
        model.setInput(DictionaryRuleField.Name, "draft", 5); model.paste(null)
        assertEquals("draft", model.state.value.name); assertNotNull(model.state.value.error)
        model.paste("invalid")
        runCurrent()
        assertEquals("draft", model.state.value.name)
    }
    @Test fun validPasteReplacesOnlyEditableFieldsAndRetainsOriginalMetadata() = runTest(dispatcher) {
        val model = DictionaryRuleEditViewModel(Fake(DictionaryRuleSnapshot("old", enabled = false, sortNumber = 7)), SavedStateHandle(), "old", dispatcher); runCurrent()
        model.paste("{\"name\":\"imported\",\"urlRule\":\"url\",\"showRule\":\"show\",\"enabled\":true,\"sortNumber\":99}"); runCurrent()
        assertEquals(DictionaryRuleSnapshot("imported", "url", "show", false, 7), model.currentRule())
    }
    @Test fun typingAfterPasteRequestWinsOverDelayedParser() = runTest(dispatcher) {
        val model = DictionaryRuleEditViewModel(Fake(), SavedStateHandle(), null, dispatcher)
        model.paste("{\"name\":\"imported\",\"urlRule\":\"url\",\"showRule\":\"show\"}")
        model.setInput(DictionaryRuleField.Name, "typed", 5); runCurrent()
        assertEquals("typed", model.state.value.name)
    }
    @Test fun restoredFinishedDoesNotSaveAgain() = runTest(dispatcher) {
        val prefs = Fake(); val handle = SavedStateHandle(); val model = DictionaryRuleEditViewModel(prefs, handle, null)
        model.save(); runCurrent()
        val restored = DictionaryRuleEditViewModel(prefs, snapshot(handle), null); restored.save(); runCurrent()
        assertTrue(restored.state.value.finished); assertEquals(1, prefs.saved.size)
    }

    @Test fun repeatedFullEditorRequestCannotReplaceThePendingTarget() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val model = DictionaryRuleEditViewModel(Fake(), handle, null)
        model.focus(DictionaryRuleField.UrlRule); assertNotNull(model.fullEditRequest())
        model.focus(DictionaryRuleField.ShowRule); assertNull(model.fullEditRequest())
        val restored = DictionaryRuleEditViewModel(Fake(), snapshot(handle), null)
        assertTrue(restored.fullEditResult("url-result", 10))
        assertEquals("url-result", restored.state.value.urlRule); assertEquals("", restored.state.value.showRule)
        assertFalse(restored.fullEditResult("second", 0))
    }
    @Test fun canceledFullEditorAllowsANewTargetAndCannotApplyLateResult() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val model = DictionaryRuleEditViewModel(Fake(), handle, null)
        model.focus(DictionaryRuleField.UrlRule); model.fullEditRequest(); model.fullEditCancelled()
        val restored = DictionaryRuleEditViewModel(Fake(), snapshot(handle), null)
        assertFalse(restored.fullEditResult("canceled-result", 0))
        restored.focus(DictionaryRuleField.ShowRule); assertNotNull(restored.fullEditRequest())
        assertTrue(restored.fullEditResult("show-result", 2)); assertEquals("show-result", restored.state.value.showRule)
    }
    @Test fun latePasteFailuresDoNotOverwriteTypingOrDiscardedState() = runTest(dispatcher) {
        val typing = DictionaryRuleEditViewModel(Fake(), SavedStateHandle(), null, dispatcher)
        typing.paste("invalid"); typing.setInput(DictionaryRuleField.Name, "typed", 5); runCurrent()
        assertEquals("typed", typing.state.value.name); assertNull(typing.state.value.error)
        val discarded = DictionaryRuleEditViewModel(Fake(), SavedStateHandle(), null, dispatcher)
        discarded.paste("invalid"); discarded.discard(); runCurrent()
        assertTrue(discarded.state.value.finished); assertNull(discarded.state.value.error)
    }
    private fun snapshot(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
    private class Fake(private val rule: DictionaryRuleSnapshot? = null) : DictionaryRuleRepository {
        val saved = mutableListOf<Pair<String?, DictionaryRuleSnapshot>>()
        var reads = 0; var failure: Exception? = null
        var read: suspend () -> DictionaryRuleSnapshot? = { rule }
        override suspend fun load(name: String): DictionaryRuleSnapshot? { reads++; return read() }
        override suspend fun save(previousName: String?, rule: DictionaryRuleSnapshot) { failure?.let { throw it }; saved += previousName to rule }
    }
}
