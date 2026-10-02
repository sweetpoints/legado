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
class ImportThemeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun savedCopy(value: SavedStateHandle) = SavedStateHandle(value.keys().associateWith { value.get<Any?>(it) })
    @Test fun newAndChangedThemesAreSelectedWhileIdenticalThemeIsOptIn() = runTest(dispatcher) {
        val repo = Fake(); val model = ImportThemeViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        assertFalse(model.state.value.loading); assertEquals(setOf("new", "other"), model.state.value.selected)
        assertEquals(2, model.state.value.selectCount); assertFalse(model.state.value.isSelectAll)
        model.toggle("existing"); assertTrue(model.state.value.isSelectAll)
        model.toggleAll(); assertEquals(0, model.state.value.selectCount)
        model.toggleAll(); assertEquals(3, model.state.value.selectCount)
        model.toggle("missing"); assertEquals(3, model.state.value.selectCount)
    }
    @Test fun selectionAndEditedPayloadRestoreWithoutAppendingOrReParsing() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportThemeViewModel(repo, saved, "input"); runCurrent()
        model.toggle("new"); model.edit("replacement with renamed rule", "other"); runCurrent()
        val restored = ImportThemeViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertEquals(1, repo.reads); assertEquals(3, restored.state.value.items.size)
        assertEquals(setOf("other"), restored.state.value.selected)
        assertEquals("replacement with renamed rule", restored.state.value.items[1].json)
        assertEquals("other", restored.state.value.items[1].key)
        assertTrue(repo.inserts.isEmpty())
    }
    @Test fun stableCodeKeyRestoresPendingAndConsumedHostRequestNeverReappears() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportThemeViewModel(repo, saved, "input"); runCurrent()
        model.openCode("existing")
        val restored = ImportThemeViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertEquals("existing", restored.state.value.code?.key)
        model.consumeCode("existing")
        val consumed = ImportThemeViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertNull(consumed.state.value.code)
    }
    @Test fun confirmationWritesOnlyCurrentSelectionExactlyOnceAndRestoredFinishedDoesNotImport() = runTest(dispatcher) {
        val repo = Fake().apply { insertGate = CompletableDeferred() }; val saved = SavedStateHandle()
        val model = ImportThemeViewModel(repo, saved, "input"); runCurrent()
        model.toggle("new"); model.toggle("existing"); model.confirm(); model.confirm(); model.cancel(); runCurrent()
        assertTrue(model.state.value.busy); assertFalse(model.state.value.finished)
        assertEquals(listOf(listOf("other", "existing")), repo.inserts)
        repo.insertGate!!.complete(Unit); runCurrent(); assertTrue(model.state.value.finished)
        val restored = ImportThemeViewModel(repo, savedCopy(saved), "input"); runCurrent(); restored.confirm()
        assertTrue(restored.state.value.finished); assertEquals(1, repo.inserts.size)
    }
    @Test fun cacheCommitMarkerClosesEvenIfProcessStateWasSavedBeforeCompletion() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportThemeViewModel(repo, saved, "input"); runCurrent()
        val stale = savedCopy(saved); model.confirm(); runCurrent()
        val restored = ImportThemeViewModel(repo, stale, "input"); runCurrent()
        assertTrue(restored.state.value.finished); assertEquals(1, repo.inserts.size)
    }
    @Test fun failedInsertRetainsSelectionAndDraftForRetry() = runTest(dispatcher) {
        val repo = Fake().apply { insertFails = true }; val model = ImportThemeViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        model.confirm(); runCurrent(); assertFalse(model.state.value.finished); assertFalse(model.state.value.busy)
        assertTrue(model.state.value.error!!.contains("disk")); assertEquals(setOf("new", "other"), model.state.value.selected)
        repo.insertFails = false; model.confirm(); runCurrent(); assertTrue(model.state.value.finished)
    }
    @Test fun cancelledReadCannotPublishLateItemsOrPersistToRoom() = runTest(dispatcher) {
        val repo = Fake().apply { readGate = CompletableDeferred() }; val saved = SavedStateHandle()
        val model = ImportThemeViewModel(repo, saved, "input"); runCurrent(); model.cancel()
        repo.readGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.finished); assertTrue(model.state.value.items.isEmpty()); assertTrue(repo.inserts.isEmpty())
        val restored = ImportThemeViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertTrue(restored.state.value.finished); assertEquals(1, repo.reads)
    }
    @Test fun failedReadBlocksConfirmAndRetryAndEmptyInputDoNotWrite() = runTest(dispatcher) {
        val repo = Fake().apply { readFails = true }; val model = ImportThemeViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        model.confirm(); runCurrent(); assertTrue(repo.inserts.isEmpty()); assertNotNull(model.state.value.error)
        repo.readFails = false; model.load(); runCurrent(); assertEquals(3, model.state.value.items.size)
        val empty = ImportThemeViewModel(repo, SavedStateHandle(), ""); runCurrent(); assertTrue(empty.state.value.finished)
    }
    @Test fun malformedCodePreservesPayloadAndUserSelection() = runTest(dispatcher) {
        val repo = Fake().apply { editFails = true }; val model = ImportThemeViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        model.toggleAll(); model.edit("invalid", "existing"); runCurrent()
        assertEquals("old", model.state.value.items.last().json); assertTrue(model.state.value.isSelectAll)
        assertNotNull(model.state.value.error); assertFalse(model.state.value.busy)
    }
    @Test fun codeRenameChangesLocalStatusWithoutResettingExplicitSelection() = runTest(dispatcher) {
        val repo = Fake(); val model = ImportThemeViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        model.edit("existing-name", "new"); runCurrent()
        assertEquals(ThemeImportStatus.Existing, model.state.value.items.first().status)
        assertTrue("new" in model.state.value.selected)
        model.edit("new-name", "existing"); runCurrent()
        assertEquals(ThemeImportStatus.New, model.state.value.items.last().status)
        assertFalse("existing" in model.state.value.selected)
        model.confirm(); runCurrent(); assertEquals(listOf(listOf("new", "other")), repo.inserts)
    }
    @Test fun localizedStatusesAndDefaultsDistinguishNewChangedAndIdenticalThemes() {
        assertEquals(R.string.import_status_new, themeImportStatus(item("new", ThemeImportStatus.New)))
        assertEquals(R.string.import_status_update, themeImportStatus(item("changed", ThemeImportStatus.Update)))
        assertEquals(R.string.import_status_exist, themeImportStatus(item("same", ThemeImportStatus.Existing)))
        assertTrue(item("changed", ThemeImportStatus.Update).selectedByDefault)
        assertFalse(item("same", ThemeImportStatus.Existing).selectedByDefault)
    }
    private fun item(key: String, status: ThemeImportStatus) = ThemeImportItem(key, key, "old", status)
    private inner class Fake : ThemeImportRepository {
        var cache: ThemeImportSession? = null
        var reads = 0; var readFails = false; var editFails = false; var insertFails = false
        var readGate: CompletableDeferred<Unit>? = null; var insertGate: CompletableDeferred<Unit>? = null
        val inserts = mutableListOf<List<String>>()
        override suspend fun read(source: String): List<ThemeImportItem> {
            reads++; readGate?.await(); if (readFails) error("format")
            return listOf(item("new", ThemeImportStatus.New), item("other", ThemeImportStatus.Update), item("existing", ThemeImportStatus.Existing))
        }
        override suspend fun restore(session: String) = cache
        override suspend fun stage(session: String, items: List<ThemeImportItem>) { cache = ThemeImportSession(items) }
        override suspend fun edit(key: String, code: String): ThemeImportItem {
            if (editFails) error("invalid json")
            return item(key, if (code == "existing-name") ThemeImportStatus.Existing else ThemeImportStatus.New).copy(json = code, name = "edited")
        }
        override suspend fun insert(session: String, items: List<ThemeImportItem>, selected: Set<String>) {
            inserts += items.filter { it.key in selected }.map { it.key }; insertGate?.await()
            if (insertFails) error("disk")
            cache = ThemeImportSession(items, true)
        }
    }
}
