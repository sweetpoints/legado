package io.legado.app.ui.book.bookmark

import androidx.lifecycle.*
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkEditorNativeCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BookmarkEditorViewModel>()
    private val coordinators = mutableListOf<BookmarkEditorNativeCoordinator>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { coordinators.forEach { it.close() }; models.forEach { it.stop(); it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Repository : BookmarkEditorRepository {
        var draft = BookmarkEditorDraft(BookmarkEditorSeed(42, "Book", "Author", 3, 5, "Chapter", "text", "note", 0))
        var failure = false
        var commitGate: CompletableDeferred<Unit>? = null
        val commits = mutableListOf<Pair<BookmarkEditorDraft, Boolean>>()
        override suspend fun load(id: String): BookmarkEditorDraft { if (failure) error("load failed"); return draft }
        override suspend fun write(id: String, draft: BookmarkEditorDraft) { this.draft = draft }
        override suspend fun commit(id: String, draft: BookmarkEditorDraft, delete: Boolean) {
            commitGate?.await(); if (failure) error("commit failed")
            commits += draft to delete; this.draft = draft.copy(finished = true)
        }
    }
    private fun model(repo: Repository, saved: SavedStateHandle = SavedStateHandle()) =
        BookmarkEditorViewModel(repo, saved, "ticket").also { models += it }
    private fun coordinator(m: BookmarkEditorViewModel, host: Host, scope: CoroutineScope, ready: () -> Boolean = { true }, close: () -> Unit = {}) =
        BookmarkEditorNativeCoordinator(m, host.lifecycle, scope, ready, close).also { coordinators += it }
    private fun command(c: BookmarkEditorNativeCoordinator, owner: Any, op: String, args: Map<String, Any?> = emptyMap()) =
        c.command(owner, args + mapOf("op" to op, "generation" to c.generation))
    private fun field(c: BookmarkEditorNativeCoordinator, owner: Any, text: String, start: Int, end: Int) =
        command(c, owner, "field", mapOf("key" to "content", "text" to text, "start" to start, "end" to end))
    @Test fun rejectedFieldBlocksMutationButBackCancelsWithoutAnyConfirmationOrRoomWrite() = runTest(dispatcher) {
        val repo = Repository(); val m = model(repo); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        var closes = 0; val c = coordinator(m, host, backgroundScope, close = { closes++ }); val owner = c.bind()
        assertThrows(IllegalArgumentException::class.java) { field(c, owner, "draft", 8, 1) }
        for (op in listOf("confirm", "delete", "retry")) assertThrows(IllegalStateException::class.java) { command(c, owner, op) }
        command(c, owner, "cancel"); runCurrent()
        assertTrue(m.state.value.finished); assertEquals(1, closes); assertTrue(repo.commits.isEmpty())
    }
    @Test fun rawSelectionAndFocusPersistThenOnlyNativeCommitCanFinish() = runTest(dispatcher) {
        val repo = Repository(); val saved = SavedStateHandle(); val m = model(repo, saved); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val c = coordinator(m, host, backgroundScope); val owner = c.bind()
        field(c, owner, "A😀BC", 5, 1); assertEquals(5, c.snapshot(owner)["contentStart"]); assertEquals(1, c.snapshot(owner)["contentEnd"])
        m.flushDraft(); val restored = model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })); runCurrent()
        assertEquals("content", restored.state.value.focused); assertEquals(5, restored.state.value.contentStart); assertEquals(1, restored.state.value.contentEnd)
        repo.commitGate = CompletableDeferred(); command(c, owner, "confirm"); runCurrent()
        c.requestClose(); assertTrue(m.state.value.busy); assertFalse(m.state.value.finished); assertTrue(repo.commits.isEmpty())
        repo.commitGate!!.complete(Unit); runCurrent()
        assertTrue(m.state.value.finished); assertEquals("A😀BC", repo.commits.single().first.content); assertFalse(repo.commits.single().second)
        assertEquals(repo.draft.seed, repo.commits.single().first.seed)
    }
    @Test fun finishedWaitsForHostReadyThenClosesOnceAcrossLifecyclePulses() = runTest(dispatcher) {
        val repo = Repository().apply { draft = draft.copy(finished = true) }; val m = model(repo); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }; var ready = false; var closes = 0
        val c = coordinator(m, host, backgroundScope, { ready }, { closes++ }); c.bind(); runCurrent()
        assertEquals(0, closes); ready = true; c.onHostReady(); runCurrent(); assertEquals(1, closes)
        host.registry.currentState = Lifecycle.State.STARTED; host.registry.currentState = Lifecycle.State.RESUMED
        c.onHostReady(); runCurrent(); assertEquals(1, closes); assertTrue(repo.commits.isEmpty())
    }
    @Test fun retiredOwnersPausedMutationsAndLoadRetryHaveExplicitGates() = runTest(dispatcher) {
        val repo = Repository().apply { failure = true }; val m = model(repo); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }; val c = coordinator(m, host, backgroundScope)
        val old = c.bind(); val owner = c.bind(); c.detach(old)
        assertThrows(IllegalStateException::class.java) { command(c, old, "retry") }
        repo.failure = false; command(c, owner, "retry"); runCurrent(); assertTrue(m.state.value.loaded)
        host.registry.currentState = Lifecycle.State.STARTED
        assertThrows(IllegalStateException::class.java) { command(c, owner, "delete") }
        host.registry.currentState = Lifecycle.State.RESUMED
        command(c, owner, "delete"); runCurrent(); assertTrue(repo.commits.single().second)
    }
}
