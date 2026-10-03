package io.legado.app.ui.login

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SourceLoginHostViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<SourceLoginHostViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val request = SourceLoginRequest(type = "rssSource", key = "K".repeat(2000000), bookUrl = "Exact book")
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private class Repo : SourceLoginRepository {
        var loads = 0; var failure = false; var form = false; var missing = false; var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(request: SourceLoginRequest): SourceLoginSnapshot {
            loads++; withContext(NonCancellable) { gate?.await() }; if (failure) error("Source failed")
            return SourceLoginSnapshot(if (missing) null else RssSource(sourceUrl = request.key.orEmpty(), sourceName = "Exact source",
                loginUi = if (form) "[{\"name\":\"user\"}]" else null), null, null, request.bookType,
                mapOf("Auth" to "Value"), mapOf("user" to "Stored"))
        }
    }
    private class Sessions : SourceLoginSessionRepository {
        var value: SourceLoginRequest? = null; var failure = false; var gate: CompletableDeferred<Unit>? = null
        override suspend fun read(id: String): SourceLoginRequest? { withContext(NonCancellable) { gate?.await() }; return value }
        override suspend fun write(id: String, request: SourceLoginRequest) { if (failure) error("Disk failed"); value = request }
        override suspend fun release(id: String) { value = null }
    }
    private fun model(repo: Repo = Repo(), sessions: Sessions = Sessions(), saved: SavedStateHandle = SavedStateHandle()) =
        SourceLoginHostViewModel(repo, sessions, saved).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun gate() = CompletableDeferred<Unit>().also { gates += it }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { models.forEach { it.stop() }; gates.forEach { it.complete(Unit) }; runCurrent() }
    }
    @Test fun largeEntryRestoresExactIdentityAndFormInputsWithOnlySmallSavedUuid() = test {
        val saved = SavedStateHandle(); val sessions = Sessions(); val first = model(sessions = sessions, saved = saved)
        first.bind(request); runCurrent(); assertTrue(first.state.value.loaded); first.stop()
        val restored = model(sessions = sessions, saved = copy(saved)); restored.bind(); runCurrent()
        assertEquals(request, restored.inputs()!!.first); assertEquals(mapOf("Auth" to "Value"), restored.inputs()!!.second.headers)
        assertEquals(mapOf("user" to "Stored"), restored.inputs()!!.second.loginInfo)
        assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
    }
    @Test fun repeatedRotationBindDoesNotReloadSourceOrRepeatInitialization() = test {
        val repo = Repo().apply { gate = gate() }; val model = model(repo)
        model.bind(request); runCurrent(); model.bind(request); model.bind(); runCurrent(); assertEquals(1, repo.loads)
        repo.gate!!.complete(Unit); runCurrent(); model.bind(); model.bind(request.copy()); runCurrent(); assertEquals(1, repo.loads)
    }
    @Test fun privateWriteFailureCanRetryWithoutLosingInitialSeedOrRunningScriptsEarly() = test {
        val repo = Repo(); val sessions = Sessions().apply { failure = true }; val model = model(repo, sessions)
        model.bind(request); runCurrent(); assertNotNull(model.state.value.error); assertEquals(0, repo.loads)
        sessions.failure = false; model.retry(); runCurrent(); assertTrue(model.state.value.loaded); assertEquals(request, sessions.value)
    }
    @Test fun canceledNonCooperativeSourceCannotPublishAfterOwnerStops() = test {
        val repo = Repo().apply { gate = gate() }; val model = model(repo)
        model.bind(request); runCurrent(); model.stop(); repo.gate!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.loaded); assertNull(model.inputs())
    }
    @Test fun canceledPrivateReadCannotStartSourceInitialization() = test {
        val sessions = Sessions().apply { value = request; gate = gate() }; val repo = Repo(); val model = model(repo, sessions)
        model.bind(); runCurrent(); model.stop(); sessions.gate!!.complete(Unit); runCurrent()
        assertEquals(0, repo.loads); assertFalse(model.state.value.loaded)
    }
    @Test fun formRoutingAndMissingEntryAreExplicitWithoutFakeReadyStates() = test {
        val form = model(Repo().apply { form = true }); form.bind(request); runCurrent(); assertTrue(form.state.value.form)
        val missing = model(Repo().apply { missing = true }); missing.bind(request); runCurrent()
        assertTrue(missing.state.value.missing); assertFalse(missing.state.value.loaded)
        val noInput = model(); noInput.bind(); runCurrent(); assertTrue(noInput.state.value.missing)
    }
    @Test fun changingEntryClearsOldSnapshotDuringLoadingFailureAndPublishesOnlyMatchingNewInput() = test {
        val repo = Repo(); val model = model(repo); model.bind(request); runCurrent()
        assertEquals(request, model.inputs()!!.first)
        val next = request.copy(key = "New source")
        repo.gate = gate(); model.bind(next); runCurrent(); assertNull(model.inputs())
        repo.failure = true; repo.gate!!.complete(Unit); runCurrent(); assertNull(model.inputs()); assertNotNull(model.state.value.error)
        repo.failure = false; model.retry(); runCurrent()
        assertEquals(next, model.inputs()!!.first); assertEquals(next.key, model.inputs()!!.second.source!!.getKey())
    }

}
