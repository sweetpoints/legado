package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.help.TextSelectMenuConfig
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextSelectMenuSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val actions = mutableListOf<TextSelectMenuSettingsAction>()

    private fun show() {
        val config = TextSelectMenuConfig.default()
        val state =
            TextSelectMenuSettingsUiState(
                listOf(TextSelectMenuSettingsViewModel.ZONE_BAR) +
                    config.bar +
                    TextSelectMenuSettingsViewModel.ZONE_MORE +
                    config.more
            )
        compose.setContent {
            LegadoComposeTheme {
                TextSelectMenuSettingsScreen(
                    state,
                    { actions += it },
                    {},
                    Modifier.heightIn(max = 650.dp),
                )
            }
        }
    }

    @Test
    fun transferButtonsAndResetKeepTheirExactActionsAndTouchTargets() {
        show()
        compose
            .onNodeWithTag("text-select-menu-transfer-copy")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("text-select-menu-transfer-dict").performScrollTo().performClick()
        compose.onNodeWithTag("text-select-menu-actions").performClick()
        compose.onNodeWithTag("text-select-menu-reset").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    TextSelectMenuSettingsAction.Transfer("copy"),
                    TextSelectMenuSettingsAction.Transfer("dict"),
                    TextSelectMenuSettingsAction.Reset,
                ),
                actions,
            )
        }
    }

    @Test
    fun dragCanCrossDividerAndConsumesReleaseWithoutTransfer() {
        show()
        val list = compose.onNodeWithTag("text-select-menu-list").fetchSemanticsNode().boundsInRoot
        val source =
            compose.onNodeWithTag("text-select-menu-drag-aloud").fetchSemanticsNode().boundsInRoot
        val target =
            compose.onNodeWithTag("text-select-menu-item-dict").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("text-select-menu-list").performTouchInput {
            down(Offset(source.center.x - list.left, source.center.y - list.top))
            moveTo(Offset(source.center.x - list.left, target.center.y - list.top))
            up()
        }
        compose.runOnIdle {
            assertEquals(TextSelectMenuSettingsAction.StartDrag("aloud"), actions.first())
            assertTrue(actions.contains(TextSelectMenuSettingsAction.MoveTo("dict")))
            assertEquals(TextSelectMenuSettingsAction.FinishDrag(true), actions.last())
            assertFalse(actions.any { it is TextSelectMenuSettingsAction.Transfer })
        }
    }

    @Test
    fun cancellingPointerEndsGestureWithoutCommit() {
        show()
        val list = compose.onNodeWithTag("text-select-menu-list").fetchSemanticsNode().boundsInRoot
        val source =
            compose.onNodeWithTag("text-select-menu-drag-copy").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("text-select-menu-list").performTouchInput {
            down(Offset(source.center.x - list.left, source.center.y - list.top))
            moveTo(Offset(source.center.x - list.left, source.center.y - list.top + 60f))
            cancel()
        }
        compose.runOnIdle {
            assertEquals(TextSelectMenuSettingsAction.FinishDrag(false), actions.last())
        }
    }

    @Test
    fun accessibilityOffersBothReorderAndZoneTransfer() {
        show()
        val semantics =
            compose.onNodeWithTag("text-select-menu-item-dict").fetchSemanticsNode().config
        compose.runOnIdle {
            val custom = semantics[SemanticsActions.CustomActions]
            assertEquals(3, custom.size)
            custom.forEach { assertTrue(it.action()) }
            assertEquals(
                listOf(
                    TextSelectMenuSettingsAction.Step("dict", -1),
                    TextSelectMenuSettingsAction.Step("dict", 1),
                    TextSelectMenuSettingsAction.Transfer("dict"),
                ),
                actions,
            )
        }
    }
}
