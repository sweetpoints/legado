package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.LayerDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import io.legado.app.R
import io.legado.app.constant.AppConst.timeFormat
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.HighlightStyle
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.config.ReaderInfoValues
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import java.util.Date

internal object BookmarkIndicatorGeometry {
    fun marginRight(
        rootPaddingRight: Int,
        headerPaddingRight: Int,
        indicatorPaddingRight: Int,
    ): Int = rootPaddingRight + headerPaddingRight - indicatorPaddingRight

    fun top(baseline: Int, height: Int, paddingBottom: Int, minTop: Int): Int =
        (baseline - height + paddingBottom).coerceAtLeast(minTop)
}

/** 页面视图 */
class PageView(context: Context) : FrameLayout(context) {

    private val readBookActivity
        get() = activity as? ReadBookActivity

    private val contentView = ContentTextView(context, null).apply { id = R.id.content_text_view }
    private val pageRoot = FrameLayout(context)
    private val composeView = ComposeView(context)

    private var battery = 100
    private var readerInfoValues by mutableStateOf(ReaderInfoValues(battery = 100))
    private var readerInfoTemplates by mutableStateOf(emptyList<String>())
    private var tipColor by mutableIntStateOf(ReadBookConfig.textColor)
    private var tipDividerColor by
        mutableIntStateOf(ContextCompat.getColor(context, R.color.divider))
    private var tipTextSize by mutableIntStateOf(ReadTipConfig.tipTextSize)
    private var readerInfoTypeface by mutableStateOf(ChapterProvider.typeface)
    private var headerVisible by mutableStateOf(false)
    private var footerVisible by mutableStateOf(true)
    private var headerLineVisible by mutableStateOf(false)
    private var footerLineVisible by mutableStateOf(false)
    private var statusBarVisible by mutableStateOf(true)
    private var navigationBarVisible by mutableStateOf(true)
    private var statusBarInset by mutableIntStateOf(0)
    private var navigationBarInset by mutableIntStateOf(0)
    private var headerMeasuredHeight by mutableIntStateOf(0)
    private var headerRightPosition by mutableStateOf(Offset.Zero)
    private var headerRightBaseline by mutableIntStateOf(0)
    private var contentBounds by mutableStateOf(IntRect.Zero)
    private var headerPadding by mutableStateOf(ReaderTipPadding(0, 0, 0, 0))
    private var footerPadding by mutableStateOf(ReaderTipPadding(0, 0, 0, 0))
    private var bookmarkVisible by mutableStateOf(false)
    private var bookmarkInHeader by mutableStateOf(false)
    private var bookmarkOffset by mutableStateOf(IntOffset.Zero)

    private data class CanvasReadyCallback(val isCurrent: () -> Boolean, val action: () -> Unit)

    private val canvasReadyCallbacks = mutableListOf<CanvasReadyCallback>()
    private var canvasReadyPreDraw: OneShotPreDrawListener? = null
    private var isMainView by mutableStateOf(false)
    var isScroll = false

    internal fun readerInfoText(slot: Int): CharSequence =
        ReaderInfoTemplateRenderer.render(
            readerInfoTemplates.getOrElse(slot) { "" },
            readerInfoValues,
        )

    internal val readerTipTextSizeSp: Int
        get() = tipTextSize

    internal val readerTipTypeface
        get() = readerInfoTypeface

    internal val contentViewTop: Float
        get() = pageRoot.top + pageRoot.paddingTop + contentBounds.top.toFloat()

    internal fun closePdfRenderer() = contentView.closePdfRenderer()

    internal fun cancelHighlightTap() = contentView.cancelHighlightTap()

    val headerHeight: Int
        get() =
            pageRoot.top +
                pageRoot.paddingTop +
                (if (statusBarVisible) statusBarInset else 0) +
                (if (headerVisible) headerMeasuredHeight else 0)

    val imgBgPaddingStart: Int
        get() = pageRoot.paddingStart

    fun bookmarkIndicatorMarginRight(indicatorPaddingRight: Int): Int =
        BookmarkIndicatorGeometry.marginRight(
            pageRoot.paddingRight,
            ReadBookConfig.headerPaddingRight.dpToPx(),
            indicatorPaddingRight,
        )

