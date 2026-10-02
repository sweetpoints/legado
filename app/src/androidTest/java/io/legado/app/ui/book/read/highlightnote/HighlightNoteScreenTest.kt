package io.legado.app.ui.book.read.highlightnote

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import io.legado.app.data.repository.HighlightNoteRepository
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.BookHighlight
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HighlightNoteScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun multilineEditorsAndActionsReachable() {
        var text = ""; var note = ""; val actions = mutableListOf<HighlightNoteAction>(); var cancels = 0
        compose.setContent { LegadoComposeTheme { HighlightNoteScreen(HighlightNoteUiState(chapterName = "Chapter 1"),
            { text = it }, { note = it }, { actions += it }, { cancels++ }, {}) } }
        compose.onNodeWithText("Chapter 1").assertExists()
        compose.onNodeWithTag("highlight-note-book-text").performScrollTo().performTextReplacement("first\nsecond")
        compose.onNodeWithTag("highlight-note-input").performScrollTo().performTextReplacement("my note")
        compose.onNodeWithTag("highlight-note-save").performClick()
        compose.onNodeWithTag("highlight-note-delete").performClick()
        compose.onNodeWithTag("highlight-note-cancel").performClick()
        compose.runOnIdle { assertEquals("first\nsecond", text); assertEquals("my note", note)
            assertEquals(listOf(HighlightNoteAction.Save, HighlightNoteAction.Delete), actions); assertEquals(1, cancels) }
    }
    @Test fun inFlightWriteDisablesAllMutationActions() {
        compose.setContent { LegadoComposeTheme { HighlightNoteScreen(HighlightNoteUiState(busy = true), {}, {}, {}, {}, {}) } }
        listOf("highlight-note-book-text", "highlight-note-input", "highlight-note-save", "highlight-note-delete", "highlight-note-cancel").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
    }
    @Test fun restoredFinishedStateClosesWithoutDatabaseReplay() {
        var closes = 0; var writes = 0
        val repository = object : HighlightNoteRepository {
            override suspend fun save(highlight: BookHighlight) { writes++ }
            override suspend fun delete(highlight: BookHighlight) { writes++ }
        }
        val model = HighlightNoteViewModel(repository, SavedStateHandle(mapOf("highlight" to BookHighlight(time = 5L), "note.finished" to true)))
        compose.setContent { LegadoComposeTheme { HighlightNoteRoute(model, { closes++ }) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, closes); assertEquals(0, writes) }
    }
}
