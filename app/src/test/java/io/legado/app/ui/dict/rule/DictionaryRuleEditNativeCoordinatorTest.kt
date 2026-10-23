package io.legado.app.ui.dict.rule

import androidx.lifecycle.*
import io.legado.app.data.repository.DictionaryRuleRepository
import io.legado.app.data.repository.DictionaryRuleSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryRuleEditNativeCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<DictionaryRuleEditViewModel>()
    private val coordinators = mutableListOf<DictionaryRuleEditNativeCoordinator>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { coordinators.forEach { it.close() }; models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Repository : DictionaryRuleRepository {
        var row = DictionaryRuleSnapshot("original", "url", "show", false, 431)
        val writes = mutableListOf<DictionaryRuleSnapshot>()
        var failure = false
        override suspend fun load(name: String) = row
        override suspend fun save(previousName: String?, rule: DictionaryRuleSnapshot) {
            if (failure) error("save failed")
            writes += rule; row = rule
        }
    }
    private fun model(repo: Repository = Repository(), saved: SavedStateHandle = SavedStateHandle()) =
        DictionaryRuleEditViewModel(repo, saved, "original", dispatcher).also { models += it }
    private fun coordinator(model: DictionaryRuleEditViewModel, host: Host, scope: CoroutineScope,
        fullscreen: (DictionaryFullEditRequest) -> Unit = {}, copy: (String) -> Unit = {}, paste: () -> String? = { null },
        dismiss: () -> Unit = {}) = DictionaryRuleEditNativeCoordinator(model, host.lifecycle, scope, { true }, fullscreen, copy, paste, {}, dismiss).also { coordinators += it }
    private fun command(c: DictionaryRuleEditNativeCoordinator, owner: Any, op: String, args: Map<String, Any?> = emptyMap()) =
        c.command(owner, args + mapOf("op" to op, "generation" to c.generation))
    private fun field(c: DictionaryRuleEditNativeCoordinator, owner: Any, key: String, text: String, start: Int = 0, end: Int = start) =
        command(c, owner, "field", mapOf("key" to key, "text" to text, "start" to start, "end" to end))

    @Test fun rejectedFieldBlocksDependentOperationsAndExplicitCancelStillConfirmsDirtyExit() = runTest(dispatcher) {
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
        command(c, owner, "save"); runCurrent()
        assertTrue(m.state.value.finished)
    }
    @Test fun nativeSelectionsFocusMetadataAndActualFullscreenResultRemainAuthoritative() = runTest(dispatcher) {
        val repo = Repository(); val saved = SavedStateHandle(); val m = model(repo, saved); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        var request: DictionaryFullEditRequest? = null
        val c = coordinator(m, host, backgroundScope, fullscreen = { request = it }); val owner = c.bind()
        field(c, owner, "ShowRule", "draft", 2, 4)
        assertEquals(4, c.snapshot(owner)["ShowRuleEnd"])
        command(c, owner, "fullscreen"); assertEquals(DictionaryRuleField.ShowRule, request?.field); assertEquals(2, request?.cursor)
        val restored = model(repo, saved); runCurrent()
        assertEquals(4, restored.state.value.selectionEnds[2]); assertEquals(DictionaryRuleField.ShowRule, restored.state.value.focused)
        assertTrue(restored.fullEditResult("returned", 3)); assertFalse(restored.fullEditResult("again", 1))
        restored.save(); runCurrent()
        assertEquals(DictionaryRuleSnapshot("original", "url", "returned", false, 431), repo.writes.single())
    }
    @Test fun retiredBindingsAndBackgroundEffectsAreRejectedBeforeConsumption() = runTest(dispatcher) {
        val m = model(); runCurrent(); var launches = 0; var copies = 0
        val host = Host().apply { registry.currentState = Lifecycle.State.STARTED }
        val c = coordinator(m, host, backgroundScope, fullscreen = { launches++ }, copy = { copies++ }); val old = c.bind()
        field(c, old, "ShowRule", "draft")
        assertThrows(IllegalStateException::class.java) { command(c, old, "fullscreen") }
        assertThrows(IllegalStateException::class.java) { command(c, old, "copy") }
        host.registry.currentState = Lifecycle.State.RESUMED
        command(c, old, "fullscreen"); assertEquals(1, launches); assertEquals(0, copies)
        val current = c.bind(); c.detach(old)
        assertThrows(IllegalStateException::class.java) { c.snapshot(old) }
        assertEquals("draft", c.snapshot(current)["ShowRule"])
    }
    @Test fun restoredFinishedClosesWithoutSavingAgain() = runTest(dispatcher) {
        val repo = Repository(); val m = model(repo, SavedStateHandle(mapOf("dictionary.editor.loaded" to true, "dictionary.editor.finished" to true)))
        val host = Host().apply { registry.currentState = Lifecycle.State.STARTED }; var closes = 0
        val c = coordinator(m, host, backgroundScope, dismiss = { closes++ }); c.bind(); runCurrent(); assertEquals(0, closes)
        host.registry.currentState = Lifecycle.State.RESUMED; runCurrent(); assertEquals(1, closes)
        host.registry.currentState = Lifecycle.State.STARTED; host.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
        assertEquals(1, closes); assertTrue(repo.writes.isEmpty())
    }
    @Test fun pastePreservesPrivateMetadataAndSaveFailureLeavesDraftRetryable() = runTest(dispatcher) {
        val repo = Repository(); val m = model(repo); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val c = coordinator(m, host, backgroundScope, paste = { "{\"name\":\"imported\",\"urlRule\":\"new url\",\"showRule\":\"new show\",\"enabled\":true,\"sortNumber\":0}" }); val owner = c.bind()
        command(c, owner, "paste"); runCurrent(); assertEquals("imported", m.state.value.name)
        repo.failure = true; command(c, owner, "save"); runCurrent()
        assertFalse(m.state.value.finished); assertEquals("imported", m.state.value.name); assertNotNull(m.state.value.error)
        repo.failure = false; command(c, owner, "save"); runCurrent()
        assertEquals(false, repo.writes.single().enabled); assertEquals(431, repo.writes.single().sortNumber)
    }
}
