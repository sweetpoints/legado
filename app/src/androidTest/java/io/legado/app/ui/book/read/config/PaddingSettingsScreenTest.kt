package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PaddingSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private class Repository : PaddingSettingsRepository {
        var snapshot = PaddingSnapshot(PaddingRegion.entries.associateWith { RegionPadding(6, 6, 16, 16) })
        var resets = 0
        override fun load() = snapshot
        override fun apply(region: PaddingRegion, side: PaddingSide, value: Int, linkSides: Boolean) {
            var values = snapshot[region].with(side, value)
            if (linkSides && side in listOf(PaddingSide.LEFT, PaddingSide.RIGHT)) values = values.with(if (side == PaddingSide.LEFT) PaddingSide.RIGHT else PaddingSide.LEFT, value)
            snapshot = PaddingSnapshot(snapshot.regions + (region to values))
        }
        override fun setShowLine(region: PaddingRegion, shown: Boolean) { snapshot = PaddingSnapshot(snapshot.regions + (region to snapshot[region].copy(showLine = shown))) }
        override fun reset(region: PaddingRegion) { resets++; snapshot = PaddingSnapshot(snapshot.regions + (region to RegionPadding(0, 0, 16, 16))) }
        override fun save() = Unit
    }
    private fun show(repository: Repository = Repository(), onTracking: (Boolean) -> Unit = {}): PaddingSettingsViewModel {
        val model = PaddingSettingsViewModel(repository, SavedStateHandle())
        compose.setContent { LegadoComposeTheme {
            PaddingSettingsRoute(model, Color.White, Color.Black, onTracking, Modifier.heightIn(max = 600.dp))
        } }
        return model
    }
    @Test fun regionButtonsAndSwitchesExposeAccessibleSelectionAndTouchTargets() {
        show()
        PaddingRegion.entries.forEach { compose.onNodeWithTag("padding-region-${it.name}").assertHeightIsAtLeast(48.dp) }
        compose.onNodeWithTag("padding-region-BODY").assertIsSelected()
        compose.onNodeWithTag("padding-show-line").assertDoesNotExist()
        compose.onNodeWithTag("padding-region-HEADER").performClick()
        compose.onNodeWithTag("padding-show-line").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("padding-lock").performScrollTo().assertIsOn().performClick().assertIsOff()
    }
    @Test fun microAdjustmentButtonsLinkAndUnlinkHorizontalValues() {
        val model = show()
        compose.onNodeWithTag("padding-plus-LEFT").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(17, model.state.value.current.left); assertEquals(17, model.state.value.current.right) }
        compose.onNodeWithTag("padding-lock").performScrollTo().performClick()
        compose.onNodeWithTag("padding-plus-RIGHT").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(17, model.state.value.current.left); assertEquals(18, model.state.value.current.right) }
    }
    @Test fun activeDragDisablesRegionResetAndLockUntilTrackingStops() {
        val model = show()
        compose.runOnIdle { model.startTracking(PaddingSide.TOP) }
        compose.onNodeWithTag("padding-region-HEADER").assertIsNotEnabled()
        compose.onNodeWithTag("padding-reset").assertIsNotEnabled()
        compose.onNodeWithTag("padding-lock").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { model.stopTracking(PaddingSide.TOP) }
        compose.onNodeWithTag("padding-region-HEADER").assertIsEnabled()
        compose.onNodeWithTag("padding-reset").assertIsEnabled()
    }
    @Test fun resetRequiresConfirmationAndCancelDoesNotMutate() {
        val repository = Repository()
        show(repository)
        compose.onNodeWithTag("padding-reset").performClick()
        compose.onNodeWithTag("padding-reset-cancel").performClick()
        compose.runOnIdle { assertEquals(0, repository.resets) }
        compose.onNodeWithTag("padding-reset").performClick()
        compose.onNodeWithTag("padding-reset-confirm").performClick()
        compose.runOnIdle { assertEquals(1, repository.resets) }
        compose.onNodeWithTag("padding-value-TOP").assertTextEquals("0")
    }
    @Test fun keyboardOrAccessibilitySliderChangesAreFlushedWhenInteractionFinishes() {
        val repository = Repository()
        val model = show(repository)
        compose.onNodeWithTag("padding-slider-TOP").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(40f)) }
        compose.runOnIdle { model.finish(PaddingSide.TOP); assertEquals(40, repository.snapshot[PaddingRegion.BODY].top) }
    }
}
