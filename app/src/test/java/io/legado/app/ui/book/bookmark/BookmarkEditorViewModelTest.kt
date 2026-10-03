package io.legado.app.ui.book.bookmark

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BookmarkEditorViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class Fake(editPos: Int = 0) : BookmarkEditorRepository {
        var draft =
            BookmarkEditorDraft(
                BookmarkEditorSeed(
                    42,
                    "Book",
                    "Author",
                    7,
                    9,
                    "Chapter",
                    "Original",
                    "Note",
                    editPos,
                )
            )
        var gate: CompletableDeferred<Unit>? = null
        var commitGate: CompletableDeferred<Unit>? = null
        var uncooperative = false
        var fail = false
        var loads = 0
        var writes = 0
        val commits = mutableListOf<Pair<BookmarkEditorDraft, Boolean>>()

        override suspend fun load(id: String): BookmarkEditorDraft {
            loads++
            if (uncooperative) withContext(NonCancellable) { gate?.await() } else gate?.await()
            if (fail) error("failed")
            return draft
        }

        override suspend fun write(id: String, draft: BookmarkEditorDraft) {
            writes++
            if (fail) error("failed")
            if (!this.draft.finished && draft.revision >= this.draft.revision) this.draft = draft
        }

        override suspend fun commit(id: String, draft: BookmarkEditorDraft, delete: Boolean) {
            commits += draft to delete
            if (uncooperative) withContext(NonCancellable) { commitGate?.await() }
            else commitGate?.await()
            if (fail) error("failed")
            this.draft = draft.copy(finished = true)
        }
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        BookmarkEditorViewModel(repo, saved, "id").also { models += it }

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
    fun pendingLoadDisablesEditsAndSeedFailureCanRetry() = test {
        val repo =
            Fake().apply {
                gate = CompletableDeferred()
                fail = true
            }
        val model = model(repo)
        runCurrent()
        model.bookText("Ignored", 0, 0)
        model.confirm()
        model.delete()
        assertTrue(repo.commits.isEmpty())
        repo.gate!!.complete(Unit)
        runCurrent()
        assertEquals("failed", model.state.value.error)
        repo.fail = false
        model.load()
        runCurrent()
        assertEquals("Chapter", model.state.value.chapter)
        assertTrue(model.state.value.canEdit)
    }

    @Test
    fun largeDraftAndSelectionRestoreWithOnlySmallSavedState() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        val large = "large ".repeat(20000)
        first.bookText(large, 12, 4)
        first.content("Edited note", 3, 3)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(1, repo.writes)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals(large, restored.state.value.bookText)
        assertEquals("Edited note", restored.state.value.content)
        assertEquals(12, restored.state.value.textStart)
        assertEquals(4, restored.state.value.textEnd)
        assertEquals(3, restored.state.value.contentStart)
        assertTrue(
            saved
                .keys()
                .mapNotNull { saved.get<Any?>(it) }
                .filterIsInstance<String>()
                .all { it.length < 100 }
        )
    }

    @Test
    fun blankAndWhitespaceInputsAreSavedExactlyWithoutChangingMetadata() = test {
        val repo = Fake()
        val seed = repo.draft.seed
        val model = model(repo)
        runCurrent()
        model.bookText("", 0, 0)
        model.content(" New note ", 2, 4)
        model.confirm()
        runCurrent()
        val actual = repo.commits.single().first
        assertEquals(seed, actual.seed)
        assertEquals("", actual.bookText)
        assertEquals(" New note ", actual.content)
        assertTrue(model.state.value.finished)
    }

    @Test
    fun deleteOnlyExistsForEditingAndUsesIndependentDeleteOperation() = test {
        val freshRepo = Fake(-1)
        val fresh = model(freshRepo)
        runCurrent()
        assertFalse(fresh.state.value.canDelete)
        fresh.delete()
        assertTrue(freshRepo.commits.isEmpty())
        val repo = Fake(0)
        val edited = model(repo)
        runCurrent()
        assertTrue(edited.state.value.canDelete)
        edited.delete()
        runCurrent()
        assertTrue(repo.commits.single().second)
        assertTrue(edited.state.value.finished)
    }

    @Test
    fun duplicateConfirmDeleteAndCancelCannotInterruptActiveCommit() = test {
        val repo = Fake().apply { commitGate = CompletableDeferred() }
        val model = model(repo)
        runCurrent()
        model.confirm()
        model.confirm()
        model.delete()
        model.cancel()
        model.content("Ignored", 0, 0)
        runCurrent()
        assertEquals(1, repo.commits.size)
        assertFalse(repo.commits.single().second)
        assertTrue(model.state.value.busy)
        assertFalse(model.state.value.finished)
        repo.commitGate!!.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.finished)
    }

    @Test
    fun cancelRejectsNonCooperativeLateLoadAndNeverWritesDatabase() = test {
        val repo =
            Fake().apply {
                gate = CompletableDeferred()
                uncooperative = true
            }
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        first.cancel()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertTrue(first.state.value.finished)
        assertFalse(first.state.value.loaded)
        assertTrue(repo.commits.isEmpty())
        assertEquals(0, repo.writes)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertTrue(restored.state.value.finished)
        assertEquals(1, repo.loads)
    }

    @Test
    fun failedSaveKeepsLatestDraftRetryableAndExplicitFlushDoesNotNeedDebounce() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        model.content("Draft", 5, 5)
        model.flushDraft()
        assertEquals("Draft", repo.draft.content)
        repo.fail = true
        model.confirm()
        runCurrent()
        assertEquals("failed", model.state.value.error)
        assertTrue(model.state.value.canEdit)
        repo.fail = false
        model.confirm()
        runCurrent()
        assertEquals(2, repo.commits.size)
        assertTrue(model.state.value.finished)
    }

    @Test
    fun diskCommittedRestoreDoesNotRepeatSaveAndReloadCannotReplaceDirtyInput() = test {
        val repo = Fake()
        val first = model(repo)
        runCurrent()
        first.bookText("Draft", 5, 5)
        first.load()
        runCurrent()
        assertEquals("Draft", first.state.value.bookText)
        assertEquals(1, repo.loads)
        first.confirm()
        runCurrent()
        val saved = SavedStateHandle()
        val restored = model(repo, saved)
        runCurrent()
        assertTrue(restored.state.value.finished)
        restored.confirm()
        restored.delete()
        assertEquals(1, repo.commits.size)
        val again = model(repo, copy(saved))
        runCurrent()
        assertTrue(again.state.value.finished)
        assertEquals(2, repo.loads)
    }

    @Test
    fun stoppedViewModelRejectsNonCooperativeLoadResultWithoutPublishingLoadedState() = test {
        val repo =
            Fake().apply {
                gate = CompletableDeferred()
                uncooperative = true
            }
        val model = model(repo)
        runCurrent()
        val before = model.state.value
        model.stop()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertEquals(before, model.state.value)
        assertFalse(model.state.value.loaded)
    }

    @Test
    fun stoppedViewModelDoesNotPublishFinishedAfterNonCooperativeDatabaseCommit() = test {
        val repo =
            Fake().apply {
                commitGate = CompletableDeferred()
                uncooperative = true
            }
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        runCurrent()
        model.confirm()
        runCurrent()
        val before = model.state.value
        model.stop()
        repo.commitGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, repo.commits.size)
        assertTrue(repo.draft.finished)
        assertEquals(before, model.state.value)
        assertFalse(model.state.value.finished)
        assertNotEquals(true, saved.get<Boolean>("bookmark.finished"))
    }
}
