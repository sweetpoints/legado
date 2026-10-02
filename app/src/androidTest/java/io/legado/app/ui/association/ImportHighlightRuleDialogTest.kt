package io.legado.app.ui.association

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ImportHighlightRuleDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualSelectionSurvivesRecreationAndFinishOnDismissDoesNotFinishRotationOrWriteOnCancel() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val rule = HighlightRule(name = "Unconfirmed ${UUID.randomUUID()}", pattern = "unconfirmed", style = "{}")
        val file = File(context.cacheDir, "highlight-dialog-${rule.uuid}.json")
        runBlocking(Dispatchers.IO) { file.writeText(GSON.toJson(listOf(rule))) }
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity { it.showImportHighlightRuleDialog(Uri.fromFile(file).toString(), true) }
                compose.waitUntil { compose.onAllNodesWithTag("highlight-import-check-${rule.uuid}").fetchSemanticsNodes().isNotEmpty() }
                scenario.onActivity { activity ->
                    activity.showImportHighlightRuleDialog(Uri.fromFile(file).toString(), true)
                    activity.supportFragmentManager.executePendingTransactions()
                    assertEquals(1, activity.supportFragmentManager.fragments.filterIsInstance<ImportHighlightRuleDialog>().size)
                }
                compose.onNodeWithTag("highlight-import-check-${rule.uuid}").performClick().assertIsOff()
                compose.onNodeWithTag("highlight-import-confirm").assertIsNotEnabled()
                scenario.recreate()
                compose.waitUntil { compose.onAllNodesWithTag("highlight-import-check-${rule.uuid}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("highlight-import-check-${rule.uuid}").assertIsOff()
                scenario.onActivity { assertFalse(it.isFinishing) }
                compose.onNodeWithTag("highlight-import-cancel").performClick()
                compose.onNodeWithTag("highlight-import-confirm").assertDoesNotExist()
                assertFalse(runBlocking(Dispatchers.IO) { appDb.highlightRuleDao.all.any { it.uuid == rule.uuid } })
            }
        } finally { runBlocking(Dispatchers.IO) { file.delete() } }
    }
}
