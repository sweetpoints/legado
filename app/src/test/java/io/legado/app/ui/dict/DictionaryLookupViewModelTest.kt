package io.legado.app.ui.dict

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryLookupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun model(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        word: String? = "甲乙",
    ) = DictionaryLookupViewModel(repo, saved, word, dispatcher)

    @Test
    fun firstEnabledRuleQueriesTheExactWord() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo)
            runCurrent()
            assertEquals(listOf("a" to "甲乙"), repo.queries)
            assertEquals("a:甲乙", model.state.value.document?.text)
        }

    @Test
    fun emptyWordNeverLoadsRulesOrQueries() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo, word = "")
            runCurrent()
            assertTrue(model.state.value.invalidWord)
            assertEquals(0, repo.ruleReads)
            assertTrue(repo.queries.isEmpty())
        }

    @Test
    fun noEnabledRulesStopsLoadingWithoutQuery() =
        runTest(dispatcher) {
            val repo = Fake().apply { values = emptyList() }
            val model = model(repo)
            runCurrent()
            assertFalse(model.state.value.loading)
            assertTrue(model.state.value.rules.isEmpty())
            assertTrue(repo.queries.isEmpty())
        }

    @Test
    fun switchingRuleCancelsAndIgnoresLateResult() =
        runTest(dispatcher) {
            val repo = Fake()
            val old = CompletableDeferred<String>()
            repo.searching = { rule, _ ->
                if (rule.name == "a") withContext(NonCancellable) { old.await() } else "fresh"
            }
            val model = model(repo)
            runCurrent()
            model.select("b")
            runCurrent()
            assertEquals("fresh", model.state.value.document?.text)
            old.complete("obsolete")
            runCurrent()
            assertEquals("b", model.state.value.selected)
            assertEquals("fresh", model.state.value.document?.text)
        }

    @Test
    fun lateFailureDoesNotReplaceCurrentResultOrError() =
        runTest(dispatcher) {
            val repo = Fake()
            val old = CompletableDeferred<Unit>()
            repo.searching = { rule, _ ->
                if (rule.name == "a") {
                    withContext(NonCancellable) { old.await() }
                    error("obsolete error")
                } else "fresh"
            }
            val model = model(repo)
            runCurrent()
            model.select("b")
            runCurrent()
            old.complete(Unit)
            runCurrent()
            assertNull(model.state.value.error)
            assertEquals("fresh", model.state.value.document?.text)
        }

    @Test
    fun selectedDictionaryAndCompletedResultRestoreWithoutDuplicateRequest() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val original = model(repo, saved)
            runCurrent()
            original.select("b")
            runCurrent()
            val restored =
                model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
            runCurrent()
            assertEquals("b", restored.state.value.selected)
            assertEquals("b:甲乙", restored.state.value.document?.text)
            assertEquals(2, repo.queries.size)
        }

    @Test
    fun changedRuleInvalidatesRestoredResult() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            model(repo, saved)
            runCurrent()
            repo.values = repo.values.map { it.copy(urlRule = "new-url") }
            model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
            runCurrent()
            assertEquals(2, repo.queries.size)
        }

    @Test
    fun queryFailureCanRetryAndTabReselectionDoesNotRepeatRequest() =
        runTest(dispatcher) {
            val repo = Fake().apply { searching = { _, _ -> error("request failed") } }
            val model = model(repo)
            runCurrent()
            assertEquals("request failed", model.state.value.error)
            assertFalse(model.state.value.loading)
            repo.searching = { _, _ -> "recovered" }
            model.retry()
            runCurrent()
            model.select("a")
            runCurrent()
            assertEquals("recovered", model.state.value.document?.text)
            assertEquals(2, repo.queries.size)
        }

    @Test
    fun buttonAndImageActionsUseCurrentRuleAndExactScript() =
        runTest(dispatcher) {
            val repo =
                Fake().apply {
                    searching = { _, _ ->
                        "<button>查词@onclick:java.toast('甲乙')</button><img src='https://example.org/x.png,{\"click\":\"java.toast(2)\"}'>"
                    }
                }
            val model = model(repo)
            runCurrent()
            val urls = model.state.value.document!!.actions.keys.toList()
            urls.forEach { assertTrue(model.action(it)) }
            runCurrent()
            assertEquals(
                listOf(
                    Triple("a", "button 查词", "java.toast('甲乙')"),
                    Triple("a", "image", "java.toast(2)"),
                ),
                repo.clicks,
            )
        }

    @Test
    fun unknownActionAndStaleDocumentActionNeverExecuteScript() =
        runTest(dispatcher) {
            val repo =
                Fake().apply { searching = { _, _ -> "<button>go@onclick:script()</button>" } }
            val model = model(repo)
            runCurrent()
            val old = model.state.value.document!!.actions.keys.single()
            assertFalse(model.action("https://dictionary-action.invalid/unknown/0"))
            model.select("b")
            runCurrent()
            assertFalse(model.action(old))
            assertTrue(repo.clicks.isEmpty())
        }

    @Test
    fun oversizedResultIsNotAddedToSavedStateBundle() =
        runTest(dispatcher) {
            val repo = Fake().apply { searching = { _, _ -> "x".repeat(70_000) } }
            val saved = SavedStateHandle()
            val model = model(repo, saved)
            runCurrent()
            assertNotNull(model.state.value.document)
            assertFalse(saved.contains("dictionary.lookup.result.content"))
        }

    @Test
    fun staleRulesLoadCannotOverrideNewerLoad() =
        runTest(dispatcher) {
            val repo = Fake()
            val old = CompletableDeferred<List<DictionaryRuleSnapshot>>()
            var reads = 0
            repo.readRules = {
                if (++reads == 1) withContext(NonCancellable) { old.await() }
                else listOf(DictionaryRuleSnapshot("b"))
            }
            val model = model(repo)
            runCurrent()
            model.loadRules()
            runCurrent()
            old.complete(listOf(DictionaryRuleSnapshot("old")))
            runCurrent()
            assertEquals(listOf("b"), model.state.value.rules.map { it.name })
            assertEquals("b", model.state.value.selected)
        }

    private class Fake : DictionaryLookupRepository {
        var values =
            listOf(DictionaryRuleSnapshot("a", "url-a"), DictionaryRuleSnapshot("b", "url-b"))
        var ruleReads = 0
        val queries = mutableListOf<Pair<String, String>>()
        val clicks = mutableListOf<Triple<String, String, String>>()
        var readRules: suspend () -> List<DictionaryRuleSnapshot> = { values }
        var searching: suspend (DictionaryRuleSnapshot, String) -> String = { rule, word ->
            "${rule.name}:$word"
        }

        override suspend fun rules(): List<DictionaryRuleSnapshot> {
            ruleReads++
            return readRules()
        }

        override suspend fun search(rule: DictionaryRuleSnapshot, word: String): String {
            queries += rule.name to word
            return searching(rule, word)
        }

        override suspend fun click(rule: DictionaryRuleSnapshot, name: String, script: String) {
            clicks += Triple(rule.name, name, script)
        }

        override suspend fun image(source: String) = DictionaryImageData(byteArrayOf(), "image/png")
    }
}
