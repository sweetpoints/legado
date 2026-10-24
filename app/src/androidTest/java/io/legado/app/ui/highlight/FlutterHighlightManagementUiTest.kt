package io.legado.app.ui.highlight

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.android.FlutterFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.ui.flutter.FlutterUiTestDriver
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Normal entry points; real Flutter actions and Room/durable-session receipts. */
class FlutterHighlightManagementUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dao = appDb.highlightRuleDao
    private fun <T> database(action: () -> T) = runBlocking(Dispatchers.IO) { action() }
    private fun fixture(action: (HighlightRule, HighlightRule) -> Unit) {
        val backup = database { dao.all }
        val first = HighlightRule(name = "Flutter first", pattern = "first", group = "Flutter A", order = 0).apply { isEnabled = false }
        val hidden = HighlightRule(name = "Hidden", pattern = "hidden", group = "Flutter B", order = 1)
        val last = HighlightRule(name = "Flutter last", pattern = "last", group = "Flutter A", order = 2)
        try { database { dao.deleteAll(); dao.insert(first, hidden, last) }; action(database { dao.all.single { it.uuid == first.uuid } }, database { dao.all.single { it.uuid == hidden.uuid } }) }
        finally { database { dao.deleteAll(); if (backup.isNotEmpty()) dao.insert(*backup.toTypedArray()) } }
    }
    @Test fun normalManagerFilterSelectionRecreateDeleteCancelAndSwitchPreserveFullMetadata() = fixture { first, hidden ->
        FlutterUiTestDriver(instrumentation).use { driver ->
            driver.enableAccessibility(); val ui = HighlightManagerUiProbe(driver, instrumentation)
            ActivityScenario.launch(HighlightRuleActivity::class.java).use { scenario ->
                ui.await15("normal Flutter manager and Room projection") {
                    var ready = false; scenario.onActivity { host -> ready = host.supportFragmentManager.fragments.any { it is FlutterFragment } && host.viewModel.state.value.canAct && host.viewModel.state.value.rules.size == 3 }; ready
                }
                ui.click("highlight-management-menu"); ui.click("highlight-management-filter"); ui.click("highlight-filter-Flutter A")
                ui.await15("actual filtered rows") { !ui.exists("highlight-management-row-${hidden.uuid}") && ui.exists("highlight-management-row-${first.uuid}") }
                ui.click("highlight-management-select-${first.uuid}"); ui.await15("actual selection ACK") { ui.checked("highlight-management-select-${first.uuid}", true) }
                scenario.recreate()
                ui.await15("private filter and actual selection restored") {
                    var native = false; scenario.onActivity { native = it.viewModel.state.value.canAct && it.viewModel.state.value.draft.filter == "Flutter A" && it.viewModel.state.value.draft.selection == setOf(first.uuid) }
                    native && ui.checked("highlight-management-select-${first.uuid}", true) && !ui.exists("highlight-management-row-${hidden.uuid}")
                }
                ui.click("highlight-management-delete-selection"); ui.await15("actual deletion confirmation") { ui.exists("highlight-management-delete-confirm") }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                ui.await15("Back cancels only deletion") { !ui.exists("highlight-management-delete-confirm") }
                assertEquals(3, database { dao.all.size })
                ui.click("highlight-management-enabled-${first.uuid}")
                ui.await15("actual enabled switch and committed Room") { ui.checked("highlight-management-enabled-${first.uuid}", true) && database { dao.all.single { it.uuid == first.uuid }.isEnabled } }
                assertEquals(GSON.toJson(first.copy(isEnabled = true)), GSON.toJson(database { dao.all.single { it.uuid == first.uuid } }))
                assertEquals(GSON.toJson(hidden), GSON.toJson(database { dao.all.single { it.uuid == hidden.uuid } }))
                val last = database { dao.all.single { it.name == "Flutter last" } }
                val source = driver.bounds("highlight-management-name-${first.uuid}")
                val target = driver.bounds("highlight-management-name-${last.uuid}")
                driver.touchDown(source.exactCenterX(), source.exactCenterY()).use { touch ->
                    touch.hold(650)
                    touch.moveTo(target.exactCenterX(), target.exactCenterY())
                    ui.await15("held physical drag updates native visible order before release") {
                        var moved = false; scenario.onActivity { moved = it.viewModel.state.value.visible.map { row -> row.uuid } == listOf(last.uuid, first.uuid) }; moved
                    }
                }
                ui.await15("filtered drag Room receipt preserves hidden slot") { database { dao.all.map { it.uuid } == listOf(last.uuid, hidden.uuid, first.uuid) } }
                assertEquals(GSON.toJson(first.copy(isEnabled = true, order = 2)), GSON.toJson(database { dao.all.single { it.uuid == first.uuid } }))
                assertEquals(GSON.toJson(last.copy(order = 0)), GSON.toJson(database { dao.all.single { it.uuid == last.uuid } }))
                assertEquals(GSON.toJson(hidden), GSON.toJson(database { dao.all.single { it.uuid == hidden.uuid } }))
            }
        }
    }
    @Test fun normalGroupRawRenameRecreateCancelThenConfirmPreservesOtherMetadata() = fixture { first, hidden ->
        FlutterUiTestDriver(instrumentation).use { driver ->
            driver.enableAccessibility(); val ui = HighlightManagerUiProbe(driver, instrumentation)
            ActivityScenario.launch(HighlightRuleActivity::class.java).use { scenario ->
                ui.await15("normal manager ready") { ui.exists("highlight-management-menu") }
                ui.click("highlight-management-menu"); ui.click("highlight-management-groups")
                ui.await15("normal group Flutter label") { ui.exists("highlight-group-label-Flutter A") }
                ui.click("highlight-group-edit-Flutter A"); driver.setText("highlight-group-name", "  Private😀\nDraft  ")
                ui.await15("raw group native ACK") {
                    var raw = false; scenario.onActivity { host -> raw = host.supportFragmentManager.fragments.filterIsInstance<HighlightGroupManageDialog>().single().model.state.value.name == "  Private😀\nDraft  " }; raw
                }
                scenario.recreate(); ui.await15("actual recreated private raw draft") { ui.text("highlight-group-name") == "  Private😀\nDraft  " }
                ui.click("highlight-group-cancel"); ui.await15("rename canceled") { !ui.exists("highlight-group-name") }
                assertEquals(GSON.toJson(first), GSON.toJson(database { dao.all.single { it.uuid == first.uuid } }))
                ui.click("highlight-group-edit-Flutter A"); driver.setText("highlight-group-name", "  Renamed  "); ui.click("highlight-group-rename-confirm")
                ui.await15("rename commits before actual group receipt") { database { dao.all.single { it.uuid == first.uuid }.group == "Renamed" } && ui.exists("highlight-group-label-Renamed") }
                assertEquals(GSON.toJson(first.copy(group = "Renamed")), GSON.toJson(database { dao.all.single { it.uuid == first.uuid } }))
                assertEquals(GSON.toJson(hidden), GSON.toJson(database { dao.all.single { it.uuid == hidden.uuid } }))
            }
        }
    }
    @Test fun normalManagerActualListScrollRestoresFromPrivateSessionAfterActivityRecreate() {
        val backup = database { dao.all }
        val rows = (0 until 60).map { index -> HighlightRule(name = "Scroll $index", pattern = "pattern $index", order = index) }
        try {
            database { dao.deleteAll(); dao.insert(*rows.toTypedArray()) }
            FlutterUiTestDriver(instrumentation).use { driver ->
                driver.enableAccessibility(); val ui = HighlightManagerUiProbe(driver, instrumentation)
                ActivityScenario.launch(HighlightRuleActivity::class.java).use { scenario ->
                    val id = "highlight-management-name-${rows[40].uuid}"
                    ui.reveal(id)
                    val bounds = driver.bounds(id)
                    var savedIndex = 0; var savedOffset = 0.0
                    ui.await15("actual scroll acknowledged in private native draft") {
                        scenario.onActivity { savedIndex = it.viewModel.state.value.draft.scrollIndex; savedOffset = it.viewModel.state.value.draft.scrollOffset }
                        savedIndex > 0
                    }
                    scenario.recreate()
                    ui.await15("actual row restores without scrolling the recreated list") { ui.exists(id) && kotlin.math.abs(driver.bounds(id).top - bounds.top) <= 2 }
                    scenario.onActivity { assertEquals(savedIndex, it.viewModel.state.value.draft.scrollIndex); assertEquals(savedOffset, it.viewModel.state.value.draft.scrollOffset, .01) }
                    assertEquals(rows.map { it.uuid }, database { dao.all.map { it.uuid } })
                }
            }
        } finally { database { dao.deleteAll(); if (backup.isNotEmpty()) dao.insert(*backup.toTypedArray()) } }
    }

}
