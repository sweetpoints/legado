package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadAloudSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private class Repository : ReadAloudSettingsRepository {
        var preferences = ReadAloudPreferences(ReadAloudSwitch.entries.associateWith { false })
        var listener: ((String?) -> Unit)? = null
        var engine = "Custom engine"

        override fun load() = preferences

        override fun setSwitch(setting: ReadAloudSwitch, enabled: Boolean) {
            preferences = preferences.copy(switches = preferences.switches + (setting to enabled))
            listener?.invoke(setting.key)
        }

        override fun setStart(mode: String) {
            preferences = preferences.copy(start = mode)
        }

        override fun observeChanges(onChange: (String?) -> Unit): AutoCloseable {
            listener = onChange
            return AutoCloseable { listener = null }
        }

        override suspend fun engineName() = engine

        override fun playbackRunning() = false

        override fun notifyPlaybackConfigurationChanged() = Unit
    }

    private fun show(
        repository: Repository = Repository(),
        onNavigate: (ReadAloudSettingsDestination) -> Boolean = { true },
    ): ReadAloudSettingsViewModel {
        val model = ReadAloudSettingsViewModel(repository, SavedStateHandle())
        compose.setContent {
            LegadoComposeTheme {
                ReadAloudSettingsRoute(
                    model,
                    Color.White,
                    onNavigate,
                    Modifier.heightIn(max = 550.dp),
                )
            }
        }
        return model
    }

    @Test
    fun ignoreFocusEnablesCallPauseAndDisablingItKeepsTheStoredChoice() {
        show()
        val ignore = "read-aloud-switch-${ReadAloudSwitch.IgnoreAudioFocus.key}"
        val calls = "read-aloud-switch-${ReadAloudSwitch.PauseDuringCalls.key}"
        compose.onNodeWithTag(calls).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(ignore).performScrollTo().performClick()
        compose.onNodeWithTag(calls).performScrollTo().assertIsEnabled().performClick().assertIsOn()
        compose.onNodeWithTag(ignore).performScrollTo().performClick()
        compose.onNodeWithTag(calls).performScrollTo().assertIsNotEnabled().assertIsOn()
    }

    @Test
    fun allSevenSwitchesHaveAccessibleRowsAndExpectedCallbacks() {
        val repository = Repository()
        show(repository)
        ReadAloudSwitch.entries.forEach {
            compose
                .onNodeWithTag("read-aloud-switch-${it.key}")
                .performScrollTo()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
        }
        compose.runOnIdle { assertTrue(repository.preferences.switches.values.all { it }) }
    }

    @Test
    fun startPickerShowsSelectionAndPersistsThePageChoiceImmediatelyAboveControls() {
        val repository = Repository()
        show(repository)
        compose.onNodeWithTag("read-aloud-controls").performScrollTo()
        val startBounds =
            compose.onNodeWithTag("read-aloud-start").fetchSemanticsNode().boundsInRoot
        val controlsBounds =
            compose.onNodeWithTag("read-aloud-controls").fetchSemanticsNode().boundsInRoot
        assertTrue(startBounds.bottom <= controlsBounds.top)
        compose.onNodeWithTag("read-aloud-start").performScrollTo().performClick()
        compose.onNodeWithTag("read-aloud-start-sentence").assertIsSelected()
        compose.onNodeWithTag("read-aloud-start-page").performClick()
        compose.runOnIdle { assertEquals("page", repository.preferences.start) }
        compose.onNodeWithTag("read-aloud-start-sentence").assertDoesNotExist()
    }

    @Test
    fun navigationRowsEmitTheMatchingActionAndEngineSummaryRefreshes() {
        val destinations = mutableListOf<ReadAloudSettingsDestination>()
        val repository = Repository()
        val model =
            show(repository) {
                destinations += it
                true
            }
        compose
            .onNodeWithTag("read-aloud-engine")
            .performScrollTo()
            .assertTextContains("Custom engine")
            .performClick()
        compose.onNodeWithTag("read-aloud-controls").performScrollTo().performClick()
        compose.onNodeWithTag("read-aloud-system-tts").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    ReadAloudSettingsDestination.Engine,
                    ReadAloudSettingsDestination.Controls,
                    ReadAloudSettingsDestination.SystemTts,
                ),
                destinations,
            )
            repository.engine = "New selected engine"
            model.refreshEngine()
        }
        compose
            .onNodeWithTag("read-aloud-engine")
            .performScrollTo()
            .assertTextContains("New selected engine")
    }
}
