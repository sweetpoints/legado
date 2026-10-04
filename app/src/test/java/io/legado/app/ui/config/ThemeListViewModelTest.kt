package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun stableDeleteConfirmationRestoresAndConfirmsCapturedThemeAfterGlobalReorder() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = newModel(repo, saved)
            val store = owned(model)
            try {
                runCurrent()
                model.delete("one")
                repo.items = repo.items.reversed()
                val restored = newModel(repo, copy(saved))
                val other = owned(restored)
                try {
                    runCurrent()
                    assertEquals("one", restored.state.value.deleteKey)
                    restored.confirmDelete()
                    restored.confirmDelete()
                    runCurrent()
                    assertEquals(listOf("one"), repo.deleted)
                    assertEquals(listOf("two"), restored.state.value.items.map { it.key })
                } finally {
                    other.clear()
                }
            } finally {
                store.clear()
            }
        }

    @Test
    fun cancelDeleteAndChangedTargetCannotDeleteAnotherTheme() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                model.delete("one")
                model.cancelDelete()
                model.confirmDelete()
                assertTrue(repo.deleted.isEmpty())
                model.delete("one")
                repo.items = repo.items.filterNot { it.key == "one" }
                model.reload()
                runCurrent()
                assertNull(model.state.value.deleteKey)
                model.confirmDelete()
                assertTrue(repo.deleted.isEmpty())
            } finally {
                store.clear()
            }
        }

    @Test
    fun clipboardNullDoesNothingValidImportReloadsAndInvalidImportProducesOriginalFailureEvent() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                model.importClipboard()
                val request = model.state.value.event!!
                assertEquals(ThemeListEventKind.Clipboard, request.kind)
                model.consume(request)
                model.importText(null)
                assertTrue(repo.imports.isEmpty())
                model.importText("json")
                runCurrent()
                assertEquals(listOf("json"), repo.imports)
                repo.valid = false
                model.importText("bad")
                runCurrent()
                assertEquals(ThemeListEventKind.ImportFailed, model.state.value.event!!.kind)
            } finally {
                store.clear()
            }
        }

    @Test
    fun sharePayloadIsDurableBeforePendingEventAndSavedStateContainsNoLargeJson() =
        runTest(dispatcher) {
            val repo =
                Fake().apply {
                    gate = CompletableDeferred()
                    items = listOf(ThemeListItem("one", "One", "x".repeat(1200000), 0))
                }
            val saved = SavedStateHandle()
            val model = newModel(repo, saved)
            val store = owned(model)
            try {
                runCurrent()
                model.share("one")
                runCurrent()
                assertNull(model.state.value.event)
                assertTrue(model.state.value.busy)
                repo.gate!!.complete(Unit)
                runCurrent()
                val event = model.state.value.event!!
                assertEquals(1200000, model.sharePayload(event.receipt!!).length)
                assertTrue(saved.keys().none { saved.get<Any?>(it) == repo.items.single().json })
                val restoredSaved = copy(saved)
                val restored = newModel(repo, restoredSaved)
                val other = owned(restored)
                try {
                    runCurrent()
                    assertEquals(event, restored.state.value.event)
                    assertEquals(1200000, restored.sharePayload(event.receipt).length)
                    restored.consume(event)
                    val next = newModel(repo, copy(restoredSaved))
                    val last = owned(next)
                    try {
                        runCurrent()
                        assertNull(next.state.value.event)
                    } finally {
                        last.clear()
                    }
                } finally {
                    other.clear()
                }
            } finally {
                store.clear()
            }
        }

    @Test
    fun busyApplyRejectsDuplicateRequestsAndUsesCapturedItemDespiteListMutation() =
        runTest(dispatcher) {
            val repo = Fake().apply { gate = CompletableDeferred() }
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                model.apply("one")
                model.apply("two")
                runCurrent()
                repo.items = emptyList()
                repo.gate!!.complete(Unit)
                runCurrent()
                assertEquals(listOf("one-json"), repo.applied)
                assertFalse(model.state.value.busy)
            } finally {
                store.clear()
            }
        }

    @Test
    fun failedReadAllowsExplicitReloadAndOldLoadCannotOverrideNewestRows() =
        runTest(dispatcher) {
            val repo = Fake().apply { loadFails = true }
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                assertEquals("load", model.state.value.error)
                model.apply("one")
                assertTrue(repo.applied.isEmpty())
                repo.loadFails = false
                model.reload()
                runCurrent()
                assertNull(model.state.value.error)
                assertEquals(2, model.state.value.items.size)
            } finally {
                store.clear()
            }
        }

    @Test
    fun nonCooperativeLoadCannotPublishRowsOrErrorsAfterStop() =
        runTest(dispatcher) {
            val repo = Fake().apply { loadGate = CompletableDeferred() }
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                val before = model.state.value
                model.stop()
                repo.loadGate!!.complete(Unit)
                runCurrent()
                assertEquals(before, model.state.value)
                assertTrue(model.state.value.items.isEmpty())
            } finally {
                store.clear()
            }
        }

    @Test
    fun nonCooperativeShareStageCompletesItsFileButCannotPublishPendingEventAfterStop() =
        runTest(dispatcher) {
            val repo = Fake().apply { shareGate = CompletableDeferred() }
            val saved = SavedStateHandle()
            val model = newModel(repo, saved)
            val store = owned(model)
            try {
                runCurrent()
                model.share("one")
                runCurrent()
                val before = model.state.value
                model.stop()
                repo.shareGate!!.complete(Unit)
                runCurrent()
                assertEquals(before, model.state.value)
                assertNull(model.state.value.event)
                assertNull(saved.get<String>("event"))
                assertEquals(1, repo.payloads.size)
            } finally {
                store.clear()
            }
        }

    private fun newModel(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        ThemeListViewModel(repo, saved)

    private fun owned(model: ThemeListViewModel) = ViewModelStore().apply { put("themes", model) }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private class Fake : ThemeListRepository {
        var items =
            listOf(
                ThemeListItem("one", "One", "one-json", 0),
                ThemeListItem("two", "Two", "two-json", 0),
            )
        var loadGate: CompletableDeferred<Unit>? = null
        var shareGate: CompletableDeferred<Unit>? = null
        var valid = true
        var loadFails = false
        var gate: CompletableDeferred<Unit>? = null
        val deleted = mutableListOf<String>()
        val imports = mutableListOf<String>()
        val applied = mutableListOf<String>()
        val payloads = mutableMapOf<String, String>()

        override suspend fun list(): List<ThemeListItem> {
            if (loadGate != null) withContext(NonCancellable) { loadGate!!.await() }
            if (loadFails) error("load")
            return items.toList()
        }

        override suspend fun delete(item: ThemeListItem): Boolean {
            deleted += item.key
            items = items.filterNot { it.key == item.key }
            return true
        }

        override suspend fun add(json: String): Boolean {
            imports += json
            return valid
        }

        override suspend fun apply(item: ThemeListItem) {
            val json = item.json
            gate?.await()
            applied += json
        }

        override suspend fun stageShare(session: String, receipt: String, item: ThemeListItem) {
            if (shareGate != null)
                withContext(NonCancellable) {
                    shareGate!!.await()
                    payloads[receipt] = item.json
                }
            else {
                gate?.await()
                payloads[receipt] = item.json
            }
        }

        override suspend fun share(session: String, receipt: String) = payloads.getValue(receipt)
    }
}
