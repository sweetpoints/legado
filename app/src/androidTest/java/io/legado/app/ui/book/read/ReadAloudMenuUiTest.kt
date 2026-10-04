package io.legado.app.ui.book.read

import io.legado.app.ci.lazyItem
import android.content.Context
import io.legado.app.testutil.saveSemantics
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.IntentAction
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.Restore
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.TTSReadAloudService
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudControlsDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real reader gestures; stop tests run the production service with its speech engine shut down. */
@RunWith(AndroidJUnit4::class)
class ReadAloudMenuUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedMenuHelp = LocalConfig.all["readMenuHelpVersion"]
    private val savedPreferences =
        listOf(
                PreferKey.readAloudControlsPause,
                PreferKey.readAloudControlsRealtime,
                PreferKey.readAloudControlsPosition,
                PreferKey.readAloudControlsAutoHide,
                PreferKey.readAloudFollowManualPage,
                PreferKey.readAloudControlsDrag,
                PreferKey.readAloudControlsDock,
                PreferKey.readAloudControlsWidth,
                PreferKey.readAloudControlsX,
                PreferKey.readAloudControlsY,
                PreferKey.readAloudWakeLock,
                PreferKey.ttsTimer,
                PreferKey.readAloudStart,
                PreferKey.preDownloadNum,
                PreferKey.cronet,
                PreferKey.readAloudByPage,
            )
            .associateWith { prefs.all[it] }
    private val savedRunning = BaseReadAloudService.isRun
    private val savedPaused = BaseReadAloudService.pause
    private val savedFollowing = BaseReadAloudService.followReadAloudPosition
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var book: Book? = null
    private var textFile: File? = null
    private var lastAloudMismatch = ""
    private var lastReaderGeometry = ""
    private var serviceStarted = false
    private var speechServer: NanoHTTPD? = null
    private var speechSource: BookSource? = null
    private var speechChapters = emptyList<BookChapter>()
    private val notificationPermission = "android.permission.POST_NOTIFICATIONS"
    private val wasBatteryExempt =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    @Before
    fun setUp() {
        compose.waitUntil(30_000) {
            compose.mainClock.advanceTimeByFrame()
            (context.applicationContext as io.legado.app.App).initialization.isCompleted
        }
        prefs
            .edit()
            .putBoolean(PreferKey.readAloudControlsPause, true)
            .putBoolean(PreferKey.readAloudControlsRealtime, false)
            .putBoolean(PreferKey.readAloudControlsPosition, true)
            .putBoolean(PreferKey.readAloudControlsAutoHide, false)
            .putBoolean(PreferKey.readAloudFollowManualPage, false)
            .commit()
        LocalConfig.edit().putInt("readMenuHelpVersion", 1).commit()
        val file =
            File.createTempFile("aloud-menu-", ".txt", context.cacheDir).also { textFile = it }
        file.writeText(
            (0..60).joinToString("\n") {
                "Reader content line $it for the playback menu regression."
            }
        )
        val fixture =
            Book(
                    bookUrl = file.absolutePath,
                    originName = file.name,
                    name = file.name,
                    charset = "UTF-8",
                    type = BookType.local or BookType.text,
                    totalChapterNum = 1,
                )
                .apply { setPageAnim(PageAnim.noAnim) }
        book = fixture
        appDb.bookDao.insert(fixture)
        appDb.bookChapterDao.insert(
            BookChapter(
                bookUrl = fixture.bookUrl,
                url = "aloud-menu-chapter",
                title = "Playback controls",
                start = 0L,
                end = file.length(),
            )
        )
        scenario =
            ActivityScenario.launch(
                Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", fixture.bookUrl)
            )
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments
                .filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.dismiss() }
        }
        await("reader content") {
            ReadBook.book?.bookUrl == fixture.bookUrl &&
                ReadBook.curTextChapter?.isCompleted == true &&
                ReadBook.curTextChapter?.chapter?.bookUrl == fixture.bookUrl &&
                it.findViewById<ReadView>(R.id.read_view).curPage.textPage.textChapter ===
                    ReadBook.curTextChapter &&
                !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage &&
                it.bottomDialog == 0
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        // Stop even services started indirectly by a control or a late framework callback.
        context.stopService(Intent(context, TTSReadAloudService::class.java))
        compose.waitUntil(30_000) {
            compose.mainClock.advanceTimeByFrame()
            readAloudService() == null
        }
        if (serviceStarted && !wasBatteryExempt) shell("dumpsys deviceidle whitelist -${context.packageName}")
        instrumentation.runOnMainSync {
            playbackFlag("isRun", savedRunning)
            playbackFlag("pause", savedPaused)
            if (savedFollowing) BaseReadAloudService.restoreReadAloudFollow()
            else BaseReadAloudService.detachReadAloudFollow()
        }
        speechServer?.stop()
        speechSource?.let { appDb.bookSourceDao.delete(it) }
        book?.let {
            speechChapters.forEach { chapter -> BookHelp.delContent(it, chapter) }
            appDb.bookChapterDao.delByBook(it.bookUrl)
            appDb.bookDao.delete(it)
        }
        textFile?.delete()
        TextFile.clear()
        LocalConfig.edit()
            .apply {
                if (savedMenuHelp == null) remove("readMenuHelpVersion")
                else putInt("readMenuHelpVersion", savedMenuHelp as Int)
            }
            .commit()
        prefs
            .edit()
            .apply {
                savedPreferences.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
            }
            .commit()
    }

    @Test
    fun fixedPlayingControlLongPressStopsTheService() =
        verifyLongPressStops(paused = false, movable = false, width = 288)

    @Test
    fun fixedPausedControlLongPressStopsTheService() =
        verifyLongPressStops(paused = true, movable = false, width = 288)

    @Test
    fun movablePlayingControlLongPressStopsTheService() =
        verifyLongPressStops(paused = false, movable = true, width = 85)

    @Test
    fun movablePausedControlLongPressStopsTheService() =
        verifyLongPressStops(paused = true, movable = true, width = 85)

    private fun startReadAloudService(
        paused: Boolean,
        movable: Boolean = false,
        width: Int = 288,
    ): TTSReadAloudService {
        serviceStarted = true
        // Grant for this disposable instrumentation session; revocation kills the target process.
        shell("pm grant ${context.packageName} $notificationPermission")
        shell("dumpsys deviceidle whitelist +${context.packageName}")
        scenario!!.onActivity { activity ->
            prefs
                .edit()
                .putBoolean(PreferKey.readAloudControlsDrag, movable)
                .putBoolean(PreferKey.readAloudControlsDock, false)
                .putInt(PreferKey.readAloudControlsWidth, width)
                .putFloat(PreferKey.readAloudControlsX, .5f)
                .putFloat(PreferKey.readAloudControlsY, .7f)
                .putBoolean(PreferKey.readAloudWakeLock, false)
                .putInt(PreferKey.ttsTimer, 0)
                .commit()
            ReadBook.book!!.setTtsEngine("")
            ReadAloud.upReadAloudClass()
            activity.startService(
                Intent(activity, TTSReadAloudService::class.java).setAction(IntentAction.pause)
            )
        }
        await("real service starts") { readAloudService() != null && BaseReadAloudService.isRun }
        var service: TTSReadAloudService? = null
        var commandSession = 0L
        scenario!!.onActivity { activity ->
            service = checkNotNull(readAloudService())
            // Keep the real service lifecycle/commands; voice availability is outside this gesture
            // test.
            service.clearTTS()
            commandSession = speechSession(service)[1] as Long
            if (paused) ReadAloud.pause(activity) else ReadAloud.resume(activity)
            activity.showReadAloudControls()
        }
        await("pause control and requested playback state") {
            BaseReadAloudService.isRun &&
                BaseReadAloudService.pause == paused &&
                (speechSession(checkNotNull(service))[1] as Long) != commandSession &&
                it.readAloudControlsVisible
        }
        val startedService = checkNotNull(service)
        // Preparation consumes a completed reader chapter; finish pending size-triggered
        // replacements before deliberately clearing and preparing the fixture queue.
        awaitReaderGeometry()
        // Settle the initial preparation before tests replace the audio endpoint. An engine
        // that initialized during startup may already have enqueued its first play command.
        var beforePreparation = 0L
        scenario!!.onActivity { activity ->
            beforePreparation = (BaseReadAloudService::class.java.getDeclaredField("readAloudGeneration")
                .apply { isAccessible = true }.get(startedService) as AtomicLong).get()
            startedService.textChapter = null
            startedService.contentList = emptyList()
            ReadAloud.play(activity, play = false, pageIndex = ReadBook.durPageIndex)
        }
        await("initial service preparation and callbacks finish") {
            val generation = (BaseReadAloudService::class.java.getDeclaredField("readAloudGeneration")
                .apply { isAccessible = true }.get(startedService) as AtomicLong).get()
            val preparation = BaseReadAloudService::class.java.getDeclaredField("readAloudJob")
                .apply { isAccessible = true }.get(startedService) as? io.legado.app.help.coroutine.Coroutine<*>
            generation > beforePreparation && preparation?.isCompleted == true &&
                ReadBook.curTextChapter?.let { BaseReadAloudService.hasPreparedSpeechContent(it) } == true &&
                startedService.textChapter?.chapter?.bookUrl == ReadBook.book?.bookUrl &&
                startedService.textChapter?.chapter?.index == ReadBook.durChapterIndex &&
                ReadAloud.readAloudChapterIndex == ReadBook.durChapterIndex &&
                startedService.readAloudNumber == ReadAloud.readAloudChapterStart &&
                startedService.contentList.isNotEmpty()
        }
        return startedService
    }

    private fun awaitReaderGeometry() {
        // ChapterProvider schedules real height changes before posting a new layout request.
        // Compose idle alone does not cover this delayed work and its IO layout consumer.
        val pendingSize = ChapterProvider::class.java.getDeclaredField("upViewSizeRunnable")
            .apply { isAccessible = true }
        await("reader geometry and its actual layout job finish") { activity ->
            val view = activity.findViewById<ReadView>(R.id.read_view)
            val content = view.curPage.findViewById<ContentTextView>(R.id.content_text_view)
            val jobs = ReadBook::class.java.getDeclaredField("chapterLoadingJobs")
                .apply { isAccessible = true }.get(ReadBook) as Map<*, *>
            val job = jobs[ReadBook.durChapterIndex] as? io.legado.app.help.coroutine.Coroutine<*>
            val sizePending = pendingSize.get(ChapterProvider) != null
            lastReaderGeometry = "pendingSize=$sizePending, content=${content.width}x${content.height}, " +
                "provider=${ChapterProvider.viewWidth}x${ChapterProvider.viewHeight}, " +
                "boundChapter=${System.identityHashCode(view.curPage.textPage.textChapter)}, " +
                "currentChapter=${System.identityHashCode(ReadBook.curTextChapter)}, " +
                "completed=${ReadBook.curTextChapter?.isCompleted}, jobPresent=${job != null}, " +
                "jobActive=${job?.isActive}, jobCompleted=${job?.isCompleted}, jobCancelled=${job?.isCancelled}, " +
                "jobKeys=${jobs.keys}"
            !sizePending &&
                content.width > 0 && content.height > 0 &&
                content.width == ChapterProvider.viewWidth && content.height == ChapterProvider.viewHeight &&
                ReadBook.curTextChapter?.isCompleted == true &&
                view.curPage.textPage.textChapter === ReadBook.curTextChapter && job?.isCompleted == true
        }
    }

    private fun verifyLongPressStops(paused: Boolean, movable: Boolean, width: Int) {
        val service = startReadAloudService(paused, movable, width)
        compose.onNodeWithTag("reader-aloud-pause").performClick()
        await("short tap changes pause state") {
            BaseReadAloudService.isRun && BaseReadAloudService.pause != paused
        }
        compose.onNodeWithTag("reader-aloud-pause").performClick()
        await("second tap restores pause state") {
            BaseReadAloudService.isRun && BaseReadAloudService.pause == paused
        }
        if (movable) {
            var beforeY = 0f
            scenario!!.onActivity {
                beforeY = it.readAloudControlsBounds.y
            }
            // A slow drag lasts beyond the long-press timeout and must not stop or toggle playback.
            compose.onNodeWithTag("reader-aloud-pause").performTouchInput {
                swipe(center, center.copy(y = center.y - 96.dpToPx()), durationMillis = 1000)
            }
            scenario!!.onActivity {
                assertTrue(
                    "Drag moves the control",
                    it.readAloudControlsBounds.y < beforeY - 48.dpToPx(),
                )
                assertTrue("Dragging must keep the service alive", BaseReadAloudService.isRun)
                assertEquals(
                    "Dragging must not toggle playback",
                    paused,
                    BaseReadAloudService.pause,
                )
                assertTrue(
                    "Drag stores the new position",
                    prefs.getFloat(PreferKey.readAloudControlsY, .7f) < .7f,
                )
            }
        }
        val label = "aloud-stop-paused-$paused-movable-$movable-width-$width"
        screenshot("$label-before")
        try {
            compose.onNodeWithTag("reader-aloud-pause").performTouchInput { longClick() }
            await("long press destroys service (paused=$paused, movable=$movable)", 5000) {
                !BaseReadAloudService.isRun && readAloudService() == null
            }
            scenario!!.onActivity {
                assertEquals(Lifecycle.State.DESTROYED, service.lifecycle.currentState)
                assertTrue("Stopped service remains paused", BaseReadAloudService.pause)
                assertFalse(
                    "Stopped controls disappear",
                    it.readAloudControlsVisible,
                )
            }
        } finally {
            screenshot("$label-after")
            File(context.getExternalFilesDir("ui-regression"), "$label-state.txt")
                .writeText(
                    "width=$width, shortTapToggled=true, shortTapRestored=true, running=${BaseReadAloudService.isRun}, paused=${BaseReadAloudService.pause}, lifecycle=${service.lifecycle.currentState}"
                )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readAloudService(): TTSReadAloudService? {
        val services =
            LifecycleHelp::class
                .java
                .getDeclaredField("services")
                .apply { isAccessible = true }
                .get(LifecycleHelp) as List<WeakReference<*>>
        return services
            .mapNotNull { it.get() }
            .filterIsInstance<TTSReadAloudService>()
            .singleOrNull()
    }

    private fun shell(command: String) =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }

    @Test
    fun returningFromAloudDialogKeepsControlsHiddenUntilMainMenuCloses() {
        for (paused in listOf(false, true)) {
            scenario!!.onActivity { activity ->
                playbackFlag("isRun", true)
                playbackFlag("pause", paused)
                BaseReadAloudService.restoreReadAloudFollow()
                activity.showReadAloudControls()
            }
            await("pause control visible") {
                it.readAloudControlsVisible
            }
            scenario!!.onActivity { it.showReadAloudDialog() }
            await("aloud dialog visible") { it.bottomDialog == 1 }
            scenario!!.onActivity {
                assertFalse(it.readAloudControlsVisible)
            }
            compose.onNodeWithTag("read-aloud-main-menu").performClick()
            await("returned to main menu") {
                it.bottomDialog == 0 && it.readMenu.isVisible
            }
            screenshot("aloud-main-menu-paused-$paused")
            scenario!!.onActivity {
                assertFalse(
                    "Playback controls must remain hidden over the main menu",
                    it.readAloudControlsVisible,
                )
                it.readMenu.runMenuOut(anim = false)
            }
            await("controls return after menu closes") {
                it.readAloudControlsVisible
            }
        }
    }

    @Test
    fun independentSwitchesDefaultOnAndPreserveExplicitPauseOff() {
        prefs
            .edit()
            .remove(PreferKey.readAloudControlsRealtime)
            .remove(PreferKey.readAloudControlsPause)
            .remove(PreferKey.readAloudControlsPosition)
            .commit()
        scenario!!.onActivity {
            ReadAloudControlsDialog().showNow(it.supportFragmentManager, "controls-defaults")
        }
        compose
            .onNodeWithTag("aloud-controls-switch-${PreferKey.readAloudControlsRealtime}")
            .assertExists()
        compose
            .onNodeWithTag("aloud-controls-switch-${PreferKey.readAloudControlsPause}")
            .assertExists()
        compose
            .onNodeWithTag("aloud-controls-switch-${PreferKey.readAloudControlsPosition}")
            .assertExists()
        assertTrue(prefs.getBoolean(PreferKey.readAloudControlsRealtime, false))
        assertTrue(prefs.getBoolean(PreferKey.readAloudControlsPause, false))
        assertTrue(prefs.getBoolean(PreferKey.readAloudControlsPosition, false))
        val positions =
            listOf(
                    PreferKey.readAloudControlsRealtime,
                    PreferKey.readAloudControlsPause,
                    PreferKey.readAloudControlsPosition,
                )
                .map {
                    compose
                        .onNodeWithTag("aloud-controls-switch-$it")
                        .fetchSemanticsNode()
                        .boundsInRoot
                        .top
                }
        assertTrue(positions[0] < positions[1] && positions[1] < positions[2])
        screenshot("aloud-independent-switches")
        compose
            .onNodeWithTag("aloud-controls-switch-${PreferKey.readAloudControlsPause}")
            .performClick()
        dismissDialogWithBack("aloud-controls-switch-${PreferKey.readAloudControlsPause}")
        scenario!!.onActivity {
            ReadAloudControlsDialog().showNow(it.supportFragmentManager, "controls-reopen")
        }
        assertFalse(
            "Opening settings must retain explicit false",
            prefs.getBoolean(PreferKey.readAloudControlsPause, true),
        )
        dismissDialogWithBack("aloud-controls-switch-${PreferKey.readAloudControlsPause}")
        scenario!!.onActivity {
            playbackFlag("isRun", true)
            BaseReadAloudService.restoreReadAloudFollow()
            it.showReadAloudControls()
        }
        await("pause switch hides the attached control") {
            !it.readAloudControlsVisible
        }
        scenario!!.onActivity { BaseReadAloudService.detachReadAloudFollow() }
        await("position control is independent of pause switch") {
            it.readAloudControlsVisible
        }
        prefs
            .edit()
            .putBoolean(PreferKey.readAloudControlsPosition, false)
            .putBoolean(PreferKey.readAloudControlsPause, true)
            .commit()
        await("position switch hides the detached control") {
            !it.readAloudControlsVisible
        }
        scenario!!.onActivity { BaseReadAloudService.restoreReadAloudFollow() }
        await("pause control is independent of position switch") {
            it.readAloudControlsVisible
        }
    }

    @Test
    fun returningToLiveSpeechRestoresFollowButDisabledRealtimeKeepsManualReturn() {
        val service = startReadAloudService(paused = false)
        scenario!!.onActivity {
            val chapter = checkNotNull(ReadBook.curTextChapter)
            assertTrue(chapter.pageSize >= 3)
            val speechStart =
                chapter.getPage(0)!!.lines.first { line -> !line.isTitle }.chapterPosition + 1
            BaseReadAloudService.updateReadAloudChapterIndex(ReadBook.durChapterIndex)
            service.upTtsProgress(speechStart)
        }
        await("service progress highlights the actual first page") {
            ReadBook.durPageIndex == 0 && ReadBook.curTextChapter!!.getPage(0)!!.hasReadAloudSpan
        }
        for (paused in listOf(false, true)) {
            scenario!!.onActivity { if (paused) ReadAloud.pause(it) else ReadAloud.resume(it) }
            await("requested playback state") { BaseReadAloudService.pause == paused }
            prefs.edit().putBoolean(PreferKey.readAloudControlsRealtime, true).commit()
            swipePage(next = true)
            await("manual departure stays detached after navigation (paused=$paused)") {
                ReadBook.durPageIndex == 1 &&
                    !ReadAloud.followReadAloudPosition &&
                    it.readAloudControlsVisible
            }
            prefs.edit().putBoolean(PreferKey.readAloudControlsPause, false).commit()
            swipePage(next = false)
            await("return restores follow and highlight without overriding pause switch") {
                ReadBook.durPageIndex == 0 &&
                    ReadAloud.followReadAloudPosition &&
                    ReadBook.curTextChapter!!.getPage(0)!!.hasReadAloudSpan &&
                    !it.readAloudControlsVisible
            }
            assertEquals(
                "Restoring follow must not change playback state",
                paused,
                BaseReadAloudService.pause,
            )
            prefs.edit().putBoolean(PreferKey.readAloudControlsPause, true).commit()
            await("pause control reappears on its own switch") {
                it.readAloudControlsVisible
            }
            screenshot("aloud-realtime-return-paused-$paused")
        }
        prefs.edit().putBoolean(PreferKey.readAloudControlsRealtime, false).commit()
        swipePage(next = true)
        await("leave with realtime off") {
            ReadBook.durPageIndex == 1 && !ReadAloud.followReadAloudPosition
        }
        swipePage(next = false)
        await("realtime off keeps manual return at the speech page") {
            ReadBook.durPageIndex == 0 &&
                !ReadAloud.followReadAloudPosition &&
                it.readAloudControlsVisible
        }
        screenshot("aloud-realtime-off-manual-return")
        compose.onNodeWithTag("reader-aloud-back").performClick()
        await("original position action still restores follow") {
            ReadAloud.followReadAloudPosition &&
                ReadBook.curTextChapter!!.getPage(0)!!.hasReadAloudSpan
        }
        prefs.edit().putBoolean(PreferKey.readAloudControlsRealtime, true).commit()
        swipePage(next = true)
        await("reader is ahead of the speech cursor") {
            ReadBook.durPageIndex == 1 && !ReadAloud.followReadAloudPosition
        }
        scenario!!.onActivity {
            val nextSpeechStart =
                ReadBook.curTextChapter!!.getPage(1)!!.lines.first().chapterPosition + 1
            service.upTtsProgress(nextSpeechStart)
        }
        await("speech progress catches the displayed page and restores following") {
            ReadBook.durPageIndex == 1 &&
                ReadAloud.followReadAloudPosition &&
                ReadBook.curTextChapter!!.getPage(1)!!.hasReadAloudSpan
        }
        assertTrue("Paused cursor updates must not start playback", BaseReadAloudService.pause)
    }

    @Test
    fun initialSpeechChoiceUsesComposeAndSurvivesSettingsBackup() {
        prefs.edit().remove(PreferKey.readAloudStart).commit()
        assertTrue(AppConfig.readAloudStartAtSentence)
        scenario!!.onActivity {
            ReadAloudConfigDialog().showNow(it.supportFragmentManager, "aloud-start-config")
        }
        compose.lazyItem("read-aloud-settings-list", "read-aloud-start").assertExists()
        compose.lazyItem("read-aloud-settings-list", "read-aloud-controls").assertExists()
        compose.lazyItem("read-aloud-settings-list", "read-aloud-start").performClick()
        compose.onNodeWithTag("read-aloud-start-sentence").assertIsSelected()
        screenshot("aloud-start-options-default-sentence")
        compose.onNodeWithTag("read-aloud-start-page").performClick()
        assertFalse(AppConfig.readAloudStartAtSentence)
        compose
            .onNodeWithTag("read-aloud-start")
            .assertTextContains(context.getString(R.string.read_aloud_start_page))
        screenshot("aloud-start-page-choice")
        dismissDialogWithBack("read-aloud-settings-list")
        val directory = File(context.cacheDir, "aloud-start-backup-${System.nanoTime()}")
        val unpacked = File(directory, "unpacked").apply { mkdirs() }
        val savedIgnore = HashMap(BackupConfig.ignoreConfig)
        val savedLatest = prefs.all[PreferKey.onlyLatestBackup]
        try {
            BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
            BackupConfig.ignoreConfig[BackupConfig.settingContentKey] = false
            prefs.edit().putBoolean(PreferKey.onlyLatestBackup, true).commit()
            runBlocking(Dispatchers.IO) {
                Backup.backupLocked(context, directory.path, uploadWebDav = false)
            }
            val archive = directory.listFiles()!!.single { it.extension == "zip" }
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry("config.xml") ?: error("Settings missing from real backup")
                File(unpacked, "config.xml").outputStream().use { output ->
                    zip.getInputStream(entry).copyTo(output)
                }
            }
            prefs.edit().putString(PreferKey.readAloudStart, "sentence").commit()
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(unpacked.path) }
            assertFalse(
                "Real settings restore preserves page start",
                AppConfig.readAloudStartAtSentence,
            )
            scenario!!.onActivity {
                ReadAloudConfigDialog().showNow(it.supportFragmentManager, "aloud-start-restored")
            }
            compose.lazyItem("read-aloud-settings-list", "read-aloud-start")
                .assertTextContains(context.getString(R.string.read_aloud_start_page))
                .performClick()
            compose.onNodeWithTag("read-aloud-start-page").assertIsSelected()
            screenshot("aloud-start-options-restored-page")
            compose.onNodeWithTag("read-aloud-start-sentence").performClick()
            assertTrue(AppConfig.readAloudStartAtSentence)
            screenshot("aloud-start-sentence-choice")
            dismissDialogWithBack("read-aloud-settings-list")
        } finally {
            BackupConfig.ignoreConfig.clear()
            BackupConfig.ignoreConfig.putAll(savedIgnore)
            prefs
                .edit()
                .apply {
                    if (savedLatest == null) remove(PreferKey.onlyLatestBackup)
                    else putBoolean(PreferKey.onlyLatestBackup, savedLatest as Boolean)
                }
                .commit()
            directory.deleteRecursively()
        }
    }

    @Test
    fun clearedTtsEngineCannotRestartReplacementFromAStaleInitializationCallback() {
        val service = startReadAloudService(paused = true)
        val recorder = RecordingSpeech(context)
        fun field(name: String) = TTSReadAloudService::class.java.getDeclaredField(name).apply { isAccessible = true }
        val oldGeneration = (field("initializationGeneration").get(service) as AtomicLong).get()
        val queueStarted = AtomicBoolean()
        val oldQueue = io.legado.app.help.coroutine.Coroutine.async<Unit> {
            queueStarted.set(true)
            kotlinx.coroutines.awaitCancellation()
        }
        await("old queue job starts before engine cleanup") { queueStarted.get() }
        try {
            scenario!!.onActivity {
                field("speakJob").set(service, oldQueue)
                service.clearTTS()
                assertTrue("Engine cleanup cancels the old queue job", oldQueue.isCancelled)
                assertNull("Engine cleanup drops the old queue job", field("speakJob").get(service))
                field("textToSpeech").set(service, recorder)
                val session = (field("playbackSessionId").get(service) as AtomicLong).get()
                TTSReadAloudService::class.java.getDeclaredMethod(
                    "handleTtsInitialization", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }.invoke(service, oldGeneration, TextToSpeech.SUCCESS)
                assertFalse(field("ttsInitFinish").getBoolean(service))
                assertTrue(recorder.calls.isEmpty())
                assertEquals(session, (field("playbackSessionId").get(service) as AtomicLong).get())
                assertTrue(BaseReadAloudService.pause)
                assertSame(recorder, field("textToSpeech").get(service))
                val currentGeneration = (field("initializationGeneration").get(service) as AtomicLong).get()
                TTSReadAloudService::class.java.getDeclaredMethod(
                    "handleTtsInitialization", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }.invoke(service, currentGeneration, TextToSpeech.SUCCESS)
                assertTrue("Current engine can finish initialization while paused", field("ttsInitFinish").getBoolean(service))
                assertTrue("Initialization cannot queue speech after an explicit pause", recorder.calls.isEmpty())
                assertTrue(BaseReadAloudService.pause)
                assertEquals("Paused initialization preserves the speech session", session,
                    (field("playbackSessionId").get(service) as AtomicLong).get())
                field("ttsInitFinish").setBoolean(service, false)
                service.play()
                assertTrue("Explicit play waits for the engine", field("playPendingInitialization").getBoolean(service))
                assertTrue("No speech can be queued before initialization", recorder.calls.isEmpty())
                TTSReadAloudService::class.java.getDeclaredMethod(
                    "handleTtsInitialization", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }.invoke(service, currentGeneration, TextToSpeech.SUCCESS)
            }
            await("explicit play resumes after the current engine finishes initialization") {
                !BaseReadAloudService.pause && recorder.calls.isNotEmpty()
            }
            for (playAfterPreparation in listOf(false, true)) {
                awaitReaderGeometry()
                var requestedPosition = 0
                scenario!!.onActivity {
                    service.clearTTS()
                    recorder.calls.clear()
                    field("textToSpeech").set(service, recorder)
                    if (playAfterPreparation) service.resumeReadAloud() else service.pauseReadAloud()
                    service.textChapter = null
                    service.contentList = emptyList()
                    requestedPosition = ReadBook.curTextChapter!!.getReadLength(1)
                    BaseReadAloudService::class.java.getDeclaredMethod(
                        "newReadAloud", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
                        .invoke(service, playAfterPreparation, 1, 0, false)
                    // The real IO job must return to Main to commit, which this callback currently
                    // occupies. This holds actual preparation without a timing delay or fake job.
                    assertTrue("Actual preparation awaits its Main commit", service.isReadAloudPreparing)
                    val session = (field("playbackSessionId").get(service) as AtomicLong).get()
                    val generation = (field("initializationGeneration").get(service) as AtomicLong).get()
                    val initialize = TTSReadAloudService::class.java.getDeclaredMethod(
                        "handleTtsInitialization", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
                    initialize.invoke(service, generation, TextToSpeech.SUCCESS)
                    initialize.invoke(service, generation, TextToSpeech.SUCCESS)
                    assertTrue(field("ttsInitFinish").getBoolean(service))
                    assertFalse(field("playPendingInitialization").getBoolean(service))
                    assertTrue("Initialization cannot queue old content during preparation", recorder.calls.isEmpty())
                    assertEquals("Initialization cannot replace the pending cursor/session", session,
                        (field("playbackSessionId").get(service) as AtomicLong).get())
                }
                await("prepared cursor commits with its requested play=$playAfterPreparation") {
                    !service.isReadAloudPreparing && service.readAloudNumber == requestedPosition &&
                        BaseReadAloudService.pause == !playAfterPreparation &&
                        if (playAfterPreparation) recorder.calls.isNotEmpty() else recorder.calls.isEmpty()
                }
                if (playAfterPreparation) assertEquals(requestedPosition, recorder.calls.first().position)
            }
        } finally {
            scenario!!.onActivity { service.clearTTS() }
            recorder.shutdown()
        }
    }

    @Test
    fun completedLayoutCannotOverwriteANewerExplicitSpeechRequest() {
        val service = startReadAloudService(paused = false)
        awaitReaderGeometry()
        val recorder = RecordingSpeech(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val layoutFinished = AtomicBoolean()
        val held = AtomicBoolean()
        val originalCallback = checkNotNull(ReadBook.callBack)
        try {
            scenario!!.onActivity {
                ReadBook.callBack = object : ReadBook.CallBack by originalCallback {
                    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
                        originalCallback.onLayoutPageCompleted(index, page)
                        if (index == 1 && held.compareAndSet(false, true)) {
                            check(Looper.myLooper() != Looper.getMainLooper())
                            entered.countDown()
                            check(release.await(30, TimeUnit.SECONDS))
                        }
                    }
                }
                ReadBook.loadContent(resetPageOffset = false) { layoutFinished.set(true) }
            }
            await("new reader layout pauses before its playback continuation") {
                entered.count == 0L && ReadBook.curTextChapter?.isCompleted == true &&
                    ReadBook.curTextChapter!!.pageSize > 2
            }
            var requestedPosition = 0
            var heldLayoutJob: io.legado.app.help.coroutine.Coroutine<*>? = null
            scenario!!.onActivity { activity ->
                val jobs = ReadBook::class.java.getDeclaredField("chapterLoadingJobs")
                    .apply { isAccessible = true }.get(ReadBook) as Map<*, *>
                heldLayoutJob = checkNotNull(jobs[ReadBook.durChapterIndex] as?
                    io.legado.app.help.coroutine.Coroutine<*>)
                service.clearTTS()
                TTSReadAloudService::class.java.getDeclaredField("textToSpeech")
                    .apply { isAccessible = true }.set(service, recorder)
                TTSReadAloudService::class.java.getDeclaredField("ttsInitFinish")
                    .apply { isAccessible = true }.setBoolean(service, true)
                requestedPosition = ReadBook.curTextChapter!!.getReadLength(1)
                ReadAloud.play(activity, pageIndex = 1)
            }
            await("explicit request queues speech from the selected page") {
                service.textChapter === ReadBook.curTextChapter &&
                    service.readAloudNumber == requestedPosition && recorder.calls.isNotEmpty() &&
                    recorder.calls.last().last &&
                    recorder.calls.last().id.split(':')[2].toInt() == service.contentList.lastIndex
            }
            val session = speechSession(service)
            val requestGeneration = ReadAloud.playbackRequestGeneration
            val queuedCalls = synchronized(recorder.calls) { recorder.calls.toList() }
            val listener = TTSReadAloudService::class.java.getDeclaredField("ttsUtteranceListener")
                .apply { isAccessible = true }.get(service) as UtteranceProgressListener
            listener.onStart(queuedCalls.first().id)
            await("queued speech highlights the new layout before completion resumes") {
                exactAloudStart(requestedPosition) && ReadBook.durChapterPos == requestedPosition
            }
            release.countDown()
            await("held layout completion finishes after the explicit speech request") {
                assertFalse("The held layout must execute its continuation rather than be cancelled",
                    checkNotNull(heldLayoutJob).isCancelled)
                layoutFinished.get() && checkNotNull(heldLayoutJob).isCompleted
            }
            assertEquals("Layout completion preserves the newer request", requestGeneration,
                ReadAloud.playbackRequestGeneration)
            assertEquals("Layout completion cannot restart the prepared speech session", session,
                speechSession(service))
            assertEquals("Layout completion cannot flush or duplicate queued speech", queuedCalls,
                synchronized(recorder.calls) { recorder.calls.toList() })
            await("layout completion retains the current speech highlight") {
                exactAloudStart(requestedPosition) &&
                    it.findViewById<ReadView>(R.id.read_view).curPage.textPage ===
                        ReadBook.curTextChapter!!.getPageByReadPos(requestedPosition)
            }

            // A real text edit is not a geometry change and must replace the old queue.
            val file = checkNotNull(textFile)
            val fixture = checkNotNull(book)
            val changedParagraph = "Actual changed chapter content must replace the old speech queue."
            file.appendText("\n$changedParagraph")
            val chapter = checkNotNull(appDb.bookChapterDao.getChapter(fixture.bookUrl, 0))
            appDb.bookChapterDao.insert(chapter.copy(end = file.length()))
            val changedLoaded = AtomicBoolean()
            val beforeChangedGeneration = ReadAloud.playbackRequestGeneration
            scenario!!.onActivity {
                ReadBook.callBack = originalCallback
                TextFile.clear()
                ReadBook.clearTextChapter()
                ReadBook.loadContent(resetPageOffset = false) { changedLoaded.set(true) }
            }
            val storedChapter = checkNotNull(appDb.bookChapterDao.getChapter(fixture.bookUrl, 0))
            assertEquals("The chapter includes the appended file bytes", file.length(), storedChapter.end)
            assertTrue("The real local reader sees the appended paragraph",
                checkNotNull(BookHelp.getContent(fixture, storedChapter)).contains(changedParagraph))
            await("changed chapter layout finishes before queue verification") {
                changedLoaded.get() && ReadBook.curTextChapter?.isCompleted == true
            }
            val changedLayout = checkNotNull(ReadBook.curTextChapter)
            assertTrue("The actual completed layout contains the changed text",
                changedLayout.getNeedReadAloud(0, false, 0).contains(changedParagraph))
            await("changed chapter content replaces the prepared speech queue " +
                "(requestBefore=$beforeChangedGeneration, requestAfter=${ReadAloud.playbackRequestGeneration}, " +
                "preparedEquivalent=${BaseReadAloudService.hasPreparedSpeechContent(changedLayout)})") {
                // A later geometry layout may replace the visible TextChapter after the changed
                // text has prepared its new queue. Compare the exact prepared book/chapter/text.
                ReadBook.curTextChapter?.let { BaseReadAloudService.hasPreparedSpeechContent(it) } == true &&
                    service.textChapter?.chapter?.bookUrl == fixture.bookUrl &&
                    service.textChapter?.chapter?.index == ReadBook.durChapterIndex &&
                    service.contentList.any { it.contains(changedParagraph) } &&
                    speechSession(service) != session && recorder.calls.size > queuedCalls.size &&
                    recorder.calls.last().last &&
                    recorder.calls.last().id.split(':')[2].toInt() == service.contentList.lastIndex
            }
            val changedCalls = synchronized(recorder.calls) { recorder.calls.drop(queuedCalls.size) }
            assertEquals("Changed content flushes the previous queue", TextToSpeech.QUEUE_FLUSH,
                changedCalls.first().mode)
            assertTrue(changedCalls.joinToString("") { it.text }.contains(changedParagraph))
        } finally {
            release.countDown()
            scenario!!.onActivity {
                ReadBook.callBack = originalCallback
                service.clearTTS()
            }
            recorder.shutdown()
        }
    }

    @Test
    fun initialCursorAndQueuedPageBoundariesPreserveExactHighlightsWithoutRestart() {
        val file = checkNotNull(textFile)
        file.writeText(
            "先读完的句子。" + "当前句子需要连续阅读并且跨过多个页面".repeat(180) + "。\n" + "下一段也要保持原来的朗读顺序。".repeat(10)
        )
        val fixture = checkNotNull(book)
        appDb.bookChapterDao.insert(
            BookChapter(
                bookUrl = fixture.bookUrl,
                url = "aloud-menu-chapter",
                title = "Playback controls",
                start = 0L,
                end = file.length(),
            )
        )
        val paragraphLoaded = AtomicBoolean()
        scenario!!.onActivity {
            TextFile.clear()
            ReadBook.clearTextChapter()
            ReadBook.loadContent(resetPageOffset = true) { paragraphLoaded.set(true) }
        }
        await("long paragraph spans actual reader pages") {
            paragraphLoaded.get() && ReadBook.curTextChapter?.isCompleted == true &&
                ReadBook.curTextChapter!!.pageSize > 3 &&
                it.findViewById<ReadView>(R.id.read_view).curPage.textPage.textChapter ===
                    ReadBook.curTextChapter
        }
        val service = startReadAloudService(paused = false)
        val recorder = RecordingSpeech(context)
        fun field(name: String) =
            TTSReadAloudService::class.java.getDeclaredField(name).apply { isAccessible = true }
        scenario!!.onActivity {
            field("textToSpeech").set(service, recorder)
            field("ttsInitFinish").setBoolean(service, true)
        }
        val listener = field("ttsUtteranceListener").get(service) as UtteranceProgressListener
        var previousId: String? = null
        try {
            for (splitByPage in listOf(false, true)) for (mode in listOf("sentence", "page")) {
                prefs
                    .edit()
                    .putString(PreferKey.readAloudStart, mode)
                    .putBoolean(PreferKey.readAloudByPage, splitByPage)
                    .commit()
                awaitReaderGeometry()
                recorder.calls.clear()
                var expected = 0
                var requested = 0
                scenario!!.onActivity { activity ->
                    val chapter = ReadBook.curTextChapter!!
                    requested = chapter.getReadLength(1)
                    val paragraph = chapter.paragraphs.first { requested in it.chapterIndices }
                    expected =
                        if (mode == "page") requested
                        else
                            paragraph.chapterPosition +
                                io.legado.app.service.findReadAloudSentenceStart(
                                    paragraph.text,
                                    requested - paragraph.chapterPosition,
                                )
                    ReadAloud.play(
                        activity,
                        pageIndex = 1,
                        rewindToSentenceStart = AppConfig.readAloudStartAtSentence,
                    )
                }
                await("real TTS preparation and queued page segments") {
                    service.readAloudNumber == expected &&
                        recorder.calls.size >= 3 &&
                        recorder.calls.last().last &&
                        recorder.calls.last().id.split(':')[2].toInt() ==
                            service.contentList.lastIndex
                }
                val calls = synchronized(recorder.calls) { recorder.calls.toList() }
                previousId?.let { staleId ->
                    listener.onStart(staleId)
                    listener.onRangeStart(staleId, 123, 124, 0)
                    compose.waitForIdle()
                    assertEquals(
                        "Old queued callbacks cannot move the new session",
                        expected,
                        ReadAloud.readAloudChapterStart,
                    )
                }
                previousId = calls.first().id
                assertEquals(expected, calls.first().position)
                assertEquals(
                    "Prepared page matches the queued layout: expectedPosition=$expected, " +
                        "requested=$requested, sameChapter=${service.textChapter === ReadBook.curTextChapter}, " +
                        "readerComplete=${ReadBook.curTextChapter?.isCompleted}, " +
                        "readerPages=${ReadBook.curTextChapter?.pages?.map { it.chapterPosition }}, " +
                        "servicePages=${service.textChapter?.pages?.map { it.chapterPosition }}",
                    checkNotNull(service.textChapter).getPageIndexByCharIndex(expected),
                    service.pageIndex,
                )
                awaitReaderGeometry()
                scenario!!.onActivity {
                    assertTrue("The visible layout retains the exact prepared speech content",
                        BaseReadAloudService.hasPreparedSpeechContent(checkNotNull(ReadBook.curTextChapter)))
                }
                assertEquals(TextToSpeech.QUEUE_FLUSH, calls.first().mode)
                assertTrue(calls.drop(1).all { it.mode == TextToSpeech.QUEUE_ADD })
                val expectedQueued =
                    service.contentList
                        .drop(service.nowSpeak)
                        .mapIndexed { index, text ->
                            if (index == 0) text.substring(service.paragraphStartPos) else text
                        }
                        .joinToString("")
                assertEquals(
                    "Page splitting neither repeats nor drops spoken characters",
                    expectedQueued,
                    calls.joinToString("") { it.text },
                )
                val session = (field("playbackSessionId").get(service) as AtomicLong).get()
                val stops = recorder.stops
                listener.onStart(calls.first().id)
                await("initial highlight begins exactly at the prepared speech position") {
                    ReadAloud.readAloudChapterStart == expected &&
                        ReadBook.durChapterPos == expected &&
                        exactAloudStart(expected)
                }
                val samePagePosition = expected + 3
                listener.onRangeStart(calls.first().id, 3, 4, 0)
                await(
                    "a range on the same page updates the real cursor and highlighted characters"
                ) {
                    ReadAloud.readAloudChapterStart == samePagePosition &&
                        exactAloudStart(samePagePosition)
                }
                screenshot("aloud-start-$mode-split-$splitByPage-exact-character")
                val next = calls[1]
                assertEquals(
                    "Only explicit page segmentation ends this speech paragraph",
                    splitByPage,
                    calls.first().last,
                )
                val paragraphBefore = service.nowSpeak
                listener.onDone(calls.first().id)
                await("queued completion callback updates the current paragraph") {
                    service.nowSpeak == paragraphBefore + if (splitByPage) 1 else 0
                }
                assertEquals(
                    "Only a final page chunk advances the paragraph",
                    paragraphBefore + if (splitByPage) 1 else 0,
                    service.nowSpeak,
                )
                // A range-less engine reports onStart for the already queued next page.
                listener.onStart(next.id)
                await("range-less engine follows the next queued page at its actual start") {
                    ReadAloud.readAloudChapterStart == next.position &&
                        ReadBook.durChapterPos == next.position &&
                        ReadBook.durPageIndex ==
                            ReadBook.curTextChapter!!.getPageIndexByCharIndex(next.position) &&
                        exactAloudStart(next.position)
                }
                assertEquals(
                    "Page following does not stop the audio endpoint",
                    stops,
                    recorder.stops,
                )
                assertEquals(
                    "Page following preserves the queued speech session",
                    session,
                    (field("playbackSessionId").get(service) as AtomicLong).get(),
                )
                assertFalse(BaseReadAloudService.pause)
                screenshot("aloud-start-$mode-split-$splitByPage-next-page")
                File(
                        context.getExternalFilesDir("ui-regression"),
                        "aloud-start-$mode-split-$splitByPage-queue.txt",
                    )
                    .writeText(
                        "requested=$requested, actual=$expected, session=$session, stops=$stops\n" +
                            calls.joinToString("\n") {
                                "${it.id}: mode=${it.mode}, length=${it.text.length}"
                            }
                    )
                val pageBeforeSwipe = ReadBook.durPageIndex
                val downTime = SystemClock.uptimeMillis()
                fun touch(view: ReadView, action: Int, x: Float) {
                    MotionEvent.obtain(
                            downTime,
                            SystemClock.uptimeMillis(),
                            action,
                            view.width * x,
                            view.height * .4f,
                            0,
                        )
                        .also {
                            view.dispatchTouchEvent(it)
                            it.recycle()
                        }
                }
                scenario!!.onActivity {
                    touch(it.findViewById(R.id.read_view), MotionEvent.ACTION_DOWN, .8f)
                }
                listener.onRangeStart(next.id, 3, 4, 0)
                await("same-page speech callback completes while the pointer remains down") {
                    ReadAloud.readAloudChapterStart == next.position + 3 &&
                        it.findViewById<ReadView>(R.id.read_view).isTouching
                }
                assertEquals(next.position + 3, ReadAloud.readAloudChapterStart)
                scenario!!.onActivity {
                    val view = it.findViewById<ReadView>(R.id.read_view)
                    touch(view, MotionEvent.ACTION_MOVE, .5f)
                    touch(view, MotionEvent.ACTION_MOVE, .2f)
                    touch(view, MotionEvent.ACTION_UP, .2f)
                }
                await("a same-page range during touch-down does not swallow the manual page turn") {
                    ReadBook.durPageIndex == pageBeforeSwipe + 1 &&
                        !ReadAloud.followReadAloudPosition
                }
            }
        } finally {
            scenario!!.onActivity { service.clearTTS() }
            recorder.shutdown()
        }
    }

    private fun exactAloudStart(position: Int): Boolean {
        val chapter = ReadBook.curTextChapter ?: return false
        val page = chapter.getPageByReadPos(position) ?: return false
        val line =
            page.lines.firstOrNull {
                position >= it.chapterPosition && position < it.chapterPosition + it.charSize
            } ?: return false
        if (!page.hasReadAloudSpan || !line.isReadAloud) return false
        lastAloudMismatch = "pageStart=${page.chapterPosition}, lineStart=${line.chapterPosition}, " +
            "priorLines=${page.lines.takeWhile { it !== line }.map { "${it.chapterPosition}:${it.isReadAloud}" }}, " +
            "columns=${line.columns.map { column -> "${column.positionLength}:${(column as? TextBaseColumn)?.isReadAloud}" }}"
        var offset = line.chapterPosition
        return page.lines.takeWhile { it !== line }.none { it.isReadAloud } &&
            line.columns.all { column ->
                val end = offset + column.positionLength
                val correct = column !is TextBaseColumn || column.isReadAloud == (end > position)
                offset = end
                correct
            }
    }

    private data class SpokenCall(val text: String, val mode: Int, val id: String) {
        val position
            get() = id.substringBeforeLast(':').substringAfterLast(':').toInt()

        val last
            get() = id.substringAfterLast(':').toBoolean()
    }

    private class RecordingSpeech(context: Context) : TextToSpeech(context, null) {
        val calls = Collections.synchronizedList(mutableListOf<SpokenCall>())
        @Volatile var stops = 0

        override fun speak(
            text: CharSequence,
            queueMode: Int,
            params: Bundle?,
            utteranceId: String?,
        ): Int {
            calls.add(SpokenCall(text.toString(), queueMode, checkNotNull(utteranceId)))
            return SUCCESS
        }

        override fun stop(): Int {
            stops++
            return SUCCESS
        }
    }

    @Test
    fun crossChapterReturnPreservesTheLiveSessionWithCachedAndDownloadedText() =
        verifyCrossChapterReturn(awaitLoad = false)

    @Test
    fun awaitingChapterLayoutCannotRestartSpeechWhenTheReaderCatchesUp() =
        verifyCrossChapterReturn(awaitLoad = true)

    private fun verifyCrossChapterReturn(awaitLoad: Boolean) {
        scenario!!.close()
        val requests = AtomicInteger()
        val body =
            (0..240).joinToString("\n") {
                "Paragraph $it has several sentences. The current utterance must continue. 阅读进度保持不变。"
            }
        val server =
            object : NanoHTTPD("127.0.0.1", 0) {
                    override fun serve(session: IHTTPSession): Response {
                        requests.incrementAndGet()
                        return newFixedLengthResponse(Response.Status.OK, "text/plain", body)
                            .apply { addHeader("Cache-Control", "no-store") }
                    }
                }
                .also {
                    speechServer = it
                    it.start()
                }
        val base = "http://127.0.0.1:${server.listeningPort}"
        val source =
            BookSource(bookSourceUrl = "$base/source", bookSourceName = "Speech return fixture")
                .also {
                    it.getContentRule().content = "@js:result"
                    speechSource = it
                }
        val previousBook = checkNotNull(book)
        appDb.bookChapterDao.delByBook(previousBook.bookUrl)
        appDb.bookDao.delete(previousBook)
        val fixture =
            previousBook
                .copy(
                    bookUrl = "$base/book",
                    tocUrl = "$base/toc",
                    origin = source.bookSourceUrl,
                    type = BookType.text,
                    totalChapterNum = 3,
                    durChapterIndex = 1,
                    durChapterPos = 0,
                )
                .apply {
                    setUseReplaceRule(false)
                    setReSegment(false)
                }
        book = fixture
        val chapters =
            (0..2)
                .map { index ->
                    BookChapter(
                        bookUrl = fixture.bookUrl,
                        url = "$base/chapter/$index",
                        index = index,
                        title = "Speech chapter $index",
                        baseUrl = base,
                    )
                }
                .also { speechChapters = it }
        prefs
            .edit()
            .putBoolean(PreferKey.cronet, false)
            .putInt(PreferKey.preDownloadNum, 0)
            .putBoolean(PreferKey.readAloudByPage, false)
            .commit()
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(fixture)
        appDb.bookChapterDao.delByBook(fixture.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        chapters.forEach { BookHelp.saveText(fixture, it, body) }
        scenario =
            ActivityScenario.launch(
                Intent(context, ReadBookActivity::class.java)
                    .putExtra("bookUrl", fixture.bookUrl)
                    .putExtra("inBookshelf", true)
            )
        await("three-chapter speech fixture") {
            ReadBook.durChapterIndex == 1 &&
                ReadBook.curTextChapter?.isCompleted == true &&
                ReadBook.prevTextChapter?.isCompleted == true &&
                ReadBook.nextTextChapter?.isCompleted == true &&
                it.findViewById<ReadView>(R.id.read_view).curPage.textPage.textChapter ===
                    ReadBook.curTextChapter
        }
        val service = startReadAloudService(paused = false)
        for (download in listOf(false, true)) for (paused in listOf(false, true)) {
            val realtime = awaitLoad || !paused
            prefs.edit().putBoolean(PreferKey.readAloudControlsRealtime, realtime).commit()
            var speechStart = 0
            scenario!!.onActivity { activity ->
                val chapter = checkNotNull(ReadBook.curTextChapter)
                assertTrue(chapter.pageSize > 3)
                speechStart = chapter.getPage(1)!!.lines[2].chapterPosition + 2
                ReadAloud.play(
                    activity,
                    play = !paused,
                    pageIndex = 1,
                    startPos = speechStart - chapter.getReadLength(1),
                )
            }
            await("real service prepares the middle of the speaking page") {
                service.textChapter === ReadBook.curTextChapter &&
                    ReadAloud.readAloudChapterStart == speechStart
            }
            val beforePlaybackCommand = speechSession(service)[1]
            scenario!!.onActivity { activity ->
                if (paused) ReadAloud.pause(activity) else ReadAloud.resume(activity)
            }
            await("real pause/resume command finishes before the session snapshot") {
                BaseReadAloudService.pause == paused &&
                    speechSession(service)[1] != beforePlaybackCommand
            }
            scenario!!.onActivity { activity ->
                service.upTtsProgress(speechStart)
                activity.backToSpeakingPosition()
            }
            await("speaking cursor and requested state before departure") {
                BaseReadAloudService.pause == paused &&
                    ReadBook.durPageIndex == 1 &&
                    ReadAloud.followReadAloudPosition &&
                    ReadBook.curTextChapter!!.getPage(1)!!.hasReadAloudSpan
            }
            val session = speechSession(service)
            val expectedPosition = speechStart + 3
            scenario!!.onActivity {
                if (paused) ReadBook.moveToPrevChapter(true, toLast = false)
                else ReadBook.moveToNextChapter(true)
            }
            await("manual chapter departure keeps the service cursor") {
                ReadBook.durChapterIndex == (if (paused) 0 else 2) &&
                    ReadBook.curTextChapter?.isCompleted == true &&
                    !ReadAloud.followReadAloudPosition &&
                    it.readAloudControlsVisible
            }
            assertEquals(session, speechSession(service))
            if (download) BookHelp.delContent(fixture, chapters[1])
            val requestCount = requests.get()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val held = AtomicBoolean()
            val originalCallback = checkNotNull(ReadBook.callBack)
            var awaiting: Deferred<Unit>? = null
            scenario!!.onActivity {
                ReadBook.callBack =
                    object : ReadBook.CallBack by originalCallback {
                        override fun onLayoutPageCompleted(index: Int, page: TextPage) {
                            originalCallback.onLayoutPageCompleted(index, page)
                            if (
                                page.chapterIndex == 1 &&
                                    index == 1 &&
                                    held.compareAndSet(false, true)
                            ) {
                                check(Looper.myLooper() != Looper.getMainLooper())
                                entered.countDown()
                                check(release.await(15, TimeUnit.SECONDS))
                            }
                        }

                        override fun contentLoadFinish() {
                            originalCallback.contentLoadFinish()
                            if (ReadBook.durChapterIndex == 1) completed.countDown()
                        }
                    }
            }
            try {
                if (awaitLoad) {
                    // The await API is also used by reader navigation. Exercise its real layout
                    // consumer.
                    scenario!!.onActivity {
                        ReadBook.clearTextChapter()
                        ReadBook.durChapterIndex = 1
                        ReadBook.durChapterPos = speechStart
                    }
                    awaiting =
                        CoroutineScope(Dispatchers.IO).async {
                            ReadBook.loadContentAwait(1, resetPageOffset = true)
                        }
                } else {
                    compose.onNodeWithTag("reader-aloud-back").performClick()
                }
                assertTrue(
                    "Returned chapter reaches the real layout callback",
                    entered.await(10, TimeUnit.SECONDS),
                )
                await("target page is visible before chapter completion") {
                    ReadBook.durChapterIndex == 1 &&
                        ReadBook.durPageIndex == 1 &&
                        it.findViewById<ReadView>(R.id.read_view).curPage.textPage.textChapter ===
                            ReadBook.curTextChapter &&
                        (!realtime || ReadAloud.followReadAloudPosition)
                }
                assertEquals(
                    "Returning must not already restart the engine",
                    session,
                    speechSession(service),
                )
                // The current utterance advances while chapter layout is still in progress.
                scenario!!.onActivity { service.upTtsProgress(expectedPosition) }
                release.countDown()
                assertTrue("Chapter completion is observed", completed.await(10, TimeUnit.SECONDS))
                awaiting?.let { runBlocking { withTimeout(10000) { it.await() } } }
                await("return restores the latest live position and highlight") {
                    ReadBook.durChapterIndex == 1 &&
                        ReadBook.curTextChapter?.isCompleted == true &&
                        ReadAloud.followReadAloudPosition &&
                        ReadBook.curTextChapter!!.getPage(ReadBook.durPageIndex)!!
                            .hasReadAloudSpan &&
                        (!paused || awaitLoad || ReadBook.durChapterPos == expectedPosition)
                }
                compose.waitForIdle()
                assertEquals(
                    "No play/stop or new utterance session on return",
                    session,
                    speechSession(service),
                )
                assertEquals(
                    "The service cursor must not rewind",
                    expectedPosition,
                    ReadAloud.readAloudChapterStart,
                )
                assertEquals("Playback and pause are preserved", paused, BaseReadAloudService.pause)
                if (download)
                    assertTrue(
                        "The deleted chapter is really downloaded",
                        requests.get() > requestCount,
                    )
                val label = "aloud-cross-chapter-await-$awaitLoad-download-$download-paused-$paused"
                screenshot(label)
                File(context.getExternalFilesDir("ui-regression"), "$label-state.txt")
                    .writeText(
                        "before=$session\nafter=${speechSession(service)}\n" +
                            "speechPosition=${ReadAloud.readAloudChapterStart}, visiblePosition=${ReadBook.durChapterPos}, " +
                            "following=${ReadAloud.followReadAloudPosition}, requests=${requests.get() - requestCount}"
                    )
            } finally {
                release.countDown()
                awaiting?.cancel()
                scenario!!.onActivity { ReadBook.callBack = originalCallback }
            }
        }
    }

    private fun speechSession(service: TTSReadAloudService): List<Any> {
        fun generation(type: Class<*>, field: String) =
            (type.getDeclaredField(field).apply { isAccessible = true }.get(service) as AtomicLong)
                .get()
        return listOf(
            generation(BaseReadAloudService::class.java, "readAloudGeneration"),
            generation(TTSReadAloudService::class.java, "playbackSessionId"),
            service.nowSpeak,
            service.readAloudNumber,
            service.paragraphStartPos,
            service.lifecycle.currentState,
        )
    }

    @Test
    fun scrollingBackRestoresRealtimeWithoutResettingTheViewport() {
        val loaded = AtomicBoolean()
        scenario!!.onActivity {
            ReadBook.book!!.setPageAnim(PageAnim.scrollPageAnim)
            it.upPageAnim()
            ReadBook.loadContent(resetPageOffset = true) { loaded.set(true) }
        }
        await("scroll reader layout") {
            loaded.get() &&
            it.findViewById<ReadView>(R.id.read_view).isScroll &&
                ReadBook.curTextChapter?.isCompleted == true &&
                it.findViewById<ReadView>(R.id.read_view).curPage.textPage.textChapter ===
                    ReadBook.curTextChapter
        }
        val service = startReadAloudService(paused = true)
        await("scroll speech preparation completes") {
            ReadBook.curTextChapter?.let { BaseReadAloudService.hasPreparedSpeechContent(it) } == true &&
                service.textChapter?.chapter?.bookUrl == ReadBook.book?.bookUrl &&
                service.textChapter?.chapter?.index == ReadBook.durChapterIndex &&
                service.contentList.isNotEmpty()
        }
        var firstPageHeight = 0
        var speechStart = 0
        scenario!!.onActivity {
            val chapter = ReadBook.curTextChapter!!
            firstPageHeight = chapter.getPage(0)!!.height.toInt()
            BaseReadAloudService.updateReadAloudChapterIndex(ReadBook.durChapterIndex)
            speechStart = chapter.getPage(0)!!.lines.first { line -> !line.isTitle }.chapterPosition + 1
            service.upTtsProgress(speechStart)
            prefs.edit().putBoolean(PreferKey.readAloudControlsRealtime, true).commit()
        }
        await("initial speech progress reaches the real reader before manual scrolling") {
            val consumedPosition = ReadBookActivity::class.java
                .getDeclaredField("lastReadAloudChapterStart").apply { isAccessible = true }
                .getInt(it)
            val consumedChapter = ReadBookActivity::class.java
                .getDeclaredField("lastReadAloudChapterIndex").apply { isAccessible = true }
                .getInt(it)
            consumedPosition == speechStart && consumedChapter == ReadBook.durChapterIndex &&
                ReadAloud.readAloudChapterStart == speechStart
        }
        scenario!!.onActivity {
            it.findViewById<ReadView>(R.id.read_view).curPage.scroll(-firstPageHeight - 1)
            assertFalse(
                "Detach must not be restored before the scroll finishes",
                ReadAloud.followReadAloudPosition,
            )
        }
        await("scrolled page remains detached") {
            ReadBook.durPageIndex == 1 &&
                !ReadAloud.followReadAloudPosition &&
                it.readAloudControlsVisible
        }
        var returnedLineTop = 0f
        var returnedViewport = ""
        fun viewportState(view: ReadView): String {
            val content = view.curPage.findViewById<io.legado.app.ui.book.read.page.ContentTextView>(R.id.content_text_view)
            val offset = io.legado.app.ui.book.read.page.ContentTextView::class.java
                .getDeclaredField("pageOffset").apply { isAccessible = true }.getInt(content)
            val line = view.getReadAloudPos()?.second
            return "offset=$offset, size=${content.width}x${content.height}, " +
                "chapter=${System.identityHashCode(ReadBook.curTextChapter)}, " +
                "page=${System.identityHashCode(view.curPage.textPage)}, " +
                "line=${line?.chapterPosition}:${line?.lineTop}, delegateRunning=${view.pageDelegate?.isRunning}, " +
                "speech=${ReadAloud.readAloudChapterStart}, service=${service.readAloudNumber}"
        }
        scenario!!.onActivity {
            val view = it.findViewById<ReadView>(R.id.read_view)
            view.curPage.scroll(firstPageHeight - 19)
            returnedLineTop = view.getReadAloudPos()!!.second.lineTop
            returnedViewport = viewportState(view)
        }
        await("scrolling back restores live state") {
            ReadAloud.followReadAloudPosition &&
                ReadBook.durPageIndex == 0 &&
                ReadBook.curTextChapter!!.getPage(0)!!.hasReadAloudSpan &&
                it.readAloudControlsVisible
        }
        scenario!!.onActivity {
            assertEquals(
                "Automatic following preserves the returned scroll offset: before=$returnedViewport; " +
                    "after=${viewportState(it.findViewById(R.id.read_view))}",
                returnedLineTop,
                it.findViewById<ReadView>(R.id.read_view).getReadAloudPos()!!.second.lineTop,
                .01f,
            )
        }
        screenshot("aloud-realtime-scroll-return")
    }

    private fun swipePage(next: Boolean) {
        // Start within the page so Android's edge-back gesture does not consume the swipe.
        val offset = if (next) .3f else -.3f
        onView(withId(R.id.read_view))
            .perform(
                GeneralSwipeAction(
                    Swipe.FAST,
                    { view ->
                        GeneralLocation.CENTER.calculateCoordinates(view).also {
                            it[0] += view.width * offset
                        }
                    },
                    { view ->
                        GeneralLocation.CENTER.calculateCoordinates(view).also {
                            it[0] -= view.width * offset
                        }
                    },
                    Press.FINGER,
                )
            )
    }

    private fun playbackFlag(name: String, value: Boolean) {
        BaseReadAloudService::class
            .java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .setBoolean(null, value)
    }

    private fun dismissDialogWithBack(tag: String) {
        compose.waitUntil(30_000) {
            compose.mainClock.advanceTimeByFrame()
            var focused = false
            scenario!!.onActivity { activity ->
                focused = activity.supportFragmentManager.fragments
                    .filterIsInstance<androidx.fragment.app.DialogFragment>()
                    .any { it.dialog?.window?.decorView?.hasWindowFocus() == true }
            }
            focused && runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(30_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun await(
        description: String,
        timeoutMillis: Long = 30000,
        condition: (ReadBookActivity) -> Boolean,
    ) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                compose.mainClock.advanceTimeByFrame()
                var ready = false
                scenario!!.onActivity { ready = condition(it) }
                ready
            }
            return
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // Preserve the original state diagnostics and failure assertion below.
        }
        var state = ""
        scenario!!.onActivity {
            val view = it.findViewById<ReadView>(R.id.read_view)
            val controls = it.readAloudControlsBounds
            state =
                "page=${ReadBook.durPageIndex}, chapter=${ReadBook.durChapterIndex}, " +
                    "speechChapter=${ReadAloud.readAloudChapterIndex}, speechPosition=${ReadAloud.readAloudChapterStart}, " +
                    "following=${ReadAloud.followReadAloudPosition}, running=${BaseReadAloudService.isRun}, " +
                    "paused=${BaseReadAloudService.pause}, highlighted=${view.curPage.textPage.hasReadAloudSpan}, " +
                    "selected=${view.isTextSelected}, controls=${it.readAloudControlsVisible}, " +
                    "controlPosition=${controls.x},${controls.y}, dialog=${it.bottomDialog}, " +
                    "layoutComplete=${ReadBook.curTextChapter?.isCompleted}, " +
                    "serviceNumber=${readAloudService()?.readAloudNumber}, " +
                    "servicePage=${readAloudService()?.pageIndex}, " +
                    "serviceSegments=${readAloudService()?.contentList?.take(4)?.map { segment -> segment.length }}, " +
                    "mode=${AppConfig.readAloudStartAtSentence}, byPage=${readAloudService()?.readAloudByPage}, " +
                    "spanDiagnostic=$lastAloudMismatch, geometryDiagnostic=$lastReaderGeometry, " +
                    "layoutParagraphs=${ReadBook.curTextChapter?.getParagraphs(false)?.take(4)?.map { p -> "${p.num}:${p.chapterPosition}:${p.length}" }}"
        }
        val label = "aloud-timeout-${SystemClock.uptimeMillis()}"
        compose.saveSemantics(context, label)
        screenshot(label)
        File(context.getExternalFilesDir("ui-regression"), "$label-state.txt")
            .writeText("$description\n$state")
        throw AssertionError("Timed out waiting for $description: $state")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val rendered = CountDownLatch(1)
        scenario!!.onActivity {
            val decor = it.window.decorView
            decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } }
        }
        assertTrue(rendered.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        var controlsVisible = false
        scenario!!.onActivity { controlsVisible = it.readAloudControlsVisible }
        if (controlsVisible) {
            val controls =
                compose.onNodeWithTag("reader-aloud-controls").captureToImage().asAndroidBitmap()
            try {
                File(context.getExternalFilesDir("ui-regression"), "$name-controls.png")
                    .outputStream()
                    .use {
                        assertTrue(controls.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
            } finally {
                controls.recycle()
            }
        }
    }
}
