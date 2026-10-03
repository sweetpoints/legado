package io.legado.app.ui.book.toc.rule

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.TxtTocRuleManagementRepository
import io.legado.app.data.repository.TxtTocRuleSnapshot
import io.legado.app.model.localBook.TextFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TxtTocRuleManagementViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun close() {
        Dispatchers.resetMain()
    }

    private fun model(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        picker: Boolean = false,
        regex: String? = null,
    ) = TxtTocRuleManagementViewModel(repo, saved, picker, regex)

    private fun snapshot(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun searchMatchesNameAndExampleAndClearsManagementSelection() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.toggle(1)
            vm.inputQuery(" CHAPTER B ")
            assertEquals(listOf(2L), vm.state.value.visible.map { it.id })
            assertTrue(vm.state.value.selected.isEmpty())
            vm.selectAll()
            assertEquals(listOf(2L), vm.state.value.selection.map { it.id })
            vm.invert()
            assertTrue(vm.state.value.selected.isEmpty())
            vm.inputQuery("ALPHA")
            assertEquals(listOf(1L), vm.state.value.visible.map { it.id })
        }

    @Test
    fun selectionUsesDisplayedOrderAndPrunesDeletedRows() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.toggle(3)
            vm.toggle(1)
            assertEquals(listOf(1L, 3L), vm.state.value.selection.map { it.id })
            repo.rows.value = repo.rows.value.filterNot { it.id == 1L }
            runCurrent()
            assertEquals(setOf(3L), vm.state.value.selected)
        }

    @Test
    fun bothBulkAndSingleDeleteRequireConfirmationAndCancelDoesNotWrite() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.toggle(2)
            vm.deleteSelection()
            runCurrent()
            assertTrue(repo.deleted.isEmpty())
            assertEquals(listOf(2L), vm.state.value.deleteIds)
            vm.cancelDelete()
            vm.confirmDelete()
            runCurrent()
            assertTrue(repo.deleted.isEmpty())
            vm.requestDelete(listOf(1, 99))
            vm.confirmDelete()
            runCurrent()
            assertEquals(listOf(listOf(1L)), repo.deleted)
        }

    @Test
    fun enableAndEdgesUseOnlyExplicitOrSelectedIdentities() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.toggle(2)
            vm.enableSelection(false)
            runCurrent()
            assertEquals(listOf(2L) to false, repo.enabled.single())
            vm.toEdge(3, true)
            runCurrent()
            assertEquals(listOf(3L) to true, repo.edges.single())
        }

    @Test
    fun filteredReorderingIsDisabledButBlankSearchAllowsActualOrderCommit() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.inputQuery("alpha")
            vm.move(1, 3)
            vm.finishReorder()
            runCurrent()
            assertTrue(repo.orders.isEmpty())
            vm.inputQuery("  ")
            vm.move(1, 3)
            assertEquals(listOf(2L, 3L, 1L), vm.state.value.rules.map { it.id })
            vm.finishReorder()
            vm.finishReorder()
            runCurrent()
            assertEquals(listOf(listOf(2L, 3L, 1L)), repo.orders)
        }

    @Test
    fun canceledAndRestoredUnfinishedGesturesRestoreBaselineWithoutWriting() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.toggle(2)
            vm.beginSlide(1)
            vm.slideTo(3)
            vm.move(1, 3)
            val restored = model(repo, snapshot(saved))
            runCurrent()
            assertEquals(setOf(2L), restored.state.value.selected)
            assertEquals(listOf(1L, 2L, 3L), restored.state.value.rules.map { it.id })
            restored.finishReorder()
            runCurrent()
            assertTrue(repo.orders.isEmpty())
            vm.cancelGestures()
            vm.slideTo(3)
            vm.finishReorder()
            runCurrent()
            assertEquals(setOf(2L), vm.state.value.selected)
            assertEquals(listOf(1L, 2L, 3L), vm.state.value.rules.map { it.id })
            assertTrue(repo.orders.isEmpty())
        }

    @Test
    fun slideReversalUsesOriginalAnchorStateAndRealReleaseRestoresSelection() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.toggle(2)
            vm.beginSlide(1)
            vm.slideTo(3)
            vm.slideTo(1)
            assertEquals(setOf(1L), vm.state.value.selected)
            vm.endSlide()
            val restored = model(repo, snapshot(saved))
            runCurrent()
            assertEquals(setOf(1L), restored.state.value.selected)
        }

    @Test
    fun readerChoiceUsesExactRegexIncludesDisabledAndSurvivesSearchAndRestore() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved, true, "beta" + TextFile.spaceChars + "$1")
            runCurrent()
            assertEquals(2L, vm.state.value.selectedId)
            vm.inputQuery("alpha")
            assertEquals(2L, vm.state.value.selectedId)
            val restoredSaved = snapshot(saved)
            val restored = model(repo, restoredSaved, true)
            runCurrent()
            restored.confirmChoice()
            restored.confirmChoice()
            assertEquals(
                TxtTocManagementEffect(
                    TxtTocManagementEffectKind.ReturnRegex,
                    "beta" + TextFile.spaceChars + "$1",
                ),
                restored.consumeEffect(),
            )
            assertNull(restored.consumeEffect())
            assertTrue(restored.state.value.pickerFinished)
            assertTrue(repo.orders.isEmpty())
            val completed = model(repo, snapshot(restoredSaved), true)
            runCurrent()
            assertTrue(completed.state.value.pickerFinished)
            assertNull(completed.consumeEffect())
        }

    @Test
    fun duplicateNamesChooseClickedIdentityAndMissingSelectionDoesNotClose() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.rows.value = repo.rows.value.map { it.copy(name = "same") }
            val vm = model(repo, picker = true)
            runCurrent()
            vm.confirmChoice()
            assertFalse(vm.state.value.pickerFinished)
            vm.choose(3)
            vm.confirmChoice()
            assertEquals("gamma" + TextFile.spaceChars, vm.consumeEffect()?.value)
        }

    @Test
    fun shareAndExportCaptureOnlySelectedCompleteSnapshotsBeforeLaterFlow() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.share()
            vm.export()
            runCurrent()
            assertTrue(repo.serialized.isEmpty())
            vm.toggle(2)
            val selected = repo.rows.value[1]
            vm.share()
            repo.rows.value = repo.rows.value.map { it.copy(rule = "updated") }
            runCurrent()
            assertEquals(listOf(selected), repo.serialized.single())
            assertEquals(TxtTocManagementEffectKind.ShareFile, vm.consumeEffect()?.kind)
            vm.export()
            runCurrent()
            assertEquals("updated", repo.serialized.last().single().rule)
            assertEquals(TxtTocManagementEffectKind.ExportJson, vm.consumeEffect()?.kind)
        }

    @Test
    fun delayedHistoryDoesNotOverwriteInputOrUndoHistoryRemoval() =
        runTest(dispatcher) {
            val repo = Fake()
            val gate = CompletableDeferred<List<String>>()
            var reads = 0
            repo.historyRead = {
                if (++reads == 1) withContext(NonCancellable) { gate.await() } else listOf("new")
            }
            val vm = model(repo)
            runCurrent()
            vm.showOnline(true)
            runCurrent()
            vm.inputOnline("[]")
            vm.deleteHistory("old")
            runCurrent()
            gate.complete(listOf("old"))
            runCurrent()
            assertTrue(vm.state.value.history.isEmpty())
            assertEquals("[]", vm.state.value.onlineInput)
            vm.confirmOnline()
            runCurrent()
            assertFalse(vm.state.value.online)
            assertEquals("[]", vm.consumeEffect()?.value)
        }

    @Test
    fun exportCopyKeepsOriginalUrlAndPassphraseAndPendingEffectsRestoreOnce() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.exportFinished("https://original")
            vm.copyExport()
            runCurrent()
            assertNull(vm.state.value.exportUrl)
            assertEquals("https://original", vm.consumeEffect()?.value)
            vm.exportFinished("https://another")
            vm.createPassphrase()
            runCurrent()
            assertNull(vm.state.value.exportUrl)
            vm.copyPassphrase()
            val restored = model(repo, snapshot(saved))
            runCurrent()
            assertEquals("phrase:https://another", restored.consumeEffect()?.value)
            assertNull(restored.consumeEffect())
        }

    @Test
    fun deletingDefaultHistoryHidesItNowAndNextOpenReintroducesIt() =
        runTest(dispatcher) {
            val url = io.legado.app.data.repository.DEFAULT_TXT_TOC_RULE_URL
            val repo = Fake().apply { historyRead = { listOf(url, "https://other") } }
            val vm = model(repo)
            runCurrent()
            vm.showOnline(true)
            runCurrent()
            assertEquals(listOf(url, "https://other"), vm.state.value.history)
            vm.deleteHistory(url)
            runCurrent()
            assertEquals(listOf("https://other"), vm.state.value.history)
            vm.showOnline(false)
            vm.showOnline(true)
            runCurrent()
            assertEquals(listOf(url, "https://other"), vm.state.value.history)
        }

    private class Fake : TxtTocRuleManagementRepository {
        val rows =
            MutableStateFlow(
                listOf(
                    TxtTocRuleSnapshot(
                        1,
                        "alpha",
                        "alpha",
                        example = "Chapter A",
                        serialNumber = 42,
                    ),
                    TxtTocRuleSnapshot(2, "beta", "beta", "$1", "Chapter B", 42, false),
                    TxtTocRuleSnapshot(3, "gamma", "gamma", serialNumber = 42),
                )
            )
        val enabled = mutableListOf<Pair<List<Long>, Boolean>>()
        val deleted = mutableListOf<List<Long>>()
        val edges = mutableListOf<Pair<List<Long>, Boolean>>()
        val orders = mutableListOf<List<Long>>()
        val serialized = mutableListOf<List<TxtTocRuleSnapshot>>()
        var historyRead: suspend () -> List<String> = { emptyList() }

        override fun observe() = rows

        override suspend fun setEnabled(ids: List<Long>, enabled: Boolean) {
            this.enabled += ids to enabled
        }

        override suspend fun delete(ids: List<Long>) {
            deleted += ids
        }

        override suspend fun reorder(ids: List<Long>) {
            orders += ids
        }

        override suspend fun moveToEdge(ids: List<Long>, top: Boolean) {
            edges += ids to top
        }

        override suspend fun importDefault() {}

        override suspend fun history() = historyRead()

        override suspend fun rememberUrl(url: String) {}

        override suspend fun removeUrl(url: String) {}

        override suspend fun json(rules: List<TxtTocRuleSnapshot>): String {
            serialized += rules
            return "json"
        }

        override suspend fun shareFile(rules: List<TxtTocRuleSnapshot>): String {
            serialized += rules
            return "/cache/share.json"
        }

        override suspend fun exportSummary(url: String) = "summary"

        override suspend fun passphrase(url: String) = "phrase:$url"
    }
}
