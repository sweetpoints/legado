package io.legado.app.ui.widget.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.widget.dialog.variable.VariableResult
import io.legado.app.ui.widget.dialog.variable.VariableRoute
import io.legado.app.ui.widget.dialog.variable.VariableScreen
import io.legado.app.ui.widget.dialog.variable.VariableUiState
import io.legado.app.ui.widget.dialog.variable.VariableViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VariableScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun multilineInputAndSaveEmitCallbacks() {
        var input: String? = null
        var saves = 0
        compose.setContent {
            LegadoComposeTheme {
                VariableScreen(
                    VariableUiState(title = "Variable title", input = "before"),
                    { input = it },
                    { saves++ },
                    {},
                )
            }
        }
        compose.onNodeWithText("Variable title").assertExists()
        compose
            .onNodeWithTag("variable-input")
            .performScrollTo()
            .performTextReplacement("first\nsecond")
        compose.onNodeWithTag("variable-save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("first\nsecond", input)
            assertEquals(1, saves)
        }
    }

    @Test
    fun cardTapDoesNotCloseButBackgroundTapDoes() {
        var closes = 0
        compose.setContent {
            LegadoComposeTheme { VariableScreen(VariableUiState(), {}, {}, { closes++ }) }
        }
        compose.onNodeWithTag("variable-card").performClick()
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithTag("variable-backdrop").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun commentTextCanBeSelectedAndCopiedWithoutClosingTheCard() {
        val toolbar = CaptureToolbar()
        var closes = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                LegadoComposeTheme {
                    VariableScreen(VariableUiState(comment = "copyable"), {}, {}, { closes++ })
                }
            }
        }
        compose.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("compose-test", "baseline"))
        }
        compose.onNodeWithTag("variable-comment").performScrollTo().performTouchInput {
            longClick(center)
        }
        compose.waitUntil { toolbar.copy != null }
        compose.runOnIdle { toolbar.copy?.invoke() }
        compose.waitUntil { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copyable" }
        compose.runOnIdle { assertEquals(0, closes) }
    }

    @Test
    fun routeDeliversSavedDraftOnceAndCloses() {
        val results = mutableListOf<VariableResult>()
        var closes = 0
        lateinit var model: VariableViewModel
        compose.runOnIdle { model = VariableViewModel(SavedStateHandle(mapOf("key" to "source"))) }
        compose.setContent {
            LegadoComposeTheme { VariableRoute(model, { results += it }, { closes++ }) }
        }
        compose.onNodeWithTag("variable-input").performTextReplacement("new value")
        compose.onNodeWithTag("variable-save").performClick()
        compose.waitUntil { results.size == 1 }
        compose.runOnIdle {
            model.requestSave()
            assertEquals(listOf(VariableResult("source", "new value")), results)
            assertEquals(1, closes)
        }
    }

    @Test
    fun backgroundCancellationDoesNotDeliverUnsavedDraft() {
        val results = mutableListOf<VariableResult>()
        var closes = 0
        lateinit var model: VariableViewModel
        compose.runOnIdle { model = VariableViewModel(SavedStateHandle()) }
        compose.setContent {
            LegadoComposeTheme { VariableRoute(model, { results += it }, { closes++ }) }
        }
        compose.onNodeWithTag("variable-input").performTextReplacement("unsaved")
        compose.onNodeWithTag("variable-backdrop").performClick()
        compose.runOnIdle {
            assertEquals(1, closes)
            assertTrue(results.isEmpty())
        }
    }

    @Test
    fun restoredFinishedDialogClosesWithoutRepeatingSaveCallback() {
        var saves = 0
        var closes = 0
        lateinit var restored: VariableViewModel
        compose.runOnIdle {
            val handle = SavedStateHandle(mapOf("key" to "source", "variable" to "value"))
            val original = VariableViewModel(handle)
            original.requestSave()
            original.consumeSave()
            restored =
                VariableViewModel(
                    SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })
                )
        }
        compose.setContent {
            LegadoComposeTheme { VariableRoute(restored, { saves++ }, { closes++ }) }
        }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle {
            assertEquals(0, saves)
            assertEquals(1, closes)
        }
    }

    @Test
    fun constructorKeepsNullVariableAndExactParameters() {
        val dialog = VariableDialog("title", "key", null, "comment")
        assertEquals("title", dialog.arguments?.getString("title"))
        assertEquals("key", dialog.arguments?.getString("key"))
        assertNull(dialog.arguments?.getString("variable"))
        assertEquals("comment", dialog.arguments?.getString("comment"))
    }

    private class CaptureToolbar : TextToolbar {
        var copy: (() -> Unit)? = null
        override var status = TextToolbarStatus.Hidden

        override fun hide() {
            status = TextToolbarStatus.Hidden
        }

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            copy = onCopyRequested
            status = TextToolbarStatus.Shown
        }
    }
}
