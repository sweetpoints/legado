package io.legado.app.ui.dict.rule

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.DictionaryRuleManagementRepository
import io.legado.app.data.repository.DictionaryRuleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryManagerProtocolTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun wholeCommandValidationCannotMutateOrLaunchForeignIdentity() = runTest(dispatcher) {
        val model = DictionaryRuleManagementViewModel(Fake(), SavedStateHandle())
        runCurrent()
        var launches = 0
        for (args in listOf(
            mapOf("op" to "edit", "name" to "missing"),
            mapOf("op" to "toggle", "name" to "A", "unexpected" to true),
            mapOf("op" to "enabled", "name" to "A", "value" to "true"),
            mapOf("op" to "input", "value" to "private"),
        )) {
            try { dictionaryManagerCommand(args, model) { _, _ -> launches++ }; fail("Accepted invalid request") }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals(0, launches)
        assertTrue(model.state.value.selected.isEmpty())
        assertEquals("", model.state.value.onlineInput)
    }
    @Test fun cancelledNativePreviewRestoresOrderAndSelectionBeforeNewCommit() = runTest(dispatcher) {
        val repo = Fake()
        val model = DictionaryRuleManagementViewModel(repo, SavedStateHandle())
        runCurrent()
        fun command(op: String, vararg args: Pair<String, Any>) = dictionaryManagerCommand(mapOf("op" to op, *args), model) { _, _ -> }
        command("beginSlide", "name" to "A")
        command("slideTo", "name" to "B")
        assertEquals(setOf("A", "B"), model.state.value.selected)
        command("cancelSlide")
        assertTrue(model.state.value.selected.isEmpty())
        command("move", "from" to "A", "to" to "B")
        assertEquals(listOf("B", "A"), model.state.value.rules.map { it.name })
        command("cancelReorder")
        assertEquals(listOf("A", "B"), model.state.value.rules.map { it.name })
        assertTrue(repo.orders.isEmpty())
        command("move", "from" to "A", "to" to "B")
        command("finishReorder")
        runCurrent()
        assertEquals(listOf(listOf("B", "A")), repo.orders)
    }
    @Test fun presentationProjectionDoesNotLeakDictionaryDefinitionsOrPendingNativePayload() {
        val state = DictionaryRuleManagementUiState(
            rules = listOf(DictionaryRuleSnapshot("A", "secret URL rule", "secret show rule")),
            effect = DictionaryManagementEffect(DictionaryManagementEffectKind.ImportText, "private pending input"),
        )
        val projection = dictionaryManagerSnapshot(state, "binding", 1)
        assertEquals(listOf(mapOf("name" to "A", "enabled" to true)), projection["rules"])
        assertFalse(projection.toString().contains("secret"))
        assertFalse(projection.toString().contains("private pending"))
    }
    private class Fake : DictionaryRuleManagementRepository {
        val rows = MutableStateFlow(listOf(DictionaryRuleSnapshot("A"), DictionaryRuleSnapshot("B")))
        val orders = mutableListOf<List<String>>()
        override fun observe() = rows
        override suspend fun setEnabled(names: List<String>, enabled: Boolean) {}
        override suspend fun delete(names: List<String>) {}
        override suspend fun reorder(names: List<String>) { orders += names }
        override suspend fun importDefault() {}
        override suspend fun history() = emptyList<String>()
        override suspend fun rememberUrl(url: String) {}
        override suspend fun removeUrl(url: String) {}
        override suspend fun json(rules: List<DictionaryRuleSnapshot>) = "[]"
        override suspend fun shareFile(rules: List<DictionaryRuleSnapshot>) = "file"
        override suspend fun exportSummary(url: String) = "summary"
        override suspend fun passphrase(url: String) = "phrase"
    }
}
