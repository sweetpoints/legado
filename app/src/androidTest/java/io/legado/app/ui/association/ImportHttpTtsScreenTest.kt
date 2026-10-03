package io.legado.app.ui.association

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.HttpTtsImportItem
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportHttpTtsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val items =
        listOf(
            HttpTtsImportItem("new", "New engine", "{}", 4, null),
            HttpTtsImportItem("update", "Updated engine", "{}", 4, 3),
            HttpTtsImportItem("existing", "Stored engine", "{}", 3, 3),
        )

    @Test
    fun checkboxAndRowToggleOnceAndShowActualLocalizedStatus() {
        var state by
            mutableStateOf(ImportHttpTtsState(items, setOf("new", "update"), loading = false))
        compose.setContent {
            Content(
                state,
                toggle = { key ->
                    state =
                        state.copy(
                            selected =
                                if (key in state.selected) state.selected - key
                                else state.selected + key
                        )
                },
            )
        }
        compose.onNodeWithTag("tts-import-check-existing").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(3, state.selectCount) }
        compose.onNodeWithTag("tts-import-row-existing").performClick()
        compose.onNodeWithTag("tts-import-check-existing").assertIsOff()
        listOf(
                R.string.import_status_new,
                R.string.import_status_update,
                R.string.import_status_exist,
            )
            .forEach {
                compose.onNodeWithText(context.getString(it)).assertExists()
            }
    }

    @Test
    fun selectAllCountsAndCodeAreIndependentFromRowSelection() {
        var state by
            mutableStateOf(ImportHttpTtsState(items, setOf("new", "update"), loading = false))
        val edits = mutableListOf<String>()
        compose.setContent {
            Content(
                state,
                all = {
                    state =
                        state.copy(
                            selected =
                                if (state.isSelectAll) emptySet()
                                else items.mapTo(mutableSetOf()) { it.key }
                        )
                },
                code = { edits += it },
            )
        }
        compose
            .onNodeWithTag("tts-import-select-all")
            .assertTextEquals(context.getString(R.string.select_all_count, 2, 3))
            .performClick()
            .assertTextEquals(context.getString(R.string.select_cancel_count, 3, 3))
        compose.onNodeWithTag("tts-import-code-existing").performClick()
        compose.runOnIdle {
            assertEquals(listOf("existing"), edits)
            assertEquals(3, state.selectCount)
        }
        compose.onNodeWithTag("tts-import-select-all").performClick()
        compose.runOnIdle { assertEquals(0, state.selectCount) }
    }

    @Test
    fun busyStateDisablesMutationAndCloseAndDisplaysProgress() {
        compose.setContent { Content(ImportHttpTtsState(items, loading = false, busy = true)) }
        compose.onNodeWithTag("tts-import-check-new").assertIsNotEnabled()
        compose.onNodeWithTag("tts-import-code-new").assertIsNotEnabled()
        compose.onNodeWithTag("tts-import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("tts-import-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("tts-import-progress").assertExists()
    }

    @Test
    fun failedReadExposesRetryAndBlocksConfirmation() {
        var retries = 0
        var cancels = 0
        compose.setContent {
            Content(
                ImportHttpTtsState(loading = false, error = "ImportError:format"),
                retry = { retries++ },
                cancel = { cancels++ },
            )
        }
        compose.onNodeWithText("ImportError:format").assertExists()
        compose.onNodeWithTag("tts-import-confirm").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.onNodeWithTag("tts-import-cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, cancels)
        }
    }

    @Test
    fun shortWindowKeepsFooterReachableWhileListScrolls() {
        val many = List(30) { HttpTtsImportItem("$it", "Engine $it", "{}", 1, null) }
        var confirms = 0
        compose.setContent {
            Content(
                ImportHttpTtsState(many, loading = false),
                confirm = { confirms++ },
                height = 280,
            )
        }
        compose.onNodeWithTag("tts-import-list").performScrollToIndex(29)
        compose.onNodeWithText("Engine 29").assertIsDisplayed()
        compose.onNodeWithTag("tts-import-confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }

    @Composable
    private fun Content(
        state: ImportHttpTtsState,
        toggle: (String) -> Unit = {},
        all: () -> Unit = {},
        code: (String) -> Unit = {},
        confirm: () -> Unit = {},
        cancel: () -> Unit = {},
        retry: () -> Unit = {},
        height: Int = 500,
    ) {
        LegadoComposeTheme {
            ImportHttpTtsScreen(
                state,
                toggle,
                all,
                code,
                confirm,
                cancel,
                retry,
                Modifier.height(height.dp),
            )
        }
    }
}
