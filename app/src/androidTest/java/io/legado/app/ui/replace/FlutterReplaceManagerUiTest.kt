package io.legado.app.ui.replace

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.embedding.android.FlutterFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.ReplacePreviewConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** The normal Activity, real rendered controls, existing editor, and native Room receipts. */
class FlutterReplaceManagerUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun managerEditorRoomReturnAndRecreatePreserveMetadataSelectionAndFilter() {
        val token = UUID.randomUUID().toString()
        val rule = ReplaceRule(id=0,name="Manager $token",pattern="target",replacement="changed",group="Group $token",scope="Fixture",scopeTitle=true,isEnabled=false)
        rule.id = runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.insert(rule).single() }
        val sample = "target sample & ?"
        ReplacePreviewConfig.saveSample(rule.id,sample)
        try {
            ReplaceManagerUiProbe(instrumentation).use { ui ->
                val intent=Intent(instrumentation.targetContext,ReplaceRuleActivity::class.java)
                ActivityScenario.launchActivityForResult<ReplaceRuleActivity>(intent).use { scenario ->
                    var model: ReplaceManagementViewModel?=null
                    scenario.onActivity { model=it.managementModel;assertTrue(it.supportFragmentManager.fragments.any { f-> f is FlutterFragment }) }
                    ui.await15("manager Room load") { model!!.state.value.loaded && model!!.state.value.rows.any { it.id==rule.id } }
                    ui.driver.setText("replace-rule-search", token)
                    ui.await15("query exact native ACK") { model!!.state.value.query==token && model!!.state.value.rows.singleOrNull()?.id==rule.id }
                    ui.click("replace-rule-select-${rule.id}")
                    ui.await15("actual selection ACK") { ui.checked("replace-rule-select-${rule.id}",true) && model!!.state.value.selected==setOf(rule.id) }
                    ui.click("replace-rule-edit-${rule.id}")
                    ui.driver.awaitIdentifier("replace-editor-Name")
                    ui.driver.setText("replace-editor-Name","Edited $token")
                    androidx.test.espresso.Espresso.closeSoftKeyboard()
                    ui.click("replace-editor-save")
                    ui.await15("editor Room receipt then manager return") {
                        val value=runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id) }
                        value?.name=="Edited $token" && ui.driver.node("replace-rule-search")?.let { it.recycle();true }==true
                    }
                    val stored=runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!! }
                    assertEquals(rule.pattern,stored.pattern);assertEquals(rule.replacement,stored.replacement)
                    assertEquals(rule.group,stored.group);assertEquals(rule.scope,stored.scope);assertTrue(stored.scopeTitle);assertFalse(stored.isEnabled)
                    assertEquals(sample,ReplacePreviewConfig.sample(rule.id))
                    scenario.recreate();scenario.onActivity { model=it.managementModel }
                    // Native restoration can finish before the recreated UI engine publishes its
                    // first rendered field. Require both receipts within the original 15s budget.
                    ui.await15("restored Room/private session and actual Flutter query") {
                        val state = model!!.state.value
                        val nativeRestored = state.loaded && state.query == token && state.selected == setOf(rule.id)
                        val field = ui.driver.node("replace-rule-search")
                        val rendered = field?.let {
                            try {
                                it.refresh() && it.viewIdResourceName == "replace-rule-search" &&
                                    it.isVisibleToUser && it.isEditable && it.isEnabled &&
                                    it.text?.toString() == token
                            } finally { it.recycle() }
                        } ?: false
                        nativeRestored && rendered
                    }
                    assertEquals(token,ui.driver.text("replace-rule-search"))
                    ui.await15("restored actual checked row") { ui.checked("replace-rule-select-${rule.id}",true) }
                    ui.click("replace-rule-enabled-${rule.id}")
                    ui.await15("enabled Room receipt") { runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!!.isEnabled } && ui.checked("replace-rule-enabled-${rule.id}",true) }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    assertEquals(Activity.RESULT_OK,scenario.result.resultCode)
                }
            }
        } finally {
            runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)?.let { appDb.replaceRuleDao.delete(it) } }
            ReplacePreviewConfig.removeSample(rule.id)
        }
    }
}
