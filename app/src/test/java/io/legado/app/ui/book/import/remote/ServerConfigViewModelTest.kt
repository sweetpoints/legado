package io.legado.app.ui.book.import.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.entities.Server
import io.legado.app.data.repository.RemoteServerEditorRepository
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ServerConfigViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun model(repo: Repository, saved: SavedStateHandle = SavedStateHandle()) =
        ServerConfigViewModel(repo, saved, 7).also { stores += ViewModelStore().apply { put("server", it) } }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { stores.forEach(ViewModelStore::clear); stores.clear(); runCurrent() }
    }
    @Test fun loadProjectsWebdavFieldsAndSavePreservesIdentityAndSort() = test {
        val repo = Repository(); val model = model(repo); runCurrent()
        assertEquals("old", model.state.value.draft.username)
        model.edit(ServerConfigField.Password, "new"); model.edit(ServerConfigField.Name, "changed")
        model.save(); runCurrent()
        val saved = repo.saved.single(); assertEquals(7L, saved.id); assertEquals(12, saved.sortNumber)
        assertEquals("changed", saved.name); assertEquals("new", GSON.fromJsonObject<Map<String,String>>(saved.config).getOrThrow()["password"])
        assertTrue(model.state.value.finished)
    }
    @Test fun allDraftFieldsRestoreWithoutReloadOrSaving() = test {
        val repo = Repository(); val handle = SavedStateHandle(); val first = model(repo, handle); runCurrent()
        ServerConfigField.entries.forEach { first.edit(it, it.name) }
        val restored = model(repo, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })); runCurrent()
        assertEquals(first.state.value.draft, restored.state.value.draft); assertEquals(1, repo.loads); assertTrue(repo.saved.isEmpty())
    }
    @Test fun failedLoadAndMalformedConfigBlockWritesUntilRetry() = test {
        val repo = Repository().apply { loadFailure = true }; val model = model(repo); runCurrent()
        model.edit(ServerConfigField.Name, "overwrite"); model.save(); runCurrent()
        assertTrue(model.state.value.loadFailed); assertTrue(repo.saved.isEmpty())
        repo.loadFailure = false; repo.server.config = "invalid"; model.load(); runCurrent()
        assertTrue(model.state.value.loadFailed); model.save(); assertTrue(repo.saved.isEmpty())
        repo.server.config = "{}"; model.load(); runCurrent(); assertFalse(model.state.value.loadFailed)
        model.save(); runCurrent(); assertEquals(1, repo.saved.size)
    }
    @Test fun failedSaveKeepsDraftAndAllowsRetry() = test {
        val repo = Repository().apply { saveFailure = true }; val model = model(repo); runCurrent()
        model.edit(ServerConfigField.Url, "https://new"); model.save(); runCurrent()
        assertFalse(model.state.value.finished); assertFalse(model.state.value.saving); assertNotNull(model.state.value.error)
        assertEquals("https://new", model.state.value.draft.url)
        repo.saveFailure = false; model.save(); runCurrent(); assertTrue(model.state.value.finished)
    }
    @Test fun savingRejectsDuplicateSaveEditAndClose() = test {
        val repo = Repository(); val model = model(repo); runCurrent(); repo.saveGate = CompletableDeferred()
        model.save(); model.save(); model.edit(ServerConfigField.Name, "late"); model.close(); runCurrent()
        assertTrue(model.state.value.saving); assertEquals("Name", model.state.value.draft.name); assertFalse(model.state.value.finished)
        repo.saveGate!!.complete(Unit); runCurrent(); assertEquals(1, repo.saved.size)
    }
    @Test fun cancelledDraftNeverWritesAndRestoredFinishedStateDoesNotReload() = test {
        val repo = Repository(); val handle = SavedStateHandle(); val model = model(repo, handle); runCurrent()
        model.edit(ServerConfigField.Name, "draft"); model.close(); model.save(); runCurrent()
        val restored = model(repo, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })); runCurrent()
        assertTrue(restored.state.value.finished); assertEquals(1, repo.loads); assertTrue(repo.saved.isEmpty())
    }
    @Test fun closeWhileLoadingRejectsLateUncancellableResult() = test {
        val repo = Repository().apply { loadGate = CompletableDeferred() }; val model = model(repo); runCurrent()
        model.close(); repo.loadGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.finished); assertEquals("", model.state.value.draft.name); assertTrue(repo.saved.isEmpty())
    }
    private class Repository : RemoteServerEditorRepository {
        var server = Server(7, "Name", config = "{\"url\":\"https://old\",\"username\":\"old\",\"password\":\"secret\"}", sortNumber = 12)
        var loads = 0; var loadFailure = false; var saveFailure = false
        var loadGate: CompletableDeferred<Unit>? = null; var saveGate: CompletableDeferred<Unit>? = null
        val saved = mutableListOf<Server>()
        override suspend fun load(id: Long?): Server { loads++; withContext(NonCancellable) { loadGate?.await() }; if (loadFailure) error("load failed"); return server.copy() }
        override suspend fun save(server: Server) { saveGate?.await(); if (saveFailure) error("save failed"); saved += server.copy() }
    }
}
