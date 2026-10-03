package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ConfigSearchViewModel>()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        Dispatchers.resetMain()
    }

    private open class Sessions : ConfigSearchSessionRepository {
        @Volatile var value = ConfigSearchDraft()
        var failRead = false
        var failWrite = false

        override suspend fun read(id: String): ConfigSearchDraft {
            if (failRead) error("Read failed")
            return value
        }

        override suspend fun write(id: String, draft: ConfigSearchDraft) {
            if (failWrite) error("Write failed")
            if (draft.revision >= value.revision) value = draft
        }

        override suspend fun release(id: String) = Unit
    }

    private fun model(
        sessions: Sessions = Sessions(),
        saved: SavedStateHandle = SavedStateHandle(),
    ) = ConfigSearchViewModel(sessions, saved).also { models += it }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                runCurrent()
            }
        }

    @Test
    fun fullTextSelectionAndSearchModeRestorePrivatelyBeyondTheStoredRevision() = test {
        val large = "query".repeat(100000)
        val saved = SavedStateHandle()
        val sessions = Sessions()
        sessions.value = ConfigSearchDraft(System.nanoTime() + 1_000_000_000_000, large, 2, 7, true)
        val oldRevision = sessions.value.revision
        val first = model(sessions, saved)
        runCurrent()
        assertEquals(large, first.state.value.draft.text)
        assertTrue(first.state.value.draft.searching)
        first.text(large + "x", 3, 9)
        runCurrent()
        first.flush()
        first.stop()
        assertTrue(sessions.value.revision > oldRevision)
        val restored = model(sessions, copy(saved))
        runCurrent()
        assertEquals(large + "x", restored.state.value.draft.text)
        assertEquals(3, restored.state.value.draft.start)
        assertEquals(9, restored.state.value.draft.end)
        assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
    }

    @Test
    fun blankSearchIsIgnoredAndExactTrimmedQueryIsPersistedBeforeSingleDelivery() = test {
        val sessions = Sessions()
        val saved = SavedStateHandle()
        val vm = model(sessions, saved)
        runCurrent()
        vm.text("  ")
        vm.submit()
        runCurrent()
        assertNull(sessions.value.request)
        vm.text("  Exact Query  ")
        vm.submit()
        runCurrent()
        val request = sessions.value.request!!
        assertEquals("Exact Query", request.query)
        assertNull(vm.claim(request.token) { false })
        assertEquals(request, sessions.value.request)
        assertEquals(request, vm.claim(request.token) { true })
        assertNull(vm.claim(request.token) { true })
        assertEquals(request.token, saved.get<String>("config.search.consumed"))
        assertNull(sessions.value.request)
        vm.selected(request.token)
        runCurrent()
        assertEquals("", sessions.value.text)
        assertFalse(sessions.value.searching)
    }

    @Test
    fun lostConsumptionWriteCannotReplayAndStaleSelectionCannotClearAnotherQuery() = test {
        val sessions = Sessions()
        val saved = SavedStateHandle()
        val vm = model(sessions, saved)
        runCurrent()
        vm.searching(true)
        vm.text("First")
        vm.submit()
        runCurrent()
        val before = sessions.value
        val request = before.request!!
        vm.claim(request.token) { true }
        vm.stop()
        sessions.value =
            before // Simulate restoring the older disk alongside the newer small receipt.
        val restored = model(sessions, copy(saved))
        runCurrent()
        assertNull(restored.state.value.draft.request)
        restored.text("Second")
        restored.selected(request.token)
        runCurrent()
        assertEquals("Second", sessions.value.text)
        assertTrue(sessions.value.searching)
    }

    @Test
    fun initializationAndSubmissionFailuresRequireExplicitRetryWithoutLosingThePendingQuery() =
        test {
            val sessions = Sessions().apply { failRead = true }
            val vm = model(sessions)
            runCurrent()
            vm.text("ignored")
            assertFalse(vm.state.value.loaded)
            assertEquals("", vm.state.value.draft.text)
            sessions.failRead = false
            vm.retry()
            runCurrent()
            vm.text("Exact")
            runCurrent()
            sessions.failWrite = true
            vm.submit()
            runCurrent()
            assertNotNull(vm.state.value.error)
            val request = vm.state.value.draft.request!!
            vm.text("ignored")
            assertEquals("Exact", vm.state.value.draft.text)
            sessions.failWrite = false
            vm.retry()
            runCurrent()
            assertEquals(request, sessions.value.request)
            assertEquals(request, vm.claim(request.token) { true })
        }

    @Test
    fun canceledCrossDispatcherClaimRestoresDurableRequestBeforeAnotherOwnerCanRetry() = test {
        val entered = CompletableDeferred<Unit>()
        val gate = CountDownLatch(1)
        val sessions =
            object : Sessions() {
                override suspend fun write(id: String, draft: ConfigSearchDraft) =
                    withContext(Dispatchers.IO + NonCancellable) {
                        super.write(id, draft)
                        if (draft.request == null && draft.owner != null) {
                            entered.complete(Unit)
                            check(gate.await(10, TimeUnit.SECONDS))
                        }
                    }
            }
        val saved = SavedStateHandle()
        val vm = model(sessions, saved)
        try {
            vm.state.first { it.loaded }
            vm.text("Exact")
            runCurrent()
            vm.submit()
            vm.state.first { !it.saving && it.draft.request != null }
            val request = vm.state.value.draft.request!!
            var delivered: ConfigSearchRequest? = null
            val claim = launch { delivered = vm.claim(request.token) { true } }
            entered.await()
            claim.cancel()
            gate.countDown()
            claim.join()
            assertNull(delivered)
            assertEquals(request, sessions.value.request)
            assertNull(saved.get<String>("config.search.consumed"))
            vm.stop()
            val restored = model(sessions, copy(saved))
            restored.state.first { it.loaded }
            assertEquals(request, restored.state.value.draft.request)
        } finally {
            gate.countDown()
        }
    }
}
