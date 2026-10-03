package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EffectiveReplacementViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private class Fake : EffectiveReplacementRepository {
        var rows = listOf(EffectiveReplacementRow(1, "same"), EffectiveReplacementRow(2, "same"))
        var mode = 1
        var fail = false
        var loads = 0
        var gate: CompletableDeferred<Unit>? = null
        val disabled = mutableListOf<Long>()
        val modes = mutableListOf<Int>()

        override suspend fun load(
            sourceIds: List<Long>?,
            readerRows: List<EffectiveReplacementRow>,
        ): EffectiveReplacementSnapshot {
            loads++
            return EffectiveReplacementSnapshot(rows, mode)
        }

        override suspend fun disable(id: Long) {
            gate?.await()
            if (fail) error("database failed")
            disabled += id
        }

        override suspend fun conversion(mode: Int) {
            if (fail) error("settings failed")
            modes += mode
            this.mode = mode
        }
    }

    private fun model(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        source: List<Long>? = null,
    ) = EffectiveReplacementViewModel(repo, saved, source, emptyList(), "Conversion")

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun readerConversionIsSeparateFromRealRuleZeroAndSourceDoesNotShowIt() =
        runTest(dispatcher) {
            val repo = Fake().apply { rows = listOf(EffectiveReplacementRow(0, "Real zero")) }
            val reader = model(repo)
            val source = model(repo, source = listOf(0))
            runCurrent()
            assertEquals(listOf("rule:0", "conversion"), reader.state.value.rows.map { it.key })
            assertEquals(listOf("rule:0"), source.state.value.rows.map { it.key })
        }

    @Test
    fun duplicateNamesEditByIdentityAndConsumedNavigationCannotRestore() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            runCurrent()
            first.open("rule:2")
            val restoredSaved = copy(saved)
            val restored = model(repo, restoredSaved)
            runCurrent()
            assertEquals(2L, restored.consumeEdit())
            assertNull(restored.consumeEdit())
            assertNull(model(repo, copy(restoredSaved)).consumeEdit())
        }

    @Test
    fun disableAwaitsPersistenceAndSingleFlightCannotCloseWhileBusy() =
        runTest(dispatcher) {
            val repo = Fake().apply { gate = CompletableDeferred() }
            val first = model(repo)
            runCurrent()
            first.remove("rule:1")
            first.remove("rule:1")
            runCurrent()
            first.close()
            assertTrue(first.state.value.busy)
            assertFalse(first.state.value.finished)
            assertEquals(3, first.state.value.rows.size)
            repo.gate!!.complete(Unit)
            runCurrent()
            assertEquals(listOf(1L), repo.disabled)
            assertEquals(listOf("rule:2", "conversion"), first.state.value.rows.map { it.key })
            assertTrue(first.state.value.changed)
        }

    @Test
    fun disableFailureKeepsRowAndRetryChangesOnlyTheRequestedId() =
        runTest(dispatcher) {
            val repo = Fake().apply { fail = true }
            val first = model(repo)
            runCurrent()
            first.remove("rule:2")
            runCurrent()
            assertEquals("database failed", first.state.value.error)
            assertEquals(3, first.state.value.rows.size)
            assertFalse(first.state.value.changed)
            repo.fail = false
            first.remove("rule:2")
            runCurrent()
            assertEquals(listOf(2L), repo.disabled)
        }

    @Test
    fun removedReaderRowsStayRemovedAfterRestoreEvenWhenChapterSnapshotIsStale() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            runCurrent()
            first.remove("rule:1")
            runCurrent()
            val restored = model(repo, copy(saved))
            runCurrent()
            assertFalse(restored.state.value.rows.any { it.id == 1L })
            assertEquals(1, repo.disabled.size)
        }

    @Test
    fun conversionSelectionAndRemovalNeverDisableRealDatabaseRuleZero() =
        runTest(dispatcher) {
            val repo = Fake()
            val first = model(repo)
            runCurrent()
            first.open("conversion")
            first.chooseConversion(1)
            runCurrent()
            assertTrue(repo.modes.isEmpty())
            first.open("conversion")
            first.chooseConversion(2)
            runCurrent()
            assertEquals(listOf(2), repo.modes)
            first.remove("conversion")
            runCurrent()
            assertEquals(listOf(2, 0), repo.modes)
            assertTrue(repo.disabled.isEmpty())
        }

    @Test
    fun changedCloseRestoresOneRefreshAndFinishedStateNeverReadsOrWrites() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            runCurrent()
            first.edited()
            first.close()
            val restoredSaved = copy(saved)
            val restored = model(repo, restoredSaved)
            runCurrent()
            assertTrue(restored.consumeRefresh())
            assertFalse(restored.consumeRefresh())
            val loads = repo.loads
            val finished = model(repo, copy(restoredSaved))
            finished.remove("rule:1")
            finished.open("rule:1")
            finished.edited()
            runCurrent()
            assertEquals(loads, repo.loads)
            assertFalse(finished.consumeRefresh())
            assertTrue(repo.disabled.isEmpty())
        }

    @Test
    fun unchangedCloseNeverEmitsRefreshAndPickerDraftSurvivesRestore() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            runCurrent()
            first.open("conversion")
            val restored = model(repo, copy(saved))
            runCurrent()
            assertTrue(restored.state.value.conversionPicker)
            restored.picker(false)
            restored.close()
            assertFalse(restored.consumeRefresh())
            assertTrue(restored.state.value.finished)
        }
}
