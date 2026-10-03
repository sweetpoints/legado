package io.legado.app.ui.file

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.repository.HandleFileChoice
import io.legado.app.help.IntentData
import io.legado.app.utils.putJson
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class HandleFileChoicesHostTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun recreatedCustomSelectionRetainsOriginalCallerValue() {
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), HandleFileActivity::class.java)
                .putExtra("mode", HandleFileContract.FILE)
                .putExtra("value", "Caller value")
        intent.putJson("otherActions", listOf(HandleFileChoice("content://exact/path", 42)))
        ActivityScenario.launchActivityForResult<HandleFileActivity>(intent).use { scenario ->
            compose.waitUntil(timeoutMillis = 20_000) {
                compose
                    .onAllNodes(androidx.compose.ui.test.hasTestTag("handle-file-choice-2"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            scenario.recreate()
            compose.onNodeWithTag("handle-file-choice-2").performClick()
            compose.waitUntil(timeoutMillis = 20_000) {
                scenario.state == Lifecycle.State.DESTROYED
            }
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertEquals("content://exact/path", scenario.result.resultData?.data.toString())
            assertEquals("Caller value", scenario.result.resultData?.getStringExtra("value"))
        }
    }

    @Test
    fun restoredExportOwnsLargePayloadAndOmitsCallerValue() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "handle-host-${UUID.randomUUID()}")
        directory.mkdirs()
        val payload = "Large payload".repeat(170_000)
        val intent =
            Intent(context, HandleFileActivity::class.java)
                .putExtra("mode", HandleFileContract.EXPORT)
                .putExtra("fileName", "result.txt")
                .putExtra("fileKey", IntentData.put(payload))
                .putExtra("contentType", "text/plain")
                .putExtra("value", "Must be omitted")
        intent.putJson("otherActions", listOf(HandleFileChoice(directory.path, 42)))
        try {
            ActivityScenario.launchActivityForResult<HandleFileActivity>(intent).use { scenario ->
                compose.waitUntil(timeoutMillis = 20_000) {
                    compose
                        .onAllNodes(androidx.compose.ui.test.hasTestTag("handle-file-choice-4"))
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                scenario.recreate()
                compose.onNodeWithTag("handle-file-choice-4").performClick()
                compose.waitUntil(timeoutMillis = 20_000) {
                    scenario.state == Lifecycle.State.DESTROYED
                }
                assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
                assertEquals(payload, File(directory, "result.txt").readText())
                assertFalse(scenario.result.resultData!!.hasExtra("value"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
