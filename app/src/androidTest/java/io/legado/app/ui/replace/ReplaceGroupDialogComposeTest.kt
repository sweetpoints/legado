package io.legado.app.ui.replace

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class ReplaceGroupDialogComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realDialogRestoresRenameDraftAndPreservesUnrelatedMemberships() {
        val group = "Group-${UUID.randomUUID()}"
        val original = ReplaceRule(name = "Managed", group = "$group,Other", pattern = "pattern", replacement = "replacement", isEnabled = false)
        val similar = original.copy(id = original.id + 1, group = "${group}More")
        runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.insert(original, similar) }
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity { GroupManageDialog().show(it.supportFragmentManager, "groups") }
                compose.waitUntil { compose.onAllNodesWithTag("named-group-edit-$group").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("named-group-edit-$group").performScrollTo().performClick()
                compose.onNodeWithTag("named-group-name").performTextReplacement("Renamed")
                scenario.recreate()
                compose.waitUntil { compose.onAllNodesWithTag("named-group-name").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("named-group-name").assertTextEquals("Renamed")
                compose.onNodeWithTag("named-group-confirm").performClick()
                compose.waitUntil { runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(original.id)?.group == "Other,Renamed" } }
                runBlocking(Dispatchers.IO) {
                    assertEquals(GSON.toJson(original.copy(group = "Other,Renamed")), GSON.toJson(appDb.replaceRuleDao.findById(original.id)))
                    assertEquals(GSON.toJson(similar), GSON.toJson(appDb.replaceRuleDao.findById(similar.id)))
                }
            }
        } finally { runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.delete(original, similar) } }
    }
}
