package io.legado.app.ui.dict.rule

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.DictionaryRuleManagementRepository
import io.legado.app.data.repository.DictionaryRuleSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryRuleManagementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun slideUsesAnchorStateAndReversalMatchesOriginalToggleAndReverse() {
        val range = DictionaryRangeSelection(listOf("a", "b", "c", "d"), setOf("b", "d"), 0)
        assertEquals(setOf("a", "b", "c", "d"), range.at(2))
        assertEquals(setOf("a", "b", "d"), range.at(1))
        assertEquals(setOf("a", "d"), range.at(0))
    }
    @Test fun selectedAnchorClearsRangeAndReversedRowsBecomeSelected() {
        val range = DictionaryRangeSelection(listOf("a", "b", "c", "d"), setOf("a", "d"), 0)
        assertEquals(setOf("d"), range.at(2))
        assertEquals(setOf("c", "d"), range.at(1))
        assertEquals(setOf("b", "c", "d"), range.at(0))
    }
    @Test fun selectionUsesVisibleOrderAndPrunesDeletedNames() = runTest(dispatcher) {
        val repo = Fake(); val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.toggle("c"); model.toggle("a")
        assertEquals(listOf("a", "c"), model.state.value.selection.map { it.name })
        repo.rows.value = repo.rows.value.filterNot { it.name == "a" }; runCurrent()
        assertEquals(setOf("c"), model.state.value.selected)
        model.invert(); assertEquals(setOf("b"), model.state.value.selected)
    }
    @Test fun savedSelectionDraftAndOnlineInputRestore() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle()
        val first = DictionaryRuleManagementViewModel(repo, saved); runCurrent()
        first.toggle("b"); first.showOnline(true); first.inputOnline("[{draft}]"); runCurrent()
        val restored = DictionaryRuleManagementViewModel(repo, SavedStateHandle(mapOf(
            "dictionary.management.selection" to saved.get<ArrayList<String>>("dictionary.management.selection"),
            "dictionary.management.online" to true, "dictionary.management.input" to "[{draft}]")))
        runCurrent(); assertEquals(setOf("b"), restored.state.value.selected)
        assertTrue(restored.state.value.online); assertEquals("[{draft}]", restored.state.value.onlineInput)
    }
    @Test fun bulkEnableAndDeleteUseOnlySelectedIdentities() = runTest(dispatcher) {
        val repo = Fake(); val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.toggle("b"); model.enableSelection(false); runCurrent()
        assertEquals(listOf("b") to false, repo.enabled.single())
        model.deleteSelection(); runCurrent(); assertEquals(listOf("b"), repo.deleted.single())
    }
    @Test fun singleDeleteRequiresConfirmationAndCancelDoesNotWrite() = runTest(dispatcher) {
        val repo = Fake(); val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.requestDelete("a"); assertTrue(repo.deleted.isEmpty())
        model.requestDelete(null); model.confirmDelete(); runCurrent(); assertTrue(repo.deleted.isEmpty())
        model.requestDelete("b"); model.confirmDelete(); runCurrent(); assertEquals(listOf("b"), repo.deleted.single())
    }
    @Test fun movingEqualSortNumbersPersistsActualListOrderOnce() = runTest(dispatcher) {
        val repo = Fake(); val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.move("a", "c"); assertEquals(listOf("b", "c", "a"), model.state.value.rules.map { it.name })
        assertTrue(repo.orders.isEmpty()); model.finishReorder(); model.finishReorder(); runCurrent()
        assertEquals(listOf(listOf("b", "c", "a")), repo.orders)
    }
    @Test fun canceledReorderRestoresBaselineAndDoesNotWrite() = runTest(dispatcher) {
        val repo = Fake(); val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.move("a", "c"); model.cancelReorder(); runCurrent()
        assertEquals(listOf("a", "b", "c"), model.state.value.rules.map { it.name })
        model.finishReorder(); runCurrent(); assertTrue(repo.orders.isEmpty())
    }
    @Test fun midGestureProcessRestoreRestoresOrderAndSelectionWithoutWriting() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle()
        val model = DictionaryRuleManagementViewModel(repo, saved); runCurrent()
        model.toggle("b"); model.beginSlide("a"); model.slideTo("c")
        model.move("a", "c")
        val restored = DictionaryRuleManagementViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        runCurrent()
        assertEquals(listOf("a", "b", "c"), restored.state.value.rules.map { it.name })
        assertEquals(setOf("b"), restored.state.value.selected); assertTrue(repo.orders.isEmpty())
        restored.finishReorder(); runCurrent(); assertTrue(repo.orders.isEmpty())
    }
    @Test fun cancelSlideRestoresMixedBaselineAndClearsRange() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = DictionaryRuleManagementViewModel(repo, saved); runCurrent()
        model.toggle("b"); model.beginSlide("a"); model.slideTo("c")
        assertEquals(setOf("a", "b", "c"), model.state.value.selected)
        model.cancelSlide(); model.slideTo("c")
        assertEquals(setOf("b"), model.state.value.selected); assertFalse(saved.contains("dictionary.management.selection.baseline"))
    }
    @Test fun normalSlideReleaseKeepsSelectionThroughRestore() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = DictionaryRuleManagementViewModel(repo, saved); runCurrent()
        model.beginSlide("a"); model.slideTo("b"); model.endSlide()
        val restored = DictionaryRuleManagementViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        runCurrent(); assertEquals(setOf("a", "b"), restored.state.value.selected)
    }
    @Test fun lifecycleCancellationAfterReleaseDoesNotUndoCommittedOrder() = runTest(dispatcher) {
        val repo = Fake(); val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.move("a", "c"); model.finishReorder(); model.cancelGestures(); runCurrent()
        assertEquals(listOf(listOf("b", "c", "a")), repo.orders)
    }
    @Test fun restoredPendingOrderReappliesAndFailureCanRetry() = runTest(dispatcher) {
        val repo = Fake().apply { orderFailure = true }
        val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle(mapOf("dictionary.management.order" to arrayListOf("c", "a", "b"), "dictionary.management.order.committed" to true)))
        runCurrent(); assertEquals(listOf("c", "a", "b"), model.state.value.rules.map { it.name }); assertNotNull(model.state.value.error)
        repo.orderFailure = false; model.retry(); runCurrent(); assertEquals(listOf("c", "a", "b"), repo.orders.single())
    }
    @Test fun emptySelectionCannotShareOrExport() = runTest(dispatcher) {
        val repo = Fake(); val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.share(); model.export(); runCurrent(); assertTrue(repo.serialized.isEmpty()); assertNull(model.state.value.effect)
    }
    @Test fun shareCapturesImmutableSelectionBeforeLaterFlowChange() = runTest(dispatcher) {
        val repo = Fake(); val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.toggle("b"); model.share(); repo.rows.value = listOf(DictionaryRuleSnapshot("b", "updated")); runCurrent()
        assertEquals("url-b", repo.serialized.single().single().urlRule)
        assertEquals(DictionaryManagementEffectKind.ShareFile, model.consumeEffect()?.kind); assertNull(model.consumeEffect())
    }
    @Test fun exportContainsOnlySelectedRulesAndPreservesFullMetadata() = runTest(dispatcher) {
        val repo = Fake(); val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.toggle("c"); model.export(); runCurrent()
        assertEquals(listOf(DictionaryRuleSnapshot("c", "url-c", "show-c", false, 42)), repo.serialized.single())
        assertEquals(DictionaryManagementEffectKind.ExportJson, model.consumeEffect()?.kind)
    }
    @Test fun pendingEffectRestoresAndIsConsumedOnlyOnce() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf("dictionary.management.effect.kind" to "ImportText", "dictionary.management.effect.value" to "[]"))
        val model = DictionaryRuleManagementViewModel(Fake(), saved); runCurrent()
        assertEquals(DictionaryManagementEffect(DictionaryManagementEffectKind.ImportText, "[]"), model.consumeEffect())
        assertNull(model.consumeEffect()); assertFalse(saved.contains("dictionary.management.effect.kind"))
    }
    @Test fun onlineImportAcceptsJsonAndKeepsInputAcrossDelayedHistory() = runTest(dispatcher) {
        val repo = Fake(); val gate = CompletableDeferred<List<String>>(); repo.historyRead = { gate.await() }
        val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.showOnline(true); runCurrent(); model.inputOnline("[{\"name\":\"typed\"}]")
        gate.complete(listOf("https://old")); runCurrent()
        assertEquals("[{\"name\":\"typed\"}]", model.state.value.onlineInput)
        model.confirmOnline(); runCurrent(); assertFalse(model.state.value.online)
        assertEquals(model.state.value.onlineInput, model.consumeEffect()?.value)
    }
    @Test fun lateHistoryCannotUndoDeletion() = runTest(dispatcher) {
        val repo = Fake(); val gate = CompletableDeferred<List<String>>(); var reads = 0
        repo.historyRead = { if (++reads == 1) withContext(NonCancellable) { gate.await() } else listOf("new") }
        val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.showOnline(true); runCurrent(); model.deleteHistory("old"); runCurrent()
        gate.complete(listOf("old")); runCurrent(); assertEquals(listOf("new"), model.state.value.history)
    }
    @Test fun exportCopyUsesOriginalUrlAndLateSummaryDoesNotReopenDialog() = runTest(dispatcher) {
        val repo = Fake(); val gate = CompletableDeferred<String>(); repo.summaryRead = { gate.await() }
        val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle()); runCurrent()
        model.exportFinished("https://original"); runCurrent(); model.copyExport()
        gate.complete("summary"); runCurrent(); assertNull(model.state.value.exportUrl)
        assertEquals(DictionaryManagementEffect(DictionaryManagementEffectKind.Clipboard, "https://original"), model.consumeEffect())
    }
    @Test fun passphraseCreationClosesExportAndCopyClearsPhrase() = runTest(dispatcher) {
        val model = DictionaryRuleManagementViewModel(Fake(), SavedStateHandle()); runCurrent()
        model.exportFinished("https://url"); model.createPassphrase(); runCurrent()
        assertNull(model.state.value.exportUrl); assertEquals("phrase:https://url", model.state.value.passphrase)
        model.copyPassphrase(); assertNull(model.state.value.passphrase); assertEquals("phrase:https://url", model.consumeEffect()?.value)
    }
    private class Fake : DictionaryRuleManagementRepository {
        val rows = MutableStateFlow(listOf("a", "b", "c").map { DictionaryRuleSnapshot(it, "url-$it", "show-$it", false, 42) })
        val enabled = mutableListOf<Pair<List<String>,Boolean>>(); val deleted = mutableListOf<List<String>>()
        val orders = mutableListOf<List<String>>(); val serialized = mutableListOf<List<DictionaryRuleSnapshot>>()
        var orderFailure = false
        var historyRead: suspend () -> List<String> = { emptyList() }; var summaryRead: suspend () -> String = { "summary" }
        override fun observe() = rows
        override suspend fun setEnabled(names: List<String>, enabled: Boolean) { this.enabled += names to enabled }
        override suspend fun delete(names: List<String>) { deleted += names }
        override suspend fun reorder(names: List<String>) { if (orderFailure) error("order failed"); orders += names }
        override suspend fun importDefault() {}
        override suspend fun history() = historyRead()
        override suspend fun rememberUrl(url: String) {}
        override suspend fun removeUrl(url: String) {}
        override suspend fun json(rules: List<DictionaryRuleSnapshot>): String { serialized += rules; return "json" }
        override suspend fun shareFile(rules: List<DictionaryRuleSnapshot>): String { serialized += rules; return "/cache/share.json" }
        override suspend fun exportSummary(url: String) = summaryRead()
        override suspend fun passphrase(url: String) = "phrase:$url"
    }
}
