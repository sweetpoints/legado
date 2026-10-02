package io.legado.app.ui.book.read

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.*
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HighlightStyleScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun everyChannelCheckboxRemainsReachableInShortWindowAndTogglesOnlyItsChannel() {
        var state by mutableStateOf(HighlightStyleState(initialized = true)); val changes = mutableListOf<HighlightChannel>()
        val repo = HighlightStyleRepository()
        compose.setContent { Content(state, toggle = { channel, enabled -> changes += channel; state = state.copy(style = repo.toggle(state.style, channel, enabled)) }) }
        HighlightChannel.entries.forEach { channel ->
            compose.onNodeWithTag("highlight-style-toggle-$channel").performScrollTo().assertIsOff().performClick().assertIsOn()
        }
        compose.runOnIdle { assertEquals(HighlightChannel.entries, changes); assertTrue(HighlightChannel.entries.all { repo.enabled(state.style, it) }) }
    }
    @Test fun enabledColorChannelsHaveAccessibleSwatchesAndBoldItalicDoNot() {
        val repo = HighlightStyleRepository(); val style = HighlightChannel.entries.fold(HighlightStyle()) { value, channel -> repo.toggle(value, channel, true) }
        val colors = mutableListOf<HighlightChannel>()
        compose.setContent { Content(HighlightStyleState(style, true), color = { colors += it }) }
        HighlightChannel.entries.filterNot { it == HighlightChannel.Bold || it == HighlightChannel.Italic }.forEach { channel ->
            compose.onNodeWithTag("highlight-style-color-$channel").performScrollTo().assertContentDescriptionEquals(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(highlightChannelLabel(channel))).performClick()
        }
        compose.onNodeWithTag("highlight-style-color-Bold").assertDoesNotExist(); compose.onNodeWithTag("highlight-style-color-Italic").assertDoesNotExist()
        assertEquals(7, colors.size)
    }
    @Test fun onlyPillShowsPaddingTuningAndUnderlineAndShadowExposeTheirOwnActions() {
        var state by mutableStateOf(HighlightStyleState(HighlightStyle(fill = 7, underline = HighlightStyle.Underline(), shadow = HighlightStyle.Shadow()), true))
        val extras = mutableListOf<HighlightChannel>(); val tunes = mutableListOf<HighlightChannel>()
        compose.setContent { Content(state, extra = { extras += it }, tune = { tunes += it }) }
        compose.onNodeWithTag("highlight-style-tune-Fill").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(style = state.style.copy(fillShape = HighlightStyle.FillShape.PILL)) }
        compose.onNodeWithTag("highlight-style-tune-Fill").performScrollTo().performClick()
        compose.onNodeWithTag("highlight-style-extra-Underline").performScrollTo().performClick()
        compose.onNodeWithTag("highlight-style-tune-Underline").performScrollTo().performClick()
        compose.onNodeWithTag("highlight-style-extra-Shadow").performScrollTo().performClick()
        assertEquals(listOf(HighlightChannel.Fill, HighlightChannel.Underline), tunes)
        assertEquals(listOf(HighlightChannel.Underline, HighlightChannel.Shadow), extras)
    }
    @Test fun presetsAndFontAndNumberRowsDeliverDistinctActionsWithoutChangingEachOther() {
        val presets = mutableListOf<Int>(); var fonts = 0; val numbers = mutableListOf<HighlightNumber>()
        compose.setContent { Content(HighlightStyleState(initialized = true), preset = { presets += it }, font = { fonts++ }, number = { numbers += it }) }
        compose.onNodeWithTag("highlight-style-preset-1").performClick()
        compose.onNodeWithTag("highlight-style-font").performScrollTo().performClick()
        compose.onNodeWithTag("highlight-style-font-size").performScrollTo().performClick()
        compose.onNodeWithTag("highlight-style-letter-spacing").performScrollTo().performClick()
        assertEquals(listOf(1), presets); assertEquals(1, fonts); assertEquals(listOf(HighlightNumber.FontSize, HighlightNumber.LetterSpacing), numbers)
    }
    @Composable private fun Content(state: HighlightStyleState, toggle: (HighlightChannel, Boolean) -> Unit = { _, _ -> },
        color: (HighlightChannel) -> Unit = {}, extra: (HighlightChannel) -> Unit = {}, tune: (HighlightChannel) -> Unit = {},
        preset: (Int) -> Unit = {}, font: () -> Unit = {}, number: (HighlightNumber) -> Unit = {}) {
        LegadoComposeTheme { HighlightStyleScreen(state, HighlightStyleRepository().presets, "Default font", preset, toggle, color, extra, tune,
            font, number, {}, {}, {}, {}, Modifier.heightIn(max = 320.dp)) }
    }
}
