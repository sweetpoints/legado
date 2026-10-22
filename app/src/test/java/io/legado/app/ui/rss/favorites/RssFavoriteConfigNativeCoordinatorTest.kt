package io.legado.app.ui.rss.favorites

import androidx.lifecycle.*
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssFavoriteConfigNativeCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<RssFavoriteConfigViewModel>()
    private val coordinators = mutableListOf<RssFavoriteConfigNativeCoordinator>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        coordinators.forEach { it.close() }
        models.forEach { it.stop(); it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Repository : RssFavoriteConfigRepository {
        var draft = RssFavoriteConfigDraft("Original", "Group")
        override suspend fun load(id: String) = draft
        override suspend fun write(id: String, draft: RssFavoriteConfigDraft) { this.draft = draft }
    }
    private fun model(repo: Repository = Repository(), saved: SavedStateHandle = SavedStateHandle()) =
        RssFavoriteConfigViewModel(repo, saved, "private-id").also { models += it }
    private fun coordinator(model: RssFavoriteConfigViewModel, host: Host, scope: CoroutineScope,
        save: (String?, String?) -> Unit = { _, _ -> }, delete: () -> Unit = {},
        canDeliver: () -> Boolean = { true }) =
        RssFavoriteConfigNativeCoordinator(model, host.lifecycle, scope, canDeliver, save, delete, {}, {})
            .also { coordinators += it }
    private fun command(c: RssFavoriteConfigNativeCoordinator, binding: Any, op: String,
        args: Map<String, Any?> = emptyMap()) = c.command(binding,
        args + mapOf("op" to op, "generation" to c.generation))

    @Test fun rejectedFieldBlocksSaveRetryUntilExactFieldAcknowledgement() = runTest(dispatcher) {
        val model = model(); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val c = coordinator(model, host, backgroundScope); val binding = c.bind()
        assertThrows(IllegalArgumentException::class.java) {
            command(c, binding, "field", mapOf("key" to "title", "text" to "Draft", "start" to 9, "end" to 9))
        }
        assertThrows(IllegalStateException::class.java) { command(c, binding, "save") }
        assertThrows(IllegalStateException::class.java) { command(c, binding, "retry") }
        command(c, binding, "field", mapOf("key" to "title", "text" to "Draft", "start" to 2, "end" to 4))
        val ack = c.snapshot(binding)
        assertEquals("Draft", ack["title"]); assertEquals(2, ack["titleStart"])
        command(c, binding, "save"); runCurrent(); assertTrue(model.state.value.finished)
    }
    @Test fun saveWaitsForResumeAndConsumedCallbackNeverReplaysAfterRebinding() = runTest(dispatcher) {
        val model = model(); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.STARTED }
        var saves = 0; var deletes = 0
        val c = coordinator(model, host, backgroundScope, { _, _ -> saves++ }, { deletes++ })
        val binding = c.bind(); command(c, binding, "save"); runCurrent()
        assertEquals(0, saves); assertNotNull(model.state.value.effect)
        host.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
        assertEquals(1, saves); assertEquals(0, deletes)
        c.detach(binding); c.bind(); host.registry.currentState = Lifecycle.State.STARTED
        host.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
        assertEquals(1, saves)
        assertThrows(IllegalStateException::class.java) { c.snapshot(binding) }
    }
    @Test fun titleGroupSelectionsAndFocusRemainNativeAndCancelNeverDeliversSaveOrDelete() = runTest(dispatcher) {
        val saved = SavedStateHandle(); val model = model(saved = saved); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        var calls = 0
        val c = coordinator(model, host, backgroundScope, { _, _ -> calls++ }, { calls++ })
        val binding = c.bind()
        command(c, binding, "field", mapOf("key" to "group", "text" to "Private group", "start" to 2, "end" to 5))
        command(c, binding, "focus", mapOf("key" to "group"))
        assertEquals("group", c.snapshot(binding)["focus"])
        assertEquals(2, c.snapshot(binding)["groupStart"])
        assertFalse(saved.keys().any { key -> saved.get<Any?>(key) == "Private group" })
        command(c, binding, "cancel"); runCurrent(); assertEquals(0, calls)
    }
    @Test fun fragmentStateSavedGateDefersReceiptUntilDeliverableResumeAndNeverReplays() = runTest(dispatcher) {
        val model = model(); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        var stateSaved = true
        var saves = 0
        val c = coordinator(model, host, backgroundScope,
            save = { _, _ -> saves++ }, canDeliver = { !stateSaved })
        val binding = c.bind()
        command(c, binding, "save"); runCurrent()
        assertEquals(0, saves)
        assertNotNull(model.state.value.effect)
        assertFalse(model.state.value.finished)
        stateSaved = false
        host.registry.currentState = Lifecycle.State.STARTED
        host.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        assertEquals(1, saves)
        assertTrue(model.state.value.finished)
        host.registry.currentState = Lifecycle.State.STARTED
        host.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        assertEquals(1, saves)
    }

}
