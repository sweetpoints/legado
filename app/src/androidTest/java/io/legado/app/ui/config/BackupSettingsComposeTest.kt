package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.model.backup.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class BackupSettingsComposeTest {
    @get:Rule val compose = createComposeRule()
    private fun initial() = BackupSettingsState(loading = false, settings = BackupSettingsSnapshot(defaultPath = "/test/default"))
    private fun editor() = BackupEditorActions({ _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {})
    private fun tasks() = BackupTaskActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    private fun row(key: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("backup-settings-list").performScrollToNode(hasTestTag("backup-row-$key"))
        return compose.onNodeWithTag("backup-row-$key")
    }
    @Test fun progressDependencyRetainsPlusChoiceAndEverySettingHasAnAccessibleTouchTarget() {
        var state by mutableStateOf(initial()); val actions = editor().copy(boolean = { key, value ->
            state = state.copy(settings = state.settings!!.copy(switches = state.settings!!.switches + (key to value))) })
        compose.setContent { LegadoComposeTheme { BackupSettingsScreen(state, BackupRuntimeState(), actions, tasks()) } }
        row(BackupSettingSwitch.ProgressPlus.key).performClick().assertIsOn()
        row(BackupSettingSwitch.Progress.key).performClick().assertIsOff()
        row(BackupSettingSwitch.ProgressPlus.key).assertIsNotEnabled().assertIsOn()
        row(BackupSettingSwitch.Progress.key).performClick()
        row(BackupSettingSwitch.ProgressPlus.key).assertIsEnabled().assertIsOn().assertHeightIsAtLeast(48.dp)
        assertFalse(state.settings!!.switches.getValue(BackupSettingSwitch.BookRestore))
    }
    @Test fun defaultPathSummaryAndLocalPasswordOrderArePreservedAndRestoreLongPressIsLocal() {
        var cloud = 0; var local = 0
        compose.setContent { LegadoComposeTheme { BackupSettingsScreen(initial(), BackupRuntimeState(), editor(), tasks().copy(restore = { cloud++ }, localRestore = { local++ })) } }
        row("backupUri").assertTextContains("/test/default", substring = true)
        val password = compose.onNodeWithTag("backup-row-localPassword").fetchSemanticsNode().boundsInRoot
        val path = compose.onNodeWithTag("backup-row-backupUri").fetchSemanticsNode().boundsInRoot
        assertTrue(password.top < path.top)
        row("web_dav_restore").performTouchInput { longClick() }; assertEquals(1, local); assertEquals(0, cloud)
        row("web_dav_restore").performClick(); assertEquals(1, cloud)
    }
    @Test fun automaticDialogEditsOnlyDraftAndInvalidDaysRemainVisible() {
        var state by mutableStateOf(initial().copy(draft = BackupSettingsDraft(form = BackupForm.Automatic)))
        var confirmed = 0; var canceled = 0
        val actions = editor().copy(automaticEnabled = { state = state.copy(draft = state.draft!!.copy(autoEnabled = it)) },
            automaticWebDav = { state = state.copy(draft = state.draft!!.copy(autoWebDav = it)) }, interval = { state = state.copy(draft = state.draft!!.copy(intervalText = it)) },
            confirm = { confirmed++; state = state.copy(invalidInterval = true) }, dismiss = { canceled++ })
        compose.setContent { LegadoComposeTheme { BackupSettingsScreen(state, BackupRuntimeState(), actions, tasks()) } }
        compose.onNodeWithTag("backup-auto-local").performClick(); compose.onNodeWithTag("backup-auto-enabled").performClick()
        compose.onNodeWithTag("backup-auto-days").performTextReplacement("0"); compose.onNodeWithTag("backup-form-ok").performClick()
        compose.onNodeWithTag("backup-auto-days").assertIsDisplayed(); assertEquals(1, confirmed)
        assertFalse(state.draft!!.autoEnabled); assertFalse(state.draft!!.autoWebDav); assertEquals(AutoBackupSettings(), state.settings!!.automatic)
        compose.onNodeWithTag("backup-form-cancel").performClick(); assertEquals(1, canceled)
    }
    @Test fun privateTextAndChoiceEditorsDispatchExactPayloadWithoutMutatingSettingsSnapshot() {
        var state by mutableStateOf(initial().copy(draft = BackupSettingsDraft(form = BackupForm.Password)))
        val texts = mutableListOf<String>(); val chosen = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { LegadoComposeTheme { BackupSettingsScreen(state, BackupRuntimeState(), editor().copy(text = { texts += it; state = state.copy(draft = state.draft!!.copy(text = it)) },
            choice = { key, value -> chosen += key to value }), tasks()) } }
        compose.onNodeWithTag("backup-text").performTextReplacement("synthetic-password"); assertEquals(listOf("synthetic-password"), texts)
        assertEquals("", state.settings!!.texts.getValue(BackupSettingText.Password))
        compose.runOnIdle { state = state.copy(draft = state.draft!!.copy(form = BackupForm.Content), choices = listOf(BackupChoice("cookies", "Cookies", false))) }
        compose.onNodeWithTag("backup-choice-cookies").performClick(); assertEquals(listOf("cookies" to true), chosen)
    }
    @Test fun destinationAndStableRestoreNamesCallTheCorrectActionAndBusyTaskCanBeCancelled() {
        var runtime by mutableStateOf(BackupRuntimeState(popup = BackupPopup.Destination)); val uploads = mutableListOf<Boolean>(); val names = mutableListOf<String>(); var canceled = 0
        compose.setContent { LegadoComposeTheme { BackupSettingsScreen(initial(), runtime, editor(), tasks().copy(destination = { uploads += it }, selectRestore = { names += it }, cancel = { canceled++ })) } }
        compose.onNodeWithTag("backup-destination-local").performClick(); compose.onNodeWithTag("backup-destination-webdav").performClick(); assertEquals(listOf(false, true), uploads)
        compose.runOnIdle { runtime = BackupRuntimeState(popup = BackupPopup.RestoreFiles, names = listOf("backup-2026", "backup-2025")) }
        compose.onNodeWithTag("backup-restore-name-backup-2025").performClick(); assertEquals(listOf("backup-2025"), names)
        compose.runOnIdle { runtime = BackupRuntimeState(busy = true, waiting = BackupWait.Restore) }
        compose.onNodeWithTag("backup-task-stop").performClick(); assertEquals(1, canceled)
    }
    @Test fun searchPositionsExactAutomaticSettingWithoutOpeningItAndFailureOffersExplicitRetry() {
        val title = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.auto_backup_t)
        var query by mutableStateOf<String?>(title); var state by mutableStateOf(initial()); var opened = 0; var retried = 0
        compose.setContent { LegadoComposeTheme { BackupSettingsScreen(state, BackupRuntimeState(), editor().copy(form = { opened++ }, retry = { retried++ }), tasks(),
            search = query, searchFinished = { query = null }) } }
        compose.onNodeWithTag("backup-search-autoBackup").performScrollTo().performClick()
        compose.onNodeWithTag("backup-row-autoBackup").assertIsDisplayed(); assertEquals(0, opened)
        compose.runOnIdle { state = state.copy(failed = true, error = "read failed") }
        row("autoBackup").assertIsNotEnabled(); compose.onNodeWithTag("backup-retry").performScrollTo().performClick(); assertEquals(1, retried)
    }
    @Test fun failedAcceptedFormKeepsRetryReachableWithoutAllowingDuplicateConfirmation() {
        var retries = 0
        val state = initial().copy(draft = BackupSettingsDraft(form = BackupForm.Password, text = "synthetic"), pendingCommit = true, error = "private receipt failed")
        compose.setContent { LegadoComposeTheme { BackupSettingsScreen(state, BackupRuntimeState(), editor().copy(retry = { retries++ }), tasks()) } }
        compose.onNodeWithTag("backup-form-ok").assertIsNotEnabled(); compose.onNodeWithTag("backup-form-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("backup-form-retry").performScrollTo().assertIsEnabled().performClick(); assertEquals(1, retries)
    }

}
