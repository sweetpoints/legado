package io.legado.app.ui.file

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFilePickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<LocalFilePickerViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class Fake : LocalFilePickerRepository {
        val snapshots =
            mutableMapOf(
                "/root" to
                    LocalFilePickerSnapshot(
                        "/root",
                        null,
                        emptyList(),
                        listOf(
                            LocalFilePickerRow("/root/sub", "sub", true, true),
                            LocalFilePickerRow("/root/a.txt", "a.txt", false, true),
                            LocalFilePickerRow("/root/b.pdf", "b.pdf", false, false),
                        ),
                    ),
                "/root/sub" to
                    LocalFilePickerSnapshot(
                        "/root/sub",
                        "/root",
                        listOf(LocalFilePickerCrumb("/root/sub", "sub")),
                        emptyList(),
                    ),
            )
        var listGate: CompletableDeferred<Unit>? = null
        var validateGate: CompletableDeferred<Unit>? = null
        var createGate: CompletableDeferred<Unit>? = null
        var uncooperative = false
        var fail = false
        var issue: LocalFilePickerIssue? = null
        val listed = mutableListOf<String>()
        val validated = mutableListOf<String>()
        val created = mutableListOf<Pair<String, String>>()

        override suspend fun list(
            config: LocalFilePickerConfig,
            directory: String,
        ): LocalFilePickerSnapshot {
            listed += directory
            if (uncooperative) withContext(NonCancellable) { listGate?.await() }
            else listGate?.await()
            issue?.let { throw LocalFilePickerIssueException(it) }
            if (fail) error("list failed")
            return checkNotNull(snapshots[directory])
        }

        override suspend fun validate(config: LocalFilePickerConfig, path: String): String {
            validated += path
            validateGate?.await()
            if (fail) error("validate failed")
            return path
        }

        override suspend fun create(
            config: LocalFilePickerConfig,
            directory: String,
            name: String,
        ) {
            created += directory to name
            createGate?.await()
            if (fail) error("create failed")
        }
    }

    private fun model(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        dir: Boolean = false,
    ) =
        LocalFilePickerViewModel(repo, saved, LocalFilePickerConfig("/root", dir)).also {
            models += it
        }

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
    fun loadingAndDisabledExtensionRowsCannotSelectOrConfirm() = test {
        val repo = Fake().apply { listGate = CompletableDeferred() }
        val model = model(repo)
        runCurrent()
        model.click("/root/a.txt")
        model.confirm()
        assertTrue(repo.validated.isEmpty())
        repo.listGate!!.complete(Unit)
        runCurrent()
        model.click("/root/b.pdf")
        assertNull(model.state.value.selected)
        model.confirm()
        assertEquals(LocalFilePickerIssue.FileRequired, model.state.value.issue)
        model.click("/root/a.txt")
        assertEquals("/root/a.txt", model.state.value.selected)
    }

    @Test
    fun folderAndParentNavigationClearSelectionAndRestoreIndependentScrollPositions() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        first.click("/root/a.txt")
        first.scrolled("/root", 7, 12)
        first.click("/root/sub")
        runCurrent()
        assertNull(first.state.value.selected)
        assertEquals("/root/sub", first.state.value.directory)
        first.scrolled("/root/sub", 0, 4)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals("/root/sub", restored.state.value.directory)
        assertEquals(7 to 12, restored.scroll("/root"))
        restored.click("/root")
        runCurrent()
        assertEquals("/root", restored.state.value.directory)
        assertEquals(0 to 4, restored.scroll("/root/sub"))
    }

    @Test
    fun restoredFileSelectionIsValidatedAgainstNewRowsAndDroppedIfDeleted() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        first.click("/root/a.txt")
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals("/root/a.txt", restored.state.value.selected)
        repo.snapshots["/root"] = repo.snapshots.getValue("/root").copy(rows = emptyList())
        val removed = model(repo, copy(saved))
        runCurrent()
        assertNull(removed.state.value.selected)
    }

    @Test
    fun directoryModeConfirmsCurrentDirectoryAndRestoresSmallPendingResultExactlyOnce() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val model = model(repo, saved, true)
        runCurrent()
        model.click("/root/a.txt")
        model.confirm()
        runCurrent()
        assertEquals(listOf("/root"), repo.validated)
        val pending = model.state.value.result!!
        model.confirm()
        assertEquals(1, repo.validated.size)
        val restoredSaved = copy(saved)
        val restored = model(repo, restoredSaved, true)
        runCurrent()
        assertEquals(pending, restored.state.value.result)
        restored.delivered(pending.id + 1)
        assertNotNull(restored.state.value.result)
        restored.delivered(pending.id)
        assertTrue(restored.state.value.finished)
        assertNull(restored.state.value.result)
        val closed = model(repo, copy(restoredSaved), true)
        runCurrent()
        assertTrue(closed.state.value.finished)
        assertEquals(1, repo.validated.size)
    }

    @Test
    fun duplicateConfirmAndCancelCannotInterruptActiveValidationAndErrorCanRetry() = test {
        val repo =
            Fake().apply {
                validateGate = CompletableDeferred()
                fail = false
            }
        val model = model(repo)
        runCurrent()
        model.click("/root/a.txt")
        model.confirm()
        model.confirm()
        model.cancel()
        runCurrent()
        assertEquals(1, repo.validated.size)
        assertFalse(model.state.value.finished)
        repo.fail = true
        repo.validateGate!!.complete(Unit)
        runCurrent()
        assertEquals("validate failed", model.state.value.error)
        repo.fail = false
        model.confirm()
        runCurrent()
        assertEquals(2, repo.validated.size)
        assertNotNull(model.state.value.result)
    }

    @Test
    fun folderEditorRestoresDraftSelectionAndCancelDoesNotCreateAnything() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        runCurrent()
        model.showCreate()
        model.folderName(" New folder ", 2, 5)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertTrue(restored.state.value.creating)
        assertEquals(" New folder ", restored.state.value.folderName)
        assertEquals(2, restored.state.value.folderStart)
        assertEquals(5, restored.state.value.folderEnd)
        restored.cancelCreate()
        restored.create()
        runCurrent()
        assertTrue(repo.created.isEmpty())
    }

    @Test
    fun folderCreationIsTrimmedDuplicateGuardedAndRefreshesCurrentDirectory() = test {
        val repo = Fake().apply { createGate = CompletableDeferred() }
        val model = model(repo)
        runCurrent()
        model.showCreate()
        model.folderName("   ", 0, 0)
        model.create()
        assertTrue(repo.created.isEmpty())
        model.folderName(" New ", 3, 3)
        model.create()
        model.create()
        model.cancelCreate()
        runCurrent()
        assertEquals(listOf("/root" to "New"), repo.created)
        repo.createGate!!.complete(Unit)
        runCurrent()
        assertFalse(model.state.value.creating)
        assertEquals("", model.state.value.folderName)
        assertEquals(listOf("/root", "/root"), repo.listed)
    }

    @Test
    fun failedCreateKeepsEditorDraftAndSelectionRetryable() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        model.showCreate()
        model.folderName("Draft", 1, 4)
        repo.fail = true
        model.create()
        runCurrent()
        assertTrue(model.state.value.creating)
        assertEquals("Draft", model.state.value.folderName)
        assertFalse(model.state.value.busy)
        repo.fail = false
        model.create()
        runCurrent()
        assertEquals(2, repo.created.size)
        assertFalse(model.state.value.creating)
    }

    @Test
    fun cancelAndStopRejectNonCooperativeLateDirectoryLoadWithoutPublishingRows() = test {
        val repo =
            Fake().apply {
                listGate = CompletableDeferred()
                uncooperative = true
            }
        val model = model(repo)
        runCurrent()
        model.cancel()
        repo.listGate!!.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.finished)
        assertFalse(model.state.value.loaded)
        val repo2 =
            Fake().apply {
                listGate = CompletableDeferred()
                uncooperative = true
            }
        val stopped = model(repo2)
        runCurrent()
        val before = stopped.state.value
        stopped.stop()
        repo2.listGate!!.complete(Unit)
        runCurrent()
        assertEquals(before, stopped.state.value)
    }

    @Test
    fun nonCooperativeOldDirectoryLoadCannotOverwriteLatestNavigation() = test {
        val repo =
            Fake().apply {
                listGate = CompletableDeferred()
                uncooperative = true
            }
        val model = model(repo)
        runCurrent()
        val oldGate = repo.listGate!!
        repo.listGate = null
        model.navigate("/root/sub")
        runCurrent()
        assertEquals("/root/sub", model.state.value.directory)
        oldGate.complete(Unit)
        runCurrent()
        assertEquals("/root/sub", model.state.value.directory)
    }

    @Test
    fun typedValidationIssuesAreSeparateFromPlatformErrorsAndResetOnRetry() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        LocalFilePickerIssue.entries.forEach { issue ->
            repo.issue = issue
            model.load()
            runCurrent()
            assertEquals(issue, model.state.value.issue)
            assertNull(model.state.value.error)
            repo.issue = null
            model.load()
            runCurrent()
            assertNull(model.state.value.issue)
        }
        repo.fail = true
        model.load()
        runCurrent()
        assertNull(model.state.value.issue)
        assertEquals("list failed", model.state.value.error)
        repo.fail = false
        model.load()
        runCurrent()
        model.confirm()
        assertEquals(LocalFilePickerIssue.FileRequired, model.state.value.issue)
        model.click("/root/a.txt")
        assertNull(model.state.value.issue)
        model.showCreate()
        model.folderName(" ", 0, 0)
        model.create()
        assertEquals(LocalFilePickerIssue.FolderNameRequired, model.state.value.issue)
        model.cancelCreate()
        assertNull(model.state.value.issue)
    }
}
