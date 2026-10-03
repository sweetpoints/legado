package io.legado.app.ui.widget

import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.test.core.app.ActivityScenario
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PopupActionComposeTest {
    @get:Rule val compose = createComposeRule()
    private val colors = PopupActionColors(Color.Black, Color.Red, Color.Gray)

    @Test
    fun verticalRowsExposeCheckStateAndRejectBothKindsOfDisabledAction() {
        val actions = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                PopupActionContent(
                    PopupActionState(
                        items =
                            listOf(
                                PopupAction.PopupActionItem(
                                    "Checked",
                                    "checked",
                                    ColorDrawable(android.graphics.Color.BLUE),
                                    checkable = true,
                                    checked = true,
                                ),
                                PopupAction.PopupActionItem(
                                    "Unchecked",
                                    "unchecked",
                                    checkable = true,
                                ),
                                PopupAction.PopupActionItem("Delete", "delete"),
                                PopupAction.PopupActionItem(
                                    "Disabled flag",
                                    "flag",
                                    enabled = false,
                                ),
                                PopupAction.PopupActionItem("Disabled value", "value"),
                            ),
                        vertical = true,
                        dangerValues = setOf("delete"),
                        disabledValues = setOf("value"),
                        maxWidth = 800,
                        maxHeight = 1000,
                    ),
                    colors,
                    {},
                    actions::add,
                )
            }
        }
        compose.onNodeWithTag("popup-action-0").assertIsOn().assertHasClickAction().performClick()
        compose.onNodeWithTag("popup-action-1").assertIsOff().performClick()
        compose.onNodeWithTag("popup-action-2").performClick()
        compose.onNodeWithTag("popup-action-3").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("popup-action-4").assertIsNotEnabled().performClick()
        assertEquals(listOf("checked", "unchecked", "delete"), actions)
        assertEquals(Color.Red, textColor("Delete"))
        assertEquals(Color.Gray, textColor("Disabled flag"))
        assertEquals(Color.Gray, textColor("Disabled value"))
        val checkedText = compose.onNodeWithText("Checked", true).fetchSemanticsNode().boundsInRoot
        val uncheckedText =
            compose.onNodeWithText("Unchecked", true).fetchSemanticsNode().boundsInRoot
        assertEquals(checkedText.left, uncheckedText.left)
        val first = compose.onNodeWithTag("popup-action-0").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("popup-action-4").fetchSemanticsNode().boundsInRoot
        assertEquals(first.width, last.width)
        assertTrue(last.top > first.top)
    }

    @Test
    fun horizontalRowsWrapWithinTheRtlVisibleFrameAndKeepActionValues() {
        var action: String? = null
        compose.setContent {
            LegadoComposeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    PopupActionContent(
                        PopupActionState(
                            items =
                                List(8) { PopupAction.PopupActionItem("Action $it", "value-$it") },
                            maxWidth = 360,
                            maxHeight = 800,
                        ),
                        colors,
                        {},
                        { action = it },
                    )
                }
            }
        }
        val first = compose.onNodeWithTag("popup-action-0").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("popup-action-7").fetchSemanticsNode().boundsInRoot
        assertTrue(last.top > first.top)
        assertTrue(first.right <= 360)
        compose.onNodeWithTag("popup-action-7").performClick()
        assertEquals("value-7", action)
    }

    private fun textColor(title: String): Color {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult
        ) {
            it(results)
        }
        return results.single().layoutInput.style.color
    }

    @Test
    fun keyboardNavigationSkipsDisabledRowsAndStopsAtPopupBoundaries() {
        val actions = mutableListOf<String>()
        var dismissals = 0
        compose.setContent {
            LegadoComposeTheme {
                PopupActionContent(
                    PopupActionState(
                        items =
                            listOf(
                                PopupAction.PopupActionItem("First", "first"),
                                PopupAction.PopupActionItem(
                                    "Disabled",
                                    "disabled",
                                    enabled = false,
                                ),
                                PopupAction.PopupActionItem("Last", "last"),
                            ),
                        vertical = true,
                        maxWidth = 800,
                        maxHeight = 1000,
                    ),
                    colors,
                    { dismissals++ },
                    actions::add,
                )
            }
        }
        val first = compose.onNodeWithTag("popup-action-0")
        val last = compose.onNodeWithTag("popup-action-2")
        first.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        first.performKeyInput { pressKey(Key.DirectionUp) }
        first.assertIsFocused()
        first.performKeyInput { pressKey(Key.DirectionDown) }
        last.assertIsFocused()
        last.performKeyInput { pressKey(Key.DirectionDown) }
        last.assertIsFocused()
        last.performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("last"), actions)
        last.performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, dismissals)
    }

    @Test
    fun nativeWindowMeasuresBeforeAttachAndReusesAFreshDestroyedOwnerAfterDismiss() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val anchor = activity.findViewById<View>(android.R.id.content)
                val popup = PopupAction(activity)
                popup.setItems(listOf(SelectItem("Show", "show"), SelectItem("Refresh", "refresh")))
                popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
                val firstView = popup.contentView
                val firstOwner = requireNotNull(firstView.findViewTreeLifecycleOwner())
                assertTrue(firstView.measuredWidth > 0)
                assertEquals(Lifecycle.State.RESUMED, firstOwner.lifecycle.currentState)
                popup.dismiss()
                assertEquals(Lifecycle.State.DESTROYED, firstOwner.lifecycle.currentState)
                popup.setVertical(true)
                popup.showAsDropDown(anchor, 0, 4, Gravity.START)
                val secondView = popup.contentView
                val secondOwner = requireNotNull(secondView.findViewTreeLifecycleOwner())
                assertNotSame(firstView, secondView)
                assertNotSame(firstOwner, secondOwner)
                val frame = Rect()
                anchor.getWindowVisibleDisplayFrame(frame)
                assertTrue(secondView.measuredWidth <= frame.width())
                assertTrue(secondView.measuredHeight <= frame.height())
                popup.dismiss()
                assertEquals(Lifecycle.State.DESTROYED, secondOwner.lifecycle.currentState)
            }
        }
    }
}
