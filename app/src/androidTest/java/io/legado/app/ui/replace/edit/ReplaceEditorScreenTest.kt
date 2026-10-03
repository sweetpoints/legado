package io.legado.app.ui.replace.edit

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class ReplaceEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(ReplaceEditorState(loaded = true))
    private val edited = mutableListOf<Pair<ReplaceEditorField, ReplaceEditorText>>()

    private fun actions() =
        ReplaceEditorActions(
            field = { field, value ->
                edited += field to value
                state.value = state.value.copy(draft = state.value.draft.with(field, value))
            },
            focus = { state.value = state.value.copy(focus = it) },
            flags = { regex, title, source, content ->
                state.value =
                    state.value.copy(
                        draft =
                            state.value.draft.copy(
                                regex = regex,
                                title = title,
                                source = source,
                                content = content,
                            )
                    )
            },
            scroll = { state.value = state.value.copy(scroll = it) },
        )

    private fun show(actions: ReplaceEditorActions = actions(), keyboard: Boolean = false) {
        compose.setContent {
            LegadoComposeTheme {
                ReplaceEditorScreen(
                    state.value,
                    actions,
                    listOf(ReplaceEditorAssist("[x]", "insert")),
                    2,
                    keyboard,
                )
            }
        }
    }

    @Test
    fun allEightFieldsAndFourFlagsRemainEditableWithExactValues() {
        show()
        for (field in ReplaceEditorField.entries) {
            compose
                .onNodeWithTag("replace-editor-" + field.name)
                .performScrollTo()
                .performTextReplacement(
                    if (field == ReplaceEditorField.Timeout) "1200" else " raw " + field.name
                )
        }
        for (tag in listOf("regex", "title", "source", "content")) compose
            .onNodeWithTag("replace-editor-$tag")
            .performScrollTo()
            .performClick()
        assertEquals(ReplaceEditorField.entries.toSet(), edited.map { it.first }.toSet())
        assertTrue(state.value.draft.regex)
        assertTrue(state.value.draft.title)
        assertTrue(state.value.draft.source)
        assertFalse(state.value.draft.content)
        assertEquals(" raw Pattern", state.value.draft[ReplaceEditorField.Pattern].text)
        assertEquals(1200L, state.value.draft.entity().timeoutMillisecond)
    }

    @Test
    fun previewOutputIsSelectableAndHasNoTextEditActionAndShowsErrors() {
        state.value =
            state.value.copy(
                preview = "Output",
                previewFailed = true,
                previewError = ReplaceEditorPreviewIssue.Timeout,
            )
        show()
        compose.onNodeWithTag("replace-editor-output").performScrollTo()
        compose
            .onNodeWithTag("replace-editor-output-text")
            .assertTextEquals("Output")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        compose.onNodeWithTag("replace-editor-preview-error").assertExists()
    }

    @Test
    fun largeAndCombiningFieldsUsePlaceholderAndOpenExactFullscreenField() {
        val large = "x".repeat(20000)
        val combining = "a" + "\u0301".repeat(40)
        state.value =
            state.value.copy(
                draft =
                    state.value.draft
                        .with(ReplaceEditorField.Name, ReplaceEditorText(large))
                        .with(ReplaceEditorField.Pattern, ReplaceEditorText(combining))
            )
        val opened = mutableListOf<ReplaceEditorField?>()
        show(ReplaceEditorActions(editor = { opened += it }))
        compose.onNodeWithTag("replace-editor-Name").performClick()
        compose.onNodeWithTag("replace-editor-Pattern").performScrollTo().performClick()
        assertEquals(listOf(ReplaceEditorField.Name, ReplaceEditorField.Pattern), opened)
        assertEquals(large, state.value.draft[ReplaceEditorField.Name].text)
        assertEquals(combining, state.value.draft[ReplaceEditorField.Pattern].text)
    }

    @Test
    fun copyPasteSaveHelpAndDirtyYesNoActionsKeepTheirContracts() {
        var copied = 0
        var pasted = 0
        var saved = 0
        var helped = 0
        var kept = 0
        var discarded = 0
        show(
            ReplaceEditorActions(
                copy = { copied++ },
                paste = { pasted++ },
                save = { saved++ },
                help = { helped++ },
                keep = { kept++ },
                discard = { discarded++ },
            )
        )
        compose.onNodeWithTag("replace-editor-save").performClick()
        compose.onNodeWithTag("replace-editor-menu").performClick()
        compose.onNodeWithTag("replace-editor-copy").performClick()
        compose.onNodeWithTag("replace-editor-menu").performClick()
        compose.onNodeWithTag("replace-editor-paste").performClick()
        compose.onNodeWithTag("replace-editor-help").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(exit = true) }
        compose.onNodeWithTag("replace-editor-keep").performClick()
        compose.onNodeWithTag("replace-editor-discard").performClick()
        assertEquals(
            listOf(1, 1, 1, 1, 1, 1),
            listOf(copied, pasted, saved, helped, kept, discarded),
        )
    }

    @Test
    fun keyboardSymbolsUndoRedoAndConfigurationRemainAccessible() {
        var inserted = ""
        var undo = 0
        var redo = 0
        var configured = 0
        show(
            ReplaceEditorActions(
                insert = { inserted = it },
                undo = { undo++ },
                redo = { redo++ },
                config = { configured++ },
            ),
            true,
        )
        compose.onNodeWithTag("replace-editor-Name").performClick()
        compose.onNodeWithTag("replace-editor-key-[x]").performScrollTo().performClick()
        compose.onNodeWithTag("replace-editor-undo").performScrollTo().performClick()
        compose.onNodeWithTag("replace-editor-redo").performScrollTo().performClick()
        compose.onNodeWithTag("replace-editor-key-help").performScrollTo().performClick()
        compose.onNodeWithTag("replace-editor-key-config").performClick()
        assertEquals("insert", inserted)
        assertEquals(1, undo)
        assertEquals(1, redo)
        assertEquals(1, configured)
    }

    @Test
    fun outputSelectionClearsFullscreenTargetRatherThanEditingPreviousTextbox() {
        val opened = mutableListOf<ReplaceEditorField?>()
        show(ReplaceEditorActions(editor = { opened += it }))
        compose.onNodeWithTag("replace-editor-Name").performClick()
        compose.onNodeWithTag("replace-editor-output").performScrollTo().performClick()
        compose.onNodeWithTag("replace-editor-fullscreen").performClick()
        assertEquals(listOf<ReplaceEditorField?>(null), opened)
    }

    @Test
    fun loadedDataRestoresScrollAfterInitialLoadingLayoutAndDoesNotResetSavedOffset() {
        state.value = state.value.copy(loaded = false, busy = true, scroll = 500)
        show()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    loaded = true,
                    busy = false,
                    draft =
                        state.value.draft.with(
                            ReplaceEditorField.Name,
                            ReplaceEditorText("Long line\n".repeat(100)),
                        ),
                )
        }
        compose.waitForIdle()
        val range =
            compose
                .onNodeWithTag("replace-editor-fields")
                .fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(500f, range.value(), 1f)
        assertEquals(500, state.value.scroll)
    }

    @Test
    fun pendingLoadSaveAndReturnFailureDisableDuplicateActionsAndOfferRetryDiscard() {
        state.value = state.value.copy(loaded = false, busy = true)
        show()
        compose.onNodeWithTag("replace-editor-save").assertIsNotEnabled()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    loaded = true,
                    busy = false,
                    editor = ReplaceEditorLaunch("nonce", ReplaceEditorField.Name, "path"),
                    editorReturning = true,
                )
        }
        compose.onNodeWithTag("replace-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("replace-editor-return-retry").assertExists()
        compose.onNodeWithTag("replace-editor-return-discard").assertExists()
    }
}
