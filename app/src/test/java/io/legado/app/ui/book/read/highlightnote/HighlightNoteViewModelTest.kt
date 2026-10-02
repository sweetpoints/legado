package io.legado.app.ui.book.read.highlightnote

import io.legado.app.data.repository.HighlightNoteRepository
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.BookHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HighlightNoteViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Repository : HighlightNoteRepository {
        val saved = mutableListOf<BookHighlight>()
        val deleted = mutableListOf<BookHighlight>()
        var fail = false
        override suspend fun save(highlight: BookHighlight) { delay(100); if (fail) error("offline"); saved += highlight }
        override suspend fun delete(highlight: BookHighlight) { deleted += highlight }
    }
    private fun source() = BookHighlight(time = 9L, bookUrl = "book", chapterUrl = "chapter",
        chapterName = "Chapter 1", chapterPos = 42, chapterPosEnd = 64,
        style = "original-style", bookText = "original", note = "before")

    @Test fun draftAndCancelNeverMutateArgumentOrWrite() = runTest(dispatcher) {
        val source = source(); val repository = Repository()
        val model = HighlightNoteViewModel(repository, SavedStateHandle(mapOf("highlight" to source)))
        model.setBookText("edited"); model.setNote("draft"); model.cancel(); model.submit(HighlightNoteAction.Save)
        advanceUntilIdle()
        assertEquals("original", source.bookText); assertEquals("before", source.note)
        assertTrue(repository.saved.isEmpty()); assertTrue(model.state.value.finished)
    }
    @Test fun confirmationPreservesIdentityAndStyleAndRejectsDuplicateSubmission() = runTest(dispatcher) {
        val repository = Repository(); val source = source()
        val model = HighlightNoteViewModel(repository, SavedStateHandle(mapOf("highlight" to source)))
        model.setBookText("two\nlines"); model.setNote("note")
        model.submit(HighlightNoteAction.Save); model.submit(HighlightNoteAction.Delete); model.cancel(); model.setNote("late")
        assertTrue(model.state.value.busy)
        advanceUntilIdle()
        assertEquals(listOf(source.copy(bookText = "two\nlines", note = "note")), repository.saved)
        assertTrue(repository.deleted.isEmpty()); assertTrue(model.state.value.finished)
    }
    @Test fun failedSaveRetainsDraftAndCanRetryOnce() = runTest(dispatcher) {
        val repository = Repository().apply { fail = true }
        val model = HighlightNoteViewModel(repository, SavedStateHandle(mapOf("highlight" to source())))
        model.setNote("recoverable"); model.submit(HighlightNoteAction.Save); advanceUntilIdle()
        assertFalse(model.state.value.finished); assertEquals("offline", model.state.value.error)
        assertEquals("recoverable", model.state.value.note)
        repository.fail = false; model.retry(); advanceUntilIdle()
        assertEquals(1, repository.saved.size); assertTrue(model.state.value.finished)
    }
    @Test fun deleteUsesOriginalIdentityWithoutApplyingDraft() = runTest(dispatcher) {
        val repository = Repository(); val source = source()
        val model = HighlightNoteViewModel(repository, SavedStateHandle(mapOf("highlight" to source)))
        model.setBookText("draft"); model.setNote("draft"); model.submit(HighlightNoteAction.Delete); advanceUntilIdle()
        assertEquals(listOf(source), repository.deleted); assertTrue(repository.saved.isEmpty())
    }
    @Test fun restoredDraftAndFinishedSnapshotDoNotReplayDatabaseWrite() = runTest(dispatcher) {
        val repository = Repository(); val handle = SavedStateHandle(mapOf("highlight" to source()))
        val model = HighlightNoteViewModel(repository, handle); model.setBookText("draft"); model.setNote("restored")
        val snapshot = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = HighlightNoteViewModel(repository, snapshot)
        assertEquals("draft", restored.state.value.bookText); assertEquals("restored", restored.state.value.note)
        restored.submit(HighlightNoteAction.Save); advanceUntilIdle()
        val finished = HighlightNoteViewModel(repository, SavedStateHandle(snapshot.keys().associateWith { snapshot.get<Any?>(it) }))
        finished.submit(HighlightNoteAction.Save); advanceUntilIdle()
        assertTrue(finished.state.value.finished); assertEquals(1, repository.saved.size)
    }
    @Test fun missingAnnotationClosesWithoutWriting() = runTest(dispatcher) {
        val repository = Repository(); val model = HighlightNoteViewModel(repository, SavedStateHandle())
        assertTrue(model.state.value.finished); model.submit(HighlightNoteAction.Save); advanceUntilIdle()
        assertTrue(repository.saved.isEmpty())
    }
}
