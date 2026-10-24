package io.legado.app.ui.book.toc.rule

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.android.FlutterFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.model.localBook.TextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Normal native entry points, real Flutter controls, Room and durable private receipts. */
class FlutterTxtTocManagerUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun <T> db(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }
    @Test fun managerEditorRoomReturnAndPrivateQuerySelectionSurviveRecreation() {
        val token = UUID.randomUUID().toString(); val id = System.currentTimeMillis()
        val row = TxtTocRule(id, "Manager $token", "^Chapter(.+)", "@js:return result", "Example $token", 9931, false)
        db { appDb.txtTocRuleDao.insert(row) }
        try { TxtTocManagerUiProbe(instrumentation).use { ui ->
            ActivityScenario.launch<TxtTocRuleActivity>(Intent(instrumentation.targetContext, TxtTocRuleActivity::class.java)).use { scenario ->
                var model: TxtTocRuleManagementViewModel? = null
                scenario.onActivity { model = it.viewModel; assertTrue(it.supportFragmentManager.fragments.any { f -> f is FlutterFragment }) }
                ui.await15("TXT Room loaded") { model!!.state.value.let { !it.loading && it.rules.any { r -> r.id == id } } }
                ui.driver.setText("txt-toc-search", token)
                ui.await15("query exact private ACK") { model!!.state.value.query == token && model!!.state.value.visible.singleOrNull()?.id == id }
                ui.reveal("txt-toc-select-$id"); ui.click("txt-toc-select-$id")
                ui.await15("actual/native selection") { model!!.state.value.selected == setOf(id) && ui.checked("txt-toc-select-$id", true) }
                ui.click("txt-toc-edit-$id"); ui.driver.awaitIdentifier("txt-toc-editor-Name")
                ui.driver.setText("txt-toc-editor-Name", "Edited $token")
                androidx.test.espresso.Espresso.closeSoftKeyboard(); ui.click("txt-toc-editor-save")
                ui.await15("editor Room transaction and manager return") { db { appDb.txtTocRuleDao.get(id)?.name } == "Edited $token" && ui.driver.node("txt-toc-search")?.let { it.recycle(); true } == true }
                val stored = db { appDb.txtTocRuleDao.get(id)!! }; assertEquals(row.rule, stored.rule); assertEquals(row.replacement, stored.replacement); assertEquals(row.example, stored.example); assertEquals(row.serialNumber, stored.serialNumber); assertFalse(stored.enable)
                scenario.recreate(); scenario.onActivity { model = it.viewModel }
                ui.await15("durable query and selected identity restored") { model!!.state.value.let { !it.loading && it.query == token && it.selected == setOf(id) } && ui.driver.text("txt-toc-search") == token }
                ui.reveal("txt-toc-enabled-$id"); ui.await15("restored actual checkbox") { ui.checked("txt-toc-select-$id", true) }
                ui.click("txt-toc-enabled-$id")
                ui.await15("enabled Room receipt") { db { appDb.txtTocRuleDao.get(id)?.enable } == true && ui.checked("txt-toc-enabled-$id", true) }
            }
        } } finally { db { appDb.txtTocRuleDao.get(id)?.let { appDb.txtTocRuleDao.delete(it) } } }
    }
    @Test fun largePrivateQueryAndOnlineDraftKeepActualRawSelectionsWithoutLargeSavedBundle() {
        val query = "private-query ".repeat(24000); val draft = "private-url ".repeat(28000)
        TxtTocManagerUiProbe(instrumentation).use { ui ->
            ActivityScenario.launch(TxtTocRuleActivity::class.java).use { scenario ->
                var model: TxtTocRuleManagementViewModel? = null; scenario.onActivity { model = it.viewModel }
                ui.await15("manager private session load") { !model!!.state.value.loading }
                fun select(id: String) {
                    androidx.test.espresso.Espresso.closeSoftKeyboard()
                    ui.await15("actual focused field advertises selection $id") { ui.actual(id) { it.isFocused && it.isEditable && it.isEnabled && it.actionList.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION } } }
                    var accepted = false
                    ui.actual(id) { node -> if (node.isFocused && node.isEditable && node.isEnabled && node.actionList.any { a -> a.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION }) {
                        accepted = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                            putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 2)
                            putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 5)
                        }); accepted
                    } else false }
                    assertTrue("Single TXT raw selection action accepted", accepted)
                }
                ui.driver.setText("txt-toc-search", query); select("txt-toc-search")
                ui.await15("query actual/native raw selection durable ACK") { model!!.state.value.let { it.query == query && it.queryStart == 2 && it.queryEnd == 5 } && ui.actual("txt-toc-search") { it.text?.toString() == query && it.textSelectionStart == 2 && it.textSelectionEnd == 5 } && ui.actual("txt-toc-menu") { it.isEnabled && it.isClickable } }
                ui.click("txt-toc-menu"); ui.click("txt-toc-action:online"); ui.driver.setText("txt-toc-online-input", draft); select("txt-toc-online-input")
                ui.await15("online actual/native raw selection durable ACK") { model!!.state.value.let { it.online && it.onlineInput == draft && it.onlineStart == 2 && it.onlineEnd == 5 } && ui.actual("txt-toc-online-input") { it.text?.toString() == draft && it.textSelectionStart == 2 && it.textSelectionEnd == 5 } && ui.actual("txt-toc-online-confirm") { it.isEnabled && it.isClickable } }
                scenario.onActivity { activity ->
                    val bundle = Bundle()
                    android.app.Activity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java).apply { isAccessible = true }.invoke(activity, bundle)
                    val parcel = android.os.Parcel.obtain()
                    try { parcel.writeBundle(bundle); assertTrue("Private TXT input did not enter saved Bundle", parcel.dataSize() < 256000) } finally { parcel.recycle() }
                }
                scenario.recreate(); scenario.onActivity { model = it.viewModel }
                ui.await15("restored native private input and exact raw selections") { model!!.state.value.let { !it.loading && it.query == query && it.queryStart == 2 && it.queryEnd == 5 && it.online && it.onlineInput == draft && it.onlineStart == 2 && it.onlineEnd == 5 } }
                ui.await15("restored actual online input and raw selection") { ui.actual("txt-toc-online-input") { it.text?.toString() == draft && it.textSelectionStart == 2 && it.textSelectionEnd == 5 } }
                ui.click("txt-toc-dialog-cancel")
                ui.await15("modal cancellation retains actual background query selection") { !model!!.state.value.online && ui.actual("txt-toc-search") { it.text?.toString() == query && it.textSelectionStart == 2 && it.textSelectionEnd == 5 } }
            }
        }
    }
    @Test fun physicalDragPreservesConcurrentMetadataAndCancelledDragDoesNotCommit() {
        val original = db { appDb.txtTocRuleDao.all }; val id = System.currentTimeMillis(); val token = UUID.randomUUID().toString()
        val rows = (0..2).map { i -> TxtTocRule(id+i, "Drag $i $token", "^$i", "result$i", "Example$i", -100000+i, i != 0) }
        db { rows.forEach { appDb.txtTocRuleDao.insert(it) } }
        try { TxtTocManagerUiProbe(instrumentation).use { ui ->
            ActivityScenario.launch(TxtTocRuleActivity::class.java).use { scenario ->
                var model: TxtTocRuleManagementViewModel? = null; scenario.onActivity { model = it.viewModel }
                ui.await15("three fixture rows at real list head") { model!!.state.value.let { !it.loading && it.rules.take(3).map { r -> r.id } == rows.map { r -> r.id } } }
                ui.reveal("txt-toc-name-$id"); ui.driver.awaitIdentifier("txt-toc-name-${id+2}")
                val from = ui.bounds("txt-toc-name-$id"); val to = ui.bounds("txt-toc-name-${id+2}"); var down = SystemClock.uptimeMillis()
                fun event(action: Int, x: Float, y: Float) { if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis(); val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); try { assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() } }
                event(MotionEvent.ACTION_DOWN, from.centerX().toFloat(), from.centerY().toFloat()); SystemClock.sleep(700)
                event(MotionEvent.ACTION_MOVE, from.centerX().toFloat(), to.centerY().toFloat());
                ui.await15("actual drag preview follows business identity") { model!!.state.value.rules.take(3).map { it.id } == listOf(id+1,id+2,id) }
                db { appDb.txtTocRuleDao.get(id+1)!!.let { appDb.txtTocRuleDao.update(it.copy(example = "Concurrent $token")) } }
                ui.await15("concurrent metadata appears during held drag") { model!!.state.value.rules.firstOrNull { it.id == id+1 }?.example == "Concurrent $token" }
                event(MotionEvent.ACTION_UP, from.centerX().toFloat(), to.centerY().toFloat())
                ui.await15("drag order persisted preserving current metadata") { db { appDb.txtTocRuleDao.all.take(3).map { it.id } } == listOf(id+1,id+2,id) && !model!!.state.value.busy }
                val committed = db { appDb.txtTocRuleDao.all.map { it.id to it.serialNumber } }
                assertEquals("Concurrent $token", db { appDb.txtTocRuleDao.get(id+1)?.example }); assertEquals(rows[0].replacement, db { appDb.txtTocRuleDao.get(id)?.replacement }); assertFalse(db { appDb.txtTocRuleDao.get(id)!!.enable })
                val again = ui.bounds("txt-toc-name-${id+1}"); val last = ui.bounds("txt-toc-name-$id")
                event(MotionEvent.ACTION_DOWN, again.centerX().toFloat(), again.centerY().toFloat()); SystemClock.sleep(700); event(MotionEvent.ACTION_MOVE, again.centerX().toFloat(), last.centerY().toFloat())
                ui.await15("cancelled drag had a real preview") { model!!.state.value.rules.take(3).map { it.id } != listOf(id+1,id+2,id) }
                event(MotionEvent.ACTION_CANCEL, again.centerX().toFloat(), last.centerY().toFloat())
                ui.await15("cancel restores native order") { model!!.state.value.rules.take(3).map { it.id } == listOf(id+1,id+2,id) }
                assertEquals(committed, db { appDb.txtTocRuleDao.all.map { it.id to it.serialNumber } })
            }
        } } finally { db { rows.mapNotNull { appDb.txtTocRuleDao.get(it.id) }.forEach { appDb.txtTocRuleDao.delete(it) }; if (original.isNotEmpty()) appDb.txtTocRuleDao.update(*original.toTypedArray()) } }
    }
    @Test fun readerPickerInitialRegexRecreatesAndDeliversOnceWhileCancelNeverDelivers() {
        val id = System.currentTimeMillis(); val token = UUID.randomUUID().toString(); val row = TxtTocRule(id, "Picker $token", "^Chapter", "result", "Example", -100000, true)
        db { appDb.txtTocRuleDao.insert(row) }
        try { TxtTocManagerUiProbe(instrumentation).use { ui ->
            ActivityScenario.launch<TxtTocPickerTestActivity>(Intent(instrumentation.targetContext, TxtTocPickerTestActivity::class.java)).use { scenario ->
                fun show(tag: String) { scenario.onActivity { activity ->
                    TxtTocRuleDialog(row.rule + TextFile.spaceChars + row.replacement).showNow(activity.supportFragmentManager,tag)
                } }
                fun dialog(): TxtTocRuleDialog? { var d: TxtTocRuleDialog? = null; scenario.onActivity { d = it.supportFragmentManager.fragments.filterIsInstance<TxtTocRuleDialog>().singleOrNull() }; return d }
                show("picker-one"); ui.await15("picker normal Flutter host and initial regex") { dialog()?.viewModel?.state?.value?.let { !it.loading && it.selectedId == id } == true && ui.checked("txt-toc-select-$id",true) }
                scenario.recreate(); ui.await15("picker selected identity restored after recreation") { dialog()?.viewModel?.state?.value?.let { !it.loading && it.selectedId == id } == true && ui.checked("txt-toc-select-$id",true) }
                ui.click("txt-toc-confirm"); ui.await15("picker closed after callback receipt") { dialog() == null }
                scenario.onActivity { assertEquals(listOf(row.rule + TextFile.spaceChars + row.replacement), it.receipts) }
                show("picker-cancel"); ui.driver.awaitIdentifier("txt-toc-confirm"); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); ui.await15("hardware Back closes only picker") { dialog() == null }
                scenario.onActivity { assertFalse(it.isFinishing); assertEquals(1,it.receipts.size) }
                assertEquals(row.rule, db { appDb.txtTocRuleDao.get(id)?.rule })
            }
        } } finally { db { appDb.txtTocRuleDao.get(id)?.let { appDb.txtTocRuleDao.delete(it) } } }
    }
}
