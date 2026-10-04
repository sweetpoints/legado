package io.legado.app.ui.about

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.os.Build
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.testutil.saveSemantics
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.data.entities.replaceBookAfterSourceChange
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.data.entities.saveWithCover
import io.legado.app.data.repository.ReadingHistoryPreference
import io.legado.app.help.book.ReadRecordCoverCache
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.ThemeStorePrefKeys
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadRecordHistoryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = prefs.all
    private val savedSort = LocalConfig.all["readRecordSort"]
    private val savedRecords = appDb.readRecordDao.all
    private val id = UUID.randomUUID().toString()
    private val book =
        Book(bookUrl = "history:$id", name = "History $id", author = "History Author")
    private var scenario: ActivityScenario<ReadRecordActivity>? = null
    private lateinit var cover: File

    @Before
    fun setUp() {
        prefs
            .edit()
            .remove("readRecordSimpleLayout")
            .remove("readRecordUseDays")
            .remove("readRecordShowSeconds")
            .remove("readRecordFixedCard")
            .remove(PreferKey.readRecordCover)
            .remove(PreferKey.readRecordCoverDark)
            .commit()
        LocalConfig.edit().putInt("readRecordSort", 1).commit()
        appDb.readRecordDao.clear()
        cover = File(context.cacheDir, "history-$id.png")
        Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(35, 148, 115))
            cover.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        book.coverUrl = cover.absolutePath
        book.durChapterIndex = 6
        book.durChapterTitle = "Chapter 7: Current chapter"
        book.durChapterPos = 25
        appDb.bookDao.insert(book)
        appDb.readRecordDao.insert(
            ReadRecord(
                deviceId = AppConst.androidId,
                bookName = book.name,
                author = book.author,
                readTime = 25 * 3600_000L,
                lastRead = 1000,
            )
        )
        appDb.readRecordDao.insert(
            ReadRecord(
                deviceId = "remote",
                bookName = "Archived second",
                readTime = 7200_000L,
                lastRead = 500,
            )
        )
        appDb.readRecordDao.insert(
            ReadRecord(
                deviceId = "remote",
                bookName = "Archived third",
                readTime = 3600_000L,
                lastRead = 400,
            )
        )
    }

    @After
    fun tearDown() {
        scenario?.close()
        appDb.bookDao.delete(book)
        appDb.readRecordDao.clear()
        appDb.readRecordDao.insert(*savedRecords.toTypedArray())
        ReadRecordCoverCache.prune()
        cover.delete()
        prefs
            .edit()
            .apply {
                for (key in
                    listOf(
                        "readRecordSimpleLayout",
                        "readRecordUseDays",
                        "readRecordShowSeconds",
                        "readRecordFixedCard",
                    )) {
                    val value = savedPrefs[key]
                    if (value is Boolean) putBoolean(key, value) else remove(key)
                }
                for (key in listOf(PreferKey.readRecordCover, PreferKey.readRecordCoverDark)) {
                    val value = savedPrefs[key]
                    if (value is String) putString(key, value) else remove(key)
                }
            }
            .commit()
        LocalConfig.edit()
            .apply {
                if (savedSort is Int) putInt("readRecordSort", savedSort)
                else remove("readRecordSort")
            }
            .commit()
    }

    @Test
    fun newestDeviceSnapshotAndSearchKeepCorrectTotalDuration() {
        appDb.readRecordDao.insert(
            ReadRecord(
                deviceId = "old",
                bookName = "Same",
                author = "New author",
                readTime = 400,
                lastRead = 100,
                lastChapterTitle = "Old chapter",
                lastChapterIndex = 2,
            ),
            ReadRecord(
                deviceId = "new",
                bookName = "Same",
                author = "New author",
                readTime = 200,
                lastRead = 200,
                lastChapterTitle = "New chapter",
                lastChapterIndex = 8,
                lastChapterPos = 33,
                coverUrl = "cover",
            ),
            ReadRecord(
                deviceId = "new",
                bookName = "Same",
                author = "Other author",
                readTime = 900,
                lastRead = 300,
                lastChapterTitle = "Other chapter",
                lastChapterIndex = 19,
                coverUrl = "other-cover",
            ),
        )
        val record = appDb.readRecordDao.search("New author").single()
        assertEquals(600L, record.readTime)
        assertEquals(200L, record.lastRead)
        assertEquals("New chapter", record.lastChapterTitle)
        assertEquals(8, record.lastChapterIndex)
        assertEquals(33, record.lastChapterPos)
        assertEquals("cover", record.coverUrl)
        assertEquals(
            record,
            appDb.readRecordDao.allShow.single {
                it.bookName == "Same" && it.author == "New author"
            },
        )
        assertEquals(900L, appDb.readRecordDao.search("Other author").single().readTime)
        assertEquals(2, appDb.readRecordDao.search("Same").size)
    }

    @Test
    fun snapshotUsesTheCapturedChapterIndexBeforeBookshelfTitleCatchesUp() {
        appDb.bookChapterDao.insert(
            BookChapter(
                bookUrl = book.bookUrl,
                url = "chapter:$id",
                index = 12,
                title = "Chapter 13: Captured chapter",
            )
        )
        ReadRecord(
                deviceId = AppConst.androidId,
                bookName = book.name,
                author = book.author,
                lastChapterIndex = 12,
                lastChapterTitle = "Stale bookshelf title",
                lastChapterPos = 33,
            )
            .saveWithCover(book)
        val saved = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
        assertEquals(12, saved.lastChapterIndex)
        assertEquals(33, saved.lastChapterPos)
        assertEquals("Chapter 13: Captured chapter", saved.lastChapterTitle)
    }

    @Test
    fun refreshingSnapshotCannotUndoConcurrentDurationUpdatesOrDeletion() {
        val executor = Executors.newSingleThreadExecutor()
        fun refreshDuring(change: () -> Unit) {
            lateinit var refresh: Future<*>
            appDb.runInTransaction {
                val started = CountDownLatch(1)
                refresh = executor.submit {
                    started.countDown()
                    book.saveReadRecordSnapshot()
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                change()
            }
            refresh.get(10, TimeUnit.SECONDS)
        }
        try {
            val latest =
                appDb.readRecordDao
                    .getRecord(AppConst.androidId, book.name, book.author)!!
                    .copy(readTime = 30 * 3600_000L, lastRead = 5000)
            refreshDuring { appDb.readRecordDao.insert(latest) }
            val refreshed =
                appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
            assertEquals(latest.readTime, refreshed.readTime)
            assertEquals(latest.lastRead, refreshed.lastRead)
            assertEquals(book.durChapterTitle, refreshed.lastChapterTitle)

            refreshDuring { appDb.readRecordDao.deleteByBook(book.name, book.author) }
            assertNull(appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun coverDownloadMustDecodeBeforeItReplacesTheOriginalAddress() {
        val invalid =
            File(context.cacheDir, "invalid-cover-$id.html").apply {
                writeText("<html>not an image</html>")
            }
        try {
            val record =
                ReadRecord(
                    deviceId = AppConst.androidId,
                    bookName = book.name,
                    author = book.author,
                    coverUrl = invalid.absolutePath,
                )
            appDb.readRecordDao.insert(record)
            runBlocking { ReadRecordCoverCache.request(record)!!.join() }
            assertEquals(
                invalid.absolutePath,
                appDb.readRecordDao
                    .getRecord(AppConst.androidId, book.name, book.author)!!
                    .coverUrl,
            )
            record.coverUrl = cover.absolutePath
            appDb.readRecordDao.insert(record)
            runBlocking { ReadRecordCoverCache.request(record)!!.join() }
            val saved = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
            assertNotEquals(cover.absolutePath, saved.coverUrl)
            assertArrayEquals(cover.readBytes(), File(saved.coverUrl!!).readBytes())
        } finally {
            invalid.delete()
        }
    }

    @Test
    fun oldPreferencesRestoreSimpleLayoutAndCoversDefaultToExcluded() {
        val directory = File(context.cacheDir, "history-preferences-$id").apply { mkdirs() }
        try {
            AppConfig.readRecordSimpleLayout = false
            AppConfig.readRecordUseDays = true
            AppConfig.readRecordShowSeconds = false
            writePreferenceSnapshot(context, directory.absolutePath, "config") {
                putBoolean("enableReadRecord", true)
            }
            File(directory, "readRecord.json")
                .writeText(
                    GSON.toJson(
                        listOf(
                            ReadRecord(
                                deviceId = "",
                                bookName = book.name,
                                readTime = 30 * 3600_000L,
                                lastRead = 2000,
                                lastChapterTitle = "Restored chapter",
                                lastChapterIndex = 12,
                                lastChapterPos = 44,
                            )
                        )
                    )
                )
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath) }
            assertTrue(AppConfig.readRecordSimpleLayout)
            assertFalse(AppConfig.readRecordUseDays)
            assertTrue(AppConfig.readRecordShowSeconds)
            val restored = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, "")!!
            assertEquals(30 * 3600_000L, restored.readTime)
            assertEquals("Restored chapter", restored.lastChapterTitle)
            assertEquals(12, restored.lastChapterIndex)
            assertEquals(44, restored.lastChapterPos)
            assertNull(appDb.readRecordDao.getRecord("", book.name, ""))
            val knownAuthor =
                appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
            assertEquals(25 * 3600_000L, knownAuthor.readTime)
            assertEquals(1000L, knownAuthor.lastRead)
            assertEquals(
                setOf("", book.author),
                appDb.readRecordDao.allShow
                    .filter { it.bookName == book.name }
                    .map { it.author }
                    .toSet(),
            )
            val previous = BackupConfig.ignoreConfig.remove(BackupConfig.readRecordCoverContentKey)
            try {
                assertFalse(BackupConfig.contentIsEnabled(BackupConfig.readRecordCoverContentKey))
            } finally {
                if (previous != null)
                    BackupConfig.ignoreConfig[BackupConfig.readRecordCoverContentKey] = previous
            }
            writePreferenceSnapshot(context, directory.absolutePath, "config") {
                putBoolean("readRecordShowSeconds", false)
            }
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath) }
            assertFalse(AppConfig.readRecordShowSeconds)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun removingLegacyAuthorNameKeepsTimeAndLatestSnapshotsInBothLayouts() {
        val dao = appDb.readRecordDao
        val removedAuthor = "History Author 精校文字全本"
        val combined = ReadRecordAuthors.merge(book.author, removedAuthor)
        for (simple in listOf(true, false)) {
            AppConfig.readRecordSimpleLayout = simple
            val legacy =
                ReadRecord(
                    deviceId = AppConst.androidId,
                    bookName = book.name,
                    author = combined,
                    readTime = 100,
                    lastRead = 100,
                    lastChapterTitle = "Legacy chapter",
                    lastChapterIndex = 1,
                    lastChapterPos = 7,
                    coverUrl = cover.path,
                )
            val remote =
                legacy.copy(
                    deviceId = "remote",
                    readTime = 300,
                    lastRead = 150,
                    lastChapterTitle = "Remote chapter",
                    lastChapterIndex = 2,
                )
            val known =
                legacy.copy(
                    author = book.author,
                    readTime = 200,
                    lastRead = 200,
                    lastChapterTitle = "Known chapter",
                    lastChapterIndex = 3,
                )
            val other = known.copy(author = "Different author", readTime = 500)
            val unknown = known.copy(author = "", readTime = 700)
            dao.clear()
            dao.insert(legacy, remote, known, other, unknown)
            val total = dao.allTime
            launch()
            await { it.snapshot.rows.size == 4 }
            fun choose() {
                remove(book.name, combined)
                compose.onNodeWithTag("history-author-choice").performClick()
                compose.onNodeWithText(removedAuthor).performClick()
            }
            choose()
            compose.onNodeWithTag("history-cancel").performClick()
            assertEquals(setOf(legacy, remote, known, other, unknown), dao.all.toSet())
            choose()
            screenshot("reading-history-remove-author-confirm-$simple")
            val latest =
                known.copy(
                    lastRead = 300,
                    lastChapterTitle = "Updated while confirming",
                    lastChapterIndex = 9,
                    lastChapterPos = 41,
                )
            dao.insert(latest)
            compose.onNodeWithTag("history-confirm").performClick()
            await { it.snapshot.rows.size == 3 && !it.busy }
            val expected =
                setOf(
                    latest.copy(readTime = 300),
                    remote.copy(author = book.author),
                    other,
                    unknown,
                )
            assertEquals(expected, dao.all.toSet())
            assertEquals(total, dao.allTime)
            assertEquals(600L, dao.allShow.single { it.author == book.author }.readTime)
            dao.removeLegacyAuthor(book.name, combined, removedAuthor)
            dao.removeLegacyAuthor(book.name, book.author, book.author)
            dao.removeLegacyAuthor(book.name, combined, "Not in the record")
            assertEquals(expected, dao.all.toSet())
            scenario!!.close()
            scenario = null
            launch()
            await { it.snapshot.rows.size == 3 }
            compose.onNodeWithTag("history-row-${key(book.name, book.author)}").assertExists()
            assertEquals(expected, dao.all.toSet())
            scenario!!.close()
            scenario = null
        }
    }

    @Test
    fun fixedCardCanScrollWithRowsAndReturnWithoutChangingRowActions() {
        appDb.readRecordDao.insert(
            *(0 until 80)
                .map {
                    ReadRecord(deviceId = "scroll", bookName = "Scroll $it", readTime = it + 1L)
                }
                .toTypedArray()
        )
        AppConfig.readRecordSimpleLayout = false
        launch()
        await { it.snapshot.rows.size == 83 }
        val before = compose.onNodeWithTag("history-summary").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("history-list").performScrollToIndex(30)
        assertEquals(
            before,
            compose.onNodeWithTag("history-summary").fetchSemanticsNode().boundsInRoot,
        )
        preference(ReadingHistoryPreference.Fixed)
        await { !it.preferences.fixed }
        compose.onNodeWithTag("history-list").performScrollToIndex(0)
        compose.onNodeWithTag("history-summary").assertIsDisplayed()
        compose.onNodeWithTag("history-list").performScrollToIndex(30)
        compose.onNodeWithTag("history-summary").assertDoesNotExist()
        scenario!!.recreate()
        await { it.snapshot.rows.size == 83 }
        compose.onNodeWithTag("history-list").performScrollToIndex(0)
        remove(book.name, book.author)
        compose.onNodeWithTag("history-confirm").performClick()
        await { it.snapshot.rows.size == 82 }
        assertNull(appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author))
        assertNotNull(appDb.readRecordDao.getRecord("remote", "Archived second", ""))
        preference(ReadingHistoryPreference.Simple)
        await { it.preferences.simple }
        compose.onNodeWithTag("history-compact-summary").assertIsDisplayed()
        preference(ReadingHistoryPreference.Simple)
        await { !it.preferences.simple }
        preference(ReadingHistoryPreference.Fixed)
        await { it.preferences.fixed }
        compose.onNodeWithTag("history-list").performScrollToIndex(30)
        compose.onNodeWithTag("history-summary").assertIsDisplayed()
        screenshot("reading-history-card-pinned")
    }

    @Test
    fun dayAndNightFallbackCoversKeepRealCoversAndUseWhiteWhenUnset() {
        val nightCover = File(context.cacheDir, "history-night-$id.png")
        val nightColor = Color.rgb(64, 86, 180)
        Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(nightColor)
            nightCover.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val savedMode = prefs.getString(PreferKey.themeMode, null)
        val savedNightMode = AppCompatDelegate.getDefaultNightMode()
        try {
            AppConfig.readRecordSimpleLayout = false
            prefs
                .edit()
                .putString(PreferKey.readRecordCover, cover.path)
                .putString(PreferKey.readRecordCoverDark, nightCover.path)
                .commit()
            for (isNight in listOf(false, true)) {
                night(isNight)
                launch()
                await { it.snapshot.rows.size == 3 }
                awaitColor(
                    "history-cover-${key("Archived second", "")}",
                    if (isNight) nightColor else Color.rgb(35, 148, 115),
                )
                awaitColor(
                    "history-summary-cover-1",
                    if (isNight) nightColor else Color.rgb(35, 148, 115),
                )
                awaitColor("history-cover-${key(book.name, book.author)}", Color.rgb(35, 148, 115))
                screenshot(
                    if (isNight) "reading-history-fallback-night"
                    else "reading-history-fallback-day"
                )
                scenario!!.close()
                scenario = null
            }
            prefs.edit().remove(PreferKey.readRecordCoverDark).commit()
            launch()
            await { it.snapshot.rows.size == 3 }
            awaitColor("history-cover-${key("Archived second", "")}", Color.WHITE)
        } finally {
            scenario?.close()
            scenario = null
            nightCover.delete()
            prefs
                .edit()
                .apply {
                    if (savedMode == null) remove(PreferKey.themeMode)
                    else putString(PreferKey.themeMode, savedMode)
                }
                .commit()
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(savedNightMode) }
        }
    }

    @Test
    fun layoutSwitchAndDayToggleRefreshSummaryAndRows() {
        launch()
        await { it.snapshot.rows.size == 3 }
        compose.onNodeWithTag("history-total").assertTextEquals("28小时")
        preference(ReadingHistoryPreference.Simple)
        await { !it.preferences.simple }
        compose.onNodeWithTag("history-compact-summary").assertDoesNotExist()
        compose
            .onNodeWithTag("history-count")
            .assertTextEquals(context.getString(R.string.read_record_book_count, 3))
        compose
            .onNodeWithTag("history-chapter-${key(book.name, book.author)}", true)
            .assertTextEquals(book.durChapterTitle!!)
        preference(ReadingHistoryPreference.Days)
        await { it.preferences.days }
        compose
            .onNodeWithTag("history-total")
            .assertTextEquals(context.getString(R.string.read_record_total_duration, "1天4小时"))
        compose
            .onNodeWithTag("history-time-${key(book.name, book.author)}", true)
            .assertTextEquals("1天1小时")
        assertRecordLayout(book.name, book.author)
        scenario!!.recreate()
        await { it.snapshot.rows.size == 3 }
        compose
            .onNodeWithTag("history-total")
            .assertTextEquals(context.getString(R.string.read_record_total_duration, "1天4小时"))
        screenshot("reading-history-enhanced")
    }

    @Test
    fun emptyCoversStayWhiteAndDarkCardsRemainDistinguishable() {
        val themePrefs = ThemeStore.prefs(context)
        val backgroundKey = ThemeStorePrefKeys.KEY_BACKGROUND_COLOR
        val savedBackground = themePrefs.all[backgroundKey]
        val savedMode = prefs.getString(PreferKey.themeMode, null)
        val savedNightMode = AppCompatDelegate.getDefaultNightMode()
        try {
            AppConfig.readRecordSimpleLayout = false
            for ((name, background) in
                listOf(
                    "light" to Color.rgb(245, 245, 245),
                    "dark" to Color.rgb(32, 32, 32),
                    "black" to Color.BLACK,
                    "brown" to Color.rgb(52, 39, 34),
                    "blue" to Color.rgb(37, 48, 68),
                    "custom" to Color.rgb(231, 214, 185),
                )) {
                val dark = name !in listOf("light", "custom")
                themePrefs.edit().putInt(backgroundKey, background).commit()
                night(dark)
                launch()
                await { it.snapshot.rows.size == 3 }
                awaitColor("history-cover-${key("Archived second", "")}", Color.WHITE)
                awaitColor("history-summary-cover-1", Color.WHITE)
                awaitColor("history-cover-${key(book.name, book.author)}", Color.rgb(35, 148, 115))
                val image = compose.onNodeWithTag("history-summary").captureToImage().toPixelMap()
                val card = image[8, image.height / 2].toArgb()
                if (dark)
                    for (channel in
                        listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) assertTrue(
                        "Dark card has visible boundary",
                        channel(card) - channel(background) in 12..21,
                    )
                else assertEquals(background, card)
                screenshot("reading-history-covers-$name")
                scenario!!.close()
                scenario = null
            }
        } finally {
            scenario?.close()
            scenario = null
            themePrefs
                .edit()
                .apply {
                    if (savedBackground is Int) putInt(backgroundKey, savedBackground)
                    else remove(backgroundKey)
                }
                .commit()
            prefs
                .edit()
                .apply {
                    if (savedMode == null) remove(PreferKey.themeMode)
                    else putString(PreferKey.themeMode, savedMode)
                }
                .commit()
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(savedNightMode) }
        }
    }

    @Test
    fun secondsToggleUpdatesBothLayoutsWithoutChangingStoredDuration() {
        val record = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
        record.readTime += 123_000L
        appDb.readRecordDao.update(record)
        val original = appDb.readRecordDao.all.toSet()
        launch()
        await { it.snapshot.rows.size == 3 }
        text("time", book.name, book.author, "25小时2分钟3秒")
        preference(ReadingHistoryPreference.Seconds)
        await { !it.preferences.seconds }
        text("time", book.name, book.author, "25小时2分钟")
        preference(ReadingHistoryPreference.Simple)
        await { !it.preferences.simple }
        compose
            .onNodeWithTag("history-total")
            .assertTextEquals(context.getString(R.string.read_record_total_duration, "28小时2分钟"))
        preference(ReadingHistoryPreference.Days)
        await { it.preferences.days }
        scenario!!.recreate()
        await { it.snapshot.rows.size == 3 }
        text("time", book.name, book.author, "1天1小时2分钟")
        preference(ReadingHistoryPreference.Seconds)
        await { it.preferences.seconds }
        text("time", book.name, book.author, "1天1小时2分钟3秒")
        assertEquals(original, appDb.readRecordDao.all.toSet())
    }

    @Test
    fun largeHistoryOpensAndFiltersWithoutWritingBookshelfSnapshots() {
        prefs
            .edit()
            .putString(PreferKey.readRecordCover, cover.path)
            .putString(PreferKey.readRecordCoverDark, cover.path)
            .commit()
        appDb.readRecordDao.insert(
            *(0 until 6372)
                .map { index ->
                    ReadRecord(
                        deviceId = "history-device",
                        bookName = "Archived $id $index",
                        author = "Author $index",
                        readTime = index + 1L,
                        lastRead = index + 1L,
                        lastChapterTitle = "Saved chapter $index",
                        lastChapterIndex = index,
                        lastChapterPos = index % 10,
                    )
                }
                .toTypedArray()
        )
        val before = appDb.readRecordDao.all.toSet()
        AppConfig.readRecordSimpleLayout = false
        val start = SystemClock.elapsedRealtime()
        launch()
        await { it.snapshot.rows.size == 6375 }
        compose.onNodeWithTag("history-row-${key(book.name, book.author)}").assertIsDisplayed()
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue("First history rows took ${elapsed}ms", elapsed < 8000)
        text("chapter", book.name, book.author, book.durChapterTitle!!)
        val total =
            context.getString(
                R.string.read_record_total_duration,
                formatDuring(before.sumOf { it.readTime }),
            )
        compose.onNodeWithTag("history-total").assertTextEquals(total)
        compose.onNodeWithTag("history-search").performTextReplacement(book.name)
        await { it.snapshot.rows.size == 1 }
        compose.onNodeWithTag("history-total").assertTextEquals(total)
        preference(ReadingHistoryPreference.Simple)
        preference(ReadingHistoryPreference.Days)
        compose.onNodeWithTag("history-sort").performClick()
        compose.onNodeWithTag("history-sort-0").performClick()
        await { it.preferences.sort == 0 }
        compose.onNodeWithTag("history-search").performTextClearance()
        await { it.snapshot.rows.size == 6375 }
        assertEquals(before, appDb.readRecordDao.all.toSet())
        println("History first display: rows=6375 elapsedMs=$elapsed")
    }

    @Test
    fun sameNameAuthorsKeepSeparateRowsCoversChaptersAndReaderRoutes() {
        val otherColor = Color.rgb(190, 60, 40)
        val otherCover = File(context.cacheDir, "history-other-$id.png")
        Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(otherColor)
            otherCover.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val other =
            book.copy(
                bookUrl = "history-other:$id",
                author = "Other History Author",
                coverUrl = otherCover.path,
                durChapterTitle = "Other author's chapter",
                durChapterIndex = 20,
                durChapterTime = book.durChapterTime + 1,
            )
        val opened = AtomicReference<Intent?>()
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component?.className != ReadBookActivity::class.java.name)
                        return null
                    opened.set(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
        instrumentation.addMonitor(monitor)
        try {
            appDb.bookDao.insert(other)
            appDb.readRecordDao.clear()
            appDb.readRecordDao.insert(
                ReadRecord(
                    deviceId = AppConst.androidId,
                    bookName = book.name,
                    author = book.author,
                    readTime = 25 * 3600_000L,
                    lastRead = 1000,
                ),
                ReadRecord(
                    deviceId = AppConst.androidId,
                    bookName = other.name,
                    author = other.author,
                    readTime = 12 * 3600_000L,
                    lastRead = 2000,
                ),
            )
            book.saveReadRecordSnapshot()
            other.saveReadRecordSnapshot()
            val firstStored =
                appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
            val otherStored =
                appDb.readRecordDao.getRecord(AppConst.androidId, other.name, other.author)!!
            assertArrayEquals(cover.readBytes(), File(firstStored.coverUrl!!).readBytes())
            assertArrayEquals(otherCover.readBytes(), File(otherStored.coverUrl!!).readBytes())
            assertNotEquals(firstStored.coverUrl, otherStored.coverUrl)
            assertEquals(book.durChapterTitle, firstStored.lastChapterTitle)
            assertEquals(other.durChapterTitle, otherStored.lastChapterTitle)
            AppConfig.readRecordSimpleLayout = false
            launch()
            await { it.snapshot.rows.size == 2 }
            text("chapter", book.name, book.author, book.durChapterTitle!!)
            text("chapter", other.name, other.author, other.durChapterTitle!!)
            awaitColor("history-cover-${key(book.name,book.author)}", Color.rgb(35, 148, 115))
            awaitColor("history-cover-${key(other.name,other.author)}", otherColor)
            text("time", book.name, book.author, "25小时")
            text("time", other.name, other.author, "12小时")
            for (selected in listOf(book, other)) {
                opened.set(null)
                compose
                    .onNodeWithTag("history-row-${key(selected.name,selected.author)}")
                    .performClick()
                compose.waitUntil(10_000) { opened.get() != null }
                assertEquals(selected.bookUrl, opened.get()!!.getStringExtra("bookUrl"))
            }
            screenshot("reading-history-same-name-authors")
            preference(ReadingHistoryPreference.Simple)
            await { it.preferences.simple }
            text(
                "author",
                book.name,
                book.author,
                context.getString(R.string.author_show, book.author),
            )
            text(
                "author",
                other.name,
                other.author,
                context.getString(R.string.author_show, other.author),
            )
            opened.set(null)
            compose.onNodeWithTag("history-row-${key(other.name,other.author)}").performClick()
            compose.waitUntil(10_000) { opened.get() != null }
            assertEquals(other.bookUrl, opened.get()!!.getStringExtra("bookUrl"))
            preference(ReadingHistoryPreference.Simple)
            await { !it.preferences.simple }
            remove(book.name, book.author)
            compose.onNodeWithTag("history-confirm").performClick()
            await { it.snapshot.rows.size == 1 }
            assertNull(appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author))
            assertEquals(
                otherStored,
                appDb.readRecordDao.getRecord(AppConst.androidId, other.name, other.author),
            )
            assertFalse(File(firstStored.coverUrl!!).exists())
            assertTrue(File(otherStored.coverUrl!!).isFile)
            assertArrayEquals(otherCover.readBytes(), File(otherStored.coverUrl!!).readBytes())
        } finally {
            instrumentation.removeMonitor(monitor)
            scenario?.close()
            scenario = null
            appDb.bookDao.delete(other)
            otherCover.delete()
        }
    }

    @Test
    fun deletingBookRetainsOwnedCoverAndDeletingHistoryRemovesOnlyItsCopy() {
        book.delete()
        val stored = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
        val retained = File(stored.coverUrl!!)
        assertNotEquals(cover.path, retained.path)
        assertArrayEquals(cover.readBytes(), retained.readBytes())
        cover.delete()
        assertTrue(retained.isFile)
        AppConfig.readRecordSimpleLayout = false
        launch()
        await { it.snapshot.rows.size == 3 }
        text("chapter", book.name, book.author, book.durChapterTitle!!)
        remove(book.name, book.author)
        compose.onNodeWithTag("history-confirm").performClick()
        await { it.snapshot.rows.size == 2 }
        assertFalse(retained.exists())
        assertTrue(appDb.readRecordDao.all.any { it.bookName == "Archived second" })
    }

    @Test
    fun changingSourceRetainsHistoryBeforeRemovingTheOldBookshelfEntry() {
        val replacement =
            book.copy(
                bookUrl = "history-new:$id",
                coverUrl = null,
                durChapterTitle = "New source chapter",
            )
        val before = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
        try {
            replaceBookAfterSourceChange(book, replacement, emptyList(), clearActiveReader = false)
            assertNull(appDb.bookDao.getBook(book.bookUrl))
            assertNotNull(appDb.bookDao.getBook(replacement.bookUrl))
            val saved = appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)!!
            assertEquals(before.readTime, saved.readTime)
            assertEquals(before.lastRead, saved.lastRead)
            assertEquals(book.durChapterTitle, saved.lastChapterTitle)
            assertEquals(book.durChapterIndex, saved.lastChapterIndex)
            assertEquals(book.durChapterPos, saved.lastChapterPos)
            assertArrayEquals(cover.readBytes(), File(saved.coverUrl!!).readBytes())
            AppConfig.readRecordSimpleLayout = false
            launch()
            await { it.snapshot.rows.size == 3 }
            text("chapter", book.name, book.author, replacement.durChapterTitle!!)
            assertEquals(
                saved,
                appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author),
            )
        } finally {
            appDb.bookDao.delete(replacement)
        }
    }

    @Test
    fun enhancedLayoutFitsNarrowScreenAndLargerText() {
        val originalSize = shell("wm size")
        val originalDensity = shell("wm density")
        fun overrideValue(value: String, name: String): String? =
            Regex("(?m)^Override $name: ([0-9x]+)\\s*$").find(value)?.groupValues?.get(1)
        val sizeOverride = overrideValue(originalSize, "size")
        val densityOverride = overrideValue(originalDensity, "density")
        val physicalSize = checkNotNull(
            Regex("Physical size: ([0-9]+x[0-9]+)").find(originalSize)?.groupValues?.get(1)
        )
        val physicalDimensions = physicalSize.split('x').map(String::toInt)
        val baseSize = sizeOverride ?: physicalSize
        val baseDimensions = baseSize.split('x').map(String::toInt)
        val restoredDensity = (densityOverride ?: checkNotNull(
            Regex("Physical density: ([0-9]+)").find(originalDensity)?.groupValues?.get(1)
        )).toInt()
        val display = checkNotNull(context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY))
        val originalRotation = display.rotation
        val restoredWidth = baseDimensions[if (originalRotation % 2 == 0) 0 else 1]
        val restoredHeight = baseDimensions[if (originalRotation % 2 == 0) 1 else 0]
        val originalFont = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)
        val originallyAutoRotating = Settings.System.getInt(
            context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0,
        ) != 0
        fun awaitWindow(width: Int, height: Int, rotation: Int, density: Int, font: Float) {
            var actual = "activity unavailable"
            try {
                compose.waitUntil(10_000) {
                    var matched = false
                    scenario?.onActivity {
                        val configuration = it.resources.configuration
                        val decor = it.window.decorView
                        val metrics = it.resources.displayMetrics
                        val visible = android.graphics.Rect()
                        decor.getWindowVisibleDisplayFrame(visible)
                        actual = "decor=${decor.width}x${decor.height}, rotation=${decor.display.rotation}, " +
                            "orientation=${configuration.orientation}, density=${configuration.densityDpi}, " +
                            "font=${configuration.fontScale}, metrics=${metrics.widthPixels}x${metrics.heightPixels}, " +
                            "visibleFrame=$visible"
                        matched = decor.width == width && decor.height == height &&
                            decor.display.rotation == rotation && configuration.densityDpi == density &&
                            configuration.fontScale == font &&
                            configuration.orientation == if (height > width)
                                Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                    }
                    matched
                }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError(
                    "Expected window=${width}x$height, rotation=$rotation, density=$density, font=$font; actual $actual",
                    failure,
                )
            }
        }
        try {
            assertTrue(instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0))
            shell("wm size 720x1280")
            shell("wm density 360")
            shell("settings put system font_scale 1.3")
            // Android 8 clamps each forced dimension to twice its initial physical dimension.
            val width =
                if (Build.VERSION.SDK_INT == 26) minOf(720, physicalDimensions[0] * 2) else 720
            val height =
                if (Build.VERSION.SDK_INT == 26) minOf(1280, physicalDimensions[1] * 2) else 1280
            assertEquals(
                "Forced size must match the platform's exact bounds",
                "${width}x$height",
                overrideValue(shell("wm size"), "size"),
            )
            AppConfig.readRecordSimpleLayout = false
            launch()
            awaitWindow(width, height, UiAutomation.ROTATION_FREEZE_0, 360, 1.3f)
            await { it.snapshot.rows.size == 3 }
            assertRecordLayout(book.name, book.author)
            reveal("history-summary")
            compose.onNodeWithTag("history-summary").assertIsDisplayed()
            screenshot("reading-history-narrow-large-text", composeContent = true)
        } finally {
            try {
                try {
                    shell("settings put system font_scale $originalFont")
                } finally {
                    try {
                        shell("wm density ${densityOverride ?: "reset"}")
                    } finally {
                        try {
                            shell("wm size ${sizeOverride ?: "reset"}")
                        } finally {
                            assertTrue(instrumentation.uiAutomation.setRotation(originalRotation))
                        }
                    }
                }
                if (scenario != null) awaitWindow(
                    restoredWidth, restoredHeight, originalRotation, restoredDensity, originalFont,
                )
            } finally {
                try {
                    if (originallyAutoRotating)
                        assertTrue(instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE))
                } finally {
                    scenario?.close()
                    scenario = null
                }
            }
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(ReadRecordActivity::class.java)
    }

    private fun key(name: String, author: String) = "${name.length}:$name$author"

    private fun await(predicate: (ReadingHistoryState) -> Boolean) {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            var ready = false
            scenario!!.onActivity {
                ready = !it.viewModel.state.value.loading && predicate(it.viewModel.state.value)
            }
            ready
        }
        compose.waitForIdle()
    }

    private fun preference(field: ReadingHistoryPreference) {
        compose.onNodeWithTag("history-menu").performClick()
        compose.onNodeWithTag("history-pref-${field.name}").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun remove(name: String, author: String) {
        reveal("history-delete-${key(name,author)}")
        compose.onNodeWithTag("history-delete-${key(name,author)}", true).performClick()
    }

    private fun text(field: String, name: String, author: String, value: String) {
        reveal("history-$field-${key(name,author)}")
        compose.onNodeWithTag("history-$field-${key(name,author)}", true).assertTextEquals(value)
    }

    private fun reveal(tag: String) {
        if (runCatching { compose.onNodeWithTag(tag, true).assertIsDisplayed() }.isFailure) {
            compose.onNodeWithTag("history-list", true).performScrollToNode(hasTestTag(tag))
        }
    }

    private fun awaitColor(tag: String, color: Int) {
        reveal(tag)
        var observed: Int? = null
        try { compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            runCatching {
                    val image = compose.onNodeWithTag(tag, true).captureToImage().toPixelMap()
                    // The middle summary cover is partly covered by the top book cover.
                    val divisor = if (tag == "history-summary-cover-1") 4 else 2
                    observed = image[image.width / divisor, image.height / divisor].toArgb()
                    observed == color
                }
                .getOrDefault(false)
        } } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            val artifact = "history-cover-failure-$id"
            runCatching { compose.saveSemantics(context, artifact) }
            runCatching {
                val directory = File(context.getExternalFilesDir(null), "ui-regression").apply { mkdirs() }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(cover.path, options)
                var request = "fixture row absent"
                scenario!!.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    val fixture = state.snapshot.rows.find { it.key == key(book.name, book.author) }
                    request = "loading=${state.loading} currentMatchesFixture=${fixture?.cover?.current == cover.path} " +
                        "currentPresent=${!fixture?.cover?.current.isNullOrBlank()} snapshotPresent=${!fixture?.cover?.snapshot.isNullOrBlank()} " +
                        "onlyWifi=${fixture?.cover?.onlyWifi} fallbackPresent=${!state.preferences.fallback.isNullOrBlank()}"
                }
                File(directory, "$artifact.txt").writeText(
                    "tag=$tag expected=$color observed=$observed fixtureExists=${cover.isFile} " +
                        "fixtureBytes=${cover.length()} decoded=${options.outWidth}x${options.outHeight}; $request"
                )
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    try { File(directory, "$artifact.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
            }
            throw AssertionError("Cover $tag expected=$color actual=$observed", error)
        }
    }

    private fun night(value: Boolean) {
        prefs.edit().putString(PreferKey.themeMode, if (value) "2" else "1").commit()
        instrumentation.runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(
                if (value) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun assertRecordLayout(name: String, author: String) {
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-row-${key(name,author)}"))
        val fields =
            listOf("title", "author", "chapter", "time", "date").map {
                compose
                    .onNodeWithTag("history-$it-${key(name,author)}", true)
                    .getUnclippedBoundsInRoot()
            }
        fields.zipWithNext().forEach { (a, b) ->
            assertTrue("Author/chapter/time fields do not overlap: $a then $b; all=$fields", a.bottom <= b.top)
        }
        assertTrue(fields.all { it.right > it.left && it.bottom > it.top })
        val cover =
            compose
                .onNodeWithTag("history-cover-${key(name,author)}", true)
                .getUnclippedBoundsInRoot()
        assertTrue(cover.right <= fields.first().left)
        fun font(field: String): Float {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithTag("history-$field-${key(name,author)}", true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.fontSize.value
        }
        assertTrue(font("title") > font("author"))
        assertTrue(font("time") > font("date"))
        listOf("title", "author", "chapter", "time", "date").forEach { field ->
            val tag = "history-$field-${key(name, author)}"
            compose.onNodeWithTag(tag, true).performScrollTo().assertIsDisplayed()
        }
    }

    private fun shell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        instrumentation.waitForIdleSync()
        return output
    }

    private fun screenshot(name: String, composeContent: Boolean = false) {
        instrumentation.waitForIdleSync()
        // The narrow-layout artifact captures the Compose content, excluding system bars.
        // Other artifacts retain the whole display, including confirmation dialog windows.
        val bitmap = if (composeContent) {
            compose.onRoot().captureToImage().asAndroidBitmap()
        } else {
            checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        }
        try {
            assertTrue("Screenshot must contain pixels", bitmap.width > 0 && bitmap.height > 0)
            val dir = File(context.getExternalFilesDir("ui-regression"), "").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
