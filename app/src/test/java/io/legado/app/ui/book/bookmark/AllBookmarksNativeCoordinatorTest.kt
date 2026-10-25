package io.legado.app.ui.book.bookmark

import androidx.lifecycle.*
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AllBookmarksNativeCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
        override val lifecycle: Lifecycle get() = registry
    }
    private class Repo : AllBookmarksRepository {
        val rows = MutableStateFlow(listOf(AllBookmarksRow(1, "Book", "Author", "Chapter", "Original", "Note")))
        var bookmark = Bookmark(1, "Book", "Author", 2, 17, "Chapter", "Full body", "Note")
        var gate: CompletableDeferred<Unit>? = null
        var reads = 0
        override fun observe() = rows
        override suspend fun resolve(id: Long, edit: Boolean): AllBookmarksDestination? {
            reads++
            val copy = bookmark.copy()
            withContext(NonCancellable) { gate?.await() }
            return if (copy.time == id) AllBookmarksDestination(copy, null) else null
        }
        override suspend fun export(directory: String, markdown: Boolean) = "bookmark.json"
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun directoryWaitsForResumedAndClaimsBeforeNativeLaunchWithoutReplay() = runTest(dispatcher) {
        val host = Host(); val model = AllBookmarksViewModel(Repo(), SavedStateHandle())
        val formats = mutableListOf<Boolean>()
        val coordinator = AllBookmarksNativeCoordinator(model, host.lifecycle, backgroundScope, { true }, {}, { _, _ -> }, {
            assertNull(model.state.value.effect); assertNotNull(model.waitingDirectory()); formats += it
        }, {}, "Exported", "Error")
        try {
            runCurrent(); model.requestExport(true); runCurrent(); assertTrue(formats.isEmpty())
            host.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(listOf(true), formats)
            host.registry.currentState = Lifecycle.State.STARTED; runCurrent()
            host.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(listOf(true), formats)
        } finally { coordinator.close(); model.stop(); runCurrent() }
    }
    @Test fun pausedNonCooperativeResolveCannotLaunchAndResumeReadsLatestFullEntity() = runTest(dispatcher) {
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val repo = Repo(); val model = AllBookmarksViewModel(repo, SavedStateHandle())
        val destinations = mutableListOf<AllBookmarksDestination>()
        val coordinator = AllBookmarksNativeCoordinator(model, host.lifecycle, backgroundScope, { true }, {}, { destination, _ ->
            assertNull(model.state.value.effect); destinations += destination
        }, {}, {}, "Exported", "Error")
        try {
            runCurrent(); repo.gate = CompletableDeferred(); model.open(1, true); runCurrent()
            assertEquals(1, repo.reads)
            host.registry.currentState = Lifecycle.State.STARTED; runCurrent()
            repo.bookmark = repo.bookmark.copy(chapterPos = 99, bookText = "body".repeat(50000))
            repo.gate!!.complete(Unit); runCurrent()
            assertTrue(destinations.isEmpty()); assertNotNull(model.state.value.effect)
            host.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
            assertEquals(2, repo.reads); assertEquals(99, destinations.single().bookmark.chapterPos)
            assertEquals(200000, destinations.single().bookmark.bookText.length)
        } finally { coordinator.close(); model.stop(); runCurrent() }
    }
    @Test fun retiredBindingCannotDispatchAndOnlyKnownExactRoomIdentityCanOpen() = runTest(dispatcher) {
        val host = Host(); val model = AllBookmarksViewModel(Repo(), SavedStateHandle())
        val coordinator = AllBookmarksNativeCoordinator(model, host.lifecycle, backgroundScope, { true }, {}, { _, _ -> }, {}, {}, "Exported", "Error")
        try {
            runCurrent(); val retired = coordinator.bind(); val current = coordinator.bind()
            assertThrows(IllegalStateException::class.java) { coordinator.snapshot(retired) }
            coordinator.command(current, mapOf("generation" to coordinator.generation, "op" to "open", "id" to "2", "edit" to true))
            assertNull(model.state.value.effect)
            coordinator.command(current, mapOf("generation" to coordinator.generation, "op" to "open", "id" to "1", "edit" to true))
            assertEquals(1L, model.state.value.effect!!.bookmark)
            assertThrows(IllegalArgumentException::class.java) { coordinator.command(current,
                mapOf("generation" to coordinator.generation, "op" to "back", "arbitraryUrl" to "rejected")) }
        } finally { coordinator.close(); model.stop(); runCurrent() }
    }
    @Test fun nativeLaunchFailureConsumesEffectClearsDirectoryAndAllowsFreshRequest() = runTest(dispatcher) {
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val model = AllBookmarksViewModel(Repo(), SavedStateHandle()); val notices = mutableListOf<String>()
        var launches = 0
        val coordinator = AllBookmarksNativeCoordinator(model, host.lifecycle, backgroundScope, { true }, {}, { _, _ -> }, {
            launches++; error("launch failed")
        }, notices::add, "Exported", "Error")
        try {
            runCurrent(); model.requestExport(false); runCurrent()
            assertEquals(1, launches); assertNull(model.state.value.effect); assertNull(model.waitingDirectory())
            assertEquals(listOf("launch failed"), notices)
            host.registry.currentState = Lifecycle.State.STARTED; runCurrent()
            host.registry.currentState = Lifecycle.State.RESUMED; runCurrent(); assertEquals(1, launches)
            model.requestExport(true); runCurrent(); assertEquals(2, launches)
        } finally { coordinator.close(); model.stop(); runCurrent() }
    }
}
