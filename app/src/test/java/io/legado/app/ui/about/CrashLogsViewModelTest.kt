package io.legado.app.ui.about

import io.legado.app.data.repository.CrashLogContent
import io.legado.app.data.repository.CrashLogEntry
import io.legado.app.data.repository.CrashLogsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrashLogsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val log = CrashLogEntry("local/log", "2026-10-02.log")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialLoadPublishesLogsAndFinishesLoading() =
        runTest(dispatcher) {
            val model = CrashLogsViewModel(FakeRepository(logs = listOf(log)))
            assertTrue(model.state.value.isLoading)
            advanceUntilIdle()
            assertEquals(listOf(log), model.state.value.logs)
            assertFalse(model.state.value.isBusy)
        }

    @Test
    fun loadFailureCanBeRetried() =
        runTest(dispatcher) {
            val repository = FakeRepository(logs = listOf(log))
            repository.loadFailure = IllegalStateException("Permission denied")
            val model = CrashLogsViewModel(repository)
            advanceUntilIdle()
            assertEquals("Permission denied", model.state.value.error)
            assertFalse(model.state.value.isLoading)
            repository.loadFailure = null
            model.refresh()
            advanceUntilIdle()
            assertEquals(listOf(log), model.state.value.logs)
            assertNull(model.state.value.error)
        }

    @Test
    fun openLogIsPendingUntilAcknowledgedAndIgnoresUnknownIds() =
        runTest(dispatcher) {
            val repository = FakeRepository(logs = listOf(log))
            val model = CrashLogsViewModel(repository)
            advanceUntilIdle()
            model.openLog("unknown")
            assertEquals(0, repository.readCount)
            model.openLog(log.id)
            assertEquals(log.id, model.state.value.loadingLogId)
            advanceUntilIdle()
            val content = CrashLogContent(log, "stack trace")
            assertEquals(content, model.state.value.openedLog)
            model.openLog(log.id)
            advanceUntilIdle()
            assertEquals(1, repository.readCount)
            model.consumeOpenedLog(content.copy(text = "stale"))
            assertEquals(content, model.state.value.openedLog)
            model.consumeOpenedLog(content)
            assertNull(model.state.value.openedLog)
        }

    @Test
    fun readFailurePreservesTheListAndAllowsAnotherAttempt() =
        runTest(dispatcher) {
            val repository = FakeRepository(logs = listOf(log))
            repository.readFailure = IllegalStateException("Missing file")
            val model = CrashLogsViewModel(repository)
            advanceUntilIdle()
            model.openLog(log.id)
            advanceUntilIdle()
            assertEquals("Missing file", model.state.value.error)
            assertEquals(listOf(log), model.state.value.logs)
            assertNull(model.state.value.loadingLogId)
            repository.readFailure = null
            model.openLog(log.id)
            advanceUntilIdle()
            assertNotNull(model.state.value.openedLog)
            assertNull(model.state.value.error)
        }

    @Test
    fun clearRefreshesRemainingFilesEvenAfterPartialFailure() =
        runTest(dispatcher) {
            val repository = FakeRepository(logs = listOf(log))
            val model = CrashLogsViewModel(repository)
            advanceUntilIdle()
            val backup = CrashLogEntry("backup/log", "older.log")
            repository.clear = {
                repository.logs = listOf(backup)
                error("Backup is read-only")
            }
            model.clearLogs()
            model.clearLogs()
            model.refresh()
            assertTrue(model.state.value.isClearing)
            advanceUntilIdle()
            assertEquals(1, repository.clearCount)
            assertEquals(listOf(backup), model.state.value.logs)
            assertEquals("Backup is read-only", model.state.value.error)
            assertFalse(model.state.value.isBusy)
        }

    @Test
    fun clearInvalidatesAlreadyLoadedLogContent() =
        runTest(dispatcher) {
            val repository = FakeRepository(logs = listOf(log))
            val model = CrashLogsViewModel(repository)
            advanceUntilIdle()
            model.openLog(log.id)
            advanceUntilIdle()
            model.clearLogs()
            advanceUntilIdle()
            assertTrue(model.state.value.logs.isEmpty())
            assertNull(model.state.value.openedLog)
            assertFalse(model.state.value.isBusy)
        }

    @Test
    fun cancelledReadCannotOpenAFileAfterClear() =
        runTest(dispatcher) {
            val release = CompletableDeferred<Unit>()
            val repository = FakeRepository(logs = listOf(log))
            repository.read = { entry ->
                withContext(NonCancellable) { release.await() }
                CrashLogContent(entry, "late result")
            }
            val model = CrashLogsViewModel(repository)
            advanceUntilIdle()
            model.openLog(log.id)
            runCurrent()
            model.clearLogs()
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertNull(model.state.value.openedLog)
            assertTrue(model.state.value.logs.isEmpty())
            assertFalse(model.state.value.isBusy)
        }

    @Test
    fun cancelledLoadCannotReplaceNewerResults() =
        runTest(dispatcher) {
            val release = CompletableDeferred<Unit>()
            val repository = FakeRepository(logs = listOf(log))
            var calls = 0
            repository.load = {
                if (calls++ == 0) {
                    withContext(NonCancellable) { release.await() }
                    listOf(log)
                } else emptyList()
            }
            val model = CrashLogsViewModel(repository)
            runCurrent()
            model.refresh()
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertTrue(model.state.value.logs.isEmpty())
            assertFalse(model.state.value.isBusy)
        }

    private class FakeRepository(var logs: List<CrashLogEntry>) : CrashLogsRepository {
        var loadFailure: Exception? = null
        var readFailure: Exception? = null
        var readCount = 0
        var clearCount = 0
        var load: suspend () -> List<CrashLogEntry> = { logs }
        var read: suspend (CrashLogEntry) -> CrashLogContent = {
            CrashLogContent(it, "stack trace")
        }
        var clear: suspend () -> Unit = { logs = emptyList() }

        override suspend fun loadLogs(): List<CrashLogEntry> {
            loadFailure?.let { throw it }
            return load()
        }

        override suspend fun readLog(id: String): CrashLogContent {
            readCount++
            readFailure?.let { throw it }
            return read(logs.first { it.id == id })
        }

        override suspend fun clearLogs() {
            clearCount++
            clear()
        }
    }
}
