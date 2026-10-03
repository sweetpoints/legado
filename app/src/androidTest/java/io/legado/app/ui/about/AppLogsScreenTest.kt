package io.legado.app.ui.about

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.AppLogRow
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppLogsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun clearRequiresConfirmationAndCancelDoesNotEmitClear() {
        val state = mutableStateOf(AppLogsUiState(isLoading = false))
        var clears = 0
        compose.setContent {
            LegadoComposeTheme {
                AppLogsScreen(
                    state.value,
                    {},
                    { state.value = state.value.copy(showClearConfirmation = true) },
                    {
                        clears++
                        state.value = state.value.copy(showClearConfirmation = false)
                    },
                    { state.value = state.value.copy(showClearConfirmation = false) },
                    {},
                    {},
                    {},
                    Modifier.heightIn(max = 500.dp),
                )
            }
        }
        compose.onNodeWithTag("app-logs-clear").performClick()
        compose.onNodeWithText(context.getString(R.string.clear_log_confirm)).assertExists()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithText(context.getString(R.string.no)).performClick()
        compose.onNodeWithText(context.getString(R.string.clear_log_confirm)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithTag("app-logs-clear").performClick()
        compose.onNodeWithText(context.getString(R.string.yes)).performClick()
        compose.runOnIdle { assertEquals(1, clears) }
    }

    @Test
    fun exportMenuClosesAfterSelectionAndActionsAreDisabledWhileWorking() {
        val state = mutableStateOf(AppLogsUiState(isLoading = false))
        var exports = 0
        compose.setContent {
            LegadoComposeTheme {
                AppLogsScreen(
                    state.value,
                    {},
                    {},
                    {},
                    {},
                    { exports++ },
                    {},
                    {},
                    Modifier.heightIn(max = 500.dp),
                )
            }
        }
        compose.onNodeWithTag("app-logs-menu").performClick()
        compose.onNodeWithText(context.getString(R.string.export)).performClick()
        compose.runOnIdle {
            assertEquals(1, exports)
            state.value = state.value.copy(isWorking = true)
        }
        compose.onNodeWithText(context.getString(R.string.export)).assertDoesNotExist()
        compose.onNodeWithTag("app-logs-clear").assertIsNotEnabled()
        compose.onNodeWithTag("app-logs-menu").assertIsNotEnabled()
    }

    @Test
    fun rowsWithDetailsEmitTheirStableIds() {
        var opened: Long? = null
        val logs =
            listOf(
                AppLogRow(12, "details time", "error", true),
                AppLogRow(13, "plain time", "plain", false),
            )
        compose.setContent {
            LegadoComposeTheme {
                AppLogsScreen(
                    AppLogsUiState(logs = logs, isLoading = false),
                    { opened = it },
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    Modifier.heightIn(max = 500.dp),
                )
            }
        }
        compose.onNodeWithTag("app-log-12").performClick()
        compose.runOnIdle { assertEquals(12L, opened) }
    }

    @Test
    fun livePrependingKeepsTheVisibleLogAnchoredByItsId() {
        val rows = (1L..60L).map { AppLogRow(it, "time $it", "message $it", false) }
        val state = mutableStateOf(AppLogsUiState(logs = rows, isLoading = false))
        compose.setContent {
            LegadoComposeTheme {
                AppLogsScreen(
                    state.value,
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                    Modifier.heightIn(max = 500.dp),
                )
            }
        }
        compose.onNodeWithTag("app-logs-list").performScrollToIndex(30)
        compose.onNodeWithTag("app-log-31").assertIsDisplayed()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    logs = listOf(AppLogRow(100, "new time", "new message", false)) + rows
                )
        }
        compose.onNodeWithTag("app-log-31").assertIsDisplayed()
    }
}
