package io.legado.app.ui.file

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.AppConst
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.*
import org.junit.Assert.*

class FileManagementHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun actualAppPrivateRootBrowsesAndOpensExactGrantedProviderUriOnceAndDeleteNeverRecurses() {
        val context = instrumentation.targetContext
        val root = context.getExternalFilesDir(null)?.parentFile
        Assume.assumeNotNull(root)
        val fixture = File(root!!, "file-manager-host-${UUID.randomUUID()}").apply { mkdir() }
        val file = File(fixture, "中文 space.txt").apply { writeText("fixture") }
        val nonempty = File(fixture, "nonempty").apply { mkdir() }
        val kept = File(nonempty, "kept").apply { writeText("kept") }
        val uri = FileProvider.getUriForFile(context, AppConst.authority, file)
        val opened = CopyOnWriteArrayList<Intent>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_VIEW) return null
                    opened += Intent(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
        val drafts = File(context.filesDir, "file-management-drafts")
        val before = drafts.listFiles().orEmpty().map { it.name }.toSet()
        var own = emptyList<File>()
        try {
            ActivityScenario.launch(FileManageActivity::class.java).use { scenario ->
                instrumentation.addMonitor(monitor)
                compose.waitUntil(10000) {
                    var ready = false
                    scenario.onActivity { ready = it.viewModel.state.value.loaded }
                    ready
                }
                scenario.onActivity { it.viewModel.click(fixture.path) }
                compose.waitUntil(10000) {
                    var ready = false
                    scenario.onActivity {
                        ready =
                            it.viewModel.state.value.directory == fixture.path &&
                                !it.viewModel.state.value.loading
                    }
                    ready
                }
                compose.onNodeWithTag("file-management-row-${file.path}").performClick()
                compose.waitUntil(10000) { opened.size == 1 }
                assertEquals(uri, opened.single().data)
                assertEquals("text/plain", opened.single().type)
                assertTrue(opened.single().flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                scenario.recreate()
                compose.waitUntil(10000) {
                    var ready = false
                    scenario.onActivity { ready = it.viewModel.state.value.loaded }
                    ready
                }
                compose.waitForIdle()
                assertEquals(1, opened.size)
                scenario.onActivity {
                    assertEquals(fixture.path, it.viewModel.state.value.directory)
                    assertNull(it.viewModel.state.value.navigation)
                }
                compose.onNodeWithTag("file-management-row-${nonempty.path}").performTouchInput {
                    longClick()
                }
                compose.onNodeWithTag("file-management-delete-${nonempty.path}").performClick()
                compose.waitUntil(10000) {
                    var ready = false
                    scenario.onActivity {
                        ready = !it.viewModel.state.value.busy && !it.viewModel.state.value.loading
                    }
                    ready
                }
                assertEquals("kept", kept.readText())
                compose.onNodeWithTag("file-management-row-${file.path}").performTouchInput {
                    longClick()
                }
                compose.onNodeWithTag("file-management-delete-${file.path}").performClick()
                compose.waitUntil(10000) { !file.exists() }
                assertTrue(kept.exists())
                own =
                    drafts.listFiles().orEmpty().filter {
                        it.name.endsWith(".json") && it.name !in before
                    }
                assertTrue(own.isNotEmpty())
                scenario.onActivity { it.finish() }
                compose.waitUntil(10000) { own.all { !it.exists() } }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            fixture.deleteRecursively()
            own.forEach { body ->
                listOf(".json", ".json.bak", ".json.new", ".closed").forEach {
                    File(body.parentFile, body.name.removeSuffix(".json") + it).delete()
                }
            }
        }
    }
}
