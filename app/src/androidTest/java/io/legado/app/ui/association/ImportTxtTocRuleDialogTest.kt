package io.legado.app.ui.association

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportTxtTocRuleDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualCodeDialogSaveUpdatesStableRowAndRecreationRestoresSelectionAndExpandedExampleWithoutImporting() {
        val example = (1..45).joinToString("\n") { "Line $it" }
        val rule = TxtTocRule(System.nanoTime(), "Unconfirmed TOC", "pattern", "replacement", example, 12, false)
        val edited = rule.copy(name = "Edited by code save")
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { ImportTxtTocRuleDialog(GSON.toJson(rule), finishOnDismiss = true)
                .show(it.supportFragmentManager, "toc-import") }
            compose.waitUntil { compose.onAllNodesWithTag("toc-import-check-0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("toc-import-check-0").performClick().assertIsOff()
            compose.onNodeWithTag("toc-import-code-0").performClick()
            compose.waitUntil { compose.onAllNodesWithTag("code-body").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
            compose.onNodeWithTag("code-body").performTextReplacement(GSON.toJson(edited))
            compose.onNodeWithTag("code-save").performClick()
            compose.waitUntil { compose.onAllNodesWithText(edited.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("toc-import-check-0").assertIsOff()
            compose.onNodeWithTag("toc-import-example-0").performClick()
            assertEquals(39, lineCount())
            compose.onNodeWithTag("toc-import-check-0").assertIsOff()
            scenario.recreate()
            compose.waitUntil { compose.onAllNodesWithText(edited.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("toc-import-check-0").assertIsOff()
            assertEquals(39, lineCount())
            scenario.onActivity { assertFalse(it.isFinishing) }
            runBlocking(Dispatchers.IO) { assertNull(appDb.txtTocRuleDao.get(rule.id)) }
            compose.onNodeWithTag("toc-import-cancel").performClick()
            compose.onNodeWithTag("toc-import-confirm").assertDoesNotExist()
            runBlocking(Dispatchers.IO) { assertNull(appDb.txtTocRuleDao.get(rule.id)) }
        }
    }
    private fun lineCount(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("toc-import-example-0").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().lineCount
    }
}