    fun bookmarkIndicatorTop(height: Int, paddingBottom: Int): Int =
        BookmarkIndicatorGeometry.top(
            pageRoot.paddingTop + headerRightPosition.y.toInt() + headerRightBaseline,
            height,
            paddingBottom,
            pageRoot.paddingTop,
        )

    init {
        pageRoot.addView(
            contentView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        pageRoot.addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            pageRoot,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        composeView.setContent {
            val templates = readerInfoTemplates
            ReaderPageChrome(
                modifier =
                    Modifier.testTag(
                        if (isMainView) "reader-current-page" else "reader-neighbor-page"
                    ),
                statusBarHeight = statusBarInset,
                navigationBarHeight = navigationBarInset,
                showStatusBar = statusBarVisible,
                showNavigationBar = navigationBarVisible,
                showHeader = headerVisible,
                showFooter = footerVisible,
                showHeaderLine = headerLineVisible,
                showFooterLine = footerLineVisible,
                headerPadding = headerPadding,
                footerPadding = footerPadding,
                headerTemplates =
                    templates.take(3).let { if (it.size == 3) it else listOf("", "", "") },
                footerTemplates =
                    templates.drop(3).let { if (it.size == 3) it else listOf("", "", "") },
                values = readerInfoValues,
                tipColor = tipColor,
                dividerColor = tipDividerColor,
                accentColor = context.accentColor,
                textSizeSp = tipTextSize,
                typeface = readerInfoTypeface,
                bookmarkVisible = bookmarkVisible,
                bookmarkInHeader = bookmarkInHeader,
                bookmarkOffset = bookmarkOffset,
                bookmarkDescription = context.getString(R.string.bookmark),
                onContentBounds = ::updateContentBounds,
                onHeaderMeasured = { measured ->
                    if (headerMeasuredHeight != measured) {
                        headerMeasuredHeight = measured
                        updateBookmarkOffset()
                    }
                },
                onHeaderRightGeometry = { position, baseline ->
                    if (headerRightPosition != position || headerRightBaseline != baseline) {
                        headerRightPosition = position
                        headerRightBaseline = baseline
                        updateBookmarkOffset()
                    }
                },
            )
        }
        if (!isInEditMode) {
            upStyle()
            pageRoot.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
                val captionBar = windowInsets.getInsets(WindowInsetsCompat.Type.captionBar())
                val navigationBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
                val displayCutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                // Android 15+ interprets NEVER as ALWAYS for non-floating windows. Preserve
                // the user's choice even when the platform no longer letterboxes the window.
                val avoidCutout = AppConfig.paddingDisplayCutouts || !ReadBookConfig.readBodyToLh
                val newNavigationBarInset = windowInsets.navigationBarHeight
                val newTopInset = maxOf(
                    statusBars.top,
                    captionBar.top,
                    if (avoidCutout && statusBarVisible) displayCutout.top else 0,
                )
                if (statusBarInset != newTopInset) statusBarInset = newTopInset
                if (navigationBarInset != newNavigationBarInset)
                    navigationBarInset = newNavigationBarInset
                val oldPadding =
                    intArrayOf(
                        pageRoot.paddingLeft,
                        pageRoot.paddingTop,
                        pageRoot.paddingRight,
                        pageRoot.paddingBottom,
                    )
                // Nine-patch backgrounds own intrinsic border padding. Read it afresh so
                // rotation or disabling cutout padding cannot retain an old safe inset.
                val backgroundPadding = Rect()
                if (ReadBookConfig.isNineBgImg) pageRoot.background?.getPadding(backgroundPadding)
                pageRoot.setPadding(
                    maxOf(backgroundPadding.left, navigationBars.left,
                        if (avoidCutout) displayCutout.left else 0),
                    maxOf(backgroundPadding.top,
                        if (avoidCutout && !statusBarVisible) displayCutout.top else 0),
                    maxOf(backgroundPadding.right, navigationBars.right,
                        if (avoidCutout) displayCutout.right else 0),
                    maxOf(backgroundPadding.bottom,
                        if (avoidCutout) (displayCutout.bottom - newNavigationBarInset).coerceAtLeast(0) else 0),
                )
                updateBookmarkOffset()
                val paddingChanged =
                    oldPadding[0] != pageRoot.paddingLeft ||
                        oldPadding[1] != pageRoot.paddingTop ||
                        oldPadding[2] != pageRoot.paddingRight ||
                        oldPadding[3] != pageRoot.paddingBottom
                if (paddingChanged && isMainView) readBookActivity?.upBookmarkIndicator()
                windowInsets
            }
            ViewCompat.requestApplyInsets(pageRoot)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        upBg()
        updateBookmarkOffset()
    }

    internal val isCanvasReady: Boolean
        get() =
            isAttachedToWindow &&
                contentView.isAttachedToWindow &&
                contentBounds.width > 0 &&
                contentBounds.height > 0 &&
                !contentView.isLayoutRequested &&
                contentView.isLaidOut &&
                contentView.width == contentBounds.width &&
                contentView.height == contentBounds.height &&
                contentView.top == pageRoot.paddingTop + contentBounds.top

    /** Register once against actual canvas layout, after Compose has measured the page chrome. */
    internal fun doOnCanvasReady(isCurrent: () -> Boolean, action: () -> Unit) {
        canvasReadyCallbacks += CanvasReadyCallback(isCurrent, action)
        awaitCanvasLayout()
    }

    private fun awaitCanvasLayout() {
        if (
            canvasReadyCallbacks.isEmpty() ||
                contentBounds.width <= 0 ||
                contentBounds.height <= 0 ||
                canvasReadyPreDraw != null
        ) return
        // View.layout clears its layout-request flag after onLayoutChange callbacks.
        // Check readiness before drawing, when both native and Compose geometry have settled.
        canvasReadyPreDraw = contentView.doOnPreDraw {
            canvasReadyPreDraw = null
            if (isCanvasReady) {
                val callbacks = canvasReadyCallbacks.toList()
                canvasReadyCallbacks.clear()
                callbacks.forEach { if (it.isCurrent()) it.action() }
            } else if (isAttachedToWindow) {
                awaitCanvasLayout()
            }
        }
        contentView.postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        canvasReadyPreDraw?.removeListener()
        canvasReadyPreDraw = null
        canvasReadyCallbacks.clear()
        super.onDetachedFromWindow()
    }

    private fun updateContentBounds(bounds: IntRect) {
        if (contentBounds != bounds) {
            contentBounds = bounds
            updateContentLayout()
        }
        awaitCanvasLayout()
    }

    private fun updateContentLayout() {
        val params = contentView.layoutParams as? FrameLayout.LayoutParams ?: return
        val top = contentBounds.top.coerceAtLeast(0)
        val height = contentBounds.height.coerceAtLeast(0)
        if (params.topMargin != top || params.height != height) {
            params.topMargin = top
            params.height = height
            contentView.layoutParams = params
        }
    }

    private fun updateBookmarkOffset() {
        if (!bookmarkVisible) return
        val indicatorPadding = if (bookmarkInHeader) 4.dpToPx() else 0
        val x =
            if (bookmarkInHeader)
                pageRoot.paddingRight - bookmarkIndicatorMarginRight(indicatorPadding)
            else 0
        val y =
            if (bookmarkInHeader) {
                bookmarkIndicatorTop(32.dpToPx(), indicatorPadding) - pageRoot.paddingTop
            } else {
                headerHeight - pageRoot.paddingTop
            }
        val offset = IntOffset(x, y)
        if (bookmarkOffset != offset) bookmarkOffset = offset
    }

    fun upStyle() {
        upTipStyle()
        val textColor = ReadBookConfig.textColor
        tipColor = if (ReadTipConfig.tipColor == 0) textColor else ReadTipConfig.tipColor
        tipDividerColor =
            when (ReadTipConfig.tipDividerColor) {
                -1 -> ContextCompat.getColor(context, R.color.divider)
                0 -> textColor
                else -> ReadTipConfig.tipDividerColor
            }
        tipTextSize = ReadTipConfig.tipTextSize
        headerPadding =
            ReaderTipPadding(
                ReadBookConfig.headerPaddingLeft,
                ReadBookConfig.headerPaddingTop,
                ReadBookConfig.headerPaddingRight,
                ReadBookConfig.headerPaddingBottom,
            )
        footerPadding =
            ReaderTipPadding(
                ReadBookConfig.footerPaddingLeft,
                ReadBookConfig.footerPaddingTop,
                ReadBookConfig.footerPaddingRight,
                ReadBookConfig.footerPaddingBottom,
            )
        headerLineVisible = headerVisible && ReadBookConfig.showHeaderLine
        footerLineVisible = footerVisible && ReadBookConfig.showFooterLine
        upStatusBar()
        upNavigationBar()
        upPaddingDisplayCutouts()
        readerInfoValues =
            readerInfoValues.copy(
                time = timeFormat.format(Date(System.currentTimeMillis())),
                battery = battery,
            )
        renderReaderInfo()
    }

    /** 显示状态栏时隐藏header */
    fun upStatusBar() {
        statusBarVisible =
            !ReadBookConfig.hideStatusBar || readBookActivity?.isInMultiWindow == true
    }

    fun upNavigationBar() {
        navigationBarVisible = !ReadBookConfig.hideNavigationBar
    }

    fun upPaddingDisplayCutouts() {
        ViewCompat.requestApplyInsets(pageRoot)
        updateBookmarkOffset()
    }

    private fun upTipStyle() {
        headerVisible =
            when (ReadTipConfig.headerMode) {
                1 -> true
                2 -> false
                else -> !ReadBookConfig.hideStatusBar
            }
        footerVisible = ReadTipConfig.footerMode != 1
        readerInfoTemplates =
            with(ReadTipConfig) {
                listOf(
                    effectiveTemplate(tipHeaderLeftTemplate, tipHeaderLeft),
                    effectiveTemplate(tipHeaderMiddleTemplate, tipHeaderMiddle),
                    effectiveTemplate(tipHeaderRightTemplate, tipHeaderRight),
                    effectiveTemplate(tipFooterLeftTemplate, tipFooterLeft),
                    effectiveTemplate(tipFooterMiddleTemplate, tipFooterMiddle),
                    effectiveTemplate(tipFooterRightTemplate, tipFooterRight),
                )
            }
        readerInfoTypeface = ChapterProvider.typeface
        if (!headerVisible) headerMeasuredHeight = 0
    }

    private fun renderReaderInfo() {
        composeView.invalidate()
        composeView.requestLayout()
        updateBookmarkOffset()
    }

    fun showBookmarkIndicator(show: Boolean) {
        val showInHeader = show && headerVisible
        bookmarkVisible = show
        bookmarkInHeader = showInHeader
        renderReaderInfo()
    }

    /** 更新背景 */
    fun upBg() {
        pageRoot.background =
            LayerDrawable(
                arrayOf(
                    ReadBookConfig.bgMeanColor.toDrawable(),
                    ReadBookConfig.bg,
                )
            )
        upBgAlpha()
    }

    /** 更新背景透明度 */
    fun upBgAlpha() {
        ReadBookConfig.bg?.alpha = (ReadBookConfig.bgAlpha / 100f * 255).toInt()
        pageRoot.invalidate()
    }

    /** 更新时间信息 */
    fun upTime() {
        readerInfoValues =
            readerInfoValues.copy(time = timeFormat.format(Date(System.currentTimeMillis())))
        renderReaderInfo()
    }

    /** 更新电池信息 */
    fun upBattery(battery: Int) {
        this.battery = battery.coerceIn(0, 100)
        readerInfoValues = readerInfoValues.copy(battery = this.battery)
        renderReaderInfo()
    }

    /** 设置内容 */
    fun setContent(
        textPage: TextPage,
        resetPageOffset: Boolean = true,
        chapterPosition: Int = ReadBook.durChapterPos,
        scrollAnchor: ScrollReadAnchor? = null,
    ) {
        if (isMainView && !isScroll) {
            setProgress(textPage)
        } else {
            post {
                setProgress(textPage)
            }
        }
        if (resetPageOffset) {
            resetPageOffset()
        }
        contentView.setContent(textPage)
        if (resetPageOffset && isMainView && isScroll) {
            if (scrollAnchor == null || !contentView.restoreScrollAnchor(scrollAnchor)) {
                contentView.restorePageOffset(chapterPosition)
            }
        }
    }

    internal fun captureScrollAnchor(): ScrollReadAnchor? = contentView.captureScrollAnchor()

    fun invalidateContentView() {
        contentView.invalidate()
    }

    /** 设置无障碍文本 */
    fun setContentDescription(content: String) {
        contentView.contentDescription = content
    }

    /** 重置滚动位置 */
    fun resetPageOffset() {
        contentView.resetPageOffset()
    }

    /** 设置进度 */
    fun setProgress(textPage: TextPage) = textPage.apply {
        val readProgress = readProgress
        val totalPages =
            if (textChapter.isCompleted) {
                pageSize.toString()
            } else {
                val pageSizeInt = pageSize
                if (pageSizeInt <= 0) "-" else "~$pageSizeInt"
            }
        readerInfoValues =
            readerInfoValues.copy(
                bookName = ReadBook.book?.name.orEmpty(),
                chapterTitle = title,
                page = index.plus(1).toString(),
                totalPages = totalPages,
                readProgress = readProgress,
                chapter = chapterIndex.plus(1).toString(),
                totalChapters = chapterSize.toString(),
            )
        renderReaderInfo()
    }

    fun setAutoPager(autoPager: AutoPager?) {
        contentView.setAutoPager(autoPager)
    }

    fun submitRenderTask() {
        contentView.submitRenderTask()
    }

    fun setIsScroll(value: Boolean) {
        isScroll = value
        contentView.setIsScroll(value)
    }

    /** 滚动事件 */
    fun scroll(offset: Int) {
        contentView.scroll(offset)
    }

    fun isAtChapterTop(): Boolean {
        return contentView.isAtChapterTop()
    }

    /** 更新是否开启选择功能 */
    fun upSelectAble(selectAble: Boolean) {
        contentView.selectAble = selectAble
    }

    /**
     * 优先处理页面内单击
     *
     * @return true:已处理, false:未处理
     */
    fun onClick(x: Float, y: Float): Boolean {
        return contentView.click(x - imgBgPaddingStart, y - headerHeight)
    }

    /** 长按事件 */
    fun longPress(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        return contentView.longPress(x - imgBgPaddingStart, y - headerHeight, select)
    }

    /** 选择文本 */
    fun selectText(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        return contentView.selectText(x - imgBgPaddingStart, y - headerHeight, select)
    }

    fun getCurVisiblePage(): TextPage {
        return contentView.getCurVisiblePage()
    }

    fun getReadPosition(): Pair<Int, TextLine>? {
        return contentView.getReadPosition()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        return contentView.getReadAloudPos()
    }

    fun markAsMainView() {
        isMainView = true
        contentView.isMainView = true
    }

    fun selectStartMove(x: Float, y: Float) {
        contentView.selectStartMove(x - imgBgPaddingStart, y - headerHeight)
    }

    fun selectStartMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        contentView.selectStartMoveIndex(relativePagePos, lineIndex, charIndex)
    }

