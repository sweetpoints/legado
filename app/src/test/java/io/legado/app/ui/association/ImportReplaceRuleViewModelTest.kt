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
class ImportReplaceRuleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun savedCopy(value: SavedStateHandle) = SavedStateHandle(value.keys().associateWith { value.get<Any?>(it) })
    @Test fun onlyMissingNamesAreSelectedAndExistingNamesAreOptIn() = runTest(dispatcher) {
        val repo = Fake(); val model = ImportReplaceRuleViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        assertFalse(model.state.value.loading); assertEquals(setOf("new"), model.state.value.selected)
        assertEquals(1, model.state.value.selectCount); assertFalse(model.state.value.isSelectAll)
        model.toggle("existing"); model.toggle("other"); assertTrue(model.state.value.isSelectAll)
        model.toggleAll(); assertEquals(0, model.state.value.selectCount)
        model.toggleAll(); assertEquals(3, model.state.value.selectCount)
        model.toggle("missing"); assertEquals(3, model.state.value.selectCount)
    }
    @Test fun selectionAndEditedPayloadRestoreWithoutAppendingOrReParsing() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportReplaceRuleViewModel(repo, saved, "input"); runCurrent()
        model.toggle("new"); model.toggle("other"); model.edit("replacement with renamed rule", "other"); runCurrent()
        val restored = ImportReplaceRuleViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertEquals(1, repo.reads); assertEquals(3, restored.state.value.items.size)
        assertEquals(setOf("other"), restored.state.value.selected)
        assertEquals("replacement with renamed rule", restored.state.value.items[1].json)
        assertEquals("other", restored.state.value.items[1].key)
        assertTrue(repo.inserts.isEmpty())
    }
    @Test fun stableCodeKeyRestoresPendingAndConsumedHostRequestNeverReappears() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportReplaceRuleViewModel(repo, saved, "input"); runCurrent()
        model.openCode("existing")
        val restored = ImportReplaceRuleViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertEquals("existing", restored.state.value.code?.key)
        model.consumeCode("existing")
        val consumed = ImportReplaceRuleViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertNull(consumed.state.value.code)
    }
    @Test fun confirmationWritesOnlyCurrentSelectionExactlyOnceAndRestoredFinishedDoesNotImport() = runTest(dispatcher) {
        val repo = Fake().apply { insertGate = CompletableDeferred() }; val saved = SavedStateHandle()
        val model = ImportReplaceRuleViewModel(repo, saved, "input"); runCurrent()
        model.toggle("new"); model.toggle("other"); model.toggle("existing"); model.confirm(); model.confirm(); model.cancel(); runCurrent()
        assertTrue(model.state.value.busy); assertFalse(model.state.value.finished)
        assertEquals(listOf(listOf("other", "existing")), repo.inserts)
        repo.insertGate!!.complete(Unit); runCurrent(); assertTrue(model.state.value.finished)
        val restored = ImportReplaceRuleViewModel(repo, savedCopy(saved), "input"); runCurrent(); restored.confirm()
        assertTrue(restored.state.value.finished); assertEquals(1, repo.inserts.size)
    }
    @Test fun cacheCommitMarkerClosesEvenIfProcessStateWasSavedBeforeCompletion() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportReplaceRuleViewModel(repo, saved, "input"); runCurrent()
        val stale = savedCopy(saved); model.confirm(); runCurrent()
        val restored = ImportReplaceRuleViewModel(repo, stale, "input"); runCurrent()
        assertTrue(restored.state.value.finished); assertEquals(1, repo.inserts.size)
    }
    @Test fun failedInsertRetainsSelectionAndDraftForRetry() = runTest(dispatcher) {
        val repo = Fake().apply { insertFails = true }; val model = ImportReplaceRuleViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        model.confirm(); runCurrent(); assertFalse(model.state.value.finished); assertFalse(model.state.value.busy)
        assertTrue(model.state.value.error!!.contains("disk")); assertEquals(setOf("new"), model.state.value.selected)
        repo.insertFails = false; model.confirm(); runCurrent(); assertTrue(model.state.value.finished)
    }
    @Test fun cancelledReadCannotPublishLateItemsOrPersistToRoom() = runTest(dispatcher) {
        val repo = Fake().apply { readGate = CompletableDeferred() }; val saved = SavedStateHandle()
        val model = ImportReplaceRuleViewModel(repo, saved, "input"); runCurrent(); model.cancel()
        repo.readGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.finished); assertTrue(model.state.value.items.isEmpty()); assertTrue(repo.inserts.isEmpty())
        val restored = ImportReplaceRuleViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertTrue(restored.state.value.finished); assertEquals(1, repo.reads)
    }
    @Test fun failedReadBlocksConfirmAndRetryAndEmptyInputDoNotWrite() = runTest(dispatcher) {
        val repo = Fake().apply { readFails = true }; val model = ImportReplaceRuleViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        model.confirm(); runCurrent(); assertTrue(repo.inserts.isEmpty()); assertNotNull(model.state.value.error)
        repo.readFails = false; model.load(); runCurrent(); assertEquals(3, model.state.value.items.size)
        val empty = ImportReplaceRuleViewModel(repo, SavedStateHandle(), ""); runCurrent(); assertTrue(empty.state.value.finished)
    }
    @Test fun malformedCodePreservesPayloadAndUserSelection() = runTest(dispatcher) {
        val repo = Fake().apply { editFails = true }; val model = ImportReplaceRuleViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        model.toggleAll(); model.edit("invalid", "existing"); runCurrent()
        assertEquals("old", model.state.value.items.last().json); assertTrue(model.state.value.isSelectAll)
        assertNotNull(model.state.value.error); assertFalse(model.state.value.busy)
    }
    @Test fun codeRenameChangesLocalStatusWithoutResettingExplicitSelection() = runTest(dispatcher) {
        val repo = Fake(); val model = ImportReplaceRuleViewModel(repo, SavedStateHandle(), "input"); runCurrent()
        model.edit("existing-name", "new"); runCurrent()
        assertEquals(ReplaceRuleImportStatus.Existing, model.state.value.items.first().status)
        assertTrue("new" in model.state.value.selected)
        model.edit("new-name", "existing"); runCurrent()
        assertEquals(ReplaceRuleImportStatus.New, model.state.value.items.last().status)
        assertFalse("existing" in model.state.value.selected)
        model.confirm(); runCurrent(); assertEquals(listOf(listOf("new")), repo.inserts)
    }
    @Test fun changedExistingRulesShowUpdateButRemainOptIn() {
        val changed = ReplaceRuleImportItem("changed", "changed", "json", ReplaceRuleImportStatus.Update)
        assertEquals(R.string.import_status_update, replaceRuleImportStatus(changed)); assertFalse(changed.selectedByDefault)
        assertEquals(R.string.import_status_new, replaceRuleImportStatus(item("new", false)))
        assertEquals(R.string.import_status_exist, replaceRuleImportStatus(item("same", true)))
    }
    @Test fun unconfirmedGroupDraftRestoresButCancelDoesNotApplyOrInsert() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportReplaceRuleViewModel(repo, saved, "input"); runCurrent()
        model.openGroup(); model.groupDraft(" Draft "); model.addGroupDraft(true); runCurrent()
        val restored = ImportReplaceRuleViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertTrue(restored.state.value.groupOpen); assertEquals(" Draft ", restored.state.value.groupDraft)
        assertTrue(restored.state.value.addGroupDraft); assertEquals(listOf("A", "B"), restored.state.value.groups)
        restored.confirm(); runCurrent(); assertTrue(repo.inserts.isEmpty())
        restored.closeGroup(); assertEquals("", restored.state.value.group)
        restored.cancel(); assertTrue(repo.inserts.isEmpty())
    }
    @Test fun confirmedGroupAndModeRestoreAndAreAppliedOnlyOnImport() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = ImportReplaceRuleViewModel(repo, saved, "input"); runCurrent()
        model.openGroup(); model.groupDraft(" Added "); model.addGroupDraft(true); model.acceptGroup(); runCurrent()
        val restored = ImportReplaceRuleViewModel(repo, savedCopy(saved), "input"); runCurrent()
        assertEquals(" Added ", restored.state.value.group); assertTrue(restored.state.value.addGroup)
        assertFalse(restored.state.value.groupOpen); assertTrue(repo.inserts.isEmpty())
        restored.confirm(); runCurrent(); assertEquals(" Added " to true, repo.importedGroup)
    }
    @Test fun groupMergePreservesOriginalForBlankAndDeduplicatesExistingSeparators() {
        assertEquals("A;B", replaceImportGroup("A;B", "  ", true))
        assertEquals("C", replaceImportGroup("A;B", " C ", false))
        assertEquals("A,B", replaceImportGroup(" A;B，A； ", " B ", true))
        assertEquals("A,B,C", replaceImportGroup("A;B", " C ", true))
        assertEquals("C", replaceImportGroup(null, " C ", true))
    }
    private fun item(key: String, exists: Boolean) = ReplaceRuleImportItem(key, key, "old",
        if (exists) ReplaceRuleImportStatus.Existing else ReplaceRuleImportStatus.New)
    private inner class Fake : ReplaceRuleImportRepository {
        var cache: ReplaceRuleImportSession? = null
        var reads = 0; var readFails = false; var editFails = false; var insertFails = false
        var readGate: CompletableDeferred<Unit>? = null; var insertGate: CompletableDeferred<Unit>? = null
        val inserts = mutableListOf<List<String>>()
        var importedGroup: Pair<String, Boolean>? = null
        override suspend fun groups() = listOf("A", "B")
        override suspend fun read(source: String): List<ReplaceRuleImportItem> {
            reads++; readGate?.await(); if (readFails) error("format")
            return listOf(item("new", false), item("other", true), item("existing", true))
        }
        override suspend fun restore(session: String) = cache
        override suspend fun stage(session: String, items: List<ReplaceRuleImportItem>) { cache = ReplaceRuleImportSession(items) }
        override suspend fun edit(key: String, code: String): ReplaceRuleImportItem {
            if (editFails) error("invalid json")
            return item(key, code == "existing-name").copy(json = code, name = "edited")
        }
        override suspend fun insert(session: String, items: List<ReplaceRuleImportItem>, selected: Set<String>, group: String, add: Boolean) {
            importedGroup = group to add
            inserts += items.filter { it.key in selected }.map { it.key }; insertGate?.await()
            if (insertFails) error("disk")
            cache = ReplaceRuleImportSession(items, true)
        }
    }
}
