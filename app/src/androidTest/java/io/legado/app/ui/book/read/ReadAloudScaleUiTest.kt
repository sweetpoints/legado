package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.service.BaseReadAloudService
import io.legado.app.testutil.saveSemantics
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudControlsDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual reader layout and touch bounds; this fixture does not test service lifecycle. */
@RunWith(AndroidJUnit4::class)
class ReadAloudScaleUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedMenuHelp = LocalConfig.all["readMenuHelpVersion"]
    private val savedPrefs =
        listOf(
                PreferKey.readAloudControlsPause,
                PreferKey.readAloudControlsSize,
                PreferKey.readAloudControlsRealtime,
                PreferKey.readAloudControlsPosition,
                PreferKey.readAloudControlsAutoHide,
                PreferKey.readAloudControlsDrag,
                PreferKey.readAloudControlsDock,
                PreferKey.readAloudControlsOpacity,
                PreferKey.readAloudControlsThreshold,
                PreferKey.readAloudControlsX,
                PreferKey.readAloudControlsY,
                "readAloudControlsWidth",
            )
            .associateWith { prefs.all[it] }
    private val savedRunning = BaseReadAloudService.isRun
    private val savedPaused = BaseReadAloudService.pause
    private val savedFollowing = BaseReadAloudService.followReadAloudPosition
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var book: Book? = null
    private var textFile: File? = null

    @Before
    fun setUp() {
        prefs
            .edit()
            .putBoolean(PreferKey.readAloudControlsPause, true)
            // This fixture measures controls; independent follow/visibility behavior is tested
            // separately.
            .putBoolean(PreferKey.readAloudControlsRealtime, false)
            .putBoolean(PreferKey.readAloudControlsPosition, true)
            .putBoolean(PreferKey.readAloudControlsAutoHide, false)
            .putBoolean(PreferKey.readAloudControlsDrag, false)
            .putBoolean(PreferKey.readAloudControlsDock, false)
            .remove("readAloudControlsWidth")
            .remove(PreferKey.readAloudControlsOpacity)
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
                !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage &&
                it.bottomDialog == 0
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            playbackFlag("isRun", savedRunning)
            playbackFlag("pause", savedPaused)
            if (savedFollowing) BaseReadAloudService.restoreReadAloudFollow()
            else BaseReadAloudService.detachReadAloudFollow()
        }
        scenario?.close()
        book?.let {
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
                savedPrefs.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Float -> putFloat(key, value)
                    }
                }
            }
            .commit()
    }

    @Test
    fun widthControlsWholeBarAndCircleWithMatchingTouchBounds() {
        val evidence = StringBuilder()
        for (widthDp in listOf(288, 144, 85, 432)) {
            prefs.edit().putInt("readAloudControlsWidth", widthDp).commit()
            scenario!!.onActivity {
                playbackFlag("isRun", true)
                BaseReadAloudService.detachReadAloudFollow()
                it.showReadAloudControls()
            }
            await("position bar visible") {
                it.readAloudControlsVisible
            }
            screenshot("aloud-scale-position-$widthDp")
            var expectedHeight = 0
            var density = 1f
            var actualBounds = ReadAloudControlsBounds(0f, 0f, 0, 0)
            scenario!!.onActivity { activity ->
                actualBounds = activity.readAloudControlsBounds
                density = activity.resources.displayMetrics.density
            }
            val bar = actualBounds
            val hostBounds = compose.onNodeWithTag("reader-host").fetchSemanticsNode().boundsInRoot
            val expectedWidth =
                minOf(
                    (widthDp * density).roundToInt(),
                    hostBounds.width.roundToInt() - (32 * density).roundToInt(),
                )
            expectedHeight = (expectedWidth / 6f).roundToInt()
            evidence.appendLine(
                "requested=$widthDp actual=${bar.width}x${bar.height} expected=${expectedWidth}x$expectedHeight"
            )
            File(context.getExternalFilesDir("ui-regression"), "aloud-scale-bounds.txt")
                .writeText(evidence.toString())
            assertEquals(
                "Long control must use the selected total width",
                expectedWidth,
                bar.width,
            )
            assertEquals(
                "Long control height must scale with its width",
                expectedHeight,
                bar.height,
            )
            val semanticsBounds =
                compose.onNodeWithTag("reader-aloud-controls").fetchSemanticsNode().boundsInRoot
            assertEquals(
                "Compose exposes the measured control width",
                bar.width,
                semanticsBounds.width.roundToInt(),
            )
            assertEquals(
                "Compose exposes the measured control height",
                bar.height,
                semanticsBounds.height.roundToInt(),
            )
            assertEquals("Compose exposes the control X", bar.x + hostBounds.left, semanticsBounds.left, 1f)
            assertEquals("Compose exposes the control Y", bar.y + hostBounds.top, semanticsBounds.top, 1f)
            assertTrue(
                "The complete Compose control stays within the host viewport",
                semanticsBounds.left >= 0 &&
                    semanticsBounds.top >= 0 &&
                    semanticsBounds.right <= hostBounds.right &&
                    semanticsBounds.bottom <= hostBounds.bottom,
            )
            compose.onNodeWithTag("reader-aloud-back").assertIsDisplayed()
            compose.onNodeWithTag("reader-aloud-here").assertIsDisplayed()
            for (paused in listOf(false, true)) {
                scenario!!.onActivity {
                    playbackFlag("pause", paused)
                    BaseReadAloudService.restoreReadAloudFollow()
                    it.showReadAloudControls()
                }
                await("pause control visible") {
                    it.readAloudControlsVisible
                }
                screenshot("aloud-scale-circle-$widthDp-$paused")
                var circleBounds = ReadAloudControlsBounds(0f, 0f, 0, 0)
                scenario!!.onActivity { circleBounds = it.readAloudControlsBounds }
                assertEquals("Circle follows the same scale", expectedHeight, circleBounds.width)
                assertEquals(expectedHeight, circleBounds.height)
                val image = compose.onNodeWithTag("reader-aloud-controls").captureToImage()
                assertEquals(expectedHeight, image.width)
                assertEquals(expectedHeight, image.height)
                compose.onNodeWithTag("reader-aloud-pause").assertIsDisplayed()
            }
        }
        File(context.getExternalFilesDir("ui-regression"), "aloud-scale-bounds.txt")
            .writeText(evidence.toString())
    }

    @Test
    fun controlsSettingsRecreationAndRepeatedDismissReleaseEachHostCounterOnce() {
        lateinit var previousActivity: ReadBookActivity
        scenario!!.onActivity {
            previousActivity = it
            assertEquals(0, it.bottomDialog)
            ReadAloudControlsDialog().show(it.supportFragmentManager, "controls-counter")
        }
        await("settings counted") { it.bottomDialog == 1 }
        scenario!!.recreate()
        await("restored settings counted") {
            it.supportFragmentManager.findFragmentByTag("controls-counter")?.view != null &&
                it.bottomDialog == 1
        }
        assertEquals(0, previousActivity.bottomDialog)
        scenario!!.onActivity {
            val dialog =
                it.supportFragmentManager.findFragmentByTag("controls-counter")
                    as ReadAloudControlsDialog
            dialog.dismiss()
            dialog.dismiss()
        }
        await("settings counter released") { it.bottomDialog == 0 }
    }

    @Test
    fun legacySizeAndSelectedWidthSurviveSettingsAndReaderRecreation() {
        prefs
            .edit()
            .putInt(PreferKey.readAloudControlsSize, 72)
            .remove("readAloudControlsWidth")
            .commit()
        scenario!!.onActivity {
            ReadAloudControlsDialog().show(it.supportFragmentManager, "scale-settings")
        }
        compose
            .onNodeWithTag("aloud-controls-value-Width")
            .performScrollTo()
            .assertTextEquals("432")
        screenshot("aloud-scale-legacy-settings")
        compose
            .onNodeWithTag("aloud-controls-slider-Width")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(85f)) }
        scenario!!.onActivity {
            (it.supportFragmentManager.findFragmentByTag("scale-settings")
                    as ReadAloudControlsDialog)
                .dismiss()
        }
        await("settings dismissed") { it.bottomDialog == 0 }
        scenario!!.recreate()
        scenario!!.onActivity {
            playbackFlag("isRun", true)
            BaseReadAloudService.detachReadAloudFollow()
            it.showReadAloudControls()
        }
        await("restored position control") {
            it.readAloudControlsVisible
        }
        screenshot("aloud-scale-restored-85")
        scenario!!.onActivity { activity ->
            assertEquals(85, prefs.getInt("readAloudControlsWidth", -1))
            val bar = activity.readAloudControlsBounds
            assertEquals((85 * activity.resources.displayMetrics.density).roundToInt(), bar.width)
        }
    }

    @Test
    fun draggedPositionSurvivesDisablingDragAndResetRestoresDefault() {
        for (pauseControl in listOf(false, true)) verifyLockedPosition(pauseControl)
    }

    private fun verifyLockedPosition(pauseControl: Boolean) {
        prefs
            .edit()
            .remove(PreferKey.readAloudControlsX)
            .remove(PreferKey.readAloudControlsY)
            .putBoolean(PreferKey.readAloudControlsDrag, true)
            .putBoolean(PreferKey.readAloudControlsPause, true)
            .putInt(PreferKey.readAloudControlsWidth, 85)
            .commit()
        scenario!!.onActivity {
            playbackFlag("isRun", true)
            if (pauseControl) BaseReadAloudService.restoreReadAloudFollow()
            else BaseReadAloudService.detachReadAloudFollow()
            it.showReadAloudControls(resetPosition = true)
        }
        await("movable control visible") { it.readAloudControlsVisible }
        screenshot("aloud-lock-default-$pauseControl")

        var defaultX = 0f
        var defaultY = 0f
        scenario!!.onActivity { activity ->
            val bar = activity.readAloudControlsBounds
            defaultX = bar.x
            defaultY = bar.y
        }
        // Gesture the actual Compose control; its production pointer handler stores normalized
        // coordinates on ACTION_UP.
        drag(-48.dpToPx().toFloat(), -72.dpToPx().toFloat())
        var movedX = 0f
        var movedY = 0f
        var storedX = 0f
        var storedY = 0f
        scenario!!.onActivity { activity ->
            val bar = activity.readAloudControlsBounds
            movedX = bar.x
            movedY = bar.y
            storedX = prefs.getFloat(PreferKey.readAloudControlsX, Float.NaN)
            storedY = prefs.getFloat(PreferKey.readAloudControlsY, Float.NaN)
            assertTrue(
                "Drag must move the real control",
                movedX < defaultX - 24.dpToPx() && movedY < defaultY - 24.dpToPx(),
            )
            assertTrue("Drag must persist normalized X", storedX.isFinite() && storedX != .5f)
            assertTrue("Drag must persist normalized Y", storedY.isFinite() && storedY >= 0f)
        }

        prefs.edit().putBoolean(PreferKey.readAloudControlsDrag, false).commit()
        scenario!!.onActivity { it.showReadAloudControls() }
        await("control remains visible after disabling drag") {
            it.readAloudControlsVisible
        }
        scenario!!.onActivity { activity ->
            val bar = activity.readAloudControlsBounds
            assertEquals("Disabling drag must retain X", movedX, bar.x, 1f)
            assertEquals("Disabling drag must retain Y", movedY, bar.y, 1f)
        }

        // With dragging disabled the same real gesture must leave both the view and stored
        // coordinates unchanged.
        drag(48.dpToPx().toFloat(), 48.dpToPx().toFloat())
        scenario!!.onActivity { activity ->
            val bar = activity.readAloudControlsBounds
            assertEquals("Disabled drag must ignore movement on X", movedX, bar.x, 1f)
            assertEquals("Disabled drag must ignore movement on Y", movedY, bar.y, 1f)
            assertEquals(storedX, prefs.getFloat(PreferKey.readAloudControlsX, Float.NaN), 0f)
            assertEquals(storedY, prefs.getFloat(PreferKey.readAloudControlsY, Float.NaN), 0f)
        }
        screenshot("aloud-lock-retained-$pauseControl")

        scenario!!.onActivity { it.showReadAloudControls(resetPosition = true) }
        await("control remains visible after reset") {
            it.readAloudControlsVisible &&
                !prefs.contains(PreferKey.readAloudControlsX) &&
                !prefs.contains(PreferKey.readAloudControlsY)
        }
        scenario!!.onActivity { activity ->
            val bar = activity.readAloudControlsBounds
            assertFalse("Reset must remove stored X", prefs.contains(PreferKey.readAloudControlsX))
            assertFalse("Reset must remove stored Y", prefs.contains(PreferKey.readAloudControlsY))
            assertEquals("Reset must restore default X", defaultX, bar.x, 1f)
            assertEquals("Reset must restore default Y", defaultY, bar.y, 1f)
            File(context.getExternalFilesDir("ui-regression"), "aloud-lock-$pauseControl.txt")
                .writeText(
                    "default=$defaultX,$defaultY moved=$movedX,$movedY stored=$storedX,$storedY " +
                        "lockedGestureUnchanged=true reset=${bar.x},${bar.y} coordinatesRemoved=true"
                )
        }
        screenshot("aloud-lock-reset-$pauseControl")
    }

    @Test
    fun oldSmallWidthIsClampedAndOpacityDefaultsOnlyWhenUnset() {
        prefs
            .edit()
            .putInt("readAloudControlsWidth", 40)
            .remove(PreferKey.readAloudControlsOpacity)
            .commit()
        scenario!!.onActivity {
            playbackFlag("isRun", true)
            BaseReadAloudService.detachReadAloudFollow()
            it.showReadAloudControls()
        }
        await("clamped control") {
            it.readAloudControlsVisible
        }
        screenshot("aloud-scale-old40-clamped85-opacity90")
        var restoredWidth = 0
        assertBackgroundOpacity(90)
        scenario!!.onActivity { activity ->
            restoredWidth = activity.readAloudControlsBounds.width
            assertEquals(
                (85 * activity.resources.displayMetrics.density).roundToInt(),
                restoredWidth,
            )
            ReadAloudControlsDialog().show(activity.supportFragmentManager, "new-defaults")
        }
        settingsNode("aloud-controls-value-Width").assertTextEquals("85")
        assertEquals(85, prefs.getInt("readAloudControlsWidth", -1))
        settingsNode("aloud-controls-value-Opacity")
            .assertTextEquals("90")
        settingsNode("aloud-controls-slider-Opacity")
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(30f)) }
        scenario!!.onActivity {
            (it.supportFragmentManager.findFragmentByTag("new-defaults") as ReadAloudControlsDialog)
                .dismiss()
        }
        await("new settings dismissed") { it.bottomDialog == 0 }
        scenario!!.recreate()
        scenario!!.onActivity {
            playbackFlag("isRun", true)
            BaseReadAloudService.detachReadAloudFollow()
            it.showReadAloudControls()
        }
        await("explicit opacity restored") {
            it.readAloudControlsVisible
        }
        screenshot("aloud-scale-explicit-opacity30")
        assertBackgroundOpacity(30)
        scenario!!.onActivity { activity ->
            assertEquals(
                "Explicit opacity remains unchanged",
                30,
                prefs.getInt(PreferKey.readAloudControlsOpacity, -1),
            )
            ReadAloudControlsDialog().show(activity.supportFragmentManager, "saved-opacity")
        }
        settingsNode("aloud-controls-value-Opacity")
            .assertTextEquals("30")
        scenario!!.onActivity {
            (it.supportFragmentManager.findFragmentByTag("saved-opacity")
                    as ReadAloudControlsDialog)
                .dismiss()
        }
    }

    private fun settingsNode(tag: String): SemanticsNodeInteraction {
        try {
            compose.onNodeWithTag("aloud-controls-settings-list")
                .performScrollToNode(hasTestTag(tag))
            return compose.onNodeWithTag(tag).assertIsDisplayed()
        } catch (error: AssertionError) {
            compose.saveSemantics(context, "$tag-timeout")
            throw error
        }
    }

    private fun assertBackgroundOpacity(percent: Int) {
        // PixelCopy captures the composed window, so its alpha is opaque. Compare the actual
        // source-over RGB above the native reader. Position this fixture away from bottom toasts.
        val savedPosition = listOf(PreferKey.readAloudControlsX, PreferKey.readAloudControlsY)
            .associateWith { prefs.all[it] }
        prefs.edit()
            .putFloat(PreferKey.readAloudControlsX, .5f)
            .putFloat(PreferKey.readAloudControlsY, .4f)
            .commit()
        try {
            awaitSurfaceFrame()
            val hostBounds = compose.onNodeWithTag("reader-host").fetchSemanticsNode().boundsInRoot
            val bounds = compose.onNodeWithTag("reader-aloud-controls").fetchSemanticsNode().boundsInRoot
            val sampleX = (bounds.left - hostBounds.left).roundToInt() + bounds.width.roundToInt() / 2
            val sampleY = (bounds.top - hostBounds.top).roundToInt() + bounds.height.roundToInt() / 5
            var foreground = 0
            scenario!!.onActivity { activity ->
                foreground = io.legado.app.utils.ColorUtils.withAlpha(activity.bottomBackground, percent / 100f)
                playbackFlag("isRun", false)
                activity.showReadAloudControls()
            }
            awaitSurfaceFrame()
            val underlay = compose.onNodeWithTag("reader-host").captureToImage().asAndroidBitmap()
            try {
                val beneath = underlay.getPixel(sampleX, sampleY)
                val expected = androidx.core.graphics.ColorUtils.compositeColors(foreground, beneath)
                scenario!!.onActivity {
                    playbackFlag("isRun", true)
                    BaseReadAloudService.detachReadAloudFollow()
                    it.showReadAloudControls()
                }
                awaitSurfaceFrame()
                assertEquals(bounds, compose.onNodeWithTag("reader-aloud-controls").fetchSemanticsNode().boundsInRoot)
                val bitmap = compose.onNodeWithTag("reader-host").captureToImage().asAndroidBitmap()
                try {
                    val actual = bitmap.getPixel(sampleX, sampleY)
                    val channels = listOf<(Int) -> Int>(
                        android.graphics.Color::red,
                        android.graphics.Color::green,
                        android.graphics.Color::blue,
                    )
                    if (channels.any { kotlin.math.abs(it(expected) - it(actual)) > 2 }) {
                        for ((name, frame) in listOf("underlay" to underlay, "actual" to bitmap)) {
                            File(context.getExternalFilesDir("ui-regression"), "aloud-opacity-$percent-$name.png")
                                .outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    }
                    for (channel in channels) {
                        assertEquals(
                            "Rendered background uses $percent percent opacity at $sampleX,$sampleY " +
                                "bounds=$bounds host=$hostBounds foreground=$foreground beneath=$beneath actual=$actual",
                            channel(expected).toFloat(),
                            channel(actual).toFloat(),
                            2f,
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                underlay.recycle()
            }
        } finally {
            prefs.edit().apply {
                savedPosition.forEach { (key, value) ->
                    if (value is Float) putFloat(key, value) else remove(key)
                }
            }.commit()
        }
    }

    private fun awaitSurfaceFrame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val rendered = CountDownLatch(1)
        scenario!!.onActivity {
            val decor = it.window.decorView
            decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } }
        }
        assertTrue("Reader window rendered the updated controls", rendered.await(5, TimeUnit.SECONDS))
    }

    private fun drag(dx: Float, dy: Float) {
        compose.onNodeWithTag("reader-aloud-controls").performTouchInput {
            swipe(center, center + Offset(dx, dy), durationMillis = 200)
        }
        compose.waitForIdle()
    }

    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0).let {
                try {
                    instrumentation.sendPointerSync(it)
                } finally {
                    it.recycle()
                }
            }
        }
        compose.waitForIdle()
    }

    private fun playbackFlag(name: String, value: Boolean) {
        BaseReadAloudService::class
            .java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .setBoolean(null, value)
    }

    private fun await(description: String, condition: (ReadBookActivity) -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 30000) {
                var ready = false
                scenario!!.onActivity { ready = condition(it) }
                ready
            }
            return
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // Preserve the original state diagnostics and failure assertion below.
        }
        throw AssertionError("Timed out waiting for $description")
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
        if (
            scenario?.let { current ->
                var visible = false
                current.onActivity { visible = it.readAloudControlsVisible }
                visible
            } == true
        ) {
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
