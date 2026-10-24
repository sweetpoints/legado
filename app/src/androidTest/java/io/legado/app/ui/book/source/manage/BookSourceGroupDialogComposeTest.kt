package io.legado.app.ui.book.source.manage

import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.group.NamedGroupsFlutterRobot
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class BookSourceGroupDialogComposeTest {

    @Test
    fun realDialogRestoresRenameDraftAndPreservesUnrelatedMemberships() {
        val group = "Group-${UUID.randomUUID()}"
        val original =
            BookSource(
                bookSourceUrl = "https://$group.invalid",
                bookSourceName = "Managed",
                bookSourceGroup = "$group,Other",
                header = "header",
                enabled = false,
            )
        val similar =
            original.copy(
                bookSourceUrl = "https://similar-$group.invalid",
                bookSourceGroup = "${group}More",
            )
        runBlocking(Dispatchers.IO) { appDb.bookSourceDao.insert(original, similar) }
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
                            appDb.bookSourceDao
                                .getBookSource(original.bookSourceUrl)
                                ?.bookSourceGroup == "Other,Renamed"
                        }
                    }
                }
                runBlocking(Dispatchers.IO) {
                    assertEquals(
                        GSON.toJson(original.copy(bookSourceGroup = "Other,Renamed")),
                        GSON.toJson(appDb.bookSourceDao.getBookSource(original.bookSourceUrl)),
                    )
                    assertEquals(
                        GSON.toJson(similar),
                        GSON.toJson(appDb.bookSourceDao.getBookSource(similar.bookSourceUrl)),
                    )
                }
            }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.bookSourceDao.delete(original, similar) }
        }
    }
}
