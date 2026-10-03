package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.model.cover.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class CoverFontSettingsComposeTest {
    @get:Rule val compose = createComposeRule()
    private fun initial() = CoverFontSettingsState(loading = false, settings = CoverFontSettingsSnapshot())
    private fun actions() = CoverFontSettingsActions({ _, _ -> }, {}, {}, {}, {}, {}, {})
    private fun row(key: String) = compose.onNodeWithTag("cover-font-settings-list").performScrollToNode(hasTestTag("cover-font-row-$key")).let { compose.onNodeWithTag("cover-font-row-$key") }
    @Test fun customSizeControlsAllFourEditorsAndEnablingPreservesPercentSummaries() {
        var state by mutableStateOf(initial()); val edited = mutableListOf<CoverFontSize>()
        compose.setContent { LegadoComposeTheme { CoverFontSettingsScreen(state, actions().copy(boolean = { key, value ->
            state = state.copy(settings = state.settings!!.copy(switches = state.settings!!.switches + (key to value))) }, edit = { edited += it })) } }
        CoverFontSize.entries.forEach { row(it.key).assertIsNotEnabled().assertTextContains("100%") }
        row(CoverFontSwitch.CustomSize.key).assertHeightIsAtLeast(48.dp).performClick()
        CoverFontSize.entries.forEach { row(it.key).assertIsEnabled().performClick() }; assertEquals(CoverFontSize.entries, edited)
    }
    @Test fun numericTextSupportsPartialAndEmptyRestoreAndClampsOverflowWithoutPublishingInvalidPercent() {
        var state by mutableStateOf(initial().copy(editing = CoverFontSize.TitleSmall)); val values = mutableListOf<Int>(); val confirmed = mutableListOf<Boolean>()
        val restore = StateRestorationTester(compose)
        restore.setContent { LegadoComposeTheme { CoverFontSettingsScreen(state, actions().copy(number = { values += it; state = state.copy(number = it) }, confirm = { confirmed += it })) } }
        compose.onNodeWithTag("cover-font-number").performTextReplacement("1"); compose.onNodeWithTag("cover-font-confirm").assertIsNotEnabled(); assertTrue(values.isEmpty())
        compose.onNodeWithTag("cover-font-number").performTextReplacement("150"); compose.onNodeWithTag("cover-font-confirm").assertIsEnabled(); assertEquals(listOf(150), values)
        compose.onNodeWithTag("cover-font-number").performTextReplacement("999"); compose.onNodeWithTag("cover-font-number").assertTextEquals("200")
        compose.onNodeWithTag("cover-font-number").performTextReplacement(""); restore.emulateSavedInstanceStateRestore(); compose.onNodeWithTag("cover-font-number").assertTextEquals("")
        compose.onNodeWithTag("cover-font-confirm").assertIsNotEnabled(); compose.onNodeWithTag("cover-font-default").performClick(); assertEquals(listOf(true), confirmed)
    }
    @Test fun bothComposeCoverPreviewsAndFontDestinationAreAvailableInScrollableContent() {
        var selected = 0
        compose.setContent { LegadoComposeTheme { CoverFontSettingsScreen(initial(), actions().copy(font = { selected++ })) } }
        row("coverFont").performClick(); assertEquals(1, selected); row("coverPreview").assertIsDisplayed()
        compose.onNodeWithTag("cover-font-preview-short").assertExists(); compose.onNodeWithTag("cover-font-preview-long").assertExists()
    }
    @Test fun searchPositionsExactSizeWithoutOpeningEditorAndFailedLoadOffersExplicitRetry() {
        val title = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cover_author_small_size)
        var search by mutableStateOf<String?>(title); var edited = 0; var retry = 0; var state by mutableStateOf(initial())
        compose.setContent { LegadoComposeTheme { CoverFontSettingsScreen(state, actions().copy(edit = { edited++ }, retry = { retry++ }), search, { search = null }) } }
        compose.onNodeWithTag("cover-font-search-${CoverFontSize.AuthorSmall.key}").performScrollTo().performClick()
        compose.onNodeWithTag("cover-font-row-${CoverFontSize.AuthorSmall.key}").assertIsDisplayed(); assertEquals(0, edited)
        compose.runOnIdle { state = state.copy(failed = true, error = "read failed") }; row("coverFont").assertIsNotEnabled()
        compose.onNodeWithTag("cover-font-retry").performScrollTo().performClick(); assertEquals(1, retry)
    }
}
