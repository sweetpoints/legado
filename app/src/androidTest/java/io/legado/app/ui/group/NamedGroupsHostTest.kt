package io.legado.app.ui.group

import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class NamedGroupsHostTest {
    @Test fun privateNameAndRawSelectionRestoreWithoutLargeSavedBundleOrRoomWrite() {
        val before = runBlocking(Dispatchers.IO) { GSON.toJson(appDb.replaceRuleDao.all) }
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { io.legado.app.ui.replace.GroupManageDialog().show(it.supportFragmentManager, "groups") }
            NamedGroupsFlutterRobot(scenario).use { robot ->
                robot.ready(); robot.click("named-group-add")
                robot.driver.awaitIdentifier("named-group-name")
                val large = "private".repeat(30000)
                // Private checkpoint input deliberately bypasses Accessibility Binder payload limits.
                scenario.onActivity {
                    val host = it.supportFragmentManager.fragments.filterIsInstance<NamedGroupDialog>().single()
                    host.model.input(large, 2, 5)
                    val bundle = android.os.Bundle()
                    android.app.Activity::class.java.getDeclaredMethod("onSaveInstanceState", android.os.Bundle::class.java)
                        .apply { isAccessible = true }.invoke(it, bundle)
                    val parcel = android.os.Parcel.obtain()
                    try { parcel.writeBundle(bundle); assertTrue(parcel.dataSize() < 128000) }
                    finally { parcel.recycle() }
                }
                scenario.recreate()
                robot.assertName(large); robot.assertSelection(2, 5)
                robot.click("named-group-cancel")
                robot.driver.await("Private draft cancel completes") { !robot.host().model.state.value.editing }
                assertEquals(before, runBlocking(Dispatchers.IO) { GSON.toJson(appDb.replaceRuleDao.all) })
            }
        }
    }

    @Test fun bookSourceCancelLeavesMetadataUntouchedAndDeleteRemovesOnlyExactMembership() {
        val group = "Group-${UUID.randomUUID()}"
        val original = BookSource(bookSourceUrl = "https://$group.invalid", bookSourceName = "Managed",
            bookSourceGroup = "$group,Other", header = "header", enabled = false)
        val similar = original.copy(bookSourceUrl = "https://similar-$group.invalid", bookSourceGroup = "${group}More")
        runBlocking(Dispatchers.IO) { appDb.bookSourceDao.insert(original, similar) }
        try {
            checkDialog(io.legado.app.ui.book.source.manage.GroupManageDialog(), group, false,
                { GSON.toJson(appDb.bookSourceDao.getBookSource(original.bookSourceUrl)) },
                GSON.toJson(original), GSON.toJson(original.copy(bookSourceGroup = "Other")))
            assertEquals(GSON.toJson(similar), GSON.toJson(appDb.bookSourceDao.getBookSource(similar.bookSourceUrl)))
        } finally { runBlocking(Dispatchers.IO) { appDb.bookSourceDao.delete(original, similar) } }
    }
    @Test fun rssSourceCancelAndDoneKeepParentAliveWhileDeletePreservesSimilarNames() {
        val group = "Group-${UUID.randomUUID()}"
        val original = RssSource(sourceUrl = "https://$group.invalid", sourceName = "Managed",
            sourceGroup = "$group,Other", header = "header", enabled = false)
        val similar = original.copy(sourceUrl = "https://similar-$group.invalid", sourceGroup = "${group}More")
        runBlocking(Dispatchers.IO) { appDb.rssSourceDao.insert(original, similar) }
        try {
            checkDialog(io.legado.app.ui.rss.source.manage.GroupManageDialog(), group, true,
                { GSON.toJson(appDb.rssSourceDao.getByKey(original.sourceUrl)) },
                GSON.toJson(original), GSON.toJson(original.copy(sourceGroup = "Other")))
            assertEquals(GSON.toJson(similar), GSON.toJson(appDb.rssSourceDao.getByKey(similar.sourceUrl)))
        } finally { runBlocking(Dispatchers.IO) { appDb.rssSourceDao.delete(original, similar) } }
    }
    @Test fun replacementCancelAndDeleteKeepMetadataAndUnrelatedMemberships() {
        val group = "Group-${UUID.randomUUID()}"
        val original = ReplaceRule(name = "Managed", group = "$group,Other", pattern = "pattern",
            replacement = "replacement", isEnabled = false)
        val similar = original.copy(id = original.id + 1, group = "${group}More")
        runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.insert(original, similar) }
        try {
            checkDialog(io.legado.app.ui.replace.GroupManageDialog(), group, false,
                { GSON.toJson(appDb.replaceRuleDao.findById(original.id)) },
                GSON.toJson(original), GSON.toJson(original.copy(group = "Other")))
            assertEquals(GSON.toJson(similar), GSON.toJson(appDb.replaceRuleDao.findById(similar.id)))
        } finally { runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.delete(original, similar) } }
    }
    private fun checkDialog(dialog: NamedGroupDialog, group: String, showDone: Boolean,
        read: () -> String, before: String, after: String) {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { dialog.show(it.supportFragmentManager, "groups") }
            NamedGroupsFlutterRobot(scenario).use { robot ->
                robot.ready(); robot.edit(group); robot.input("uncommitted name")
                robot.click("named-group-cancel")
                robot.driver.await("Cancel acknowledges editor closure without Room write") {
                    !robot.host().model.state.value.editing && runBlocking(Dispatchers.IO) { read() == before }
                }
                val id = "named-group-delete-" + namedGroupRowId(group)
                robot.click(id)
                robot.driver.await("Delete preserves all other metadata and memberships") {
                    runBlocking(Dispatchers.IO) { read() == after }
                }
                if (showDone) robot.click("named-group-done") else robot.click("named-group-close")
                robot.driver.await("Only owned named group dialog closes") {
                    var closed = false
                    scenario.onActivity { closed = it.supportFragmentManager.fragments.none { fragment -> fragment is NamedGroupDialog } && !it.isFinishing }
                    closed
                }
            }
        }
    }
}
