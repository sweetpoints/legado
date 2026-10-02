package io.legado.app.ui.association

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.R
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ImportThemeDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualCodeSaveUpdatesStableThemeRowAndRecreationKeepsDraftSelectionWithoutApplyingOrImporting() {
        val theme = ThemeConfig.Config("Unconfirmed-${UUID.randomUUID()}", false, "#112233", "#223344", "#334455", "#445566", true, null, 15)
        val edited = theme.copy(themeName = "Edited-${UUID.randomUUID()}", backgroundImgBlur = 32)
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { ImportThemeDialog(GSON.toJson(theme), finishOnDismiss = true)
                .show(it.supportFragmentManager, "theme-import") }
            compose.waitUntil { compose.onAllNodesWithTag("theme-import-check-0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("theme-import-check-0").performClick().assertIsOff()
            compose.onNodeWithTag("theme-import-code-0").performClick()
            compose.waitUntil { compose.onAllNodesWithTag("code-body").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
            compose.onNodeWithTag("code-body").performTextReplacement(GSON.toJson(edited))
            compose.onNodeWithTag("code-save").performClick()
            compose.waitUntil { compose.onAllNodesWithText(edited.themeName).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("theme-import-check-0").assertIsOff()
            scenario.recreate()
            compose.waitUntil { compose.onAllNodesWithText(edited.themeName).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("theme-import-check-0").assertIsOff()
            scenario.onActivity { assertFalse(it.isFinishing) }
            assertNotImported(theme.themeName, edited.themeName)
            compose.onNodeWithTag("theme-import-cancel").performClick()
            compose.onNodeWithTag("theme-import-confirm").assertDoesNotExist()
            assertNotImported(theme.themeName, edited.themeName)
        }
    }
    private fun assertNotImported(vararg names: String) = runBlocking(Dispatchers.IO) {
        assertFalse(ThemeConfig.configList.any { it.themeName in names })
    }
}
