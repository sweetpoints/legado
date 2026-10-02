package io.legado.app.ui.config

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.repository.AppThemeListStore
import io.legado.app.data.repository.DefaultThemeListRepository
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.GSON
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ThemeListDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun config(name: String) = ThemeConfig.Config(name, false, "#123456", "#654321", "#FFFFFF", "#EEEEEE", false, null, 0)
    private fun preserveThemes(block: () -> Unit) {
        val file = File(ThemeConfig.configFilePath)
        val original = runBlocking(Dispatchers.IO) { ThemeConfig.snapshotConfigs() }
        val bytes = runBlocking(Dispatchers.IO) { if (file.exists()) file.readBytes() else null }
        try { block() } finally { runBlocking(Dispatchers.IO) { synchronized(ThemeConfig) {
            ThemeConfig.configList.clear(); ThemeConfig.configList.addAll(original)
            if (bytes == null) file.delete() else file.writeBytes(bytes)
            File(file.path + ".bak").delete(); File(file.path + ".new").delete()
        } } }
    }
    @Test fun realDeleteConfirmationSurvivesRotationAndDeletesOnlyCapturedTheme() = preserveThemes {
        val token = UUID.randomUUID().toString(); val first = config("First-$token"); val second = config("Second-$token")
        val repo = DefaultThemeListRepository(AppThemeListStore(context))
        val key = runBlocking(Dispatchers.IO) {
            ThemeConfig.addConfig(first); ThemeConfig.addConfig(second)
            repo.list().first { it.name == first.themeName }.key
        }
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { ThemeListDialog().show(it.supportFragmentManager, "themes") }
            compose.waitUntil { compose.onAllNodesWithTag("theme-list-delete-$key").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("theme-list-delete-$key").performClick(); scenario.recreate()
            compose.waitUntil { compose.onAllNodesWithTag("theme-list-delete-confirm").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("theme-list-delete-confirm").assertExists()
            compose.onNodeWithTag("theme-list-delete-cancel").performClick()
            assertTrue(runBlocking(Dispatchers.IO) { ThemeConfig.snapshotConfigs().any { it.themeName == first.themeName } })
            compose.onNodeWithTag("theme-list-delete-$key").performClick()
            compose.onNodeWithTag("theme-list-delete-confirm").performClick()
            compose.waitUntil { compose.onAllNodesWithTag("theme-list-delete-$key").fetchSemanticsNodes().isEmpty() }
            val remaining = runBlocking(Dispatchers.IO) { ThemeConfig.snapshotConfigs() }
            assertFalse(remaining.any { it.themeName == first.themeName }); assertTrue(remaining.any { it.themeName == second.themeName })
            compose.onNodeWithTag("theme-list-close").performClick()
            scenario.onActivity { it.supportFragmentManager.executePendingTransactions(); assertNull(it.supportFragmentManager.findFragmentByTag("themes")) }
        }
    }
    @Test fun realClipboardImportPreservesAllFieldsAndRefreshesNameReplacement() = preserveThemes {
        val token = UUID.randomUUID().toString(); val old = config("Imported-$token")
        val incoming = old.copy(isNightTheme = true, transparentNavBar = true, backgroundImgPath = "https://example.invalid/image-$token", backgroundImgBlur = 13)
        runBlocking(Dispatchers.IO) { ThemeConfig.addConfig(old) }
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            var originalClip: ClipData? = null
            scenario.onActivity {
                val clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                originalClip = clipboard.primaryClip; clipboard.setPrimaryClip(ClipData.newPlainText("theme", GSON.toJson(incoming)))
                ThemeListDialog().show(it.supportFragmentManager, "themes")
            }
            try {
                val repo = DefaultThemeListRepository(AppThemeListStore(context))
                compose.waitUntil { runCatching { compose.onNodeWithTag("theme-list-import").assertIsEnabled() }.isSuccess }
                compose.onNodeWithTag("theme-list-import").performClick()
                compose.waitUntil { runBlocking(Dispatchers.IO) { ThemeConfig.snapshotConfigs().any { it == incoming } } }
                val newKey = runBlocking(Dispatchers.IO) { repo.list().first { it.name == incoming.themeName }.key }
                compose.waitUntil { compose.onAllNodesWithTag("theme-list-apply-$newKey").fetchSemanticsNodes().isNotEmpty() }
                val rows = runBlocking(Dispatchers.IO) { ThemeConfig.snapshotConfigs().filter { it.themeName == incoming.themeName } }
                assertEquals(listOf(incoming), rows)
                assertEquals(newKey, runBlocking(Dispatchers.IO) { repo.list().first { it.name == incoming.themeName }.key })
            } finally { scenario.onActivity {
                val clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                originalClip?.let(clipboard::setPrimaryClip) ?: clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            } }
        }
    }
}
