package io.legado.app.ui.widget.number

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NumberPickerScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun confirmCommitsTypedInputAndDismissesOnce() {
        val values = mutableListOf<Int>()
        var closes = 0
        compose.setContent { LegadoComposeTheme { NumberPickerRoute(NumberPickerConfig(minimum = 1, maximum = 99, initial = 10), null, { values += it }, {}, { closes++ }) } }
        compose.onNodeWithTag("number-input").performScrollTo().performTextReplacement("42")
        compose.onNodeWithTag("number-confirm").performScrollTo().performClick()
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { assertEquals(listOf(42), values) }
    }

    @Test fun decimalEditorReturnsRawIntegerOnImeDone() {
        val values = mutableListOf<Int>()
        compose.setContent { LegadoComposeTheme { NumberPickerRoute(NumberPickerConfig(minimum = 5, maximum = 99, initial = 10, decimal = true), null, { values += it }, {}, {}) } }
        compose.onNodeWithTag("number-input").performScrollTo().performTextReplacement("4.2")
        compose.onNodeWithTag("number-input").performImeAction()
        compose.runOnIdle { assertEquals(listOf(42), values) }
    }

    @Test fun customLabelEditorReturnsItsPosition() {
        val values = mutableListOf<Int>()
        val config = NumberPickerConfig(minimum = 1, maximum = 3, labels = listOf("First", "Second", "Third"))
        compose.setContent { LegadoComposeTheme { NumberPickerRoute(config, null, { values += it }, {}, {}) } }
        compose.onNodeWithTag("number-input").performScrollTo().performTextReplacement("sec")
        compose.onNodeWithTag("number-confirm").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(2), values) }
    }

    @Test fun neutralButtonInvokesOnlyItsCallbackAndCloses() {
        val values = mutableListOf<Int>()
        var custom = 0
        var closes = 0
        compose.setContent { LegadoComposeTheme { NumberPickerRoute(NumberPickerConfig(maximum = 99), "Default", { values += it }, { custom++ }, { closes++ }) } }
        compose.onNodeWithTag("number-input").performScrollTo().performTextReplacement("42")
        compose.onNodeWithTag("number-custom").performScrollTo().performClick()
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { assertEquals(1, custom); assertTrue(values.isEmpty()) }
    }

    @Test fun cancelDoesNotInvokeValueOrNeutralCallback() {
        var values = 0
        var custom = 0
        var closes = 0
        compose.setContent { LegadoComposeTheme { NumberPickerRoute(NumberPickerConfig(maximum = 99), "Default", { values++ }, { custom++ }, { closes++ }) } }
        compose.onNodeWithTag("number-cancel").performScrollTo().performClick()
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { assertEquals(0, values); assertEquals(0, custom) }
    }

    @Test fun savedEditorDraftSurvivesRestorationWithoutWheelResettingIt() {
        val restoration = StateRestorationTester(compose)
        val values = mutableListOf<Int>()
        restoration.setContent { LegadoComposeTheme { NumberPickerRoute(NumberPickerConfig(maximum = 99, initial = 10), null, { values += it }, {}, {}) } }
        compose.onNodeWithTag("number-input").performScrollTo().performTextReplacement("42")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("number-confirm").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(42), values) }
    }

    @Test fun wheelSemanticSelectionAndStepButtonsKeepRawValues() {
        val values = mutableListOf<Int>()
        compose.setContent { LegadoComposeTheme { NumberPickerRoute(NumberPickerConfig(minimum = 1, maximum = 99, initial = 10), null, { values += it }, {}, {}) } }
        compose.onNodeWithTag("number-wheel").performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
        compose.onNodeWithTag("number-plus").performClick()
        compose.onNodeWithTag("number-minus").performClick()
        compose.onNodeWithTag("number-confirm").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(40), values) }
    }

    @Test fun actualWheelDragChangesSelectionWithinBounds() {
        val values = mutableListOf<Int>()
        compose.setContent { LegadoComposeTheme { NumberPickerRoute(NumberPickerConfig(maximum = 9999, initial = 10), null, { values += it }, {}, {}) } }
        compose.onNodeWithTag("number-wheel").performTouchInput { swipe(center, center - Offset(0f, 80f), 300) }
        compose.waitForIdle()
        compose.onNodeWithTag("number-confirm").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, values.size); assertTrue(values.single() in 0..9999); assertNotEquals(10, values.single()) }
    }
}
