package io.legado.app.ui.autoTask

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AutoTaskImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<AutoTaskImportViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private fun item(key: String, status: AutoTaskImportStatus = AutoTaskImportStatus.New) =
        AutoTaskImportItem(
            key,
            "task$key",
            "Name$key",
            true,
            "*/30 * * * *",
            null,
            "large script".repeat(1000),
            status,
        )

    private inner class Fake : AutoTaskImportRepository {
        var items =
            listOf(
                item("0"),
                item("1", AutoTaskImportStatus.Update),
                item("2", AutoTaskImportStatus.Exists),
            )
        var gate: CompletableDeferred<Unit>? = null
        var editGate: CompletableDeferred<Unit>? = null
        var commitGate: CompletableDeferred<Unit>? = null
        var ignoreLoadCancellation = false
        var fail = false
        var committed = false
        var loads = 0
        var edits = 0
        var commits = 0
        var lastSelected: Set<String>? = null

        override suspend fun load(id: String): AutoTaskImportSession {
            loads++
            if (ignoreLoadCancellation) withContext(NonCancellable) { gate?.await() }
            else gate?.await()
            if (fail) error("failed")
            return AutoTaskImportSession(items, committed)
        }

        override suspend fun edit(id: String, key: String, json: String): AutoTaskImportItem {
            edits++
            editGate?.await()
            if (fail) error("invalid")
            val updated = item(key, AutoTaskImportStatus.Exists).copy(name = json)
            items = items.map { if (it.key == key) updated else it }
            return updated
        }

        override suspend fun commit(id: String, selected: Set<String>) {
            commits++
            lastSelected = selected
            commitGate?.await()
            if (fail) error("failed")
            committed = true
        }
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        AutoTaskImportViewModel(repo, saved, "session").also { models += it }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                runCurrent()
            }
        }

    @Test
    fun delayedLoadBlocksEditsAndDefaultsToNewAndUpdatedOnly() = test {
        val repo = Fake().apply { gate = CompletableDeferred() }
        val model = model(repo)
        runCurrent()
        model.edit("0")
        model.toggle("0")
        model.confirm()
        assertEquals(0, repo.commits)
        assertNull(model.state.value.editor)
        repo.gate!!.complete(Unit)
        runCurrent()
        assertEquals(setOf("0", "1"), model.state.value.selected)
    }

    @Test
    fun selectionRestoresIncludingExplicitEmptyWithoutBundlingScripts() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        first.clearSelection()
        val restored = model(repo, copy(saved))
        runCurrent()
        assertTrue(restored.state.value.selected.isEmpty())
        assertTrue(
            saved
                .keys()
                .mapNotNull { saved.get<Any?>(it) }
                .filterIsInstance<String>()
                .all { it.length < 100 }
        )
        restored.selectAll()
        assertTrue(restored.state.value.allSelected)
        restored.toggle("missing")
        assertEquals(3, restored.state.value.selectedCount)
    }

    @Test
    fun editorRequestSurvivesRestorationAndConsumedNavigationDoesNotReopen() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        first.edit("1")
        val request = first.state.value.editor!!.requestId
        val restoredSaved = copy(saved)
        val restored = model(repo, restoredSaved)
        runCurrent()
        assertTrue(restored.state.value.openEditor)
        restored.editorOpened("stale")
        assertTrue(restored.state.value.openEditor)
        restored.editorOpened(request)
        val again = model(repo, copy(restoredSaved))
        runCurrent()
        assertFalse(again.state.value.openEditor)
        again.codeSaved("Edited", request)
        runCurrent()
        assertEquals("Edited", again.state.value.items[1].name)
        assertFalse("1" in again.state.value.selected)
        again.codeSaved("Edited", request)
        runCurrent()
        assertEquals(1, repo.edits)
    }

    @Test
    fun staleEditorResultsCannotReplaceNewRequestAndInvalidEditKeepsRowAndSelection() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        model.edit("0")
        val old = model.state.value.editor!!.requestId
        model.edit("1")
        val fresh = model.state.value.editor!!.requestId
        model.codeSaved("Old", old)
        runCurrent()
        assertEquals(0, repo.edits)
        repo.fail = true
        model.codeSaved("Invalid", fresh)
        runCurrent()
        assertEquals("Name1", model.state.value.items[1].name)
        assertTrue("1" in model.state.value.selected)
        assertEquals("invalid", model.state.value.error)
    }

    @Test
    fun cancelDuringLoadIsFinalAndRestorationNeverWritesOrLoadsAgain() = test {
        val repo = Fake().apply { gate = CompletableDeferred() }
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        first.cancel()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertTrue(first.state.value.finished)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertTrue(restored.state.value.finished)
        assertEquals(1, repo.loads)
        assertEquals(0, repo.commits)
    }

    @Test
    fun confirmationSnapshotsSelectionRejectsDuplicatesAndProtectsActiveCommit() = test {
        val repo = Fake().apply { commitGate = CompletableDeferred() }
        val model = model(repo)
        runCurrent()
        model.confirm()
        model.confirm()
        model.toggle("0")
        model.cancel()
        runCurrent()
        assertEquals(1, repo.commits)
        assertEquals(setOf("0", "1"), repo.lastSelected)
        assertFalse(model.state.value.finished)
        repo.commitGate!!.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.finished)
    }

    @Test
    fun failureIsRetryableAndCommittedDiskSessionDoesNotImportTwice() = test {
        val repo = Fake().apply { fail = true }
        val model = model(repo)
        runCurrent()
        assertEquals("failed", model.state.value.error)
        repo.fail = false
        model.load()
        runCurrent()
        model.confirm()
        runCurrent()
        assertTrue(model.state.value.finished)
        assertEquals(1, repo.commits)
        val restored = model(repo)
        runCurrent()
        assertTrue(restored.state.value.finished)
        assertEquals(1, repo.commits)
    }

    @Test
    fun commitFailureKeepsSelectionAndAllowsExplicitRetry() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        model.toggle("2")
        repo.fail = true
        model.confirm()
        runCurrent()
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.finished)
        assertEquals(3, model.state.value.selectedCount)
        repo.fail = false
        model.confirm()
        runCurrent()
        assertEquals(2, repo.commits)
        assertTrue(model.state.value.finished)
    }

    @Test
    fun uncooperativeLateLoadCannotReopenCanceledImportOrRestoreEditor() = test {
        val repo =
            Fake().apply {
                gate = CompletableDeferred()
                ignoreLoadCancellation = true
            }
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        runCurrent()
        model.cancel()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.finished)
        assertTrue(model.state.value.items.isEmpty())
        assertNull(model.state.value.editor)
        assertEquals(0, repo.commits)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertTrue(restored.state.value.finished)
        assertEquals(1, repo.loads)
    }

    @Test
    fun editorSavedAndFinalSaveAcceptDifferentContentOnSameNonceButRejectIdenticalReplay() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        runCurrent()
        model.edit("0")
        val request = model.state.value.editor!!.requestId
        model.codeSaved("First", request)
        runCurrent()
        assertEquals("First", model.state.value.items[0].name)
        model.codeSaved("Final", request)
        runCurrent()
        assertEquals("Final", model.state.value.items[0].name)
        assertEquals(2, repo.edits)
        val restored = model(repo, copy(saved))
        runCurrent()
        restored.codeSaved("Final", request)
        runCurrent()
        assertEquals(2, repo.edits)
        restored.edit("0")
        val next = restored.state.value.editor!!.requestId
        assertNotEquals(request, next)
        restored.codeSaved("Final", next)
        runCurrent()
        assertEquals(3, repo.edits)
    }

    @Test
    fun failedEditorSaveDoesNotConsumePayloadAndBusyDuplicateCannotStartSecondEdit() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        model.edit("1")
        val request = model.state.value.editor!!.requestId
        repo.fail = true
        model.codeSaved("Retry", request)
        runCurrent()
        assertEquals("invalid", model.state.value.error)
        repo.fail = false
        repo.editGate = CompletableDeferred()
        model.codeSaved("Retry", request)
        runCurrent()
        model.codeSaved("Retry", request)
        runCurrent()
        assertEquals(2, repo.edits)
        repo.editGate!!.complete(Unit)
        runCurrent()
        assertEquals("Retry", model.state.value.items[1].name)
        model.codeSaved("Retry", request)
        runCurrent()
        assertEquals(2, repo.edits)
    }
}
