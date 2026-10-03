package io.legado.app.ui.association

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookImportDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun actualCodeSaveAndRecreationKeepStableDraftAndSelectionUntilCancel() {
        val url = "https://${UUID.randomUUID()}.invalid/rss"
        val source =
            BookSource(
                bookSourceUrl = url,
                bookSourceName = "Original BookSource",
                jsLib = "metadata-library",
                searchUrl = "search",
            )
        val edited = source.copy(bookSourceName = "Edited BookSource", jsLib = "edited-library")
        val previous = AppConfig.importReplaceSource
        AppConfig.importReplaceSource = false
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity {
                    ImportBookSourceDialog(GSON.toJson(source), finishOnDismiss = true)
                        .show(it.supportFragmentManager, "book-import")
                }
                compose.waitUntil {
                    compose
                        .onAllNodesWithTag("book-import-check-0")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithTag("book-import-check-0").performClick().assertIsOff()
                compose.onNodeWithTag("book-import-code-0").performClick()
                compose.waitUntil {
                    compose.onAllNodesWithTag("code-body").fetchSemanticsNodes().any {
                        !it.config.contains(
                            androidx.compose.ui.semantics.SemanticsProperties.Disabled
                        )
                    }
                }
                compose.onNodeWithTag("code-body").performTextReplacement(GSON.toJson(edited))
                compose.onNodeWithTag("code-save").performClick()
                compose.waitUntil {
                    compose
                        .onAllNodesWithText("Edited BookSource")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithTag("book-import-check-0").assertIsOff()
                scenario.recreate()
                compose.waitUntil {
                    compose
                        .onAllNodesWithText("Edited BookSource")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithTag("book-import-check-0").assertIsOff()
                scenario.onActivity { assertFalse(it.isFinishing) }
                assertNull(runBlocking(Dispatchers.IO) { appDb.bookSourceDao.getBookSource(url) })
                compose.onNodeWithTag("book-import-cancel").performClick()
                compose.onNodeWithTag("book-import-confirm").assertDoesNotExist()
                assertNull(runBlocking(Dispatchers.IO) { appDb.bookSourceDao.getBookSource(url) })
            }
        } finally {
            AppConfig.importReplaceSource = previous
        }
    }
}
