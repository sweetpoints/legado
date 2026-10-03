package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.AutoReadSettingsRepository
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AutoReadScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun catalogMenuStopAndSettingsEmitIndependentCallbacks() {
        val calls = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                AutoReadScreen(
                    AutoReadUiState(),
                    Color.Black,
                    Color.White,
                    {},
                    {},
                    { calls += "catalog" },
                    { calls += "menu" },
                    { calls += "stop" },
                    { calls += "settings" },
                    Modifier.heightIn(max = 400.dp),
                )
            }
        }
        compose.onNodeWithTag("auto-read-catalog").performClick()
        compose.onNodeWithTag("auto-read-menu").performClick()
        compose.onNodeWithTag("auto-read-stop").performClick()
        compose.onNodeWithTag("auto-read-settings").performClick()
        compose.runOnIdle { assertEquals(listOf("catalog", "menu", "stop", "settings"), calls) }
    }

    @Test
    fun accessibleSliderCommitsSpeedAfterValueCallback() {
        var state by mutableStateOf(AutoReadUiState())
        val events = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                AutoReadScreen(
                    state,
                    Color.White,
                    Color.Black,
                    {
                        state = state.copy(speed = it)
                        events += "speed:$it"
                    },
                    { events += "finished" },
                    {},
                    {},
                    {},
                    {},
                    Modifier.heightIn(max = 400.dp),
                )
            }
        }
        compose.onNodeWithTag("auto-read-speed").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(120f)
        }
        compose.runOnIdle {
            assertEquals(120, state.speed)
            assertEquals(listOf("speed:120", "finished"), events)
        }
    }

    @Test
    fun routeDeliversTtsOnlyAfterGestureCommitAndConsumesEffect() {
        lateinit var model: AutoReadViewModel
        val saved = mutableListOf<Int>()
        var ttsUpdates = 0
        compose.runOnIdle {
            model =
                AutoReadViewModel(
                    object : AutoReadSettingsRepository {
                        override fun readSpeed() = 10

                        override fun saveSpeed(speed: Int) {
                            saved += speed
                        }
                    },
                    SavedStateHandle(),
                )
        }
        compose.setContent {
            LegadoComposeTheme {
                AutoReadRoute(
                    model,
                    Color.Black,
                    Color.White,
                    {
                        assertEquals(listOf(35), saved)
                        ttsUpdates++
                    },
                    {},
                    {},
                    {},
                    {},
                    Modifier.heightIn(max = 400.dp),
                )
            }
        }
        compose.runOnIdle {
            model.changeSpeed(35)
            assertTrue(saved.isEmpty())
            assertEquals(0, ttsUpdates)
        }
        compose.runOnIdle { model.finishChangingSpeed() }
        compose.waitUntil { ttsUpdates == 1 }
        compose.runOnIdle {
            assertEquals(0, model.state.value.ttsUpdate)
            assertEquals(listOf(35), saved)
        }
    }
}
