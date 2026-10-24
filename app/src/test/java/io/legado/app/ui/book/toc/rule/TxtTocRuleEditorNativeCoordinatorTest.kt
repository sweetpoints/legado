package io.legado.app.ui.book.toc.rule

import androidx.lifecycle.*
import io.legado.app.data.repository.TxtTocRuleEditorRepository
import io.legado.app.data.repository.TxtTocRuleSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TxtTocRuleEditorNativeCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<TxtTocRuleEditorViewModel>()
    private val coordinators = mutableListOf<TxtTocRuleEditorNativeCoordinator>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { coordinators.forEach { it.close() }; models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Repository : TxtTocRuleEditorRepository {
        var row = TxtTocRuleSnapshot(-12, "original", "^Chapter", "@js:result", "Chapter One", 42, false)
        val writes = mutableListOf<TxtTocRuleSnapshot>()
        var failure = false
        var wait: CompletableDeferred<Unit>? = null
        override suspend fun load(id: Long) = row
        override suspend fun save(rule: TxtTocRuleSnapshot, requireExisting: Boolean): TxtTocRuleSnapshot {
            wait?.await()
            if (failure) error("save failed")
            writes += rule; row = rule; return rule
        }
    }
    private fun model(repo: Repository = Repository(), saved: SavedStateHandle = SavedStateHandle()) =
        TxtTocRuleEditorViewModel(repo, saved, -12, dispatcher).also { models += it }
    private fun coordinator(model: TxtTocRuleEditorViewModel, host: Host, scope: CoroutineScope,
        fullscreen: (TxtTocEditorCodeRequest) -> Unit = {}, copy: (String) -> Unit = {}, paste: () -> String? = { null },
        saved: (TxtTocRuleSnapshot) -> Unit = {}, failure: (Exception) -> Unit = {}, dismiss: () -> Unit = {}, ready: () -> Boolean = { true }) =
        TxtTocRuleEditorNativeCoordinator(model, host.lifecycle, scope, ready, fullscreen, copy, paste, {}, saved, failure, dismiss).also { coordinators += it }
    private fun command(c: TxtTocRuleEditorNativeCoordinator, owner: Any, op: String, args: Map<String, Any?> = emptyMap()) =
        c.command(owner, args + mapOf("op" to op, "generation" to c.generation))
    private fun field(c: TxtTocRuleEditorNativeCoordinator, owner: Any, key: String, text: String, start: Int = 0, end: Int = start) =
        command(c, owner, "field", mapOf("key" to key, "text" to text, "start" to start, "end" to end))

    @Test fun rejectedInputBlocksDependentActionsButExplicitAbandonmentStillConfirmsDirtyExit() = runTest(dispatcher) {
        val m = model(); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val c = coordinator(m, host, backgroundScope); val owner = c.bind()
        assertThrows(IllegalArgumentException::class.java) { field(c, owner, "Name", "draft", 9) }
        listOf("save", "copy", "paste", "fullscreen", "retry").forEach { op ->
            assertThrows(IllegalStateException::class.java) { command(c, owner, op) }
        }
        command(c, owner, "cancel"); assertTrue(m.state.value.confirmExit)
        c.requestClose(); assertFalse(m.state.value.confirmExit); assertFalse(m.state.value.finished)
        field(c, owner, "Name", "draft", 1, 4)
        command(c, owner, "save"); runCurrent(); assertTrue(m.state.value.finished)
    }
    @Test fun rawSelectionRestoresAndFullscreenUsesTheActualFocusedFieldThenCollapsesToReturnedCursor() = runTest(dispatcher) {
        val repo = Repository(); val saved = SavedStateHandle(); val m = model(repo, saved); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        var request: TxtTocEditorCodeRequest? = null
        val c = coordinator(m, host, backgroundScope, fullscreen = { request = it }); val owner = c.bind()
        field(c, owner, "Replacement", "draft", 4, 1)
        val restored = model(repo, saved); runCurrent()
        assertEquals(4, restored.state.value.cursors[2]); assertEquals(1, restored.state.value.selectionEnds[2])
        command(c, owner, "fullscreen"); assertEquals(TxtTocEditorField.Replacement, request?.field); assertEquals(4, request?.cursor)
        assertFalse(c.snapshot(owner)["canEdit"] as Boolean)
        assertTrue(m.codeResult("returned", 3)); assertFalse(m.codeResult("again", 1))
        assertEquals(3, m.state.value.cursors[2]); assertEquals(3, m.state.value.selectionEnds[2])
        command(c, owner, "save"); runCurrent()
        assertEquals(TxtTocRuleSnapshot(-12, "original", "^Chapter", "returned", "Chapter One", 42, false), repo.writes.single())
    }
    @Test fun retiredBindingsAndPausedEffectsCannotLaunchFullscreenOrReadClipboard() = runTest(dispatcher) {
        val m = model(); runCurrent(); var launches = 0; var copies = 0
        val host = Host().apply { registry.currentState = Lifecycle.State.STARTED }
        val c = coordinator(m, host, backgroundScope, fullscreen = { launches++ }, copy = { copies++ }); val old = c.bind()
        field(c, old, "Example", "draft")
        assertThrows(IllegalStateException::class.java) { command(c, old, "fullscreen") }
        assertThrows(IllegalStateException::class.java) { command(c, old, "copy") }
        host.registry.currentState = Lifecycle.State.RESUMED
        command(c, old, "fullscreen"); assertEquals(1, launches); assertEquals(0, copies)
        val current = c.bind(); c.detach(old)
        assertThrows(IllegalStateException::class.java) { c.snapshot(old) }
        assertEquals("draft", c.snapshot(current)["Example"])
        c.close(); assertThrows(IllegalStateException::class.java) { c.snapshot(current) }
    }
    @Test fun restoredSuccessWaitsForResumedAndDeliversCallbackAndDismissOnlyOnceWithoutAnotherWrite() = runTest(dispatcher) {
        val repo = Repository(); val handle = SavedStateHandle(mapOf(
            "txt.toc.editor.loaded" to true, "txt.toc.editor.hasOriginal" to true,
            "txt.toc.editor.finished" to true, "txt.toc.editor.pendingCallback" to true,
            "txt.toc.editor.id" to -12L, "txt.toc.editor.order" to 42, "txt.toc.editor.enable" to false,
            "txt.toc.editor.Name" to "saved", "txt.toc.editor.Regex" to "^Chapter",
            "txt.toc.editor.Replacement" to "rep", "txt.toc.editor.Example" to "sample"))
        val m = model(repo, handle); val host = Host().apply { registry.currentState = Lifecycle.State.STARTED }
        val delivered = mutableListOf<TxtTocRuleSnapshot>(); var closes = 0
        coordinator(m, host, backgroundScope, saved = delivered::add, dismiss = { closes++ }).bind()
        runCurrent(); assertTrue(delivered.isEmpty()); assertEquals(0, closes)
        host.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
        assertEquals(TxtTocRuleSnapshot(-12, "saved", "^Chapter", "rep", "sample", 42, false), delivered.single())
        assertEquals(1, closes); assertFalse(m.state.value.pendingCallback)
        host.registry.currentState = Lifecycle.State.STARTED; host.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
        assertEquals(1, delivered.size); assertEquals(1, closes); assertTrue(repo.writes.isEmpty())
        val recreated = model(repo, handle); assertNull(recreated.consumeSavedRule())
    }
    @Test fun restoredFinishedWithoutCallbackClosesOnlyAndFailedSavePreservesDraftMetadata() = runTest(dispatcher) {
        val repo = Repository(); val m = model(repo); runCurrent(); val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        var delivered = 0; var closes = 0
        val c = coordinator(m, host, backgroundScope, saved = { delivered++ }, dismiss = { closes++ }); val owner = c.bind()
        field(c, owner, "Name", "draft"); repo.failure = true; command(c, owner, "save"); runCurrent()
        assertEquals("draft", m.state.value.name); assertNotNull(m.state.value.error); assertFalse(m.state.value.finished)
        assertEquals(0, delivered); assertEquals(0, closes)
        repo.failure = false; command(c, owner, "save"); runCurrent(); assertEquals(1, delivered); assertEquals(1, closes)
        assertFalse(repo.writes.single().enable); assertEquals(42, repo.writes.single().serialNumber)
        val finished = model(repo, SavedStateHandle(mapOf("txt.toc.editor.loaded" to true, "txt.toc.editor.finished" to true)))
        coordinator(finished, host, backgroundScope, saved = { delivered++ }, dismiss = { closes++ }).bind(); runCurrent()
        assertEquals(1, delivered); assertEquals(2, closes); assertEquals(1, repo.writes.size)
    }
    @Test fun hostReadyPulseDeliversFinishedStateOnceWhenSavedStateGateClearsWithoutAnotherModelEmission() = runTest(dispatcher) {
        val m = model(); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        var ready = false; var delivered = 0; var closed = 0
        val c = coordinator(m, host, backgroundScope, saved = { delivered++ }, dismiss = { closed++ }, ready = { ready })
        val owner = c.bind(); field(c, owner, "Name", "saved"); command(c, owner, "save"); runCurrent()
        assertTrue(m.state.value.finished); assertEquals(0, delivered); assertEquals(0, closed)
        ready = true; c.onHostReady(); runCurrent()
        assertEquals(1, delivered); assertEquals(1, closed)
        c.onHostReady(); runCurrent(); assertEquals(1, delivered); assertEquals(1, closed)
    }
    @Test fun callbackFailureIsReportedAndHostStillClosesWithoutReplayingTheConsumedSavedReceipt() = runTest(dispatcher) {
        val repo = Repository(); val m = model(repo); runCurrent(); val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        var delivered = 0; var closed = 0; val errors = mutableListOf<Exception>()
        val c = coordinator(m, host, backgroundScope, saved = { delivered++; error("host callback failed") }, failure = errors::add, dismiss = { closed++ })
        val owner = c.bind(); field(c, owner, "Name", "saved"); command(c, owner, "save"); runCurrent()
        assertEquals(1, delivered); assertEquals(1, closed); assertEquals("host callback failed", errors.single().message)
        assertTrue(m.state.value.finished); assertEquals("saved", repo.writes.single().name)
        assertFalse(m.state.value.pendingCallback); assertNull(m.consumeSavedRule())
        c.onHostReady(); runCurrent(); assertEquals(1, delivered); assertEquals(1, closed); assertEquals(1, repo.writes.size)
    }

    @Test fun successfulCallbackCannotRunBeforeTheRepositoryTransactionCompletes() = runTest(dispatcher) {
        val repo = Repository().apply { wait = CompletableDeferred() }; val m = model(repo); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }; val receipts = mutableListOf<TxtTocRuleSnapshot>(); var closed = 0
        val c = coordinator(m, host, backgroundScope, saved = receipts::add, dismiss = { closed++ }); val owner = c.bind()
        field(c, owner, "Name", "saved"); command(c, owner, "save"); runCurrent()
        assertTrue(m.state.value.saving); assertTrue(receipts.isEmpty()); assertEquals(0, closed); assertTrue(repo.writes.isEmpty())
        repo.wait!!.complete(Unit); runCurrent()
        assertEquals(repo.writes.single(), receipts.single()); assertEquals(1, closed)
    }

}
