package io.legado.app.ui.login

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.data.repository.SourceLoginRow
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SourceLoginFormScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rows = listOf(SourceLoginRow("Name", RowUi.Type.text, "name"),
        SourceLoginRow("Password", RowUi.Type.password, "password"),
        SourceLoginRow("Select", RowUi.Type.select, "select", options = listOf("A", "B")),
        SourceLoginRow("Toggle", RowUi.Type.toggle, "toggle", options = listOf("☐", "☑")),
        SourceLoginRow("Button", RowUi.Type.button, "button", action = "submit"))
    @Test fun fieldsRemainAccessibleAndPasswordVisibilityToggles() {
        var state by mutableStateOf(SourceLoginFormUiState("source", false, rows, values = mapOf("password" to "secret")))
        compose.setContent { Content(state, onEdit = { key, value -> state = state.copy(values = state.values + (key to value)) }) }
        compose.onNodeWithTag("source-login-field:name").performTextInput("typed")
        compose.onNodeWithTag("source-login-field:password").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
        compose.onNodeWithContentDescription(context.getString(R.string.source_login_show_password)).performClick()
        compose.onNodeWithTag("source-login-field:password").assertTextContains("secret")
        compose.onNodeWithContentDescription(context.getString(R.string.source_login_hide_password)).assertExists()
        val bounds = compose.onNodeWithTag("source-login-field:name").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height >= 48 * context.resources.displayMetrics.density)
        compose.runOnIdle { assertEquals("typed", state.values["name"]) }
    }
    @Test fun selectAndLegacyToggleHaveDistinctActionsAndLongPressUses666msRelease() {
        val choices = mutableListOf<String>(); val long = mutableListOf<Boolean>()
        compose.setContent { Content(SourceLoginFormUiState("source", false, rows, values = mapOf("select" to "A", "toggle" to "☐")),
            onChoose = { _, value -> choices += value }, onToggle = { _, value -> long += value }, onAction = { _, value -> long += value }) }
        compose.onNodeWithText("Select: A").performScrollTo().performClick()
        compose.onNodeWithText("B").performClick()
        compose.onNodeWithTag("source-login-field:toggle").performScrollTo().performClick()
        compose.onNodeWithTag("source-login-action:submit").performScrollTo().performTouchInput { down(center); advanceEventTime(700); up() }
        compose.runOnIdle { assertEquals(listOf("B"), choices); assertEquals(listOf(false, true), long) }
    }
    @Test fun v2ValidationAndCountdownRemainVisibleAndSubmitIsHidden() {
        compose.setContent { Content(SourceLoginFormUiState("source", true, rows, errors = mapOf("name" to "required"), countdowns = mapOf("submit" to 2))) }
        compose.onNodeWithText("required").assertExists()
        compose.onNodeWithTag("source-login-submit").assertDoesNotExist()
        compose.onNodeWithText("Button (2s)").performScrollTo().assertExists()
        compose.onNodeWithTag("source-login-action:submit").assertIsNotEnabled()
    }
    @Test fun initialRenderFailureKeepsHeaderClearAndLogMenuAvailable() {
        var headers = 0; var deletes = 0; var clears = 0; var logs = 0
        compose.setContent { Content(SourceLoginFormUiState("source", false, error = "render failed"), onHeader = { headers++ },
            onDeleteHeader = { deletes++ }, onClearRequest = { if (it) clears++ }, onLog = { logs++ }) }
        listOf(R.string.show_login_header, R.string.del_login_header, R.string.clear_login_info, R.string.log).forEach {
            compose.onNodeWithTag("source-login-menu").performClick()
            compose.onNodeWithTag("source-login-menu-icon:$it", useUnmergedTree = true).assertExists()
            compose.onNodeWithText(context.getString(it)).performClick()
        }
        compose.onNodeWithTag("source-login-submit").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(1, 1, 1, 1), listOf(headers, deletes, clears, logs)) }
    }
    @Test fun emptyHeaderShowsContentAndPopulatedHeaderOffersCopy() {
        var state by mutableStateOf(SourceLoginFormUiState("source", false, header = "")); var copies = 0
        compose.setContent { Content(state, onCopy = { copies++ }) }
        compose.onNodeWithText(context.getString(R.string.empty)).assertExists()
        compose.runOnIdle { state = state.copy(header = "Cookie: session") }
        compose.onNodeWithText("Cookie: session").assertExists()
        compose.onNodeWithText(context.getString(R.string.copy_text)).performClick()
        compose.runOnIdle { assertEquals(1, copies) }
    }
    @Test fun clearConfirmationSeparatesCancelFromRemoval() {
        var confirmations = 0; val requested = mutableListOf<Boolean>()
        compose.setContent { Content(SourceLoginFormUiState("source", false, clear = true), onClear = { confirmations++ }, onClearRequest = { requested += it }) }
        compose.onNodeWithText(context.getString(R.string.no)).performClick()
        compose.onNodeWithText(context.getString(R.string.yes)).performClick()
        compose.runOnIdle { assertEquals(listOf(false), requested); assertEquals(1, confirmations) }
    }
    @Composable private fun Content(state: SourceLoginFormUiState,
        onEdit: (String, String) -> Unit = { _, _ -> }, onChoose: (SourceLoginRow, String) -> Unit = { _, _ -> },
        onToggle: (SourceLoginRow, Boolean) -> Unit = { _, _ -> }, onAction: (SourceLoginRow, Boolean) -> Unit = { _, _ -> },
        onHeader: () -> Unit = {}, onDeleteHeader: () -> Unit = {}, onCopy: () -> Unit = {},
        onClearRequest: (Boolean) -> Unit = {}, onClear: () -> Unit = {}, onLog: () -> Unit = {}) {
        LegadoComposeTheme { SourceLoginFormScreen(state, onEdit, onChoose, onAction, onToggle, {}, {},
            onHeader, onDeleteHeader, onCopy, {}, onClearRequest, onClear, onLog, Modifier.height(600.dp)) }
    }
}
