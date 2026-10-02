package io.legado.app.ui.book.bookmark

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*

class BookmarkEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: BookmarkEditorViewModel
    private class Fake(editPos: Int = 0) : BookmarkEditorRepository {
        var draft = BookmarkEditorDraft(BookmarkEditorSeed(42, "Book", "Author", 1, 2, "Chapter", "Original text", "Note", editPos))
        var gate: CompletableDeferred<Unit>? = null; var commitGate: CompletableDeferred<Unit>? = null
        var fail = false; val commits = mutableListOf<Pair<BookmarkEditorDraft, Boolean>>()
        override suspend fun load(id: String): BookmarkEditorDraft { gate?.await(); if (fail) error("failed"); return draft }
        override suspend fun write(id: String, draft: BookmarkEditorDraft) { if (draft.revision >= this.draft.revision && !this.draft.finished) this.draft = draft }
        override suspend fun commit(id: String, draft: BookmarkEditorDraft, delete: Boolean) { commits += draft to delete; commitGate?.await(); if (fail) error("failed"); this.draft = draft.copy(finished = true) }
    }
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private fun show(repo: Fake, close: () -> Unit = {}, owner: Owner? = null) {
        compose.runOnIdle { model = BookmarkEditorViewModel(repo, SavedStateHandle(), "id") }
        compose.setContent { LegadoComposeTheme {
            if (owner == null) BookmarkEditorRoute(model, { true }, close, {})
            else CompositionLocalProvider(LocalLifecycleOwner provides owner) { BookmarkEditorRoute(model, { true }, close, {}) }
        } }
    }
    private fun loaded() { compose.waitUntil(5000) { model.state.value.loaded } }
    @After fun cleanup() { if (::model.isInitialized) compose.runOnIdle { model.stop() } }
    @Test fun readonlyChapterAndTwoEditableFieldsSaveExactValuesAndPreserveSeed() {
        val repo = Fake(); val seed = repo.draft.seed; var closes = 0; show(repo, { closes++ }); loaded()
        compose.onNodeWithTag("bookmark-editor-chapter").assertTextEquals("Chapter").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.EditableText))
        compose.onNodeWithTag("bookmark-editor-text").performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("bookmark-editor-content").performScrollTo().performTextReplacement(" New note ")
        compose.onNodeWithTag("bookmark-editor-confirm").performClick(); compose.waitUntil { closes == 1 }
        assertEquals(1, repo.commits.size); assertEquals(seed, repo.commits.single().first.seed)
        assertEquals("", repo.commits.single().first.bookText); assertEquals(" New note ", repo.commits.single().first.content); assertFalse(repo.commits.single().second)
    }
    @Test fun newBookmarkHidesDeleteAndCancelLeavesDatabaseUntouched() {
        val repo = Fake(-1); var closes = 0; show(repo, { closes++ }); loaded()
        compose.onNodeWithTag("bookmark-editor-delete").assertDoesNotExist()
        compose.onNodeWithTag("bookmark-editor-text").performTextReplacement("Unconfirmed")
        compose.onNodeWithTag("bookmark-editor-cancel").performClick(); compose.waitUntil { closes == 1 }; assertTrue(repo.commits.isEmpty())
    }
    @Test fun existingBookmarkDeleteUsesDeleteAndClosesOnce() {
        val repo = Fake(0); var closes = 0; show(repo, { closes++ }); loaded()
        compose.onNodeWithTag("bookmark-editor-delete").performClick(); compose.waitUntil { closes == 1 }
        assertEquals(1, repo.commits.size); assertTrue(repo.commits.single().second)
    }
    @Test fun activeCommitDisablesDuplicateActionsAndEditingUntilRoomFinishes() {
        val repo = Fake().apply { commitGate = CompletableDeferred() }; var closes = 0; show(repo, { closes++ }); loaded()
        compose.onNodeWithTag("bookmark-editor-confirm").performClick()
        listOf("confirm", "delete", "cancel", "text", "content").forEach { compose.onNodeWithTag("bookmark-editor-$it").assertIsNotEnabled() }
        compose.runOnIdle { model.confirm(); model.delete(); model.cancel(); assertEquals(1, repo.commits.size); assertEquals(0, closes); repo.commitGate!!.complete(Unit) }
        compose.waitUntil { closes == 1 }; assertEquals(1, repo.commits.size)
    }
    @Test fun seedFailureRetryAndOutsideCancelNeverCommit() {
        val repo = Fake().apply { gate = CompletableDeferred(); fail = true }; var closes = 0; show(repo, { closes++ })
        compose.onNodeWithTag("bookmark-editor-confirm").assertIsNotEnabled()
        compose.runOnIdle { repo.gate!!.complete(Unit) }; compose.waitUntil { !model.state.value.loading }; compose.onNodeWithTag("bookmark-editor-error").assertTextEquals("failed")
        compose.runOnIdle { repo.fail = false }; compose.onNodeWithTag("bookmark-editor-retry").performClick(); loaded()
        compose.onNodeWithTag("bookmark-editor-outside").performTouchInput { click(Offset(8f, 8f)) }; compose.waitUntil { closes == 1 }; assertTrue(repo.commits.isEmpty())
    }
    @Test fun completedSaveWaitsForResumedCloseWithoutRepeatingDatabaseOperation() {
        val owner = Owner(); val repo = Fake(); var closes = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        show(repo, { closes++ }, owner); loaded(); compose.onNodeWithTag("bookmark-editor-confirm").performClick()
        compose.waitUntil { model.state.value.finished }; assertEquals(1, repo.commits.size); assertEquals(0, closes)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { closes == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED; owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitForIdle()
        assertEquals(1, closes); assertEquals(1, repo.commits.size)
    }
    @Test fun textSelectionAndImeNextKeepDraftWhileDoneDoesNotSave() {
        val repo = Fake(); show(repo); loaded()
        compose.onNodeWithTag("bookmark-editor-text").performTextInputSelection(TextRange(1, 4))
        compose.runOnIdle { assertEquals(1, model.state.value.textStart); assertEquals(4, model.state.value.textEnd) }
        compose.onNodeWithTag("bookmark-editor-text").performImeAction(); compose.onNodeWithTag("bookmark-editor-content").assertIsFocused()
        compose.onNodeWithTag("bookmark-editor-content").performImeAction(); compose.runOnIdle { assertFalse(model.state.value.finished); assertTrue(repo.commits.isEmpty()) }
    }
}
