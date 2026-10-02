package io.legado.app.ui.book.group

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.BookGroupEditorRepository
import io.legado.app.data.repository.BookGroupEditorSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookGroupEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }
    private fun model(repo: Fake, initial: BookGroupEditorSnapshot? = null, saved: SavedStateHandle = SavedStateHandle()) = BookGroupEditorViewModel(repo, saved, initial)
    private fun snapshot(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun freshLoadRetainsAllFieldsAndNormalizesUnsupportedSort() = runTest(dispatcher) {
        val current = BookGroupEditorSnapshot(4, "latest", "https://cover", 7, false, false, 99, true)
        val repo = Fake(current); val model = model(repo, current.copy(name = "stale")); runCurrent()
        assertEquals(current.copy(bookSort = -1), model.state.value.draft); assertTrue(model.state.value.canDelete)
    }
    @Test fun delayedLoadDoesNotOverwriteTypingOrCoverChoice() = runTest(dispatcher) {
        val gate = CompletableDeferred<BookGroupEditorSnapshot?>(); val repo = Fake().apply { read = { gate.await() } }
        val model = model(repo, BookGroupEditorSnapshot(4)); runCurrent(); model.name("typed"); model.sort(5); model.refresh(false)
        gate.complete(BookGroupEditorSnapshot(4, "loaded", "remote", bookSort = 0)); runCurrent()
        assertEquals("typed", model.state.value.draft.name); assertEquals(5, model.state.value.draft.bookSort); assertFalse(model.state.value.draft.enableRefresh); assertEquals("remote", model.state.value.draft.cover)
    }
    @Test fun savedDraftAndFreshLoadedDefaultsRestoreWithoutRead() = runTest(dispatcher) {
        val original = BookGroupEditorSnapshot(4, "old", "old", 7, true, false, 0, false)
        val latest = original.copy(cover = "latest", bookSort = 5, onlyUpdateRead = true)
        val repo = Fake(latest); val saved = SavedStateHandle(); val model = model(repo, original, saved); runCurrent(); model.name("draft")
        val restored = model(repo, original, snapshot(saved)); runCurrent()
        assertEquals(latest.copy(name = "draft"), restored.state.value.draft); assertEquals(1, repo.reads)
    }
    @Test fun saveWaitsForDatabaseAndKeepsCompleteFieldsAndLatestReturnedIdentity() = runTest(dispatcher) {
        val repo = Fake(); val gate = CompletableDeferred<Unit>(); repo.write = { gate.await(); it.copy(id = 8, order = 19) }
        val model = model(repo); model.name("new"); model.sort(5); model.refresh(false); model.onlyRead(true); model.coverResult("https://new"); runCurrent()
        model.save(); model.save(); runCurrent(); assertTrue(model.state.value.saving); assertFalse(model.state.value.finished)
        gate.complete(Unit); runCurrent(); assertEquals(1, repo.writes.size); assertFalse(repo.writes.single().second)
        assertEquals(BookGroupEditorSnapshot(8, "new", "https://new", 19, false, true, 5, true), model.state.value.draft); assertTrue(model.state.value.finished)
    }
    @Test fun existingSaveFailureRetainsInputAndDoesNotClose() = runTest(dispatcher) {
        val original = BookGroupEditorSnapshot(4, "old"); val repo = Fake(original).apply { write = { error("分组不存在") } }
        val model = model(repo, original); runCurrent(); model.name("draft"); model.save(); runCurrent()
        assertEquals("draft", model.state.value.draft.name); assertEquals("分组不存在", model.state.value.error); assertFalse(model.state.value.finished); assertFalse(model.state.value.saving)
    }
    @Test fun missingOriginalCannotSaveOrDeleteAndRetryLoadsIt() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo, BookGroupEditorSnapshot(4)); runCurrent(); model.name("draft"); model.save(); model.requestDelete(true); model.confirmDelete(); runCurrent()
        assertTrue(model.state.value.loadFailed); assertTrue(repo.writes.isEmpty()); assertTrue(repo.deleted.isEmpty())
        repo.read = { BookGroupEditorSnapshot(4, "loaded") }; model.load(); runCurrent(); assertFalse(model.state.value.loadFailed); assertEquals("draft", model.state.value.draft.name)
    }
    @Test fun emptyNameCannotWriteAndOnlyAllowedGroupsCanDelete() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); model.save(); runCurrent(); assertEquals("分组名称不能为空", model.state.value.error); assertTrue(repo.writes.isEmpty()); assertFalse(model.state.value.canDelete)
        val system = model(Fake(BookGroupEditorSnapshot(-1, "all")), BookGroupEditorSnapshot(-1)); runCurrent(); system.requestDelete(true); assertFalse(system.state.value.canDelete); assertFalse(system.state.value.confirmDelete)
        val legacy = model(Fake(BookGroupEditorSnapshot(Long.MIN_VALUE, "legacy")), BookGroupEditorSnapshot(Long.MIN_VALUE)); runCurrent(); assertTrue(legacy.state.value.canDelete)
    }
    @Test fun deleteNeedsConfirmationAndFailureLeavesDialogOpen() = runTest(dispatcher) {
        val row = BookGroupEditorSnapshot(4, "name"); val repo = Fake(row); val model = model(repo, row); runCurrent()
        model.confirmDelete(); runCurrent(); assertTrue(repo.deleted.isEmpty())
        model.requestDelete(true); model.requestDelete(false); model.confirmDelete(); runCurrent(); assertTrue(repo.deleted.isEmpty())
        repo.deleteFailure = true; model.requestDelete(true); model.confirmDelete(); runCurrent(); assertFalse(model.state.value.finished); assertEquals("delete failed", model.state.value.error)
        repo.deleteFailure = false; model.confirmDelete(); runCurrent(); assertTrue(model.state.value.finished); assertEquals(listOf(4L), repo.deleted)
    }
    @Test fun selectingCoverRestoresPendingRequestWithoutDuplicateLaunchAndCancellationAllowsRetry() = runTest(dispatcher) {
        val saved = SavedStateHandle(); val repo = Fake(); val model = model(repo, saved = saved)
        assertTrue(model.requestCover()); assertFalse(model.requestCover())
        val restored = model(repo, saved = snapshot(saved)); assertFalse(restored.requestCover())
        restored.coverResult(null); assertTrue(restored.requestCover()); restored.coverResult("https://image"); runCurrent(); assertEquals("https://image", restored.state.value.draft.cover)
        assertFalse(restored.requestCover()); assertTrue(restored.state.value.coverMenu)
    }
    @Test fun removedCoverCannotBeReplacedByLateImportOrLateFailure() = runTest(dispatcher) {
        val repo = Fake(); val gate = CompletableDeferred<String>(); repo.cover = { withContext(NonCancellable) { gate.await() } }
        val model = model(repo); model.coverResult("content://old"); runCurrent(); model.removeCover(); gate.complete("old"); runCurrent()
        assertNull(model.state.value.draft.cover); assertFalse(model.state.value.importingCover); assertNull(model.state.value.error)
        val failed = CompletableDeferred<String>(); repo.cover = { withContext(NonCancellable) { failed.await() } }
        model.coverResult("content://failed"); runCurrent(); model.removeCover(); failed.completeExceptionally(IllegalStateException("stale")); runCurrent(); assertNull(model.state.value.error)
    }
    @Test fun saveCannotPersistWhileImportingCoverAndFailedImportKeepsPreviousCover() = runTest(dispatcher) {
        val repo = Fake(); val gate = CompletableDeferred<String>(); repo.cover = { gate.await() }; val model = model(repo)
        model.name("draft"); model.coverResult("content://image"); runCurrent(); model.save(); runCurrent(); assertTrue(repo.writes.isEmpty())
        gate.completeExceptionally(IllegalStateException("image failed")); runCurrent(); assertNull(model.state.value.draft.cover); assertEquals("image failed", model.state.value.error)
    }
    @Test fun finishedRestoreNeverWritesAgainAndCancelNeverWrites() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved = saved); model.name("name"); model.save(); runCurrent()
        val restored = model(repo, saved = snapshot(saved)); restored.save(); restored.confirmDelete(); runCurrent(); assertTrue(restored.state.value.finished); assertEquals(1, repo.writes.size)
        val canceled = model(repo); canceled.name("discard"); canceled.close(); canceled.save(); runCurrent(); assertTrue(canceled.state.value.finished); assertEquals(1, repo.writes.size)
    }
    private class Fake(initial: BookGroupEditorSnapshot? = null) : BookGroupEditorRepository {
        var reads = 0; val writes = mutableListOf<Pair<BookGroupEditorSnapshot, Boolean>>(); val deleted = mutableListOf<Long>()
        var read: suspend () -> BookGroupEditorSnapshot? = { initial }
        var write: suspend (BookGroupEditorSnapshot) -> BookGroupEditorSnapshot = { it }
        var cover: suspend (String) -> String = { it }; var deleteFailure = false
        override suspend fun load(id: Long): BookGroupEditorSnapshot? { reads++; return read() }
        override suspend fun save(draft: BookGroupEditorSnapshot, existing: Boolean): BookGroupEditorSnapshot { val persisted = write(draft); writes += draft to existing; return persisted }
        override suspend fun delete(id: Long) { if (deleteFailure) error("delete failed"); deleted += id }
        override suspend fun importCover(uri: String) = cover(uri)
    }
}
