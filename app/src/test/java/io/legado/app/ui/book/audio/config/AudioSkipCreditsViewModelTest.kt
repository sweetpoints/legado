package io.legado.app.ui.book.audio.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSkipCreditsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun owned(model: AudioSkipCreditsViewModel) = ViewModelStore().apply { put("audio", model) }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun scopesUseLatestGlobalPreserveLocalAndOnlyCloseSavesBook() = runTest(dispatcher) {
        val repo = Fake(); val model = AudioSkipCreditsViewModel(repo, SavedStateHandle()); val owner = owned(model)
        try { runCurrent(); model.scope(false); runCurrent(); assertEquals(30, model.state.value.draft!!.bookOpen)
            model.opening(7); model.closing(9); runCurrent(); model.scope(true); runCurrent()
            assertEquals(7, model.state.value.draft!!.bookOpen); assertEquals(30, model.state.value.draft!!.opening)
            repo.globals = 90 to 100; model.scope(false); runCurrent()
            assertEquals(90, model.state.value.draft!!.bookOpen); assertEquals(100, model.state.value.draft!!.bookClose)
            assertTrue(repo.saves.isEmpty()); model.requestClose(); runCurrent(); assertTrue(model.state.value.finished)
            assertEquals(90, repo.saves.single().bookOpen)
        } finally { owner.clear() }
    }
    @Test fun restoredLocalDraftKeepsEditsAndReadsLatestAlreadySavedGlobalPreferences() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = AudioSkipCreditsViewModel(repo, saved); val owner = owned(model)
        try { runCurrent(); model.scope(false); runCurrent(); model.opening(17); runCurrent(); repo.globals = 70 to 80
            val restored = AudioSkipCreditsViewModel(repo, copy(saved)); val second = owned(restored)
            try { runCurrent(); assertFalse(restored.state.value.draft!!.useGlobal); assertEquals(17, restored.state.value.draft!!.bookOpen)
                assertEquals(70, restored.state.value.draft!!.globalOpen); assertTrue(repo.saves.isEmpty())
                assertTrue(saved.keys().all { saved.get<Any?>(it) is Boolean || saved.get<Any?>(it) is Int })
            } finally { second.clear() }
        } finally { owner.clear() }
    }
    @Test fun lateScopeReadKeepsNewSecondsAndRapidScopeRequestsKeepLastIntent() = runTest(dispatcher) {
        val repo = Fake(); val model = AudioSkipCreditsViewModel(repo, SavedStateHandle()); val owner = owned(model)
        try { runCurrent(); repo.loadGate = CompletableDeferred(); model.scope(false); runCurrent()
            assertTrue(model.state.value.scopeLoading); model.opening(110); runCurrent()
            repo.loadGate!!.complete(Unit); runCurrent(); assertEquals(110, model.state.value.draft!!.bookOpen)
            repo.loadGate = CompletableDeferred(); model.scope(true); runCurrent(); model.scope(false); runCurrent()
            repo.loadGate!!.complete(Unit); runCurrent(); assertFalse(model.state.value.draft!!.useGlobal)
        } finally { repo.loadGate?.complete(Unit); owner.clear() }
    }
    @Test fun durableCloseFinishesAfterOwnerRemovalAndDuplicateCloseDoesNotSaveTwice() = runTest(dispatcher) {
        val repo = Fake(); val model = AudioSkipCreditsViewModel(repo, SavedStateHandle()); val owner = owned(model)
        try { runCurrent(); model.opening(25); runCurrent(); repo.saveGate = CompletableDeferred()
            model.requestClose(); model.requestClose(); runCurrent(); assertTrue(model.state.value.saving)
            owner.clear(); repo.saveGate!!.complete(Unit); runCurrent(); assertEquals(1, repo.saves.size)
            assertEquals(25, repo.saves.single().globalOpen)
        } finally { repo.saveGate?.complete(Unit); owner.clear() }
    }
    @Test fun failedCloseRetainsDraftAndRetryRepeatsBookSave() = runTest(dispatcher) {
        val repo = Fake(); val model = AudioSkipCreditsViewModel(repo, SavedStateHandle()); val owner = owned(model)
        try { runCurrent(); model.scope(false); runCurrent(); model.closing(22); runCurrent(); repo.saveFails = true
            model.requestClose(); runCurrent(); assertTrue(model.state.value.closeFailed); assertFalse(model.state.value.finished)
            assertEquals(22, model.state.value.draft!!.bookClose); repo.saveFails = false; model.retry(); runCurrent()
            assertTrue(model.state.value.finished); assertEquals(22, repo.saves.single().bookClose)
        } finally { owner.clear() }
    }
    @Test fun failedLoadCanRetryAndCloseNeverWritesBlankBook() = runTest(dispatcher) {
        val repo = Fake().apply { loadFails = true }; val model = AudioSkipCreditsViewModel(repo, SavedStateHandle()); val owner = owned(model)
        try { runCurrent(); assertTrue(model.state.value.loadFailed); assertNull(model.state.value.draft)
            model.requestClose(); runCurrent(); assertTrue(repo.saves.isEmpty())
            val retry = AudioSkipCreditsViewModel(repo, SavedStateHandle()); val second = owned(retry)
            try { runCurrent(); repo.loadFails = false; retry.retry(); runCurrent(); assertFalse(retry.state.value.loading); assertFalse(retry.state.value.loadFailed); assertNotNull(retry.state.value.draft) }
            finally { second.clear() }
        } finally { owner.clear() }
    }
    private class Fake : AudioSkipCreditsRepository {
        var globals = 30 to 40; var loadFails = false; var saveFails = false
        var loadGate: CompletableDeferred<Unit>? = null; var saveGate: CompletableDeferred<Unit>? = null
        val saves = mutableListOf<AudioSkipCreditsDraft>(); private val mutex = Mutex()
        override suspend fun load(): AudioSkipCreditsDraft {
            if (loadFails) error("book unavailable")
            val result = AudioSkipCreditsDraft(true, 1, 2, globals.first, globals.second)
            loadGate?.let { withContext(NonCancellable) { it.await() } }; return result
        }
        override suspend fun write(draft: AudioSkipCreditsDraft, revision: Long, globalsChanged: Boolean, saveBook: Boolean) {
            withContext(NonCancellable) { mutex.withLock {
                if (globalsChanged) globals = draft.globalOpen to draft.globalClose
                if (saveBook) { saveGate?.await(); if (saveFails) error("save failed"); saves += draft }
            } }
        }
    }
}
