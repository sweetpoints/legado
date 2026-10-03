package io.legado.app.ui.book.source.edit

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookSourceEditScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state =
        mutableStateOf(
            BookSourceComposeState(
                document = BookSourceEditDocument.from(BookSource("url", "name"))
            )
        )
    private var native: BookSourceNativeAction? = null
    private var save: BookSourceSaveAction? = null
    private var retries = 0
    private val actions =
        BookSourceScreenActions(
            field = { tab, key, text, start, end ->
                update { it.copy(form = it.form.updateField(tab, key, text, start, end)) }
            },
            focus = { tab, key -> update { it.copy(selectedTab = tab, focusedKey = key) } },
            tab = { tab -> update { it.copy(selectedTab = tab, focusedKey = null) } },
            options = { options -> update { it.copy(form = it.form.copy(options = options)) } },
            expanded = { expanded -> update { it.copy(optionsExpanded = expanded) } },
            save = { save = it },
            native = { action, _ -> native = action },
            autoComplete = {},
            paste = {},
            clearCookie = {},
            insert = { text ->
                update { it.copy(form = it.form.insert(it.selectedTab, it.focusedKey!!, text)) }
            },
            undo = {},
            redo = {},
            groups = {},
            dismissGroups = {},
            variableEdit = { value -> state.value = state.value.copy(variable = value) },
            variableSave = {},
            dismissVariable = {},
            cancel = {},
            discard = {},
            keepEditing = {},
            retry = { retries++ },
        )

    private fun update(change: (BookSourceEditDocument) -> BookSourceEditDocument) {
        state.value = state.value.copy(document = change(state.value.document!!))
    }

    private fun show(keyboardVisible: Boolean = false) {
        compose.setContent {
            BookSourceEditScreen(
                state.value,
                actions,
                maxLines = 6,
                keyboardRows = 1,
                keyboardVisible = keyboardVisible,
            )
        }
    }

    @Test
    fun lastFieldOnEveryTabIsEditableAndKeepsItsOwnValue() {
        show()
        val form = state.value.document!!.form
        form.tabs.forEachIndexed { tab, fields ->
            compose.onNodeWithTag("source-tab-$tab").performScrollTo().performClick()
            val field = fields.last()
            val tag = "source-field-$tab-${field.key}"
            compose.onNodeWithTag("source-fields").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performTextReplacement("tab-$tab value")
            compose.runOnIdle {
                assertEquals(
                    "tab-$tab value",
                    state.value.document!!.form.field(tab, field.key)!!.value,
                )
            }
        }
    }

    @Test
    fun collapsedOptionsExposeAllSixSwitchesAndFiveTypesAfterExpansion() {
        show()
        compose.onNodeWithTag("source-option-${R.string.is_enable}").assertDoesNotExist()
        compose.onNodeWithTag("source-options-toggle").performClick()
        val labels =
            listOf(
                R.string.is_enable,
                R.string.discovery,
                R.string.auto_save_cookie,
                R.string.review,
                R.string.is_event_listener,
                R.string.custom_button,
            )
        labels.forEach { label ->
            compose.onNodeWithTag("source-option-$label").performScrollTo().performClick()
        }
        compose.runOnIdle {
            val options = state.value.document!!.form.options
            assertEquals(
                listOf(false, false, false, true, true, true),
                listOf(
                    options.enabled,
                    options.enabledExplore,
                    options.enabledCookieJar,
                    options.enabledReview,
                    options.eventListener,
                    options.customButton,
                ),
            )
        }
        (0..4).forEach { type ->
            compose.onNodeWithTag("source-type").performClick()
            compose.onNodeWithTag("source-type-$type").performClick()
            compose.runOnIdle { assertEquals(type, state.value.document!!.form.options.type) }
        }
    }

    @Test
    fun largeAndCombiningHeavyFieldsOpenNativeEditorWithoutLayingOutRawPayload() {
        val raw = "a" + "\u0301".repeat(30)
        update { it.copy(form = it.form.updateField(0, "bookSourceName", raw)) }
        show()
        compose.onNodeWithText(raw).assertDoesNotExist()
        compose.onNodeWithTag("source-field-0-bookSourceName").performClick()
        compose.runOnIdle { assertEquals(BookSourceNativeAction.EDITOR, native) }
    }

    @Test
    fun busySaveAndFullEditorActionsAreDisabledAndErrorOffersRetry() {
        state.value = state.value.copy(busy = true, error = "disk failed")
        show()
        compose.onNodeWithTag("source-save").assertIsNotEnabled()
        compose.onNodeWithTag("source-fullscreen").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busy = false) }
        compose.onNodeWithTag("source-retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun fieldInputAndOptionsHeaderHaveAccessibleTouchTargets() {
        show()
        val input = compose.onNodeWithTag("source-field-0-bookSourceUrl").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("source-options-toggle").getUnclippedBoundsInRoot()
        assertTrue(input.bottom - input.top >= 48.dp)
        assertTrue(header.bottom - header.top >= 48.dp)
    }

    @Test
    fun keyboardAssistReplacesOnlyFocusedFieldRawSelection() {
        update {
            it.copy(
                selectedTab = 1,
                focusedKey = "bookList",
                form = it.form.updateField(1, "bookList", "A\r\n中😀Z", 6, 3),
            )
        }
        state.value = state.value.copy(assists = listOf(BookSourceKeyboardAssist("insert-x", "x")))
        show(keyboardVisible = true)
        compose.onNodeWithText("insert-x").performClick()
        compose.runOnIdle {
            assertEquals("A\r\nxZ", state.value.document!!.form.field(1, "bookList")!!.value)
        }
    }

    @Test
    fun variableDialogEditsActualValueAndSaveUsesOriginalAction() {
        state.value = state.value.copy(variable = "original", variableComment = "description")
        show()
        compose.onNodeWithTag("source-variable").performTextReplacement("changed")
        compose.onNodeWithTag("source-variable").assertTextEquals("changed")
        compose.runOnIdle { assertEquals("changed", state.value.variable) }
    }
}
