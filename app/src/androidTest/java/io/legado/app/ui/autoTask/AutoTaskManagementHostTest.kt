package io.legado.app.ui.autoTask

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.help.IntentData
import io.legado.app.ui.file.HandleFileContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class AutoTaskManagementHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun realHostRoutesTaskIdAndLoginTypeAndFileImportExportUsesFullFreshJson() {
        val id = UUID.randomUUID().toString()
        val task = AutoTaskRule(id, "Compose host $id", false, "0 * * * *", script = "script:$id", loginUrl = "https://login.invalid", header = "headers", lastLog = "private runtime")
        runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.upsert(task) }
        val intents = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                val destination = intent.component?.className.orEmpty()
                if (destination.endsWith("AutoTaskEditActivity") || destination.endsWith("AutoTaskDebugActivity") || destination.endsWith("SourceLoginActivity") || destination.endsWith("HandleFileActivity")) {
                    intents += Intent(intent); return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        try {
            ActivityScenario.launch(AutoTaskActivity::class.java).use { scenario ->
                instrumentation.addMonitor(monitor)
                compose.onNodeWithTag("tasks-search").performTextReplacement(task.name)
                compose.waitUntil { compose.onAllNodesWithTag("task-$id").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("task-edit-$id").performClick()
                compose.waitUntil { intents.size == 1 }; assertEquals(id, intents.last().getStringExtra("autoTaskId"))
                compose.onNodeWithTag("task-debug-$id").performClick()
                compose.waitUntil { intents.size == 2 }; assertEquals(id, intents.last().getStringExtra("autoTaskId"))
                compose.onNodeWithTag("task-menu-$id").performClick()
                compose.onNodeWithText(instrumentation.targetContext.getString(R.string.login)).performClick()
                compose.waitUntil { intents.size == 3 }; assertEquals("autoTask", intents.last().getStringExtra("type")); assertEquals(id, intents.last().getStringExtra("key"))
                compose.onNodeWithTag("tasks-menu").performClick(); compose.onNodeWithText(instrumentation.targetContext.getString(R.string.import_local)).performClick()
                compose.waitUntil { intents.size == 4 }; assertEquals(HandleFileContract.FILE, intents.last().getIntExtra("mode", -1))
                assertArrayEquals(arrayOf("txt", "json"), intents.last().getStringArrayExtra("allowExtensions"))
                compose.onNodeWithTag("task-select-$id").performClick(); compose.onNodeWithTag("tasks-batch").performClick()
                compose.onNodeWithText(instrumentation.targetContext.getString(R.string.export_selection)).performClick()
                compose.waitUntil { intents.size == 5 }
                val export = intents.last(); assertEquals(HandleFileContract.EXPORT, export.getIntExtra("mode", -1))
                assertEquals("exportAutoTaskSelection.json", export.getStringExtra("fileName")); assertEquals("application/json", export.getStringExtra("contentType"))
                val json = IntentData.get<String>(export.getStringExtra("fileKey"))!!
                assertTrue(json.contains("script:$id")); assertTrue(json.contains("headers")); assertFalse(json.contains("private runtime")); assertFalse(json.contains("lastLog"))
                scenario.recreate(); compose.onNodeWithTag("tasks-search").assertTextEquals(task.name); compose.onNodeWithTag("task-select-$id").assertIsOn()
                assertEquals(5, intents.size)
            }
        } finally { instrumentation.removeMonitor(monitor); runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.deleteByIds(listOf(id)) } }
    }
    @Test fun actualExportNoticeCreatesAutoTaskPassphraseAndLocalFilesDoNotOfferIt() {
        val repo = io.legado.app.data.repository.RoomAutoTaskManagementRepository(instrumentation.targetContext)
        runBlocking(Dispatchers.IO) {
            val notice = repo.exportNotice("https://export.invalid/auto.json")
            val decoded = io.legado.app.help.SourceSharePassphrase.decode(requireNotNull(notice.passphrase))
            assertTrue(decoded is io.legado.app.help.SourceSharePassphrase.DecodeResult.Success)
            val value = (decoded as io.legado.app.help.SourceSharePassphrase.DecodeResult.Success).value
            assertEquals(io.legado.app.help.SourceSharePassphrase.Type.AUTO_TASK, value.type)
            assertEquals(notice.url, value.url)
            val local = repo.exportNotice("content://export/auto.json")
            assertNull(local.passphrase); assertEquals("", local.summary)
        }
    }
    @Test fun realOnlineImportChildRestoresWithoutDuplicateAndCancellationKeepsRoomUnchanged() {
        val id = UUID.randomUUID().toString(); val task = AutoTaskRule(id, "Paste $id", false, "0 * * * *", script = "42")
        val json = io.legado.app.utils.GSON.toJson(listOf(task))
        try {
            ActivityScenario.launch(AutoTaskActivity::class.java).use { scenario ->
                compose.onNodeWithTag("tasks-menu").performClick(); compose.onNodeWithText(instrumentation.targetContext.getString(R.string.import_on_line)).performClick()
                compose.waitUntil { var loaded = false; scenario.onActivity { loaded = !it.viewModel.state.value.onlineLoading }; loaded }
                compose.onNodeWithTag("tasks-online-input").performTextReplacement(json)
                scenario.recreate(); compose.onNodeWithTag("tasks-online-input").assertTextEquals(json)
                compose.onNodeWithTag("tasks-online-confirm").performClick()
                compose.waitUntil { var count = 0; scenario.onActivity { count = it.supportFragmentManager.fragments.filterIsInstance<ImportAutoTaskDialog>().size }; count == 1 }
                scenario.recreate()
                scenario.onActivity {
                    val dialogs = it.supportFragmentManager.fragments.filterIsInstance<ImportAutoTaskDialog>()
                    assertEquals(1, dialogs.size); dialogs.single().dismiss()
                }
                runBlocking(Dispatchers.IO) { assertNull(appDb.autoTaskRuleDao.getById(id)) }
            }
        } finally { runBlocking(Dispatchers.IO) { appDb.autoTaskRuleDao.deleteByIds(listOf(id)) } }
    }
}
