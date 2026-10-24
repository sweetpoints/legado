package io.legado.app.ui.highlight.edit

import androidx.lifecycle.*
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HighlightRuleEditorNativeCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<HighlightRuleEditorViewModel>()
    private val coordinators = mutableListOf<HighlightRuleEditorNativeCoordinator>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { coordinators.forEach { it.close() }; models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Repository : HighlightRuleEditorRepository {
        var disk: HighlightRuleEditorDraft? = null
        var saved: HighlightRuleEditorDraft? = null
        var saves = 0
        override suspend fun initial(session: String, id: Long, seed: String?) = disk ?: HighlightRuleEditorDraft(HighlightRuleDraft(id = 9, name = "Original", pattern = "literal", isEnabled = false, order = 77, timeoutMillisecond = 789))
        override suspend fun draft(session: String, draft: HighlightRuleEditorDraft) { disk = draft }
        override suspend fun save(session: String, draft: HighlightRuleEditorDraft) = draft.copy(savedRuleId = 9, revision = draft.revision + 1).also { saves++; saved = it; disk = it }
    }
    private fun model(repo: Repository = Repository(), saved: SavedStateHandle = SavedStateHandle()) = HighlightRuleEditorViewModel(repo, saved, 9, null).also { models += it }
    private fun coordinator(model: HighlightRuleEditorViewModel, host: Host, scope: CoroutineScope,
        ready: () -> Boolean = { true }, event: (HighlightRuleEditorEvent) -> Unit = {}, color: (HighlightRuleColorDraft) -> Unit = {}) =
        HighlightRuleEditorNativeCoordinator(model, host.lifecycle, scope, ready, emptyMap(), { emptyMap() }, {}, event, color).also { coordinators += it }
    private fun command(c: HighlightRuleEditorNativeCoordinator, owner: Any, op: String, args: Map<String, Any?> = emptyMap()) = c.command(owner, args + mapOf("op" to op, "generation" to c.generation))

    @Test fun rejectedFieldBlocksDependentSaveAndStyleButExplicitCancelStillClosesWithoutRoomWrite() = runTest(dispatcher) {
        val repo = Repository(); val m = model(repo); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }; val events = mutableListOf<HighlightRuleEditorEvent>()
        val c = coordinator(m, host, backgroundScope, event = { assertNull(m.state.value.event); events += it }); val owner = c.bind()
        assertThrows(IllegalArgumentException::class.java) { command(c, owner, "field", mapOf("key" to "name", "text" to "bad", "start" to 99, "end" to 0)) }
        listOf("save", "style", "flags").forEach { op -> assertThrows(IllegalStateException::class.java) { command(c, owner, op) } }
        command(c, owner, "cancel"); runCurrent()
        assertEquals(listOf(HighlightRuleEditorEvent.Close), events); assertEquals(0, repo.saves)
    }
    @Test fun rawSelectionFocusAndDiskDraftRestoreWithoutSerializingFieldTextIntoSavedState() = runTest(dispatcher) {
        val repo = Repository(); val saved = SavedStateHandle(); val m = model(repo, saved); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }; val c = coordinator(m, host, backgroundScope); val owner = c.bind()
        command(c, owner, "field", mapOf("key" to "name", "text" to "Draft name", "start" to 8, "end" to 2))
        command(c, owner, "scroll", mapOf("offset" to 175)); m.flush(); runCurrent()
        val restored = model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })); runCurrent()
        assertEquals("Draft name", restored.state.value.draft!!.rule.name); assertEquals("name", restored.state.value.focus)
        assertEquals(8 to 2, restored.state.value.selections["name"]); assertEquals(175, restored.state.value.scroll)
        assertTrue(saved.keys().none { saved.get<Any?>(it) == "Draft name" })
        restored.save(); runCurrent(); assertFalse(repo.saved!!.rule.isEnabled); assertEquals(77, repo.saved!!.rule.order); assertEquals(789L, repo.saved!!.rule.timeoutMillisecond)
    }
    @Test fun durableSaveWaitsForResumedAndReadyThenConsumesBeforeSingleDelivery() = runTest(dispatcher) {
        val m = model(); runCurrent(); var ready = false; var delivered = 0
        val host = Host().apply { registry.currentState = Lifecycle.State.STARTED }
        val c = coordinator(m, host, backgroundScope, ready = { ready }, event = { assertNull(m.state.value.event); if (it == HighlightRuleEditorEvent.Saved) delivered++ }); c.bind()
        m.save(); runCurrent(); assertEquals(0, delivered)
        host.registry.currentState = Lifecycle.State.RESUMED; runCurrent(); assertEquals(0, delivered)
        ready = true; c.onHostReady(); runCurrent(); assertEquals(1, delivered)
        host.registry.currentState = Lifecycle.State.STARTED; host.registry.currentState = Lifecycle.State.RESUMED; c.onHostReady(); runCurrent(); assertEquals(1, delivered)
    }
    @Test fun colorAndStyleRetryOnHostReadyAndRetiredBindingsCannotModifyEditor() = runTest(dispatcher) {
        val m = model(); runCurrent(); var ready = false; var styles = 0; var colors = 0
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        val c = coordinator(m, host, backgroundScope, ready = { ready }, event = { if (it == HighlightRuleEditorEvent.Style) styles++ }, color = { colors++ }); val old = c.bind()
        m.openStyle(); m.color(8101, 123, true); runCurrent(); assertEquals(0, styles); assertEquals(0, colors)
        ready = true; c.onHostReady(); runCurrent(); assertEquals(1, styles); assertEquals(1, colors)
        m.focus("name"); c.onHostReady(); runCurrent(); assertEquals(1, colors)
        m.closeColor(); runCurrent(); m.color(8101, 123, true); runCurrent(); assertEquals(2, colors)
        val current = c.bind(); c.detach(old)
        assertThrows(IllegalStateException::class.java) { command(c, old, "field", mapOf("key" to "name", "text" to "retired", "start" to 0, "end" to 0)) }
        assertEquals("Original", ((c.snapshot(current)["fields"] as Map<*, *>)["name"] as Map<*, *>)["text"])
    }
    @Test fun throwingSavedCallbackClosesFinishedHostWithoutReplayingOrSavingAgain() = runTest(dispatcher) {
        val repo = Repository(); val m = model(repo); runCurrent()
        val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
        var refreshes = 0; var closes = 0; var reports = 0
        val c = HighlightRuleEditorNativeCoordinator(m, host.lifecycle, backgroundScope, { true }, emptyMap(), { emptyMap() }, {},
            { event ->
                assertNull(m.state.value.event)
                when (event) {
                    HighlightRuleEditorEvent.Saved -> { refreshes++; throw IllegalStateException("reader unavailable") }
                    HighlightRuleEditorEvent.Close -> closes++
                    else -> Unit
                }
            }, {}, { reports++ }).also { coordinators += it }
        c.bind(); m.save(); runCurrent()
        assertTrue(m.state.value.finished); assertEquals(1, repo.saves)
        assertEquals(1, refreshes); assertEquals(1, closes); assertEquals(1, reports)
        c.onHostReady(); host.registry.currentState = Lifecycle.State.STARTED
        host.registry.currentState = Lifecycle.State.RESUMED; runCurrent()
        m.save(); runCurrent()
        assertEquals(1, repo.saves); assertEquals(1, refreshes); assertEquals(1, closes)
    }

}
