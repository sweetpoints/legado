package io.legado.app.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.repository.ReplaceRuleGroupRepository
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class NamedGroupsHostTest {
    @Test fun privateNativeNameCheckpointRestoresFromDiskWithoutLargeSavedBundleOrRoomWrite() {
        val before = runBlocking(Dispatchers.IO) { GSON.toJson(appDb.replaceRuleDao.all) }
        val large = "private".repeat(30000)
        assertEquals(210000, large.length)
        val wait = FlutterUiTestDriver(InstrumentationRegistry.getInstrumentation())
        var model: NamedGroupViewModel? = null
        var fresh: NamedGroupViewModel? = null
        lateinit var handle: SavedStateHandle
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    model = checkpointModel(activity) { handle = it }
                }
                wait.await("Owned native checkpoint repository loaded") { model?.state?.value?.loading == false }
                scenario.onActivity { activity ->
                    // This owned model participates in the real Activity SavedStateRegistry but
                    // is deliberately not bound to Flutter or Accessibility. It tests the private
                    // checkpoint boundary, not giant Accessibility Binder text transport.
                    model!!.add(); model!!.input(large, 2, 5)
                    assertEquals(large, model!!.state.value.name)
                    assertEquals(setOf("namedGroup.session"), handle.keys())
                    val bundle = android.os.Bundle()
                    android.app.Activity::class.java.getDeclaredMethod("onSaveInstanceState", android.os.Bundle::class.java)
                        .apply { isAccessible = true }.invoke(activity, bundle)
                    val parcel = android.os.Parcel.obtain()
                    try { parcel.writeBundle(bundle); assertTrue(parcel.dataSize() < 128000) }
                    finally { parcel.recycle() }
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val retained = checkpointModel(activity) { handle = it }
                    assertEquals(large, retained.state.value.name)
                    assertEquals(2, retained.state.value.selectionStart)
                    assertEquals(5, retained.state.value.selectionEnd)
                    assertTrue(retained.state.value.editing)
                    assertFalse(retained.state.value.recoveryFailed)
                    // A fresh ViewModel with only the primitive token must read the actual
                    // private file; the retained Activity ViewModel cannot supply this draft.
                    val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
                    assertEquals(setOf("namedGroup.session"), restoredHandle.keys())
                    fresh = NamedGroupViewModel(ReplaceRuleGroupRepository(), restoredHandle,
                        NamedGroupSession(activity.applicationContext, restoredHandle))
                    assertNotSame(retained, fresh)
                    assertEquals(large, fresh!!.state.value.name)
                    assertEquals(2, fresh!!.state.value.selectionStart)
                    assertEquals(5, fresh!!.state.value.selectionEnd)
                    assertTrue(fresh!!.state.value.editing)
                    assertFalse(fresh!!.state.value.recoveryFailed)
                }
                assertEquals(before, runBlocking(Dispatchers.IO) { GSON.toJson(appDb.replaceRuleDao.all) })
            } finally {
                scenario.onActivity { fresh?.stop(); model?.stop(); model?.clearSession() }
            }
        }
    }

    @Test fun actualUnicodeNameAndSelectionRestoreThenCancelWithoutRoomWrite() {
        val before = runBlocking(Dispatchers.IO) { GSON.toJson(appDb.replaceRuleDao.all) }
        val input = "raw😀组名"
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { io.legado.app.ui.replace.GroupManageDialog().show(it.supportFragmentManager, "groups") }
            NamedGroupsFlutterRobot(scenario).use { robot ->
                robot.ready(); robot.click("named-group-add")
                robot.driver.awaitIdentifier("named-group-name")
                // This separate case exercises the normal rendered field using real SET_TEXT
                // and SET_SELECTION, without representing a giant Binder transaction as tested.
                robot.input(input); robot.select(2, 5)
                scenario.recreate()
                robot.assertName(input); robot.assertSelection(2, 5)
                robot.click("named-group-cancel")
                robot.driver.await("Actual Unicode draft cancel completes") { !robot.host().model.state.value.editing }
                assertEquals(before, runBlocking(Dispatchers.IO) { GSON.toJson(appDb.replaceRuleDao.all) })
                robot.click("named-group-close")
            }
        }
    }

    private fun checkpointModel(activity: AboutActivity, handle: (SavedStateHandle) -> Unit): NamedGroupViewModel =
        ViewModelProvider(activity, viewModelFactory {
            initializer {
                val saved = createSavedStateHandle()
                handle(saved)
                NamedGroupViewModel(ReplaceRuleGroupRepository(), saved,
                    NamedGroupSession(activity.applicationContext, saved))
            }
        })["named-groups-private-checkpoint", NamedGroupViewModel::class.java]

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
