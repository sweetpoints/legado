package io.legado.app.ui.association

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.association.compose.AssociationDataImportDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.file.HandleFileActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedFileImportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = HashMap(prefs.all)
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLocal =
        listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion").associateWith {
            LocalConfig.all[it]
        }
    private val savedBackupTime = LocalConfig.lastBackup
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "shared-file-$id")
    private val books = arrayListOf<Book>()
    private val sources = arrayListOf<BookSource>()
    private val rules = arrayListOf<HighlightRule>()
    private val replacements = arrayListOf<ReplaceRule>()

    @Before
    fun setUp() {
        directory.mkdirs()
        File(directory, "books").mkdirs()
        prefs
            .edit()
            .putBoolean(PreferKey.cronet, false)
            .putBoolean(PreferKey.autoBackup, false)
            .putString(PreferKey.defaultBookTreeUri, File(directory, "books").path)
            .putBoolean(PreferKey.onlyLatestBackup, true)
            .commit()
        LocalConfig.edit()
            .putBoolean("privacyPolicyOk", true)
            .putInt("readHelpVersion", 1)
            .putInt("readMenuHelpVersion", 1)
            .commit()
    }

    @After
    fun tearDown() {
        closeReaders()
        rules.forEach { rule ->
            appDb.highlightRuleDao.all
                .find { it.uuid == rule.uuid }
                ?.let { appDb.highlightRuleDao.delete(it) }
        }
        replacements.forEach { appDb.replaceRuleDao.delete(it) }
        books.forEach { book ->
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            BookHelp.clearCache(book)
        }
        sources.forEach { appDb.bookSourceDao.delete(it) }
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        prefs
            .edit()
            .clear()
            .apply {
                savedPrefs.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                        }
                    }
                }
            }
            .commit()
        LocalConfig.edit()
            .apply {
                savedLocal.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                    }
                }
            }
            .commit()
        LocalConfig.lastBackup = savedBackupTime
        directory.deleteRecursively()
    }

    @Test(timeout = 120_000)
    fun sharedBookListConfirmsThenUsesEnabledSourceSearchAndPersistsTheMatchedBook() {
        val name = "Shared book $id"
        val author = "Shared author"
        val missing = "Missing book $id"
        val mixed = AtomicBoolean()
        val requests = AtomicInteger()
        val server =
            object : NanoHTTPD("127.0.0.1", 0) {
                    override fun serve(session: IHTTPSession): Response {
                        requests.incrementAndGet()
                        if (mixed.get()) {
                            if (session.parameters["key"]?.firstOrNull() == missing)
                                return newFixedLengthResponse("<html></html>")
                            Thread.sleep(600)
                        }
                        return newFixedLengthResponse(
                            "<article><h2>$name</h2><span class='author'>$author</span>" +
                                "<a href='/book/$id'>Details</a></article>"
                        )
                    }
                }
                .apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val source =
            BookSource(
                "http://127.0.0.1:${server.listeningPort}",
                "Share fixture",
                customOrder = Int.MIN_VALUE,
                searchUrl = "/search?key={{key}}",
                ruleSearch =
                    SearchRule(
                        bookList = "article",
                        name = "h2@text",
                        author = ".author@text",
                        bookUrl = "a@href",
                    ),
            )
        sources.add(source)
        appDb.bookSourceDao.insert(source)
        val file =
            File(directory, "renamed-list.json").apply {
                writeText(
                    GSON.toJson(
                        listOf(mapOf("name" to name, "author" to author, "intro" to "intro"))
                    )
                )
            }
        try {
            launchShare(file, "application/json").use { scenario ->
                awaitDialog(scenario)
                compose
                    .onNodeWithText(context.getString(R.string.import_bookshelf))
                    .assertIsDisplayed()
                assertFalse(appDb.bookDao.has(name, author))
                assertEquals(0, requests.get())
                scenario.recreate()
                awaitDialog(scenario)
                compose
                    .onNodeWithText(context.getString(R.string.import_bookshelf))
                    .assertIsDisplayed()
                screenshot("share-bookshelf-confirmation")
                lateinit var model: FileAssociationViewModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[FileAssociationViewModel::class.java]
                }
                clickDialogText(R.string.ok)
                await { model.state.value.session?.phase == AssociationPhase.Finished }
                val book = checkNotNull(appDb.bookDao.getBook(name, author))
                books.add(book)
                assertEquals("${source.bookSourceUrl}/book/$id", book.bookUrl)
                assertEquals(source.bookSourceUrl, book.origin)
                assertTrue(requests.get() > 0)
            }
            val savedEnabled =
                appDb.bookSourceDao.allEnabled.filter { it.bookSourceUrl != source.bookSourceUrl }
            savedEnabled.forEach { appDb.bookSourceDao.enable(it.bookSourceUrl, false) }
            try {
                appDb.bookDao.delete(books.single())
                mixed.set(true)
                requests.set(0)
                file.writeText(
                    GSON.toJson(
                        listOf(
                            mapOf("name" to missing, "author" to author),
                            mapOf("name" to name, "author" to author),
                        )
                    )
                )
                launchShare(file, "application/json").use { scenario ->
                    awaitDialog(scenario)
                    clickDialogText(R.string.ok)
                    await {
                        appDb.bookDao.has(name, author) &&
                            AppLog.logs.any { it.second.contains(missing) }
                    }
                    assertFalse(appDb.bookDao.has(missing, author))
                    assertTrue(requests.get() >= 2)
                    assertEquals(source.bookSourceUrl, appDb.bookDao.getBook(name, author)!!.origin)
                }
            } finally {
                savedEnabled.forEach { appDb.bookSourceDao.enable(it.bookSourceUrl, true) }
            }
        } finally {
            server.stop()
        }
    }

    @Test(timeout = 120_000)
    fun rawAndTypedHighlightFilesUseTheHighlightPreviewWhileReplacementFilesKeepTheirRoute() {
        for (typed in listOf(false, true)) {
            val rule =
                HighlightRule(
                    name = "Shared highlight $typed",
                    pattern = "target-$id-$typed",
                    style =
                        """{"bold":true,"textColor":123456,"fill":123456,"fillShape":"PILL","pillPaddingScale":1.5}""",
                    group = "Share group",
                    applyToTitle = true,
                    applyToBody = false,
                    isEnabled = false,
                )
            rules.add(rule)
            val file =
                File(directory, "unrelated-$typed.json").apply {
                    writeText(
                        GSON.toJson(
                            if (typed) HighlightRuleFile(HighlightRuleFile.TYPE, listOf(rule))
                            else listOf(rule)
                        )
                    )
                }
            launchShare(file, "application/octet-stream").use { scenario ->
                awaitDialog(scenario)
                compose.onNodeWithText(rule.name).assertIsDisplayed()
                assertFalse(appDb.highlightRuleDao.all.any { it.uuid == rule.uuid })
                screenshot("share-highlight-$typed")
                compose.onNodeWithTag("highlight-import-confirm").performClick()
                await { appDb.highlightRuleDao.all.any { it.uuid == rule.uuid } }
                val saved = appDb.highlightRuleDao.all.single { it.uuid == rule.uuid }
                assertEquals(rule.styleObj(), saved.styleObj())
                assertEquals(rule.pattern, saved.pattern)
                assertEquals(rule.group, saved.group)
                assertEquals(rule.applyToBody, saved.applyToBody)
                assertFalse(saved.isEnabled)
            }
        }
        val rule =
            ReplaceRule(
                id = System.currentTimeMillis(),
                name = "Shared replacement",
                pattern = id,
                replacement = "changed",
            )
        replacements.add(rule)
        val file =
            File(directory, "misleading-highlight-name.json").apply {
                writeText(GSON.toJson(listOf(rule)))
            }
        launchShare(file, "application/json").use { scenario ->
            awaitDialog(scenario)
            compose.waitUntil {
                compose.onAllNodesWithText(rule.name).fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNodeWithText(context.getString(R.string.import_replace_rule))
                .assertIsDisplayed()
            compose.onNodeWithText(rule.name).assertIsDisplayed()
            compose.onNodeWithTag("replace-import-confirm").performClick()
            await { appDb.replaceRuleDao.all.any { it.id == rule.id } }
            assertEquals(
                "changed",
                appDb.replaceRuleDao.all.single { it.id == rule.id }.replacement,
            )
            assertFalse(appDb.highlightRuleDao.all.any { it.pattern == id })
        }
    }

    @Test(timeout = 120_000)
    fun actualBackupZipConfirmsBeforeRestoringBooksSourcesRulesAndSettings() = runBlocking {
        val source = BookSource("https://shared-backup-$id.invalid", "Backup source")
        val book =
            Book(
                bookUrl = "${source.bookSourceUrl}/book",
                origin = source.bookSourceUrl,
                name = "Backup book $id",
                author = "Author",
                durChapterIndex = 7,
            )
        val rule =
            HighlightRule(
                name = "Backup rule $id",
                pattern = id,
                style =
                    """{"bold":true,"fill":123456,"fillShape":"PILL","pillPaddingScale":1.75}""",
            )
        sources.add(source)
        books.add(book)
        rules.add(rule)
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.highlightRuleDao.insert(rule)
        val marker = "shared-backup-$id"
        prefs.edit().putString(marker, "restored value").commit()
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
        listOf(
                BackupConfig.bookshelfContentKey,
                BackupConfig.annotationContentKey,
                BackupConfig.sourceContentKey,
                BackupConfig.settingContentKey,
            )
            .forEach { BackupConfig.ignoreConfig[it] = false }
        val output = File(directory, "backup").apply { mkdirs() }
        Backup.backupLocked(context, output.path, uploadWebDav = false)
        val archive = output.listFiles()!!.single { it.extension == "zip" }
        ZipFile(archive).use { zip ->
            listOf("bookshelf.json", "bookSource.json", "highlightRule.json", "config.xml")
                .forEach { assertNotNull(it, zip.getEntry(it)) }
        }
        val renamed = File(directory, "any-name.zip")
        archive.copyTo(renamed)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        appDb.highlightRuleDao.all
            .find { it.uuid == rule.uuid }!!
            .let { appDb.highlightRuleDao.delete(it) }
        prefs.edit().remove(marker).commit()
        launchShare(renamed, "application/zip").use { scenario ->
            awaitDialog(scenario)
            compose
                .onNodeWithText(context.getString(R.string.restore_confirmation))
                .assertIsDisplayed()
            assertFalse(appDb.bookDao.has(book.bookUrl))
            screenshot("share-backup-confirmation")
            clickDialogText(R.string.cancel)
        }
        assertFalse(appDb.bookDao.has(book.bookUrl))
        assertFalse(prefs.contains(marker))
        launchShare(renamed, "application/zip").use { scenario ->
            awaitDialog(scenario)
            lateinit var model: FileAssociationViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[FileAssociationViewModel::class.java]
            }
            clickDialogText(R.string.ok)
            await { model.state.value.session?.phase == AssociationPhase.Finished }
            assertEquals("restored value", prefs.getString(marker, null))
            assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
            assertEquals(
                source.bookSourceName,
                appDb.bookSourceDao.getBookSource(source.bookSourceUrl)!!.bookSourceName,
            )
            assertEquals(
                rule.styleObj(),
                appDb.highlightRuleDao.all.single { it.uuid == rule.uuid }.styleObj(),
            )
        }
    }

    @Test(timeout = 120_000)
    fun sharedTxtEpubPdfAndBookZipCopyDurablyAndOpenTheRealReader() {
        val txt =
            File(directory, "shared-txt-$id.txt").apply {
                writeText("Chapter one\n" + "SHARED_TEXT_VISIBLE $id\n".repeat(20))
            }
        val epub =
            File(directory, "shared-epub-$id.epub").apply {
                instrumentation.context.assets.open("issue1074-containers-fragments.epub").use {
                    input ->
                    outputStream().use(input::copyTo)
                }
            }
        val pdf =
            File(directory, "shared-pdf-$id.pdf").apply {
                val document = PdfDocument()
                try {
                    val page =
                        document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    val paint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 100, 170) }
                    page.canvas.drawRect(24f, 24f, 576f, 120f, paint)
                    paint.color = Color.WHITE
                    paint.textSize = 32f
                    page.canvas.drawText("SHARED PDF VISIBLE", 40f, 85f, paint)
                    document.finishPage(page)
                    outputStream().use(document::writeTo)
                } finally {
                    document.close()
                }
            }
        listOf(txt to "text/plain", epub to "application/epub+zip", pdf to "application/pdf")
            .forEach { (file, mime) ->
                val expected = file.readBytes()
                launchShare(file, mime).use { scenario ->
                    confirmLocalPreview(scenario)
                    val copied = File(directory, "books/${file.name}")
                    await { copied.isFile && appDb.bookDao.has(copied.path) }
                    val book = appDb.bookDao.getBook(copied.path)!!
                    books.add(book)
                    assertArrayEquals(expected, copied.readBytes())
                    assertTrue(file.delete())
                    openReader(book)
                    awaitReader(book)
                    val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                    assertTrue(chapters.isNotEmpty())
                    assertFalse(LocalBook.getContent(book, chapters.first()).isNullOrBlank())
                    if (file.extension == "pdf") await { pdfMarkerIsVisible() }
                    screenshot("share-reader-${copied.extension}")
                    closeReaders()
                }
            }
        val archive = File(directory, "book-container.zip")
        val chapter = "Ordinary archive text $id"
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("archive-$id.txt"))
            zip.write(chapter.toByteArray())
            zip.closeEntry()
        }
        launchShare(archive, "application/zip").use { scenario ->
            confirmLocalPreview(scenario)
            val path = File(directory, "books/archive-$id.txt").path
            await { appDb.bookDao.has(path) }
            val book = appDb.bookDao.getBook(path)!!
            openReader(book)
            books.add(book)
            awaitReader(book)
            val renderedPages = ReadBook.curTextChapter!!.pages.map { it.text }
            screenshot("share-reader-zip")
            assertEquals(chapter, File(path).readText())
            assertTrue(
                "Expected archive text <$chapter>; rendered pages: $renderedPages",
                renderedPages.any { it.contains(chapter) },
            )
            closeReaders()
        }
    }

    @Test(timeout = 120_000)
    fun firstSharedBookRetainsItsSelectedBatchAcrossRecreationAndTheFolderResult() {
        prefs.edit().remove(PreferKey.defaultBookTreeUri).commit()
        val file =
            File(directory, "first-share-$id.txt").apply {
                writeText("FIRST_SHARED_STREAM $id\n".repeat(15))
            }
        val folderRequests = AtomicInteger()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component?.className != HandleFileActivity::class.java.name)
                        return null
                    assertEquals(HandleFileContract.DIR_SYS, intent.getIntExtra("mode", -1))
                    folderRequests.incrementAndGet()
                    val activity =
                        listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED)
                            .flatMap {
                                ActivityLifecycleMonitorRegistry.getInstance()
                                    .getActivitiesInStage(it)
                            }
                            .filterIsInstance<FileAssociationActivity>()
                            .single()
                    val model = ViewModelProvider(activity)[FileAssociationViewModel::class.java]
                    assertEquals(1, model.pendingLocalBooks.size)
                    assertTrue(model.choosingLocalBookDirectory)
                    assertNotNull(
                        activity.supportFragmentManager.findFragmentByTag("sharedLocalBooks")
                    )
                    return Instrumentation.ActivityResult(
                        Activity.RESULT_OK,
                        Intent().setData(Uri.fromFile(File(directory, "books"))),
                    )
                }
            }
        instrumentation.addMonitor(monitor)
        try {
            launchShare(file, "text/plain").use { scenario ->
                awaitLocalPreview(scenario)
                assertEquals(0, folderRequests.get())
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(
                        1,
                        activity.supportFragmentManager.fragments
                            .filterIsInstance<ImportLocalBookDialog>()
                            .size,
                    )
                }
                awaitLocalPreview(scenario)
                confirmLocalPreview(scenario)
                compose
                    .onNodeWithText(context.getString(R.string.shared_local_books_storage))
                    .assertIsDisplayed()
                screenshot("share-local-folder-after-confirmation")
                clickDialogText(R.string.select_folder)
                val copied = File(directory, "books/${file.name}")
                await { copied.exists() && appDb.bookDao.has(copied.path) }
                val book = appDb.bookDao.getBook(copied.path)!!
                books.add(book)
                assertEquals(1, folderRequests.get())
                assertEquals(file.readText(), copied.readText())
                openReader(book)
                awaitReader(book)
                assertTrue(
                    ReadBook.curTextChapter!!.pages.any { it.text.contains("FIRST_SHARED_STREAM") }
                )
                closeReaders()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test(timeout = 120_000)
    fun saveFolderMenusUpdateTheSharedSettingWithoutMovingExistingBooks() {
        AppConfig.importBookPath = directory.path
        val oldFile =
            File(directory, "books/old-menu-$id.txt").apply { writeText("ORIGINAL MENU BOOK") }
        val oldBook = LocalBook.importFile(Uri.fromFile(oldFile)).also(books::add)
        val newDirectory = File(directory, "changed").apply { mkdirs() }
        val finalDirectory = File(directory, "preview-changed").apply { mkdirs() }
        var destination = newDirectory
        val requests = AtomicInteger()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component?.className != HandleFileActivity::class.java.name)
                        return null
                    assertEquals(HandleFileContract.DIR_SYS, intent.getIntExtra("mode", -1))
                    requests.incrementAndGet()
                    return Instrumentation.ActivityResult(
                        Activity.RESULT_OK,
                        Intent().setData(Uri.fromFile(destination)),
                    )
                }
            }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<ImportBookActivity>(
                    Intent(context, ImportBookActivity::class.java)
                )
                .use {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            !Environment.isExternalStorageManager()
                    ) {
                        onView(withText(R.string.tip_perm_request_storage))
                            .inRoot(isDialog())
                            .check(matches(isDisplayed()))
                        // Declining the existing scan permission must still leave the save-folder
                        // menu usable.
                        pressBack()
                    }
                    compose
                        .onNodeWithContentDescription(context.getString(R.string.menu))
                        .performClick()
                    compose
                        .onNodeWithText(context.getString(R.string.local_book_save_path))
                        .performClick()
                    compose
                        .onNodeWithText(context.getString(R.string.local_book_save_path))
                        .assertIsDisplayed()
                    screenshot("local-import-save-folder-menu")
                    clickDialogText(R.string.select_folder)
                    await { AppConfig.defaultBookTreeUri == Uri.fromFile(newDirectory).toString() }
                }
            destination = finalDirectory
            val source =
                File(directory, "menu-share-$id.txt").apply { writeText("MENU SHARED BOOK") }
            launchShare(source, "text/plain").use { scenario ->
                awaitLocalPreview(scenario)
                compose.onNodeWithTag("shared-local-menu").performClick()
                compose.onNodeWithTag("shared-local-directory").performClick()
                screenshot("share-local-preview-save-folder-menu")
                clickDialogText(R.string.select_folder)
                await { AppConfig.defaultBookTreeUri == Uri.fromFile(finalDirectory).toString() }
                assertTrue(finalDirectory.listFiles()!!.isEmpty())
                confirmLocalPreview(scenario)
                val copy = File(finalDirectory, source.name)
                await { appDb.bookDao.has(copy.path) }
                books.add(appDb.bookDao.getBook(copy.path)!!)
                assertArrayEquals(source.readBytes(), copy.readBytes())
            }
            assertEquals(2, requests.get())
            assertEquals("ORIGINAL MENU BOOK", oldFile.readText())
            assertEquals(oldBook, appDb.bookDao.getBook(oldBook.bookUrl))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test(timeout = 120_000)
    fun baseDialogKeepsItsTagForQueuedDuplicateRequestsAndRecreation() {
        val file = File(directory, "dialog-tag-$id.txt").apply { writeText("DIALOG TEST") }
        launchShare(file, "text/plain").use { scenario ->
            awaitLocalPreview(scenario)
            scenario.onActivity { activity ->
                val manager = activity.supportFragmentManager
                val dialog = SharedImportTagTestDialog()
                dialog.show(manager, "retainedTag")
                dialog.show(manager, "retainedTag")
                manager.executePendingTransactions()
                assertSame(dialog, manager.findFragmentByTag("retainedTag"))
                assertEquals(
                    1,
                    manager.fragments.filterIsInstance<SharedImportTagTestDialog>().size,
                )
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val manager = activity.supportFragmentManager
                val dialog = manager.findFragmentByTag("retainedTag") as SharedImportTagTestDialog
                dialog.show(manager, "retainedTag")
                manager.executePendingTransactions()
                assertEquals(
                    1,
                    manager.fragments.filterIsInstance<SharedImportTagTestDialog>().size,
                )
                dialog.dismissNow()
                dialog.show(manager, "retainedTag")
                manager.executePendingTransactions()
                assertSame(dialog, manager.findFragmentByTag("retainedTag"))
                dialog.dismissNow()
            }
        }
    }

    @Test(timeout = 120_000)
    fun archivePreviewsEverySupportedTypeAndOnlyImportsTheSelection() {
        val pdfFile = File(directory, "batch-$id.pdf")
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
            page.canvas.drawColor(Color.WHITE)
            pdf.finishPage(page)
            pdfFile.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
        val contents =
            linkedMapOf(
                "alpha-$id.txt" to "ALPHA $id".toByteArray(),
                "omit-$id.pdf" to pdfFile.readBytes(),
                "nested/gamma-$id.TXT" to "GAMMA $id".toByteArray(),
                "batch-$id.epub" to
                    instrumentation.context.assets.open("issue1074-containers-fragments.epub").use {
                        it.readBytes()
                    },
                pdfFile.name to pdfFile.readBytes(),
                "notes.json" to "{}".toByteArray(),
                "picture.jpg" to byteArrayOf(1, 2, 3),
            )
        val archive = File(directory, "five-books-$id.zip")
        writeArchive(archive, contents)
        val original = archive.readBytes()
        val previousBook = ReadBook.book?.bookUrl
        lateinit var previewCover: File
        lateinit var omittedCover: File
        lateinit var coverBytes: ByteArray
        lateinit var acceptedCover: File
        launchShare(archive, "application/zip").use { scenario ->
            awaitLocalPreview(scenario)
            lateinit var model: FileAssociationViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[FileAssociationViewModel::class.java]
            }
            val items = model.localBookBatch.value!!
            assertEquals(5, items.size)
            assertEquals(
                setOf("txt", "epub", "pdf"),
                items.map { it.file.name.substringAfterLast('.').lowercase() }.toSet(),
            )
            assertEquals(previousBook, ReadBook.book?.bookUrl)
            items.forEach { assertFalse(appDb.bookDao.has(it.preview!!.name, it.preview.author)) }
            val omitted = items.indexOfFirst { it.file.name.startsWith("omit-") }
            previewCover =
                File(checkNotNull(items.single { it.file.name == pdfFile.name }.preview!!.coverUrl))
            omittedCover = File(checkNotNull(items[omitted].preview!!.coverUrl))
            assertTrue(previewCover.length() > 0)
            assertTrue(omittedCover.length() > 0)
            coverBytes = previewCover.readBytes()
            compose.onNodeWithTag("shared-local-list").performScrollToIndex(omitted)
            val omittedBook = items[omitted].preview!!
            val label =
                if (omittedBook.author.isBlank()) omittedBook.name
                else "${omittedBook.name} / ${omittedBook.author}"
            compose.onNodeWithText(label).performClick()
            await { model.selectedLocalBooks.size == 4 }
            assertEquals(4, model.selectedLocalBooks.size)
            scenario.recreate()
            awaitLocalPreview(scenario)
            scenario.onActivity { activity ->
                assertEquals(
                    1,
                    activity.supportFragmentManager.fragments
                        .filterIsInstance<ImportLocalBookDialog>()
                        .size,
                )
            }
            assertEquals(4, model.selectedLocalBooks.size)
            screenshot("share-local-archive-selection")
            val selected = items.filter { it.file.uri in model.selectedLocalBooks }
            confirmLocalPreview(scenario)
            await { model.state.value.session?.phase == AssociationPhase.Finished }
            assertEquals(previousBook, ReadBook.book?.bookUrl)
            selected.forEach { item ->
                val book = appDb.bookDao.getBook(File(directory, "books/${item.file.name}").path)!!
                books.add(book)
                assertEquals(item.preview!!.name, book.name)
                assertArrayEquals(
                    contents.entries.single { File(it.key).name == item.file.name }.value,
                    File(book.bookUrl).readBytes(),
                )
                if (item.file.name == pdfFile.name) {
                    assertEquals(LocalBook.getCoverPath(book), book.coverUrl)
                    acceptedCover = File(book.coverUrl!!)
                    assertNotEquals(previewCover.path, acceptedCover.path)
                }
            }
            assertFalse(
                appDb.bookDao.has(items[omitted].preview!!.name, items[omitted].preview!!.author)
            )
            assertFalse(File(directory, "books/${items[omitted].file.name}").exists())
            assertArrayEquals(original, archive.readBytes())
            assertEquals(File(directory, "books").path, AppConfig.defaultBookTreeUri)
        }
        await { !previewCover.exists() && !omittedCover.exists() }
        assertArrayEquals(coverBytes, acceptedCover.readBytes())
        lateinit var cancelledCover: File
        launchShare(pdfFile, "application/pdf").use { scenario ->
            awaitLocalPreview(scenario)
            scenario.onActivity {
                val preview =
                    ViewModelProvider(it)[FileAssociationViewModel::class.java]
                        .localBookBatch
                        .value!!
                        .single()
                        .preview!!
                cancelledCover = File(preview.coverUrl!!)
                assertTrue(cancelledCover.length() > 0)
                assertFalse(appDb.bookDao.has(preview.name, preview.author))
            }
            compose.onNodeWithTag("shared-local-cancel").assertIsDisplayed().performClick()
        }
        await { !cancelledCover.exists() }
        assertArrayEquals(coverBytes, acceptedCover.readBytes())
        assertArrayEquals(original, archive.readBytes())
    }

    @Test(timeout = 120_000)
    fun multipleSharesKeepAllThreeIdentityConflictsAndOriginalFiles() {
        AppConfig.bookImportFileName = "name='Shared identity $id';author='Shared author';"
        val existingFile = File(directory, "books/one.txt").apply { writeText("EXISTING BOOK") }
        val existing = LocalBook.importFile(Uri.fromFile(existingFile)).also(books::add)
        val missingOriginal =
            Book(
                bookUrl = File(directory, "books/two.txt").path,
                originName = "two.txt",
                name = "Missing original $id",
                author = "Preserved author",
            )
        appDb.bookDao.insert(missingOriginal)
        books.add(missingOriginal)
        val originals =
            listOf("one.txt", "two.txt", "three.txt").mapIndexed { index, name ->
                File(directory, name).apply { writeText("SHARED COPY $index $id") }
            }
        val unsupported = File(directory, "ignore.png").apply { writeBytes(byteArrayOf(1, 2)) }
        val uris = (originals + unsupported).map(::providerUri)
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .setPackage(context.packageName)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData =
            ClipData.newRawUri("books", uris[0]).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        assertTrue(
            context.packageManager.queryIntentActivities(intent, 0).any {
                it.activityInfo.name == FileAssociationActivity::class.java.name
            }
        )
        intent.component = ComponentName(context, FileAssociationActivity::class.java)
        ActivityScenario.launch<FileAssociationActivity>(intent).use { scenario ->
            awaitLocalPreview(scenario)
            lateinit var model: FileAssociationViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[FileAssociationViewModel::class.java]
            }
            val preview = model.localBookBatch.value!!.map { it.preview!! }
            assertEquals(listOf(2, 3, 4).map { "${existing.name} ($it)" }, preview.map { it.name })
            assertEquals(3, preview.size)
            preview.forEach { assertFalse(appDb.bookDao.has(it.name, it.author)) }
            screenshot("share-local-multiple-conflicts")
            confirmLocalPreview(scenario)
            await { model.state.value.session?.phase == AssociationPhase.Finished }
            preview.forEachIndexed { index, item ->
                val copy = appDb.bookDao.getBook(item.name, item.author)!!
                books.add(copy)
                assertNotEquals(existing.bookUrl, copy.bookUrl)
                assertArrayEquals(originals[index].readBytes(), File(copy.bookUrl).readBytes())
                assertTrue(originals[index].isFile)
            }
            assertEquals("EXISTING BOOK", existingFile.readText())
            assertEquals(existing, appDb.bookDao.getBook(existing.bookUrl))
            assertEquals(
                missingOriginal.name,
                appDb.bookDao.getBook(missingOriginal.bookUrl)!!.name,
            )
            assertFalse(File(missingOriginal.bookUrl).exists())
            assertFalse(File(directory, "books/ignore.png").exists())
        }
    }

    @Test(timeout = 120_000)
    fun archiveRejectsBooksMixedWithRecognizedRulesBeforeWritingEitherType() {
        val rule =
            HighlightRule(name = "Mixed archive $id", pattern = id, style = "{\"bold\":true}")
        val archive = File(directory, "mixed-types-$id.zip")
        writeArchive(
            archive,
            linkedMapOf(
                "book-$id.txt" to "BOOK $id".toByteArray(),
                "highlightRule.json" to
                    GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE, listOf(rule)))
                        .toByteArray(),
            ),
        )
        val original = archive.readBytes()
        launchShare(archive, "application/zip").use { scenario ->
            compose
                .onNodeWithText(context.getString(R.string.shared_local_books_mixed_types))
                .assertIsDisplayed()
            scenario.onActivity {
                assertNull(it.supportFragmentManager.findFragmentByTag("sharedLocalBooks"))
                val owner = ViewModelProvider(it)[FileAssociationViewModel::class.java].state.value
                assertTrue(owner.session?.previews.orEmpty().isEmpty())
                assertFalse(owner.busy)
            }
            assertTrue(File(directory, "books").listFiles()!!.isEmpty())
            assertFalse(appDb.highlightRuleDao.all.any { it.uuid == rule.uuid })
            compose.onNodeWithTag("association-loading").assertDoesNotExist()
            screenshot("share-local-mixed-types-rejected")
            clickDialogText(R.string.ok)
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertArrayEquals(original, archive.readBytes())
        }
    }

    @Test(timeout = 120_000)
    fun archiveCombinesSameCategoryJsonIntoItsExistingSelectionPreview() {
        val imported =
            (1..3).map { index ->
                HighlightRule(
                    name = "Archive highlight $index $id",
                    pattern = "$index-$id",
                    style = "{\"bold\":true}",
                )
            }
        rules.addAll(imported)
        val archive = File(directory, "rules-$id.zip")
        writeArchive(
            archive,
            linkedMapOf(
                "unrelated.json" to GSON.toJson(imported.take(2)).toByteArray(),
                "typed.json" to
                    GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE, imported.drop(2)))
                        .toByteArray(),
                "ignored.json" to "{}".toByteArray(),
                "picture.jpg" to byteArrayOf(1, 2),
            ),
        )
        val original = archive.readBytes()
        launchShare(archive, "application/zip").use { scenario ->
            awaitDialog(scenario)
            imported.forEach { rule ->
                compose.onNodeWithText(rule.name).assertIsDisplayed()
                assertFalse(appDb.highlightRuleDao.all.any { it.uuid == rule.uuid })
            }
            screenshot("share-local-archive-json-preview")
            compose.onNodeWithTag("highlight-import-confirm").performClick()
            await {
                imported.all { rule -> appDb.highlightRuleDao.all.any { it.uuid == rule.uuid } }
            }
            imported.forEach { rule ->
                assertEquals(
                    rule.styleObj(),
                    appDb.highlightRuleDao.all.single { it.uuid == rule.uuid }.styleObj(),
                )
            }
            assertArrayEquals(original, archive.readBytes())
        }
    }

    @Test(timeout = 120_000)
    fun unsupportedArchiveReportsTheFormatAndEndsInsteadOfLoadingForever() {
        val archive = File(directory, "no-books-$id.zip")
        writeArchive(
            archive,
            linkedMapOf("readme.md" to "No books".toByteArray(), "data.bin" to byteArrayOf(1)),
        )
        launchShare(archive, "application/zip").use { scenario ->
            lateinit var model: FileAssociationViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[FileAssociationViewModel::class.java]
            }
            await {
                (model.state.value.restoreError ?: model.state.value.session?.error) ==
                    context.getString(R.string.unsupport_archivefile_entry)
            }
            assertNull(model.localBookBatch.value)
            assertTrue(model.state.value.session?.previews.orEmpty().isEmpty())
            assertFalse(model.state.value.busy)
            compose.onNodeWithTag("association-loading").assertDoesNotExist()
            screenshot("share-local-unsupported-archive")
            await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertTrue(archive.isFile)
        }
    }

    @Test(timeout = 120_000)
    fun cancelledPathReturnsToPreviewAndExplicitDefaultIsSharedWithOpenWith() {
        val privateDirectory = File(context.filesDir, "books")
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                    if (intent.component?.className == HandleFileActivity::class.java.name)
                        Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    else null
            }
        instrumentation.addMonitor(monitor)
        try {
            for (firstOpen in listOf(false, true)) for (choice in listOf("dismiss", "picker")) {
                prefs.edit().remove(PreferKey.defaultBookTreeUri).commit()
                // Exercise both directions: SHARE remembers the default for VIEW and vice versa.
                for (round in 0..1) {
                    val open = if (round == 0) firstOpen else !firstOpen
                    val file =
                        File(directory, "private-$firstOpen-$choice-$round-$id.txt").apply {
                            writeText("PRIVATE COPY $choice $id\n".repeat(20))
                        }
                    val copy = File(privateDirectory, file.name)
                    val scenario =
                        if (open) {
                            val intent =
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(providerUri(file), "text/plain")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    .setComponent(
                                        ComponentName(context, FileAssociationActivity::class.java)
                                    )
                            ActivityScenario.launch<FileAssociationActivity>(intent)
                        } else launchShare(file, "text/plain")
                    scenario.use {
                        if (!open) confirmLocalPreview(scenario)
                        if (round == 0) {
                            compose
                                .onNodeWithText(
                                    context.getString(R.string.shared_local_books_storage)
                                )
                                .assertIsDisplayed()
                            if (open)
                                scenario.onActivity {
                                    assertNull(
                                        it.supportFragmentManager.findFragmentByTag(
                                            "sharedLocalBooks"
                                        )
                                    )
                                }
                            if (choice == "dismiss") pressBack()
                            else clickDialogText(R.string.select_folder)
                            awaitLocalPreview(scenario)
                            scenario.onActivity {
                                val model =
                                    ViewModelProvider(it)[FileAssociationViewModel::class.java]
                                assertFalse(model.localBookDestination.value == true)
                                assertFalse(model.choosingLocalBookDirectory)
                                assertFalse(model.importingLocalBooks.value == true)
                            }
                            assertFalse(copy.exists())
                            assertFalse(appDb.bookDao.has(copy.path))
                            assertNull(AppConfig.defaultBookTreeUri)
                            scenario.recreate()
                            confirmLocalPreview(scenario)
                            compose
                                .onNodeWithText(
                                    context.getString(R.string.shared_local_books_storage)
                                )
                                .assertIsDisplayed()
                            screenshot("share-local-explicit-path-$firstOpen-$choice")
                            clickDialogText(R.string.shared_local_books_private)
                        }
                        await { copy.isFile && appDb.bookDao.has(copy.path) }
                        val book = appDb.bookDao.getBook(copy.path)!!.also(books::add)
                        assertEquals(
                            Uri.fromFile(privateDirectory).toString(),
                            AppConfig.defaultBookTreeUri,
                        )
                        assertArrayEquals(file.readBytes(), copy.readBytes())
                        assertTrue(file.isFile)
                        if (open) {
                            awaitReader(book)
                            assertTrue(
                                ReadBook.curTextChapter!!.pages.any {
                                    it.text.contains("PRIVATE COPY $choice")
                                }
                            )
                            closeReaders()
                        }
                        assertTrue(copy.delete())
                    }
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun clickDialogText(resource: Int) {
        compose
            .onNodeWithText(context.getString(resource))
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
    }

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)

    private fun writeArchive(file: File, contents: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            contents.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun awaitLocalPreview(scenario: ActivityScenario<FileAssociationActivity>) {
        var state = ""
        try {
            await {
                var ready = false
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[FileAssociationViewModel::class.java]
                    val fragment =
                        activity.supportFragmentManager.findFragmentByTag("sharedLocalBooks")
                            as? ImportLocalBookDialog
                    val preview = fragment?.model?.state?.value
                    state =
                        "batch=${model.localBookBatch.value?.size}; selected=${model.selectedLocalBooks.size}; " +
                            "pending=${model.pendingLocalBooks.size}; destination=${model.localBookDestination.value}; " +
                            "error=${model.state.value.restoreError ?: model.state.value.session?.error}; fragments=${activity.supportFragmentManager.fragments.map { it.javaClass.simpleName to it.tag }}; " +
                            "view=${fragment?.view}; loaded=${preview?.loaded}; count=${preview?.rows?.size}; " +
                            "focus=${fragment?.dialog?.window?.decorView?.hasWindowFocus()}"
                    ready =
                        model.state.value.loaded &&
                            !model.state.value.busy &&
                            !model.state.value.nativeResultPending &&
                            preview?.loaded == true &&
                            !preview.loading &&
                            !preview.busy &&
                            preview.rows.isNotEmpty() &&
                            fragment.view?.isShown == true &&
                            fragment.dialog?.window?.decorView?.hasWindowFocus() == true
                }
                ready
            }
            assertPreviewRows(scenario)
        } catch (error: AssertionError) {
            screenshot("share-local-preview-failure-$id")
            throw AssertionError(state, error)
        }
    }

    private fun assertPreviewRows(scenario: ActivityScenario<FileAssociationActivity>) {
        var rowCount = 0
        var selectedCount = 0
        var selectableCount = 0
        scenario.onActivity { activity ->
            val pipeline = ViewModelProvider(activity)[FileAssociationViewModel::class.java]
            val dialog =
                activity.supportFragmentManager.findFragmentByTag("sharedLocalBooks")
                    as ImportLocalBookDialog
            rowCount = dialog.model.state.value.rows.size
            selectedCount = dialog.model.state.value.selected.size
            selectableCount = dialog.model.state.value.selectableCount
            assertEquals(pipeline.localBookBatch.value!!.size, rowCount)
            assertEquals(pipeline.selectedLocalBooks.size, selectedCount)
        }
        compose.onNodeWithTag("shared-local-list").assertIsDisplayed()
        compose
            .onNodeWithTag("shared-local-select-all")
            .assertTextContains(
                context.getString(
                    if (selectedCount == selectableCount) R.string.select_cancel_count
                    else R.string.select_all_count,
                    selectedCount,
                    rowCount,
                )
            )
    }

    private fun confirmLocalPreview(scenario: ActivityScenario<FileAssociationActivity>) {
        awaitLocalPreview(scenario)
        compose.onNodeWithTag("shared-local-confirm").performClick()
    }

    private fun openReader(book: Book) {
        instrumentation.runOnMainSync {
            context.startActivity(
                Intent(context, ReadBookActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun launchShare(file: File, mime: String): ActivityScenario<FileAssociationActivity> {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .setPackage(context.packageName)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(file.name, uri)
        assertTrue(
            context.packageManager.queryIntentActivities(intent, 0).any {
                it.activityInfo.name == FileAssociationActivity::class.java.name
            }
        )
        intent.component = ComponentName(context, FileAssociationActivity::class.java)
        return ActivityScenario.launch(intent)
    }

    private fun awaitReader(book: Book) {
        await {
            var readerVisible = false
            instrumentation.runOnMainSync {
                readerVisible =
                    ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<ReadBookActivity>()
                        .any { it.window.decorView.hasWindowFocus() }
            }
            ReadBook.book?.bookUrl == book.bookUrl &&
                ReadBook.curTextChapter?.chapter?.bookUrl == book.bookUrl &&
                ReadBook.curTextChapter?.isCompleted == true &&
                ReadBook.curTextChapter?.pages?.isNotEmpty() == true &&
                readerVisible
        }
    }

    private fun awaitDialog(scenario: ActivityScenario<FileAssociationActivity>) {
        var dialog: DialogFragment? = null
        await {
            // The fragment's window can gain focus before Content initializes its private VM.
            // Confirm real rendered controls before reading that existing VM from its store.
            val highlightRendered =
                compose
                    .onAllNodesWithTag("highlight-import-confirm")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isNotEmpty()
            val replacementRendered =
                compose
                    .onAllNodesWithTag("replace-import-confirm")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isNotEmpty()
            var ready = false
            scenario.onActivity { activity ->
                val owner =
                    ViewModelProvider(activity)[FileAssociationViewModel::class.java].state.value
                dialog =
                    activity.supportFragmentManager.fragments
                        .filterIsInstance<DialogFragment>()
                        .firstOrNull {
                            it.dialog?.isShowing == true &&
                                it.dialog?.window?.decorView?.hasWindowFocus() == true
                        }
                val contentReady =
                    when (val current = dialog) {
                        is ImportHighlightRuleDialog ->
                            highlightRendered &&
                                ViewModelProvider(current)[ImportHighlightRuleViewModel::class.java]
                                    .state
                                    .value
                                    .let { !it.loading && !it.busy && it.items.isNotEmpty() }
                        is ImportReplaceRuleDialog ->
                            replacementRendered &&
                                ViewModelProvider(current)[ImportReplaceRuleViewModel::class.java]
                                    .state
                                    .value
                                    .let { !it.loading && !it.busy && it.items.isNotEmpty() }
                        is AssociationDataImportDialog ->
                            owner.session?.importType in setOf("bookshelf", "backup")
                        else -> false
                    }
                ready = owner.loaded && !owner.busy && !owner.nativeResultPending && contentReady
            }
            ready
        }
        when (dialog) {
            is ImportHighlightRuleDialog ->
                compose
                    .onNodeWithTag("highlight-import-confirm")
                    .assertIsDisplayed()
                    .assertIsEnabled()
            is ImportReplaceRuleDialog ->
                compose
                    .onNodeWithTag("replace-import-confirm")
                    .assertIsDisplayed()
                    .assertIsEnabled()
            is AssociationDataImportDialog ->
                compose
                    .onNodeWithText(context.getString(R.string.ok))
                    .assertIsDisplayed()
                    .assertIsEnabled()
            else -> error("Unexpected import dialog ${dialog?.javaClass?.simpleName}")
        }
    }

    private fun closeReaders() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ReadBookActivity>()
                .forEach { it.finish() }
        }
        compose.waitForIdle()
    }

    private fun await(condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 20000) {
                condition()
            }
            return
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // Preserve the original state diagnostics and failure assertion below.
        }
        assertTrue("Shared import did not reach the expected database/reader state", condition())
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(100, 5000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "ui-regression/$name.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }

    private fun pdfMarkerIsVisible(): Boolean {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return false
        return try {
            (0 until bitmap.height step 4).sumOf { y ->
                (0 until bitmap.width step 4).count { x ->
                    val pixel = bitmap.getPixel(x, y)
                    Color.red(pixel) < 40 &&
                        Color.green(pixel) in 80..120 &&
                        Color.blue(pixel) > 150
                }
            } > 100
        } finally {
            bitmap.recycle()
        }
    }
}

class SharedImportTagTestDialog : BaseComposeDialogFragment() {
    @Composable override fun Content() = Unit
}
