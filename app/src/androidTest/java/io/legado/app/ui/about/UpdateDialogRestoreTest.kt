package io.legado.app.ui.about

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.update.AppUpdate
import org.junit.*
import org.junit.Assert.*
import java.io.File

class UpdateDialogRestoreTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ids = mutableListOf<String>()
    @After fun cleanup() { ids.forEach { File(context.filesDir, "update-dialog-requests/$it.json").delete() } }
    private fun await(scenario: ActivityScenario<AboutActivity>) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity { activity -> ready = (activity.supportFragmentManager.findFragmentByTag("update-test") as? UpdateDialog)?.model?.state?.value?.loading == false }
            if (ready) return; SystemClock.sleep(25)
        }
        throw AssertionError("Update did not restore")
    }
    @Test fun largeReleaseLogRemainsOnDiskAndRealRecreationRestoresScrolledContentAndMetadata() {
        val body = (1..150).joinToString("\n\n") { "Paragraph $it " + "Long release note ".repeat(30) } + "\n\nLast release paragraph"
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = UpdateDialog(AppUpdate.UpdateInfo("v-test", body, "primary", "app.apk", size = 1024, createdAt = 1704067200000))
                assertEquals(setOf("requestId"), dialog.requireArguments().keySet()); ids += dialog.requireArguments().getString("requestId")!!
                assertTrue(dialog.requireArguments().toString().length < 500); dialog.showNow(activity.supportFragmentManager, "update-test")
            }
            await(scenario); compose.onNodeWithText("Last release paragraph").performScrollTo().assertIsDisplayed()
            scenario.recreate(); await(scenario)
            compose.onNodeWithTag("update-title").assertTextEquals("v-test")
            compose.onNodeWithText("Last release paragraph").assertIsDisplayed()
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag("update-test") as UpdateDialog
                assertEquals(body, dialog.model.state.value.request!!.body); assertEquals(ids.single(), dialog.requireArguments().getString("requestId"))
                assertTrue(dialog.model.state.value.metadata.contains("1 kb")); assertFalse(activity.isFinishing)
            }
            compose.onNodeWithTag("update-cancel").performClick()
        }
    }
    @Test fun betaRecreationKeepsPrimaryUpdateAndBrowserWhileHidingIgnoreAndMirrors() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = UpdateDialog(AppUpdate.UpdateInfo("beta-test", "Log", "primary", "app.apk", "backup", "mirror", "alternate", isBeta = true))
                ids += dialog.requireArguments().getString("requestId")!!; dialog.showNow(activity.supportFragmentManager, "update-test")
            }
            await(scenario); scenario.recreate(); await(scenario)
            compose.onNodeWithTag("update-now").assertIsEnabled(); compose.onNodeWithTag("update-menu").performClick()
            compose.onNodeWithTag("update-browser").assertExists(); compose.onNodeWithTag("update-ignore").assertDoesNotExist()
            compose.onNodeWithTag("update-download-Mirror").assertDoesNotExist()
        }
    }
}
