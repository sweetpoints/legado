package io.legado.app.ui.book.read

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.model.browser.BrowserRequest
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.browser.BrowserNavigation
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.theme.contrastRatio
import io.legado.app.ui.theme.contrastingForeground
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reader-owned menu state and actions; the host composes Content directly. */
class ReaderMenuController(
    private val activity: AppCompatActivity,
    private val callBack: ReaderMenuCallbacks,
    private val visibilityChanged: (Boolean) -> Unit = {},
) {
    var canShowMenu = false
    private val context
        get() = activity

    var isVisible by mutableStateOf(false)
        private set

    private fun changeVisibility(visible: Boolean) {
        isVisible = visible
        visibilityChanged(visible)
    }

    private var bottomState by mutableStateOf(ReadMenuBottomState())
    private var topState by mutableStateOf(ReadMenuTopState())
    private var menuVisible by mutableStateOf(false)
    private var animateMenu by mutableStateOf(false)
    private var progressDragging by mutableStateOf(false)
    private var confirmSkipToChapter = false
    private var isMenuOutAnimating = false
    private val handler = buildMainHandler()
    private var animationComplete: Runnable? = null
    private var disposed = false
    private var popupOwner: Pair<String, String>? = null
    private val immersiveMenu
        get() = AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0

    private var bgColor = context.bottomBackground
    private var textColor = context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
    private val showBrightnessView
        get() = context.getPrefBoolean(PreferKey.showBrightnessView, true)

    init {
        reset()
        upBrightnessState()
    }

    @Composable
    fun Content() {
        LegadoComposeTheme {
            ReadMenuScreen(
                visible = menuVisible,
                animate = animateMenu,
                dragging = progressDragging,
                top = topState,
                bottom = bottomState,
                dismiss = { runMenuOut() },
                back = { activity.onBackPressedDispatcher.onBackPressed() },
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
                    topState = topState.copy(brightnessRight = AppConfig.brightnessVwPos)
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

    fun reset() {
        fun readable(preferred: Int, background: Int): Int =
            if (contrastRatio(preferred, background) >= 4.5) preferred
            else contrastingForeground(background)
        bgColor =
            if (immersiveMenu)
                runCatching { ReadBookConfig.durConfig.curBgStr().toColorInt() }
                    .getOrDefault(context.bottomBackground)
            else context.bottomBackground
        textColor =
            if (immersiveMenu) ReadBookConfig.durConfig.curTextColor()
            else context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
        textColor = readable(textColor, bgColor)
        val topBackground = if (immersiveMenu) bgColor else context.primaryColor
        val topForeground =
            readable(if (immersiveMenu) textColor else context.primaryTextColor, topBackground)
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
                background = Color(topBackground),
                foreground = Color(topForeground),
                additionForeground =
                    Color(
                        readable(
                            if (immersiveMenu)
                                ColorUtils.withAlpha(ColorUtils.lightenColor(textColor), .75f)
                            else topForeground,
                            topBackground,
                        )
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
        changeVisibility(true)
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
            changeVisibility(false)
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
        if (popupOwner != currentBookOwner()) dismissPopup()
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
        popupOwner = currentBookOwner()
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

    private fun currentBookOwner(): Pair<String, String>? =
        ReadBook.book?.let { it.bookUrl to it.origin }

    private fun dismissPopup() {
        popupOwner = null
        topState = topState.copy(popup = null, popupEntries = emptyList())
    }

    private fun popupAction(value: String) {
        val popup = topState.popup ?: return
        val stillCurrent = popupOwner == currentBookOwner()
        dismissPopup()
        if (
            !stillCurrent ||
                disposed ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        )
            return
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
        val book = ReadBook.book ?: return
        val source = ReadBook.bookSource
        val chapterIndex = ReadBook.durChapterIndex
        val url = topState.chapterUrl
        val title = topState.chapterName
        if (AppConfig.readUrlInBrowser) {
            context.openUrl(url.substringBefore(",{"))
            return
        }
        val request =
            BrowserRequest(
                url = url,
                title = title,
                sourceName = source?.bookSourceName.orEmpty(),
                sourceOrigin = source?.bookSourceUrl.orEmpty(),
                sourceType = source?.getSourceType() ?: 0,
            )
        activity.lifecycleScope.launch {
            var ticket: String? = null
            try {
                ticket = BrowserNavigation.prepare(activity.applicationContext, request)
                currentCoroutineContext().ensureActive()
                if (
                    disposed ||
                        activity.isFinishing ||
                        activity.isDestroyed ||
                        !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                        ReadBook.book !== book ||
                        ReadBook.bookSource !== source ||
                        ReadBook.durChapterIndex != chapterIndex ||
                        topState.chapterUrl != url ||
                        topState.chapterName != title
                )
                    return@launch
                BrowserNavigation.startPrepared(activity, checkNotNull(ticket))
                ticket = null
            } catch (canceled: kotlinx.coroutines.CancellationException) {
                throw canceled
            } catch (error: Exception) {
                io.legado.app.constant.AppLog.put("打开章节网页失败", error)
            } finally {
                ticket?.let { prepared ->
                    withContext(NonCancellable) {
                        BrowserNavigation.abandon(activity.applicationContext, prepared)
                    }
                }
            }
        }
    }

    private fun customButton(longPress: Boolean) {
        val book = ReadBook.book ?: return
        val source = ReadBook.bookSource
        val bookUrl = book.bookUrl
        val chapterIndex = ReadBook.durChapterIndex
        activity.lifecycleScope.launch {
            val chapter =
                withContext(IO) {
                    appDb.bookChapterDao.getChapter(bookUrl, chapterIndex)
                }
            if (
                disposed ||
                    activity.isFinishing ||
                    !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                    ReadBook.book !== book ||
                    ReadBook.bookSource !== source ||
                    ReadBook.durChapterIndex != chapterIndex
            )
                return@launch
            SourceCallBack.callBackBtn(
                activity,
                if (longPress) SourceCallBack.LONG_CLICK_CUSTOM_BUTTON
                else SourceCallBack.CLICK_CUSTOM_BUTTON,
                source,
                book,
                chapter,
                BookType.text,
            )
        }
    }

    fun dispose() {
        disposed = true
        animationComplete?.let(handler::removeCallbacks)
        animationComplete = null
        contentObserver?.let(context.contentResolver::unregisterContentObserver)
        contentObserver = null
    }

    /** 系统亮度监听，在高阳光亮度时启用 */
    private var contentObserver: ContentObserver? = null

    /** 设置屏幕亮度 */
    fun setScreenBrightness(value: Float) {
        // Replace the observer before applying a new slider value, so old callbacks cannot own the
        // window.
        contentObserver?.let(context.contentResolver::unregisterContentObserver)
        contentObserver = null
        activity.run {
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
}
