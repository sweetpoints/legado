package io.legado.app.ui.book.read

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HighlightParameterScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun shadowEditsCommitOnlyOnConfirmationAndRetainColor() {
        var result: HighlightStyle.Shadow? = null
        compose.setContent { LegadoComposeTheme {
            ShadowEditRoute(HighlightStyle.Shadow(color = 123), { result = it }, {}, Modifier.heightIn(max = 500.dp))
        } }
        compose.onNodeWithTag("shadow-radius").performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        compose.onNodeWithTag("shadow-dx").performSemanticsAction(SemanticsActions.SetProgress) { it(17f) }
        compose.runOnIdle { assertNull(result) }
        compose.onNodeWithTag("highlight-parameter-confirm").performClick()
        compose.runOnIdle {
            assertEquals(4.5f, result!!.radius)
            assertEquals(-1.5f, result!!.dx)
            assertEquals(123, result!!.color)
        }
    }
    @Test fun underlineConfirmationKeepsKindAndColorAndIgnoresDuplicateClicks() {
        val results = mutableListOf<HighlightStyle.Underline>()
        compose.setContent { LegadoComposeTheme {
            UnderlineEditRoute(HighlightStyle.Underline(kind = HighlightStyle.Kind.DASHED, color = 321),
                { results += it }, {}, Modifier.heightIn(max = 500.dp))
        } }
        compose.onNodeWithTag("underline-width").performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
        compose.onNodeWithTag("underline-distance").performSemanticsAction(SemanticsActions.SetProgress) { it(31f) }
        compose.onNodeWithTag("highlight-parameter-confirm").performClick()
        compose.onNodeWithTag("highlight-parameter-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, results.size)
            assertEquals(HighlightStyle.Underline(HighlightStyle.Kind.DASHED, 321, 3.5f, 15.5f), results.single())
        }
    }
    @Test fun cancellationDiscardsShadowChanges() {
        var saves = 0
        var cancels = 0
        compose.setContent { LegadoComposeTheme {
            ShadowEditRoute(HighlightStyle.Shadow(), { saves++ }, { cancels++ }, Modifier.heightIn(max = 500.dp))
        } }
        compose.onNodeWithTag("shadow-dy-plus").performClick()
        compose.onNodeWithTag("highlight-parameter-cancel").performClick()
        compose.onNodeWithTag("highlight-parameter-confirm").performClick()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(1, cancels) }
    }
    @Test fun shadowDraftSurvivesSavedStateRestorationWithoutSaving() {
        val restoration = StateRestorationTester(compose)
        var result: HighlightStyle.Shadow? = null
        restoration.setContent { LegadoComposeTheme {
            ShadowEditRoute(HighlightStyle.Shadow(), { result = it }, {}, Modifier.heightIn(max = 500.dp))
        } }
        compose.onNodeWithTag("shadow-dy").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertNull(result) }
        compose.onNodeWithTag("highlight-parameter-confirm").performClick()
        compose.runOnIdle { assertEquals(-8.5f, result!!.dy) }
    }

}
