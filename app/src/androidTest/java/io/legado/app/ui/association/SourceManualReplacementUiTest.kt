package io.legado.app.ui.association

import android.app.ActivityManager
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.EffectiveReplacementViewModel
import io.legado.app.ui.book.read.EffectiveReplacesDialog
import io.legado.app.ui.book.read.ManualReplaceRulesDialog
import io.legado.app.ui.book.read.ManualReplacementViewModel
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.CodeDialogAction
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceManualReplacementUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val keys =
        listOf(
            PreferKey.manualReplaceRule,
            PreferKey.importReplaceSource,
            PreferKey.autoBackup,
            PreferKey.importRememberGroup,
        )
    private val savedPrefs = keys.associateWith { prefs.all[it] }
    private val savedRules = appDb.replaceRuleDao.all
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLastBackup = LocalConfig.lastBackup
    private val id = UUID.randomUUID().toString()
    private val files = arrayListOf<File>()
    private val urls = (0..1).map { "https://manual-$id.invalid/$it" }
    private val rules =
        listOf(
            ReplaceRule(
                id = 9123601,
                name = "Mixed source and body",
                pattern = "Seed",
                replacement = "Seed+",
                scopeSource = true,
                scopeTitle = true,
                scopeContent = true,
                isRegex = false,
                order = 1,
            ),
            ReplaceRule(
                id = 9123602,
                name = "Ordered scoped source",
                pattern = "+",
                replacement = "++",
                scope = "Seed0",
                scopeSource = true,
                scopeContent = false,
                isRegex = false,
                order = 2,
            ),
            ReplaceRule(
                id = 9123603,
                name = "Excluded source",
                pattern = "Seed0",
                replacement = "Wrong",
                excludeScope = urls[0],
                scopeSource = true,
                scopeContent = false,
                isRegex = false,
                order = 3,
            ),
            ReplaceRule(
                id = 9123604,
                name = "No matching text",
                pattern = "Absent",
                replacement = "Wrong",
                scopeSource = true,
                scopeContent = false,
                isRegex = false,
                order = 4,
            ),
            ReplaceRule(
                id = 9123605,
                name = "Disabled source",
                pattern = "Seed",
                replacement = "Wrong",
                scopeSource = true,
                scopeContent = false,
                isEnabled = false,
                isRegex = false,
                order = 5,
            ),
            ReplaceRule(
                id = 9123606,
                name = "Body only",
                pattern = "Seed",
                replacement = "Body",
                scopeSource = false,
                scopeContent = true,
                isRegex = false,
                order = 6,
            ),
        )

    @Before
    fun setup() {
        prefs
            .edit()
            .remove(PreferKey.manualReplaceRule)
            .putBoolean(PreferKey.importReplaceSource, true)
            .putBoolean(PreferKey.autoBackup, false)
            .putBoolean(PreferKey.importRememberGroup, false)
            .commit()
        savedRules.forEach { appDb.replaceRuleDao.insert(it.copy(isEnabled = false)) }
        appDb.replaceRuleDao.insert(*rules.toTypedArray())
    }

    @After
    fun cleanup() {
        appDb.replaceRuleDao.delete(*rules.toTypedArray())
        appDb.replaceRuleDao.insert(*savedRules.toTypedArray())
        urls.forEach {
            appDb.bookSourceDao.delete(it)
            appDb.rssSourceDao.delete(it)
        }
        files.forEach { it.delete() }
        prefs
            .edit()
            .apply {
                savedPrefs.forEach { (key, value) ->
                    if (value is Boolean) putBoolean(key, value) else remove(key)
                }
            }
            .commit()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        LocalConfig.lastBackup = savedLastBackup
    }

    @Test fun bookManualAndEffectiveMenusUseRawCandidates() = manualFlow(false)

    @Test fun rssManualAndEffectiveMenusUseRawCandidates() = manualFlow(true)

    @Test
    fun replacementMenusResumeAfterRecreationOrBackgroundWhileComparisonIsPending() {
        AppConfig.importReplaceSource = false
        for (rss in listOf(false, true)) for (manual in listOf(false, true)) for (recreate in
            listOf(false, true)) {
            withImport(rss) { host ->
                val code = host.open(0)
                originalPreview()
                compose
                    .onNodeWithTag("code-body")
                    .performTextReplacement(GSON.toJson(source(rss, 0, "Edited Seed0")))
                withBlockedSourceRules { entered, release ->
                    host.menu(
                        if (manual) R.id.menu_manual_replace_rule else R.id.menu_effective_replaces,
                        code,
                        waitForIdleAfterClick = false,
                    )
                    await("The real source-rule query must be held") { entered.count == 0L }
                    val model = main { if (rss) host.feed else host.book }
                    assertTrue(
                        "The query must still be held: rss=$rss manual=$manual recreate=$recreate",
                        main {
                            if (rss)
                                (host.feed.state.value.busy || host.feed.state.value.pendingRefresh)
                            else
                                (host.book.state.value.busy || host.book.state.value.pendingRefresh)
                        },
                    )
                    val activity = main { host.parent.requireActivity() }
                    // ActivityScenario waits for an idle main thread before dispatching these
                    // transitions, which cannot happen while this held query animates progress.
                    if (recreate) {
                        main { activity.recreate() }
                        await("The system must recreate the preview while its query is held") {
                            main {
                                ActivityLifecycleMonitorRegistry.getInstance()
                                    .getActivitiesInStage(Stage.RESUMED)
                                    .filterIsInstance<FileAssociationActivity>()
                                    .find { it !== activity }
                                    ?.supportFragmentManager
                                    ?.fragments
                                    ?.filterIsInstance<DialogFragment>()
                                    ?.find {
                                        if (rss) it is ImportRssSourceDialog
                                        else it is ImportBookSourceDialog
                                    }
                                    ?.let {
                                        host.parent = it
                                        it.isResumed && it.view != null
                                    } == true
                            }
                        }
                        assertSame(model, main { if (rss) host.feed else host.book })
                    } else {
                        main { assertTrue(activity.moveTaskToBack(true)) }
                        await("The system must stop the preview while its query is held") {
                            main {
                                ActivityLifecycleMonitorRegistry.getInstance()
                                    .getLifecycleStageOf(activity) == Stage.STOPPED
                            }
                        }
                    }
                    assertTrue(
                        "Lifecycle transition must precede query release: rss=$rss manual=$manual recreate=$recreate",
                        main {
                            if (rss)
                                (host.feed.state.value.busy || host.feed.state.value.pendingRefresh)
                            else
                                (host.book.state.value.busy || host.book.state.value.pendingRefresh)
                        },
                    )
                    release.countDown()
                    await("The retained comparison must finish") {
                        main {
                            if (rss)
                                (!host.feed.state.value.busy &&
                                    !host.feed.state.value.pendingRefresh)
                            else
                                (!host.book.state.value.busy &&
                                    !host.book.state.value.pendingRefresh)
                        }
                    }
                    main {
                        assertNull(
                            if (rss) host.feed.state.value.error else host.book.state.value.error
                        )
                    }
                    if (!recreate) {
                        main {
                            activity
                                .getSystemService(ActivityManager::class.java)
                                .appTasks
                                .single { it.taskInfo?.taskId == activity.taskId }
                                .moveToFront()
                        }
                        await("The application task must return to the foreground") {
                            main {
                                ActivityLifecycleMonitorRegistry.getInstance()
                                    .getLifecycleStageOf(activity) == Stage.RESUMED
                            }
                        }
                    }
                    val menu: DialogFragment =
                        if (manual) host.child<ManualReplaceRulesDialog>()
                        else host.child<EffectiveReplacesDialog>()
                    main {
                        // Replacement is unchecked, so the restored effective list must remain
                        // empty.
                        assertEquals(
                            "rss=$rss manual=$manual recreate=$recreate",
                            if (manual) rules.take(4).map { it.id } else emptyList<Long>(),
                            ruleIds(menu),
                        )
                        val restored =
                            host.parent.childFragmentManager.fragments
                                .filterIsInstance<CodeDialog>()
                                .single()
                        assertTrue(restored.currentOriginalCode().contains("Edited Seed0"))
                        assertNull(
                            if (rss)
                                host.feed.state.value.effects.firstOrNull {
                                    it.action == RssImportAction.Manual ||
                                        it.action == RssImportAction.Effective
                                }
                            else
                                host.book.state.value.effects.firstOrNull {
                                    it.action == BookImportAction.Manual ||
                                        it.action == BookImportAction.Effective
                                }
                        )
                    }
                    host.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    host.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    main {
                        assertEquals(
                            1,
                            host.parent.childFragmentManager.fragments.count {
                                it.javaClass == menu.javaClass
                            },
                        )
                    }
                    val frame = java.util.concurrent.CountDownLatch(1)
                    main {
                        val decor = checkNotNull(menu.dialog?.window).decorView
                        assertTrue("Restored rule dialog must be visible", decor.isShown)
                        decor.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                        decor.invalidate()
                    }
                    assertTrue(
                        "Restored rule dialog must render",
                        frame.await(15, java.util.concurrent.TimeUnit.SECONDS),
                    )
                    screenshot(
                        "source-rule-pending-$rss-$manual-$recreate",
                        main { checkNotNull(menu.dialog?.window) },
                    )
                }
            }
        }
    }

    private fun withBlockedSourceRules(
        fail: Boolean = false,
        action: (java.util.concurrent.CountDownLatch, java.util.concurrent.CountDownLatch) -> Unit,
    ) {
        val delegate = appDb.replaceRuleDao
        val field =
            appDb.javaClass.declaredFields.single {
                it.name.contains("replaceRuleDao", ignoreCase = true)
            }
        field.isAccessible = true
        val original = field.get(appDb)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val proxy =
            java.lang.reflect.Proxy.newProxyInstance(
                io.legado.app.data.dao.ReplaceRuleDao::class.java.classLoader,
                arrayOf(io.legado.app.data.dao.ReplaceRuleDao::class.java),
            ) { _, method, args ->
                if (method.name == "findEnabledBySourceScope") {
                    entered.countDown()
                    check(release.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                        "Source-rule comparison was not released"
                    }
                    check(!fail) { "Injected source-rule read failure" }
                }
                try {
                    method.invoke(delegate, *(args ?: emptyArray()))
                } catch (error: java.lang.reflect.InvocationTargetException) {
                    throw error.targetException
                }
            }
        try {
            // Gate the generated DAO only in this test; every query still runs on the real
            // database.
            field.set(appDb, if (original is Lazy<*>) lazyOf(proxy) else proxy)
            action(entered, release)
        } finally {
            release.countDown()
            field.set(appDb, original)
        }
    }

    private fun originalPreview() {
        if (compose.onAllNodesWithTag("code-preview-toggle").fetchSemanticsNodes().isEmpty()) return
        val checkbox = compose.onNodeWithTag("code-preview-toggle")
        if (
            checkbox
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] ==
                androidx.compose.ui.state.ToggleableState.On
        )
            checkbox.performClick()
    }

    private fun manualFlow(rss: Boolean) {
        AppConfig.importReplaceSource = false
        withImport(rss) { host ->
            host.names("Seed0", "Seed1")
            host.query("Seed0")
            main { if (rss) host.feed.select("1", false) else host.book.select("1", false) }
            host.menu(R.id.menu_manual_replace_rule)
            var manual = host.child<ManualReplaceRulesDialog>()
            assertEquals(rules.take(4).map { it.id }, main { ruleIds(manual) })
            compose.onNodeWithTag("manual-all").performClick()
            host.scenario.recreate()
            host.findParent()
            manual = host.child()
            compose.onNodeWithTag("manual-confirm").performClick()
            host.ready()
            host.names("Seed++0", "Seed+1") // Hidden, unchecked candidate is also replaced.
            main {
                assertEquals(
                    listOf(true, false),
                    if (rss)
                        host.feed.state.value.items.map { it.key in host.feed.state.value.selected }
                    else
                        host.book.state.value.items.map {
                            it.key in host.book.state.value.selected
                        },
                )
                assertEquals(
                    "Seed0",
                    if (rss) host.feed.state.value.query else host.book.state.value.query,
                )
            }
            host.query("")
            host.menu(R.id.menu_effective_replaces)
            val effective = host.child<EffectiveReplacesDialog>()
            main {
                assertEquals(rules.take(2).map { it.id }, ruleIds(effective))
                effective.dismiss()
            }
            host.ready()
            var code = host.open(0)
            originalPreview()
            compose
                .onNodeWithTag("code-body")
                .performTextReplacement(GSON.toJson(source(rss, 0, "Edited Seed0")))
            host.menu(R.id.menu_manual_replace_rule, code)
            manual = host.child()
            // Clear inherited global selection, then choose the mixed-scope rule only.
            compose.onNodeWithTag("manual-all").performClick()
            clickRule(manual, 0)
            compose.onNodeWithTag("manual-confirm").performClick()
            host.ready(code)
            host.names("Edited Seed+0", "Seed+1")
            host.menu(R.id.menu_effective_replaces, code)
            val single = host.child<EffectiveReplacesDialog>()
            main {
                assertEquals(listOf(rules[0].id), ruleIds(single))
                single.dismiss()
            }
            host.ready(code)
            // Reopening and confirming must not apply the non-idempotent rule twice.
            host.menu(R.id.menu_manual_replace_rule, code)
            manual = host.child()
            compose.onNodeWithTag("manual-confirm").performClick()
            host.ready(code)
            host.names("Edited Seed+0", "Seed+1")
            host.scenario.recreate()
            host.findParent()
            code = host.child()
            host.ready(code)
            main { assertTrue(code.currentOriginalCode().contains("Edited Seed0")) }
            originalPreview()
            compose.onNodeWithTag("code-fullscreen").performClick()
            var editor: CodeEditor? = null
            await("Manual source editor missing") {
                main {
                    editor =
                        ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<CodeEditActivity>()
                            .firstOrNull()
                            ?.findViewById(R.id.editText)
                    editor?.text?.toString()?.contains("Edited Seed0") == true &&
                        editor?.isEditable == true
                }
            }
            main {
                val text = checkNotNull(editor).text
                val index = text.toString().indexOf("Edited Seed0")
                text.replace(index, index + "Edited".length, "Editor")
            }
            compose.onNodeWithTag("code-save").performClick()
            // The editor result updates its draft before the RESUMED route delivers EditorSaved.
            // Wait for that callback and the parent's refresh to finish before inspecting candidates.
            await("Native editor result delivered and candidate refresh completed") {
                main {
                    val state = code.model.state.value
                    state.original.contains("Editor Seed0") && !state.busy &&
                        state.effects.none { it.action == CodeDialogAction.EditorSaved } &&
                        (if (rss) host.feed.state.value.interactive else host.book.state.value.interactive)
                }
            }
            host.ready(code)
            host.names("Editor Seed+0", "Seed+1")
            main { assertTrue(code.currentOriginalCode().contains("Editor Seed0")) }
            originalPreview()
            compose.onNodeWithTag("code-body").performTextReplacement("{ invalid draft")
            host.menu(R.id.menu_manual_replace_rule, code)
            main {
                assertEquals("{ invalid draft", code.currentOriginalCode())
                assertTrue(
                    host.parent.childFragmentManager.fragments.none {
                        it is ManualReplaceRulesDialog
                    }
                )
            }
            screenshot("source-manual-invalid-$rss")
            main { code.dismiss() }
            host.ready()
            host.names("Editor Seed+0", "Seed+1")
            host.click(ImportControl.Confirm)
            await("Import not persisted") {
                val stored =
                    if (rss) appDb.rssSourceDao.getByKey(urls[0])?.sourceName
                    else appDb.bookSourceDao.getBookSource(urls[0])?.bookSourceName
                stored == "Editor Seed+0"
            }
            assertNull(
                if (rss) appDb.rssSourceDao.getByKey(urls[1])
                else appDb.bookSourceDao.getBookSource(urls[1])
            )
        }
    }

    @Test
    fun automaticSourceOptionControlsManualMenus() {
        for (rss in listOf(false, true)) withImport(rss) { host ->
            host.names("Seed++0", "Seed+1")
            host.manualEnabled(false)
            var code = host.open(0)
            host.manualEnabled(false, code)
            main { code.dismiss() }
            host.ready()
            host.menu(R.id.menu_replace_source)
            host.ready()
            host.names("Seed0", "Seed1")
            host.manualEnabled(true)
            code = host.open(0)
            host.manualEnabled(true, code)
            main { code.dismiss() }
            host.ready()

            host.menu(R.id.menu_manual_replace_rule)
            val manual = host.child<ManualReplaceRulesDialog>()
            clickRule(manual, 0)
            compose.onNodeWithTag("manual-confirm").performClick()
            host.ready()
            host.names("Seed+0", "Seed+1")
            assertFalse(AppConfig.importReplaceSource)
            host.menu(R.id.menu_effective_replaces)
            val effective = host.child<EffectiveReplacesDialog>()
            main {
                assertEquals(listOf(rules[0].id), ruleIds(effective))
                effective.dismiss()
            }
            host.ready()

            // Switching modes rebuilds from the raw source and retains the manual selection.
            host.menu(R.id.menu_replace_source)
            host.ready()
            host.names("Seed++0", "Seed+1")
            host.manualEnabled(false)
            code = host.open(0)
            host.manualEnabled(false, code)
            main { code.dismiss() }
            host.ready()
            host.menu(R.id.menu_replace_source)
            host.ready()
            host.names("Seed+0", "Seed+1")
            host.manualEnabled(true)
            assertFalse(AppConfig.manualReplaceRule)
            host.click(ImportControl.Cancel)
            AppConfig.importReplaceSource = true
        }
    }

    @Test
    fun failedModeSwitchKeepsManualResultAndPreferences() {
        AppConfig.importReplaceSource = false
        for (rss in listOf(false, true)) withImport(rss) { host ->
            host.menu(R.id.menu_manual_replace_rule)
            val manual = host.child<ManualReplaceRulesDialog>()
            clickRule(manual, 0)
            compose.onNodeWithTag("manual-confirm").performClick()
            host.ready()
            host.names("Seed+0", "Seed+1")
            withBlockedSourceRules(fail = true) { entered, release ->
                host.menu(R.id.menu_replace_source, waitForIdleAfterClick = false)
                await("The real source-rule query must be held") { entered.count == 0L }
                release.countDown()
                host.ready()
                host.names("Seed+0", "Seed+1")
                assertFalse(AppConfig.importReplaceSource)
                host.manualEnabled(true)
                assertTrue(
                    main {
                        (if (rss) host.feed.state.value.error else host.book.state.value.error)
                            ?.contains("Injected source-rule read failure") == true
                    }
                )
            }
            host.menu(R.id.menu_replace_source)
            host.ready()
            host.names("Seed++0", "Seed+1")
            host.click(ImportControl.Cancel)
            AppConfig.importReplaceSource = false
        }
    }

    @Test
    fun legacyManualFlagAndBackupRemainUnchanged() = runBlocking {
        assertFalse(AppConfig.manualReplaceRule)
        prefs.edit().putBoolean(PreferKey.manualReplaceRule, true).commit()
        assertTrue(AppConfig.manualReplaceRule)
        // The Compose management host exercises the current toggle and persistence path.
        BackupConfig.contentKeys.forEach {
            BackupConfig.ignoreConfig[it] = it != BackupConfig.settingContentKey
        }
        val archive = Backup.backupForLanTransferLocked(context).also { files.add(it) }
        ZipFile(archive).use { zip ->
            val xml =
                zip.getInputStream(checkNotNull(zip.getEntry("config.xml"))).bufferedReader().use {
                    it.readText()
                }
            assertTrue(xml.contains("name=\"manualReplaceRule\" value=\"true\""))
        }
        AppConfig.manualReplaceRule = false
        Restore.restoreOrThrow(context, archive.toUri(), lanTransfer = true)
        assertTrue(AppConfig.manualReplaceRule)
        for (readerManual in listOf(false, true)) for (sourceAutomatic in listOf(false, true)) {
            AppConfig.manualReplaceRule = readerManual
            AppConfig.importReplaceSource = sourceAutomatic
            val book =
                Book(
                    bookUrl = "https://reader-$id.invalid",
                    name = "Reader $id",
                    origin = "Origin $id",
                )
            book.setUseReplaceRule(true)
            val chapter = BookChapter(bookUrl = book.bookUrl, url = "chapter", title = "Seed title")
            val processed = ReadBook.processChapterContent(book, chapter, "Seed body")
            assertEquals(if (readerManual) "Seed title" else "Seed+ title", processed.first)
            val text = processed.second.toString()
            assertEquals(
                "Source import mode must not change reader behavior",
                !readerManual,
                text.contains("Body+"),
            )
            assertEquals(readerManual, text.contains("Seed body"))
            for (rss in listOf(false, true)) withImport(rss) { host ->
                if (sourceAutomatic) host.names("Seed++0", "Seed+1")
                else host.names("Seed0", "Seed1")
                host.manualEnabled(!sourceAutomatic)
                host.click(ImportControl.Cancel)
            }
        }
        val readerCandidates = appDb.replaceRuleDao.findManualCandidates().map { it.id }
        assertTrue(rules[0].id in readerCandidates)
        assertTrue(rules[5].id in readerCandidates)
        assertTrue(rules.subList(1, 5).none { it.id in readerCandidates })
    }

    private fun source(rss: Boolean, index: Int, name: String = "Seed$index"): Any =
        if (rss) RssSource(sourceUrl = urls[index], sourceName = name, ruleArticles = "article")
        else BookSource(bookSourceUrl = urls[index], bookSourceName = name, searchUrl = "/search")

    private fun withImport(rss: Boolean, action: (Host) -> Unit) {
        val file =
            File(context.cacheDir, "source-manual-$id-${files.size}.json").also { files.add(it) }
        file.writeText(GSON.toJson((0..1).map { source(rss, it) }))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        val intent =
            Intent(context, FileAssociationActivity::class.java).apply {
                this.action = Intent.ACTION_SEND
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        compose.launchAssociation<FileAssociationActivity>(intent).use { scenario ->
            val host = Host(scenario, rss)
            host.findParent()
            host.ready()
            action(host)
        }
    }

    private enum class ImportControl(val tag: String) {
        Confirm("confirm"),
        Cancel("cancel"),
    }

    private inner class Host(
        val scenario: ActivityScenario<FileAssociationActivity>,
        val rss: Boolean,
    ) {
        lateinit var parent: DialogFragment
        val book
            get() = ViewModelProvider(parent)[BookImportViewModel::class.java]

        val feed
            get() = ViewModelProvider(parent)[RssImportViewModel::class.java]

        fun findParent() =
            await("Missing import preview") {
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.fragments
                        .filterIsInstance<DialogFragment>()
                        .find {
                            if (rss) it is ImportRssSourceDialog else it is ImportBookSourceDialog
                        }
                        ?.let { parent = it }
                }
                ::parent.isInitialized && main { parent.view != null }
            }

        fun ready(top: DialogFragment = parent) =
            await("Preview still updating") {
                main {
                    (if (rss) feed.state.value.interactive else book.state.value.interactive) &&
                        top.dialog?.window?.decorView?.hasWindowFocus() == true &&
                        (top !is CodeDialog ||
                            top.model.state.value.loaded && !top.model.state.value.busy)
                }
            }

        fun names(vararg names: String) = main {
            assertEquals(
                names.toList(),
                if (rss) feed.state.value.items.map { it.sourceName }
                else book.state.value.items.map { it.sourceName },
            )
        }

        inline fun <reified T : DialogFragment> child(): T {
            var result: T? = null
            await("Missing ${T::class.simpleName}") {
                main {
                    result =
                        parent.childFragmentManager.fragments.filterIsInstance<T>().lastOrNull()
                    result?.dialog?.window?.decorView?.hasWindowFocus() == true &&
                        (result !is EffectiveReplacesDialog ||
                            ViewModelProvider(checkNotNull(result))[
                                    EffectiveReplacementViewModel::class.java]
                                .state
                                .value
                                .loading == false) &&
                        (result !is ManualReplaceRulesDialog ||
                            ViewModelProvider(checkNotNull(result))[
                                    ManualReplacementViewModel::class.java]
                                .state
                                .value
                                .loading == false) &&
                        (result !is CodeDialog || (result as CodeDialog).model.state.value.loaded)
                }
            }
            return checkNotNull(result)
        }

        fun menu(id: Int, dialog: DialogFragment = parent, waitForIdleAfterClick: Boolean = true) {
            if (dialog is CodeDialog) {
                val action =
                    when (id) {
                        R.id.menu_manual_replace_rule -> "Manual"
                        R.id.menu_effective_replaces -> "Effective"
                        R.id.menu_replace_rule -> "ReplaceRules"
                        else -> error("Unknown code action")
                    }
                compose.onNodeWithTag("code-menu").performClick()
                val item = compose.onNodeWithTag("code-action-$action").assertIsDisplayed()
                if (waitForIdleAfterClick) item.performClick()
                else {
                    val click =
                        item
                            .fetchSemanticsNode()
                            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick]
                            .action!!
                    main { assertTrue(click()) }
                }
                return
            }
            if (rss && dialog === parent) {
                compose.onNodeWithTag("rss-import-menu").performClick()
                val menu =
                    when (id) {
                        R.id.menu_replace_source -> RssImportMenu.Automatic
                        R.id.menu_manual_replace_rule -> RssImportMenu.Manual
                        R.id.menu_effective_replaces -> RssImportMenu.Effective
                        else -> error("Unsupported RSS menu $id")
                    }
                compose
                    .onNodeWithTag("rss-import-menu-${menu.name}")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .performClick()
                return
            }
            if (!rss && dialog === parent) {
                compose.onNodeWithTag("book-import-menu").performClick()
                val menu =
                    when (id) {
                        R.id.menu_replace_source -> BookImportMenu.Automatic
                        R.id.menu_manual_replace_rule -> BookImportMenu.Manual
                        R.id.menu_effective_replaces -> BookImportMenu.Effective
                        else -> error("Unsupported book menu $id")
                    }
                val item =
                    compose.onNodeWithTag("book-import-menu-${menu.name}").performScrollTo().assertIsDisplayed()
                if (waitForIdleAfterClick) item.performClick()
                else {
                    val action =
                        item
                            .fetchSemanticsNode()
                            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick]
                            .action!!
                    main { assertTrue(action()) }
                }
                return
            }
            error("Unsupported menu host ${dialog::class.java.simpleName}")
        }

        fun manualEnabled(enabled: Boolean, dialog: DialogFragment = parent) {
            if (dialog is CodeDialog) {
                compose.onNodeWithTag("code-menu").performClick()
                val item = compose.onNodeWithTag("code-action-Manual").assertIsDisplayed()
                if (enabled) item.assertIsEnabled() else item.assertIsNotEnabled()
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithTag("code-action-Manual")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
                }
                return
            }
            if (rss && dialog === parent) {
                compose.onNodeWithTag("rss-import-menu").performClick()
                val manual = compose.onNodeWithTag("rss-import-menu-Manual").assertIsDisplayed()
                if (enabled) manual.assertIsEnabled() else manual.assertIsNotEnabled()
                compose.onNodeWithTag("rss-import-menu-Automatic").assertIsDisplayed()
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithTag("rss-import-menu-Manual")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
                }
                main { assertEquals(!enabled, feed.state.value.automatic) }
                return
            }
            if (!rss && dialog === parent) {
                compose.onNodeWithTag("book-import-menu").performClick()
                val manual = compose.onNodeWithTag("book-import-menu-Manual").assertIsDisplayed()
                if (enabled) manual.assertIsEnabled() else manual.assertIsNotEnabled()
                compose.onNodeWithTag("book-import-menu-Automatic").assertIsDisplayed()
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithTag("book-import-menu-Manual")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
                }
                main { assertEquals(!enabled, book.state.value.automatic) }
                return
            }
            error("Unsupported manual replacement host ${dialog::class.java.simpleName}")
        }

        fun query(value: String) {
            if (rss) compose.onNodeWithTag("rss-import-search").performTextReplacement(value)
            else compose.onNodeWithTag("book-import-search").performTextReplacement(value)
        }

        fun click(control: ImportControl) {
            val prefix = if (rss) "rss-import" else "book-import"
            compose.onNodeWithTag("$prefix-${control.tag}").assertIsDisplayed().performClick()
        }

        fun open(index: Int): CodeDialog {
            if (rss) {
                val key = main { feed.state.value.items[index].key }
                compose.onNodeWithTag("rss-import-code-$key").performScrollTo().performClick()
                return child()
            }
            val key = main { book.state.value.items[index].key }
            compose.onNodeWithTag("book-import-code-$key").performScrollTo().performClick()
            return child()
        }
    }

    private fun ruleIds(dialog: DialogFragment) =
        if (dialog is EffectiveReplacesDialog)
            ViewModelProvider(dialog)[EffectiveReplacementViewModel::class.java]
                .state
                .value
                .rows
                .filterNot { it.conversion }
                .map { it.id }
        else
            ViewModelProvider(dialog)[ManualReplacementViewModel::class.java].state.value.rows.map {
                it.id
            }

    private fun clickRule(dialog: DialogFragment, index: Int) {
        val id = main {
            ViewModelProvider(dialog)[ManualReplacementViewModel::class.java]
                .state
                .value
                .rows[index]
                .id
        }
        compose.onNodeWithTag("manual-rule-$id").performScrollTo().performClick()
    }

    private fun <T> main(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun await(message: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.mainClock.advanceTimeByFrame()
                condition()
            }
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            assertTrue(message, condition())
        }
    }

    private fun screenshot(name: String, window: android.view.Window? = null) {
        instrumentation.waitForIdleSync()
        val bitmap =
            if (window == null) checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            else
                main {
                    Bitmap.createBitmap(
                        window.decorView.width,
                        window.decorView.height,
                        Bitmap.Config.ARGB_8888,
                    )
                }
        try {
            if (window != null) {
                val copied = java.util.concurrent.CountDownLatch(1)
                var result = android.view.PixelCopy.ERROR_UNKNOWN
                main {
                    android.view.PixelCopy.request(
                        window,
                        bitmap,
                        {
                            result = it
                            copied.countDown()
                        },
                        android.os.Handler(android.os.Looper.getMainLooper()),
                    )
                }
                assertTrue(
                    "Restored dialog buffer must be copied",
                    copied.await(5, java.util.concurrent.TimeUnit.SECONDS),
                )
                assertEquals(android.view.PixelCopy.SUCCESS, result)
            }
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
