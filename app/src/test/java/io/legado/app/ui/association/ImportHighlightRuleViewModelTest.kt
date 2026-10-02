package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ImportHighlightRuleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun newAndUpdatedAreSelectedExistingIsOptInAndEmptySelectionNeverWrites() = runTest(dispatcher) {
        val repo = Fake(); val model = ImportHighlightRuleViewModel(repo, SavedStateHandle(), "uri"); runCurrent()
        assertEquals(setOf("new", "update"), model.state.value.selected)
        model.toggle("existing"); assertTrue(model.state.value.isSelectAll)
        model.toggleAll(); assertEquals(0, model.state.value.selectCount)
        model.confirm(); runCurrent(); assertTrue(repo.inserts.isEmpty())
        model.toggleAll(); assertEquals(3, model.state.value.selectCount)
        model.toggle("missing"); assertEquals(3, model.state.value.selectCount)
    }
    @Test fun selectionRestoresFromCacheWithoutUriReadOrWritingRoom() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val first = ImportHighlightRuleViewModel(repo, saved, "uri"); runCurrent()
        first.toggle("new"); first.toggle("existing")
        val second = ImportHighlightRuleViewModel(repo, copy(saved), "uri"); runCurrent()
        assertEquals(setOf("update", "existing"), second.state.value.selected)
        assertEquals(1, repo.reads); assertTrue(repo.inserts.isEmpty())
        assertEquals("full metadata", second.state.value.items.first().json)
    }
    @Test fun confirmIsSingleFlightAndCancelIsBlockedUntilSelectedPayloadCommits() = runTest(dispatcher) {
        val repo = Fake().apply { insertGate = CompletableDeferred() }; val saved = SavedStateHandle()
        val model = ImportHighlightRuleViewModel(repo, saved, "uri"); runCurrent(); model.toggle("new")
        model.confirm(); model.confirm(); model.cancel(); model.toggleAll(); runCurrent()
        assertEquals(listOf(setOf("update")), repo.inserts); assertTrue(model.state.value.busy); assertFalse(model.state.value.finished)
        repo.insertGate!!.complete(Unit); runCurrent(); assertTrue(model.state.value.finished); assertTrue(model.state.value.refreshPending)
        model.consumeRefresh(); assertFalse(model.state.value.refreshPending)
        val restored = ImportHighlightRuleViewModel(repo, copy(saved), "uri"); runCurrent()
        assertTrue(restored.state.value.finished); assertFalse(restored.state.value.refreshPending); assertEquals(1, repo.inserts.size)
    }
    @Test fun committedMarkerRestoresPendingReaderRefreshButCannotImportAgain() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportHighlightRuleViewModel(repo, saved, "uri"); runCurrent()
        val stale = copy(saved); model.confirm(); runCurrent()
        val restored = ImportHighlightRuleViewModel(repo, stale, "uri"); runCurrent(); restored.confirm()
        assertTrue(restored.state.value.finished); assertTrue(restored.state.value.refreshPending); assertEquals(1, repo.inserts.size)
    }
    @Test fun readFailureBlocksConfirmAndRetryReloadsFullDraft() = runTest(dispatcher) {
        val repo = Fake().apply { readFails = true }; val model = ImportHighlightRuleViewModel(repo, SavedStateHandle(), "uri"); runCurrent()
        model.confirm(); runCurrent(); assertTrue(repo.inserts.isEmpty()); assertNotNull(model.state.value.error)
        repo.readFails = false; model.load(); runCurrent(); assertEquals(3, model.state.value.items.size); assertNull(model.state.value.error)
    }
    @Test fun insertFailureKeepsSelectionForExplicitRetryWithoutReaderRefresh() = runTest(dispatcher) {
        val repo = Fake().apply { insertFails = true }; val model = ImportHighlightRuleViewModel(repo, SavedStateHandle(), "uri"); runCurrent()
        model.toggle("new"); model.confirm(); runCurrent()
        assertFalse(model.state.value.finished); assertFalse(model.state.value.refreshPending); assertFalse(model.state.value.busy)
        assertEquals(setOf("update"), model.state.value.selected); assertTrue(model.state.value.error!!.contains("disk"))
        repo.insertFails = false; model.confirm(); runCurrent(); assertTrue(model.state.value.refreshPending)
        assertEquals(listOf(setOf("update"), setOf("update")), repo.inserts)
    }
    @Test fun cancellingReadPreventsLateListAndNeverQueuesReaderRefresh() = runTest(dispatcher) {
        val repo = Fake().apply { readGate = CompletableDeferred() }; val model = ImportHighlightRuleViewModel(repo, SavedStateHandle(), "uri"); runCurrent()
        model.cancel(); repo.readGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.finished); assertTrue(model.state.value.items.isEmpty()); assertFalse(model.state.value.refreshPending)
        assertTrue(repo.inserts.isEmpty())
    }
    @Test fun emptyInputClosesWithoutReadingOrWriting() = runTest(dispatcher) {
        val repo = Fake(); val model = ImportHighlightRuleViewModel(repo, SavedStateHandle(), ""); runCurrent()
        assertTrue(model.state.value.finished); assertEquals(0, repo.reads); assertTrue(repo.inserts.isEmpty())
    }
    private class Fake : HighlightImportRepository {
        var cache: HighlightImportSession? = null; var reads = 0; var readFails = false; var insertFails = false
        var readGate: CompletableDeferred<Unit>? = null; var insertGate: CompletableDeferred<Unit>? = null
        val inserts = mutableListOf<Set<String>>()
        override suspend fun read(source: String): List<HighlightImportItem> {
            reads++; readGate?.await(); if (readFails) error("format")
            return listOf(HighlightImportItem("new", "New", "full metadata", HighlightImportStatus.NEW),
                HighlightImportItem("update", "Update", "{}", HighlightImportStatus.UPDATE),
                HighlightImportItem("existing", "Existing", "{}", HighlightImportStatus.EXISTING))
        }
        override suspend fun restore(session: String) = cache
        override suspend fun stage(session: String, items: List<HighlightImportItem>) { cache = HighlightImportSession(items) }
        override suspend fun insert(session: String, items: List<HighlightImportItem>, selected: Set<String>) {
            inserts += selected.toSet(); insertGate?.await(); if (insertFails) error("disk")
            cache = HighlightImportSession(items, true)
        }
    }
}
