package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.parseReadConfigObject
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.postEvent
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingLayoutTransitionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun suppliedStylesKeepFullPageAfterCoverScrollCoverTransition() {
        val savedConfigs = ReadBookConfig.configList.map { it.copy() }
        val savedShare = ReadBookConfig.shareConfig.copy()
        val savedShared = ReadBookConfig.shareLayout
        val savedStyle = ReadBookConfig.readStyleSelect
        val savedComic = ReadBookConfig.isComic
        val configFiles =
            listOf(File(ReadBookConfig.configFilePath), File(ReadBookConfig.shareConfigFilePath))
                .associateWith { it.takeIf(File::exists)?.readBytes() }
        val file = File.createTempFile("layout-transition-", ".txt", context.cacheDir)
        val paragraphs =
            listOf(
                "窗外的雨渐渐停了。",
                "她推开窗，远处的山谷被薄薄的晨雾笼罩，屋檐上的水珠一颗接一颗落下来。",
                "“现在可以出发了吗？”他把地图展开，指了指那条沿着河岸向北延伸的小路。",
                "大家收拾好行李，仔细检查了门窗，然后穿过安静的院子。石板路上还留着昨夜的积水，天空却已经露出一片清澈的蓝色。",
                "没有人回答。",
            )
        // Keep the swipe away from the chapter's short final page, where offset zero is legitimate.
        file.writeText(
            (1..500).joinToString("\n\n") { "第${it}段。" + paragraphs[it % paragraphs.size] }
        )
        val book =
            Book(
                    bookUrl = file.absolutePath,
                    originName = file.name,
                    name = file.name,
                    charset = "UTF-8",
                    type = BookType.local or BookType.text,
                    totalChapterNum = 1,
                    durChapterPos = 1470,
                    latestChapterTime = file.lastModified(),
                )
                .apply { setPageAnim(-1) }
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(
            BookChapter(
                bookUrl = book.bookUrl,
                url = "layout-chapter",
                title = "Layout transition",
                start = 0L,
                end = file.length(),
            )
        )
        try {
            instrumentation.runOnMainSync {
                ReadBookConfig.isComic = false
                ReadBookConfig.readStyleSelect = 0
                ReadBookConfig.shareLayout = false
                ReadBookConfig.configList.clear()
                for (name in listOf("cover", "scroll")) {
                    val json =
                        instrumentation.context.assets
                            .open("issue1213-$name.json")
                            .bufferedReader()
                            .use { it.readText() }
                    ReadBookConfig.configList +=
                        parseReadConfigObject(json).getOrThrow().apply {
                            // The reporter's private font is not attached; retain all supplied
                            // layout values.
                            textFont = ""
                        }
                }
                // Same scroll mode with the other preset's padding, spacing and indentation.
                ReadBookConfig.configList +=
                    ReadBookConfig.configList[0].copy().apply { setCurPageAnim(3) }
                repeat(3) { ReadBookConfig.configList += ReadBookConfig.Config() }
                ReadBookConfig.shareConfig = ReadBookConfig.Config()
            }
            ActivityScenario.launch<ReadBookActivity>(
                    Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl)
                )
                .use { scenario ->
                    scenario.onActivity { activity ->
                        activity.supportFragmentManager.fragments
                            .filterIsInstance<ClickActionConfigDialog>()
                            .forEach { it.dismiss() }
                    }
                    awaitStableReader(scenario, book.bookUrl, false)
                    val initial = capture(scenario, "layout-cover-before")
                    // Observe this failing transition without forcing layout, replacing callbacks,
                    // or waiting for the expected paragraph. All existing readiness/assertions stay.
                    val geometryTrace = arrayListOf<String>()
                    var droppedSamples = 0
                    val geometryStart = android.os.SystemClock.uptimeMillis()
                    val geometryWallStart = System.currentTimeMillis()
                    val offsetField = ContentTextView::class.java.getDeclaredField("pageOffset").apply { isAccessible = true }
                    val pendingSizeField = ChapterProvider::class.java.getDeclaredField("upViewSizeRunnable").apply { isAccessible = true }
                    lateinit var observedView: ReadView
                    lateinit var observer: android.view.ViewTreeObserver.OnPreDrawListener
                    scenario.onActivity { activity ->
                        observedView = activity.findViewById(R.id.read_view)
                        observer = android.view.ViewTreeObserver.OnPreDrawListener {
                            val sample = runCatching {
                                val view = observedView.curPage.findViewById<ContentTextView>(R.id.content_text_view)
                                val page = observedView.curPage.textPage
                                val visible = observedView.getReadPosition()?.second
                                val saved = ReadBook.durChapterPos
                                val targetLine = page.lines.lastOrNull { it.chapterPosition <= saved }
                                "scroll=${observedView.isScroll} offset=${offsetField.getInt(view)} " +
                                    "readView=${observedView.width}x${observedView.height} contentBounds=${view.left},${view.top},${view.right},${view.bottom} " +
                                    "content=${view.width}x${view.height} provider=${ChapterProvider.viewWidth}x${ChapterProvider.viewHeight} " +
                                    "providerPadding=${ChapterProvider.paddingTop},${ChapterProvider.paddingBottom} style=${ReadBookConfig.styleSelect} " +
                                    "pendingSize=${pendingSizeField.get(ChapterProvider) != null} running=${observedView.pageDelegate?.isRunning} " +
                                    "pageChapter=${System.identityHashCode(page.textChapter)} currentChapter=${System.identityHashCode(ReadBook.curTextChapter)} " +
                                    "bound=${page.textChapter === ReadBook.curTextChapter} completed=${ReadBook.curTextChapter?.isCompleted} " +
                                    "page=${page.index} pageHeight=${page.height} saved=$saved " +
                                    "visible=${visible?.chapterPosition} pageStart=${page.lines.firstOrNull()?.chapterPosition} " +
                                    "targetPosition=${targetLine?.chapterPosition} targetTop=${targetLine?.lineTop} targetBottom=${targetLine?.lineBottom} " +
                                    "version=${observedView.getReadPositionVersion()} bottomDialog=${activity.bottomDialog}"
                            }.getOrElse { "sampleFailure=${it.javaClass.simpleName}" }
                            if (geometryTrace.lastOrNull()?.substringAfter(' ') != sample) {
                                if (geometryTrace.size < 24) geometryTrace += "${android.os.SystemClock.uptimeMillis() - geometryStart} $sample"
                                else droppedSamples++
                            }
                            true
                        }
                        observedView.viewTreeObserver.addOnPreDrawListener(observer)
                    }
                    lateinit var enteredScroll: ReadingSnapshot
                    try {
                        switchStyle(scenario, 1)
                        awaitStableReader(scenario, book.bookUrl, true)
                        enteredScroll = capture(scenario, "layout-scroll-before-swipe")
                    } finally {
                        scenario.onActivity { observedView.viewTreeObserver.removeOnPreDrawListener(observer) }
                        val trace = File(context.getExternalFilesDir("ui-regression"), "layout-transition-restore-geometry.txt")
                        trace.parentFile?.mkdirs()
                        trace.writeText("startUptimeMs=$geometryStart startWallTimeMs=$geometryWallStart\n" +
                            geometryTrace.joinToString("\n") + "\ndroppedSamples=$droppedSamples\n")
                    }
                    assertNotNull(
                        "The original page must identify an actual source paragraph",
                        initial.visiblePosition,
                    )
                    assertEquals(
                        "Entering scroll mode must preserve the same source character",
                        initial.visiblePosition,
                        enteredScroll.savedPosition,
                    )
                    assertEquals(
                        "The restored paragraph must be at the top of the actual scroll view",
                        initial.visiblePosition?.paragraph,
                        enteredScroll.visiblePosition?.paragraph,
                    )
                    // Recreating a scroll reader must not recursively inflate the Activity binding.
                    scenario.recreate()
                    awaitStableReader(scenario, book.bookUrl, true)
                    // Full ReadView reaches the system Home edge in the CI viewport. Keep the
                    // single real gesture inside the current page's actual visible text bounds.
                    fun scrollCoordinates(fraction: Float): (android.view.View) -> FloatArray = { target ->
                        val content = (target as ReadView).curPage.findViewById<ContentTextView>(R.id.content_text_view)
                        val visible = android.graphics.Rect()
                        check(content.getGlobalVisibleRect(visible) && !visible.isEmpty)
                        floatArrayOf(visible.exactCenterX(), visible.top + visible.height() * fraction)
                    }
                    onView(withId(R.id.read_view)).perform(
                        GeneralSwipeAction(Swipe.FAST, scrollCoordinates(0.8f), scrollCoordinates(0.2f), Press.FINGER),
                    )
                    compose.waitForIdle()
                    await {
                        var stopped = false
                        scenario.onActivity {
                            stopped =
                                it.findViewById<ReadView>(R.id.read_view).pageDelegate?.isRunning !=
                                    true
                        }
                        stopped
                    }
                    val scrolled = capture(scenario, "layout-scroll")
                    assertTrue(
                        "The reproduction must include a nonzero scroll offset",
                        scrolled.offset < 0,
                    )
                    var previousChapter = ReadBook.curTextChapter
                    var lineTopBeforeRefresh = 0f
                    scenario.onActivity { activity ->
                        val view = activity.findViewById<ReadView>(R.id.read_view)
                        previousChapter = ReadBook.curTextChapter
                        lineTopBeforeRefresh = checkNotNull(view.getReadAloudPos()).second.lineTop
                        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                    }
                    compose.waitUntil(30000) {
                        compose.mainClock.advanceTimeByFrame()
                        ReadBook.curTextChapter !== previousChapter &&
                            ReadBook.curTextChapter?.isCompleted == true
                    }
                    awaitStableReader(scenario, book.bookUrl, true)
                    scenario.onActivity { activity ->
                        val view = activity.findViewById<ReadView>(R.id.read_view)
                        assertEquals(
                            "A pure layout refresh preserves the visible line's exact pixel anchor",
                            lineTopBeforeRefresh,
                            checkNotNull(view.getReadAloudPos()).second.lineTop,
                            .01f,
                        )
                        val version = view.getReadPositionVersion()
                        view.curPage.scroll(-1)
                        assertTrue("Actual scrolling invalidates a layout anchor", view.getReadPositionVersion() > version)
                        view.curPage.scroll(1)
                    }
                    capture(scenario, "layout-scroll-before-preset")
                    switchStyle(scenario, 2)
                    awaitStableReader(scenario, book.bookUrl, true)
                    val sameMode = capture(scenario, "layout-scroll-same-mode")
                    assertEquals(
                        "Changing between scroll presets must preserve the source character",
                        scrolled.visiblePosition,
                        sameMode.savedPosition,
                    )
                    assertEquals(
                        "A same-mode preset must keep the original paragraph at the top",
                        scrolled.visiblePosition?.paragraph,
                        sameMode.visiblePosition?.paragraph,
                    )
                    switchStyle(scenario, 0)
                    awaitStableReader(scenario, book.bookUrl, false)
                    val returned = capture(scenario, "layout-cover-returned")
                    scenario.recreate()
                    awaitStableReader(scenario, book.bookUrl, false)
                    val reopened = capture(scenario, "layout-cover-reopened")
                    assertEquals(
                        "Horizontal pages must not retain a vertical scroll offset",
                        0,
                        returned.offset,
                    )
                    assertTrue(
                        "The lower part of the returned page must contain rendered text",
                        returned.bottomPixels > 100,
                    )
                    assertTrue(
                        "Reopening must also render text in the lower part of the page",
                        reopened.bottomPixels > 100,
                    )
                    // The first visible line can be just "落下来。", repeated every five paragraphs.
                    // Compare its paragraph identity, character offset, and 96 characters of
                    // following
                    // context across paragraph boundaries, then verify that position is actually
                    // drawn.
                    val anchor = checkNotNull(scrolled.visiblePosition)
                    assertEquals(
                        "The anchor must include substantial following context",
                        96,
                        anchor.context.length,
                    )
                    assertEquals(
                        "Returning must retain the same paragraph and source character",
                        anchor,
                        returned.savedPosition,
                    )
                    assertEquals(
                        "Reopening must retain the same paragraph and source character",
                        anchor,
                        reopened.savedPosition,
                    )
                    assertTrue(
                        "The returned page must draw the preserved position",
                        returned.savedPositionVisible,
                    )
                    assertTrue(
                        "The reopened page must draw the preserved position",
                        reopened.savedPositionVisible,
                    )
                }
        } finally {
            appDb.bookDao.delete(book)
            file.delete()
            TextFile.clear()
            instrumentation.runOnMainSync {
                ReadBookConfig.configList.clear()
                ReadBookConfig.configList.addAll(savedConfigs)
                ReadBookConfig.shareConfig = savedShare
                ReadBookConfig.shareLayout = savedShared
                ReadBookConfig.readStyleSelect = savedStyle
                ReadBookConfig.isComic = savedComic
                ChapterProvider.upStyle()
            }
            configFiles.forEach { (path, bytes) ->
                if (bytes == null) path.delete() else path.writeBytes(bytes)
            }
        }
    }

    private fun switchStyle(scenario: ActivityScenario<ReadBookActivity>, index: Int) {
        scenario.onActivity { ReadStyleDialog().showNow(it.supportFragmentManager, "layout-style") }
        compose.onNodeWithTag("read-style-presets").performScrollTo()
        compose
            .onNodeWithTag("read-style-presets")
            .performScrollToNode(hasTestTag("read-style-preset-$index"))
        compose.onNodeWithTag("read-style-preset-$index").assertIsDisplayed().performClick()
        await { ReadBookConfig.styleSelect == index }
        compose.onNodeWithTag("read-style-preset-$index").assertIsSelected()
        capture(scenario, "layout-style-selected-$index")
        pressBack()
    }

    private fun awaitReader(
        scenario: ActivityScenario<ReadBookActivity>,
        url: String,
        scroll: Boolean,
    ) {
        // Compose idle does not include the provider's delayed resize and its layout job.
        val pendingSize = ChapterProvider::class.java.getDeclaredField("upViewSizeRunnable")
            .apply { isAccessible = true }
        val loadingJobs = ReadBook::class.java.getDeclaredField("chapterLoadingJobs")
            .apply { isAccessible = true }
        val trace = File(context.getExternalFilesDir("ui-regression"), "layout-geometry.txt")
        var lastSample = ""
        await {
            var ready = false
            scenario.onActivity {
                val view = it.findViewById<ReadView>(R.id.read_view)
                val content = view.curPage.findViewById<ContentTextView>(R.id.content_text_view)
                val jobs = loadingJobs.get(ReadBook) as Map<*, *>
                val job = jobs[ReadBook.durChapterIndex] as? io.legado.app.help.coroutine.Coroutine<*>
                val sample = "scroll=$scroll pending=${pendingSize.get(ChapterProvider) != null} " +
                    "content=${content.width}x${content.height} provider=${ChapterProvider.viewWidth}x${ChapterProvider.viewHeight} " +
                    "jobCompleted=${job?.isCompleted} chapterCompleted=${ReadBook.curTextChapter?.isCompleted} " +
                    "bound=${view.curPage.textPage.textChapter === ReadBook.curTextChapter}"
                if (sample != lastSample) {
                    trace.appendText("$sample\n")
                    lastSample = sample
                }
                ready =
                    pendingSize.get(ChapterProvider) == null &&
                        content.width > 0 && content.height > 0 &&
                        content.width == ChapterProvider.viewWidth &&
                        content.height == ChapterProvider.viewHeight &&
                        (job == null || job.isCompleted) &&
                    ReadBook.book?.bookUrl == url &&
                        ReadBook.curTextChapter?.chapter?.bookUrl == url &&
                        ReadBook.curTextChapter?.isCompleted == true &&
                        !view.curPage.textPage.isMsgPage &&
                        view.curPage.textPage.textChapter === ReadBook.curTextChapter &&
                        view.curPage.textPage.lines.size > 3 &&
                        view.isScroll == scroll &&
                        it.bottomDialog == 0
            }
            ready
        }
        compose.waitForIdle()
    }

    /** This regression's baseline must belong to the completed layout, not a resized old chapter. */
    private fun awaitStableReader(scenario: ActivityScenario<ReadBookActivity>, url: String, scroll: Boolean) {
        val pendingSize = ChapterProvider::class.java.getDeclaredField("upViewSizeRunnable").apply { isAccessible = true }
        val loadingJobs = ReadBook::class.java.getDeclaredField("chapterLoadingJobs").apply { isAccessible = true }
        val layoutField = io.legado.app.ui.book.read.page.entities.TextChapter::class.java.getDeclaredField("layout").apply { isAccessible = true }
        val layoutClass = io.legado.app.ui.book.read.page.provider.TextChapterLayout::class.java
        val layoutWidth = layoutClass.getDeclaredField("visibleWidth").apply { isAccessible = true }
        val layoutHeight = layoutClass.getDeclaredField("visibleHeight").apply { isAccessible = true }
        val pageOffset = ContentTextView::class.java.getDeclaredField("pageOffset").apply { isAccessible = true }
        var candidate: List<Any?>? = null
        var frameCandidate: List<Any?>? = null
        var frameCallback: android.view.Choreographer.FrameCallback? = null
        var generation = 0
        val diagnostics = arrayListOf<String>()
        var lastSample = "not sampled"
        try {
            // The frame and every readiness condition share this one original 30s wait.
            compose.waitUntil(timeoutMillis = 30_000) {
                var ready = false
                scenario.onActivity { activity ->
                    val view = activity.findViewById<ReadView>(R.id.read_view)
                    val content = view.curPage.findViewById<ContentTextView>(R.id.content_text_view)
                    val page = view.curPage.textPage
                    val chapter = ReadBook.curTextChapter
                    val layout = chapter?.let(layoutField::get)
                    val sourceWidth = layout?.let(layoutWidth::getInt)
                    val sourceHeight = layout?.let(layoutHeight::getInt)
                    val job = (loadingJobs.get(ReadBook) as Map<*, *>)[ReadBook.durChapterIndex] as? io.legado.app.help.coroutine.Coroutine<*>
                    val hasPendingSize = pendingSize.get(ChapterProvider) != null
                    val sourceGeometry = sourceWidth == ChapterProvider.visibleWidth && sourceHeight == ChapterProvider.visibleHeight
                    val actualFactory = chapter?.isCompleted == true && !page.isMsgPage && ReadBook.msg == null &&
                        page === view.pageFactory.curPage
                    val layoutRequested = view.isLayoutRequested || view.curPage.isLayoutRequested || content.isLayoutRequested
                    val valid = !hasPendingSize && !layoutRequested && content.width > 0 && content.height > 0 &&
                        content.width == ChapterProvider.viewWidth && content.height == ChapterProvider.viewHeight &&
                        sourceGeometry && actualFactory && (job == null || job.isCompleted) &&
                        ReadBook.book?.bookUrl == url && chapter?.chapter?.bookUrl == url &&
                        chapter?.isCompleted == true && page.textChapter === chapter && page.lines.size > 3 &&
                        view.isScroll == scroll && view.pageDelegate?.isRunning != true && activity.bottomDialog == 0
                    val signature = listOf(chapter, page, job, sourceWidth, sourceHeight,
                        page.index, page.height, page.lines.firstOrNull()?.chapterPosition,
                        page.lines.firstOrNull()?.lineTop, page.lines.lastOrNull()?.lineBottom,
                        content.width, content.height, content.left, content.top, content.right, content.bottom,
                        ChapterProvider.viewWidth, ChapterProvider.viewHeight, ChapterProvider.visibleWidth, ChapterProvider.visibleHeight,
                        ReadBook.durChapterPos, view.getReadPositionVersion(), pageOffset.getInt(content))
                    if (!valid || candidate != signature) {
                        generation++
                        frameCallback?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) }
                        frameCallback = null
                        frameCandidate = null
                        candidate = if (valid) signature else null
                        if (valid) {
                            val token = generation
                            val captured = signature
                            frameCallback = android.view.Choreographer.FrameCallback {
                                if (token == generation) {
                                    frameCandidate = captured
                                    frameCallback = null
                                }
                            }.also { android.view.Choreographer.getInstance().postFrameCallback(it) }
                        }
                    }
                    ready = valid && candidate == signature && frameCandidate == signature
                    val sample = "scroll=$scroll pendingSize=$hasPendingSize layoutRequested=$layoutRequested " +
                        "content=${content.width}x${content.height} provider=${ChapterProvider.viewWidth}x${ChapterProvider.viewHeight} " +
                        "source=${sourceWidth}x${sourceHeight} visible=${ChapterProvider.visibleWidth}x${ChapterProvider.visibleHeight} " +
                        "sourceGeometry=$sourceGeometry actualFactory=$actualFactory jobCompleted=${job?.isCompleted} " +
                        "chapterCompleted=${chapter?.isCompleted} bound=${page.textChapter === chapter} " +
                        "chapter=${System.identityHashCode(chapter)} page=${page.index} pageHeight=${page.height} " +
                        "saved=${ReadBook.durChapterPos} offset=${pageOffset.getInt(content)} running=${view.pageDelegate?.isRunning} " +
                        "bottomDialog=${activity.bottomDialog} frameStable=${frameCandidate == signature}"
                    if (sample != lastSample && diagnostics.size < 24) diagnostics += sample
                    lastSample = sample
                }
                ready
            }
            compose.waitForIdle()
        } catch (failure: Throwable) {
            val output = File(context.getExternalFilesDir("ui-regression"), "layout-strict-readiness-failure.txt")
            output.parentFile?.mkdirs()
            output.appendText(diagnostics.joinToString("\n") + "\nlast=$lastSample\n")
            throw failure
        } finally {
            scenario.onActivity {
                generation++
                frameCallback?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) }
                frameCallback = null
            }
        }
    }

    private data class SourcePosition(val paragraph: Int, val offset: Int, val context: String)

    private data class ReadingSnapshot(
        val offset: Int,
        val bottomPixels: Int,
        val visiblePosition: SourcePosition?,
        val savedPosition: SourcePosition?,
        val savedPositionVisible: Boolean,
    )

    private fun capture(
        scenario: ActivityScenario<ReadBookActivity>,
        name: String,
    ): ReadingSnapshot {
        val output = context.getExternalFilesDir("ui-regression")!!
        output.mkdirs()
        lateinit var result: ReadingSnapshot
        scenario.onActivity { activity ->
            val readView = activity.findViewById<ReadView>(R.id.read_view)
            val view = readView.curPage.findViewById<ContentTextView>(R.id.content_text_view)
            val offset =
                ContentTextView::class
                    .java
                    .getDeclaredField("pageOffset")
                    .apply { isAccessible = true }
                    .getInt(view)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                var bottomPixels = 0
                for (y in view.height * 2 / 3 until view.height * 9 / 10) for (x in
                    view.width / 10 until view.width * 9 / 10) if (
                    Color.alpha(bitmap.getPixel(x, y)) > 0
                )
                    bottomPixels++
                File(output, "$name-content.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val page = readView.curPage.textPage
                val visible = readView.getReadPosition()
                val pageRange = page.chapterPosition..(page.chapterPosition + page.charSize)
                val paragraphs =
                    page.textChapter.paragraphsInternal.filterNot { it.firstLine.isTitle }
                fun sourcePosition(position: Int): SourcePosition? {
                    val index = paragraphs.indexOfFirst { position in it.chapterIndices }
                    if (index < 0) return null
                    val paragraph = paragraphs[index]
                    val text = paragraph.text.trimStart()
                    val id =
                        Regex("第(\\d+)段。").find(text)?.groupValues?.get(1)?.toInt() ?: return null
                    val indent = paragraph.text.length - text.length
                    val sourceOffset =
                        (position - paragraph.chapterPosition - indent).coerceAtLeast(0)
                    val context =
                        paragraphs
                            .drop(index)
                            .joinToString("\n") { it.text.trimStart() }
                            .drop(sourceOffset)
                            .take(96)
                    return SourcePosition(id, sourceOffset, context)
                }
                result =
                    ReadingSnapshot(
                        offset,
                        bottomPixels,
                        visible?.second?.chapterPosition?.let(::sourcePosition),
                        sourcePosition(ReadBook.durChapterPos),
                        page.lines.any {
                            ReadBook.durChapterPos in it.chapterIndices &&
                                it.isVisible(offset.toFloat())
                        },
                    )
                File(output, "$name.txt")
                    .writeText(
                        "scroll=${readView.isScroll} offset=$offset bottomPixels=$bottomPixels\n" +
                            "view=${view.width}x${view.height} provider=${ChapterProvider.viewWidth}x${ChapterProvider.viewHeight}\n" +
                            "visibleChapter=${visible?.first} visiblePosition=${visible?.second?.chapterPosition} visibleText=${visible?.second?.text} pageRange=$pageRange animating=${readView.pageDelegate?.isRunning}\n" +
                            "sourcePosition=$result\n" +
                            "pageIndex=${page.index} height=${page.height} position=${ReadBook.durChapterPos} lines=" +
                            page.lines
                                .map { "${it.lineTop}:${it.lineBottom}:${it.text}" }
                                .joinToString("\n")
                    )
            } finally {
                bitmap.recycle()
            }
        }
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(output, "$name.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            screenshot.recycle()
        }
        return result
    }

    private fun await(condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                condition()
            }
            return
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // Preserve the original state diagnostics and failure assertion below.
        }
        assertTrue(
            "The actual reader or style selector did not reach the expected state",
            condition(),
        )
    }
}
