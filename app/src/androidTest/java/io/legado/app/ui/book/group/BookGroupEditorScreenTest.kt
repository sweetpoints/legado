package io.legado.app.ui.book.group

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.BookGroupEditorRepository
import io.legado.app.data.repository.BookGroupEditorSnapshot
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookGroupEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun show(repo: Fake = Fake(), initial: BookGroupEditorSnapshot? = null, saved: SavedStateHandle = SavedStateHandle(), select: () -> Unit = {}, close: () -> Unit = {}): BookGroupEditorViewModel {
        val model = BookGroupEditorViewModel(repo, saved, initial)
        compose.setContent { LegadoComposeTheme { BookGroupEditorRoute(model, select, close) } }
        compose.waitUntil { !model.state.value.loading }
        return model
    }
    @Test fun allEditableFieldsAreSavedOnlyAfterDatabaseSuccessAndCloseOnce() {
        val repo = Fake(); repo.wait = CompletableDeferred(); var closes = 0
        val model = show(repo, close = { closes++ })
        compose.onNodeWithTag("book-group-delete").assertDoesNotExist()
        compose.onNodeWithTag("book-group-name").performTextInput("new")
        compose.onNodeWithTag("book-group-sort").performClick(); compose.onNodeWithTag("book-group-sort-5").performClick()
        compose.onNodeWithTag("book-group-refresh").performClick(); compose.onNodeWithTag("book-group-only-read").performClick()
        compose.runOnIdle { model.coverResult("https://example.invalid/image") }
        compose.waitUntil { !model.state.value.importingCover }
        compose.onNodeWithTag("book-group-save").performClick(); compose.onNodeWithTag("book-group-save").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, closes); repo.wait!!.complete(Unit) }
        compose.waitUntil { closes > 0 }
        compose.runOnIdle { assertEquals(1, closes); assertEquals(BookGroupEditorSnapshot(0, "new", "https://example.invalid/image", bookSort = 5, enableRefresh = false, onlyUpdateRead = true), repo.writes.single().first); assertFalse(repo.writes.single().second) }
    }
    @Test fun failedSaveKeepsDraftAndShowsErrorWithoutClosing() {
        val repo = Fake().apply { failure = true }; var closes = 0
        show(repo, close = { closes++ }); compose.onNodeWithTag("book-group-name").performTextInput("draft"); compose.onNodeWithTag("book-group-save").performClick()
        compose.onNodeWithTag("book-group-error").assertTextEquals("save failed"); compose.onNodeWithTag("book-group-name").assertTextContains("draft")
        compose.onNodeWithTag("book-group-save").assertIsEnabled(); compose.runOnIdle { assertEquals(0, closes); assertTrue(repo.writes.isEmpty()) }
    }
    @Test fun systemGroupAllowsEditingButHasNoDeleteAction() {
        val initial = BookGroupEditorSnapshot(-1, "all", bookSort = 0)
        val repo = Fake(initial); show(repo, initial)
        compose.onNodeWithTag("book-group-delete").assertDoesNotExist(); compose.onNodeWithTag("book-group-name").assertTextContains("all")
        compose.onNodeWithTag("book-group-save").performClick(); compose.waitUntil { repo.writes.isNotEmpty() }
        compose.runOnIdle { assertTrue(repo.writes.single().second); assertEquals(-1L, repo.writes.single().first.id) }
    }
    @Test fun deleteNeedsConfirmationAndCancelLeavesGroupThenConfirmCloses() {
        val initial = BookGroupEditorSnapshot(4, "custom"); val repo = Fake(initial); var closes = 0
        show(repo, initial, close = { closes++ })
        compose.onNodeWithTag("book-group-delete").performClick(); compose.onNodeWithTag("book-group-delete-cancel").performClick()
        compose.runOnIdle { assertTrue(repo.deleted.isEmpty()); assertEquals(0, closes) }
        compose.onNodeWithTag("book-group-delete").performClick(); compose.onNodeWithTag("book-group-delete-confirm").performClick()
        compose.waitUntil { closes > 0 }; compose.runOnIdle { assertEquals(listOf(4L), repo.deleted); assertEquals(1, closes) }
    }
    @Test fun coverMenuRemovesCurrentDraftAndEmptyCoverLaunchesOnlyOnePicker() {
        val initial = BookGroupEditorSnapshot(4, "custom", "https://cover"); val repo = Fake(initial); var launches = 0
        val model = show(repo, initial, select = { launches++ })
        compose.onNodeWithTag("book-group-cover").performClick(); compose.onNodeWithTag("book-group-remove-cover").performClick()
        compose.runOnIdle { assertNull(model.state.value.draft.cover) }
        compose.onNodeWithTag("book-group-cover").performClick(); compose.onNodeWithTag("book-group-cover").performClick()
        compose.runOnIdle { assertEquals(1, launches); model.coverResult(null) }
        compose.onNodeWithTag("book-group-cover").performClick(); compose.runOnIdle { assertEquals(2, launches) }
    }
    @Test fun restoredPendingPickerDoesNotRelaunchAndKeepsSaveDisabledUntilResult() {
        var launches = 0
        val model = show(saved = SavedStateHandle(mapOf("book.group.editor.selectingCover" to true, "book.group.editor.name" to "draft")), select = { launches++ })
        compose.onNodeWithTag("book-group-save").assertIsNotEnabled(); compose.runOnIdle { assertEquals(0, launches); model.coverResult("https://new") }
        compose.waitUntil { !model.state.value.importingCover }; compose.onNodeWithTag("book-group-save").assertIsEnabled()
        compose.runOnIdle { assertEquals("https://new", model.state.value.draft.cover) }
    }
    @Test fun restoredFinishedClosesWithoutSavingOrPickingAgain() {
        val repo = Fake(); var closes = 0; var selects = 0
        show(repo, saved = SavedStateHandle(mapOf("book.group.editor.finished" to true)), select = { selects++ }, close = { closes++ })
        compose.waitUntil { closes > 0 }; compose.runOnIdle { assertEquals(1, closes); assertEquals(0, selects); assertTrue(repo.writes.isEmpty()); assertTrue(repo.deleted.isEmpty()) }
    }
    @Test fun allSevenSortChoicesRemainReachableAndCancelDoesNotPersist() {
        val repo = Fake(); var closes = 0; val model = show(repo, close = { closes++ })
        compose.onNodeWithTag("book-group-sort").performClick()
        (-1..5).forEach { compose.onNodeWithTag("book-group-sort-$it").assertExists() }
        compose.onNodeWithTag("book-group-sort-3").performClick(); compose.runOnIdle { assertEquals(3, model.state.value.draft.bookSort) }
        compose.onNodeWithTag("book-group-name").performTextInput("discard"); compose.onNodeWithTag("book-group-cancel").performClick()
        compose.waitUntil { closes > 0 }; compose.runOnIdle { assertTrue(repo.writes.isEmpty()); assertTrue(repo.deleted.isEmpty()) }
    }
    private class Fake(private val initial: BookGroupEditorSnapshot? = null) : BookGroupEditorRepository {
        val writes = mutableListOf<Pair<BookGroupEditorSnapshot, Boolean>>(); val deleted = mutableListOf<Long>()
        var failure = false; var wait: CompletableDeferred<Unit>? = null
        override suspend fun load(id: Long) = initial
        override suspend fun save(draft: BookGroupEditorSnapshot, existing: Boolean): BookGroupEditorSnapshot { wait?.await(); if (failure) error("save failed"); writes += draft to existing; return draft.copy(id = if (existing) draft.id else 8) }
        override suspend fun delete(id: Long) { deleted += id }
        override suspend fun importCover(uri: String) = uri
    }
}
