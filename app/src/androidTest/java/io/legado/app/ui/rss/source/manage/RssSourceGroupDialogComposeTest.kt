package io.legado.app.ui.rss.source.manage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RssSourceGroupDialogComposeTest {
    @get:Rule val compose = createEmptyComposeRule()

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
                compose.waitUntil {
                    compose
                        .onAllNodesWithTag("named-group-edit-$group")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithTag("named-group-edit-$group").performScrollTo().performClick()
                compose.onNodeWithTag("named-group-name").performTextReplacement("Renamed")
                scenario.recreate()
                compose.waitUntil {
                    compose.onAllNodesWithTag("named-group-name").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("named-group-name").assertTextEquals("Renamed")
                compose.onNodeWithTag("named-group-confirm").performClick()
                compose.waitUntil {
                    runBlocking(Dispatchers.IO) {
                        appDb.rssSourceDao.getByKey(original.sourceUrl)?.sourceGroup ==
                            "Other,Renamed"
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
