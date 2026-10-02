package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import io.legado.app.R
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
class ImportHttpTtsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun savedCopy(value: SavedStateHandle) = SavedStateHandle(value.keys().associateWith { value.get<Any?>(it) })
    @Test fun newAndNewerAreSelectedWhileExistingIsOptIn() = runTest(dispatcher) {
        val repo = Fake(); val model = ImportHttpTtsViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        assertFalse(model.state.value.loading); assertEquals(setOf("new", "update"), model.state.value.selected)
        assertEquals(2, model.state.value.selectCount); assertFalse(model.state.value.isSelectAll)
        model.toggle("existing"); assertTrue(model.state.value.isSelectAll)
        model.toggleAll(); assertEquals(0, model.state.value.selectCount)
        model.toggleAll(); assertEquals(3, model.state.value.selectCount)
        model.toggle("missing"); assertEquals(3, model.state.value.selectCount)
    }
    @Test fun selectionAndEditedPayloadRestoreWithoutAppendingOrReParsing() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportHttpTtsViewModel(repo, saved, "input"); runCurrent()
        model.toggle("new"); model.edit("replacement with different entity id", "update"); runCurrent()
        val restored = ImportHttpTtsViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertEquals(1, repo.reads); assertEquals(3, restored.state.value.items.size)
        assertEquals(setOf("update"), restored.state.value.selected)
        assertEquals("replacement with different entity id", restored.state.value.items[1].json)
        assertEquals("update", restored.state.value.items[1].key)
        assertTrue(repo.inserts.isEmpty())
    }
    @Test fun stableCodeKeyRestoresPendingAndConsumedHostRequestNeverReappears() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportHttpTtsViewModel(repo, saved, "input"); runCurrent()
        model.openCode("existing")
        val restored = ImportHttpTtsViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertEquals("existing", restored.state.value.code?.key)
        model.consumeCode("existing")
        val consumed = ImportHttpTtsViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertNull(consumed.state.value.code)
    }
    @Test fun confirmationWritesOnlyCurrentSelectionExactlyOnceAndRestoredFinishedDoesNotImport() = runTest(dispatcher) {
        val repo = Fake().apply { insertGate = CompletableDeferred() }; val saved = SavedStateHandle()
        val model = ImportHttpTtsViewModel(repo, saved, "input"); runCurrent()
        model.toggle("new"); model.toggle("existing"); model.confirm(); model.confirm(); model.cancel(); runCurrent()
        assertTrue(model.state.value.busy); assertFalse(model.state.value.finished)
        assertEquals(listOf(listOf("update", "existing")), repo.inserts)
        repo.insertGate!!.complete(Unit); runCurrent(); assertTrue(model.state.value.finished)
        val restored = ImportHttpTtsViewModel(repo, savedCopy(saved), "input"); runCurrent(); restored.confirm()
        assertTrue(restored.state.value.finished); assertEquals(1, repo.inserts.size)
    }
    @Test fun cacheCommitMarkerClosesEvenIfProcessStateWasSavedBeforeCompletion() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportHttpTtsViewModel(repo, saved, "input"); runCurrent()
        val stale = savedCopy(saved); model.confirm(); runCurrent()
        val restored = ImportHttpTtsViewModel(repo, stale, "input"); runCurrent()
        assertTrue(restored.state.value.finished); assertEquals(1, repo.inserts.size)
    }
    @Test fun failedInsertRetainsSelectionAndDraftForRetry() = runTest(dispatcher) {
        val repo = Fake().apply { insertFails = true }; val model = ImportHttpTtsViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        model.confirm(); runCurrent(); assertFalse(model.state.value.finished); assertFalse(model.state.value.busy)
        assertTrue(model.state.value.error!!.contains("disk")); assertEquals(setOf("new", "update"), model.state.value.selected)
        repo.insertFails = false; model.confirm(); runCurrent(); assertTrue(model.state.value.finished)
    }
    @Test fun cancelledReadCannotPublishLateItemsOrPersistToRoom() = runTest(dispatcher) {
        val repo = Fake().apply { readGate = CompletableDeferred() }; val saved = SavedStateHandle()
        val model = ImportHttpTtsViewModel(repo, saved, "input"); runCurrent(); model.cancel()
        repo.readGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.finished); assertTrue(model.state.value.items.isEmpty()); assertTrue(repo.inserts.isEmpty())
        val restored = ImportHttpTtsViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertTrue(restored.state.value.finished); assertEquals(1, repo.reads)
    }
    @Test fun failedReadBlocksConfirmAndRetryAndEmptyInputDoNotWrite() = runTest(dispatcher) {
        val repo = Fake().apply { readFails = true }; val model = ImportHttpTtsViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        model.confirm(); runCurrent(); assertTrue(repo.inserts.isEmpty()); assertNotNull(model.state.value.error)
        repo.readFails = false; model.load(); runCurrent(); assertEquals(3, model.state.value.items.size)
        val empty = ImportHttpTtsViewModel(repo, SavedStateHandle(), ""); runCurrent(); assertTrue(empty.state.value.finished)
    }
    @Test fun malformedCodePreservesPayloadAndUserSelection() = runTest(dispatcher) {
        val repo = Fake().apply { editFails = true }; val model = ImportHttpTtsViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        model.toggle("existing"); model.edit("invalid", "existing"); runCurrent()
        assertEquals("old", model.state.value.items.last().json); assertTrue(model.state.value.isSelectAll)
        assertNotNull(model.state.value.error); assertFalse(model.state.value.busy)
    }
    @Test fun statusMappingCoversEqualAndOlderExistingAndLocalizedResources() {
        assertEquals(R.string.import_status_new, httpTtsImportStatus(item("new", 4, null)))
        assertEquals(R.string.import_status_update, httpTtsImportStatus(item("update", 4, 3)))
        assertEquals(R.string.import_status_exist, httpTtsImportStatus(item("same", 4, 4)))
        assertEquals(R.string.import_status_exist, httpTtsImportStatus(item("older", 3, 4)))
    }
    private fun item(key: String, updated: Long, local: Long?) = HttpTtsImportItem(key, key, "old", updated, local)
    private inner class Fake : HttpTtsImportRepository {
        var cache: HttpTtsImportSession? = null
        var reads = 0; var readFails = false; var editFails = false; var insertFails = false
        var readGate: CompletableDeferred<Unit>? = null; var insertGate: CompletableDeferred<Unit>? = null
        val inserts = mutableListOf<List<String>>()
        override suspend fun read(source: String): List<HttpTtsImportItem> {
            reads++; readGate?.await(); if (readFails) error("format")
            return listOf(item("new", 4, null), item("update", 4, 3), item("existing", 3, 3))
        }
        override suspend fun restore(session: String) = cache
        override suspend fun stage(session: String, items: List<HttpTtsImportItem>) { cache = HttpTtsImportSession(items) }
        override suspend fun edit(key: String, code: String): HttpTtsImportItem {
            if (editFails) error("invalid json")
            return item(key, 4, null).copy(json = code, name = "edited")
        }
        override suspend fun insert(session: String, items: List<HttpTtsImportItem>, selected: Set<String>) {
            inserts += items.filter { it.key in selected }.map { it.key }; insertGate?.await()
            if (insertFails) error("disk")
            cache = HttpTtsImportSession(items, true)
        }
    }
}
