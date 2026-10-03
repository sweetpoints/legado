package io.legado.app.ui.replace

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReplaceManagementHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun actualHostRecreateRestoresLargePrivateQueryAndModalWithoutLargeSavedBundleOrLegacyLists() {
        ActivityScenario.launch(ReplaceRuleActivity::class.java).use { scenario ->
            var model: ReplaceManagementViewModel? = null
            scenario.onActivity { model = it.managementModel }
            compose.waitUntil(timeoutMillis = 10000) { model!!.state.value.loaded }
            val query = "Q".repeat(2000000)
            scenario.onActivity {
                it.managementModel.query(query, 3, 8)
                it.managementModel.dialog(ReplaceManagementDialog.AddGroup)
                it.managementModel.draft("Exact group", 2, 4)
            }
            runBlockingFlush(scenario)
            scenario.onActivity { activity ->
                val bundle = Bundle()
                Activity::class
                    .java
                    .getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, bundle)
                val parcel = Parcel.obtain()
                try {
                    parcel.writeBundle(bundle)
                    assertTrue(parcel.dataSize() < 256000)
                } finally {
                    parcel.recycle()
                }
            }
            scenario.recreate()
            scenario.onActivity { model = it.managementModel }
            compose.waitUntil(timeoutMillis = 10000) { model!!.state.value.loaded }
            assertEquals(query, model!!.state.value.query)
            assertEquals(8, model!!.state.value.queryEnd)
            assertEquals("Exact group", model!!.state.value.draft)
            assertEquals(ReplaceManagementDialog.AddGroup, model!!.state.value.dialog)
            compose.onNodeWithTag("replace-rule-dialog-field").assertExists()
        }
    }

    @Test
    fun manualActionReturnsOkAndPreservesPreferenceThroughRecreationAndBack() {
        val before = AppConfig.manualReplaceRule
        try {
            val intent = Intent(instrumentation.targetContext, ReplaceRuleActivity::class.java)
            ActivityScenario.launchActivityForResult<ReplaceRuleActivity>(intent).use { scenario ->
                var model: ReplaceManagementViewModel? = null
                scenario.onActivity { model = it.managementModel }
                compose.waitUntil(timeoutMillis = 10000) { model!!.state.value.loaded }
                compose.onNodeWithTag("replace-rule-more").performClick()
                compose.onNodeWithTag("replace-rule-manual").performClick()
                compose.waitUntil(timeoutMillis = 10000) {
                    !model!!.state.value.busy && model!!.state.value.manual == !before
                }
                scenario.recreate()
                scenario.onActivity { model = it.managementModel }
                compose.waitUntil(timeoutMillis = 10000) { model!!.state.value.loaded }
                assertEquals(!before, model!!.state.value.manual)
                compose.onNodeWithTag("replace-rule-back").performClick()
                assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            }
        } finally {
            instrumentation.runOnMainSync { AppConfig.manualReplaceRule = before }
        }
    }

    private fun runBlockingFlush(scenario: ActivityScenario<ReplaceRuleActivity>) {
        var model: ReplaceManagementViewModel? = null
        scenario.onActivity { model = it.managementModel }
        kotlinx.coroutines.runBlocking { model!!.flush() }
    }
}
