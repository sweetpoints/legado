package io.legado.app.ui.replace.edit

import android.app.Activity
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.repository.ReplaceEditorField
import io.legado.app.data.repository.ReplaceEditorText
import io.legado.app.help.config.ReplacePreviewConfig
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class ReplaceEditorHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<ReplaceEditActivity>? = null
    private lateinit var model: ReplaceEditorViewModel
    private val ids = mutableListOf<Long>(); private val sessions = mutableListOf<String>()
    @After fun cleanup() {
        scenario?.close()
        runBlocking(Dispatchers.IO) { ids.forEach { id -> appDb.replaceRuleDao.findById(id)?.let { appDb.replaceRuleDao.delete(it) }; ReplacePreviewConfig.removeSample(id) } }
        sessions.forEach { File(context.filesDir, "replace-editor-drafts/$it.json").delete() }
    }
    private fun launch(rule: ReplaceRule? = null) {
        rule?.let { ids += it.id; runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.insert(it) } }
        scenario = ActivityScenario.launchActivityForResult(ReplaceEditActivity.startIntent(context, rule?.id ?: -1,
            pattern = if (rule == null) "a" else null, isRegex = false, scope = if (rule == null) "Book" else null))
        scenario!!.onActivity { model = it.viewModel; sessions += model.session }
        compose.waitUntil { model.state.value.loaded }
    }
    @Test fun savedRulePreservesHiddenMetadataAndReturnsSuccessOnlyAfterRoomReceipt() {
        val rule = ReplaceRule(id = System.currentTimeMillis(), name = "Original", pattern = "a", replacement = "b",
            isRegex = false, isEnabled = false, order = 42, scopeTitle = true, scopeSource = true, scopeContent = false)
        launch(rule)
        compose.onNodeWithTag("replace-editor-Name").performTextReplacement(" Edited ")
        compose.onNodeWithTag("replace-editor-Sample").performScrollTo().performTextReplacement("aaa")
        compose.onNodeWithTag("replace-editor-save").performClick()
        compose.waitUntil { model.state.value.finished && model.state.value.saved }
        assertEquals(Activity.RESULT_OK, scenario!!.result.resultCode)
        val saved = runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!! }
        assertEquals(" Edited ", saved.name); assertFalse(saved.isEnabled); assertEquals(42, saved.order)
        assertTrue(saved.scopeTitle); assertTrue(saved.scopeSource); assertFalse(saved.scopeContent)
        assertEquals("aaa", ReplacePreviewConfig.sample(rule.id))
    }
    @Test fun recreationRestoresRawSelectionAndScrollWithoutOverwritingDraftFromRoom() {
        launch()
        scenario!!.onActivity {
            model.field(ReplaceEditorField.Name, ReplaceEditorText("Long name\n".repeat(100), 27, 4))
            model.focus(ReplaceEditorField.Name); model.scroll(500)
            runBlocking { model.flushDraft() }
        }
        scenario!!.recreate()
        scenario!!.onActivity { model = it.viewModel }
        compose.waitUntil { model.state.value.loaded }
        assertEquals("Long name\n".repeat(100), model.state.value.draft[ReplaceEditorField.Name].text)
        assertEquals(27, model.state.value.draft[ReplaceEditorField.Name].start); assertEquals(4, model.state.value.draft[ReplaceEditorField.Name].end)
        assertEquals("Book", model.state.value.draft[ReplaceEditorField.Scope].text)
        compose.waitForIdle(); assertEquals(500, model.state.value.scroll)
    }
    @Test fun actualBackShowsDirtyConfirmationAndNoDiscardsWithoutDatabaseWrite() {
        val rule = ReplaceRule(id = System.currentTimeMillis(), name = "Original", pattern = "a", isRegex = false, order = 9)
        launch(rule); compose.onNodeWithTag("replace-editor-Name").performTextReplacement("Draft")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("replace-editor-keep").performClick(); assertFalse(model.state.value.finished)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("replace-editor-discard").performClick()
        compose.waitUntil { model.state.value.finished }; assertFalse(model.state.value.saved)
        assertEquals("Original", runBlocking(Dispatchers.IO) { appDb.replaceRuleDao.findById(rule.id)!!.name })
    }
}
