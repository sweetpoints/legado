package io.legado.app.ui.book.read

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadMenuBottomScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun disabledChaptersRemainVisibleAndMemoCanBeEnabled() {
        val state = mutableStateOf(ReadMenuBottomState())
        compose.setContent {
            LegadoComposeTheme { ReadMenuBottomScreen(state.value, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("reader-previous").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("reader-next").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("reader-memo").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(showMemo = true) }
        compose.onNodeWithTag("reader-memo").assertIsDisplayed()
    }

    @Test
    fun readAloudTapAndLongPressDispatchSeparateActions() {
        val actions = mutableListOf<ReadMenuAction>()
        compose.setContent {
            LegadoComposeTheme {
                ReadMenuBottomScreen(ReadMenuBottomState(), actions::add, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("reader-read-aloud").performClick()
        compose.onNodeWithTag("reader-read-aloud").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(
                listOf(ReadMenuAction.ReadAloud, ReadMenuAction.ReadAloudSettings),
                actions,
            )
        }
    }

    @Test
    fun pendingChapterRequiresAnExplicitConfirmation() {
        var confirmed = 0
        compose.setContent {
            LegadoComposeTheme {
                ReadMenuBottomScreen(
                    ReadMenuBottomState(pendingChapter = 12),
                    {},
                    {},
                    {},
                    { confirmed++ },
                    {},
                )
            }
        }
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithTag("reader-chapter-confirm").performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }
}
