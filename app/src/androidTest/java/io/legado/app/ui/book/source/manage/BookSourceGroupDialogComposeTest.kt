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
        // App initialization may still normalize duplicate/out-of-range source orders.
        // Seed two distinct legal orders rather than introducing duplicate default zeroes.
        val seedOrders = runBlocking(Dispatchers.IO) {
            val used = appDb.bookSourceDao.allPart.map { it.customOrder }.toSet()
            (0..99999).asSequence().filterNot(used::contains).take(2).toList()
        }
        assertEquals("Two available legal source orders", 2, seedOrders.size)
        val original =
            BookSource(
                bookSourceUrl = "https://$group.invalid",
                bookSourceName = "Managed",
                bookSourceGroup = "$group,Other",
                header = "header",
                enabled = false,
                customOrder = seedOrders[0],
            )
        val similar =
            original.copy(
                bookSourceUrl = "https://similar-$group.invalid",
                bookSourceGroup = "${group}More",
                customOrder = seedOrders[1],
            )
        runBlocking(Dispatchers.IO) { appDb.bookSourceDao.insert(original, similar) }
        try {
            runBlocking(Dispatchers.IO) {
                assertEquals(GSON.toJson(original), GSON.toJson(appDb.bookSourceDao.getBookSource(original.bookSourceUrl)))
                assertEquals(GSON.toJson(similar), GSON.toJson(appDb.bookSourceDao.getBookSource(similar.bookSourceUrl)))
            }
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
