package io.legado.app.ui.group

import androidx.lifecycle.*
import io.legado.app.data.repository.NamedGroupRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class NamedGroupNativeCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<NamedGroupViewModel>()
    private val coordinators = mutableListOf<NamedGroupNativeCoordinator>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { coordinators.forEach { it.close() }; models.forEach { it.stop() }; Dispatchers.resetMain() }
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
    private class Repository : NamedGroupRepository {
        val rows = MutableStateFlow(listOf("One", "Two"))
        val writes = mutableListOf<Pair<String?, String?>>()
        var gate: CompletableDeferred<Unit>? = null
        var failure = false
        override fun groups() = rows
        override suspend fun add(name: String) { gate?.await(); if (failure) error("disk"); writes += null to name }
        override suspend fun rename(original: String, replacement: String?) { gate?.await(); if (failure) error("disk"); writes += original to replacement }
    }
    private class Session : NamedGroupDraftSession {
        var saved: NamedGroupState? = null
        var failSave = false
        var failClearAfterMutation = false
        var failRestore = false
        var cleared = 0
        override fun restore(): NamedGroupState? { if (failRestore) error("corrupt"); return saved }
        override fun save(state: NamedGroupState) {
            if (failSave || (failClearAfterMutation && saved?.mutationUncertain == true && !state.editing)) error("private disk")
            saved = state
        }
        override fun clear() { cleared++; saved = null }
    }
    private fun model(repo: Repository = Repository(), saved: SavedStateHandle = SavedStateHandle(), session: Session? = null) =
        NamedGroupViewModel(repo, saved, session).also { models += it }
    private fun coordinator(m: NamedGroupViewModel, host: Host = Host(), kind: String = "book", close: () -> Unit = {}) =
        NamedGroupNativeCoordinator(m, host.lifecycle, kind, { true }, close).also { coordinators += it }
    private fun command(c: NamedGroupNativeCoordinator, owner: Any, op: String, args: Map<String, Any?> = emptyMap()) =
        c.command(owner, args + mapOf("op" to op, "generation" to c.generation))
    private fun field(c: NamedGroupNativeCoordinator, owner: Any, text: String, start: Int, end: Int) =
        command(c, owner, "field", mapOf("text" to text, "start" to start, "end" to end))

    @Test fun rowIdentityUsesCapturedNameAndPreservesRawSelectionAcrossRestore() = runTest(dispatcher) {
        val repo = Repository(); val saved = SavedStateHandle(); val m = model(repo, saved); runCurrent()
        val c = coordinator(m); val owner = c.bind(); command(c, owner, "edit", mapOf("id" to namedGroupRowId("Two")))
        field(c, owner, " Raw😀name ", 2, 6)
        val restored = model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })); runCurrent()
        repo.rows.value = listOf("New", "Two", "One"); runCurrent(); restored.confirm(); runCurrent()
        assertEquals(listOf("Two" to " Raw😀name "), repo.writes)
        assertEquals(2, m.state.value.selectionStart); assertEquals(6, m.state.value.selectionEnd)
    }
    @Test fun failedPrivateInputNeverAcknowledgesOrAllowsConfirmUntilExplicitCancel() = runTest(dispatcher) {
        val session = Session(); val repo = Repository(); val m = model(repo, session = session); runCurrent()
        val c = coordinator(m); val owner = c.bind(); command(c, owner, "edit", mapOf("id" to namedGroupRowId("One")))
        session.failSave = true
        assertThrows(IllegalStateException::class.java) { field(c, owner, "draft", 1, 4) }
        assertEquals("One", m.state.value.name); assertEquals("One", session.saved!!.name)
        assertThrows(IllegalStateException::class.java) { command(c, owner, "confirm") }
        session.failSave = false; command(c, owner, "cancel"); assertFalse(m.state.value.editing); assertTrue(repo.writes.isEmpty())
    }
    @Test fun sessionCleanupFailureAfterRoomCommitCannotReplayCompletedWriteOrRestoredUncertainReceipt() = runTest(dispatcher) {
        val session = Session(); val repo = Repository(); val m = model(repo, session = session); runCurrent()
        m.edit("One"); m.name("Changed"); session.failClearAfterMutation = true; m.confirm(); runCurrent()
        assertEquals(listOf("One" to "Changed"), repo.writes); assertFalse(m.state.value.editing); assertNotNull(m.state.value.error)
        m.confirm(); runCurrent(); assertEquals(1, repo.writes.size)
        assertTrue(session.saved!!.mutationUncertain)
        val restored = model(repo, session = session); runCurrent(); assertTrue(restored.state.value.mutationUncertain)
        restored.confirm(); runCurrent(); assertEquals(1, repo.writes.size)
        session.failClearAfterMutation = false; restored.cancelEdit(); assertFalse(restored.state.value.editing)
    }
    @Test fun corruptedSessionIsExplicitAndRetainedUntilRecoverySucceeds() = runTest(dispatcher) {
        val session = Session().apply { failRestore = true }; val m = model(saved = SavedStateHandle(mapOf("editing" to true, "name" to "legacy")), session = session); runCurrent()
        assertTrue(m.state.value.recoveryFailed); assertFalse(m.state.value.editing); assertNotNull(m.state.value.error); m.add(); assertFalse(m.state.value.editing)
        m.clearSession(); assertEquals(0, session.cleared)
        session.failRestore = false; session.saved = NamedGroupState(editing = true, original = "One", name = "draft", selectionStart = 1, selectionEnd = 4)
        m.retry(); runCurrent(); assertFalse(m.state.value.recoveryFailed); assertEquals("draft", m.state.value.name)
    }
    @Test fun retiredOwnerAndGenerationCannotMutateOrPoisonReplacementBinding() = runTest(dispatcher) {
        val m = model(); runCurrent(); val c = coordinator(m); val old = c.bind(); val owner = c.bind()
        assertThrows(IllegalStateException::class.java) { command(c, old, "add") }
        c.detach(old); command(c, owner, "add"); field(c, owner, "new", 1, 2)
        assertEquals("new", c.snapshot(owner)["name"])
        assertThrows(IllegalArgumentException::class.java) { c.command(owner, mapOf("op" to "cancel", "generation" to c.generation + 1)) }
    }
    @Test fun busyMutationRejectsBackAndRepeatedConfirmThenResolvesOnlyOnce() = runTest(dispatcher) {
        val repo = Repository().apply { gate = CompletableDeferred() }; val m = model(repo); runCurrent()
        var closes = 0; val c = coordinator(m, kind = "rss", close = { closes++ }); val owner = c.bind()
        command(c, owner, "add"); field(c, owner, "New", 1, 2); command(c, owner, "confirm"); runCurrent()
        assertTrue(m.state.value.busy); c.requestClose(); assertEquals(0, closes)
        assertThrows(IllegalStateException::class.java) { command(c, owner, "confirm") }
        repo.gate!!.complete(Unit); runCurrent(); assertEquals(listOf(null to "New"), repo.writes)
        assertEquals(true, c.snapshot(owner)["showDone"]); command(c, owner, "back"); assertEquals(1, closes)
        c.requestClose(); assertEquals(1, closes)
    }
    @Test fun failedRoomMutationRetainsDraftAndBlankRenameKeepsMembershipRemovalSemantics() = runTest(dispatcher) {
        val repo = Repository().apply { failure = true }; val session = Session(); val m = model(repo, session = session); runCurrent()
        m.edit("One"); m.name(""); m.confirm(); runCurrent(); assertTrue(m.state.value.editing); assertNotNull(m.state.value.error)
        assertFalse(session.saved!!.mutationUncertain); repo.failure = false; m.confirm(); runCurrent()
        assertEquals(listOf("One" to ""), repo.writes)
    }
}
