package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.AddBookLinkRepository
import io.legado.app.data.repository.BookLinkTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AddBookLinkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun successfulResultIsPersistedAndRestoresWithoutResolvingAgain() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val first = AddBookLinkViewModel(repo, saved, "url"); runCurrent()
        assertEquals(repo.target, first.state.value.target); assertFalse(first.state.value.loading)
        val restored = AddBookLinkViewModel(repo, copy(saved), "url"); runCurrent()
        assertEquals(repo.target, restored.state.value.target); assertEquals(1, repo.calls)
    }
    @Test fun consumedNavigationCannotReappearAfterProcessRestore() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val first = AddBookLinkViewModel(repo, saved, "url"); runCurrent()
        first.consumeResult(); assertNull(first.state.value.target); assertTrue(first.state.value.finished)
        val restored = AddBookLinkViewModel(repo, copy(saved), "url"); runCurrent()
        assertTrue(restored.state.value.finished); assertNull(restored.state.value.target); assertEquals(1, repo.calls)
    }
    @Test fun cancelledReadCannotPublishLateNavigationEvenIfTheStoreFinishesNonCancellably() = runTest(dispatcher) {
        val repo = Fake().apply { gate = CompletableDeferred() }; val saved = SavedStateHandle()
        val model = AddBookLinkViewModel(repo, saved, "url"); runCurrent(); assertTrue(model.state.value.loading)
        model.cancel(); repo.gate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.finished); assertNull(model.state.value.target); assertNull(model.state.value.error)
        val restored = AddBookLinkViewModel(repo, copy(saved), "url"); runCurrent(); assertEquals(1, repo.calls)
        assertTrue(restored.state.value.finished)
    }
    @Test fun failureIsPendingUntilHostConsumesAndRestoresWithoutRunningAgain() = runTest(dispatcher) {
        val repo = Fake().apply { fail = true }; val saved = SavedStateHandle()
        val first = AddBookLinkViewModel(repo, saved, "url"); runCurrent()
        assertEquals("未找到匹配书源", first.state.value.error); assertFalse(first.state.value.finished)
        val restored = AddBookLinkViewModel(repo, copy(saved), "url"); runCurrent()
        assertEquals("未找到匹配书源", restored.state.value.error); assertEquals(1, repo.calls)
        restored.consumeResult(); assertNull(restored.state.value.error); assertTrue(restored.state.value.finished)
    }
    @Test fun staleSavedStateUsesRepositoryCompletedResultWithoutDuplicatingWork() = runTest(dispatcher) {
        val repo = Fake().apply { gate = CompletableDeferred() }; val saved = SavedStateHandle()
        val first = AddBookLinkViewModel(repo, saved, "url"); runCurrent(); val stale = copy(saved)
        repo.gate!!.complete(Unit); runCurrent(); assertEquals(1, repo.work)
        val restored = AddBookLinkViewModel(repo, stale, "url"); runCurrent()
        assertEquals(repo.target, restored.state.value.target); assertEquals(1, repo.work)
    }
    @Test fun cancelledUnconsumedResultClosesWithoutNavigationOrReload() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = AddBookLinkViewModel(repo, saved, "url"); runCurrent()
        model.cancel(); assertNull(model.state.value.target)
        val restored = AddBookLinkViewModel(repo, copy(saved), "url"); runCurrent(); assertTrue(restored.state.value.finished)
        assertEquals(1, repo.calls)
    }
    private class Fake : AddBookLinkRepository {
        val target = BookLinkTarget("Name", "Author", "url"); var calls = 0; var work = 0; var completed = false; var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun resolve(session: String, url: String): BookLinkTarget {
            calls++; if (completed) return target
            withContext(NonCancellable) { gate?.await() }
            if (fail) error("未找到匹配书源")
            work++; completed = true; return target
        }
    }
}
