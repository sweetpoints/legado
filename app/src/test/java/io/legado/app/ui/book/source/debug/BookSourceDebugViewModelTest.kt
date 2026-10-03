package io.legado.app.ui.book.source.debug

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BookSourceDebugViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BookSourceDebugViewModel>()
    private val repos = mutableListOf<Fake>()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        models.forEach {
            it.stop()
            it.viewModelScope.cancel()
        }
        repos.forEach {
            it.loadGate?.complete(Unit)
            it.acquireGate?.complete(Unit)
            it.sortsGate?.complete(Unit)
            it.releaseGate?.complete(Unit)
            it.leases.forEach { lease -> lease.done.complete(Unit) }
        }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        BookSourceDebugViewModel(repo, saved, "url").also {
            models += it
            repos += repo
        }

    private fun snapshot(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun firstOpenLoadsHelpAndCategoriesWithoutAutomaticallyExecuting() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            assertTrue(vm.state.value.loaded)
            assertTrue(vm.state.value.help)
            assertEquals("Name", vm.state.value.name)
            assertEquals(
                listOf("Category::https://category", "Other::https://other"),
                vm.state.value.sorts.map { it.query },
            )
            assertTrue(repo.leases.isEmpty())
        }

    @Test
    fun missingAndFailedLoadDoNotRunAndRetryCannotLoseTyping() =
        runTest(dispatcher) {
            val missing = Fake()
            missing.missing = true
            val absent = model(missing)
            runCurrent()
            assertTrue(absent.state.value.missing)
            assertTrue(missing.leases.isEmpty())
            val repo = Fake()
            repo.failLoad = true
            val vm = model(repo)
            runCurrent()
            assertNotNull(vm.state.value.error)
            vm.query("ignored")
            assertEquals("", vm.state.value.query)
            repo.failLoad = false
            vm.retry()
            runCurrent()
            vm.query("accepted", 3)
            assertEquals("accepted", vm.state.value.query)
            assertEquals(3, vm.state.value.queryStart)
        }

    @Test
    fun stoppedNonCooperativeLoadFailureDoesNotPublishAnyState() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.loadGate = CompletableDeferred()
            repo.failLoad = true
            val vm = model(repo)
            runCurrent()
            vm.stop()
            val before = vm.state.value
            repo.loadGate!!.complete(Unit)
            runCurrent()
            assertEquals(before, vm.state.value)
            assertTrue(repo.leases.isEmpty())
        }

    @Test
    fun explicitSearchChangesHelpLoadingAndSeparatesHtmlEventsFromSelectableLog() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.run("我的")
            runCurrent()
            val lease = repo.leases.single()
            assertFalse(vm.state.value.help)
            assertTrue(vm.state.value.running)
            assertEquals("我的", lease.query)
            lease.event(BookSourceDebugEvent(10, "<list>"))
            lease.event(BookSourceDebugEvent(20, "<book>"))
            lease.event(BookSourceDebugEvent(30, "<toc>"))
            lease.event(BookSourceDebugEvent(40, "<content>"))
            lease.event(BookSourceDebugEvent(1, "log"))
            assertEquals("log", vm.state.value.output)
            assertEquals("<list>", vm.html(BookSourceDebugStage.Search))
            assertEquals("<content>", vm.html(BookSourceDebugStage.Content))
            assertTrue(vm.state.value.hasSearchHtml)
            assertTrue(vm.state.value.hasBookHtml)
            assertTrue(vm.state.value.hasTocHtml)
            assertEquals("<book>", vm.html(BookSourceDebugStage.Book))
            assertEquals("<toc>", vm.html(BookSourceDebugStage.Toc))
            assertTrue(vm.state.value.hasContentHtml)
            lease.done.complete(Unit)
            runCurrent()
            assertFalse(vm.state.value.running)
            assertEquals(1, lease.closes)
        }

    @Test
    fun nullQueryUsesOriginalDefaultAndSortSelectionRunsExactNameUrlAndTerminalClearsSpinner() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.run(null)
            runCurrent()
            assertEquals("我的", repo.leases.single().query)
            repo.leases.single().done.complete(Unit)
            runCurrent()
            vm.sort(1)
            runCurrent()
            assertEquals("Other::https://other", vm.state.value.query)
            assertEquals(1, vm.state.value.selectedSort)
            assertEquals("Other::https://other", repo.leases.last().query)
            repo.leases.last().event(BookSourceDebugEvent(-1, "parse failed"))
            repo.leases.last().done.complete(Unit)
            runCurrent()
            assertEquals("parse failed", vm.state.value.output)
            assertFalse(vm.state.value.running)
        }

    @Test
    fun rerunJoinsPriorExecutionBeforeAcquiringSuccessorAndDropsLateHtmlLogAndCompletion() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.run("old")
            runCurrent()
            val old = repo.leases.single()
            old.event(BookSourceDebugEvent(1, "old log"))
            vm.run("new")
            runCurrent()
            assertEquals(1, old.closes)
            assertEquals(1, repo.leases.size)
            assertTrue(vm.state.value.running)
            old.event(BookSourceDebugEvent(20, "late html"))
            old.event(BookSourceDebugEvent(1, "late log"))
            old.done.complete(Unit)
            runCurrent()
            assertEquals(
                listOf("acquire", "run:old", "close", "finished", "acquire", "run:new"),
                repo.events,
            )
            assertFalse(vm.state.value.hasBookHtml)
            assertFalse(vm.state.value.hasContentHtml)
            assertEquals("", vm.state.value.output)
            repo.leases.last().event(BookSourceDebugEvent(1, "new log"))
            repo.leases.last().done.complete(Unit)
            runCurrent()
            assertEquals("new log", vm.state.value.output)
        }

    @Test
    fun busyChannelReportsWithoutCancelingOtherOwnerAndExplicitRetryCanRun() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.busy = true
            val vm = model(repo)
            runCurrent()
            vm.run("query")
            runCurrent()
            assertEquals(BookSourceDebugIssue.Busy, vm.state.value.issue)
            assertFalse(vm.state.value.running)
            assertTrue(repo.leases.isEmpty())
            repo.busy = false
            vm.run()
            runCurrent()
            repo.leases.single().done.complete(Unit)
            runCurrent()
            assertNull(vm.state.value.issue)
        }

    @Test
    fun diskRestoresLargeHtmlQueryCursorAndBoundedLogsWithoutReexecutingInterruptedRun() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.run("query")
            runCurrent()
            val old = repo.leases.single()
            val html = "large".repeat(200000)
            old.event(BookSourceDebugEvent(10, html))
            old.event(BookSourceDebugEvent(1, "log".repeat(10000)))
            vm.help(true)
            vm.query("draft query", 2, 4)
            runCurrent()
            vm.flush()
            val before = snapshot(saved)
            vm.stop()
            old.done.complete(Unit)
            runCurrent()
            val restored = model(repo, before)
            runCurrent()
            assertEquals(html, restored.html(BookSourceDebugStage.Search))
            assertEquals(20000, restored.state.value.output.length)
            assertEquals("draft query", restored.state.value.query)
            assertEquals(2, restored.state.value.queryStart)
            assertEquals(4, restored.state.value.queryEnd)
            assertTrue(restored.state.value.help)
            assertEquals(BookSourceDebugIssue.Interrupted, restored.state.value.issue)
            assertFalse(restored.state.value.running)
            assertEquals(1, repo.leases.size)
            assertTrue(
                saved.keys().all {
                    (saved.get<Any?>(it) as? String)?.length?.let { it < 1000 } != false
                }
            )
        }

    @Test
    fun closeAndCancellationDuringAcquireReleaseOnlyOwnedLeaseAndIgnoreLateCallbacks() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.run("query")
            runCurrent()
            val lease = repo.leases.single()
            lease.event(BookSourceDebugEvent(1, "before"))
            vm.close()
            assertEquals(1, lease.closes)
            assertTrue(vm.state.value.closed)
            lease.event(BookSourceDebugEvent(1, "late"))
            lease.done.complete(Unit)
            runCurrent()
            assertEquals("before", vm.state.value.output)
            val slow = Fake()
            slow.acquireGate = CompletableDeferred()
            val canceled = model(slow)
            runCurrent()
            canceled.run()
            runCurrent()
            canceled.stop()
            slow.acquireGate!!.complete(Unit)
            runCurrent()
            assertEquals(1, slow.leases.single().closes)
            assertEquals(0, slow.leases.single().runs)
        }

    @Test
    fun exceptionPreservesPartialLogAndNextRunKeepsPreviousStageHtmlUntilNewResponse() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.run("query")
            runCurrent()
            val lease = repo.leases.single()
            lease.event(BookSourceDebugEvent(1, "partial"))
            lease.event(BookSourceDebugEvent(10, "html"))
            lease.done.completeExceptionally(IllegalStateException("execution failed"))
            runCurrent()
            assertEquals("partial", vm.state.value.output)
            assertEquals("execution failed", vm.state.value.error)
            assertEquals(1, lease.closes)
            vm.run("again")
            runCurrent()
            assertEquals("", vm.state.value.output)
            assertEquals("html", vm.html(BookSourceDebugStage.Search))
            repo.leases.last().done.complete(Unit)
            runCurrent()
        }

    @Test
    fun shortPrefixInputsDoNotRunAndLongUrlRunsExactlyOnePrefixedQuery() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.prefix("++")
            assertEquals("++", vm.state.value.query)
            assertTrue(repo.leases.isEmpty())
            vm.query("x")
            vm.prefix("--")
            assertEquals("--", vm.state.value.query)
            assertTrue(repo.leases.isEmpty())
            vm.query("https://chapter")
            vm.prefix("--")
            runCurrent()
            assertEquals("--https://chapter", repo.leases.single().query)
            repo.leases.single().done.complete(Unit)
            runCurrent()
            vm.prefix("--")
            runCurrent()
            assertEquals("--https://chapter", repo.leases.last().query)
            repo.leases.last().done.complete(Unit)
            runCurrent()
        }

    @Test
    fun detailIgnoresBlankAndQrRunsExactQueryWithoutAutomaticExecution() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.detail()
            assertTrue(repo.leases.isEmpty())
            vm.query("https://details")
            vm.detail()
            runCurrent()
            assertEquals("https://details", repo.leases.single().query)
            repo.leases.single().done.complete(Unit)
            runCurrent()
            vm.run("--https://qr-chapter")
            runCurrent()
            assertEquals("--https://qr-chapter", repo.leases.last().query)
            repo.leases.last().done.complete(Unit)
            runCurrent()
        }

    @Test
    fun exploreErrorsDisableExecutionAndLateRefreshCannotOverrideFreshKindsOrStopDebug() =
        runTest(dispatcher) {
            val errorRepo = Fake()
            errorRepo.sorts = listOf(BookSourceDebugSort("ERROR:parse failed", "trace"))
            val errorVm = model(errorRepo)
            runCurrent()
            assertFalse(errorVm.state.value.help)
            assertTrue(errorVm.state.value.output.contains("获取发现出错"))
            errorVm.sort(0)
            assertTrue(errorRepo.leases.isEmpty())
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.run("still running")
            runCurrent()
            val lease = repo.leases.single()
            lease.event(BookSourceDebugEvent(1, "old"))
            val gate = CompletableDeferred<Unit>()
            repo.sortsGate = gate
            repo.sorts = listOf(BookSourceDebugSort("old", "old"))
            vm.refreshExplore()
            runCurrent()
            assertTrue(vm.state.value.help)
            assertEquals("", vm.state.value.output)
            repo.sorts = listOf(BookSourceDebugSort("fresh", "fresh"))
            repo.sortsGate = null
            vm.refreshExplore()
            runCurrent()
            assertEquals("fresh", vm.state.value.sorts.single().name)
            assertTrue(vm.state.value.running)
            assertEquals(0, lease.closes)
            gate.complete(Unit)
            runCurrent()
            assertEquals("fresh", vm.state.value.sorts.single().name)
            lease.done.complete(Unit)
            runCurrent()
        }

    @Test
    fun explicitCloseCleansOwnedHtmlAndLateFlushCannotRecreateButRotationStopRetainsRecord() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.run("query")
            runCurrent()
            val lease = repo.leases.single()
            lease.event(BookSourceDebugEvent(40, "private html"))
            runCurrent()
            vm.flush()
            val session = saved.get<String>("book.debug.session")!!
            vm.stop()
            lease.done.complete(Unit)
            runCurrent()
            assertEquals("private html", repo.records[session]!!.contentHtml)
            assertTrue(repo.released.isEmpty())
            val reopened = model(repo, snapshot(saved))
            runCurrent()
            reopened.close()
            reopened.viewModelScope.cancel()
            runCurrent()
            assertTrue(session in repo.released)
            assertFalse(repo.records.containsKey(session))
            assertTrue(runCatching { reopened.flush() }.isFailure)
        }

    @Test
    fun closingDuringNonCooperativeInitializationCannotCreateAnyRecordAfterCleanup() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.loadGate = CompletableDeferred()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.close()
            vm.viewModelScope.cancel()
            runCurrent()
            val before = vm.state.value
            repo.loadGate!!.complete(Unit)
            runCurrent()
            assertEquals(before, vm.state.value)
            assertTrue(repo.records.isEmpty())
            assertEquals(setOf(saved.get<String>("book.debug.session")), repo.released)
        }

    @Test
    fun restoredClosedReceiptRetriesCleanupWithoutLoadingOrExecuting() =
        runTest(dispatcher) {
            val repo = Fake()
            val session = "closed-session"
            repo.records[session] = BookSourceDebugRecord("url", contentHtml = "private")
            val saved =
                SavedStateHandle(
                    mapOf("book.debug.session" to session, "book.debug.closed" to true)
                )
            val vm = model(repo, saved)
            runCurrent()
            assertTrue(vm.state.value.closed)
            assertTrue(repo.records.isEmpty())
            assertEquals(setOf(session), repo.released)
            assertTrue(repo.leases.isEmpty())
        }

    @Test
    fun failedLatestDraftWriteRetriesCurrentPayloadWithoutRepeatingDebugExecution() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            repo.failWrite = true
            vm.query("full draft")
            runCurrent()
            assertEquals("disk full", vm.state.value.error)
            assertEquals("full draft", vm.state.value.query)
            assertTrue(repo.leases.isEmpty())
            repo.failWrite = false
            vm.retry()
            runCurrent()
            assertNull(vm.state.value.error)
            assertEquals("full draft", repo.records.values.single().query)
            assertTrue(repo.leases.isEmpty())
        }

    private class Fake : BookSourceDebugRepository {
        var failWrite = false
        var missing = false
        var failLoad = false
        var busy = false
        var loadGate: CompletableDeferred<Unit>? = null
        var acquireGate: CompletableDeferred<Unit>? = null
        val released = mutableSetOf<String>()
        var releaseGate: CompletableDeferred<Unit>? = null
        val records = mutableMapOf<String, BookSourceDebugRecord>()
        val leases = mutableListOf<Lease>()
        val events = mutableListOf<String>()

        override suspend fun load(key: String): BookSourceDebugSnapshot? {
            loadGate?.let { withContext(NonCancellable) { it.await() } }
            if (failLoad) error("load failed")
            return if (missing) null else BookSourceDebugSnapshot(key, "Name", "json")
        }

        var sorts =
            listOf(
                BookSourceDebugSort("Category", "https://category"),
                BookSourceDebugSort("Other", "https://other"),
            )
        var sortsGate: CompletableDeferred<Unit>? = null

        override suspend fun sorts(
            source: BookSourceDebugSnapshot,
            refresh: Boolean,
        ): List<BookSourceDebugSort> {
            val result = sorts
            if (refresh) sortsGate?.let { withContext(NonCancellable) { it.await() } }
            return result
        }

        override suspend fun acquire(
            source: BookSourceDebugSnapshot,
            event: (BookSourceDebugEvent) -> Unit,
        ): BookSourceDebugLease? {
            acquireGate?.await()
            if (busy) return null
            events += "acquire"
            return Lease(event, events).also { leases += it }
        }

        override suspend fun read(session: String) = records[session]

        override suspend fun write(session: String, record: BookSourceDebugRecord) {
            if (failWrite) error("disk full")
            check(session !in released)
            if (record.revision >= (records[session]?.revision ?: -1)) records[session] = record
        }

        override suspend fun release(session: String) {
            releaseGate?.await()
            released += session
            records.remove(session)
        }
    }

    private class Lease(
        val event: (BookSourceDebugEvent) -> Unit,
        private val events: MutableList<String>,
    ) : BookSourceDebugLease {
        val done = CompletableDeferred<Unit>()
        var query = ""
        var runs = 0
        var closes = 0

        override suspend fun run(query: String) =
            withContext(NonCancellable) {
                this@Lease.query = query
                runs++
                events += "run:$query"
                try {
                    done.await()
                } finally {
                    events += "finished"
                }
            }

        override fun close() {
            if (closes == 0) {
                closes++
                events += "close"
            }
        }

        override suspend fun awaitStopped() = Unit
    }
}
