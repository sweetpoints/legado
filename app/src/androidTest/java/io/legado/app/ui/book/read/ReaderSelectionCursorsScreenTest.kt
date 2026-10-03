package io.legado.app.ui.book.read

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderSelectionCursorsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cancelRestoresTheNativeMenuOnceAfterDeliveringScreenCoordinates() {
        var begins = 0
        var ends = 0
        val moves = mutableListOf<Pair<SelectionHandle, Offset>>()
        compose.setContent {
            LegadoComposeTheme {
                ReaderSelectionCursorsScreen(
                    ReaderSelectionState(startX = 100f, startY = 100f, showStart = true),
                    Color.Black,
                    { begins++ },
                    { handle, point -> moves += handle to point },
                    { ends++ },
                )
            }
        }
        compose.onNodeWithTag("reader-selection-start").performTouchInput {
            down(center)
            moveBy(Offset(60f, 20f))
            cancel()
        }
        compose.runOnIdle {
            assertEquals(1, begins)
            assertEquals(1, ends)
            assertTrue(moves.isNotEmpty())
            assertEquals(SelectionHandle.Start, moves.last().first)
            assertTrue(moves.last().second.x > 100f)
            assertTrue(moves.last().second.y > 100f)
        }
    }

    @Test
    fun clearingSelectionRemovesBothComposeTouchTargets() {
        val state =
            mutableStateOf(
                ReaderSelectionState(
                    startX = 100f,
                    startY = 100f,
                    endX = 150f,
                    endY = 100f,
                    showStart = true,
                    showEnd = true,
                )
            )
        compose.setContent {
            LegadoComposeTheme {
                ReaderSelectionCursorsScreen(state.value, Color.Black, {}, { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("reader-selection-start").assertExists()
        compose.onNodeWithTag("reader-selection-end").assertExists()
        compose.runOnIdle { state.value = state.value.copy(showStart = false, showEnd = false) }
        compose.onNodeWithTag("reader-selection-start").assertDoesNotExist()
        compose.onNodeWithTag("reader-selection-end").assertDoesNotExist()
    }
}
