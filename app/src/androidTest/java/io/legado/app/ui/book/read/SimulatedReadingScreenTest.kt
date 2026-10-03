package io.legado.app.ui.book.read

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.legado.app.data.repository.SimulatedReadingSettings
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class SimulatedReadingScreenTest {
    @get:Rule val compose = createComposeRule()
    private val initial = SimulatedReadingSettings(false, "2026-10-03", "5", "3", 12)

    @Test
    fun formAllowsDisabledSimulationToEditAllSettingsAndOnlyExplicitConfirmSaves() {
        var state by mutableStateOf(SimulatedReadingState(loading = false, settings = initial))
        var saved = 0
        var cancelled = 0
        compose.setContent {
            LegadoComposeTheme {
                SimulatedReadingScreen(
                    state,
                    { state = state.copy(settings = state.settings!!.copy(enabled = it)) },
                    { state = state.copy(settings = state.settings!!.copy(start = it)) },
                    { state = state.copy(settings = state.settings!!.copy(daily = it)) },
                    {},
                    {},
                    { saved++ },
                    { cancelled++ },
                    {},
                )
            }
        }
        compose.onNodeWithTag("simulation-start").performTextReplacement("27")
        compose.onNodeWithTag("simulation-daily").performTextReplacement("9")
        compose.onNodeWithTag("simulation-enabled").performClick()
        compose.runOnIdle {
            assertTrue(state.settings!!.enabled)
            assertEquals("27", state.settings!!.start)
            assertEquals(0, saved)
        }
        compose.onNodeWithTag("simulation-cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertEquals(0, saved)
        }
        compose.onNodeWithTag("simulation-save").performClick()
        compose.runOnIdle { assertEquals(1, saved) }
    }

    @Test
    fun dateActionPreservesDraftAndCommitBlocksAllEditableControls() {
        var state by mutableStateOf(SimulatedReadingState(loading = false, settings = initial))
        var opens = 0
        compose.setContent {
            LegadoComposeTheme {
                SimulatedReadingScreen(state, {}, {}, {}, {}, { opens++ }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("simulation-date").performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            state = state.copy(saving = true)
        }
        compose.onNodeWithTag("simulation-enabled").assertIsNotEnabled()
        compose.onNodeWithTag("simulation-date").assertIsNotEnabled()
        compose.onNodeWithTag("simulation-start").assertIsNotEnabled()
        compose.onNodeWithTag("simulation-daily").assertIsNotEnabled()
        compose.onNodeWithTag("simulation-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("simulation-save").assertIsNotEnabled()
    }

    @Test
    fun calendarUsesExactDateAndCancelDiscardsSelectionWhileConfirmDeliversIt() {
        var state by
            mutableStateOf(
                SimulatedReadingState(loading = false, settings = initial, dateOpen = true)
            )
        val dates = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                SimulatedReadingScreen(
                    state,
                    {},
                    {},
                    {},
                    { value ->
                        dates += value
                        state = state.copy(dateOpen = false)
                    },
                    { state = state.copy(dateOpen = it) },
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithTag("simulation-date-cancel").performClick()
        compose.onNodeWithTag("simulation-date-confirm").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(dates.isEmpty())
            assertEquals(initial, state.settings)
            state = state.copy(dateOpen = true)
        }
        compose.onNodeWithTag("simulation-date-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("2026-10-03"), dates) }
    }
}
