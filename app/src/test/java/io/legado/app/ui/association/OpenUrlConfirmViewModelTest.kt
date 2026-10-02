package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.constant.SourceType
import io.legado.app.data.repository.OpenUrlSourceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenUrlConfirmViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun argumentKeysRestoreTheUriMimeTypeAndSource() {
        val model = OpenUrlConfirmViewModel(handle(), FakeRepository())
        assertEquals("legado://target", model.state.value.uri)
        assertEquals("application/pdf", model.state.value.mimeType)
        assertEquals("source-id", model.state.value.sourceOrigin)
        assertEquals("My source", model.state.value.sourceName)
        assertEquals(SourceType.rss, model.state.value.sourceType)
    }

    @Test fun disableCompletesOnceAndLeavesClosePendingForTheResumedHost() = runTest(dispatcher) {
        val repository = FakeRepository().apply { barrier = CompletableDeferred() }
        val model = OpenUrlConfirmViewModel(handle(), repository)
        model.disableSource()
        model.disableSource()
        assertTrue(model.state.value.isWorking)
        runCurrent()
        assertEquals(listOf("disable:source-id:${SourceType.rss}"), repository.calls)
        assertFalse(model.state.value.shouldClose)
        repository.barrier!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.shouldClose)
        assertFalse(model.state.value.isWorking)
        model.disableSource()
        advanceUntilIdle()
        assertEquals(1, repository.calls.size)
    }

    @Test fun deleteOnlyRunsAfterConfirmation() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = OpenUrlConfirmViewModel(handle(), repository)
        model.confirmDelete()
        advanceUntilIdle()
        assertTrue(repository.calls.isEmpty())
        model.requestDelete()
        model.cancelDelete()
        model.confirmDelete()
        advanceUntilIdle()
        assertTrue(repository.calls.isEmpty())
        model.requestDelete()
        model.confirmDelete()
        advanceUntilIdle()
        assertEquals(listOf("delete:source-id:${SourceType.rss}"), repository.calls)
        assertTrue(model.state.value.shouldClose)
        assertFalse(model.state.value.showDeleteConfirmation)
    }

    @Test fun deleteConfirmationSurvivesSavedStateRestoration() {
        val state = handle()
        val model = OpenUrlConfirmViewModel(state, FakeRepository())
        model.requestDelete()
        val restoredState = copyHandle(state)
        val restored = OpenUrlConfirmViewModel(restoredState, FakeRepository())
        assertTrue(restored.state.value.showDeleteConfirmation)
        restored.cancelDelete()
        assertFalse(OpenUrlConfirmViewModel(copyHandle(restoredState), FakeRepository()).state.value.showDeleteConfirmation)
    }

    @Test fun successfulMutationIsNotRepeatedAfterSavedStateRestoration() = runTest(dispatcher) {
        val saved = handle()
        OpenUrlConfirmViewModel(saved, FakeRepository()).apply { disableSource() }
        advanceUntilIdle()
        val repository = FakeRepository()
        val restored = OpenUrlConfirmViewModel(copyHandle(saved), repository)
        assertTrue(restored.state.value.shouldClose)
        restored.disableSource()
        advanceUntilIdle()
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun mutationFailureKeepsTheDialogOpenAndAllowsRetry() = runTest(dispatcher) {
        val repository = FakeRepository().apply { failure = IllegalStateException("database unavailable") }
        val model = OpenUrlConfirmViewModel(handle(), repository)
        model.disableSource()
        advanceUntilIdle()
        assertEquals("database unavailable", model.state.value.error)
        assertFalse(model.state.value.shouldClose)
        assertFalse(model.state.value.isWorking)
        repository.failure = null
        model.disableSource()
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertTrue(model.state.value.shouldClose)
        assertEquals(2, repository.calls.size)
    }

    @Test fun clearingTheViewModelCancelsPendingSourceWork() = runTest(dispatcher) {
        val repository = FakeRepository().apply { barrier = CompletableDeferred() }
        val model = OpenUrlConfirmViewModel(handle(), repository)
        val store = ViewModelStore().apply { put("open-url", model) }
        model.disableSource()
        runCurrent()
        store.clear()
        repository.barrier!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.shouldClose)
    }

    @Test fun missingArgumentsUseCompatibleDefaults() {
        val state = OpenUrlConfirmViewModel(SavedStateHandle(), FakeRepository()).state.value
        assertEquals("", state.uri)
        assertNull(state.mimeType)
        assertEquals(SourceType.book, state.sourceType)
    }

    private fun handle() = SavedStateHandle(mapOf("uri" to "legado://target", "mimeType" to "application/pdf",
        "sourceOrigin" to "source-id", "sourceName" to "My source", "sourceType" to SourceType.rss))
    private fun copyHandle(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    private class FakeRepository : OpenUrlSourceRepository {
        val calls = mutableListOf<String>()
        var barrier: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        override suspend fun disableSource(origin: String, type: Int) = act("disable", origin, type)
        override suspend fun deleteSource(origin: String, type: Int) = act("delete", origin, type)
        private suspend fun act(operation: String, origin: String, type: Int) {
            calls += "$operation:$origin:$type"
            barrier?.await()
            failure?.let { throw it }
        }
    }
}
