package io.legado.app.ui.book.import.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun model(repo: Repository, handle: SavedStateHandle = SavedStateHandle()) = ServersViewModel(repo, handle).also { stores += ViewModelStore().apply { put("servers", it) } }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { stores.forEach(ViewModelStore::clear); runCurrent() } }
    @Test fun selectionIsDraftUntilApplyAndCancelDoesNotWritePreferences() = test {
        val repo = Repository(); val model = model(repo); model.start(); runCurrent()
        model.choose(2); assertEquals(2L, model.state.value.selected); assertTrue(repo.writes.isEmpty())
        model.close(); model.applySelection(); assertTrue(repo.writes.isEmpty())
        val next = model(repo); next.start(); runCurrent(); next.choose(2); next.applySelection(); next.applySelection()
        assertEquals(listOf(2L), repo.writes); assertTrue(next.state.value.finished)
    }
    @Test fun defaultSelectionCommitsDefaultIdOnce() = test {
        val repo = Repository(); val model = model(repo); model.useDefault(); model.useDefault()
        assertEquals(listOf(-1L), repo.writes); assertTrue(model.state.value.finished)
    }
    @Test fun selectionAndDeleteConfirmationRestoreWithoutExternalEffects() = test {
        val repo = Repository(); val handle = SavedStateHandle(); val model = model(repo, handle); model.start(); runCurrent()
        model.choose(2); model.requestDelete(1)
        val restored = model(repo, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })); restored.start(); runCurrent()
        assertEquals(2L, restored.state.value.selected); assertEquals(1L, restored.state.value.deleteId)
        assertTrue(repo.writes.isEmpty()); assertTrue(repo.deleted.isEmpty())
    }
    @Test fun stopAndResumeCollectsMissedServerChanges() = test {
        val repo = Repository(); val model = model(repo); model.start(); model.start(); runCurrent(); model.stop(); runCurrent()
        repo.rows.value = listOf(RemoteServerChoice(3, "New")); runCurrent(); assertEquals(2, model.state.value.rows.size)
        model.start(); runCurrent(); assertEquals(listOf(RemoteServerChoice(3, "New")), model.state.value.rows)
    }
    @Test fun failedObservationCanRetry() = test {
        val repo = Repository().apply { loadFailure = true }; val model = model(repo); model.start(); runCurrent()
        assertFalse(model.state.value.loading); assertNotNull(model.state.value.error)
        repo.loadFailure = false; model.retry(); runCurrent(); assertNull(model.state.value.error); assertEquals(2, model.state.value.rows.size)
    }
    @Test fun cancelledDeleteAndFailedDeleteNeverRemoveServerAndRetryWorks() = test {
        val repo = Repository().apply { deleteFailure = true }; val model = model(repo); model.start(); runCurrent()
        model.requestDelete(1); model.requestDelete(null); model.delete(); runCurrent(); assertTrue(repo.deleted.isEmpty())
        model.requestDelete(1); model.delete(); runCurrent(); assertNotNull(model.state.value.error); assertFalse(model.state.value.deleting)
        repo.deleteFailure = false; model.requestDelete(1); model.delete(); runCurrent()
        assertEquals(listOf(1L), repo.deleted); assertEquals(listOf(RemoteServerChoice(2, "Two")), model.state.value.rows)
    }
    @Test fun duplicateDeleteIsIgnoredWhilePending() = test {
        val repo = Repository().apply { gate = CompletableDeferred() }; val model = model(repo); model.start(); runCurrent()
        model.requestDelete(1); model.delete(); model.requestDelete(2); model.delete(); runCurrent()
        assertTrue(model.state.value.deleting); repo.gate!!.complete(Unit); runCurrent(); assertEquals(listOf(1L), repo.deleted)
    }
    @Test fun restoredFinishedStateDoesNotObserveOrReplayPreferenceCommit() = test {
        val repo = Repository(); val handle = SavedStateHandle(); val model = model(repo, handle); model.useDefault()
        val restored = model(repo, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })); restored.start(); restored.useDefault(); runCurrent()
        assertTrue(restored.state.value.finished); assertEquals(0, repo.observations); assertEquals(1, repo.writes.size)
    }
    private class Repository : RemoteServerListRepository {
        override val selected = 1L; override val defaultId = -1L
        val rows = MutableStateFlow(listOf(RemoteServerChoice(1, "One"), RemoteServerChoice(2, "Two")))
        val writes = mutableListOf<Long>(); val deleted = mutableListOf<Long>(); var observations = 0
        var loadFailure = false; var deleteFailure = false; var gate: CompletableDeferred<Unit>? = null
        override fun observe(): Flow<List<RemoteServerChoice>> { observations++; return if (loadFailure) flow { error("load failed") } else rows }
        override fun select(id: Long) { writes += id }
        override suspend fun delete(id: Long) { gate?.await(); if (deleteFailure) error("delete failed"); deleted += id; rows.value = rows.value.filterNot { it.id == id } }
    }
}
