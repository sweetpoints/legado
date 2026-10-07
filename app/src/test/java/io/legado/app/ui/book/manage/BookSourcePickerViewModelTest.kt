package io.legado.app.ui.book.manage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookSourcePickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BookSourcePickerViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model(
        repo: BookSourcePickerRepository,
        saved: SavedStateHandle = SavedStateHandle(),
    ) = BookSourcePickerViewModel(repo, saved).also { models += it }

    private fun restored(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun querySwitchAndRoomUpdatesReplaceVisibleRowsAndRestoreSearch() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            assertEquals("Alpha (group)", vm.state.value.items.single().displayName)
            vm.search("Beta")
            runCurrent()
            assertTrue(vm.state.value.items.isEmpty())
            repo.rows.value = listOf(BookSourcePickerItem("b", "Beta", null))
            runCurrent()
            assertEquals("Beta", vm.state.value.items.single().displayName)
            val next = model(repo, restored(saved))
            runCurrent()
            assertEquals("Beta", next.state.value.query)
            assertEquals("b", next.state.value.items.single().url)
        }

    @OptIn(InternalCoroutinesApi::class)
    @Test
    fun anOldCollectorCannotOverwriteARevisitedQueryOrReportItsLateFailure() =
        runTest(dispatcher) {
            val gates = mutableListOf<CompletableDeferred<Unit>>()
            val queries = mutableListOf<String>()
            val repo =
                object : BookSourcePickerRepository {
                    override fun observe(query: String): Flow<List<BookSourcePickerItem>> =
                        object : Flow<List<BookSourcePickerItem>> {
                            override suspend fun collect(
                                collector: FlowCollector<List<BookSourcePickerItem>>
                            ) {
                                val index = gates.size
                                queries += query
                                val gate = CompletableDeferred<Unit>().also { gates += it }
                                // An external database/network callback can outlive
                                // cancellation; generation, not query equality, owns it.
                                withContext(NonCancellable) {
                                    gate.await()
                                    collector.emit(
                                        listOf(BookSourcePickerItem("$index", query, null))
                                    )
                                }
                            }
                        }

                    override suspend fun source(url: String): String? = null

                    override suspend fun delay() = 0

                    override suspend fun saveDelay(value: Int) = Unit
                }
            val vm = model(repo)
            runCurrent()
            vm.search("A")
            runCurrent()
            vm.search("B")
            runCurrent()
            vm.search("A")
            runCurrent()
            gates[3].complete(Unit)
            runCurrent()
            assertEquals("3", vm.state.value.items.single().url)
            gates[1].complete(Unit)
            runCurrent()
            assertEquals("3", vm.state.value.items.single().url)
            gates[0].complete(Unit)
            gates[2].complete(Unit)
            runCurrent()
            assertEquals(listOf("", "A", "B", "A"), queries)
            assertNull(vm.state.value.error)
        }

    @Test
    fun newSearchRemovesSelectableRowsFromThePreviousQueryImmediately() =
        runTest(dispatcher) {
            val vm = model(Fake())
            runCurrent()
            assertTrue(vm.state.value.items.isNotEmpty())
            vm.search("Beta")
            assertEquals("Beta", vm.state.value.query)
            assertTrue(vm.state.value.items.isEmpty())
            assertTrue(vm.state.value.loading)
        }

    @Test
    fun fullPayloadConsumedOnceAndFinishedRestoreOnlyCloses() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.select("a")
            vm.select("a")
            runCurrent()
            assertEquals(1, repo.reads)
            assertEquals("full:a", vm.consumeSource())
            assertNull(vm.consumeSource())
            val next = model(repo, restored(saved))
            runCurrent()
            assertTrue(next.state.value.finished)
            assertNull(next.consumeSource())
            assertEquals(1, repo.reads)
        }

    @Test
    fun pendingSelectionRestoreReloadsPayloadWithoutBundlingLargeSource() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.select("a")
            runCurrent()
            assertFalse(saved.keys().any { saved.get<Any?>(it) == "full:a" })
            val next = model(repo, restored(saved))
            runCurrent()
            assertEquals("full:a", next.consumeSource())
            assertTrue(next.state.value.finished)
        }

    @Test
    fun cancelDiscardsUncooperativeLateSelectionAndRestoresClosed() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.gate = CompletableDeferred()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.select("a")
            runCurrent()
            vm.cancel()
            repo.gate!!.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.finished)
            assertNull(vm.consumeSource())
            val next = model(repo, restored(saved))
            runCurrent()
            assertTrue(next.state.value.finished)
            assertEquals(1, repo.reads)
        }

    @Test
    fun deletedSourceClosesWithoutCallbackAndReadFailureAllowsRetry() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            repo.failSource = true
            vm.select("a")
            runCurrent()
            assertFalse(vm.state.value.finished)
            assertEquals("read failed", vm.state.value.error)
            repo.failSource = false
            repo.missing = true
            vm.select("a")
            runCurrent()
            assertTrue(vm.state.value.finished)
            assertNull(vm.consumeSource())
        }

    @Test
    fun delayBoundsDraftRestoreAndCancelDoNotWritePreferences() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.openDelay()
            runCurrent()
            assertEquals("17", vm.state.value.delayDraft)
            vm.delayDraft("9999")
            vm.stepDelay(1)
            assertEquals(9999, vm.state.value.validDelay)
            vm.delayDraft("0")
            vm.stepDelay(-1)
            assertEquals(0, vm.state.value.validDelay)
            vm.delayDraft("123")
            val next = model(repo, restored(saved))
            runCurrent()
            assertTrue(next.state.value.delayOpen)
            assertEquals("123", next.state.value.delayDraft)
            assertEquals(1, repo.delayReads)
            next.closeDelay()
            assertTrue(repo.writes.isEmpty())
        }

    @Test
    fun delayValidationAndFailedWritePreserveDraftThenSuccessfulSaveClosesEditor() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.openDelay()
            runCurrent()
            listOf("", "-1", "10000", "abc").forEach {
                vm.delayDraft(it)
                vm.saveDelay()
                runCurrent()
                assertNull(vm.state.value.validDelay)
            }
            assertTrue(repo.writes.isEmpty())
            vm.delayDraft("42")
            repo.failSave = true
            vm.saveDelay()
            runCurrent()
            assertEquals("42", vm.state.value.delayDraft)
            assertTrue(vm.state.value.delayOpen)
            assertEquals("write failed", vm.state.value.error)
            repo.failSave = false
            vm.saveDelay()
            runCurrent()
            assertEquals(listOf(42), repo.writes)
            assertFalse(vm.state.value.delayOpen)
        }

    @Test
    fun lateInitialPreferenceCannotOverwriteTypedDraft() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.delayGate = CompletableDeferred()
            val vm = model(repo)
            runCurrent()
            vm.openDelay()
            runCurrent()
            vm.delayDraft("84")
            repo.delayGate!!.complete(Unit)
            runCurrent()
            assertEquals("84", vm.state.value.delayDraft)
            assertFalse(vm.state.value.delayLoading)
        }

    private class Fake : BookSourcePickerRepository {
        val rows = MutableStateFlow(listOf(BookSourcePickerItem("a", "Alpha", "group")))
        var reads = 0
        var delayReads = 0
        val writes = mutableListOf<Int>()
        var gate: CompletableDeferred<Unit>? = null
        var delayGate: CompletableDeferred<Unit>? = null
        var missing = false
        var failSource = false
        var failSave = false

        override fun observe(query: String) = rows.map { items ->
            items.filter { query.isEmpty() || it.displayName.contains(query) }
        }

        override suspend fun source(url: String): String? {
            ++reads
            withContext(NonCancellable) { gate?.await() }
            if (failSource) error("read failed")
            return if (missing) null else "full:$url"
        }

        override suspend fun delay(): Int {
            ++delayReads
            withContext(NonCancellable) { delayGate?.await() }
            return 17
        }

        override suspend fun saveDelay(value: Int) {
            if (failSave) error("write failed")
            writes += value
        }
    }
}
