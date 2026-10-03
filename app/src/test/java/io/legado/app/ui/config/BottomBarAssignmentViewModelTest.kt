package io.legado.app.ui.config

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BottomBarAssignmentViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<BottomBarAssignmentViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Fake : BottomBarAssignmentRepository {
        var data = BottomBarAssignmentImages(listOf("bookshelf_selected.png", "bookshelf_normal.png", "other.png"),
            AppBottomBarAssignmentRepository.slots.map { BottomBarAssignmentSlot(it, if (it == "bookshelf") "bookshelf_selected.png" else null, if (it == "bookshelf") "bookshelf_normal.png" else null) })
        var discardFail = false; var gate: CompletableDeferred<Unit>? = null; var nonCooperative = false; var fail = false; var discarded = 0; var saves = 0
        var submitted: List<BottomBarAssignmentSlot>? = null; var name = ""; var edit: String? = null
        override suspend fun load(session: String, sizePx: Int): BottomBarAssignmentImages {
            if (nonCooperative) withContext(NonCancellable) { gate?.await() } else gate?.await(); return data
        }
        override suspend fun preview(session: String, image: String, sizePx: Int): Bitmap? = null
        override suspend fun save(session: String, name: String, editName: String?, slots: List<BottomBarAssignmentSlot>): String {
            saves++; gate?.await(); if (fail) error("Invalid")
            submitted = slots; this.name = name; edit = editName; return "Actual name"
        }
        override suspend fun discard(session: String) { discarded++; if (discardFail) error("discard failed") }
    }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle(), session: String = "session") =
        BottomBarAssignmentViewModel(repo, saved, session, "Old", "Initial", 56).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.forEach { it.stop() }; runCurrent() } }
    @Test fun prefillIsLoadedAndOnlyOneSelectedSlotCanSaveWithOriginalEditContract() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); assertEquals(4, model.state.value.slots.size)
        model.name(" New ", 5, 1); model.save(); model.save(); runCurrent()
        assertEquals(1, repo.saves); assertEquals("New", repo.name); assertEquals("Old", repo.edit)
        assertEquals(1, repo.submitted!!.count { it.selected != null }); assertTrue(model.state.value.saved)
        assertEquals(BottomBarAssignmentIssue.Closed, model.state.value.pendingClose); assertEquals(0, repo.discarded)
    }
    @Test fun clearingSelectedClearsNormalAndEmptySaveDoesNotStartTransaction() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); model.palette("bookshelf", false); model.choose(null)
        assertNull(model.state.value.slots.first().normal); model.palette("bookshelf", true); assertNull(model.state.value.palette)
        model.save(); runCurrent(); assertEquals(0, repo.saves); assertEquals(BottomBarAssignmentIssue.NeedSelected, model.state.value.issue)
    }
    @Test fun paletteRejectsUnknownIdsAndNormalClearingKeepsSelected() = test {
        val model = model(Fake()); runCurrent(); model.palette("bookshelf", true); model.choose("../outside.png")
        assertEquals("bookshelf", model.state.value.palette); assertEquals("bookshelf_normal.png", model.state.value.slots.first().normal)
        model.choose(null); assertNull(model.state.value.slots.first().normal); assertEquals("bookshelf_selected.png", model.state.value.slots.first().selected)
    }
    @Test fun processRestorePreservesExplicitClearsPaletteNameAndSelectionInsteadOfPrefill() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent()
        first.name("Renamed", 6, 2); first.palette("bookshelf", false); first.choose(null); first.palette("home", false)
        first.stop(); val restored = model(repo, copy(saved)); runCurrent()
        assertNull(restored.state.value.slots.first().selected); assertNull(restored.state.value.slots.first().normal)
        assertEquals("home", restored.state.value.palette); assertEquals("Renamed", restored.state.value.name)
        assertEquals(6, restored.state.value.start); assertEquals(2, restored.state.value.end)
        assertTrue(saved.keys().all { saved.get<Any?>(it) !is Bitmap })
    }
    @Test fun savingBlocksCloseAndEditsAndFailureLeavesDraftForRetry() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); repo.gate = CompletableDeferred(); repo.fail = true
        model.save(); runCurrent(); model.close(); model.name("Ignored", 0, 0); model.palette("home", false)
        assertEquals("Initial", model.state.value.name); assertEquals(0, repo.discarded)
        repo.gate!!.complete(Unit); runCurrent(); assertFalse(model.state.value.busy); assertEquals(BottomBarAssignmentIssue.Invalid, model.state.value.issue)
        repo.fail = false; model.name("Retry", 5, 5); model.save(); runCurrent(); assertTrue(model.state.value.saved); assertEquals("Retry", repo.name)
    }
    @Test fun closeDiscardsExactlyOnceAndRestoredFinishedStateNeverReloadsOrReplaysConsumedEffect() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent(); first.close(); first.close(); runCurrent()
        assertEquals(1, repo.discarded); first.delivered(BottomBarAssignmentIssue.Invalid); assertNotNull(first.state.value.pendingClose)
        first.delivered(BottomBarAssignmentIssue.Closed); val restored = model(repo, copy(saved)); runCurrent()
        assertTrue(restored.state.value.finished); assertFalse(restored.state.value.loaded); assertNull(restored.state.value.pendingClose)
    }
    @Test fun lateNonCooperativeLoadAfterCloseCannotResurrectSelections() = test {
        val repo = Fake(); repo.gate = CompletableDeferred(); repo.nonCooperative = true
        val model = model(repo); runCurrent(); model.close(); runCurrent(); repo.gate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.finished); assertFalse(model.state.value.loaded); assertEquals(1, repo.discarded)
    }
    @Test fun missingSessionAndEmptyImagesHaveDifferentTerminalMessages() = test {
        val invalid = model(Fake(), session = ""); runCurrent(); assertEquals(BottomBarAssignmentIssue.Invalid, invalid.state.value.pendingClose)
        val repo = Fake(); repo.data = repo.data.copy(images = emptyList()); val empty = model(repo); runCurrent()
        assertEquals(BottomBarAssignmentIssue.NoImages, empty.state.value.pendingClose); assertEquals(1, repo.discarded)
    }
    @Test fun hugePasteNeverEntersSavedStateAndAllValidUnicodeNamesKeepExactInput() = test {
        val saved = SavedStateHandle(); val model = model(Fake(), saved); runCurrent()
        val valid = "😀".repeat(60)
        assertTrue(io.legado.app.help.BottomBarSkinFormat.isValidSkinName(valid))
        model.name(valid, valid.length, 3); assertEquals(valid, saved.get<String>("barAssign.name"))
        model.name("Huge".repeat(100000), 9000, 9000)
        assertEquals(valid, model.state.value.name); assertEquals(valid, saved.get<String>("barAssign.name"))
        assertEquals(BottomBarAssignmentIssue.Invalid, model.state.value.issue)
        val initial = BottomBarAssignmentViewModel(Fake(), SavedStateHandle(), "session", null, "X".repeat(200000), 56).also { models += it }
        assertEquals(80, initial.state.value.name.length)
    }
    @Test fun discardFailureUnblocksHostAndCanRetryWithoutPublishingFinish() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); repo.discardFail = true
        model.close(); runCurrent(); assertFalse(model.state.value.busy); assertFalse(model.state.value.finished)
        assertEquals(BottomBarAssignmentIssue.Invalid, model.state.value.issue); assertNull(model.state.value.pendingClose)
        repo.discardFail = false; model.close(); runCurrent(); assertTrue(model.state.value.finished); assertEquals(2, repo.discarded)
    }

}
