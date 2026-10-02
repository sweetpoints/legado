package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ContentEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    private val models = mutableListOf<ContentEditorViewModel>()
    @After fun close() { models.forEach { it.viewModelScope.cancel() }; dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private val target = ContentEditorTarget("fixed-book", 4, 10)
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = ContentEditorViewModel(repo, saved, target, "Reader title", dispatcher).also { models += it }
    private fun snapshot(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun loadedDraftDoesNotReplaceReaderTitleAndCopyUsesPresentation() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); advanceUntilIdle()
        assertEquals("Reader title\n" + repo.body, model.copyText()); assertFalse(model.state.value.hasChanges)
        model.togglePlainText(); advanceUntilIdle(); assertEquals("Reader title\nAB", model.copyText()); assertTrue(repo.plain)
    }
    @Test fun plainTextEditsRetainImagesAndCollapsedSelectionMapsAfterImages() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); advanceUntilIdle(); model.togglePlainText(); advanceUntilIdle()
        model.edit("AXB", 2, 2); advanceUntilIdle(); assertEquals("A<img src=\"image\">XB", model.state.value.raw)
        model.togglePlainText(); advanceUntilIdle(); assertEquals(model.state.value.raw.indexOf('X') + 1, model.state.value.selectionStart)
        model.save(); advanceUntilIdle(); assertEquals(listOf(target to "A<img src=\"image\">XB"), repo.saves); assertTrue(model.state.value.finished)
    }
    @Test fun dirtyCancelAwaitsSaveAndFailureKeepsDraftEditableAndDoesNotReload() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); advanceUntilIdle(); model.edit("changed", 2, 2); repo.saveGate = CompletableDeferred()
        model.close(); runCurrent(); assertTrue(model.state.value.saving); assertFalse(model.state.value.finished)
        repo.saveGate!!.completeExceptionally(IllegalStateException("disk full")); advanceUntilIdle()
        assertEquals("disk full", model.state.value.error); assertEquals("changed", model.state.value.raw); assertFalse(model.state.value.finished); assertFalse(model.consumeReload())
        repo.saveGate = null; model.close(); advanceUntilIdle(); assertTrue(model.state.value.finished); assertTrue(model.consumeReload()); assertFalse(model.consumeReload())
    }
    @Test fun cleanCancelDoesNotWriteBodyWhileExplicitSaveDoes() = runTest(dispatcher) {
        val first = Fake(); val firstModel = model(first); advanceUntilIdle(); firstModel.close(); advanceUntilIdle(); assertTrue(first.saves.isEmpty()); assertFalse(firstModel.consumeReload())
        val second = Fake(); val secondModel = model(second); advanceUntilIdle(); secondModel.save(); advanceUntilIdle(); assertEquals(listOf(target to second.body), second.saves)
    }
    @Test fun diskDraftRestoresLargeBodyAndSavedStateContainsNoChapterText() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); advanceUntilIdle()
        val large = "正文".repeat(200000); model.edit(large, 100, 100); model.setScroll(300); model.flushDraft(); runCurrent()
        assertTrue(saved.keys().none { saved.get<Any?>(it) == large }); assertEquals(large, repo.drafts[model.draftId]?.text)
        val restored = model(repo, snapshot(saved)); advanceUntilIdle(); assertEquals(large, restored.state.value.raw); assertTrue(restored.state.value.hasChanges); assertEquals(100, restored.state.value.selectionStart); assertEquals(300, restored.state.value.scrollY)
        assertEquals(1, repo.loads.size)
    }
    @Test fun finishedRestorationClosesWithoutSavingOrRepeatingConsumedReload() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); advanceUntilIdle(); first.save(); advanceUntilIdle(); assertTrue(first.consumeReload())
        val restored = model(repo, snapshot(saved)); advanceUntilIdle(); restored.save(); assertTrue(restored.state.value.finished); assertFalse(restored.consumeReload()); assertEquals(1, repo.saves.size); assertEquals(1, repo.loads.size)
    }
    @Test fun resetCannotOverwriteNewInputAndCancelWaitsBeforeSavingLatestBody() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); advanceUntilIdle(); repo.resetGate = CompletableDeferred()
        model.reset(); runCurrent(); model.edit("typed during reset", 3, 3); model.close(); runCurrent(); assertTrue(repo.saves.isEmpty())
        repo.resetGate!!.complete(ContentEditorLoaded("remote", "Remote title")); advanceUntilIdle()
        assertEquals("typed during reset", model.state.value.raw); assertEquals(listOf(target to "typed during reset"), repo.saves); assertTrue(model.state.value.finished)
    }
    @Test fun resetRequestsAreSerializedAndLatestResetIsApplied() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); advanceUntilIdle(); repo.resetGate = CompletableDeferred(); model.reset(); runCurrent(); model.reset(); model.reset(); runCurrent()
        assertEquals(1, repo.loads.count { it.second }); repo.resetGate!!.complete(ContentEditorLoaded("new", "Title")); advanceUntilIdle(); assertEquals(2, repo.loads.count { it.second }); assertEquals("new", model.state.value.raw); assertFalse(model.state.value.hasChanges)
    }
    @Test fun searchSupportsCaseRegexAndInvalidPatternWithoutMarkingDraftDirty() = runTest(dispatcher) {
        val repo = Fake().apply { body = "One one ONE" }; val model = model(repo); advanceUntilIdle(); model.setQuery("one"); advanceUntilIdle()
        assertEquals(3, model.state.value.matches.size); model.nextMatch(-1); assertEquals(2, model.state.value.matchIndex)
        model.setMatchCase(true); advanceUntilIdle(); assertEquals(1, model.state.value.matches.size)
        model.setRegex(true); model.setQuery("["); advanceUntilIdle(); assertTrue(model.state.value.searchInvalid); assertTrue(model.state.value.matches.isEmpty()); assertFalse(model.state.value.hasChanges)
        model.setQuery("(?=one)"); advanceUntilIdle(); assertEquals(1, model.state.value.matches.size); assertEquals(model.state.value.selectionStart, model.state.value.selectionEnd)
    }
    @Test fun editingDuringSearchDoesNotMoveCaretOrScroll() = runTest(dispatcher) {
        val repo = Fake().apply { body = "one one" }; val model = model(repo); advanceUntilIdle(); model.setQuery("one"); advanceUntilIdle(); model.setScroll(99)
        model.edit("one one!", 8, 8); val request = model.state.value.scrollRequest; advanceUntilIdle()
        assertEquals(8, model.state.value.selectionStart); assertEquals(99, model.state.value.scrollY); assertEquals(request, model.state.value.scrollRequest); assertEquals(2, model.state.value.matches.size)
    }
    @Test fun titleSaveIsAwaitedIndependentlyAndFailureKeepsTitleAndBodyDraft() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); advanceUntilIdle(); model.edit("dirty", 1, 1); model.openTitle(); advanceUntilIdle(); assertEquals("DB title", model.state.value.titleInput)
        model.editTitle("new title"); repo.titleFailure = true; model.saveTitle(); advanceUntilIdle(); assertTrue(model.state.value.titleEditor); assertEquals("dirty", model.state.value.raw); assertFalse(model.consumeReload())
        repo.titleFailure = false; model.saveTitle(); advanceUntilIdle(); assertEquals("display:new title", model.state.value.title); assertFalse(model.state.value.finished); assertTrue(model.state.value.hasChanges); assertTrue(model.consumeReload()); assertTrue(repo.saves.isEmpty())
    }
    @Test fun missingBookLoadFailureCanRetryWithoutSavingEmptyBody() = runTest(dispatcher) {
        val repo = Fake().apply { loadFailure = true }; val model = model(repo); advanceUntilIdle(); assertFalse(model.state.value.hasDraft); assertNotNull(model.state.value.error)
        repo.loadFailure = false; model.retryLoad(); advanceUntilIdle(); assertTrue(model.state.value.hasDraft); assertEquals(repo.body, model.state.value.raw)
    }
    private class Fake : ContentEditorRepository {
        var body = "A<img src=\"image\">B"; var plain = false; var loadFailure = false; var titleFailure = false
        var resetGate: CompletableDeferred<ContentEditorLoaded>? = null; var saveGate: CompletableDeferred<Unit>? = null
        val drafts = mutableMapOf<String, ContentEditorDraft>(); val saves = mutableListOf<Pair<ContentEditorTarget, String>>(); val loads = mutableListOf<Pair<ContentEditorTarget, Boolean>>()
        override suspend fun load(target: ContentEditorTarget, reset: Boolean): ContentEditorLoaded { loads += target to reset; if (loadFailure) error("missing book"); return if (reset && resetGate != null) resetGate!!.await() else ContentEditorLoaded(body, "DB title") }
        override suspend fun save(target: ContentEditorTarget, text: String) { saveGate?.await(); saves += target to text }
        override suspend fun title(target: ContentEditorTarget) = "DB title"
        override suspend fun saveTitle(target: ContentEditorTarget, title: String): String { if (titleFailure) error("title failure"); return "display:$title" }
        override suspend fun plainText() = plain
        override suspend fun setPlainText(value: Boolean) { plain = value }
        override suspend fun readDraft(id: String) = drafts[id]
        override suspend fun writeDraft(id: String, draft: ContentEditorDraft) { if ((drafts[id]?.revision ?: -1) <= draft.revision) drafts[id] = draft }
        override suspend fun deleteDraft(id: String) { drafts.remove(id) }
    }
}
