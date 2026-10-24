package io.legado.app.ui.replace

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.KeyEvent
import org.junit.After
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaceManagementHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = ReplaceManagerUiProbe(instrumentation)
    private val driver = ui.driver
    @After fun cleanup() { ui.close() }
    private fun await10(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Original host deadline", condition())
    }

    @Test
    fun actualHostRecreateRestoresLargePrivateQueryAndModalWithoutLargeSavedBundleOrLegacyLists() {
        driver.enableAccessibility()
        ActivityScenario.launch(ReplaceRuleActivity::class.java).use { scenario ->
            var model: ReplaceManagementViewModel? = null
            scenario.onActivity { model = it.managementModel }
            await10 { model!!.state.value.loaded }
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
            await10 { model!!.state.value.loaded }
            assertEquals(query, model!!.state.value.query)
            assertEquals(8, model.state.value.queryEnd)
            assertEquals("Exact group", model.state.value.draft)
            assertEquals(ReplaceManagementDialog.AddGroup, model.state.value.dialog)
            // The large text is a private native checkpoint test, never an Intent/Bundle payload
            // or an Accessibility ACTION_SET_TEXT argument. This separately observes the small modal.
            driver.awaitIdentifier("replace-rule-dialog-field")
            assertEquals("Exact group", driver.text("replace-rule-dialog-field"))

        }
    }

    @Test
    fun manualActionReturnsOkAndPreservesPreferenceThroughRecreationAndBack() {
        val before = AppConfig.manualReplaceRule
        try {
            driver.enableAccessibility()
            val intent = Intent(instrumentation.targetContext, ReplaceRuleActivity::class.java)
            ActivityScenario.launchActivityForResult<ReplaceRuleActivity>(intent).use { scenario ->
                var model: ReplaceManagementViewModel? = null
                scenario.onActivity { model = it.managementModel }
                await10 { model!!.state.value.loaded }
                ui.click("replace-rule-more")
                ui.click("replace-rule-manual")
                await10 {
                    !model!!.state.value.busy && model!!.state.value.manual == !before
                }
                scenario.recreate()
                scenario.onActivity { model = it.managementModel }
                await10 { model!!.state.value.loaded }
                assertEquals(!before, model!!.state.value.manual)
                // A hardware Back cancels only the Flutter modal; the next Back returns RESULT_OK.
                scenario.onActivity { it.managementModel.dialog(ReplaceManagementDialog.AddGroup) }
                driver.awaitIdentifier("replace-rule-dialog-field")
                val transitionDeadline = SystemClock.uptimeMillis() + 10000
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await10 {
                    val field = driver.node("replace-rule-dialog-field")
                    val back = driver.node("replace-rule-back")
                    val rendered = field == null && back != null
                    field?.recycle(); back?.recycle()
                    model!!.state.value.dialog == null && rendered
                }
                val frame = CountDownLatch(1)
                val committed = Runnable { frame.countDown() }
                scenario.onActivity {
                    assertTrue(!it.isFinishing)
                    val decor = it.window.decorView
                    if (Build.VERSION.SDK_INT >= 29) {
                        decor.viewTreeObserver.registerFrameCommitCallback(committed)
                        decor.postInvalidateOnAnimation()
                    } else decor.postOnAnimation { decor.post(committed) }
                }
                try {
                    assertTrue("Modal dismissal frame within original 10s transition deadline",
                        frame.await((transitionDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS))
                } finally {
                    if (Build.VERSION.SDK_INT >= 29) scenario.onActivity {
                        it.window.decorView.viewTreeObserver.unregisterFrameCommitCallback(committed)
                    }
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
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
