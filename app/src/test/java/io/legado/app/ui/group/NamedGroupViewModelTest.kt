package io.legado.app.ui.group

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.NamedGroupRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class NamedGroupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<NamedGroupViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.forEach { it.stop() }; Dispatchers.resetMain() }
    private class Repository : NamedGroupRepository {
        val rows = MutableStateFlow(listOf("One", "Two"))
        val added = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String?>>()
        var gate: CompletableDeferred<Unit>? = null
        var failure = false
        override fun groups() = rows
        override suspend fun add(name: String) {
            withContext(NonCancellable) { gate?.await() }
            if (failure) error("disk")
            added += name
        }
        override suspend fun rename(original: String, replacement: String?) {
            if (failure) error("disk")
            renamed += original to replacement
        }
    }
    private fun model(repository: Repository, saved: SavedStateHandle = SavedStateHandle()) =
        NamedGroupViewModel(repository, saved).also { models += it }
    @Test fun addsOnlyNonblankNamesAndKeepsExactInput() = runTest(dispatcher) {
        val repository = Repository(); val model = model(repository); runCurrent()
        model.add(); model.name(" "); model.confirm(); runCurrent()
        assertTrue(repository.added.isEmpty()); assertFalse(model.state.value.editing)
        model.add(); model.name(" Exact name "); model.confirm(); runCurrent()
        assertEquals(listOf(" Exact name "), repository.added)
    }
    @Test fun restoredRenameTargetsCapturedNameRatherThanNewListIndex() = runTest(dispatcher) {
        val repository = Repository(); val saved = SavedStateHandle(); val first = model(repository, saved); runCurrent()
        first.edit("Two"); first.name("Changed")
        val restored = model(repository, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        repository.rows.value = listOf("New", "One", "Two"); runCurrent(); restored.confirm(); runCurrent()
        assertEquals(listOf("Two" to "Changed"), repository.renamed)
    }
    @Test fun blankRenameRemovesMembershipWhileCancelAndUnknownNamesDoNothing() = runTest(dispatcher) {
        val repository = Repository(); val model = model(repository); runCurrent()
        model.edit("Unknown"); model.delete("Unknown"); assertFalse(model.state.value.editing)
        model.edit("One"); model.name("Changed"); model.cancelEdit(); model.confirm()
        assertTrue(repository.renamed.isEmpty())
        model.edit("One"); model.name(""); model.confirm(); runCurrent()
        model.delete("Two"); runCurrent()
        assertEquals(listOf("One" to "", "Two" to null), repository.renamed)
    }
    @Test fun failedMutationRetainsDraftAndAllowsExplicitRetry() = runTest(dispatcher) {
        val repository = Repository(); val model = model(repository); runCurrent()
        model.edit("One"); model.name("New"); repository.failure = true; model.confirm(); runCurrent()
        assertEquals("disk", model.state.value.error); assertTrue(model.state.value.editing); assertFalse(model.state.value.busy)
        repository.failure = false; model.confirm(); runCurrent()
        assertEquals(listOf("One" to "New"), repository.renamed); assertFalse(model.state.value.editing)
    }
    @Test fun activeMutationRejectsRepeatedActionsAndNoncooperativeResultCannotPublishAfterStop() = runTest(dispatcher) {
        val repository = Repository().apply { gate = CompletableDeferred() }; val model = model(repository); runCurrent()
        model.add(); model.name("New"); model.confirm(); runCurrent()
        model.confirm(); model.delete("One"); model.cancelEdit(); model.name("Ignored")
        val before = model.state.value; assertTrue(before.busy); assertEquals("New", before.name)
        model.stop(); repository.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf("New"), repository.added); assertEquals(before, model.state.value)
    }
}
