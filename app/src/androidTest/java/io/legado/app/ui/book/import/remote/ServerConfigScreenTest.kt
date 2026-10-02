package io.legado.app.ui.book.import.remote

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ServerConfigScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun editsActualWebdavFieldsAndDispatchesSave() {
        var state by mutableStateOf(ServerConfigUiState(loading = false)); var saves = 0
        compose.setContent { LegadoComposeTheme { ServerConfigScreen(state, { field, value -> state = state.copy(draft = when (field) {
            ServerConfigField.Name -> state.draft.copy(name = value); ServerConfigField.Url -> state.draft.copy(url = value)
            ServerConfigField.Username -> state.draft.copy(username = value); ServerConfigField.Password -> state.draft.copy(password = value)
        }) }, { saves++ }, {}, {}) } }
        ServerConfigField.entries.forEach { compose.onNodeWithTag("server-field-${it.name}").performScrollTo().performTextInput(it.name) }
        compose.onNodeWithTag("server-save").performClick()
        compose.runOnIdle { assertEquals(ServerConfigDraft("Name", "Url", "Username", "Password"), state.draft); assertEquals(1, saves) }
    }
    @Test fun failedLoadDisablesWritesAndExposesRetry() {
        var retries = 0
        compose.setContent { LegadoComposeTheme { ServerConfigScreen(ServerConfigUiState(loading = false, loadFailed = true, error = "failed"), { _, _ -> }, {}, {}, { retries++ }) } }
        compose.onNodeWithTag("server-save").assertIsNotEnabled()
        compose.onNodeWithTag("server-field-Name").assertIsNotEnabled()
        compose.onNodeWithTag("server-retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun savingBlocksCloseAndDuplicateSave() {
        compose.setContent { LegadoComposeTheme { ServerConfigScreen(ServerConfigUiState(loading = false, saving = true), { _, _ -> }, {}, {}, {}) } }
        compose.onNodeWithTag("server-save").assertIsNotEnabled(); compose.onNodeWithTag("server-close").assertIsNotEnabled()
    }
}
