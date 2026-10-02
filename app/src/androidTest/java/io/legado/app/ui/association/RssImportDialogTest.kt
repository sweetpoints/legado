package io.legado.app.ui.association

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class RssImportDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualCodeSaveAndRecreationKeepStableDraftAndSelectionUntilCancel() {
        val url = "https://${UUID.randomUUID()}.invalid/rss"
        val source = RssSource(sourceUrl = url, sourceName = "Original RSS", jsLib = "metadata-library", ruleArticles = "article")
        val edited = source.copy(sourceName = "Edited RSS", jsLib = "edited-library")
        val previous = AppConfig.importReplaceSource
        AppConfig.importReplaceSource = false
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity { ImportRssSourceDialog(GSON.toJson(source), finishOnDismiss = true).show(it.supportFragmentManager, "rss-import") }
                compose.waitUntil { compose.onAllNodesWithTag("rss-import-check-0").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("rss-import-check-0").performClick().assertIsOff()
                compose.onNodeWithTag("rss-import-code-0").performClick()
                scenario.onActivity { activity ->
                    val parent = activity.supportFragmentManager.findFragmentByTag("rss-import") as ImportRssSourceDialog
                    parent.childFragmentManager.executePendingTransactions()
                    val code = parent.childFragmentManager.fragments.filterIsInstance<CodeDialog>().single()
                    code.binding.codeView.setText(GSON.toJson(edited))
                    assertTrue(code.binding.toolBar.menu.performIdentifierAction(R.id.menu_save, 0))
                }
                compose.waitUntil { compose.onAllNodesWithText("Edited RSS").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("rss-import-check-0").assertIsOff()
                scenario.recreate()
                compose.waitUntil { compose.onAllNodesWithText("Edited RSS").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("rss-import-check-0").assertIsOff()
                scenario.onActivity { assertFalse(it.isFinishing) }
                assertNull(runBlocking(Dispatchers.IO) { appDb.rssSourceDao.getByKey(url) })
                compose.onNodeWithTag("rss-import-cancel").performClick()
                compose.onNodeWithTag("rss-import-confirm").assertDoesNotExist()
                assertNull(runBlocking(Dispatchers.IO) { appDb.rssSourceDao.getByKey(url) })
            }
        } finally { AppConfig.importReplaceSource = previous }
    }
}
