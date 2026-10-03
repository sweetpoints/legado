package io.legado.app.ui.widget.keyboard

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.repository.KeyboardAssistSettingsText
import io.legado.app.ui.about.AboutActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class KeyboardAssistSettingsHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val original =
        KeyboardAssist(1, "Fixture-${UUID.randomUUID()}", "Original value", 62000)
    private val keys = mutableListOf<Pair<Int, String>>()
    private val sessions = mutableListOf<String>()
    private lateinit var scenario: ActivityScenario<AboutActivity>
    private lateinit var host: KeyboardAssistsConfig

    @Before
    fun setup() {
        runBlocking(Dispatchers.IO) { appDb.keyboardAssistsDao.insert(original) }
        keys += original.type to original.key
        scenario = ActivityScenario.launch(AboutActivity::class.java)
        scenario.onActivity {
            host = KeyboardAssistsConfig()
            host.show(it.supportFragmentManager, "keyboard-settings")
        }
        compose.onNodeWithTag("keyboard-settings-lines").assertExists()
        compose.waitUntil { host.model.state.value.loaded }
    }

    @After
    fun cleanup() {
        scenario.close()
        runBlocking(Dispatchers.IO) {
            appDb.keyboardAssistsDao.all
                .filter { (it.type to it.key) in keys }
                .forEach { appDb.keyboardAssistsDao.delete(it) }
        }
        sessions.forEach { File(context.filesDir, "keyboard-assist-settings/$it.json").delete() }
    }

    private fun openOriginal() {
        scenario.onActivity {
            host.model.openEditor(
                host.model.state.value.rows.single { it.key == original.key && it.type == 1 }.id
            )
        }
        compose.waitUntil { host.model.state.value.editor != null }
        sessions += host.model.editorSession!!
    }

    @Test
    fun actualEditorRenamesTypeOneToZeroAndPreservesSerialAndWhitespace() {
        openOriginal()
        val renamed = "Renamed-${UUID.randomUUID()}"
        keys += 0 to renamed
        compose.onNodeWithTag("keyboard-settings-editor-key").performTextReplacement(renamed)
        compose
            .onNodeWithTag("keyboard-settings-editor-value")
            .performTextReplacement(" Raw value ")
        compose.onNodeWithTag("keyboard-settings-editor-save").performClick()
        compose.waitUntil { host.model.state.value.editor == null }
        val actual =
            runBlocking(Dispatchers.IO) {
                appDb.keyboardAssistsDao.all.single { it.type == 0 && it.key == renamed }
            }
        assertEquals(" Raw value ", actual.value)
        assertEquals(62000, actual.serialNo)
        assertFalse(
            runBlocking(Dispatchers.IO) {
                appDb.keyboardAssistsDao.all.any { it.type == 1 && it.key == original.key }
            }
        )
    }

    @Test
    fun recreationRestoresDiskEditorAndSelectionAndCancelDoesNotChangeRoom() {
        openOriginal()
        val large = "Value".repeat(40000)
        scenario.onActivity {
            host.model.editorText(false, KeyboardAssistSettingsText(large, 27, 4))
            runBlocking { host.model.flushDraft() }
        }
        scenario.recreate()
        scenario.onActivity {
            host =
                it.supportFragmentManager.findFragmentByTag("keyboard-settings")
                    as KeyboardAssistsConfig
        }
        compose.onNodeWithTag("keyboard-settings-editor-value").assertExists()
        compose.waitUntil { host.model.state.value.editor != null }
        val draft = host.model.state.value.editor!!
        assertEquals(large, draft.value.text)
        assertEquals(27, draft.value.start)
        assertEquals(4, draft.value.end)
        compose.onNodeWithTag("keyboard-settings-editor-cancel").performClick()
        compose.waitUntil { host.model.state.value.editor == null }
        assertEquals(
            original.value,
            runBlocking(Dispatchers.IO) {
                appDb.keyboardAssistsDao.all.single { it.type == 1 && it.key == original.key }.value
            },
        )
    }

    @Test
    fun staleCodeCallbackCannotOverwriteNewSessionAndRepeatedSameCodeIsIdempotent() {
        openOriginal()
        val stale = host.model.editorSession!!
        scenario.onActivity { host.model.cancelEditor() }
        compose.waitUntil { host.model.state.value.editor == null }
        openOriginal()
        val current = host.model.editorSession!!
        assertNotEquals(stale, current)
        scenario.onActivity { host.onCodeSave("Stale", "$stale:value") }
        assertEquals(original.value, host.model.state.value.editor!!.value.text)
        scenario.onActivity { host.onCodeSave("Updated", "$current:value") }
        val revision = host.model.state.value.editor!!.revision
        scenario.onActivity { host.onCodeSave("Updated", "$current:value") }
        assertEquals("Updated", host.model.state.value.editor!!.value.text)
        assertEquals(revision, host.model.state.value.editor!!.revision)
    }
}
