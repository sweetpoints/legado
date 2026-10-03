package io.legado.app.ui.book.read

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReadMenuScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun toolbarTapAndLongPressKeepIndependentActions() {
        val actions = mutableListOf<Pair<Int, Boolean>>()
        compose.setContent {
            Screen(
                top =
                    ReadMenuTopState(
                        showBrightness = false,
                        actions =
                            listOf(
                                ReaderToolbarAction(
                                    R.id.menu_refresh,
                                    R.drawable.ic_refresh_black_24dp,
                                    "Refresh",
                                )
                            ),
                    ),
                toolbar = { id, long -> actions += id to long },
            )
        }
        compose.onNodeWithTag("reader-toolbar-${R.id.menu_refresh}").performClick()
        compose.onNodeWithTag("reader-toolbar-${R.id.menu_refresh}").performTouchInput {
            longClick()
        }
        compose.runOnIdle {
            assertEquals(listOf(R.id.menu_refresh to false, R.id.menu_refresh to true), actions)
        }
    }

    @Test
    fun actualDropdownExposesCheckedStateAndDisabledAction() {
        compose.setContent {
            Screen(
                top =
                    ReadMenuTopState(
                        showBrightness = false,
                        popup = ReaderPopup.Overflow,
                        popupEntries =
                            listOf(
                                ReaderPopupEntry(
                                    "Reverse",
                                    "reverseContent",
                                    checkable = true,
                                    checked = true,
                                ),
                                ReaderPopupEntry("No title", "sameTitleRemoved", checkable = true),
                                ReaderPopupEntry("Disabled", "disabled", enabled = false),
                            ),
                    )
            )
        }
        compose.onNodeWithTag("reader-menu-item-reverseContent").assertIsOn()
        compose.onNodeWithTag("reader-menu-item-sameTitleRemoved").assertIsOff()
        compose.onNodeWithTag("reader-menu-item-disabled").assertIsNotEnabled()
    }

    @Test
    fun automaticBrightnessDisablesManualSlider() {
        compose.setContent {
            Screen(top = ReadMenuTopState(brightnessAutomatic = true, brightness = 100))
        }
        compose.onNodeWithTag("reader-brightness").assertIsNotEnabled()
    }

    @Composable
    private fun Screen(top: ReadMenuTopState, toolbar: (Int, Boolean) -> Unit = { _, _ -> }) {
        LegadoComposeTheme {
            ReadMenuScreen(
                visible = true,
                animate = false,
                dragging = false,
                top = top,
                bottom = ReadMenuBottomState(),
                dismiss = {},
                back = {},
                bookInfo = {},
                chapterClick = {},
                chapterLongClick = {},
                customClick = {},
                toolbarAction = toolbar,
                openPopup = {},
                dismissPopup = {},
                popupAction = {},
                toggleBrightness = {},
                brightnessChange = {},
                brightnessCommit = {},
                swapBrightness = {},
                bottomAction = {},
                progressDragging = {},
                progressCommit = {},
                chapterConfirm = {},
                chapterCancel = {},
            )
        }
    }
}
