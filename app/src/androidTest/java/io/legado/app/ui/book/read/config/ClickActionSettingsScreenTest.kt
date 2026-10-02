package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.preferences.ClickActionRegion
import io.legado.app.data.preferences.ClickActionSettingsRepository
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ClickActionSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private class Repository : ClickActionSettingsRepository {
        var actions = ClickActionRegion.entries.associateWith { it.defaultAction }
        override fun load() = actions
        override fun setAction(region: ClickActionRegion, action: Int) { actions = actions + (region to action) }
        override fun ensureMenuAction() { }
        override fun observe(onChange: () -> Unit) = AutoCloseable { }
    }
    @Test fun nineRegionsPickAllActionsAndUpdateOnlyTheirOwnCell() {
        val repo = Repository(); val model = ClickActionSettingsViewModel(repo, SavedStateHandle())
        compose.setContent { LegadoComposeTheme { ClickActionSettingsRoute(model, {}) } }
        ClickActionRegion.entries.forEach {
            compose.onNodeWithTag("click-action-region-${it.name}").assertExists()
        }
        compose.onNodeWithTag("click-action-region-TopLeft").performClick()
        compose.onNodeWithTag("click-action-option-2").assertIsSelected()
        compose.onNodeWithTag("click-action-option-13").performScrollTo().performClick()
        compose.onNodeWithTag("click-action-picker").assertDoesNotExist()
        val pause = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.read_aloud_pause_resume)
        compose.onNodeWithTag("click-action-region-TopLeft").assertTextEquals(pause)
        compose.runOnIdle {
            assertEquals(13, repo.actions[ClickActionRegion.TopLeft])
            assertEquals(0, repo.actions[ClickActionRegion.MiddleCenter])
        }
    }
    @Test fun cancellingPickerDoesNotChangePersistedActionAndCloseCallsHost() {
        val repo = Repository(); val model = ClickActionSettingsViewModel(repo, SavedStateHandle())
        var closes = 0
        compose.setContent { LegadoComposeTheme { ClickActionSettingsRoute(model, { closes++ }) } }
        compose.onNodeWithTag("click-action-region-BottomCenter").performClick()
        compose.onNodeWithTag("click-action-picker-cancel").performClick()
        compose.onNodeWithTag("click-action-picker").assertDoesNotExist()
        compose.onNodeWithTag("click-action-close").performClick()
        compose.runOnIdle { assertEquals(1, closes); assertEquals(1, repo.actions[ClickActionRegion.BottomCenter]) }
    }
    @Test fun bottomGridRowStaysAboveNavigationInsetAndAllCellsKeepEqualSize() {
        var navigationBottom = 0
        var density = 1f
        val state = ClickActionSettingsUiState(ClickActionRegion.entries.associateWith { it.defaultAction })
        compose.setContent { LegadoComposeTheme {
            density = LocalDensity.current.density
            navigationBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
            ClickActionSettingsScreen(state, {}, {}, {}, {})
        } }
        val root = compose.onNodeWithTag("click-action-root").fetchSemanticsNode().boundsInRoot
        val bottom = compose.onNodeWithTag("click-action-region-BottomCenter").fetchSemanticsNode().boundsInRoot
        assertTrue(root.bottom - bottom.bottom >= navigationBottom + 6f * density - 1f)
        val first = compose.onNodeWithTag("click-action-region-TopLeft").fetchSemanticsNode().boundsInRoot
        ClickActionRegion.entries.forEach {
            val bounds = compose.onNodeWithTag("click-action-region-${it.name}").fetchSemanticsNode().boundsInRoot
            assertEquals(first.width, bounds.width, 1f)
            assertEquals(first.height, bounds.height, 1f)
        }
    }
}
