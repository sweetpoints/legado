package io.legado.app.ui.book.read

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.Settings
import android.util.AttributeSet
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.invisible
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible

/** Compatibility host for the native reading canvas. Every menu control is rendered by Compose. */
class ReadMenu @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    var canShowMenu = false
    private val callBack
        get() = activity as CallBack

    private var bottomState by mutableStateOf(ReadMenuBottomState())
    private var topState by mutableStateOf(ReadMenuTopState())
    private var menuVisible by mutableStateOf(false)
    private var animateMenu by mutableStateOf(false)
    private var progressDragging by mutableStateOf(false)
    private var confirmSkipToChapter = false
    private var isMenuOutAnimating = false
    private val handler = buildMainHandler()
    private var animationComplete: Runnable? = null
    private val immersiveMenu
        get() = AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0

    private var bgColor = context.bottomBackground
    private var textColor = context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
    private val showBrightnessView
        get() = context.getPrefBoolean(PreferKey.showBrightnessView, true)

    init {
        val composition =
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    LegadoComposeTheme {
                        ReadMenuScreen(
                            visible = menuVisible,
                            animate = animateMenu,
                            dragging = progressDragging,
                            top = topState,
                            bottom = bottomState,
                            dismiss = { runMenuOut() },
                            back = { activity?.onBackPressedDispatcher?.onBackPressed() },
                            bookInfo = callBack::openBookInfoActivity,
                            chapterClick = ::openChapterUrl,
                            chapterLongClick = { topState = topState.copy(browserPrompt = true) },
                            customClick = ::customButton,
                            toolbarAction = ::toolbarAction,
                            openPopup = ::openPopup,
                            dismissPopup = ::dismissPopup,
                            popupAction = ::popupAction,
                            toggleBrightness = {
                                context.putPrefBoolean("brightnessAuto", !brightnessAuto())
                                upBrightnessState()
                            },
                            brightnessChange = { setScreenBrightness(it.toFloat()) },
                            brightnessCommit = {
                                AppConfig.readBrightness = it
                                topState = topState.copy(brightness = it)
                            },
                            swapBrightness = {
                                AppConfig.brightnessVwPos = !AppConfig.brightnessVwPos
                                topState =
                                    topState.copy(brightnessRight = AppConfig.brightnessVwPos)
                            },
                            bottomAction = ::onBottomAction,
                            progressDragging = { progressDragging = it },
                            progressCommit = ::commitProgress,
                            chapterConfirm = ::confirmChapter,
                            chapterCancel = ::cancelChapter,
                        )
                        if (topState.browserPrompt && !topState.localBook) {
                            ReadChapterBrowserPrompt(
                                { topState = topState.copy(browserPrompt = false) },
                                { browser ->
                                    AppConfig.readUrlInBrowser = browser
                                    topState = topState.copy(browserPrompt = false)
                                },
                            )
                        }
                    }
                }
            }
        addView(composition, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        reset()
        upBrightnessState()
        applyNavigationBarPadding()
    }

    fun reset() {
        bgColor =
            if (immersiveMenu)
                runCatching { ReadBookConfig.durConfig.curBgStr().toColorInt() }
                    .getOrDefault(context.bottomBackground)
            else context.bottomBackground
        textColor =
            if (immersiveMenu) ReadBookConfig.durConfig.curTextColor()
            else context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
        bottomState =
            bottomState.copy(
                background = Color(bgColor),
                foreground = Color(textColor),
                nightTheme = AppConfig.isNightTheme,
                eInk = AppConfig.isEInkMode,
                showMemo = context.getPrefBoolean(PreferKey.showBookMemo, false),
            )
        topState =
            topState.copy(
                background = Color(if (immersiveMenu) bgColor else context.primaryColor),
                foreground = Color(if (immersiveMenu) textColor else context.primaryTextColor),
                additionForeground =
                    Color(
                        if (immersiveMenu)
                            ColorUtils.withAlpha(ColorUtils.lightenColor(textColor), .75f)
                        else context.primaryTextColor
                    ),
                showAddition = AppConfig.showReadTitleBarAddition,
                chapterNameOnly = AppConfig.showReadTitleChapterNameOnly,
                brightnessRight = AppConfig.brightnessVwPos,
            )
    }

    fun refreshMenuColorFilter() = reset()

    fun upBrightnessState() {
        topState =
            topState.copy(
                showBrightness = showBrightnessView,
                brightnessAutomatic = brightnessAuto(),
                brightness = AppConfig.readBrightness,
            )
        setScreenBrightness(AppConfig.readBrightness.toFloat())
    }

    private fun brightnessAuto() =
        context.getPrefBoolean("brightnessAuto", true) || !showBrightnessView

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        animationComplete?.let(handler::removeCallbacks)
        isMenuOutAnimating = false
        callBack.onMenuShow()
        visible()
        reset()
        upBookView()
        topState = topState.copy(actions = callBack.readerToolbarActions())
        animateMenu = anim
        menuVisible = true
        callBack.upSystemUiVisibility()
        completeAnimation(anim) {
            callBack.upSystemUiVisibility()
            if (!LocalConfig.readMenuHelpVersionIsLast) callBack.showHelp()
        }
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode, onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating) return
        callBack.onMenuHide()
        if (!isVisible) return
        animationComplete?.let(handler::removeCallbacks)
        isMenuOutAnimating = true
        dismissPopup()
        topState = topState.copy(browserPrompt = false)
        bottomState = bottomState.copy(pendingChapter = null)
        animateMenu = anim
        menuVisible = false
        completeAnimation(anim) {
            invisible()
            canShowMenu = false
            isMenuOutAnimating = false
            onMenuOutEnd?.invoke()
            callBack.upSystemUiVisibility()
        }
    }

    private fun completeAnimation(animated: Boolean, action: () -> Unit) {
        if (!animated) {
            action()
            return
        }
        val completion = Runnable {
            animationComplete = null
            action()
        }
        animationComplete = completion
        handler.postDelayed(completion, 150)
    }

    fun upBookView() {
        val chapter = ReadBook.curTextChapter
        topState =
            topState.copy(
                title = ReadBook.book?.name.orEmpty(),
                chapterName = chapter?.title.orEmpty(),
                chapterUrl =
                    if (ReadBook.isLocalBook) "" else chapter?.chapter?.getAbsoluteURL().orEmpty(),
                localBook = ReadBook.isLocalBook,
                sourceName =
                    ReadBook.bookSource?.bookSourceName ?: context.getString(R.string.book_source),
                showCustomButton = ReadBook.bookSource?.customButton == true,
            )
        if (chapter != null) {
            bottomState =
                bottomState.copy(
                    previousEnabled = ReadBook.durChapterIndex != 0,
                    nextEnabled = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1,
                )
            upSeekBar()
        }
    }

    fun updateToolbarActions(actions: List<ReaderToolbarAction>) {
        topState = topState.copy(actions = actions)
    }

    fun openPopup(popup: ReaderPopup) {
        val entries =
            if (popup == ReaderPopup.Source) sourceEntries() else callBack.readerPopupEntries(popup)
        topState = topState.copy(popup = popup, popupEntries = entries)
    }

    private fun sourceEntries(): List<ReaderPopupEntry> {
        val hasLogin = ReadBook.bookSource?.hasLogin() == true
        val canPay =
            hasLogin &&
                ReadBook.curTextChapter?.isVip == true &&
                ReadBook.curTextChapter?.isPay != true
        return buildList {
            if (hasLogin) add(ReaderPopupEntry(context.getString(R.string.login), "login"))
            if (canPay) add(ReaderPopupEntry(context.getString(R.string.chapter_pay), "chapterPay"))
            add(ReaderPopupEntry(context.getString(R.string.edit_book_source), "editSource"))
            add(ReaderPopupEntry(context.getString(R.string.disable_book_source), "disableSource"))
        }
    }

    private fun dismissPopup() {
        topState = topState.copy(popup = null, popupEntries = emptyList())
    }

    private fun popupAction(value: String) {
        val popup = topState.popup ?: return
        dismissPopup()
        if (popup == ReaderPopup.Source)
            when (value) {
                "login" -> callBack.showLogin()
                "chapterPay" -> callBack.payAction()
                "editSource" -> callBack.openSourceEditActivity()
                "disableSource" -> callBack.disableSource()
            }
        else callBack.readerPopupAction(popup, value)
    }

    private fun toolbarAction(id: Int, longPress: Boolean) {
        if (longPress && id == R.id.menu_change_source) openPopup(ReaderPopup.ChangeSource)
        else if (longPress && id == R.id.menu_refresh) openPopup(ReaderPopup.Refresh)
        else if (!longPress) callBack.readerToolbarAction(id)
    }

    private fun openChapterUrl() {
        if (ReadBook.isLocalBook) return
        val url = topState.chapterUrl
        if (AppConfig.readUrlInBrowser) context.openUrl(url.substringBefore(",{"))
        else
            Coroutine.async {
                context.startActivity<WebViewActivity> {
                    val source = ReadBook.bookSource
                    putExtra("title", topState.chapterName)
                    putExtra("url", url)
                    putExtra("sourceOrigin", source?.bookSourceUrl)
                    putExtra("sourceName", source?.bookSourceName)
                    putExtra("sourceType", source?.getSourceType())
                }
            }
    }

    private fun customButton(longPress: Boolean) {
        val book = ReadBook.book ?: return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
        activity?.let { owner ->
            SourceCallBack.callBackBtn(
                owner,
                if (longPress) SourceCallBack.LONG_CLICK_CUSTOM_BUTTON
                else SourceCallBack.CLICK_CUSTOM_BUTTON,
                ReadBook.bookSource,
                book,
                chapter,
                BookType.text,
            )
        }
    }

    override fun onDetachedFromWindow() {
        animationComplete?.let(handler::removeCallbacks)
        animationComplete = null
        contentObserver?.let(context.contentResolver::unregisterContentObserver)
        contentObserver = null
        super.onDetachedFromWindow()
    }

    /** 系统亮度监听，在高阳光亮度时启用 */
    private var contentObserver: ContentObserver? = null

    /** 设置屏幕亮度 */
    fun setScreenBrightness(value: Float) {
        // Replace the observer before applying a new slider value, so old callbacks cannot own the
        // window.
        contentObserver?.let(context.contentResolver::unregisterContentObserver)
        contentObserver = null
        activity?.run {
            fun setBrightness(value: Float) {
                val params = window.attributes
                params.screenBrightness = value
                window.attributes = params
            }
            val autoBrightness = BRIGHTNESS_OVERRIDE_NONE
            if (brightnessAuto() || value == autoBrightness) {
                setBrightness(autoBrightness)
                return
            }
            val brightness = if (value < 1f) 0.004f else value / 255f
            var isSunMax = false
            if (brightness == 1f) {
                val sysBrightness = getCurrentBrightness(context)
                if (sysBrightness == 255) {
                    isSunMax = true
                }
            }
            if (isSunMax) {
                contentObserver =
                    object : ContentObserver(buildMainHandler()) {
                        override fun onChange(selfChange: Boolean, uri: Uri?) {
                            super.onChange(selfChange, uri)
                            if (contentObserver !== this) return
                            if (
                                uri == Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                            ) {
                                val sysBrightness = getCurrentBrightness(context)
                                if (sysBrightness < 200) {
                                    setBrightness(brightness)
                                    contentObserver?.let {
                                        context.contentResolver.unregisterContentObserver(it)
                                    }
                                    contentObserver = null
                                } else if (sysBrightness < 255) {
                                    setBrightness(brightness)
                                } else {
                                    setBrightness(autoBrightness)
                                }
                            }
                        }
                    }
                val brightnessUri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                context.contentResolver.registerContentObserver(
                    brightnessUri,
                    false,
                    contentObserver!!,
                )
                setBrightness(autoBrightness)
            } else {
                setBrightness(brightness)
            }
        }
    }

    /** 获取系统亮度值 */
    private fun getCurrentBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
        } catch (_: Settings.SettingNotFoundException) {
            -1
        }
    }

    private fun commitProgress(progress: Int) {
        when (AppConfig.progressBarBehavior) {
            "page" -> ReadBook.skipToPage(progress)
            "chapter" ->
                if (confirmSkipToChapter) callBack.skipToChapter(progress)
                else bottomState = bottomState.copy(pendingChapter = progress)
        }
    }

    private fun confirmChapter() {
        val chapter = bottomState.pendingChapter ?: return
        bottomState = bottomState.copy(pendingChapter = null)
        confirmSkipToChapter = true
        callBack.skipToChapter(chapter)
    }

    private fun cancelChapter() {
        bottomState = bottomState.copy(pendingChapter = null)
        upSeekBar()
    }

    private fun onBottomAction(action: ReadMenuAction) {
        when (action) {
            ReadMenuAction.Search -> runMenuOut { callBack.openSearchActivity(null) }
            ReadMenuAction.AutoPage -> runMenuOut { callBack.autoPage() }
            ReadMenuAction.ReplaceRule -> callBack.openReplaceRule()
            ReadMenuAction.NightTheme -> {
                AppConfig.isNightTheme = !AppConfig.isNightTheme
                ThemeConfig.applyDayNight(context)
            }
            ReadMenuAction.PreviousChapter ->
                ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            ReadMenuAction.NextChapter -> ReadBook.moveToNextChapter(true)
            ReadMenuAction.Catalog -> runMenuOut { callBack.openChapterList() }
            ReadMenuAction.ReadAloud ->
                runMenuOut {
                    if (BaseReadAloudService.isRun) callBack.showReadAloudDialog()
                    else callBack.onClickReadAloud()
                }
            ReadMenuAction.ReadAloudSettings -> runMenuOut { callBack.showReadAloudDialog() }
            ReadMenuAction.Style -> runMenuOut { callBack.showReadStyle() }
            ReadMenuAction.Settings -> runMenuOut { callBack.showMoreSetting() }
            ReadMenuAction.Memo -> runMenuOut { callBack.showBookMemo() }
        }
    }

    fun upSeekBar() {
        val maximum: Int
        val progress: Int
        when (AppConfig.progressBarBehavior) {
            "page" -> {
                val chapter = ReadBook.curTextChapter ?: return
                maximum = chapter.pageSize - 1
                progress = ReadBook.durPageIndex
            }
            "chapter" -> {
                maximum = ReadBook.simulatedChapterSize - 1
                progress = ReadBook.durChapterIndex
            }
            else -> return
        }
        bottomState = bottomState.copy(maximum = maximum.coerceAtLeast(0), progress = progress)
    }

    fun setSeekPage(seek: Int) {
        bottomState = bottomState.copy(progress = seek)
    }

    fun setAutoPage(autoPage: Boolean) {
        bottomState = bottomState.copy(autoPage = autoPage)
    }

    interface CallBack {
        fun readerToolbarActions(): List<ReaderToolbarAction>

        fun readerPopupEntries(popup: ReaderPopup): List<ReaderPopupEntry>

        fun readerPopupAction(popup: ReaderPopup, value: String)

        fun readerToolbarAction(id: Int)

        fun autoPage()

        fun openReplaceRule()

        fun openChapterList()

        fun openSearchActivity(searchWord: String?)

        fun openSourceEditActivity()

        fun openBookInfoActivity()

        fun showReadStyle()

        fun showMoreSetting()

        fun showBookMemo()

        fun showReadAloudDialog()

        fun upSystemUiVisibility()

        fun onClickReadAloud()

        fun showHelp()

        fun showLogin()

        fun payAction()

        fun disableSource()

        fun skipToChapter(index: Int)

        fun onMenuShow()

        fun onMenuHide()
    }
}
