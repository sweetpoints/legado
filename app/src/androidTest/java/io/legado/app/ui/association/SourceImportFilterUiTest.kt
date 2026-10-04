package io.legado.app.ui.association

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceImportFilterUiTest {
    private enum class ImportControl(val tag: String) {
        Confirm("confirm"),
        Cancel("cancel"),
        SelectVisible("select-visible"),
    }

    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val prefKeys =
        listOf(
            PreferKey.importReplaceSource,
            PreferKey.importRememberGroup,
            PreferKey.importLastGroup,
            PreferKey.importLastGroupAdd,
            PreferKey.autoBackup,
        )
    private val savedPrefs = prefKeys.associateWith { prefs.all[it] }
    private val savedRules = appDb.replaceRuleDao.findEnabledBySourceScope()
    private val id = UUID.randomUUID().toString()
    private val files = arrayListOf<File>()
    private val urls = arrayListOf<String>()
    private val rule =
        ReplaceRule(
            name = "Import filter $id",
            pattern = "Edited-$id",
            replacement = "Derived-$id",
            isRegex = false,
            scopeSource = true,
            scopeContent = false,
        )

    @Before
    fun setUp() {
        prefs
            .edit()
            .remove(PreferKey.importRememberGroup)
            .remove(PreferKey.importLastGroup)
            .remove(PreferKey.importLastGroupAdd)
            .putBoolean(PreferKey.importReplaceSource, false)
            .putBoolean(PreferKey.autoBackup, false)
            .commit()
        savedRules.forEach { appDb.replaceRuleDao.insert(it.copy(isEnabled = false)) }
        appDb.replaceRuleDao.insert(rule)
    }

    @After
    fun tearDown() {
        urls.forEach {
            appDb.bookSourceDao.delete(it)
            appDb.rssSourceDao.delete(it)
        }
        appDb.replaceRuleDao.delete(rule)
        savedRules.forEach { appDb.replaceRuleDao.insert(it) }
        files.forEach { it.delete() }
        prefs
            .edit()
            .apply {
                savedPrefs.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                    }
                }
            }
            .commit()
    }

    @Test fun bookFilteringKeepsCandidatesThroughEditReplacementAndRecreation() = filtering(false)

    @Test fun rssFilteringKeepsCandidatesThroughEditReplacementAndRecreation() = filtering(true)

    @Test
    fun onlineAutoImportKeepsOnePreviewAndItsStateAfterRecreation() {
        for (rss in listOf(false, true)) {
            val candidates =
                listOf(
                    source(rss, url("auto-$rss/hidden"), "Hidden", "Other", ""),
                    source(rss, url("auto-$rss/keep"), "Keep", "Wanted", ""),
                )
            val body = GSON.toJson(candidates).toByteArray(Charsets.UTF_8)
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 15_000
                val worker = Thread {
                    server.accept().use { client ->
                        client.soTimeout = 10_000
                        val reader = client.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        client.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII)
                            )
                            write(body)
                            flush()
                        }
                    }
                }
                    .apply {
                        isDaemon = true
                        start()
                    }
                val uri =
                    Uri.parse("legado://import/auto")
                        .buildUpon()
                        .appendQueryParameter(
                            "src",
                            "http://127.0.0.1:${server.localPort}/source.json",
                        )
                        .build()
                val intent =
                    Intent(context, OnLineImportActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(uri)
                ActivityScenario.launch<OnLineImportActivity>(intent).use { scenario ->
                    val host = ImportHost(scenario, rss)
                    host.findParent()
                    host.awaitReady()
                    host.query("Keep", 1)
                    host.rowClick(0, false)
                    host.selection(true, false)
                    val preview = host.open(0, 1)
                    val edited =
                        GSON.toJson(source(rss, url("auto-$rss/edited"), "Kept edit", "Wanted", ""))
                    compose.onNodeWithTag("code-body").performTextReplacement(edited)
                    compose.onNodeWithTag("code-save").performClick()
                    host.awaitReady()
                    host.query("Kept edit", 1)
                    host.recreate()
                    scenario.onActivity { activity ->
                        assertEquals(
                            1,
                            activity.supportFragmentManager.fragments
                                .filterIsInstance<DialogFragment>()
                                .size,
                        )
                    }
                    host.assertQuery("Kept edit", 1)
                    host.selection(true, false)
                    val restored = host.open(0, 1)
                    main {
                        assertEquals(edited, restored.currentOriginalCode())
                        restored.dismiss()
                    }
                    host.awaitReady()
                    screenshot("source-filter-auto-restored-$rss")
                    host.click(ImportControl.Cancel)
                    host.awaitFinished()
                }
                worker.join(1_000)
            }
        }
    }

    private fun filtering(rss: Boolean) {
        val candidateUrls = (0..2).map { url("$rss/candidate-$it") }
        val editedUrl = url("$rss/edited")
        val candidates =
            listOf(
                source(rss, candidateUrls[0], "Hidden", "Other", "hidden comment"),
                source(rss, candidateUrls[1], "Twin", "Precise,Shared", "OnlyNeedle"),
                source(rss, candidateUrls[2], "Twin", "PreciseSuffix,Shared", "other comment"),
            )
        val existing = source(rss, candidateUrls[2], "Old stored name", "Existing", "old", 1)
        if (rss) appDb.rssSourceDao.insert(existing as RssSource)
        else appDb.bookSourceDao.insert(existing as BookSource)
        withImport(rss, candidates) { host ->
            host.selection(true, true, true)
            host.rejectStaleRowAfterQuery("OnlyNeedle", 1)
            host.selection(true, true, true)
            host.query("TWIN", 1, 2)
            host.footer(2, 2, 3, all = true)
            host.click(ImportControl.SelectVisible)
            host.selection(true, false, false)
            host.footer(0, 2, 1, all = false)
            screenshot("source-filter-count-$rss")
            if (!rss) {
                host.query("Hidden", 0)
                host.rowClick(0, false)
                host.query("TWIN", 1, 2)
                host.menu(BookImportMenu.SelectNew)
                host.selection(false, true, false)
                host.menu(BookImportMenu.SelectUpdate)
                host.selection(false, true, true)
                host.query("Hidden", 0)
                host.rowClick(0, false)
                host.query("TWIN", 1, 2)
            } else {
                host.click(ImportControl.SelectVisible)
            }
            host.selection(true, true, true)
            host.rowClick(0, false)
            host.selection(true, false, true)
            host.query("CANDIDATE-1", 1)
            host.query("group:Precise", 1)
            host.query("Shared", 1, 2)
            host.query("OnlyNeedle", 1)
            host.recreate()
            host.assertQuery("OnlyNeedle", 1)
            host.selection(true, false, true)
            host.query("does-not-exist")
            host.noResults()
            host.footer(0, 0, 2, all = true)
            host.query("", 0, 1, 2)
            host.query("OnlyNeedle", 1)
            val preview = host.open(0, 1)
            val edited =
                GSON.toJson(source(rss, editedUrl, "Edited-$id", "Edited group", "edited comment"))
            main {
                assertTrue(preview.currentOriginalCode().contains(candidateUrls[1]))
                assertFalse(preview.currentOriginalCode().contains(candidateUrls[0]))
            }
            compose.onNodeWithTag("code-body").performTextReplacement(edited)
            compose.onNodeWithTag("code-save").performClick()
            host.awaitReady()
            host.assertQuery("OnlyNeedle")
            host.selection(true, false, true)
            host.query("Edited-$id", 1)
            host.menu(BookImportMenu.Automatic)
            host.awaitReady()
            host.assertQuery("Edited-$id")
            host.query("Derived-$id", 1)
            val derivedPreview = host.open(0, 1)
            main {
                assertTrue(derivedPreview.currentOriginalCode().contains("Edited-$id"))
                assertTrue(derivedPreview.model.state.value.displayed.contains("Derived-$id"))
                derivedPreview.dismiss()
            }
            host.awaitReady()
            host.selection(true, false, true)
            host.menu(BookImportMenu.Automatic)
            host.awaitReady()
            host.assertQuery("Derived-$id")
            host.query("", 0, 1, 2)
            // Clear every selection through the actual footer, then import only original index 1.
            host.click(ImportControl.SelectVisible)
            host.click(ImportControl.SelectVisible)
            host.selection(false, false, false)
            host.query("Edited-$id", 1)
            host.rowClick(0, false)
            host.selection(false, true, false)
            host.setGroup("Imported group", false)
            host.click(ImportControl.Confirm)
            await("Edited $rss source was not imported") {
                storedGroup(rss, editedUrl) == "Imported group"
            }
            host.awaitFinished()
        }
        assertNull(storedGroup(rss, candidateUrls[0]))
        assertNull(storedGroup(rss, candidateUrls[1]))
        assertEquals("Existing", storedGroup(rss, candidateUrls[2]))
        assertEquals(
            "Old stored name",
            if (rss) appDb.rssSourceDao.getByKey(candidateUrls[2])?.sourceName
            else appDb.bookSourceDao.getBookSource(candidateUrls[2])?.bookSourceName,
        )
        val savedName =
            if (rss) appDb.rssSourceDao.getByKey(editedUrl)?.sourceName
            else appDb.bookSourceDao.getBookSource(editedUrl)?.bookSourceName
        assertEquals("Edited-$id", savedName)
        assertStoredMetadata(rss, candidateUrls[2], existing)
        assertStoredMetadata(
            rss,
            editedUrl,
            source(rss, editedUrl, "Edited-$id", "Imported group", "edited comment"),
        )
    }

    @Test
    fun specialFiltersMatchManagementGroupAndLoginSemantics() {
        for (rss in listOf(false, true)) {
            val candidates =
                listOf("未分组", "Ungrouped", "RSS", "rss").mapIndexed { index, group ->
                    source(rss, url("$rss/metadata-$index"), group = group).also {
                        when (it) {
                            is BookSource ->
                                when (index) {
                                    0 -> {
                                        it.mainJs = "function login() {}"
                                        it.loginUi = "[{\"name\":\"account\"}]"
                                    }
                                    1 -> it.loginUrl = "https://login.invalid"
                                }
                            is RssSource -> if (index == 1) it.loginUrl = "https://login.invalid"
                        }
                    }
                }
            withImport(rss, candidates) { host ->
                host.query("group:RSS", 2)
                host.query("group:rss", 3)
                host.query(context.getString(R.string.no_group), 0)
                host.query(
                    context.getString(R.string.need_login),
                    *if (rss) intArrayOf(1) else intArrayOf(0, 1),
                )
                host.click(ImportControl.Cancel)
                host.awaitFinished()
            }
        }
    }

    @Test
    fun groupMemoryIsOptInSharedBetweenBookAndRssAndDisablingClearsIt() {
        assertFalse("The unset preference must default to OFF", AppConfig.importRememberGroup)
        val first = url("group/default-off")
        withImport(false, listOf(source(false, first))) { host ->
            host.group(null, false)
            host.setGroup("Once", false)
            assertNull(AppConfig.importLastGroup)
            host.click(ImportControl.Confirm)
            await("One-time replacement group was not imported") {
                storedGroup(false, first) == "Once"
            }
        }
        val second = url("group/remember-add")
        withImport(true, listOf(source(true, second))) { host ->
            host.group(null, false)
            host.menu(BookImportMenu.RememberGroup)
            assertTrue(AppConfig.importRememberGroup)
            host.setGroup("Remember add", true)
            assertEquals("Remember add", AppConfig.importLastGroup)
            assertTrue(AppConfig.importLastGroupAdd)
            host.click(ImportControl.Confirm)
            await("Remembered add group was not applied") {
                storedGroup(true, second) == "Original,Remember add"
            }
        }
        val third = url("group/remember-replace")
        withImport(false, listOf(source(false, third))) { host ->
            host.group("Remember add", true)
            host.inspectGroupDialog("Remember add", true)
            screenshot("source-remembered-group-book")
            host.cancelGroup()
            host.setGroup("Remember replace", false)
            host.recreate()
            host.group("Remember replace", false)
            host.click(ImportControl.Confirm)
            await("Remembered replacement group was not applied") {
                storedGroup(false, third) == "Remember replace"
            }
        }
        val fourth = url("group/disable")
        withImport(true, listOf(source(true, fourth))) { host ->
            host.group("Remember replace", false)
            host.inspectGroupDialog("Remember replace", false)
            screenshot("source-remembered-group-rss")
            host.cancelGroup()
            host.menu(BookImportMenu.RememberGroup)
            host.group(null, false)
            assertFalse(AppConfig.importRememberGroup)
            assertNull(AppConfig.importLastGroup)
            assertFalse(AppConfig.importLastGroupAdd)
            host.click(ImportControl.Confirm)
            await("Disabling memory must preserve the source's own group") {
                storedGroup(true, fourth) == "Original"
            }
        }
        withImport(false, listOf(source(false, url("group/after-disable")))) { host ->
            host.group(null, false)
            host.menu(BookImportMenu.RememberGroup)
            host.group(null, false)
            assertNull(AppConfig.importLastGroup)
            host.click(ImportControl.Cancel)
        }
    }

    private fun source(
        rss: Boolean,
        url: String,
        name: String = "Group fixture",
        group: String = "Original",
        comment: String = "",
        time: Long = 100,
    ): Any =
        if (rss)
            RssSource(
                sourceUrl = url,
                sourceName = name,
                sourceGroup = group,
                sourceComment = comment,
                ruleArticles = "article",
                lastUpdateTime = time,
            )
        else
            BookSource(
                bookSourceUrl = url,
                bookSourceName = name,
                bookSourceGroup = group,
                bookSourceComment = comment,
                searchUrl = "/search",
                lastUpdateTime = time,
            )

    private fun url(path: String) = "https://filter-$id.invalid/$path".also { urls.add(it) }

    private fun storedGroup(rss: Boolean, url: String): String? =
        if (rss) appDb.rssSourceDao.getByKey(url)?.sourceGroup
        else appDb.bookSourceDao.getBookSource(url)?.bookSourceGroup

    private fun assertStoredMetadata(rss: Boolean, url: String, expected: Any) {
        val actual =
            if (rss) checkNotNull(appDb.rssSourceDao.getByKey(url))
            else checkNotNull(appDb.bookSourceDao.getBookSource(url))
        // Book's existing SourceHelp renumbers customOrder asynchronously. Compare every other
        // persisted field structurally, including nested rules, rather than URL-only entity equals.
        fun document(value: Any) =
            GSON.toJsonTree(if (value is BookSource) value.copy(customOrder = 0) else value)
        assertEquals(
            "Full persisted metadata: rss=$rss url=$url",
            document(expected),
            document(actual),
        )
    }

    private fun withImport(rss: Boolean, sources: List<Any>, action: (ImportHost) -> Unit) {
        val file = File(context.cacheDir, "source-filter-$id-${files.size}.json")
        files.add(file)
        file.writeText(GSON.toJson(sources))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        val intent =
            Intent(context, FileAssociationActivity::class.java).apply {
                this.action = Intent.ACTION_SEND
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        ActivityScenario.launch<FileAssociationActivity>(intent).use { scenario ->
            val host = ImportHost(scenario, rss)
            host.findParent()
            host.awaitReady()
            action(host)
        }
    }

    private inner class ImportHost(
        val scenario: ActivityScenario<out FragmentActivity>,
        val rss: Boolean,
    ) {
        lateinit var parent: DialogFragment
        private val book
            get() = ViewModelProvider(parent)[BookImportViewModel::class.java]

        private val feed
            get() = ViewModelProvider(parent)[RssImportViewModel::class.java]

        private fun bookLabels() =
            BookImportSearchLabels(
                context.getString(R.string.enabled),
                context.getString(R.string.disabled),
                context.getString(R.string.need_login),
                context.getString(R.string.no_group),
                context.getString(R.string.enabled_explore),
                context.getString(R.string.disabled_explore),
            )

        private val prefix: String
            get() = if (rss) "rss-import" else "book-import"

        private fun rssLabels() =
            RssImportSearchLabels(
                context.getString(R.string.enabled),
                context.getString(R.string.disabled),
                context.getString(R.string.need_login),
                context.getString(R.string.no_group),
            )

        private fun visibleKeys(): List<String> = main {
            if (rss) visibleRssImportItems(feed.state.value, rssLabels()).map { it.key }
            else visibleBookImportItems(book.state.value, bookLabels()).map { it.key }
        }

        fun findParent() =
            await("Import dialog missing: rss=$rss") {
                var found: DialogFragment? = null
                scenario.onActivity { activity ->
                    found =
                        activity.supportFragmentManager.fragments
                            .filterIsInstance<DialogFragment>()
                            .find {
                                if (rss) it is ImportRssSourceDialog
                                else it is ImportBookSourceDialog
                            }
                }
                found?.also { parent = it } != null
            }

        fun awaitReady() =
            await("Import dialog not ready: rss=$rss") {
                main {
                    parent.view != null &&
                        (if (rss) feed.state.value.interactive else book.state.value.interactive) &&
                        parent.dialog?.window?.decorView?.hasWindowFocus() == true
                }
            }

        fun query(query: String, vararg expected: Int) {
            if (rss) compose.onNodeWithTag("rss-import-search").performTextReplacement(query)
            else compose.onNodeWithTag("book-import-search").performTextReplacement(query)
            assertQuery(query, *expected)
        }

        fun assertQuery(query: String, vararg expected: Int) {
            await("Filter '$query' did not resolve ${expected.toList()}: rss=$rss") {
                main {
                    if (rss)
                        visibleRssImportItems(
                                feed.state.value,
                                RssImportSearchLabels(
                                    context.getString(R.string.enabled),
                                    context.getString(R.string.disabled),
                                    context.getString(R.string.need_login),
                                    context.getString(R.string.no_group),
                                ),
                            )
                            .map { it.key.toInt() } == expected.toList()
                    else
                        visibleBookImportItems(book.state.value, bookLabels()).map {
                            it.key.toInt()
                        } == expected.toList()
                }
            }
            if (rss) compose.onNodeWithTag("rss-import-search").assertTextContains(query)
            else compose.onNodeWithTag("book-import-search").assertTextContains(query)
            main {
                assertEquals(query, if (rss) feed.state.value.query else book.state.value.query)
            }
            expected.forEach { index ->
                compose.onNodeWithTag("$prefix-row-$index").performScrollTo().assertIsDisplayed()
            }
        }

        fun selection(vararg expected: Boolean) {
            main {
                assertEquals(
                    "Selection must use original indices: rss=$rss",
                    expected.toList(),
                    if (rss) feed.state.value.items.map { it.key in feed.state.value.selected }
                    else book.state.value.items.map { it.key in book.state.value.selected },
                )
            }
            visibleKeys().forEach { key ->
                val checkbox = compose.onNodeWithTag("$prefix-check-$key").performScrollTo()
                if (expected[key.toInt()]) checkbox.assertIsOn() else checkbox.assertIsOff()
            }
        }

        fun footer(selected: Int, visible: Int, total: Int, all: Boolean) {
            val expected =
                context.getString(
                    if (all) R.string.import_unselect_results else R.string.import_select_results,
                    selected,
                    visible,
                    total,
                )
            if (rss) compose.onNodeWithTag("rss-import-select-visible").assertTextContains(expected)
            else compose.onNodeWithTag("book-import-select-visible").assertTextContains(expected)
        }

        fun noResults() {
            if (rss) {
                compose
                    .onNodeWithText(context.getString(R.string.import_no_results))
                    .assertIsDisplayed()
                compose.onNodeWithTag("rss-import-select-visible").assertIsNotEnabled()
            } else {
                compose
                    .onNodeWithTag("book-import-empty")
                    .assertTextEquals(context.getString(R.string.import_no_results))
                compose.onNodeWithTag("book-import-select-visible").assertIsNotEnabled()
            }
        }

        fun click(control: ImportControl) {
            compose.onNodeWithTag("$prefix-${control.tag}").performClick()
        }

        fun rowClick(position: Int, openCode: Boolean) {
            if (rss) {
                val key = main {
                    visibleRssImportItems(
                            feed.state.value,
                            RssImportSearchLabels(
                                context.getString(R.string.enabled),
                                context.getString(R.string.disabled),
                                context.getString(R.string.need_login),
                                context.getString(R.string.no_group),
                            ),
                        )[position]
                        .key
                }
                compose
                    .onNodeWithTag("rss-import-${if (openCode) "code" else "check"}-$key")
                    .performScrollTo()
                    .performClick()
                return
            }
            val key = main { visibleBookImportItems(book.state.value, bookLabels())[position].key }
            compose
                .onNodeWithTag("book-import-${if (openCode) "code" else "check"}-$key")
                .performScrollTo()
                .performClick()
        }

        fun rejectStaleRowAfterQuery(query: String, originalIndex: Int) {
            if (rss) {
                val stale = compose.onNodeWithTag("rss-import-check-0")
                this.query(query, originalIndex)
                stale.assertDoesNotExist()
                main { assertTrue(parent.childFragmentManager.fragments.none { it is CodeDialog }) }
                return
            }
            val stale = compose.onNodeWithTag("book-import-check-0")
            this.query(query, originalIndex)
            stale.assertDoesNotExist()
            main { assertTrue(parent.childFragmentManager.fragments.none { it is CodeDialog }) }
            assertQuery(query, originalIndex)
        }

        fun menu(option: BookImportMenu) {
            // Both actual menu enums share these options; RSS deliberately omits Book-only actions.
            val menuName = if (rss) RssImportMenu.valueOf(option.name).name else option.name
            compose.onNodeWithTag("$prefix-menu").performClick()
            compose.onNodeWithTag("$prefix-menu-$menuName").performClick()
            awaitReady()
        }

        fun open(position: Int, originalIndex: Int): CodeDialog {
            rowClick(position, true)
            var code: CodeDialog? = null
            await("Source code preview missing: rss=$rss") {
                main {
                    code =
                        parent.childFragmentManager.fragments
                            .filterIsInstance<CodeDialog>()
                            .firstOrNull()
                    code?.dialog?.window?.decorView?.hasWindowFocus() == true &&
                        code?.model?.state?.value?.loaded == true
                }
            }
            return checkNotNull(code).also {
                main { assertEquals(originalIndex.toString(), it.requestId) }
            }
        }

        fun recreate() {
            scenario.recreate()
            findParent()
            awaitReady()
        }

        fun awaitFinished() =
            await("Dismissed import host must finish") {
                scenario.state == Lifecycle.State.DESTROYED
            }

        fun group(expected: String?, add: Boolean) {
            main {
                assertEquals(expected, if (rss) feed.state.value.group else book.state.value.group)
                assertEquals(add, if (rss) feed.state.value.addGroup else book.state.value.addGroup)
            }
            val title =
                expected
                    ?.let { context.getString(R.string.diy_edit_source_group_title, it) }
                    ?.let { if (add) "+$it" else it }
                    ?: context.getString(R.string.diy_source_group)
            if (rss) {
                compose.onNodeWithTag("rss-import-group").assertTextContains(title)
                main {
                    assertEquals(
                        AppConfig.importRememberGroup,
                        feed.state.value.preferences.rememberGroup,
                    )
                }
                assertEquals(
                    RssImportMenu.ShowComment.ordinal + 1,
                    RssImportMenu.RememberGroup.ordinal,
                )
                assertRememberMenuOrder()
            } else {
                compose.onNodeWithTag("book-import-group").assertTextContains(title)
                main {
                    assertEquals(
                        AppConfig.importRememberGroup,
                        book.state.value.preferences.rememberGroup,
                    )
                }
                assertRememberMenuOrder()
            }
        }

        private fun assertRememberMenuOrder() {
            compose.onNodeWithTag("$prefix-menu").performClick()
            val comment =
                compose.onNodeWithTag("$prefix-menu-ShowComment").fetchSemanticsNode().boundsInRoot
            val remember =
                compose
                    .onNodeWithTag("$prefix-menu-RememberGroup")
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(comment.bottom <= remember.top)
            androidx.test.espresso.Espresso.pressBack()
        }

        fun inspectGroupDialog(expected: String?, add: Boolean) {
            if (rss) {
                compose.onNodeWithTag("rss-import-group").performClick()
                compose
                    .onNodeWithTag("rss-import-group-name")
                    .assertTextContains(expected.orEmpty())
                if (add) compose.onNodeWithTag("rss-import-add-group").assertIsOn()
                else compose.onNodeWithTag("rss-import-add-group").assertIsOff()
                return
            }
            compose.onNodeWithTag("book-import-group").performClick()
            compose.onNodeWithTag("book-import-group-name").assertTextContains(expected.orEmpty())
            if (add) compose.onNodeWithTag("book-import-add-group").assertIsOn()
            else compose.onNodeWithTag("book-import-add-group").assertIsOff()
        }

        fun cancelGroup() {
            if (rss) compose.onNodeWithTag("rss-import-group-cancel").performClick()
            else compose.onNodeWithTag("book-import-group-cancel").performClick()
        }

        fun setGroup(value: String, add: Boolean) {
            val current = main {
                (if (rss) feed.state.value.group else book.state.value.group) to
                    (if (rss) feed.state.value.addGroup else book.state.value.addGroup)
            }
            inspectGroupDialog(current.first, current.second)
            if (rss) {
                compose.onNodeWithTag("rss-import-group-name").performTextReplacement(value)
                if (current.second != add)
                    compose.onNodeWithTag("rss-import-add-group").performClick()
                compose.onNodeWithTag("rss-import-group-ok").performClick()
                awaitReady()
                group(value, add)
                return
            }
            compose.onNodeWithTag("book-import-group-name").performTextReplacement(value)
            if (current.second != add) compose.onNodeWithTag("book-import-add-group").performClick()
            compose.onNodeWithTag("book-import-group-ok").performClick()
            awaitReady()
            group(value, add)
        }
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
                condition()
            }
            return
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // Preserve the original state diagnostics and failure assertion below.
        }
        assertTrue(message, condition())
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            try {
                File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
