package io.legado.app.ui.rss.source.manage

import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.group.NamedGroupsFlutterRobot
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RssSourceGroupDialogComposeTest {

    @Test
    fun realDialogRestoresRenameDraftAndPreservesUnrelatedMemberships() {
        val group = "Group-${UUID.randomUUID()}"
        val original =
            RssSource(
                sourceUrl = "https://$group.invalid",
                sourceName = "Managed",
                sourceGroup = "$group,Other",
                header = "header",
                enabled = false,
            )
        val similar =
            original.copy(
                sourceUrl = "https://similar-$group.invalid",
                sourceGroup = "${group}More",
            )
        runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(original, similar) }
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
                            appDb.rssSourceDao.getByKey(original.sourceUrl)?.sourceGroup ==
                                "Other,Renamed"
                        }
                    }
                }
                runBlocking(Dispatchers.IO) {
                    assertEquals(
                        GSON.toJson(original.copy(sourceGroup = "Other,Renamed")),
                        GSON.toJson(appDb.rssSourceDao.getByKey(original.sourceUrl)),
                    )
                    assertEquals(
                        GSON.toJson(similar),
                        GSON.toJson(appDb.rssSourceDao.getByKey(similar.sourceUrl)),
                    )
                }
            }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(original, similar) }
        }
    }
}
