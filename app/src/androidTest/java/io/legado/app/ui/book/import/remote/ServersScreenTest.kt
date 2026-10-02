package io.legado.app.ui.book.import.remote

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.legado.app.data.repository.RemoteServerChoice
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ServersScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun selectionAndEditorActionsUseCorrectServerIdentity() {
        var state by mutableStateOf(ServersUiState(listOf(RemoteServerChoice(1,"One"), RemoteServerChoice(2,"Two")), 1, loading = false))
        var edited = 0L; var adds = 0
        compose.setContent { LegadoComposeTheme { ServersScreen(state, { state = state.copy(selected = it) }, { adds++ }, { edited = it }, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("servers-row-2").performClick().assertIsSelected()
        compose.onNodeWithTag("servers-edit-1").performClick(); compose.onNodeWithTag("servers-add").performClick()
        compose.runOnIdle { assertEquals(2L,state.selected); assertEquals(1L,edited); assertEquals(1,adds) }
    }
    @Test fun deleteRequiresExplicitConfirmation() {
        var state by mutableStateOf(ServersUiState(listOf(RemoteServerChoice(7,"Server")), 7, loading = false)); var deletes = 0
        compose.setContent { LegadoComposeTheme { ServersScreen(state, {}, {}, {}, { state = state.copy(deleteId = it) }, { deletes++ }, {}, {}, {}, {}) } }
        compose.onNodeWithTag("servers-delete-7").performClick(); compose.runOnIdle { assertEquals(0,deletes) }
        compose.onNodeWithTag("servers-confirm-delete-7").performClick(); compose.runOnIdle { assertEquals(1,deletes) }
    }
    @Test fun defaultApplyAndCancelHaveIndependentActions() {
        val actions = mutableListOf<String>()
        compose.setContent { LegadoComposeTheme { ServersScreen(ServersUiState(selected = 1, loading = false), {}, {}, {}, {}, {}, { actions += "apply" }, { actions += "default" }, { actions += "cancel" }, {}) } }
        compose.onNodeWithTag("servers-default").performClick(); compose.onNodeWithTag("servers-apply").performClick(); compose.onNodeWithTag("servers-cancel").performClick()
        compose.runOnIdle { assertEquals(listOf("default","apply","cancel"),actions) }
    }
}
