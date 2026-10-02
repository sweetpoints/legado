package io.legado.app.ui.code

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.ui.about.AboutActivity
import org.junit.Rule
import org.junit.Test

class CurlAnalyzeUrlDialogComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualConverterRestoresInputOutputDirectionAndSelectionAcrossActivityRotation() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { CurlAnalyzeUrlDialog("curl https://example.com -H 'Accept: application/json'", false).show(it.supportFragmentManager, "curl-converter") }
            compose.waitUntil { compose.onAllNodesWithTag("curl-converter-input").fetchSemanticsNodes().firstOrNull()?.config
                ?.get(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text?.contains("Accept: application/json") == true }
            compose.onNodeWithTag("curl-converter-input").assertTextContains("Accept: application/json")
            compose.onNodeWithTag("curl-converter-convert").performClick()
            compose.waitUntil { compose.onNodeWithTag("curl-converter-output").fetchSemanticsNode().config
                .get(androidx.compose.ui.semantics.SemanticsProperties.EditableText).text.contains("https://example.com") }
            scenario.recreate()
            compose.waitUntil { compose.onAllNodesWithTag("curl-converter-output").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("curl-converter-output").assertTextContains("application/json")
            compose.onNodeWithTag("curl-converter-direction-CurlToAnalyze").assertIsSelected()
            compose.onNodeWithTag("curl-converter-input").assertTextContains("Accept: application/json")
            compose.onNodeWithTag("curl-converter-insert").assertDoesNotExist()
            compose.onNodeWithTag("curl-converter-close").performClick()
            compose.waitUntil { compose.onAllNodesWithTag("curl-converter-close").fetchSemanticsNodes().isEmpty() }
        }
    }
}
