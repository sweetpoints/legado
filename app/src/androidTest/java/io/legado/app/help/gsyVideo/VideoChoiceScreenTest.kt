package io.legado.app.help.gsyVideo

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VideoChoiceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectedEpisodeStartsVisibleAndDeliversItsAbsoluteIndex() {
        val selections = mutableListOf<Int>()
        compose.setContent {
            LegadoComposeTheme {
                VideoChoiceScreen("选集（100）", List(100) { "集 $it" }, 80, selections::add)
            }
        }
        compose.onNodeWithTag("video-choice-80").assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(listOf(80), selections) }
    }

    @Test
    fun speedSelectionKeepsProvidedOrdering() {
        val selections = mutableListOf<Int>()
        compose.setContent {
            LegadoComposeTheme {
                VideoChoiceScreen("倍速", listOf("3.0X", "2.5X", "2.0X"), onSelect = selections::add)
            }
        }
        compose.onNodeWithTag("video-choice-2").performClick()
        compose.runOnIdle { assertEquals(listOf(2), selections) }
    }
}
