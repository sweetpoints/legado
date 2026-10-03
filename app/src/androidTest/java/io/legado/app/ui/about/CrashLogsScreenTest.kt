package io.legado.app.ui.about

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.CrashLogEntry
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CrashLogsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun rowsAndClearEmitTheirCallbacks() {
        var opened: String? = null
        var clears = 0
        val entry = CrashLogEntry("local/log", "2026-10-02.log")
        compose.setContent {
            LegadoComposeTheme {
                CrashLogsScreen(
                    CrashLogsUiState(logs = listOf(entry), isLoading = false),
                    { opened = it },
                    { clears++ },
                    {},
                    {},
                    Modifier.heightIn(max = 500.dp),
                )
            }
        }
        compose.onNodeWithText(entry.name).performClick()
        compose.onNodeWithTag("crash-logs-clear").performClick()
        compose.runOnIdle {
            assertEquals(entry.id, opened)
            assertEquals(1, clears)
        }
    }

    @Test
    fun busyStateDisablesClearAndEmptyStateAppearsAfterLoad() {
        val state = mutableStateOf(CrashLogsUiState())
        compose.setContent {
            LegadoComposeTheme {
                CrashLogsScreen(state.value, {}, {}, {}, {}, Modifier.heightIn(max = 500.dp))
            }
        }
        compose.onNodeWithTag("crash-logs-clear").assertIsNotEnabled()
        compose.onNodeWithTag("crash-logs-progress").assertExists()
        compose.onNodeWithTag("crash-logs-empty").assertDoesNotExist()
        compose.runOnIdle { state.value = CrashLogsUiState(isLoading = false) }
        compose.onNodeWithTag("crash-logs-progress").assertDoesNotExist()
        compose.onNodeWithTag("crash-logs-empty").assertExists()
    }

    @Test
    fun errorOffersRetryWithoutLosingExistingRows() {
        var retries = 0
        compose.setContent {
            LegadoComposeTheme {
                CrashLogsScreen(
                    CrashLogsUiState(
                        logs = listOf(CrashLogEntry("id", "remaining.log")),
                        isLoading = false,
                        error = "Permission denied",
                    ),
                    {},
                    {},
                    { retries++ },
                    {},
                    Modifier.heightIn(max = 500.dp),
                )
            }
        }
        compose.onNodeWithText("Permission denied").assertExists()
        compose.onNodeWithText("remaining.log").assertExists()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
