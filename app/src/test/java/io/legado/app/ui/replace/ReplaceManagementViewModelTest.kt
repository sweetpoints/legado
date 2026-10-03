package io.legado.app.ui.replace

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.ReplaceManagementCheckpoint
import io.legado.app.data.repository.ReplaceManagementExport
import io.legado.app.data.repository.ReplaceManagementFilter
import io.legado.app.data.repository.ReplaceManagementRepository
import io.legado.app.data.repository.ReplaceManagementRow
import io.legado.app.data.repository.ReplaceManagementSessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceManagementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ReplaceManagementViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val labels = ReplaceManagementLabels("Enabled", "Disabled", "No group")

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        Dispatchers.resetMain()
    }

    private class Repo : ReplaceManagementRepository {
        val values =
            MutableStateFlow(
                (1L..3L).map {
                    ReplaceManagementRow(it, "Rule $it", "Rule $it", null, true, it.toInt())
                }
            )
        val filters = mutableListOf<ReplaceManagementFilter>()
        val calls = mutableListOf<String>()
        var flag = false
        var gate: CompletableDeferred<Unit>? = null
        var failure = false

        override fun rows(filter: ReplaceManagementFilter) = values.also { filters += filter }

        override fun groups() = MutableStateFlow(listOf("A", "AA"))

        private suspend fun await() {
            withContext(NonCancellable) { gate?.await() }
            if (failure) error("Write failed")
        }

        override suspend fun enabled(ids: List<Long>, value: Boolean) {
            calls += "enable:$ids:$value"
            await()
        }

        override suspend fun group(ids: List<Long>, value: String, add: Boolean) {
            calls += "group:$ids:$value:$add"
            await()
        }

        override suspend fun edge(ids: List<Long>, top: Boolean) {
            calls += "edge:$ids:$top"
            await()
        }

        override suspend fun move(id: Long, target: Long, after: Boolean) {
            calls += "move:$id:$target:$after"
            await()
        }

        override suspend fun delete(ids: List<Long>) {
            calls += "delete:$ids"
            await()
        }

        override suspend fun export(ids: List<Long>) = ReplaceManagementExport("path")

        override suspend fun releaseExport(path: String) {}

        override suspend fun importHistory() = emptyList<String>()

        override suspend fun rememberImport(value: String) {}

        override suspend fun forgetImport(value: String) {}

        override suspend fun manual() = flag

        override suspend fun manual(value: Boolean) {
            await()
            flag = value
        }

        override suspend fun refreshPipeline() {}
    }

    private class Sessions : ReplaceManagementSessionRepository {
        var value: ReplaceManagementCheckpoint? = null
        var writes = 0
        var readGate: CompletableDeferred<Unit>? = null
        var failure = false

        override suspend fun read(session: String): ReplaceManagementCheckpoint? {
            withContext(NonCancellable) { readGate?.await() }
            return value
        }

        override suspend fun write(session: String, value: ReplaceManagementCheckpoint) {
            if (failure) error("Disk failed")
            writes++
            if (value.revision >= (this.value?.revision ?: -1)) this.value = value
        }

        override suspend fun release(session: String) {
            value = null
        }
    }

    private fun model(
        repo: Repo = Repo(),
        sessions: Sessions = Sessions(),
        saved: SavedStateHandle = SavedStateHandle(),
    ) = ReplaceManagementViewModel(repo, sessions, saved).also { models += it }

    private fun gate() = CompletableDeferred<Unit>().also { gates += it }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                gates.forEach { it.complete(Unit) }
                runCurrent()
            }
        }

    private fun TestScope.start(model: ReplaceManagementViewModel) {
        model.bind(labels)
        runCurrent()
    }

    @Test
    fun largeQuerySelectionModalAndCursorRestoreWithOnlySmallSavedFields() = test {
        val saved = SavedStateHandle()
        val sessions = Sessions()
        val first = model(sessions = sessions, saved = saved)
        start(first)
        first.query("Q".repeat(2000000), 4, 8)
        first.selected(2, true)
        first.dialog(ReplaceManagementDialog.AddGroup)
        first.draft("G".repeat(2000000), 2, 6)
        first.scroll(23, 12)
        runCurrent()
        first.stop()
        val restored = model(sessions = sessions, saved = copy(saved))
        start(restored)
        assertEquals(2000000, restored.state.value.query.length)
        assertEquals(4, restored.state.value.queryStart)
        assertEquals(setOf(2L), restored.state.value.selected)
        assertEquals(listOf(2L), restored.state.value.targets)
        assertEquals(2000000, restored.state.value.draft.length)
        assertEquals(6, restored.state.value.draftEnd)
        assertEquals(23, restored.state.value.scrollIndex)
        assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
    }

    @Test
    fun queryBranchesUseExactLocalizedValuesWithoutTrimmingAndFirstEmissionDoesNotMarkChanged() =
        test {
            val repo = Repo()
            val model = model(repo)
            start(model)
            assertFalse(model.state.value.changed)
            model.query("Disabled")
            runCurrent()
            assertEquals(ReplaceManagementFilter.Disabled, repo.filters.last())
            assertFalse(model.state.value.changed)
            model.query("group:A")
            runCurrent()
            assertEquals(ReplaceManagementFilter.Group("A"), repo.filters.last())
            model.query(" ")
            runCurrent()
            assertEquals(ReplaceManagementFilter.Search(" "), repo.filters.last())
            repo.values.value = repo.values.value.map { it.copy(name = "Updated") }
            runCurrent()
            assertTrue(model.state.value.changed)
        }

    @Test
    fun hiddenSelectionSurvivesFilteringButBulkTargetsUseOnlyVisibleRowsInTheirOrder() = test {
        val repo = Repo()
        val model = model(repo)
        start(model)
        model.selected(1, true)
        model.selected(3, true)
        runCurrent()
        repo.values.value = listOf(repo.values.value[2], repo.values.value[1])
        runCurrent()
        assertEquals(setOf(1L, 3L), model.state.value.selected)
        assertEquals(listOf(3L), model.state.value.visibleSelection)
        model.selectAll()
        runCurrent()
        assertEquals(setOf(1L, 2L, 3L), model.state.value.selected)
        model.invertSelection()
        runCurrent()
        assertEquals(setOf(1L), model.state.value.selected)
    }

    @Test
    fun slideSelectionUsesToggleAndReverseAndCancelNeverWritesHoverDraft() = test {
        val sessions = Sessions()
        val model = model(sessions = sessions)
        start(model)
        model.selected(2, true)
        runCurrent()
        val before = sessions.writes
        assertTrue(model.beginSelection())
        model.previewSelection(setOf(1, 2))
        assertEquals(setOf(1L), model.state.value.selected)
        model.previewSelection(setOf(2, 3))
        assertEquals(setOf(3L), model.state.value.selected)
        model.cancelGesture()
        runCurrent()
        assertEquals(setOf(2L), model.state.value.selected)
        assertEquals(before, sessions.writes)
        model.beginSelection()
        model.previewSelection(setOf(2, 3))
        model.finishSelection()
        runCurrent()
        assertEquals(listOf(3L), sessions.value!!.selected)
    }

    @Test
    fun dragCancelAndReturnToOriginalOrderNeverWriteDaoAndRoomUpdatesAreBuffered() = test {
        val repo = Repo()
        val model = model(repo)
        start(model)
        model.beginDrag(2)
        model.dragTo(3, true)
        model.dragTo(1, true)
        model.finishDrag()
        runCurrent()
        assertTrue(repo.calls.isEmpty())
        model.beginDrag(1)
        model.dragTo(3, true)
        repo.values.value = repo.values.value.map { it.copy(name = "New name") }
        runCurrent()
        assertEquals("Rule 1", model.state.value.rows.last().name)
        model.cancelGesture()
        assertEquals("New name", model.state.value.rows.first().name)
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun completedRelativeDragCommitsExactlyOnceAndRepeatedUpIsHarmless() = test {
        val repo = Repo()
        val model = model(repo)
        start(model)
        model.beginDrag(1)
        model.dragTo(3, true)
        model.finishDrag()
        model.finishDrag()
        runCurrent()
        assertEquals(listOf("move:1:3:true"), repo.calls)
        assertTrue(model.state.value.changed)
    }

    @Test
    fun processRestoreDropsGesturePreviewAndKeepsOnlyCommittedSelection() = test {
        val saved = SavedStateHandle()
        val sessions = Sessions()
        val first = model(sessions = sessions, saved = saved)
        start(first)
        first.selected(2, true)
        runCurrent()
        first.beginSelection()
        first.previewSelection(setOf(1, 2, 3))
        first.stop()
        val restored = model(sessions = sessions, saved = copy(saved))
        start(restored)
        assertEquals(setOf(2L), restored.state.value.selected)
        assertNull(restored.state.value.dragging)
    }

    @Test
    fun deleteCancelDoesNotWriteAndConfirmationUsesFixedIdsWithDuplicateClicksGuarded() = test {
        val repo = Repo().apply { gate = gate() }
        val model = model(repo)
        start(model)
        model.selected(2, true)
        model.dialog(ReplaceManagementDialog.Delete)
        model.cancelDialog()
        runCurrent()
        assertTrue(repo.calls.isEmpty())
        model.dialog(ReplaceManagementDialog.Delete)
        model.confirmDialog()
        model.confirmDialog()
        runCurrent()
        assertEquals(listOf("delete:[2]"), repo.calls)
        assertTrue(model.state.value.busy)
        repo.gate!!.complete(Unit)
        runCurrent()
        assertNull(model.state.value.dialog)
        assertTrue(model.state.value.selected.isEmpty())
    }

    @Test
    fun groupDraftKeepsExactNonEmptyValueAndManualPreferenceCanToggleRepeatedly() = test {
        val repo = Repo()
        val model = model(repo)
        start(model)
        model.selected(1, true)
        model.dialog(ReplaceManagementDialog.AddGroup)
        model.draft(" A ")
        model.confirmDialog()
        runCurrent()
        assertEquals(listOf("group:[1]: A :true"), repo.calls)
        model.toggleManual()
        runCurrent()
        assertTrue(model.state.value.manual)
        model.toggleManual()
        runCurrent()
        assertFalse(model.state.value.manual)
    }

    @Test
    fun failedDraftWriteCanRetryAndLatePrivateLoadOrMutationCannotPublishAfterStop() = test {
        val sessions = Sessions().apply { failure = true }
        val first = model(sessions = sessions)
        start(first)
        first.query("Exact draft")
        runCurrent()
        assertNotNull(first.state.value.error)
        sessions.failure = false
        first.retry()
        runCurrent()
        assertEquals("Exact draft", sessions.value!!.query)
        val loading = Sessions().apply { readGate = gate() }
        val stopped = model(sessions = loading)
        stopped.bind(labels)
        runCurrent()
        stopped.stop()
        loading.readGate!!.complete(Unit)
        runCurrent()
        assertFalse(stopped.state.value.loaded)
        val repo = Repo().apply { gate = gate() }
        val busy = model(repo)
        start(busy)
        busy.enabled(listOf(1), false)
        runCurrent()
        busy.stop()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertTrue(busy.state.value.busy)
    }
}
