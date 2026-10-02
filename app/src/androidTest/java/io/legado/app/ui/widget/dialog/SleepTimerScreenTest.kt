package io.legado.app.ui.widget.dialog

import io.legado.app.data.preferences.SleepTimerMode
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.preferences.SleepTimerPreferences
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.widget.dialog.sleeptimer.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SleepTimerScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun minuteAndChapterPresetsEmitTheirModeAndValue() {
        val selected = mutableListOf<SleepTimerSelection>()
        compose.setContent {
            LegadoComposeTheme {
                SleepTimerScreen(SleepTimerUiState(minute = 30), { mode, value -> selected += SleepTimerSelection(mode, value) }, {}, {}, {}, {},
                    Modifier.heightIn(max = 400.dp))
            }
        }
        compose.onNodeWithTag("sleep-minutes-30").assertIsSelected()
        for (value in listOf(15, 30, 45, 60)) compose.onNodeWithTag("sleep-minutes-$value").performScrollTo().performClick()
        for (value in listOf(1, 2, 3, 5)) compose.onNodeWithTag("sleep-chapters-$value").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(15, 30, 45, 60).map { SleepTimerSelection(SleepTimerMode.Minutes, it) } +
                listOf(1, 2, 3, 5).map { SleepTimerSelection(SleepTimerMode.Chapters, it) }, selected)
        }
    }

    @Test fun audioEpisodesAndReadAloudChaptersUseTheirRespectiveStatusAndLabels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = mutableStateOf(SleepTimerUiState(chapter = 3, useEpisodes = true))
        compose.setContent { LegadoComposeTheme { SleepTimerScreen(state.value, { _, _ -> }, {}, {}, {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.audio_stop_chapters, 3)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sleep_timer_by_episode)).assertExists()
        compose.runOnIdle { state.value = state.value.copy(useEpisodes = false) }
        compose.onNodeWithText(context.getString(R.string.sleep_timer_chapters, 3)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sleep_timer_by_chapter)).assertExists()
    }

    @Test fun customButtonsInputConfirmAndImeEmitCallbacksOnSmallScreen() {
        val modes = mutableListOf<SleepTimerMode>()
        var input: String? = null
        var confirmations = 0
        compose.setContent {
            LegadoComposeTheme {
                SleepTimerScreen(SleepTimerUiState(customMode = SleepTimerMode.Minutes, input = "12"),
                    { _, _ -> }, { modes += it }, { input = it }, { confirmations++ }, {}, Modifier.heightIn(max = 220.dp))
            }
        }
        compose.onNodeWithTag("sleep-custom-minutes").performScrollTo().performClick()
        compose.onNodeWithTag("sleep-custom-chapters").performScrollTo().performClick()
        compose.onNodeWithTag("sleep-custom-input").performScrollTo().performTextReplacement("42")
        compose.onNodeWithTag("sleep-custom-input").performImeAction()
        compose.onNodeWithTag("sleep-custom-confirm").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(SleepTimerMode.Minutes, SleepTimerMode.Chapters), modes)
            assertEquals("42", input)
            assertEquals(2, confirmations)
        }
    }

    @Test fun customValidationUsesTheCurrentModesMaximum() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            LegadoComposeTheme {
                SleepTimerScreen(SleepTimerUiState(customMode = SleepTimerMode.Chapters, input = "100", showValidation = true),
                    { _, _ -> }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.sleep_timer_range_hint, 99)).assertExists()
    }

    @Test fun inactiveTimerDisablesOffAndActiveTimerEmitsOff() {
        var off = 0
        val state = mutableStateOf(SleepTimerUiState())
        compose.setContent { LegadoComposeTheme { SleepTimerScreen(state.value, { _, _ -> }, {}, {}, {}, { off++ }) } }
        compose.onNodeWithTag("sleep-off").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(chapter = 3) }
        compose.onNodeWithTag("sleep-off").performClick()
        compose.runOnIdle { assertEquals(1, off) }
    }

    @Test fun routeDeliversSelectionOnceAndPreventsDoubleSubmission() {
        val selected = mutableListOf<SleepTimerSelection>()
        lateinit var model: SleepTimerViewModel
        compose.runOnIdle {
            model = SleepTimerViewModel(object : SleepTimerPreferences {
                override fun lastCustom(mode: SleepTimerMode) = 0
                override fun rememberCustom(mode: SleepTimerMode, value: Int) = Unit
                override fun preferChapters(chapters: Boolean) = Unit
            }, SavedStateHandle())
        }
        compose.setContent { LegadoComposeTheme { SleepTimerRoute(model, { selected += it }, {}) } }
        compose.onNodeWithTag("sleep-minutes-30").performClick()
        compose.waitUntil { selected.size == 1 }
        compose.runOnIdle { model.selectPreset(SleepTimerMode.Chapters, 3) }
        compose.runOnIdle { assertEquals(listOf(SleepTimerSelection(SleepTimerMode.Minutes, 30)), selected) }
        compose.onNodeWithTag("sleep-minutes-30").assertIsNotEnabled()
    }

    @Test fun restoredFinishedRouteClosesWithoutRepeatingServiceCallback() {
        var callbacks = 0
        var closes = 0
        val preferences = object : SleepTimerPreferences {
            override fun lastCustom(mode: SleepTimerMode) = 0
            override fun rememberCustom(mode: SleepTimerMode, value: Int) = Unit
            override fun preferChapters(chapters: Boolean) = Unit
        }
        lateinit var restored: SleepTimerViewModel
        compose.runOnIdle {
            val handle = SavedStateHandle()
            val original = SleepTimerViewModel(preferences, handle)
            original.selectPreset(SleepTimerMode.Minutes, 30)
            original.consumeSelection()
            restored = SleepTimerViewModel(preferences,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        }
        compose.setContent { LegadoComposeTheme { SleepTimerRoute(restored, { callbacks++ }, { closes++ }) } }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { assertEquals(0, callbacks); assertEquals(1, closes) }
    }

    @Test fun newInstanceRetainsArgumentsForBothPlaybackHosts() {
        val audio = SleepTimerDialog.newInstance(30, 3, useEpisodes = true)
        assertEquals(30, audio.arguments?.getInt("minute"))
        assertEquals(3, audio.arguments?.getInt("chapter"))
        assertTrue(audio.arguments?.getBoolean("episodes") == true)
        assertFalse(SleepTimerDialog.newInstance(30, 3).arguments?.getBoolean("episodes") == true)
    }
}
