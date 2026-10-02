package io.legado.app.ui.association

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportHttpTtsDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realDialogRecreationPreservesCodeChangesAndSelectionAndCancelNeverInserts() {
        val first = HttpTTS(System.nanoTime(), "First", "url", lastUpdateTime = 1)
        val second = HttpTTS(first.id + 1, "Second", "url", lastUpdateTime = 1)
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { ImportHttpTtsDialog(GSON.toJson(listOf(first, second)), finishOnDismiss = true)
                .show(it.supportFragmentManager, "tts-import") }
            compose.waitUntil { compose.onAllNodesWithTag("tts-import-check-0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("tts-import-check-0").performClick().assertIsOff()
            scenario.onActivity {
                (it.supportFragmentManager.findFragmentByTag("tts-import") as ImportHttpTtsDialog)
                    .onCodeSave(GSON.toJson(second.copy(name = "Edited before recreation")), "1")
            }
            compose.waitUntil { compose.onAllNodesWithText("Edited before recreation").fetchSemanticsNodes().isNotEmpty() }
            scenario.recreate()
            compose.waitUntil { compose.onAllNodesWithText("Edited before recreation").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("tts-import-check-0").assertIsOff()
            compose.onNodeWithTag("tts-import-check-1").assertIsOn()
            scenario.onActivity { assertFalse(it.isFinishing) }
            runBlocking(Dispatchers.IO) {
                assertNull(appDb.httpTTSDao.get(first.id)); assertNull(appDb.httpTTSDao.get(second.id))
            }
            compose.onNodeWithTag("tts-import-cancel").performClick()
            compose.onNodeWithTag("tts-import-confirm").assertDoesNotExist()
            runBlocking(Dispatchers.IO) {
                assertNull(appDb.httpTTSDao.get(first.id)); assertNull(appDb.httpTTSDao.get(second.id))
            }
        }
    }
}
