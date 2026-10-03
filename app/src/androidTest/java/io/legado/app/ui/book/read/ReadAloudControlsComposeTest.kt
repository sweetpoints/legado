package io.legado.app.ui.book.read

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReadAloudControlsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pauseClickAndStopLongPressUseDistinctActions() {
        var pauses = 0
        var stops = 0
        compose.setContent {
            LegadoComposeTheme {
                ReadAloudControlsScreen(
                    state = ReadAloudControlPresentation(),
                    pause = { pauses++ },
                    stop = { stops++ },
                    back = {},
                    readHere = {},
                    dragStart = {},
                    drag = { _, _ -> },
                    dragEnd = {},
                    dragCancel = {},
                )
            }
        }
        compose.onNodeWithTag("reader-aloud-back").assertDoesNotExist()
        compose.onNodeWithTag("reader-aloud-pause").performClick()
        compose.onNodeWithTag("reader-aloud-pause").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, pauses)
            assertEquals(1, stops)
        }
    }

    @Test
    fun detachedControlsKeepBothActionsAtMinimumWidth() {
        var backs = 0
        var restarts = 0
        compose.setContent {
            LegadoComposeTheme {
                ReadAloudControlsScreen(
                    state = ReadAloudControlPresentation(showPause = false, width = 85f),
                    pause = {},
                    stop = {},
                    back = { backs++ },
                    readHere = { restarts++ },
                    dragStart = {},
                    drag = { _, _ -> },
                    dragEnd = {},
                    dragCancel = {},
                )
            }
        }
        compose.onNodeWithTag("reader-aloud-pause").assertDoesNotExist()
        compose.onNodeWithTag("reader-aloud-back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reader-aloud-here").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals(1, restarts)
        }
    }

    @Test
    fun draggingCancelsClickAndLongPressBeforeCompletingOnce() {
        var clicks = 0
        var stops = 0
        var starts = 0
        var ends = 0
        var distance = 0f
        compose.setContent {
            LegadoComposeTheme {
                ReadAloudControlsScreen(
                    state = ReadAloudControlPresentation(drag = true),
                    pause = { clicks++ },
                    stop = { stops++ },
                    back = {},
                    readHere = {},
                    dragStart = { starts++ },
                    drag = { x, _ -> distance += x },
                    dragEnd = { ends++ },
                    dragCancel = {},
                )
            }
        }
        compose.onNodeWithTag("reader-aloud-pause").performTouchInput {
            swipe(center, center + Offset(100f, 0f), durationMillis = 1000)
        }
        compose.runOnIdle {
            assertEquals(0, clicks)
            assertEquals(0, stops)
            assertEquals(1, starts)
            assertEquals(1, ends)
            assertTrue(distance > 0f)
        }
    }
}
