package io.legado.app.ui.book.source.manage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookSourcePart
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookSourceManagerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BookSourceManagerViewModel>()

    @Before
    fun prepareDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun closeModels() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model(
        repository: FakeRepository,
        store: MemoryStore = MemoryStore(),
    ): BookSourceManagerViewModel {
        return BookSourceManagerViewModel(repository, store, FakePreferences(), dispatcher).also {
            models += it
        }
    }

    @Test
    fun hiddenCheckedSourcesSurviveFilteringButBulkTargetsStayVisible() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val manager = model(repository)
            runCurrent()
            manager.selectAll()
            runCurrent()
            manager.query("Beta")
            runCurrent()
            assertEquals(setOf("a", "b", "c"), manager.state.value.selected)
            assertEquals(listOf("b"), manager.state.value.rows.map { it.url })
            manager.mutate(SourceMutation.DISABLE)
            runCurrent()
            assertEquals(listOf(listOf("b")), repository.mutations.map { it.keys })
            manager.invert()
            runCurrent()
            assertEquals(setOf("a", "c"), manager.state.value.selected)
            manager.query("")
            runCurrent()
            assertEquals(setOf("a", "c"), manager.state.value.selected)
        }

    @Test
    fun intervalNoSelectionIsSafeAndVisibleIntervalKeepsHiddenChecks() =
        runTest(dispatcher) {
            val manager = model(FakeRepository())
            runCurrent()
            manager.interval()
            runCurrent()
            assertTrue(manager.state.value.selected.isEmpty())
            manager.toggle("a")
            manager.toggle("c")
            runCurrent()
            manager.interval()
            runCurrent()
            assertEquals(setOf("a", "b", "c"), manager.state.value.selected)
        }

    @Test
    fun frozenDeleteSelectionAndDraftRestoreOutsideSavedStateBundle() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val store = MemoryStore()
            val manager = model(repository, store)
            runCurrent()
            manager.toggle("b")
            runCurrent()
            manager.open(SourceManagerDialog.ADD_GROUP)
            runCurrent()
            val largeDraft = "group".repeat(20_000)
            manager.draft(largeDraft)
            runCurrent()
            val restored = model(repository, store)
            runCurrent()
            assertEquals(largeDraft, restored.state.value.draft)
            assertEquals(SourceManagerDialog.ADD_GROUP, restored.state.value.dialog)
            assertEquals(setOf("b"), restored.state.value.selected)
            restored.confirm()
            runCurrent()
            assertEquals(listOf("b"), repository.mutations.single().keys)
            assertEquals(largeDraft, repository.mutations.single().value)
            val saved = SavedStateHandle()
            BookSourceManagerViewModel.token(saved)
            assertEquals(setOf("manager-session"), saved.keys())
            assertTrue(saved.get<String>("manager-session")!!.length < 100)
        }

    @Test
    fun failedMutationPreservesDialogAndAcceptedMutationRejectsRapidDoubleSubmit() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val manager = model(repository)
            runCurrent()
            manager.open(SourceManagerDialog.DELETE, "a")
            runCurrent()
            repository.failMutation = true
            manager.confirm()
            runCurrent()
            assertNotNull(manager.state.value.error)
            assertEquals(SourceManagerDialog.DELETE, manager.state.value.dialog)
            repository.failMutation = false
            repository.mutationGate = CompletableDeferred()
            manager.confirm()
            manager.confirm()
            runCurrent()
            assertTrue(manager.state.value.busy)
            assertEquals(2, repository.mutations.size)
            repository.mutationGate!!.complete(Unit)
            runCurrent()
            assertFalse(manager.state.value.busy)
            assertNull(manager.state.value.dialog)
        }

    @Test
    fun acceptedMutationCompletesItsReceiptAfterViewModelCancellation() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val store = MemoryStore()
            val manager = model(repository, store)
            runCurrent()
            repository.mutationGate = CompletableDeferred()
            manager.mutate(SourceMutation.TOP, listOf("a"))
            runCurrent()
            assertTrue(store.session.pendingOperation)
            manager.viewModelScope.cancel()
            repository.mutationGate!!.complete(Unit)
            runCurrent()
            assertFalse(store.session.pendingOperation)
            assertEquals(1, repository.mutations.size)
        }

    @Test
    fun interruptedOperationRestoresWarningWithoutReplayingSideEffect() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val store =
                MemoryStore().apply { session = SourceManagerSession(pendingOperation = true) }
            val manager = model(repository, store)
            runCurrent()
            assertTrue(manager.state.value.error!!.contains("被中断"))
            assertTrue(repository.mutations.isEmpty())
        }

    @Test
    fun exportUsesExactVisibleSortedSelectionAndReceiptSurvivesRecreation() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val store = MemoryStore()
            val manager = model(repository, store)
            runCurrent()
            manager.selectAll()
            manager.query("Beta")
            runCurrent()
            manager.export(false)
            runCurrent()
            assertEquals(listOf("b"), repository.exportKeys)
            val pending = manager.state.value.effect!!
            val restored = model(repository, store)
            runCurrent()
            assertEquals(pending.id, restored.state.value.effect!!.id)
            assertTrue(restored.acceptEffect(pending.id))
            assertFalse(restored.acceptEffect(pending.id))
            val completed = model(repository, store)
            runCurrent()
            assertNull(completed.state.value.effect)
            assertEquals(1, repository.exportCount)
        }

    @Test
    fun descendingManualMoveReversesRelativeDirectionAndNonManualSortRejectsMove() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val manager = model(repository)
            runCurrent()
            manager.ascending()
            runCurrent()
            manager.move("c", "b", true)
            runCurrent()
            assertEquals(Triple("c", "b", false), repository.moves.single())
            manager.sort(BookSourceSort.Name)
            runCurrent()
            manager.move("c", "b", true)
            runCurrent()
            assertEquals(1, repository.moves.size)
        }

    @Test
    fun editingLargeDraftUpdatesImmediatelyBeforePendingDiskWritesRun() =
        runTest(dispatcher) {
            val store = MemoryStore()
            val manager = model(FakeRepository(), store)
            runCurrent()
            manager.open(SourceManagerDialog.IMPORT)
            runCurrent()
            manager.draft("first draft")
            val latest = "large input".repeat(20_000)
            manager.draft(latest)
            assertEquals(latest, manager.state.value.draft)
            runCurrent()
            assertEquals(latest, store.session.draft)
        }

    @Test
    fun selectingEveryPassedRowExportsOnlyPassedVisibleUrls() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            repository.rows.value =
                repository.rows.value.mapIndexed { index, row ->
                    row.copy(checkStatus = if (index == 0) "PASSED" else "FAILED")
                }
            val manager = model(repository)
            runCurrent()
            manager.showStatus()
            manager.status("PASSED")
            manager.selectAll()
            runCurrent()
            assertEquals(listOf("a"), manager.state.value.rows.map { it.url })
            manager.export(false)
            runCurrent()
            // The legacy 100%-selected optimization re-queried without this status filter and
            // exported a,b,c. The immutable selection snapshot deliberately exports only a.
            assertEquals(listOf("a"), repository.exportKeys)
        }

    @Test
    fun ownedSessionCleanupWaitsForAcceptedIoAndFencesLateWrites() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val store = MemoryStore()
            val manager = model(repository, store)
            val owner = ViewModelStore().apply { put("manager", manager) }
            runCurrent()
            repository.mutationGate = CompletableDeferred()
            manager.mutate(SourceMutation.TOP, listOf("a"))
            runCurrent()
            owner.clear()
            runCurrent()
            assertEquals(0, store.deletions)
            repository.mutationGate!!.complete(Unit)
            runCurrent()
            assertEquals(1, store.deletions)
            assertEquals(0, store.writesAfterDeletion)
            manager.draft("late input")
            manager.mutate(SourceMutation.DELETE, listOf("a"))
            runCurrent()
            assertEquals(1, repository.mutations.size)
            assertEquals(0, store.writesAfterDeletion)
        }

    @Test
    fun exportPromptAndPassphraseRestoreFullFeedbackAndCopyTheirImmutablePayload() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val store = MemoryStore()
            val manager = model(repository, store)
            runCurrent()
            val url = "https://export.example/" + "payload".repeat(10_000)
            manager.exportReturned(url)
            runCurrent()
            assertEquals(SourceManagerDialog.EXPORT_SUCCESS, manager.state.value.dialog)
            assertEquals("upload summary", manager.state.value.feedback!!.summary)
            manager.draft("edited display text")
            runCurrent()
            val restored = model(repository, store)
            runCurrent()
            assertEquals(url, restored.state.value.feedback!!.url)
            restored.confirm()
            runCurrent()
            assertEquals(url, restored.state.value.effect!!.key)
            restored.acceptEffect(restored.state.value.effect!!.id)
            restored.exportReturned(url)
            runCurrent()
            restored.showPassphrase()
            runCurrent()
            assertEquals(SourceManagerDialog.PASSPHRASE, restored.state.value.dialog)
            restored.draft("edited passphrase display")
            restored.confirm()
            runCurrent()
            assertEquals("encoded:$url", restored.state.value.effect!!.key)
        }

    @Test
    fun durableReceiptRechecksCurrentOwnerBeforeSynchronousNativeLaunch() =
        runTest(dispatcher) {
            val store = MemoryStore()
            val manager = model(FakeRepository(), store)
            runCurrent()
            manager.effect("search", "a")
            runCurrent()
            val effect = manager.state.value.effect!!
            var resumed = true
            var launches = 0
            store.onWrite = { snapshot -> if (effect.id in snapshot.receipts) resumed = false }
            assertFalse(manager.deliverEffect(effect.id, { resumed }, { launches++ }))
            assertEquals(0, launches)
            assertEquals(effect.id, manager.state.value.effect!!.id)
            assertFalse(effect.id in store.session.receipts)
            resumed = true
            store.onWrite = null
            assertTrue(manager.deliverEffect(effect.id, { resumed }, { launches++ }))
            assertEquals(1, launches)
            assertFalse(manager.deliverEffect(effect.id, { resumed }, { launches++ }))
        }

    private class MemoryStore : SourceManagerSessionStorage {
        var session = SourceManagerSession()
        var deletions = 0
        var writesAfterDeletion = 0
        var onWrite: ((SourceManagerSession) -> Unit)? = null

        override fun read() = session

        override fun write(session: SourceManagerSession) {
            if (deletions > 0) writesAfterDeletion++
            this.session = session
            onWrite?.invoke(session)
        }

        override fun delete() {
            deletions++
            session = SourceManagerSession()
        }
    }

    private class FakePreferences : SourceManagerPreferences {
        override var showStatus = false
        override var blockNavigation = false
    }

    private data class Mutation(
        val keys: List<String>,
        val action: SourceMutation,
        val value: String,
    )

    private class FakeRepository : BookSourceManagerRepository {
        val rows =
            MutableStateFlow(
                listOf(row("a", "Alpha", 0), row("b", "Beta", 1), row("c", "Charlie", 2))
            )
        val mutations = mutableListOf<Mutation>()
        val moves = mutableListOf<Triple<String, String, Boolean>>()
        var failMutation = false
        var mutationGate: CompletableDeferred<Unit>? = null
        var exportKeys = emptyList<String>()
        var exportCount = 0

        override fun sources(query: String) = rows.map { rows ->
            rows.filter { query.isEmpty() || it.name.contains(query) }
        }

        override suspend fun feedback(url: String) =
            SourceManagerFeedback(url, "upload summary", true)

        override suspend fun passphrase(url: String) = "encoded:$url"

        override suspend fun importHistory() = emptyList<String>()

        override suspend fun rememberImport(value: String) = Unit

        override suspend fun forgetImport(value: String) = Unit

        override fun allSources() = rows

        override fun groups() = MutableStateFlow(emptyList<String>())

        override fun counts() = MutableStateFlow(emptyMap<String, Int>())

        override suspend fun mutate(keys: List<String>, action: SourceMutation, value: String) {
            mutations += Mutation(keys.toList(), action, value)
            if (failMutation) error("write failed")
            mutationGate?.await()
        }

        override suspend fun move(key: String, target: String, after: Boolean) {
            moves += Triple(key, target, after)
        }

        override suspend fun export(keys: List<String>): SourceExport {
            exportKeys = keys.toList()
            exportCount++
            return SourceExport(File("source.json"), "source.json", "application/json")
        }

        override suspend fun resolve(keys: List<String>) = keys.map {
            BookSourcePart(bookSourceUrl = it)
        }

        companion object {
            fun row(url: String, name: String, order: Int) =
                SourceManagerRow(
                    url,
                    name,
                    null,
                    order,
                    true,
                    true,
                    true,
                    false,
                    false,
                    0,
                    0,
                    0,
                    "NEEDS_CHECK",
                    "",
                    "#",
                )
        }
    }
}
