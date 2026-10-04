package io.legado.app.ui.main.my

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.service.McpService
import io.legado.app.service.WebService
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Compose destination, persisted settings and More intent forwarding. */
@RunWith(AndroidJUnit4::class)
class MyPageCustomizationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = prefs.all
    private val savedLocal = LocalConfig.all
    private var scenario: ActivityScenario<MainActivity>? = null
    private var moreActivity: MyMoreActivity? = null

    @Before
    fun setUp() {
        prefs
            .edit()
            .remove(PreferKey.myMoreItems)
            .putBoolean(PreferKey.autoRefresh, false)
            .putBoolean(PreferKey.autoCheckNewBackup, false)
            .putBoolean("autoUpdateVariant", false)
            .putString(PreferKey.defaultHomePage, "my")
            .commit()
        LocalConfig.edit()
            .putBoolean("privacyPolicyOk", true)
            .putLong("appVersionCode", appInfo.versionCode)
            .putString("password", "")
            .commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        setting("autoTaskManage")
    }

    @After
    fun cleanUp() {
        instrumentation.runOnMainSync { moreActivity?.finish() }
        scenario?.close()
        prefs
            .edit()
            .clear()
            .apply { savedPrefs.forEach { (key, value) -> putValue(key, value) } }
            .commit()
        LocalConfig.edit()
            .clear()
            .apply { savedLocal.forEach { (key, value) -> putValue(key, value) } }
            .commit()
    }

    @Test
    fun actualPickerMovesOnlySelectedItemsAndMoreKeepsExistingActions() {
        val webRunning = WebService.isRun
        val mcpRunning = McpService.isRun
        val scheduled = prefs.getBoolean(PreferKey.autoTaskService, false)
        openPicker()
        choose("autoTaskManage")
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        setting("autoTaskManage")
        assertEquals(
            defaultMyMoreItems,
            prefs.getStringSet(PreferKey.myMoreItems, defaultMyMoreItems),
        )

        openPicker()
        for (key in
            listOf(
                "autoTaskManage",
                PreferKey.autoTaskService,
                PreferKey.webService,
                PreferKey.mcpService,
                "txtTocRuleManage",
                "replaceManage",
                "dictRuleManage",
                "check_update",
            )) choose(key)
        screenshot("my-customization-picker")
        scenario!!.recreate()
        compose
            .onNodeWithTag("my-customization-list")
            .performScrollToNode(hasTestTag("my-option-autoTaskManage"))
        compose.onNodeWithTag("my-option-autoTaskManage").assertIsOn()
        compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        val selected =
            setOf(
                "autoTaskManage",
                PreferKey.autoTaskService,
                PreferKey.webService,
                PreferKey.mcpService,
                "txtTocRuleManage",
                "replaceManage",
                "dictRuleManage",
                "check_beta_update",
            )
        assertEquals(selected, prefs.getStringSet(PreferKey.myMoreItems, emptySet()))
        compose.onNodeWithTag("my-setting-autoTaskManage").assertDoesNotExist()
        setting("check_update")
        assertEquals(webRunning, WebService.isRun)
        assertEquals(mcpRunning, McpService.isRun)
        assertEquals(scheduled, prefs.getBoolean(PreferKey.autoTaskService, false))
        screenshot("my-customization-main")
        val monitor = instrumentation.addMonitor(MyMoreActivity::class.java.name, null, false)
        try {
            setting("myMore").performClick()
            moreActivity =
                instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? MyMoreActivity
            assertNotNull(moreActivity)
            selected.forEach { setting(it) }
            compose.onNodeWithTag("my-more-setting-check_update").assertDoesNotExist()
            compose.onNodeWithTag("my-more-setting-bookSourceManage").assertDoesNotExist()
            screenshot("my-customization-more")
            instrumentation.runOnMainSync {
                assertEquals(context.getString(R.string.reader_menu_more), moreActivity!!.title)
                moreActivity!!.finish()
            }
            moreActivity = null
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        setting("check_update")
        scenario!!.recreate()
        setting("check_update")
        compose.onNodeWithTag("my-setting-autoTaskManage").assertDoesNotExist()
    }

    @Test
    fun realConfigRestoreAndLegacyBackupRestoreTheVisiblePage() {
        val directory =
            File(context.cacheDir, "my-page-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val selected = setOf("autoTaskManage", PreferKey.mcpService)
        try {
            writePreferenceSnapshot(context, directory.absolutePath, "config") {
                putStringSet(PreferKey.myMoreItems, selected)
            }
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath) }
            setting("check_beta_update")
            compose.onNodeWithTag("my-setting-autoTaskManage").assertDoesNotExist()
            assertEquals(selected, prefs.getStringSet(PreferKey.myMoreItems, emptySet()))
            writePreferenceSnapshot(context, directory.absolutePath, "config") {
                putBoolean("enableReadRecord", true)
            }
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath) }
            setting("autoTaskManage")
            compose.onNodeWithTag("my-setting-check_beta_update").assertDoesNotExist()
            scenario!!.recreate()
            setting("autoTaskManage")
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun setting(key: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val prefix = if (moreActivity != null) "my-more" else "my"
        awaitSettingsOwner(prefix)
        compose
            .onNodeWithTag("$prefix-settings-list")
            .performScrollToNode(hasTestTag("$prefix-setting-$key"))
        return compose.onNodeWithTag("$prefix-setting-$key").assertExists()
    }

    private fun awaitSettingsOwner(prefix: String) {
        // finish() and recreate() complete outside Compose's scheduler. Do not query the
        // disappearing child hierarchy before the real destination regains its window.
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            var ownerReady = false
            fun inspect(activity: android.app.Activity) {
                val decor = activity.window.decorView
                ownerReady = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).any { it === activity } &&
                    decor.hasWindowFocus() && decor.hasAttachedComposition()
            }
            if (prefix == "my-more") {
                instrumentation.runOnMainSync { moreActivity?.let(::inspect) }
            } else {
                scenario!!.onActivity(::inspect)
            }
            ownerReady && compose.onAllNodesWithTag("$prefix-settings-list")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
    }

    private fun View.hasAttachedComposition(): Boolean {
        if (this is AbstractComposeView && isAttachedToWindow && hasComposition) return true
        return this is ViewGroup && (0 until childCount).any { getChildAt(it).hasAttachedComposition() }
    }

    private fun openPicker() {
        compose
            .onNodeWithContentDescription(context.getString(R.string.customize_my))
            .performClick()
    }

    private fun choose(key: String) {
        compose
            .onNodeWithTag("my-customization-list")
            .performScrollToNode(hasTestTag("my-option-$key"))
        compose.onNodeWithTag("my-option-$key").performClick()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
            }
        }
    }
}
