package io.legado.app.ui.association

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportReplaceRuleDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualDialogRestoresCodeSelectionAndOpenGroupDraftWithoutImportingOnRecreationOrCancel() {
        val rule = ReplaceRule(id = System.nanoTime(), name = "Unconfirmed replacement", pattern = "target", replacement = "new", isRegex = false)
        val edited = rule.copy(name = "Edited replacement")
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { ImportReplaceRuleDialog(GSON.toJson(rule), finishOnDismiss = true)
                .show(it.supportFragmentManager, "replace-import") }
            compose.waitUntil { compose.onAllNodesWithTag("replace-import-check-0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("replace-import-check-0").performClick().assertIsOff()
            scenario.onActivity {
                (it.supportFragmentManager.findFragmentByTag("replace-import") as ImportReplaceRuleDialog)
                    .onCodeSave(GSON.toJson(edited), "0")
            }
            compose.waitUntil { compose.onAllNodesWithText(edited.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("replace-import-group").performClick()
            compose.onNodeWithTag("replace-import-group-name").performTextInput("Restore group")
            compose.onNodeWithTag("replace-import-add-group").performClick().assertIsOn()
            scenario.recreate()
            compose.onNodeWithTag("replace-import-group-name").assertTextContains("Restore group")
            compose.onNodeWithTag("replace-import-add-group").assertIsOn()
            scenario.onActivity { assertFalse(it.isFinishing) }
            compose.onNodeWithTag("replace-import-group-ok").performClick()
            compose.onNodeWithTag("replace-import-group").assertTextContains("+【Restore group】")
            compose.onNodeWithTag("replace-import-check-0").assertIsOff()
            compose.onNodeWithText(edited.name).assertExists()
            runBlocking(Dispatchers.IO) { assertNull(appDb.replaceRuleDao.findById(rule.id)) }
            compose.onNodeWithTag("replace-import-cancel").performClick()
            compose.onNodeWithTag("replace-import-confirm").assertDoesNotExist()
            runBlocking(Dispatchers.IO) { assertNull(appDb.replaceRuleDao.findById(rule.id)) }
        }
    }
}
