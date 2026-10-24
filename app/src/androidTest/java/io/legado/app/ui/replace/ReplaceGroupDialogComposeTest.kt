package io.legado.app.ui.replace

import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.group.NamedGroupsFlutterRobot
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class ReplaceGroupDialogComposeTest {

    @Test
    fun realDialogRestoresRenameDraftAndPreservesUnrelatedMemberships() {
        val group = "Group-${UUID.randomUUID()}"
        val original =
            ReplaceRule(
                name = "Managed",
                group = "$group,Other",
                pattern = "pattern",
                replacement = "replacement",
                isEnabled = false,
            )
        val similar = original.copy(id = original.id + 1, group = "${group}More")
        runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.insert(original, similar) }
        try {
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                scenario.onActivity {
                    GroupManageDialog().show(it.supportFragmentManager, "groups")
                }
                NamedGroupsFlutterRobot(scenario).use { robot ->
                    robot.ready()
                    robot.edit(group)
                    robot.input("Renamed")
                    robot.select(2, 5)
                    scenario.recreate()
                    robot.assertName("Renamed")
                    robot.assertSelection(2, 5)
                    robot.click("named-group-confirm")
                    robot.awaitOriginalRoomReceipt {
                        runBlocking(Dispatchers.IO) {
                            appDb.replaceRuleDao.findById(original.id)?.group == "Other,Renamed"
                        }
                    }
                }
                runBlocking(Dispatchers.IO) {
                    assertEquals(
                        GSON.toJson(original.copy(group = "Other,Renamed")),
                        GSON.toJson(appDb.replaceRuleDao.findById(original.id)),
                    )
                    assertEquals(
                        GSON.toJson(similar),
                        GSON.toJson(appDb.replaceRuleDao.findById(similar.id)),
                    )
                }
            }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.delete(original, similar) }
        }
    }
}
