package io.legado.app.ui.about

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.AppLogDetail
import io.legado.app.data.repository.AppLogExport
import io.legado.app.data.repository.AppLogRow
import io.legado.app.data.repository.AppLogsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLogsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<AppLogsViewModel>()
    private val row = AppLogRow(1, "time", "message", true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        models.forEach { it.stopObserving() }
        Dispatchers.resetMain()
    }

    @Test
    fun pausedObservationStopsUpdatesAndResumeCollectsTheLatestSnapshot() =
        runTest(dispatcher) {
            val repository = FakeRepository(listOf(row))
            val model = model(repository)
            model.startObserving()
            runCurrent()
            assertEquals(listOf(row), model.state.value.logs)
            model.stopObserving()
            repository.rows.value = listOf(row.copy(id = 2, message = "new"), row)
            runCurrent()
            assertEquals(listOf(row), model.state.value.logs)
            model.startObserving()
            runCurrent()
            assertEquals(repository.rows.value, model.state.value.logs)
            assertFalse(model.state.value.isBusy)
        }

    @Test
    fun cancelAndUnconfirmedClearNeverClearLogs() =
        runTest(dispatcher) {
            val repository = FakeRepository(listOf(row))
            val model = model(repository)
            model.startObserving()
            runCurrent()
            model.confirmClear()
            model.requestClear()
            assertTrue(model.state.value.showClearConfirmation)
            model.dismissClearConfirmation()
            model.confirmClear()
            runCurrent()
            assertEquals(0, repository.clearCount)
            assertEquals(listOf(row), model.state.value.logs)
        }

    @Test
    fun clearConfirmationIsRestorableAndConfirmClearsOnlyOnce() =
        runTest(dispatcher) {
            val repository = FakeRepository(listOf(row))
            val savedState = SavedStateHandle()
            val model = model(repository, savedState)
            model.startObserving()
            runCurrent()
            model.requestClear()
            model.stopObserving()
            val recreated =
                model(
                    repository,
                    SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) }),
                )
            assertTrue(recreated.state.value.showClearConfirmation)
            recreated.startObserving()
            runCurrent()
            recreated.confirmClear()
            recreated.confirmClear()
            runCurrent()
            assertEquals(1, repository.clearCount)
            assertFalse(recreated.state.value.showClearConfirmation)
            assertTrue(recreated.state.value.logs.isEmpty())
        }

    @Test
    fun clearFailureKeepsLiveRemainingLogsAndReportsTheError() =
        runTest(dispatcher) {
            val repository = FakeRepository(listOf(row))
            val model = model(repository)
            model.startObserving()
            runCurrent()
            val remaining = row.copy(id = 2, message = "remaining")
            repository.clear = {
                repository.rows.value = listOf(remaining)
                error("Clear failed")
            }
            model.requestClear()
            model.confirmClear()
            runCurrent()
            assertEquals(listOf(remaining), model.state.value.logs)
            assertEquals("Clear failed", model.state.value.error)
            assertFalse(model.state.value.isBusy)
            assertFalse(model.state.value.showClearConfirmation)
        }

    @Test
    fun detailUsesStableIdAndIsConsumedOnlyByItsMatchingAcknowledgement() =
        runTest(dispatcher) {
            val repository = FakeRepository(listOf(row))
            val model = model(repository)
            model.startObserving()
            runCurrent()
            model.openLog(999)
            assertFalse(model.state.value.isWorking)
            model.openLog(row.id)
            model.openLog(row.id)
            runCurrent()
            assertEquals(1, repository.readCount)
            val detail = AppLogDetail(row.id, "Log", "stack trace")
            assertEquals(detail, model.state.value.openedLog)
            model.stopObserving()
            model.consumeOpenedLog(detail.copy(text = "old"))
            assertEquals(detail, model.state.value.openedLog)
            model.consumeOpenedLog(detail)
            assertNull(model.state.value.openedLog)
        }

    @Test
    fun rowsWithoutDetailsDoNotStartARead() =
        runTest(dispatcher) {
            val repository = FakeRepository(listOf(row.copy(hasDetails = false)))
            val model = model(repository)
            model.startObserving()
            runCurrent()
            model.openLog(row.id)
            runCurrent()
            assertEquals(0, repository.readCount)
        }

    @Test
    fun exportRemainsPendingAcrossPauseUntilTheShareIsAcknowledged() =
        runTest(dispatcher) {
            val repository = FakeRepository(listOf(row))
            val release = CompletableDeferred<Unit>()
            repository.export = {
                release.await()
                AppLogExport.Text("snapshot")
            }
            val model = model(repository)
            model.startObserving()
            runCurrent()
            model.prepareExport()
            model.prepareExport()
            runCurrent()
            assertTrue(model.state.value.isWorking)
            model.stopObserving()
            release.complete(Unit)
            runCurrent()
            val export = AppLogExport.Text("snapshot")
            assertEquals(export, model.state.value.export)
            assertEquals(1, repository.exportCount)
            model.consumeExport(AppLogExport.Text("old"))
            assertEquals(export, model.state.value.export)
            model.consumeExport(export)
            assertNull(model.state.value.export)
        }

    @Test
    fun exportAndShareFailuresAllowAFreshAttempt() =
        runTest(dispatcher) {
            val repository = FakeRepository(listOf(row))
            val model = model(repository)
            model.startObserving()
            runCurrent()
            repository.export = { error("Cannot write file") }
            model.prepareExport()
            runCurrent()
            assertEquals(AppLogNotice.ShareFailed, model.state.value.notice)
            assertFalse(model.state.value.isBusy)
            repository.export = { AppLogExport.Text("snapshot") }
            model.prepareExport()
            runCurrent()
            model.shareFailed(AppLogExport.Text("snapshot"))
            assertNull(model.state.value.export)
            assertEquals(AppLogNotice.ShareFailed, model.state.value.notice)
            model.prepareExport()
            runCurrent()
            assertEquals(AppLogExport.Text("snapshot"), model.state.value.export)
            assertNull(model.state.value.notice)
        }

    @Test
    fun emptyExportReportsNoLogsAndDoesNotLaunchAShare() =
        runTest(dispatcher) {
            val repository = FakeRepository(emptyList())
            repository.export = { null }
            val model = model(repository)
            model.startObserving()
            runCurrent()
            model.prepareExport()
            runCurrent()
            assertEquals(AppLogNotice.NoLogs, model.state.value.notice)
            assertNull(model.state.value.export)
            assertFalse(model.state.value.isBusy)
        }

    @Test
    fun observationFailureCanBeRetriedWithoutRepeatingADestructiveAction() =
        runTest(dispatcher) {
            val repository = FakeRepository(listOf(row))
            repository.loadFailure = IllegalStateException("Load failed")
            val model = model(repository)
            model.startObserving()
            runCurrent()
            assertEquals("Load failed", model.state.value.error)
            repository.loadFailure = null
            model.refresh()
            runCurrent()
            assertEquals(listOf(row), model.state.value.logs)
            assertNull(model.state.value.error)
            assertEquals(0, repository.clearCount)
        }

    private fun model(
        repository: AppLogsRepository,
        handle: SavedStateHandle = SavedStateHandle(),
    ) = AppLogsViewModel(repository, handle).also { models.add(it) }

    private class FakeRepository(initial: List<AppLogRow>) : AppLogsRepository {
        val rows = MutableStateFlow(initial)
        var loadFailure: Exception? = null
        override val logs = flow {
            loadFailure?.let { throw it }
            emitAll(rows)
        }
        var clearCount = 0
        var readCount = 0
        var exportCount = 0
        var clear: suspend () -> Unit = { rows.value = emptyList() }
        var export: suspend () -> AppLogExport? = { AppLogExport.Text("snapshot") }

        override suspend fun clearLogs() {
            clearCount++
            clear()
        }

        override suspend fun readDetail(id: Long): AppLogDetail {
            readCount++
            return AppLogDetail(id, "Log", "stack trace")
        }

        override suspend fun prepareExport(): AppLogExport? {
            exportCount++
            return export()
        }
    }
}
