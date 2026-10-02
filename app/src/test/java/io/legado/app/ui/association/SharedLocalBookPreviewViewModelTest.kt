package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SharedLocalBookPreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<SharedLocalBookPreviewViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Fake : SharedLocalBookPreviewRepository {
        var gate: CompletableDeferred<Unit>? = null; var fail = false; var uncooperative = false
        override suspend fun project(seeds: List<SharedLocalBookPreviewSeed>): List<SharedLocalBookPreviewRow> {
            if (uncooperative) withContext(NonCancellable) { gate?.await() } else gate?.await()
            if (fail) error("failed")
            return seeds.map { SharedLocalBookPreviewRow(FileSharedLocalBookPreviewRepository.id(it.uri), it.title ?: "File", "file.txt", it.directory, it.onBookshelf, "txt", "1KB", "Today") }
        }
    }
    private val input = listOf(SharedLocalBookPreviewSeed("file:///first.txt", "First"), SharedLocalBookPreviewSeed("file:///second.pdf", "Second"))
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = SharedLocalBookPreviewViewModel(repo, saved).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.forEach { it.stop() }; runCurrent() } }
    @Test fun initialBatchLoadsAllSourceSelectionAndBusyFlagsGuardEveryOperation() = test {
        val repo = Fake().apply { gate = CompletableDeferred() }; val model = model(repo)
        model.batch(input, input.map { it.uri }); runCurrent(); model.confirm(); assertNull(model.state.value.effect)
        repo.gate!!.complete(Unit); runCurrent(); assertEquals(2, model.state.value.selected.size)
        model.source(true, false); model.toggle(model.state.value.rows.first().id); model.selectAll(); model.confirm(); model.directory(); model.cancel()
        assertEquals(2, model.state.value.selected.size); assertNull(model.state.value.effect); assertFalse(model.state.value.finished)
        model.source(false, true); assertFalse(model.state.value.canAct); model.source(false, false); assertTrue(model.state.value.canAct)
    }
    @Test fun fileSelectionAndScrollRestoreOnlySmallIdsWithoutLargeTitlesOrUris() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved)
        val seeds = input.map { it.copy(title = "Large title ".repeat(10000)) }; first.batch(seeds, seeds.map { it.uri }); runCurrent()
        first.toggle(first.state.value.rows.first().id); first.scrolled(12, 31)
        val restored = model(repo, copy(saved)); restored.batch(seeds, seeds.map { it.uri }); runCurrent()
        assertEquals(first.state.value.selected, restored.state.value.selected); assertEquals(12 to 31, restored.scroll())
        assertTrue(saved.keys().mapNotNull { saved.get<Any?>(it) }.filterIsInstance<String>().all { it.length <= 64 })
        assertTrue(saved.keys().mapNotNull { saved.get<Any?>(it) }.filterIsInstance<ArrayList<*>>().flatMap { it.filterIsInstance<String>() }.all { it.length == 64 })
    }
    @Test fun selectAllOnlyAffectsSelectableRowsAndNoSelectionCannotImport() = test {
        val repo = Fake(); val model = model(repo); val seeds = input + SharedLocalBookPreviewSeed("file:///folder", "Folder", directory = true) + SharedLocalBookPreviewSeed("file:///existing", "Existing", onBookshelf = true)
        model.batch(seeds, seeds.map { it.uri }); runCurrent(); assertEquals(2, model.state.value.selected.size); assertEquals(2, model.state.value.selectableCount)
        model.selectAll(); assertTrue(model.state.value.selected.isEmpty()); model.confirm(); assertNull(model.state.value.effect)
        model.selectAll(); assertEquals(2, model.state.value.selected.size); model.toggle(model.state.value.rows.last().id); assertEquals(2, model.state.value.selected.size)
    }
    @Test fun importEffectIsExactOrderedSelectionRestorableAndConsumedOnlyOnce() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); first.batch(input, input.map { it.uri }); runCurrent()
        first.toggle(first.state.value.rows.last().id); first.confirm(); first.confirm(); first.directory()
        val effect = first.state.value.effect!!; assertEquals(SharedLocalBookPreviewAction.Import, effect.action); assertEquals(listOf(first.state.value.rows.first().id), effect.selection)
        val restored = model(repo, copy(saved)); assertNull(restored.consume(effect.id)); restored.batch(input, input.map { it.uri }); runCurrent()
        assertNull(restored.consume(effect.id + 1)); assertEquals(effect, restored.consume(effect.id)); assertNull(restored.consume(effect.id))
        restored.directory(); val next = restored.state.value.effect!!; assertTrue(next.id > effect.id); assertEquals(SharedLocalBookPreviewAction.Directory, next.action)
    }
    @Test fun changedBatchDropsStaleEffectAndSelectionAndStartsNewSourceSelection() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); first.batch(input, input.map { it.uri }); runCurrent(); first.scrolled(12, 4); first.confirm()
        val replacement = listOf(SharedLocalBookPreviewSeed("file:///replacement.epub", "New")); first.batch(replacement, replacement.map { it.uri }); runCurrent()
        assertNull(first.state.value.effect); assertEquals(setOf(FileSharedLocalBookPreviewRepository.id(replacement.single().uri)), first.state.value.selected); assertEquals(0 to 0, first.scroll())
    }
    @Test fun projectionErrorRetriesWithoutOverwritingDraftSelection() = test {
        val repo = Fake(); val model = model(repo); model.batch(input, input.map { it.uri }); runCurrent(); model.toggle(model.state.value.rows.first().id)
        repo.fail = true; model.load(); runCurrent(); assertEquals("failed", model.state.value.error)
        repo.fail = false; model.load(); runCurrent(); assertEquals(setOf(model.state.value.rows.last().id), model.state.value.selected)
    }
    @Test fun cancelAndStopRejectNonCooperativeLateProjectionAndCancelRestoresFinished() = test {
        val repo = Fake().apply { gate = CompletableDeferred(); uncooperative = true }; val saved = SavedStateHandle(); val model = model(repo, saved)
        model.batch(input, input.map { it.uri }); runCurrent(); model.cancel(); repo.gate!!.complete(Unit); runCurrent(); assertTrue(model.state.value.finished); assertFalse(model.state.value.loaded)
        val restored = model(repo, copy(saved)); restored.batch(input, input.map { it.uri }); runCurrent(); assertTrue(restored.state.value.finished); assertFalse(restored.state.value.loaded)
        val other = Fake().apply { gate = CompletableDeferred(); uncooperative = true }; val stopped = model(other); stopped.batch(input, input.map { it.uri }); runCurrent(); val before = stopped.state.value
        stopped.stop(); other.gate!!.complete(Unit); runCurrent(); assertEquals(before, stopped.state.value)
    }
}
