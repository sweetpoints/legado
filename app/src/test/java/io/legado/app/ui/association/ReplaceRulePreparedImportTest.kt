package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceRulePreparedImportTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private class Repo : ReplaceRuleImportRepository {
        val values = mutableMapOf<String, ReplaceRuleImportSession>()
        var readCount = 0; var input = ""; var staged: String? = null; var gate: CompletableDeferred<Unit>? = null
        val released = CompletableDeferred<String>()
        override suspend fun read(source: String): List<ReplaceRuleImportItem> { readCount++; input = source; return listOf(ReplaceRuleImportItem("0", "Rule", source, ReplaceRuleImportStatus.New)) }
        override suspend fun edit(key: String, code: String) = ReplaceRuleImportItem(key, "Rule", code, ReplaceRuleImportStatus.New)
        override suspend fun restore(session: String) = values[session]
        override suspend fun stage(session: String, items: List<ReplaceRuleImportItem>) { staged = session; gate?.await(); values[session] = ReplaceRuleImportSession(items) }
        override suspend fun release(session: String) { values.remove(session); released.complete(session) }
        override suspend fun groups() = emptyList<String>()
        override suspend fun insert(session: String, items: List<ReplaceRuleImportItem>, selected: Set<String>, group: String, add: Boolean) { values[session] = ReplaceRuleImportSession(items, true) }
    }
    @Test fun preparedLargePayloadRestoresWithoutSourceOrSecondParserReadAndSavedContainsOnlyTicket() = runTest(dispatcher) {
        val repo = Repo(); val bridge = AppReplaceRulePreparedImportRepository(repo)
        val large = "Q".repeat(2000000); val id = bridge.prepare(large); val saved = SavedStateHandle()
        val model = ImportReplaceRuleViewModel(repo, saved, "", id); runCurrent()
        assertFalse(model.state.value.finished); assertEquals(large, model.state.value.items.single().json); assertEquals(1, repo.readCount)
        val restored = ImportReplaceRuleViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), "", id); runCurrent()
        assertEquals(large, restored.state.value.items.single().json); assertEquals(1, repo.readCount)
        assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 }); model.cancel(); restored.cancel(); runCurrent()
    }
    @Test fun missingPreparedSessionNeverParsesFallbackSourceAndIsRetryable() = runTest(dispatcher) {
        val repo = Repo(); val id = UUID.randomUUID().toString(); val model = ImportReplaceRuleViewModel(repo, SavedStateHandle(), "Wrong fallback", id)
        runCurrent(); assertEquals(0, repo.readCount); assertNotNull(model.state.value.error); assertFalse(model.state.value.finished)
        repo.values[id] = ReplaceRuleImportSession(listOf(ReplaceRuleImportItem("0", "Exact", "{}", ReplaceRuleImportStatus.New)))
        model.load(); runCurrent(); assertEquals("Exact", model.state.value.items.single().name); model.cancel(); runCurrent()
    }
    @Test fun cancellationAfterAcceptedStageCleansOnlyItsOwnSessionAndPreservesNeighbor() = runTest(dispatcher) {
        val repo = Repo(); val neighbor = UUID.randomUUID().toString(); repo.values[neighbor] = ReplaceRuleImportSession(emptyList())
        val gate = CompletableDeferred<Unit>(); repo.gate = gate; val job = launch { AppReplaceRulePreparedImportRepository(repo).prepare("Exact input") }
        runCurrent(); val owned = checkNotNull(repo.staged); job.cancel(); gate.complete(Unit)
        job.join(); assertFalse(repo.values.containsKey(owned)); assertTrue(repo.values.containsKey(neighbor)); assertEquals(owned, repo.released.await())
    }
    @Test fun legacyEmptySourceStillCancelsAndLegacyNonEmptySourceStillParses() = runTest(dispatcher) {
        val repo = Repo(); val empty = ImportReplaceRuleViewModel(repo, SavedStateHandle(), ""); runCurrent(); assertTrue(empty.state.value.finished)
        val legacy = ImportReplaceRuleViewModel(repo, SavedStateHandle(), "Legacy"); runCurrent()
        assertEquals("Legacy", repo.input); assertEquals(1, repo.readCount); legacy.cancel(); runCurrent()
    }
}
