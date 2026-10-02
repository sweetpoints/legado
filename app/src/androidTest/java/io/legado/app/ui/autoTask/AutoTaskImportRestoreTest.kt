package io.legado.app.ui.autoTask

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class AutoTaskImportRestoreTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sessions = mutableListOf<String>()
    @After fun cleanup() { sessions.forEach { File(context.filesDir, "auto-task-import/$it.json").delete() } }
    private fun await(scenario: ActivityScenario<AboutActivity>, condition: (ImportAutoTaskDialog) -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity { activity ->
                (activity.supportFragmentManager.findFragmentByTag("auto-test") as? ImportAutoTaskDialog)?.let { ready = condition(it) }
            }
            if (ready) return; SystemClock.sleep(25)
        }
        throw AssertionError("Auto task import did not become ready")
    }
    @Test fun constructorAndRecreationKeepLargeScriptOnDiskAndExplicitEmptySelection() {
        val task = AutoTaskRule(UUID.randomUUID().toString(), "Large task", script = "//" + "x".repeat(120000))
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = ImportAutoTaskDialog(GSON.toJson(task))
                assertEquals(setOf("sessionId", "finishOnDismiss"), dialog.requireArguments().keySet())
                sessions += dialog.requireArguments().getString("sessionId")!!
                assertTrue(dialog.requireArguments().toString().length < 500)
                dialog.showNow(activity.supportFragmentManager, "auto-test")
            }
            await(scenario) { !it.model.state.value.loading }
            compose.onNodeWithTag("auto-task-import-all").performClick()
            scenario.recreate(); await(scenario) { !it.model.state.value.loading }
            compose.onNodeWithTag("auto-task-import-row-0").assertIsOff()
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag("auto-test") as ImportAutoTaskDialog
                assertTrue(dialog.model.state.value.items.single().json.contains(task.script))
                assertEquals(sessions.single(), dialog.requireArguments().getString("sessionId"))
                assertFalse(activity.isFinishing)
            }
        }
    }
    @Test fun restoredChildEditorUsesPublicRequestIdAndCallbackOnlyUpdatesMatchingRowOnce() {
        val task = AutoTaskRule(UUID.randomUUID().toString(), "Original", script = "run()")
        var request = ""
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = ImportAutoTaskDialog(GSON.toJson(task)); sessions += dialog.requireArguments().getString("sessionId")!!
                dialog.showNow(activity.supportFragmentManager, "auto-test")
            }
            await(scenario) { !it.model.state.value.loading }
            compose.onNodeWithTag("auto-task-import-edit-0").performClick()
            await(scenario) { dialog -> (dialog.childFragmentManager.fragments.singleOrNull() as? CodeDialog)?.requestId != null }
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag("auto-test") as ImportAutoTaskDialog
                request = (dialog.childFragmentManager.fragments.single() as CodeDialog).requestId!!
            }
            scenario.recreate(); await(scenario) { !it.model.state.value.loading }
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag("auto-test") as ImportAutoTaskDialog
                val child = dialog.childFragmentManager.fragments.single() as CodeDialog
                assertEquals(request, child.requestId); assertFalse(dialog.model.state.value.openEditor)
                dialog.onCodeSave(GSON.toJson(task.copy(name = "Ignored")), "stale")
                dialog.onCodeSave(GSON.toJson(task.copy(name = "Edited")), request)
                dialog.onCodeSave(GSON.toJson(task.copy(name = "Duplicate")), request)
                child.dismissAllowingStateLoss()
            }
            await(scenario) { !it.model.state.value.busy && it.model.state.value.items.single().name == "Edited" }
            compose.onNodeWithText("Edited").assertExists(); compose.onNodeWithText("Duplicate").assertDoesNotExist()
            compose.onNodeWithTag("auto-task-import-cancel").performClick()
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }
}
