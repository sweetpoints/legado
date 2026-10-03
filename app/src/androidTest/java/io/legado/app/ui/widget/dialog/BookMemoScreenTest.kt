package io.legado.app.ui.widget.dialog

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookMemoScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: BookMemoViewModel

    private class Fake : BookMemoRepository {
        val content = MutableStateFlow<BookMemoSnapshot?>(BookMemoSnapshot("Initial", 1))
        val writes = mutableListOf<String>()
        val drafts = mutableMapOf<String, BookMemoDraft>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false

        override fun observe(bookUrl: String) = content

        override suspend fun save(bookUrl: String, content: String): BookMemoSnapshot {
            gate?.await()
            if (fail) error("save failed")
            writes += content
            return BookMemoSnapshot(content, 2).also { this.content.value = it }
        }

        override suspend fun readDraft(id: String) = drafts[id]

        override suspend fun writeDraft(id: String, draft: BookMemoDraft) {
            drafts[id] = draft
        }
    }

    private fun show(repo: Fake, close: () -> Unit = {}) {
        compose.runOnIdle { model = BookMemoViewModel(repo, SavedStateHandle(), "book") }
        compose.setContent {
            LegadoComposeTheme {
                BookMemoRoute(
                    model,
                    object : MarkdownImageRepository {
                        override suspend fun load(source: String, width: Int) = null
                    },
                    {},
                    { true },
                    close,
                    { _, _ -> },
                )
            }
        }
        compose.waitUntil { model.state.value.loaded }
    }

    @After
    fun cleanup() {
        if (::model.isInitialized) compose.runOnIdle { model.stop() }
    }

    @Test
    fun focusedEditorKeepsExactDraftAndSelectionAndSavesRenderedMarkdown() {
        val repo = Fake()
        show(repo)
        compose.onNodeWithTag("memo-edit-save").performClick()
        compose
            .onNodeWithTag("memo-editor")
            .assertIsFocused()
            .performTextReplacement("**Saved bold**")
        compose.onNodeWithTag("memo-editor").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetSelection
        ) {
            it(2, 7, false)
        }
        compose.runOnIdle {
            assertEquals(2, model.state.value.selectionStart)
            assertEquals(7, model.state.value.selectionEnd)
        }
        compose.onNodeWithTag("memo-edit-save").performClick()
        compose.waitUntil { !model.state.value.editing }
        compose.onNodeWithText("Saved bold").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("**Saved bold**"), repo.writes) }
    }

    @Test
    fun closeDuringEditingRequiresDiscardAndCanceledConfirmationKeepsDraft() {
        val repo = Fake()
        var closes = 0
        show(repo) { closes++ }
        compose.onNodeWithTag("memo-edit-save").performClick()
        compose.onNodeWithTag("memo-editor").performTextReplacement("Draft")
        compose.onNodeWithTag("memo-close").performClick()
        compose.onNodeWithTag("memo-confirm-cancel").performClick()
        compose.onNodeWithTag("memo-editor").assertTextContains("Draft")
        compose.runOnIdle {
            assertEquals(0, closes)
            assertTrue(repo.writes.isEmpty())
        }
        compose.onNodeWithTag("memo-close").performClick()
        compose.onNodeWithTag("memo-confirm").performClick()
        compose.waitUntil { closes > 0 }
        compose.runOnIdle {
            assertEquals(1, closes)
            assertTrue(repo.writes.isEmpty())
        }
    }

    @Test
    fun clearConfirmationIsExplicitAndUsesAnEmptySaveWithoutClosing() {
        val repo = Fake()
        var closes = 0
        show(repo) { closes++ }
        compose.onNodeWithTag("memo-clear-cancel").performClick()
        compose.onNodeWithTag("memo-confirm-cancel").performClick()
        compose.runOnIdle { assertTrue(repo.writes.isEmpty()) }
        compose.onNodeWithTag("memo-clear-cancel").performClick()
        compose.onNodeWithTag("memo-confirm").performClick()
        compose.onNodeWithTag("memo-empty").assertIsDisplayed()
        compose.onNodeWithTag("memo-clear-cancel").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(""), repo.writes)
            assertEquals(0, closes)
        }
    }

    @Test
    fun pendingSaveBlocksActionsAndFailureKeepsEditableDraftForRetry() {
        val repo =
            Fake().apply {
                gate = CompletableDeferred()
                fail = true
            }
        show(repo)
        compose.onNodeWithTag("memo-edit-save").performClick()
        compose.onNodeWithTag("memo-editor").performTextReplacement("Retry draft")
        compose.onNodeWithTag("memo-edit-save").performClick()
        compose.onNodeWithTag("memo-close").assertIsNotEnabled()
        compose.onNodeWithTag("memo-edit-save").assertIsNotEnabled()
        compose.onNodeWithTag("memo-editor").assertIsNotEnabled()
        compose.runOnIdle { repo.gate!!.complete(Unit) }
        compose.waitUntil { !model.state.value.saving }
        compose.onNodeWithTag("memo-error").assertTextContains("save failed")
        compose.onNodeWithTag("memo-editor").assertTextContains("Retry draft")
        compose.runOnIdle { repo.fail = false }
        compose.onNodeWithTag("memo-edit-save").performClick()
        compose.waitUntil { !model.state.value.editing }
        compose.runOnIdle { assertEquals(listOf("Retry draft"), repo.writes) }
    }

    @Test
    fun lateDatabaseFlowCannotOverwriteDraftAndCancelShowsTheNewestStoredMemo() {
        val repo = Fake()
        show(repo)
        compose.onNodeWithTag("memo-edit-save").performClick()
        compose.onNodeWithTag("memo-editor").performTextReplacement("Local draft")
        compose.runOnIdle { repo.content.value = BookMemoSnapshot("Latest external memo", 3) }
        compose.onNodeWithTag("memo-editor").assertTextContains("Local draft")
        compose.onNodeWithTag("memo-clear-cancel").performClick()
        compose.onNodeWithText("Latest external memo").assertIsDisplayed()
        compose.runOnIdle { assertTrue(repo.writes.isEmpty()) }
    }
}
