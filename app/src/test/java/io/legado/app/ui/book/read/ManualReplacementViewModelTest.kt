package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ManualReplacementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ManualReplacementViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Fake : ManualReplacementRepository {
        var rows = listOf(ManualReplacementRow(2, "Same"), ManualReplacementRow(1, "Same"), ManualReplacementRow(3, "Third"))
        val sources = mutableListOf<Boolean>(); var gate: CompletableDeferred<Unit>? = null; var fail = false
        override suspend fun candidates(source: Boolean): List<ManualReplacementRow> {
            sources += source; gate?.await(); if (fail) error("database failed"); return rows
        }
    }
    private fun model(repo: Fake, ids: List<Long> = emptyList(), saved: SavedStateHandle = SavedStateHandle(), source: Boolean = false) =
        ManualReplacementViewModel(repo, saved, source, ids).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.forEach { it.stop() }; runCurrent() } }
    @Test fun candidateScopeOrderIdentityAndMissingSelectionArePreserved() = test {
        val repo = Fake(); val reader = model(repo, listOf(1, 99)); val source = model(repo, source = true); runCurrent()
        assertEquals(listOf(false, true), repo.sources); assertEquals(listOf(2L, 1L, 3L), reader.state.value.rows.map { it.id })
        assertEquals(setOf(1L), reader.state.value.selected); source.toggle(1); source.toggle(2); source.confirm()
        assertEquals(listOf(2L, 1L), source.consumeConfirmation())
    }
    @Test fun draftsRestoreAfterLateLoadingWithoutBeingOverwrittenByInitialArguments() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, listOf(1), saved); runCurrent(); first.toggle(2)
        repo.gate = CompletableDeferred(); val restored = model(repo, listOf(3), copy(saved)); runCurrent()
        restored.toggle(3); assertEquals(setOf(1L, 2L), restored.state.value.selected)
        repo.gate!!.complete(Unit); runCurrent(); assertEquals(setOf(1L, 2L), restored.state.value.selected)
    }
    @Test fun allSelectionCanClearAndConfirmEmptyResultExactlyOnce() = test {
        val model = model(Fake()); runCurrent(); model.all(); assertTrue(model.state.value.allSelected); model.all()
        assertTrue(model.state.value.selected.isEmpty()); model.confirm(); model.confirm()
        assertEquals(emptyList<Long>(), model.consumeConfirmation()); assertNull(model.consumeConfirmation())
    }
    @Test fun canceledOrFinishedDialogsCannotWriteOrReloadAfterRestoration() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved = saved); runCurrent(); model.toggle(1); model.cancel(); model.confirm()
        assertNull(model.consumeConfirmation()); val restored = model(repo, saved = copy(saved)); restored.load(); runCurrent()
        assertTrue(restored.state.value.finished); assertEquals(1, repo.sources.size); assertNull(restored.consumeConfirmation())
    }
    @Test fun pendingConfirmationSurvivesProcessRestoreAndAcknowledgementDoesNotReplay() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, listOf(1, 2), saved); runCurrent(); model.confirm()
        val restoredSaved = copy(saved); val restored = model(repo, saved = restoredSaved); assertEquals(listOf(2L, 1L), restored.consumeConfirmation())
        assertNull(model(repo, saved = copy(restoredSaved)).consumeConfirmation()); assertEquals(1, repo.sources.size)
    }
    @Test fun rangeDirectionReversalRestoresBaselineAndCancelDoesNotPersistGesture() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, listOf(3), saved); runCurrent()
        model.beginRange(0); model.moveRange(2); assertEquals(setOf(1L, 2L, 3L), model.state.value.selected)
        model.moveRange(0); assertEquals(setOf(2L, 3L), model.state.value.selected)
        val restored = model(repo, saved = copy(saved)); runCurrent(); assertEquals(setOf(3L), restored.state.value.selected)
        model.cancelRange(); assertEquals(setOf(3L), model.state.value.selected)
    }
    @Test fun deselectRangeReversalAndFinishedGestureAreSaved() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, listOf(1, 2, 3), saved); runCurrent()
        model.beginRange(1); model.moveRange(2); assertEquals(setOf(2L), model.state.value.selected)
        model.moveRange(1); assertEquals(setOf(2L, 3L), model.state.value.selected); model.endRange()
        val restored = model(repo, saved = copy(saved)); runCurrent(); assertEquals(setOf(2L, 3L), restored.state.value.selected)
    }
    @Test fun failedLoadCannotConfirmAndRetryFiltersIdsOnlyAfterSuccess() = test {
        val repo = Fake().apply { fail = true }; val model = model(repo, listOf(99, 1)); runCurrent(); model.confirm()
        assertFalse(model.state.value.finished); assertEquals("database failed", model.state.value.error)
        repo.fail = false; model.load(); runCurrent(); assertNull(model.state.value.error); assertEquals(setOf(1L), model.state.value.selected)
    }
}