    fun selectStartMoveIndex(textPos: TextPos) {
        contentView.selectStartMoveIndex(textPos)
    }

    fun selectEndMove(x: Float, y: Float) {
        contentView.selectEndMove(x - imgBgPaddingStart, y - headerHeight)
    }

    fun selectEndMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        contentView.selectEndMoveIndex(relativePagePos, lineIndex, charIndex)
    }

    fun selectEndMoveIndex(textPos: TextPos) {
        contentView.selectEndMoveIndex(textPos)
    }

    fun getReverseStartCursor(): Boolean {
        return contentView.reverseStartCursor
    }

    fun getReverseEndCursor(): Boolean {
        return contentView.reverseEndCursor
    }

    fun isLongScreenShot(): Boolean {
        return contentView.longScreenshot
    }

    fun resetReverseCursor() {
        contentView.resetReverseCursor()
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        contentView.cancelSelect(clearSearchResult)
    }

    fun createBookmark(): Bookmark? {
        return contentView.createBookmark()
    }

    fun createHighlight(style: HighlightStyle): BookHighlight? {
        return contentView.createHighlight(style)
    }

    fun relativePage(relativePagePos: Int): TextPage {
        return contentView.relativePage(relativePagePos)
    }

    val textPage
        get() = contentView.textPage

    val selectedText: String
        get() = contentView.getSelectedText()

    val selectStartPos
        get() = contentView.selectStart
}
