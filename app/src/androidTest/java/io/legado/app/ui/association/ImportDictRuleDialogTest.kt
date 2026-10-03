package io.legado.app.ui.association

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportDictRuleDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun realDialogRecreationPreservesRenamedCodeAndSelectionAndCancelNeverImports() {
        val suffix = UUID.randomUUID().toString()
        val first = DictRule("First-$suffix", "url", "show", false, 12)
        val second = DictRule("Second-$suffix", "url", "show", true, 15)
        val edited = second.copy(name = "Renamed-$suffix")
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity {
                ImportDictRuleDialog(GSON.toJson(listOf(first, second)), finishOnDismiss = true)
                    .show(it.supportFragmentManager, "dict-import")
            }
            compose.waitUntil {
                compose.onAllNodesWithTag("dict-import-check-0").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("dict-import-check-0").performClick().assertIsOff()
            scenario.onActivity {
                (it.supportFragmentManager.findFragmentByTag("dict-import") as ImportDictRuleDialog)
                    .onCodeSave(GSON.toJson(edited), "1")
            }
            compose.waitUntil {
                compose.onAllNodesWithText(edited.name).fetchSemanticsNodes().isNotEmpty()
            }
            scenario.recreate()
            compose.waitUntil {
                compose.onAllNodesWithText(edited.name).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("dict-import-check-0").assertIsOff()
            compose.onNodeWithTag("dict-import-check-1").assertIsOn()
            scenario.onActivity { assertFalse(it.isFinishing) }
            compose.onNodeWithTag("dict-import-cancel").performClick()
            compose.onNodeWithTag("dict-import-confirm").assertDoesNotExist()
            runBlocking(Dispatchers.IO) {
                assertNull(appDb.dictRuleDao.getByName(first.name))
                assertNull(appDb.dictRuleDao.getByName(second.name))
                assertNull(appDb.dictRuleDao.getByName(edited.name))
            }
        }
    }
}
