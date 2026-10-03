package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.get
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.script.rhino.runScriptWithContext
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.replaceBookAfterSourceChange
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.HighlightColors
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyles
import io.legado.app.help.IntentData
import io.legado.app.help.TTS
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.removeType
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.getSourceType
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.MobiFile
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.receiver.TimeBatteryReceiver
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.changesource.ChangeChapterSourceDialog
import io.legado.app.ui.book.info.BookInfoNavigation
import io.legado.app.ui.book.read.config.AutoReadDialog
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.BG_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.REVIEW_ICON_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_ACCENT_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.UNDERLINE_COLOR
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.ui.book.read.config.ReaderMenuConfigDialog
import io.legado.app.ui.book.read.config.TextSelectMenuConfigDialog
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TIP_COLOR
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TIP_DIVIDER_COLOR
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TITLE_COLOR
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TITLE_NUMBER_COLOR
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.searchContent.SearchContentActivity
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.dict.DictDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.highlight.HighlightRuleActivity
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.Debounce
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.dismissDialogFragment
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isTrue
import io.legado.app.utils.launch
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.sysScreenOffTime
import io.legado.app.utils.throttle
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 阅读界面 */
class ReadBookActivity :
    BaseReadBookActivity(),
    View.OnTouchListener,
    ReadView.CallBack,
    TextActionMenu.CallBack,
    ContentTextView.CallBack,
    ReaderMenuCallbacks,
    ReaderSearchCallbacks,
    ReadAloudDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeChapterSourceDialog.CallBack,
    ReadBook.CallBack,
    AutoReadDialog.CallBack,
    TxtTocRuleDialog.CallBack,
    ColorPickerDialogListener,
    HighlightStyleDialog.StyleHost,
    LayoutProgressListener {

    private val tocActivity =
        registerForActivityResult(TocActivityResult()) {
            it?.let {
                val highlightLayoutTitleLength =
                    (it[TocActivityResult.HIGHLIGHT_LAYOUT_TITLE_LENGTH_INDEX] as Int).takeUnless {
                        titleLength ->
                        titleLength == TocActivityResult.NO_HIGHLIGHT_LAYOUT_TITLE_LENGTH
                    }
                viewModel.openChapter(
                    it[0] as Int,
                    it[1] as Int,
                    highlightLayoutTitleLength,
                    (it[TocActivityResult.HIGHLIGHT_ANCHOR_TEXT_INDEX] as String).takeIf(
                        String::isNotEmpty
                    ),
                    pdfPageIndex =
                        (it[TocActivityResult.PDF_PAGE_INDEX] as Int).takeIf { page -> page >= 0 },
                )
            }
        }
    private val sourceEditActivity =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upBookSource {
                    resetReviewSummaryState()
                    ReadBook.loadContent(resetPageOffset = false)
                    upMenuView()
                }
            }
        }
    private val replaceActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                viewModel.replaceRuleChanged()
            }
        }
    private val searchContentActivity =
        registerForActivityResult(StartActivityContract(SearchContentActivity::class.java)) {
            val data = it.data ?: return@registerForActivityResult
            val key = data.getLongExtra("key", System.currentTimeMillis())
            val index = data.getIntExtra("index", 0)
            val searchResult = IntentData.get<SearchResult>("searchResult$key")
            val searchResultList = IntentData.get<List<SearchResult>>("searchResultList$key")
            if (searchResult != null && searchResultList != null) {
                viewModel.searchContentQuery = searchResult.query
                searchMenu.upSearchResultList(searchResultList)
                isShowingSearchResult = true
                viewModel.searchResultIndex = index
                searchMenu.updateSearchResultIndex(index)
                searchMenu.selectedSearchResult?.let { currentResult ->
                    ReadBook.saveCurrentBookProgress() // 退出全文搜索恢复此时进度
                    skipToSearch(currentResult)
                    showActionMenu()
                }
            }
        }
    private val bookInfoActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            } else {
                ReadBook.loadOrUpContent()
            }
        }
    private val selectImageDir =
        registerForActivityResult(HandleFileContract()) {
            it.uri?.let { uri ->
                ACache.get().put(AppConst.imagePathKey, uri.toString())
                viewModel.saveImage(it.value, uri)
            }
        }
    private val readView by lazy { ReadView(this).apply { id = R.id.read_view } }
    override val readMenu by lazy { ReaderMenuController(this, this) }
    override val searchMenu by lazy { ReaderSearchControls(this, this) }
    val readAloudControlsVisible
        get() = aloudControls.visible

    val readAloudControlsBounds
        get() = aloudControls.bounds

    private var selectionState by mutableStateOf(ReaderSelectionState())
    private val selectionCursorSize
        get() = 24.dpToPx().toFloat()

    private var menu: Menu? = null
    @get:SuppressLint("RestrictedApi")
    private val composeReaderMenu by lazy {
        MenuBuilder(this).also { menuInflater.inflate(R.menu.book_read, it) }
    }
    private var bookInfoNavigationJob: Job? = null
    private var bookInfoNavigationEpoch = 0L
    private var readerInitializationEpoch = 0L
    private var backupJob: Job? = null
    private var bookmarkJob: Job? = null
    private var replacePreviewJob: Job? = null
    private var replacePreviewGeneration = 0L
    private val bookmarkToggleMutex = Mutex()
    private var bookmarkTogglePending = false
    private var bookmarkBookKey: Pair<String, String>? = null
    private var bookmarks: List<Bookmark> = emptyList()
    private var tts: TTS? = null
    val textActionMenu: TextActionMenu by lazy {
        TextActionMenu(this, this)
    }
    override val isInitFinish: Boolean
        get() = viewModel.isInitFinish

    override val isScroll: Boolean
        get() = readView.isScroll

    private val isAutoPage
        get() = readView.isAutoPage

    override var isShowingSearchResult = false
    override var isSelectingSearchResult = false
        set(value) {
            field = value && isShowingSearchResult
        }

    private val timeBatteryReceiver = TimeBatteryReceiver()
    private var screenTimeOut: Long = 0
    private var loadStates: Boolean = false
    override val pageFactory
        get() = readView.pageFactory

    override val pageDelegate
        get() = readView.pageDelegate

    override val headerHeight: Int
        get() = readView.curPage.headerHeight

    override val imgBgPaddingStart: Int
        get() = readView.curPage.imgBgPaddingStart

    private val nextPageDebounce by lazy { Debounce { keyPage(PageDirection.NEXT) } }
    private val prevPageDebounce by lazy { Debounce { keyPage(PageDirection.PREV) } }
    private var bookChanged = false
    private var pageChanged = false
    private val aloudControls by lazy {
        ReadAloudComposeControls(
            this,
            { if (BaseReadAloudService.pause) ReadAloud.resume(this) else ReadAloud.pause(this) },
            ::backToSpeakingPosition,
            { ReadBook.readAloud() },
        ) {
            updateReadAloudFloatBar()
        }
    }
    private val restoreAloudFollowRunnable = Runnable { restoreAloudFollowOnVisiblePage() }
    /** 最近一次朗读进度的章内字符位置; 供"回到朗读位置"在同章内即时跳转 */
    private var lastReadAloudChapterStart = -1
    private var lastReadAloudChapterIndex = -1
    private val handler by lazy { buildMainHandler() }
    private val screenOffRunnable by lazy { Runnable { keepScreenOn(false) } }
    private val executor = ReadBook.executor
    private val upSeekBarThrottle =
        throttle(200) {
            runOnUiThread {
                upSeekBarProgress()
                readMenu.upSeekBar()
            }
        }
    private var reviewSummaryAppliedKey: String? = null
    private var reviewSummaryLoadingKey: String? = null
    private var lastReviewDialogRequestAt = 0L
    private var reviewSummaryRequestToken = 0L
    private val reviewSummaryCache =
        object : LinkedHashMap<String, ReviewRuleParser.SummaryResult>(8, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ReviewRuleParser.SummaryResult>
            ): Boolean = size > 5
        }
    private val reviewSummaryPrefetchingKeys = HashSet<String>()

    // 恢复跳转前进度对话框的交互结果
    private var confirmRestoreProcess: Boolean? = null
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }
    private var justInitData: Boolean = false
    private var syncDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        editingHighlightTime =
            savedInstanceState?.getLong(STATE_EDITING_HIGHLIGHT)?.takeIf { it != 0L }
        editingHighlightOwner = savedInstanceState?.getString(STATE_EDITING_HIGHLIGHT_OWNER)
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("pdfZoom", readView.pdfZoom.save())
        outState.putBundle("aloudControls", aloudControls.save())
        editingHighlightTime?.let { outState.putLong(STATE_EDITING_HIGHLIGHT, it) }
        editingHighlightOwner?.let { outState.putString(STATE_EDITING_HIGHLIGHT_OWNER, it) }
        super.onSaveInstanceState(outState)
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        ReaderHostScreen(
            readView = readView,
            readMenu = readMenu,
            searchMenu = searchMenu,
            aloudControls = aloudControls,
            selection = selectionState,
            selectionColor = Color(accentColor),
            selectionBegin = { textActionMenu.dismiss() },
            selectionMove = ::moveSelectionCursor,
            selectionEnd = ::finishSelectionCursor,
            contextMenu = contextMenuState,
            contextDismiss = ::dismissContextMenu,
            contextAction = ::performContextAction,
            navigationVisible = navigationBarVisible,
            navigationColor = Color(bottomBackground),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        super.onComposeCreated(savedInstanceState)
        viewModel.pendingSourceReimport.observe(this) { showPendingSourceReimport() }
        viewModel.resourceRefreshing.observe(this) { loading ->
            if (readView.pageFactory.isRefreshingResources != loading) {
                if (loading) readView.updateScrollReadPosition()
                readView.pageFactory.isRefreshingResources = loading
                upContent()
            }
        }
        readView.pdfZoom.restore(savedInstanceState?.getBundle("pdfZoom"))
        aloudControls.restore(savedInstanceState?.getBundle("aloudControls"))
        window.setBackgroundDrawable(null)
        upScreenTimeOut()
        ReadBook.register(this)
        onBackPressedDispatcher.addCallback(this) {
            if (isShowingSearchResult) {
                exitSearchMenu()
                restoreLastBookProcess()
                return@addCallback
            }
            // 拦截返回供恢复阅读进度
            if (ReadBook.lastBookProgress != null && confirmRestoreProcess != false) {
                restoreLastBookProcess()
                return@addCallback
            }
            if (BaseReadAloudService.isPlay()) {
                ReadAloud.pause(this@ReadBookActivity)
                toastOnUi(R.string.read_aloud_pause)
                return@addCallback
            }
            if (isAutoPage) {
                autoPageStop()
                return@addCallback
            }
            if (getPrefBoolean("disableReturnKey") && !menuLayoutIsVisible) {
                return@addCallback
            }
            finish()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        viewModel.initReadBookConfig(intent)
        ChapterProvider.clearReviewProviders()
        initializeReaderData(intent)
        justInitData = true
    }

    override fun initializeReaderData(requestIntent: Intent) {
        val requestEpoch = ++readerInitializationEpoch
        val page = readView.curPage
        var started = false
        fun isCurrent(): Boolean =
            !started &&
                requestEpoch == readerInitializationEpoch &&
                intent === requestIntent &&
                readView.curPage === page &&
                !isFinishing &&
                !isDestroyed &&
                readView.isAttachedToWindow &&
                page.isAttachedToWindow
        fun waitForCanvas() {
            readView.doOnLayout {
                if (!isCurrent()) return@doOnLayout
                page.doOnCanvasReady(::isCurrent) {
                    Looper.myQueue().addIdleHandler {
                        if (isCurrent()) {
                            if (page.isCanvasReady) {
                                started = true
                                viewModel.initData(requestIntent)
                            } else {
                                waitForCanvas()
                            }
                        }
                        false
                    }
                }
            }
        }
        waitForCanvas()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dismissContextMenu()
        textActionMenu.dismiss()
        readView.cancelSelect()
        editingHighlight = null
        resetBookmarkObserver()
        resetReviewSummaryState()
        initializeReaderData(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        upSystemUiVisibility()
        if (hasFocus) {
            readMenu.upBrightnessState()
        } else if (!menuLayoutIsVisible) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        upSystemUiVisibility()
        readView.upStatusBar()
        upBookmarkIndicator()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        if (!isTopResumedActivity) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        ReadBook.readStartTime = System.currentTimeMillis()
        if (bookChanged) {
            bookChanged = false
            ReadBook.callBack = this
            initializeReaderData(intent)
            justInitData = true
        } else {
            // web端阅读时，app处于阅读界面，本地记录会覆盖web保存的进度，在此处恢复
            ReadBook.webBookProgress?.let {
                ReadBook.setProgress(it)
                ReadBook.webBookProgress = null
            }
        }
        upSystemUiVisibility()
        registerReceiver(timeBatteryReceiver, timeBatteryReceiver.filter)
        readView.upTime()
        updateReadAloudFloatBar()
        screenOffTimerStart()
        // 网络监听，当从无网切换到网络环境时同步进度（注意注册的同时就会收到监听，因此界面激活时无需重复执行同步操作）
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (
                AppConfig.syncBookProgressPlus &&
                    NetworkUtils.isAvailable() &&
                    !justInitData &&
                    ReadBook.inBookshelf
            ) {
                ReadBook.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        showPendingSourceReimport()
    }

    private fun showPendingSourceReimport() {
        val pending = viewModel.pendingSourceReimport.value ?: return
        if (
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                supportFragmentManager.isStateSaved
        )
            return
        viewModel.pendingSourceReimport.value = null
        if (ReadBook.book?.bookUrl != pending.bookUrl || ReadBook.book?.origin != pending.sourceUrl)
            return
        if (supportFragmentManager.findFragmentByTag("readerSourceReimport") != null) return
        ImportBookSourceDialog(
                pending.json,
                reimportBookUrl = pending.bookUrl,
                reimportSourceUrl = pending.sourceUrl,
            )
            .show(supportFragmentManager, "readerSourceReimport")
    }

    override fun onPause() {
        super.onPause()
        readView.cancelTouchGestures()
        autoPageStop()
        backupJob?.cancel()
        readView.updateScrollReadPosition()
        ReadBook.saveRead()
        ReadBook.cancelPreDownloadTask()
        unregisterReceiver(timeBatteryReceiver)
        upSystemUiVisibility()
        if (!BuildConfig.DEBUG && ReadBook.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.syncProgress()
            } else {
                ReadBook.uploadProgress()
            }
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
        justInitData = false
        networkChangedListener.unRegister()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_read, menu)
        readMenu.refreshMenuColorFilter()
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onShowActivityOverflowMenu(anchor: View, menu: Menu): Boolean {
        readMenu.openPopup(ReaderPopup.Overflow)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        this.menu = menu
        upMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_same_title_removed)?.isChecked =
            ReadBook.curTextChapter?.sameTitleRemoved == true
        return super.onMenuOpened(featureId, menu)
    }

    /** 更新菜单 */
    private fun upMenu() {
        val menu = menu ?: return
        // Keep configuration reachable if the shared popup must fall back to the native menu.
        menu.findItem(R.id.menu_reader_all_features)?.isVisible = true
        val book = ReadBook.book ?: return
        val onLine = !book.isLocal
        for (i in 0 until menu.size) {
            val item = menu[i]
            when (item.groupId) {
                R.id.menu_group_on_line -> item.isVisible = onLine
                R.id.menu_group_local -> item.isVisible = !onLine
                R.id.menu_group_text -> item.isVisible = book.isLocalTxt
                R.id.menu_group_epub -> item.isVisible = book.isEpub
                else ->
                    when (item.itemId) {
                        R.id.menu_enable_replace -> {
                            item.isVisible = !AppConfig.manualReplaceRule
                            item.isChecked = book.getUseReplaceRule()
                        }
                        R.id.menu_manual_replace_rule ->
                            item.isVisible = AppConfig.manualReplaceRule
                        R.id.menu_re_segment -> item.isChecked = book.getReSegment()
                        //                    R.id.menu_enable_review -> {
                        //                        item.isVisible = BuildConfig.DEBUG
                        //                        item.isChecked = AppConfig.enableReview
                        //                    }

                        R.id.menu_reimport_source -> item.isVisible = onLine
                        R.id.menu_reverse_content -> {
                            item.isVisible = onLine
                            item.isChecked =
                                ReadBook.curTextChapter
                                    ?.chapter
                                    ?.takeIf {
                                        it.bookUrl == book.bookUrl &&
                                            it.index == ReadBook.durChapterIndex
                                    }
                                    ?.let { BookHelp.isContentReversed(book, it) } == true
                        }
                        R.id.menu_del_ruby_tag -> item.isChecked = book.getDelTag(Book.rubyTag)
                        R.id.menu_del_h_tag -> item.isChecked = book.getDelTag(Book.hTag)
                    }
            }
        }
        lifecycleScope.launch {
            val show =
                ReadBook.inBookshelf &&
                    withContext(IO) {
                        AppWebDav.isOk
                    }
            menu.findItem(R.id.menu_get_progress)?.isVisible = show
            menu.findItem(R.id.menu_cover_progress)?.isVisible = show
            menu.findItem(R.id.menu_reader_more)?.isVisible = hasHiddenReaderItems(menu)
            readMenu.updateToolbarActions(toolbarActions(menu))
        }
        readMenu.updateToolbarActions(toolbarActions(menu))
        menu.findItem(R.id.menu_reader_more)?.isVisible = hasHiddenReaderItems(menu)
    }

    private fun hasHiddenReaderItems(menu: Menu): Boolean {
        val visibleKeys = menu.readerOverflowItemsByKey().keys
        return loadReaderMenuConfig(this).more.any(visibleKeys::contains)
    }

    @SuppressLint("RestrictedApi")
    private fun Menu.visibleReaderOverflowItems(): List<MenuItem> {
        return (0 until size()).mapNotNull { index ->
            getItem(index).takeIf { item ->
                if (!item.isVisible) return@takeIf false
                val impl = item as? MenuItemImpl ?: return@takeIf false
                impl.requiresOverflow() || impl.requestsActionButton() && !impl.isActionButton
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun Menu.readerOverflowItemsByKey(): Map<String, MenuItem> {
        val overflowIds = visibleReaderOverflowItems().mapTo(HashSet()) { it.itemId }
        return ReaderMenuItem.entries
            .mapNotNull { descriptor ->
                descriptor
                    .findVisible(this)
                    ?.takeIf { it.itemId in overflowIds }
                    ?.let { descriptor.key to it }
            }
            .toMap()
    }

    override fun readerToolbarActions(): List<ReaderToolbarAction> {
        menu = composeReaderMenu
        upMenu()
        return toolbarActions(composeReaderMenu)
    }

    private fun toolbarActions(menu: Menu): List<ReaderToolbarAction> {
        return listOf(
                R.id.menu_change_source to R.drawable.ic_exchange,
                R.id.menu_refresh to R.drawable.ic_refresh_black_24dp,
                R.id.menu_download to R.drawable.ic_download_line,
                R.id.menu_toc_regex to R.drawable.ic_exchange,
                R.id.menu_set_charset to R.drawable.ic_translate,
            )
            .mapNotNull { (id, icon) ->
                menu
                    .findItem(id)
                    ?.takeIf { it.isVisible }
                    ?.let { item ->
                        ReaderToolbarAction(
                            id,
                            icon,
                            item.title?.toString().orEmpty(),
                            item.isEnabled,
                        )
                    }
            }
    }

    override fun readerToolbarAction(id: Int) {
        composeReaderMenu
            .findItem(id)
            ?.takeIf { it.isVisible && it.isEnabled }
            ?.let(::onCompatOptionsItemSelected)
    }

    override fun readerPopupEntries(popup: ReaderPopup): List<ReaderPopupEntry> {
        readerToolbarActions()
        composeReaderMenu.findItem(R.id.menu_same_title_removed)?.isChecked =
            ReadBook.curTextChapter?.sameTitleRemoved == true
        return when (popup) {
            ReaderPopup.ChangeSource ->
                listOf(
                    ReaderPopupEntry(getString(R.string.chapter_change_source), "chapter"),
                    ReaderPopupEntry(
                        getString(R.string.batch_chapter_change_source),
                        "batchChapter",
                    ),
                    ReaderPopupEntry(getString(R.string.book_change_source), "book"),
                )
            ReaderPopup.Refresh ->
                listOf(
                    ReaderPopupEntry(getString(R.string.menu_refresh_dur), "dur"),
                    ReaderPopupEntry(getString(R.string.menu_refresh_after), "after"),
                    ReaderPopupEntry(getString(R.string.menu_refresh_all), "all"),
                    ReaderPopupEntry(getString(R.string.menu_refresh_resources), "resources"),
                )
            ReaderPopup.Overflow,
            ReaderPopup.More -> composeOverflowEntries(popup == ReaderPopup.More)
            ReaderPopup.Source -> emptyList()
        }
    }

    private fun composeOverflowEntries(more: Boolean): List<ReaderPopupEntry> {
        val visibleByKey = composeReaderMenu.readerOverflowItemsByKey()
        val config = loadReaderMenuConfig(this)
        val primaryItems = config.primary.mapNotNull { visibleByKey[it] }.toMutableList()
        val moreItems = config.more.mapNotNull { visibleByKey[it] }
        if (more) return moreItems.map(::toComposeReaderEntry)
        val placedIds = (primaryItems + moreItems).mapTo(HashSet()) { it.itemId }
        composeReaderMenu
            .visibleReaderOverflowItems()
            .filter {
                it.itemId !in placedIds &&
                    it.itemId != R.id.menu_reader_more &&
                    it.itemId != R.id.menu_reader_all_features
            }
            .forEach { primaryItems += it }
        return buildList {
            addAll(primaryItems.map(::toComposeReaderEntry))
            if (moreItems.isNotEmpty())
                add(ReaderPopupEntry(getString(R.string.reader_menu_more), "_more"))
            add(ReaderPopupEntry(getString(R.string.reader_menu_all_features), "_config"))
        }
    }

    private fun toComposeReaderEntry(item: MenuItem): ReaderPopupEntry {
        val key =
            ReaderMenuItem.entries
                .firstOrNull { it.findVisible(composeReaderMenu)?.itemId == item.itemId }
                ?.key ?: "id:${item.itemId}"
        return ReaderPopupEntry(
            item.title?.toString().orEmpty(),
            key,
            item.isEnabled,
            item.isCheckable,
            item.isChecked,
        )
    }

    override fun readerPopupAction(popup: ReaderPopup, value: String) {
        when (popup) {
            ReaderPopup.ChangeSource ->
                when (value) {
                    "chapter" -> showChapterChangeSource()
                    "batchChapter" -> showChapterChangeSource(batchMode = true)
                    "book" -> showBookChangeSource()
                }
            ReaderPopup.Refresh ->
                when (value) {
                    "dur" -> refreshDurChapter()
                    "after" -> refreshAfterChapters()
                    "all" -> refreshAllChapters()
                    "resources" -> {
                        resetReviewSummaryState()
                        ReadBook.book?.let {
                            viewModel.refreshResources(it, includePreloaded = true)
                        }
                    }
                }
            ReaderPopup.Overflow,
            ReaderPopup.More ->
                when (value) {
                    "_more" -> readMenu.openPopup(ReaderPopup.More)
                    "_config" -> showReaderMenuConfig()
                    else -> {
                        val item =
                            ReaderMenuItem.byKey[value]?.findVisible(composeReaderMenu)
                                ?: value
                                    .removePrefix("id:")
                                    .toIntOrNull()
                                    ?.let(composeReaderMenu::findItem)
                        item
                            ?.takeIf { it.isEnabled && it.isVisible }
                            ?.let(::onCompatOptionsItemSelected)
                    }
                }
            ReaderPopup.Source -> Unit
        }
    }

    private fun showBookChangeSource() {
        readMenu.runMenuOut()
        ReadBook.book?.let {
            showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
        }
    }

    private fun showChapterChangeSource(batchMode: Boolean = false) {
        lifecycleScope.launch {
            val book = ReadBook.book ?: return@launch
            val chapter =
                appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                    ?: return@launch
            readMenu.runMenuOut()
            showDialogFragment(
                ChangeChapterSourceDialog(
                    book.name,
                    book.author,
                    chapter.index,
                    chapter.title,
                    batchMode = batchMode,
                )
            )
        }
    }

    private fun refreshDurChapter() {
        resetReviewSummaryState()
        if (ReadBook.bookSource == null) {
            upContent()
        } else {
            ReadBook.book?.let {
                if (viewModel.resourceThemeChanged(it)) {
                    viewModel.refreshResources(it, includePreloaded = false)
                    return@let
                }
                ReadBook.preserveCurrentPositionForRefresh()
                ReadBook.curTextChapter = null
                readView.upContent()
                viewModel.refreshContentDur(it)
            }
        }
    }

    private fun refreshAfterChapters() {
        resetReviewSummaryState()
        if (ReadBook.bookSource == null) {
            upContent()
        } else {
            ReadBook.book?.let {
                ReadBook.clearTextChapter()
                readView.upContent()
                viewModel.refreshContentAfter(it)
            }
        }
    }

    private fun refreshAllChapters() {
        if (ReadBook.bookSource == null) {
            resetReviewSummaryState()
            upContent()
        } else {
            ReadBook.book?.let {
                refreshContentAll(it)
            }
        }
    }

    /** 菜单 */
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_reader_more -> {
                readMenu.openPopup(ReaderPopup.More)
                return true
            }

            R.id.menu_reader_all_features -> {
                showReaderMenuConfig()
                return true
            }

            R.id.menu_change_source -> showBookChangeSource()

            R.id.menu_refresh -> refreshDurChapter()

            R.id.menu_download -> showDownloadDialog()
            R.id.menu_add_bookmark -> addBookmark()
            R.id.menu_highlight_rule -> startActivity<HighlightRuleActivity>()
            R.id.menu_simulated_reading -> showSimulatedReading()
            R.id.menu_edit_content ->
                ContentEditDialog.newInstance()?.let {
                    showDialogFragment(it)
                }
            R.id.menu_reimport_source -> viewModel.prepareSourceReimport()
            R.id.menu_update_toc ->
                ReadBook.book?.let {
                    if (it.isEpub) {
                        BookHelp.clearCache(it)
                        EpubFile.clear()
                    }
                    if (it.isMobi) {
                        MobiFile.clear()
                    }
                    loadChapterList(it)
                }

            R.id.menu_enable_replace -> changeReplaceRuleState()
            R.id.menu_manual_replace_rule -> showDialogFragment<ManualReplaceRulesDialog>()
            R.id.menu_re_segment ->
                ReadBook.book?.let {
                    it.setReSegment(!it.getReSegment())
                    item.isChecked = it.getReSegment()
                    ReadBook.loadContent(false)
                }

            //            R.id.menu_enable_review -> {
            //                AppConfig.enableReview = !AppConfig.enableReview
            //                item.isChecked = AppConfig.enableReview
            //                ReadBook.loadContent(false)
            //            }

            R.id.menu_del_ruby_tag ->
                ReadBook.book?.let {
                    item.isChecked = !item.isChecked
                    if (item.isChecked) {
                        it.addDelTag(Book.rubyTag)
                    } else {
                        it.removeDelTag(Book.rubyTag)
                    }
                    refreshContentAll(it)
                }

            R.id.menu_del_h_tag ->
                ReadBook.book?.let {
                    item.isChecked = !item.isChecked
                    if (item.isChecked) {
                        it.addDelTag(Book.hTag)
                    } else {
                        it.removeDelTag(Book.hTag)
                    }
                    refreshContentAll(it)
                }

            R.id.menu_page_anim ->
                showPageAnimConfig {
                    readView.upPageAnim()
                    ReadBook.loadContent(false)
                }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_toc_regex -> showDialogFragment(TxtTocRuleDialog(ReadBook.book?.tocUrl))

            R.id.menu_reverse_content ->
                ReadBook.book?.let {
                    viewModel.reverseContent(it)
                }

            R.id.menu_set_charset -> showCharsetConfig()
            R.id.menu_image_style -> {
                val imgStyles =
                    arrayListOf(
                        Book.imgStyleDefault,
                        Book.imgStyleFull,
                        Book.imgStyleText,
                        Book.imgStyleSingle,
                    )
                selector(
                    R.string.image_style,
                    imgStyles,
                ) { _, index ->
                    val imageStyle = imgStyles[index]
                    ReadBook.book?.setImageStyle(imageStyle)
                    if (imageStyle == Book.imgStyleSingle) {
                        ReadBook.book?.setPageAnim(0) // 切换图片样式single后，自动切换为覆盖
                        readView.upPageAnim()
                    }
                    ReadBook.loadContent(false)
                }
            }

            R.id.menu_get_progress ->
                ReadBook.book?.let {
                    viewModel.syncBookProgress(it) { progress ->
                        sureSyncProgress(progress)
                    }
                }

            R.id.menu_cover_progress ->
                ReadBook.book?.let {
                    ReadBook.uploadProgress(true) { toastOnUi(R.string.upload_book_success) }
                }

            R.id.menu_same_title_removed -> {
                ReadBook.book?.let {
                    val contentProcessor = ContentProcessor.get(it)
                    val textChapter = ReadBook.curTextChapter
                    if (
                        textChapter != null &&
                            !textChapter.sameTitleRemoved &&
                            !contentProcessor.removeSameTitleCache.contains(
                                textChapter.chapter.getFileName("nr")
                            )
                    ) {
                        toastOnUi("未找到可移除的重复标题")
                    }
                }
                viewModel.reverseRemoveSameTitle()
            }

            R.id.menu_effective_replaces -> showDialogFragment<EffectiveReplacesDialog>()

            R.id.menu_help -> showHelp()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun refreshContentAll(book: Book) {
        resetReviewSummaryState()
        ReadBook.clearTextChapter()
        readView.upContent()
        viewModel.refreshContentAll(book)
    }

    private fun resetReviewSummaryState() {
        reviewSummaryRequestToken++
        reviewSummaryAppliedKey = null
        reviewSummaryLoadingKey = null
        synchronized(reviewSummaryCache) {
            reviewSummaryCache.clear()
        }
        synchronized(reviewSummaryPrefetchingKeys) {
            reviewSummaryPrefetchingKeys.clear()
        }
        ChapterProvider.clearReviewProviders()
    }

    /** 按键拦截,显示菜单 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !readMenu.canShowMenu) {
                readMenu.runMenuIn()
                return true
            }
            if (!isDown && !readMenu.canShowMenu) {
                readMenu.canShowMenu = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 鼠标滚轮和手表旋钮事件 */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val axisValue =
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) ->
                        event.getAxisValue(MotionEvent.AXIS_SCROLL)

                    event.source and InputDevice.SOURCE_CLASS_POINTER != 0 ->
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL)

                    else -> return super.onGenericMotionEvent(event)
                }
            if (!axisValue.isFinite() || axisValue == 0f) {
                return super.onGenericMotionEvent(event)
            }
            LogUtils.d("onGenericMotionEvent", "axisValue = $axisValue")
            val direction = if (axisValue < 0f) PageDirection.NEXT else PageDirection.PREV
            mouseWheelPage(direction, axisValue)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** 按键事件 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuLayoutIsVisible) {
            return super.onKeyDown(keyCode, event)
        }
        val longPress = event.repeatCount > 0
        when {
            isPrevKey(keyCode) -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            isNextKey(keyCode) -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP ->
                if (volumeKeyPage(PageDirection.PREV, longPress)) {
                    return true
                }

            KeyEvent.KEYCODE_VOLUME_DOWN ->
                if (volumeKeyPage(PageDirection.NEXT, longPress)) {
                    return true
                }

            KeyEvent.KEYCODE_PAGE_UP -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }

            KeyEvent.KEYCODE_SPACE -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    /** 松开按键事件 */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeKeyPage(PageDirection.NONE, false)) {
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Kept for callers using the original reader touch contract; Compose cursors call the same
     * bridge.
     */
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!readView.isTextSelected) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> textActionMenu.dismiss()
            MotionEvent.ACTION_MOVE ->
                when (v.id) {
                    R.id.cursor_left ->
                        moveSelectionCursor(SelectionHandle.Start, Offset(event.rawX, event.rawY))
                    R.id.cursor_right ->
                        moveSelectionCursor(SelectionHandle.End, Offset(event.rawX, event.rawY))
                }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> finishSelectionCursor()
        }
        return true
    }

    private fun moveSelectionCursor(handle: SelectionHandle, rawPosition: Offset) {
        val readView = readView
        if (!readView.isTextSelected) return
        val command =
            selectionDragCommand(
                handle = handle,
                reverseStart = readView.curPage.getReverseStartCursor(),
                reverseEnd = readView.curPage.getReverseEndCursor(),
                rawX = rawPosition.x,
                rawY = rawPosition.y,
                cursorWidth = selectionCursorSize,
                cursorHeight = selectionCursorSize,
            )
        when (command.endpoint) {
            SelectionEndpoint.Start ->
                readView.selectStartMoveAtRaw(command.screenX, command.screenY)
            SelectionEndpoint.End -> readView.selectEndMoveAtRaw(command.screenX, command.screenY)
        }
    }

    private fun finishSelectionCursor() {
        if (!readView.isTextSelected) return
        readView.dismissTextMagnifier()
        readView.curPage.resetReverseCursor()
        showTextActionMenu()
    }

    override fun upSelectedStart(x: Float, y: Float, top: Float) {
        selectionState =
            selectionState.copy(startX = x, startY = y, startTop = top, showStart = true)
    }

    override fun upSelectedEnd(x: Float, y: Float) {
        selectionState = selectionState.copy(endX = x, endY = y, showEnd = true)
    }

    override fun onCancelSelect() {
        selectionState = selectionState.copy(showStart = false, showEnd = false)
        textActionMenu.dismiss()
    }

    override fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean {
        return readView.onTouchEvent(event)
    }

    /** 显示文本操作菜单 */
    override fun showTextActionMenu() {
        textActionMenu.show(
            readView,
            window.decorView.height,
            selectionState.startX.toInt(),
            selectionState.startTop.toInt(),
            (selectionState.startY + selectionCursorSize).toInt(),
            selectionState.endX.toInt(),
            (selectionState.endY + selectionCursorSize).toInt(),
        )
    }

    private var editingHighlightTime: Long? = null
    private var editingHighlightOwner: String? = null
    private var editingHighlightSnapshot: BookHighlight? = null
    private var editingHighlight: BookHighlight?
        get() {
            val time = editingHighlightTime ?: return null
            if (ReadBook.book?.bookUrl?.let(MD5Utils::md5Encode) != editingHighlightOwner)
                return null
            val snapshot = editingHighlightSnapshot
            return ReadBook.highlights.firstOrNull {
                it.time == time &&
                    (snapshot == null ||
                        it.bookName == snapshot.bookName && it.bookAuthor == snapshot.bookAuthor)
            } ?: snapshot?.takeIf { it.time == time }
        }
        set(value) {
            editingHighlightTime = value?.time
            editingHighlightOwner =
                if (value == null) null else ReadBook.book?.bookUrl?.let(MD5Utils::md5Encode)
            editingHighlightSnapshot = value
        }

    private var highlightStyleDialog: HighlightStyleDialog? = null
    private var contextMenuState by mutableStateOf<ReaderContextMenuState?>(null)
    private var contextMenuAction: ((String) -> Unit)? = null

    private fun dismissContextMenu() {
        contextMenuState = null
        contextMenuAction = null
    }

    private fun showContextMenu(
        x: Float,
        y: Float,
        actions: List<ReaderContextAction>,
        preferAbove: Boolean = false,
        alignToStart: Boolean = true,
        action: (String) -> Unit,
    ) {
        val owner = ReadBook.book?.bookUrl ?: return
        dismissContextMenu()
        contextMenuState = ReaderContextMenuState(x, y, actions.toList(), preferAbove, alignToStart)
        contextMenuAction = { key ->
            if (
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    ReadBook.book?.bookUrl == owner &&
                    !supportFragmentManager.isStateSaved
            ) {
                action(key)
            }
        }
    }

    private fun performContextAction(key: String) {
        val action = contextMenuAction
        dismissContextMenu()
        action?.invoke(key)
    }

    private fun showHighlightActionMenu(highlight: BookHighlight, x: Float, y: Float) {
        editingHighlight = highlight
        showContextMenu(
            x = x,
            y = y,
            actions =
                listOf(
                    ReaderContextAction(
                        getString(R.string.highlight_style),
                        ACTION_HIGHLIGHT_STYLE,
                    ),
                    ReaderContextAction(getString(R.string.highlight_note), ACTION_HIGHLIGHT_NOTE),
                    ReaderContextAction(
                        getString(R.string.highlight_create_rule),
                        ACTION_HIGHLIGHT_CREATE_RULE,
                    ),
                    ReaderContextAction(getString(android.R.string.copy), ACTION_HIGHLIGHT_COPY),
                    ReaderContextAction(
                        getString(R.string.delete),
                        ACTION_HIGHLIGHT_DELETE,
                        danger = true,
                    ),
                ),
        ) { action ->
            when (action) {
                ACTION_HIGHLIGHT_STYLE -> {
                    val dialog = HighlightStyleDialog()
                    highlightStyleDialog = dialog
                    showDialogFragment(dialog)
                }

                ACTION_HIGHLIGHT_NOTE -> showDialogFragment(HighlightNoteDialog(highlight))
                ACTION_HIGHLIGHT_CREATE_RULE ->
                    showDialogFragment(
                        HighlightRuleEditDialog.create(
                            pattern = highlight.bookText,
                            scope = ReadBook.book?.name,
                            style = highlight.style,
                        )
                    )
                ACTION_HIGHLIGHT_COPY -> sendToClip(highlight.bookText)
                ACTION_HIGHLIGHT_DELETE -> {
                    ReadBook.removeHighlight(highlight)
                    if (editingHighlight?.time == highlight.time) editingHighlight = null
                }
            }
        }
    }

    override fun onHighlightClick(highlight: BookHighlight, x: Float, y: Float) {
        showHighlightActionMenu(highlight, x, y)
    }

    override fun onHighlightRuleClick(ruleId: Long, x: Float, y: Float) {
        showContextMenu(
            x = x,
            y = y,
            actions =
                listOf(
                    ReaderContextAction(getString(R.string.edit), ACTION_HIGHLIGHT_RULE_EDIT),
                    ReaderContextAction(
                        getString(R.string.highlight_rule),
                        ACTION_HIGHLIGHT_RULE_MANAGE,
                    ),
                    ReaderContextAction(
                        getString(R.string.highlight_rule_disable),
                        ACTION_HIGHLIGHT_RULE_DISABLE,
                        danger = true,
                    ),
                ),
        ) { action ->
            when (action) {
                ACTION_HIGHLIGHT_RULE_EDIT ->
                    showDialogFragment(HighlightRuleEditDialog.edit(ruleId))

                ACTION_HIGHLIGHT_RULE_MANAGE -> startActivity<HighlightRuleActivity>()
                ACTION_HIGHLIGHT_RULE_DISABLE -> disableHighlightRule(ruleId)
            }
        }
    }

    private fun disableHighlightRule(ruleId: Long) {
        val expectedUuid = ReadBook.highlightRules.firstOrNull { it.id == ruleId }?.uuid ?: return
        Coroutine.async(lifecycleScope) {
                appDb.withTransaction {
                    val current = appDb.highlightRuleDao.findById(ruleId)
                    if (current?.uuid == expectedUuid) {
                        // Patch only enablement on the latest row; concurrent style edits stay
                        // intact.
                        appDb.highlightRuleDao.update(current.copy(isEnabled = false))
                    }
                }
            }
            .onFinally { ReadBook.upHighlightRules() }
    }

    override fun currentHighlightStyle(): HighlightStyle {
        return editingHighlight?.styleObj() ?: HighlightStyle()
    }

    override fun onHighlightStyleChanged(style: HighlightStyle) {
        editingHighlight?.let { highlight ->
            val visibleStyle = visibleHighlightStyle(style)
            highlight.applyStyle(visibleStyle)
            ReadBook.updateHighlight(highlight)
            ReadBook.saveLastHighlightStyle(visibleStyle)
        }
    }

    override fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean) {
        val presets = if (withAlpha) HighlightColors.bg else HighlightColors.text
        ColorPickerDialog.newBuilder()
            .setColor(initial.takeIf { it != 0 } ?: presets.first())
            .setShowAlphaSlider(withAlpha)
            .setDialogType(ColorPickerDialog.TYPE_PRESETS)
            .setPresets(presets)
            .setDialogId(dialogId)
            .show(this)
    }

    private fun refreshHighlightStyleDialog() {
        highlightStyleDialog?.refresh()
        (supportFragmentManager.findFragmentByTag(HighlightStyleDialog::class.simpleName)
                as? HighlightStyleDialog)
            ?.refresh()
    }

    /** 当前选择的文本 */
    override val selectedText: String
        get() = readView.getSelectText()

    /** 文本选择菜单操作 */
    override fun onMenuItemSelected(itemId: Int): Boolean {
        when (itemId) {
            R.id.menu_aloud ->
                when (AppConfig.contentSelectSpeakMod) {
                    1 ->
                        lifecycleScope.launch {
                            readView.aloudStartSelect()
                        }

                    else -> speak(readView.getSelectText())
                }

            R.id.menu_bookmark ->
                readView.curPage.let {
                    val bookmark = it.createBookmark()
                    if (bookmark == null) {
                        toastOnUi(R.string.create_bookmark_error)
                    } else {
                        showDialogFragment(BookmarkDialog(bookmark))
                    }
                    return true
                }

            R.id.menu_highlight ->
                readView.curPage.let {
                    val style =
                        visibleHighlightStyle(
                            GSON.fromJsonObject<HighlightStyle>(
                                    getPrefString(PreferKey.highlightLastStyle)
                                )
                                .getOrNull()
                        )
                    val anchorX = selectionState.startX
                    val anchorY = selectionState.startTop
                    val highlight = it.createHighlight(style)
                    if (highlight == null) {
                        toastOnUi(R.string.create_highlight_error)
                    } else {
                        ReadBook.addHighlight(highlight)
                        handler.post {
                            showHighlightActionMenu(highlight, anchorX, anchorY)
                        }
                    }
                    return true
                }

            R.id.menu_replace -> {
                val scopes = arrayListOf<String>()
                ReadBook.book?.name?.let {
                    scopes.add(it)
                }
                ReadBook.bookSource?.bookSourceUrl?.let {
                    scopes.add(it)
                }
                val text = selectedText.lineSequence().joinToString("\n") { it.trim() }
                replaceActivity.launch(
                    ReplaceEditActivity.startIntent(
                        this,
                        pattern = text,
                        scope = scopes.joinToString(";"),
                    )
                )
                return true
            }

            R.id.menu_search_content -> {
                viewModel.searchContentQuery = selectedText
                openSearchActivity(selectedText)
                return true
            }

            R.id.menu_dict -> {
                showDialogFragment(DictDialog(selectedText))
                return true
            }
        }
        return false
    }

    /** 文本选择菜单操作完成 */
    override fun onMenuActionFinally() = run {
        textActionMenu.dismiss()
        readView.cancelSelect()
    }

    override fun onEditTextActionMenu() {
        showTextSelectMenuConfig()
    }

    fun showTextSelectMenuConfig() {
        showDialogFragment(TextSelectMenuConfigDialog())
    }

    fun showReaderMenuConfig() {
        showDialogFragment(ReaderMenuConfigDialog())
    }

    fun refreshReaderMenu() {
        invalidateOptionsMenu()
        readMenu.updateToolbarActions(readerToolbarActions())
    }

    private fun speak(text: String) {
        if (tts == null) {
            tts = TTS()
        }
        tts?.speak(text)
    }

    /** 鼠标滚轮翻页 */
    private fun mouseWheelPage(direction: PageDirection, distance: Float) {
        if (menuLayoutIsVisible || !AppConfig.mouseWheelPage) {
            return
        }
        if (readView.isScroll) {
            // 滚动视图时滚动,否则翻页
            val scrollDistance = (distance * (AppConfig.mouseWheelScrollSpeed / 2f)).toInt()
            (readView.pageDelegate as? ScrollPageDelegate)?.curPage?.scroll(scrollDistance)
        } else {
            keyPageDebounce(direction, mouseWheel = true, longPress = false)
        }
    }

    /** 音量键翻页 */
    private fun volumeKeyPage(direction: PageDirection, longPress: Boolean): Boolean {
        if (!AppConfig.volumeKeyPage) {
            return false
        }
        if (!AppConfig.volumeKeyPageOnPlay && BaseReadAloudService.isPlay()) {
            return false
        }
        handleKeyPage(direction, longPress)
        return true
    }

    private fun handleKeyPage(direction: PageDirection, longPress: Boolean) {
        if (AppConfig.keyPageOnLongPress || direction == PageDirection.NONE) {
            keyPage(direction)
        } else {
            keyPageDebounce(direction, longPress = longPress)
        }
    }

    private fun keyPageDebounce(
        direction: PageDirection,
        mouseWheel: Boolean = false,
        longPress: Boolean,
    ) {
        if (longPress) {
            return
        }
        nextPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        prevPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        when (direction) {
            PageDirection.NEXT -> nextPageDebounce.invoke()
            PageDirection.PREV -> prevPageDebounce.invoke()
            else -> {}
        }
    }

    private fun keyPage(direction: PageDirection) {
        readView.cancelTouchGestures()
        readView.cancelSelect()
        readView.pageDelegate?.isCancel = false
        readView.pageDelegate?.keyTurnPage(direction)
    }

    override fun upMenuView() {
        handler.post {
            upMenu()
            readMenu.upBookView()
        }
    }

    override fun loadChapterList(book: Book) {
        ReadBook.upMsg(getString(R.string.toc_updateing))
        viewModel.loadChapterList(book)
    }

    /** 内容加载完成 */
    override fun contentLoadFinish() {
        lifecycleScope.launch(Main.immediate) {
            observeBookmarks()
            if (intent.getBooleanExtra("readAloud", false)) {
                intent.removeExtra("readAloud")
                ReadBook.readAloud()
            }
            loadStates = true
            loadReviewSummaryIfNeeded()
        }
    }

    /** 更新内容 */
    override fun upContent(
        relativePosition: Int,
        resetPageOffset: Boolean,
        readPositionVersion: Long?,
        success: (() -> Unit)?,
    ) {
        lifecycleScope.launch {
            val shouldResetPageOffset =
                resetPageOffset &&
                    (readPositionVersion == null ||
                        isReadPositionVersionCurrent(readPositionVersion))
            readView.cancelTouchGestures()
            readView.upContent(relativePosition, shouldResetPageOffset)
            scheduleAloudFollowCheck()
            observeBookmarks()
            upBookmarkIndicator()
            if (relativePosition == 0) {
                upSeekBarProgress()
            }
            loadStates = false
            loadReviewSummaryIfNeeded()
            success?.invoke()
        }
    }

    override fun readPositionVersion(): Long {
        return readView.getReadPositionVersion()
    }

    override fun isReadPositionVersionCurrent(version: Long): Boolean {
        return readView.getReadPositionVersion() == version
    }

    override suspend fun upContentAwait(
        relativePosition: Int,
        resetPageOffset: Boolean,
        readPositionVersion: Long?,
        success: (() -> Unit)?,
    ) =
        withContext(Main.immediate) {
            val shouldResetPageOffset =
                resetPageOffset &&
                    (readPositionVersion == null ||
                        isReadPositionVersionCurrent(readPositionVersion))
            readView.cancelTouchGestures()
            readView.upContent(relativePosition, shouldResetPageOffset)
            scheduleAloudFollowCheck()
            observeBookmarks()
            upBookmarkIndicator()
            if (relativePosition == 0) {
                upSeekBarProgress()
            }
            loadStates = false
            loadReviewSummaryIfNeeded()
        }

    override fun upPageAnim(upRecorder: Boolean) {
        lifecycleScope.launch {
            readView.upPageAnim(upRecorder)
            upBookmarkIndicator()
        }
    }

    override fun notifyBookChanged() {
        bookChanged = true
        if (!ReadBook.inBookshelf) {
            viewModel.removeFromBookshelf { super.finish() }
        }
    }

    override fun cancelSelect() {
        runOnUiThread {
            readView.cancelSelect()
        }
    }

    /** 页面改变 */
    override fun pageChanged() {
        pageChanged = true
        if (!isScroll) aloudControls.onMovement(100f)
        readView.onPageChange()
        dismissContextMenu()
        handler.post {
            upBookmarkIndicator()
            upSeekBarProgress()
            restoreAloudFollowOnVisiblePage()
        }
        executor.execute {
            startBackupJob()
        }
    }

    override fun onReadScroll(offset: Int) {
        if (BaseReadAloudService.isRun && !isAutoPage) {
            ReadAloud.detachReadAloudFollow()
            aloudControls.onMovement(
                -offset * 100f / ChapterProvider.visibleHeight.coerceAtLeast(1)
            )
            scheduleAloudFollowCheck()
        }
    }

    fun showReadAloudControls(resetPosition: Boolean = false) {
        aloudControls.reveal(resetPosition)
        scheduleAloudFollowCheck()
    }

    private fun scheduleAloudFollowCheck() {
        // Manual navigation emits detach before changing the displayed page.
        handler.removeCallbacks(restoreAloudFollowRunnable)
        handler.post(restoreAloudFollowRunnable)
    }

    private fun restoreAloudFollowOnVisiblePage() {
        if (
            !BaseReadAloudService.isRun ||
                ReadAloud.followReadAloudPosition ||
                !getPrefBoolean(PreferKey.readAloudControlsRealtime, true)
        )
            return
        val chapterStart = ReadAloud.readAloudChapterStart
        val chapterIndex = ReadAloud.readAloudChapterIndex
        if (chapterStart < 0 || chapterIndex != ReadBook.durChapterIndex) return
        val chapter = ReadBook.curTextChapter ?: return
        if (readView.curPage.textPage !== chapter.getPage(ReadBook.durPageIndex)) return
        val (visibleChapter, line) = readView.getReadAloudPos() ?: return
        if (visibleChapter != chapterIndex) return
        val speakingPage = chapter.getPageByReadPos(chapterStart) ?: return
        if (chapter.getPageByReadPos(line.chapterPosition) !== speakingPage) return
        ReadAloud.restoreReadAloudFollow()
        speakingPage.upPageAloudSpan(chapterStart - chapter.getReadLength(speakingPage.index))
        readView.upContent(resetPageOffset = false)
    }

    /** 更新进度条位置 */
    private fun upSeekBarProgress() {
        val progress =
            when (AppConfig.progressBarBehavior) {
                "page" -> ReadBook.durPageIndex
                else /* chapter */ -> ReadBook.durChapterIndex
            }
        readMenu.setSeekPage(progress)
    }

    /** 显示菜单 */
    override fun showMenuBar() {
        readMenu.runMenuIn()
    }

    /** 回到朗读位置：恢复页面跟随朗读，并精确跳到当前朗读位置。 同章内直接定位到已记录的朗读字符位置；跨章时打开朗读所在章节并定位到该字符位置。 全程不打断当前朗读。 */
    override fun backToSpeakingPosition() {
        if (!BaseReadAloudService.isRun) return
        val speakingChapterIndex = ReadAloud.readAloudChapterIndex
        // 优先用观察到的进度; 回退到服务里存活的朗读位置(Activity 重建后进度事件可能尚未到达)
        val chapterStart =
            lastReadAloudChapterStart.takeIf {
                lastReadAloudChapterIndex == speakingChapterIndex && it >= 0
            } ?: ReadAloud.readAloudChapterStart
        when {
            speakingChapterIndex >= 0 && speakingChapterIndex != ReadBook.durChapterIndex -> {
                // 跨章：打开朗读所在章节并精确定位到朗读字符位置。
                // openChapter 会先脱离跟随, 故在加载完成回调里再恢复跟随。
                val durChapterPos = chapterStart.coerceAtLeast(0)
                val bookUrl = ReadBook.book?.bookUrl
                ReadBook.openChapter(speakingChapterIndex, durChapterPos) {
                    if (
                        BaseReadAloudService.isRun &&
                            ReadBook.book?.bookUrl == bookUrl &&
                            ReadBook.durChapterIndex == speakingChapterIndex &&
                            ReadAloud.readAloudChapterIndex == speakingChapterIndex
                    ) {
                        ReadAloud.restoreReadAloudFollow()
                        upTextChapterAloudSpan(ReadAloud.readAloudChapterStart)
                    }
                }
            }

            else -> {
                ReadAloud.restoreReadAloudFollow()
                if (chapterStart >= 0) upTextChapterAloudSpan(chapterStart)
            }
        }
    }

    /** 把显示页定位到章内字符位置并绘制朗读高亮。 */
    private fun upTextChapterAloudSpan(chapterStart: Int) {
        if (chapterStart < 0) return
        val textChapter = ReadBook.curTextChapter ?: return
        lifecycleScope.launch(IO) {
            ReadBook.durChapterPos = chapterStart
            val pageIndex = ReadBook.durPageIndex
            val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
            textChapter.getPage(pageIndex)?.upPageAloudSpan(aloudSpanStart)
            upContent()
        }
    }

    override val oldBook: Book?
        get() = ReadBook.book

    override fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit,
    ) {
        resetReviewSummaryState()
        if (!book.isAudio) {
            viewModel.changeTo(book, toc, onSuccess)
        } else {
            ReadAloud.stop(this)
            lifecycleScope.launch {
                withContext(IO) {
                    ReadBook.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    replaceBookAfterSourceChange(ReadBook.book, book, toc)
                }
                onSuccess()
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun replaceContent(content: String) {
        ReadBook.book?.let {
            viewModel.saveContent(it, content)
        }
    }

    override fun contentCached(chapterIndex: Int) {
        if (chapterIndex in ReadBook.durChapterIndex - 1..ReadBook.durChapterIndex + 1) {
            ReadBook.clearTextChapter()
            ReadBook.loadContent(resetPageOffset = false)
        }
    }

    override fun showActionMenu() {
        when {
            BaseReadAloudService.isRun -> showReadAloudDialog()
            isAutoPage -> showDialogFragment<AutoReadDialog>()
            isShowingSearchResult -> searchMenu.runMenuIn()
            else -> readMenu.runMenuIn()
        }
    }

    /** 显示朗读菜单 */
    override fun showReadAloudDialog() {
        showDialogFragment<ReadAloudDialog>()
    }

    /** 自动翻页 */
    override fun autoPage() {
        ReadAloud.stop(this)
        if (isAutoPage) {
            autoPageStop()
        } else {
            readView.autoPager.start()
            readMenu.setAutoPage(true)
            screenTimeOut = -1L
            screenOffTimerStart()
        }
    }

    override fun autoPageStop() {
        if (isAutoPage) {
            readView.autoPager.stop()
            readMenu.setAutoPage(false)
            dismissDialogFragment<AutoReadDialog>()
            upScreenTimeOut()
        }
    }

    override fun openSourceEditActivity() {
        ReadBook.bookSource?.let {
            sourceEditActivity.launch {
                putExtra("sourceUrl", it.bookSourceUrl)
            }
        }
    }

    override fun openBookInfoActivity() {
        val book = ReadBook.book ?: return
        val identity = BookDetailIdentity(book.name, book.author, book.bookUrl)
        val origin = book.origin
        val epoch = ++bookInfoNavigationEpoch
        bookInfoNavigationJob?.cancel()
        bookInfoNavigationJob = lifecycleScope.launch {
            var ticket: String? = null
            var delivered = false
            try {
                val prepared = BookInfoNavigation.prepare(this@ReadBookActivity, identity)
                ticket = prepared
                lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                currentCoroutineContext().ensureActive()
                if (
                    epoch != bookInfoNavigationEpoch ||
                        isFinishing ||
                        supportFragmentManager.isStateSaved ||
                        ReadBook.book?.bookUrl != identity.bookUrl ||
                        ReadBook.book?.origin != origin
                )
                    return@launch
                // Launch and ownership transfer stay in one uninterrupted Main turn.
                bookInfoActivity.launch(BookInfoNavigation.intent(this@ReadBookActivity, prepared))
                delivered = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toastOnUi(error.localizedMessage ?: getString(R.string.error))
            } finally {
                if (!delivered)
                    ticket?.let { owned ->
                        withContext(NonCancellable) {
                            BookInfoNavigation.abandon(this@ReadBookActivity, owned)
                        }
                    }
            }
        }
    }

    /** 替换 */
    override fun openReplaceRule() {
        replaceActivity.launch(Intent(this, ReplaceRuleActivity::class.java))
    }

    /** 打开目录 */
    override fun openChapterList() {
        ReadBook.book?.let {
            tocActivity.launch(it.bookUrl)
        }
    }

    /** 打开搜索界面 */
    override fun openSearchActivity(searchWord: String?) {
        val book = ReadBook.book ?: return
        searchContentActivity.launch {
            putExtra("bookUrl", book.bookUrl)
            putExtra("searchWord", searchWord ?: viewModel.searchContentQuery)
            putExtra("searchResultIndex", viewModel.searchResultIndex)
            viewModel.searchResultList?.first()?.let {
                if (it.query == viewModel.searchContentQuery) {
                    IntentData.put("searchResultList", viewModel.searchResultList)
                }
            }
        }
    }

    /** 禁用书源 */
    override fun disableSource() {
        resetReviewSummaryState()
        viewModel.disableSource()
    }

    /** 显示阅读样式配置 */
    override fun showReadStyle() {
        showDialogFragment<ReadStyleDialog>()
    }

    /** 显示更多设置 */
    override fun showMoreSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    override fun showBookMemo() {
        ReadBook.book?.let { book ->
            showDialogFragment<io.legado.app.ui.widget.dialog.BookMemoDialog> {
                putString("bookUrl", book.bookUrl)
            }
        }
    }

    override fun showSearchSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    /** 更新状态栏,导航栏 */
    override fun upSystemUiVisibility() {
        upSystemUiVisibility(isInMultiWindow, !menuLayoutIsVisible, bottomDialog > 0)
        upNavigationBarColor()
    }

    // 退出全文搜索
    override fun exitSearchMenu() {
        if (isShowingSearchResult) {
            isShowingSearchResult = false
            searchMenu.deactivate()
            ReadBook.clearSearchResult()
            readView.cancelSelect(true)
        }
    }

    /* 恢复到 全文搜索/进度条跳转前的位置 */
    private fun restoreLastBookProcess() {
        if (confirmRestoreProcess == true) {
            ReadBook.restoreLastBookProgress()
        } else if (confirmRestoreProcess == null) {
            alert(R.string.draw) {
                setMessage(R.string.restore_last_book_process)
                yesButton {
                    confirmRestoreProcess = true
                    ReadBook.restoreLastBookProgress() // 恢复启动全文搜索前的进度
                }
                noButton {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
                onCancelled {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
            }
        }
    }

    override fun showLogin() {
        ReadBook.bookSource?.let {
            startActivity<SourceLoginActivity> {
                putExtra("bookType", BookType.text)
            }
        }
    }

    override fun payAction() {
        val book = ReadBook.book ?: return
        if (book.isLocal) return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
        if (chapter == null) {
            toastOnUi("no chapter")
            return
        }
        alert(R.string.chapter_pay) {
            setMessage(chapter.title)
            yesButton {
                Coroutine.async(lifecycleScope) {
                        val source =
                            ReadBook.bookSource ?: throw NoStackTraceException("no book source")
                        val payAction = source.getContentRule().payAction
                        if (payAction.isNullOrBlank()) {
                            throw NoStackTraceException("no pay action")
                        }
                        val java =
                            SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
                        runScriptWithContext {
                            source
                                .evalJS(payAction) {
                                    put("java", java)
                                    put("book", book)
                                    put("chapter", chapter)
                                    put("title", chapter.title)
                                    put("baseUrl", chapter.url)
                                    put("result", null)
                                    put("src", null)
                                }
                                .toString()
                        }
                    }
                    .onSuccess(IO) {
                        if (it.isAbsUrl()) {
                            startActivity<WebViewActivity> {
                                val bookSource = ReadBook.bookSource
                                putExtra("title", getString(R.string.chapter_pay))
                                putExtra("url", it)
                                putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                                putExtra("sourceName", bookSource?.bookSourceName)
                                putExtra("sourceType", bookSource?.getSourceType())
                            }
                        } else if (it.isTrue()) {
                            // 购买成功后刷新目录
                            ReadBook.book?.let {
                                ReadBook.curTextChapter = null
                                BookHelp.delContent(book, chapter)
                                loadChapterList(book)
                            }
                        }
                    }
                    .onError {
                        AppLog.put("执行购买操作出错\n${it.localizedMessage}", it, true)
                    }
            }
            noButton()
        }
    }

    /** 点击图片 */
    override fun oldClickImg(src: String): Boolean {
        val urlMatcher = paramPattern.matcher(src)
        if (urlMatcher.find()) {
            val urlOptionStr = src.substring(urlMatcher.end())
            val urlOptionMap = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()
            val click = urlOptionMap?.get("click")
            if (click != null) {
                Coroutine.async(lifecycleScope, IO) {
                        val source = ReadBook.bookSource ?: return@async
                        val java =
                            SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
                        val book = ReadBook.book ?: return@async
                        val chapter =
                            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                                ?: throw Exception("no find chapter")
                        runScriptWithContext {
                            source.evalJS(click) {
                                put("java", java)
                                put("book", book)
                                put("chapter", chapter)
                                put("result", src)
                            }
                        }
                    }
                    .onError {
                        AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
                    }
                return true
            }
            val jsStr = urlOptionMap?.get("js") ?: return false
            Coroutine.async(lifecycleScope, IO) {
                    val source = ReadBook.bookSource ?: return@async
                    val book = ReadBook.book ?: return@async
                    val chapter =
                        appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                            ?: throw Exception("no find chapter")
                    val urlNoOption = src.take(urlMatcher.start())
                    AnalyzeRule(book, source).apply {
                        setCoroutineContext(coroutineContext)
                        setBaseUrl(chapter.url)
                        setChapter(chapter)
                        evalJS(jsStr, urlNoOption)
                    }
                }
                .onError {
                    AppLog.put("执行图片链接js键值出错\n${it.localizedMessage}", it, true)
                }
            return true
        }
        return false
    }

    override fun clickImg(click: String, src: String) {
        Coroutine.async(lifecycleScope, IO) {
                val source = ReadBook.bookSource ?: return@async
                val java = SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
                val book = ReadBook.book ?: return@async
                val chapter =
                    appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                        ?: throw Exception("no find chapter")
                runScriptWithContext {
                    source.evalJS(click) {
                        put("java", java)
                        put("book", book)
                        put("chapter", chapter)
                        put("result", src)
                    }
                }
            }
            .onError {
                AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
            }
    }

    override fun onReviewClick(paragraphNum: Int, count: Int, chapterIndex: Int) {
        if (paragraphNum != -1 && paragraphNum <= 0) return
        if (count <= 0) {
            toastOnUi(R.string.review_empty)
            return
        }
        val source = ReadBook.bookSource ?: return
        val reviewDialogTag = ReviewDetailDialog::class.simpleName
        val fragmentManager = supportFragmentManager
        fun showReviewDialog(dialog: ReviewDetailDialog) {
            val now = SystemClock.uptimeMillis()
            val taggedDialog = fragmentManager.findFragmentByTag(reviewDialogTag)
            val existingDialog =
                taggedDialog != null ||
                    fragmentManager.fragments.any {
                        it is ReviewDetailDialog && !it.isRemoving
                    }
            if (
                fragmentManager.isStateSaved ||
                    existingDialog ||
                    now - lastReviewDialogRequestAt < REVIEW_DIALOG_REQUEST_COOLDOWN_MS
            )
                return
            lastReviewDialogRequestAt = now
            dialog.showNow(fragmentManager, reviewDialogTag)
        }
        if (source.isJsSource()) {
            val book = ReadBook.book ?: return
            showReviewDialog(
                ReviewDetailDialog(
                    paragraphNum = paragraphNum,
                    totalCount = count,
                    chapterIndex = chapterIndex,
                    paragraphData = ChapterProvider.getReviewKeyById(paragraphNum, chapterIndex),
                    bookUrl = book.bookUrl,
                    sourceKey = source.getKey(),
                    ruleHash = source.mainJs.hashCode(),
                )
            )
            return
        }
        val rule =
            source.ruleReview
                ?: run {
                    toastOnUi(R.string.review_rule_missing)
                    return
                }
        if (!rule.enabled) {
            toastOnUi(R.string.review_rule_missing)
            return
        }
        if (rule.reviewDetailUrl.isNullOrBlank()) {
            toastOnUi(R.string.review_detail_url_missing)
            return
        }
        if (rule.detailListRule.isNullOrBlank() || rule.detailContentRule.isNullOrBlank()) {
            toastOnUi(R.string.review_detail_rule_missing)
            return
        }
        val book = ReadBook.book ?: return
        showReviewDialog(
            ReviewDetailDialog(
                paragraphNum = paragraphNum,
                totalCount = count,
                chapterIndex = chapterIndex,
                paragraphData = ChapterProvider.getReviewKeyById(paragraphNum, chapterIndex),
                bookUrl = book.bookUrl,
                sourceKey = source.getKey(),
                ruleHash = rule.hashCode(),
            )
        )
    }

    private fun loadReviewSummaryIfNeeded() {
        val source =
            ReadBook.bookSource
                ?: run {
                    clearReviewSummaryProviders()
                    return
                }
        val book =
            ReadBook.book
                ?: run {
                    clearReviewSummaryProviders()
                    return
                }
        val chapterIndex = ReadBook.durChapterIndex
        val textChapter = ReadBook.curTextChapter
        if (
            textChapter != null &&
                textChapter.chapter.index == chapterIndex &&
                !textChapter.hasBodyContent
        ) {
            clearReviewSummaryProviders()
            return
        }

        if (source.isJsSource()) {
            loadJsReviewSummaryIfNeeded(book, source, chapterIndex)
            return
        }
        val rule =
            source.ruleReview
                ?: run {
                    clearReviewSummaryProviders()
                    return
                }
        val summaryUrl = rule.configuredSummaryUrl()
        if (summaryUrl == null) {
            clearReviewSummaryProviders()
            return
        }

        val key = buildReviewSummaryKey(book, source, rule.hashCode(), chapterIndex)
        if (reviewSummaryAppliedKey == key || reviewSummaryLoadingKey == key) return
        synchronized(reviewSummaryCache) { reviewSummaryCache[key] }
            ?.let { cached ->
                applyReviewSummary(key, chapterIndex, cached)
                prefetchAdjacentReviewSummary(book, source, rule, chapterIndex)
                return
            }

        reviewSummaryLoadingKey = key
        val requestToken = ++reviewSummaryRequestToken
        if (reviewSummaryAppliedKey != key) {
            ChapterProvider.clearReviewProviders()
        }
        Coroutine.async(lifecycleScope, IO) {
                val chapter =
                    appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return@async null
                if (chapter.isVolume) return@async null
                val analyzeUrl =
                    AnalyzeUrl(
                        summaryUrl,
                        baseUrl = chapter.url,
                        source = source,
                        ruleData = book,
                        chapter = chapter,
                        coroutineContext = coroutineContext,
                    )
                val body =
                    analyzeUrl.getStrResponseAwait(useWebView = false).body ?: return@async null
                ReviewRuleParser.parseSummary(
                    body,
                    rule,
                    source,
                    book,
                    chapter,
                    analyzeUrl.url,
                    coroutineContext,
                )
            }
            .onSuccess(Main) { result ->
                releaseReviewSummaryLoadingKey(key)
                if (requestToken != reviewSummaryRequestToken) return@onSuccess
                val currentBook = ReadBook.book ?: return@onSuccess
                val currentSource = ReadBook.bookSource ?: return@onSuccess
                val currentRule = currentSource.ruleReview ?: return@onSuccess
                val currentKey =
                    buildReviewSummaryKey(
                        currentBook,
                        currentSource,
                        currentRule.hashCode(),
                        ReadBook.durChapterIndex,
                    )
                if (currentKey != key) return@onSuccess
                if (result == null) {
                    ChapterProvider.clearReviewProviders()
                    return@onSuccess
                }
                synchronized(reviewSummaryCache) {
                    reviewSummaryCache[key] = result
                }
                applyReviewSummary(key, chapterIndex, result)
                prefetchAdjacentReviewSummary(book, source, rule, chapterIndex)
            }
            .onError {
                releaseReviewSummaryLoadingKey(key)
                if (requestToken != reviewSummaryRequestToken) return@onError
                val currentBook = ReadBook.book ?: return@onError
                val currentSource = ReadBook.bookSource ?: return@onError
                val currentRule = currentSource.ruleReview ?: return@onError
                if (
                    buildReviewSummaryKey(
                        currentBook,
                        currentSource,
                        currentRule.hashCode(),
                        ReadBook.durChapterIndex,
                    ) != key
                )
                    return@onError
                ChapterProvider.clearReviewProviders()
                AppLog.put("加载段评统计出错\n${it.localizedMessage}", it)
            }
    }

    private fun loadJsReviewSummaryIfNeeded(
        book: Book,
        source: BookSource,
        chapterIndex: Int,
    ) {
        val sourceHash = source.mainJs.hashCode()
        val key = buildReviewSummaryKey(book, source, sourceHash, chapterIndex)
        if (reviewSummaryAppliedKey == key || reviewSummaryLoadingKey == key) return
        synchronized(reviewSummaryCache) { reviewSummaryCache[key] }
            ?.let { cached ->
                applyReviewSummary(key, chapterIndex, cached)
                return
            }

        reviewSummaryLoadingKey = key
        val requestToken = ++reviewSummaryRequestToken
        if (reviewSummaryAppliedKey != key) {
            ChapterProvider.clearReviewProviders()
        }
        Coroutine.async(lifecycleScope, IO) {
                val chapter =
                    appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return@async null
                if (chapter.isVolume) return@async null
                JsSourceReview.getReviewSummaryAwait(source, book, chapter)
            }
            .onSuccess(Main) { result ->
                releaseReviewSummaryLoadingKey(key)
                if (requestToken != reviewSummaryRequestToken) return@onSuccess
                val currentBook = ReadBook.book ?: return@onSuccess
                val currentSource = ReadBook.bookSource ?: return@onSuccess
                if (!currentSource.isJsSource()) return@onSuccess
                val currentKey =
                    buildReviewSummaryKey(
                        currentBook,
                        currentSource,
                        currentSource.mainJs.hashCode(),
                        ReadBook.durChapterIndex,
                    )
                if (currentKey != key) return@onSuccess
                if (result == null) {
                    reviewSummaryAppliedKey = key
                    ChapterProvider.clearReviewProviders()
                    return@onSuccess
                }
                synchronized(reviewSummaryCache) {
                    reviewSummaryCache[key] = result
                }
                applyReviewSummary(key, chapterIndex, result)
            }
            .onError {
                releaseReviewSummaryLoadingKey(key)
                if (requestToken != reviewSummaryRequestToken) return@onError
                val currentBook = ReadBook.book ?: return@onError
                val currentSource = ReadBook.bookSource ?: return@onError
                if (!currentSource.isJsSource()) return@onError
                if (
                    buildReviewSummaryKey(
                        currentBook,
                        currentSource,
                        currentSource.mainJs.hashCode(),
                        ReadBook.durChapterIndex,
                    ) != key
                )
                    return@onError
                ChapterProvider.clearReviewProviders()
                AppLog.put("加载 JavaScript 段评统计出错\n${it.localizedMessage}", it)
            }
    }

    private fun clearReviewSummaryProviders() {
        reviewSummaryRequestToken++
        reviewSummaryAppliedKey = null
        reviewSummaryLoadingKey = null
        ChapterProvider.clearReviewProviders()
    }

    private fun applyReviewSummary(
        key: String,
        chapterIndex: Int,
        result: ReviewRuleParser.SummaryResult,
    ) {
        ChapterProvider.setReviewProviders(
            countProvider = { targetChapterIndex, reviewId ->
                if (targetChapterIndex == chapterIndex) result.counts[reviewId] ?: 0 else 0
            },
            keyProvider = { targetChapterIndex, reviewId ->
                if (targetChapterIndex == chapterIndex) result.keys[reviewId] else null
            },
            chapterIndex = chapterIndex,
        )
        reviewSummaryAppliedKey = key
        readView.upContent(relativePosition = 0, resetPageOffset = false)
    }

    private fun prefetchAdjacentReviewSummary(
        book: Book,
        source: BaseSource,
        rule: ReviewRule,
        chapterIndex: Int,
    ) {
        val maxIndex =
            if (ReadBook.simulatedChapterSize > 0) {
                ReadBook.simulatedChapterSize
            } else {
                ReadBook.chapterSize
            }
        if (maxIndex <= 0) return

        val requestToken = reviewSummaryRequestToken
        for (targetIndex in intArrayOf(chapterIndex - 1, chapterIndex + 1)) {
            if (targetIndex !in 0 until maxIndex) continue
            val loadedChapter =
                sequenceOf(
                        ReadBook.prevTextChapter,
                        ReadBook.curTextChapter,
                        ReadBook.nextTextChapter,
                    )
                    .filterNotNull()
                    .firstOrNull { it.chapter.index == targetIndex }
            if (loadedChapter == null || !loadedChapter.hasBodyContent) continue

            val key = buildReviewSummaryKey(book, source, rule.hashCode(), targetIndex)
            if (reviewSummaryLoadingKey == key) continue
            if (synchronized(reviewSummaryCache) { reviewSummaryCache.containsKey(key) }) continue
            val shouldPrefetch =
                synchronized(reviewSummaryPrefetchingKeys) {
                    reviewSummaryPrefetchingKeys.add(key)
                }
            if (!shouldPrefetch) continue

            Coroutine.async(lifecycleScope, IO) {
                    val chapter =
                        appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex)
                            ?: return@async null
                    if (chapter.isVolume) return@async null
                    val summaryUrl =
                        rule.reviewSummaryUrl?.takeIf { it.isNotBlank() } ?: return@async null
                    val analyzeUrl =
                        AnalyzeUrl(
                            summaryUrl,
                            baseUrl = chapter.url,
                            source = source,
                            ruleData = book,
                            chapter = chapter,
                            coroutineContext = coroutineContext,
                        )
                    val body =
                        analyzeUrl.getStrResponseAwait(useWebView = false).body ?: return@async null
                    ReviewRuleParser.parseSummary(
                        body,
                        rule,
                        source,
                        book,
                        chapter,
                        analyzeUrl.url,
                        coroutineContext,
                    )
                }
                .onSuccess(Main) { result ->
                    synchronized(reviewSummaryPrefetchingKeys) {
                        reviewSummaryPrefetchingKeys.remove(key)
                    }
                    if (requestToken != reviewSummaryRequestToken || result == null)
                        return@onSuccess
                    synchronized(reviewSummaryCache) {
                        reviewSummaryCache[key] = result
                    }
                }
                .onError {
                    synchronized(reviewSummaryPrefetchingKeys) {
                        reviewSummaryPrefetchingKeys.remove(key)
                    }
                }
        }
    }

    private fun buildReviewSummaryKey(
        book: Book,
        source: BaseSource,
        reviewHash: Int,
        chapterIndex: Int,
    ): String = "${source.getKey()}|${book.bookUrl}|$reviewHash#$chapterIndex"

    private fun releaseReviewSummaryLoadingKey(key: String) {
        if (reviewSummaryLoadingKey == key) {
            reviewSummaryLoadingKey = null
        }
    }

    /** 朗读按钮 */
    override fun onClickReadAloud() {
        autoPageStop()
        when {
            !BaseReadAloudService.isRun -> {
                ReadAloud.upReadAloudClass()
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim) {
                    val pos = readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(
                                    startPos = line.pagePosition,
                                    rewindToSentenceStart = AppConfig.readAloudStartAtSentence,
                                )
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(
                                startPos = line.pagePosition,
                                rewindToSentenceStart = AppConfig.readAloudStartAtSentence,
                            )
                        }
                    } else {
                        ReadBook.readAloud(
                            rewindToSentenceStart = AppConfig.readAloudStartAtSentence
                        )
                    }
                } else {
                    ReadBook.readAloud(rewindToSentenceStart = AppConfig.readAloudStartAtSentence)
                }
            }

            BaseReadAloudService.pause -> {
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    pageChanged = false
                    val pos = readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(
                                    startPos = line.pagePosition,
                                    rewindToSentenceStart = AppConfig.readAloudStartAtSentence,
                                )
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(
                                startPos = line.pagePosition,
                                rewindToSentenceStart = AppConfig.readAloudStartAtSentence,
                            )
                        }
                    } else {
                        ReadBook.readAloud(
                            rewindToSentenceStart = AppConfig.readAloudStartAtSentence
                        )
                    }
                } else {
                    ReadAloud.resume(this)
                }
            }

            else -> ReadAloud.pause(this)
        }
    }

    override fun showHelp() {
        showHelp("readMenuHelp")
    }

    /** 长按图片 */
    @SuppressLint("RtlHardcoded")
    override fun onImageLongPress(x: Float, y: Float, src: String) {
        showContextMenu(
            x = x,
            y = y,
            preferAbove = true,
            alignToStart = false,
            actions =
                listOf(
                    ReaderContextAction(getString(R.string.show), "show"),
                    ReaderContextAction(getString(R.string.refresh), "refresh"),
                    ReaderContextAction(getString(R.string.action_save), "save"),
                    ReaderContextAction(getString(R.string.menu), "menu"),
                    ReaderContextAction(getString(R.string.select_folder), "selectFolder"),
                ),
        ) {
            when (it) {
                "show" -> showDialogFragment(PhotoDialog(src, isBook = true))
                "refresh" -> viewModel.refreshImage(src)
                "save" -> {
                    val path = ACache.get().getAsString(AppConst.imagePathKey)
                    if (path.isNullOrEmpty()) {
                        selectImageDir.launch {
                            value = src
                        }
                    } else {
                        viewModel.saveImage(src, path.toUri())
                    }
                }

                "menu" -> showActionMenu()
                "selectFolder" -> selectImageDir.launch()
            }
        }
    }

    /** colorSelectDialog */
    override fun onColorSelected(dialogId: Int, color: Int) =
        ReadBookConfig.durConfig.run {
            when (dialogId) {
                TEXT_COLOR -> {
                    setCurTextColor(color)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6, 9, 11))
                    if (AppConfig.readBarStyleFollowPage) {
                        postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                    }
                }

                TEXT_ACCENT_COLOR -> {
                    setCurTextAccentColor(color)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6, 9, 11))
                    if (AppConfig.readBarStyleFollowPage) {
                        postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                    }
                }

                BG_COLOR -> {
                    setCurBg(0, "#${color.hexString}")
                    postEvent(EventBus.UP_CONFIG, arrayListOf(1))
                    if (AppConfig.readBarStyleFollowPage) {
                        postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                    }
                }

                REVIEW_ICON_COLOR -> {
                    ReadBookConfig.reviewIconColor = color
                    postEvent(EventBus.UP_CONFIG, arrayListOf(8, 9, 11))
                }

                UNDERLINE_COLOR -> {
                    ReadBookConfig.underlineColor = color
                    postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
                }

                HighlightStyleDialog.HL_FILL,
                HighlightStyleDialog.HL_TEXT,
                HighlightStyleDialog.HL_UNDERLINE,
                HighlightStyleDialog.HL_STRIKE,
                HighlightStyleDialog.HL_BOX,
                HighlightStyleDialog.HL_EMPHASIS,
                HighlightStyleDialog.HL_SHADOW -> {
                    val style =
                        HighlightStyleDialog.applyChannelColor(
                            currentHighlightStyle(),
                            dialogId,
                            color,
                        )
                    onHighlightStyleChanged(style)
                    refreshHighlightStyleDialog()
                }

                TIP_COLOR -> {
                    ReadTipConfig.tipColor = color
                    postEvent(EventBus.TIP_COLOR, "")
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                }

                TIP_DIVIDER_COLOR -> {
                    ReadTipConfig.tipDividerColor = color
                    postEvent(EventBus.TIP_COLOR, "")
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                }

                TITLE_NUMBER_COLOR -> {
                    ReadBookConfig.titleNumberColor = color
                    postEvent(EventBus.TIP_COLOR, "")
                    postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                }

                TITLE_COLOR -> {
                    ReadBookConfig.titleColor = color
                    postEvent(EventBus.TIP_COLOR, "")
                    postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                }
            }
        }

    /** colorSelectDialog */
    override fun onDialogDismissed(dialogId: Int) = Unit

    override fun onTocRegexDialogResult(tocRegex: String) {
        ReadBook.book?.let {
            it.tocUrl = tocRegex
            loadChapterList(it)
        }
    }

    private fun sureSyncProgress(progress: BookProgress) {
        alert(R.string.get_book_progress) {
            setMessage(R.string.current_progress_exceeds_cloud)
            okButton {
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    /* 进度条跳转到指定章节 */
    override fun skipToChapter(index: Int) {
        ReadBook.saveCurrentBookProgress() // 退出章节跳转恢复此时进度
        viewModel.openChapter(index)
    }

    /* 全文搜索跳转 */
    override fun navigateToSearch(searchResult: SearchResult, index: Int) {
        viewModel.searchResultIndex = index
        skipToSearch(searchResult)
    }

    override fun onMenuShow() {
        readView.autoPager.pause()
        updateReadAloudFloatBar(menuShowing = true)
    }

    override fun onMenuHide() {
        readView.autoPager.resume()
        aloudControls.reveal()
        updateReadAloudFloatBar(menuHiding = true)
    }

    private fun updateReadAloudFloatBar(
        menuShowing: Boolean = false,
        menuHiding: Boolean = false,
    ) {
        val menuVisible =
            when {
                menuShowing -> true
                menuHiding -> bottomDialog > 0 || searchMenu.bottomMenuVisible
                else -> menuLayoutIsVisible
            }
        aloudControls.update(menuVisible)
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upSeekBarThrottle.invoke()
        readView.onLayoutPageCompleted(index, page)
    }

    /* 全文搜索跳转 */
    private fun skipToSearch(searchResult: SearchResult) {
        if (searchResult.chapterIndex != ReadBook.durChapterIndex) {
            viewModel.openChapter(searchResult.chapterIndex) {
                jumpToPosition(searchResult)
            }
        } else {
            jumpToPosition(searchResult)
        }
    }

    private fun jumpToPosition(searchResult: SearchResult) {
        val curTextChapter = ReadBook.curTextChapter ?: return
        searchMenu.updateSearchInfo()
        val searchResultPositions = viewModel.searchResultPositions(curTextChapter, searchResult)
        val (pageIndex, lineIndex, charIndex, addLine, charIndex2) = searchResultPositions
        ReadBook.skipToPage(pageIndex) {
            isSelectingSearchResult = true
            readView.curPage.selectStartMoveIndex(0, lineIndex, charIndex)
            when (addLine) {
                0 ->
                    readView.curPage.selectEndMoveIndex(
                        0,
                        lineIndex,
                        charIndex + searchResultPositions[5] - 1,
                    )

                1 ->
                    readView.curPage.selectEndMoveIndex(
                        0,
                        lineIndex + 1,
                        charIndex2,
                    )
                // consider change page, jump to scroll position
                -1 -> readView.curPage.selectEndMoveIndex(1, 0, charIndex2)
            }
            readView.isTextSelected = true
            isSelectingSearchResult = false
        }
    }

    override fun addBookmark() {
        val book = ReadBook.book
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex)
        if (book != null && page != null) {
            val bookmark =
                book.createBookMark().apply {
                    chapterIndex = ReadBook.durChapterIndex
                    chapterPos = ReadBook.durChapterPos
                    chapterName = page.title
                    bookText = page.text.trim()
                }
            showDialogFragment(BookmarkDialog(bookmark))
        }
    }

    override fun toggleBookmark() {
        if (bookmarkTogglePending) return
        val book = ReadBook.book ?: return
        val page = readView.curPage.textPage
        if (page.lines.isEmpty()) {
            toastOnUi(R.string.create_bookmark_error)
            return
        }
        val pageStart = page.chapterPosition
        val pageEnd = pageStart + page.charSize
        bookmarkTogglePending = true
        lifecycleScope.launch {
            var awaitingConfirmation = false
            try {
                val confirmDelete = bookmarkToggleMutex.withLock {
                    val pageBookmarks =
                        withContext(IO) {
                            appDb.bookmarkDao.getByBook(book.name, book.author).filter {
                                it.chapterIndex == page.chapterIndex &&
                                    it.chapterPos in pageStart..<pageEnd
                            }
                        }
                    when {
                        pageBookmarks.size > 1 -> pageBookmarks
                        pageBookmarks.isNotEmpty() -> {
                            deleteBookmarks(pageBookmarks)
                            null
                        }

                        else -> {
                            val bookmark =
                                book.createBookMark().apply {
                                    chapterIndex = page.chapterIndex
                                    chapterPos = pageStart
                                    chapterName = page.title
                                    bookText = page.text.trim()
                                }
                            withContext(IO) {
                                appDb.bookmarkDao.insert(bookmark)
                            }
                            toastOnUi(R.string.bookmark_added)
                            null
                        }
                    }
                }
                confirmDelete?.let { pageBookmarks ->
                    var deleteConfirmed = false
                    alert(titleResource = R.string.bookmark) {
                        setMessage(getString(R.string.delete_page_bookmarks, pageBookmarks.size))
                        okButton {
                            deleteConfirmed = true
                            lifecycleScope.launch {
                                try {
                                    bookmarkToggleMutex.withLock {
                                        deleteBookmarks(pageBookmarks)
                                    }
                                } finally {
                                    bookmarkTogglePending = false
                                }
                            }
                        }
                        noButton()
                        onDismiss {
                            if (!deleteConfirmed) {
                                bookmarkTogglePending = false
                            }
                        }
                    }
                    awaitingConfirmation = true
                }
            } finally {
                if (!awaitingConfirmation) {
                    bookmarkTogglePending = false
                }
            }
        }
    }

    private suspend fun deleteBookmarks(pageBookmarks: List<Bookmark>) {
        withContext(IO) {
            appDb.bookmarkDao.delete(*pageBookmarks.toTypedArray())
        }
        toastOnUi(R.string.bookmark_removed)
    }

    private fun observeBookmarks() {
        val book = ReadBook.book ?: return
        val bookKey = book.name to book.author
        if (bookmarkBookKey == bookKey) return
        bookmarkJob?.cancel()
        bookmarkBookKey = bookKey
        bookmarks = emptyList()
        bookmarkJob = lifecycleScope.launch {
            appDb.bookmarkDao.flowByBook(book.name, book.author).collect {
                bookmarks = it
                upBookmarkIndicator()
            }
        }
    }

    private fun resetBookmarkObserver() {
        bookmarkJob?.cancel()
        bookmarkJob = null
        bookmarkBookKey = null
        bookmarks = emptyList()
        readView.curPage.showBookmarkIndicator(false)
    }

    fun upBookmarkIndicator() {
        val pageView = readView.curPage
        val textPage = pageView.textPage
        val hasBookmark =
            textPage.lines.isNotEmpty() &&
                bookmarks.any {
                    it.chapterIndex == textPage.chapterIndex && textPage.containPos(it.chapterPos)
                }
        val showIndicator = AppConfig.pullToToggleBookmark && !readView.isScroll && hasBookmark
        pageView.showBookmarkIndicator(showIndicator)
    }

    override fun changeReplaceRuleState() {
        ReadBook.book?.let {
            it.setUseReplaceRule(!it.getUseReplaceRule())
            ReadBook.saveRead()
            menu?.findItem(R.id.menu_enable_replace)?.isChecked = it.getUseReplaceRule()
            viewModel.replaceRuleChanged()
        }
    }

    override fun setReplacePreview(enabled: Boolean) {
        val generation = ++replacePreviewGeneration
        replacePreviewJob?.cancel()
        replacePreviewJob = null
        if (!enabled) return
        val sourcePosition =
            readView
                .getReadPosition()
                ?.takeIf { it.first == ReadBook.durChapterIndex }
                ?.second
                ?.chapterPosition ?: ReadBook.durChapterPos
        replacePreviewJob = lifecycleScope.launch {
            val preview =
                try {
                    ReadBook.buildReplacePreview(sourcePosition)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("生成替换净化预览失败\n${e.localizedMessage}", e)
                    null
                } ?: return@launch
            if (generation != replacePreviewGeneration || !readView.showReplacePreview(preview)) {
                preview.previewChapter.cancelLayout()
            }
        }
    }

    private fun startBackupJob() {
        backupJob?.cancel()
        backupJob =
            lifecycleScope.launch(IO) {
                delay(300000)
                ReadBook.book?.let {
                    AppWebDav.uploadBookProgress(it)
                    ensureActive()
                    it.update()
                    Backup.autoBack(this@ReadBookActivity)
                }
            }
    }

    override fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog =
            alert(R.string.get_book_progress) {
                setMessage(R.string.cloud_progress_exceeds_current)
                okButton {
                    ReadAloud.detachReadAloudFollow()
                    ReadBook.setProgress(progress)
                }
                noButton()
            }
    }

    override fun finish() {
        val book = ReadBook.book ?: return super.finish()
        if (ReadBook.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    ReadBook.book?.removeType(BookType.notShelf)
                    ReadBook.book?.save()
                    SourceCallBack.callBackBook(
                        SourceCallBack.ADD_BOOK_SHELF,
                        ReadBook.bookSource,
                        ReadBook.book,
                    )
                    ReadBook.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(
            SourceCallBack.END_READ,
            ReadBook.bookSource,
            ReadBook.book,
            ReadBook.curTextChapter?.chapter,
        )
    }

    override fun onDestroy() {
        readerInitializationEpoch++
        bookInfoNavigationEpoch++
        bookInfoNavigationJob?.cancel()
        super.onDestroy()
        readMenu.dispose()
        searchMenu.dispose()
        aloudControls.dispose()
        tts?.clearTts()
        textActionMenu.dismiss()
        dismissContextMenu()
        readView.onDestroy()
        ReadBook.unregister(this)
        handler.removeCallbacksAndMessages(null) // 清理Handler消息
        if (!ReadBook.inBookshelf && !isChangingConfigurations) {
            viewModel.removeFromBookshelf(null)
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    override fun observeLiveBus() = run {
        observeEvent<String>(EventBus.TIME_CHANGED) { readView.upTime() }
        observeEvent<Int>(EventBus.BATTERY_CHANGED) { readView.upBattery(it) }
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                onClickReadAloud()
            } else {
                ReadBook.readAloud(!BaseReadAloudService.pause)
            }
        }
        observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG) { values ->
            if (5 in values && isInitFinish) {
                readView.updateScrollReadPosition(preserveText = true)
            }
            values.forEach { value ->
                when (value) {
                    0 -> upSystemUiVisibility()
                    1 -> readView.upBg()
                    2 -> {
                        readView.upStyle()
                        upBookmarkIndicator()
                    }
                    3 -> readView.upBgAlpha()
                    4 -> readView.upPageSlopSquare()
                    5 ->
                        if (isInitFinish) {
                            ReadBook.loadContent(
                                resetPageOffset = ReadBook.isScroll,
                                readPositionVersion = readView.getReadPositionVersion(),
                            )
                        }
                    6 -> readView.upContent(resetPageOffset = false)
                    8 -> ChapterProvider.upStyle()
                    9 -> readView.invalidateTextPage()
                    10 -> ChapterProvider.upLayout()
                    11 -> readView.submitRenderTask()
                    12 -> readView.upPageTouchClick()
                    13 -> upPageAnim()
                }
            }
            updateReadAloudFloatBar()
        }
        observeEvent<Int>(EventBus.ALOUD_STATE) {
            if (it == Status.STOP || it == Status.PAUSE) {
                ReadBook.curTextChapter?.let { textChapter ->
                    val page = textChapter.getPageByReadPos(ReadBook.durChapterPos)
                    if (page != null) {
                        page.removePageAloudSpan()
                        readView.upContent(resetPageOffset = false)
                    }
                }
            }
            updateReadAloudFloatBar()
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_FOLLOW) {
            updateReadAloudFloatBar()
        }
        observeEventSticky<Int>(EventBus.TTS_PROGRESS) { chapterStart ->
            lastReadAloudChapterStart = chapterStart
            lastReadAloudChapterIndex = ReadAloud.readAloudChapterIndex
            if (!ReadAloud.followReadAloudPosition) {
                scheduleAloudFollowCheck()
                return@observeEventSticky
            }
            lifecycleScope.launch(Main.immediate) {
                if (
                    BaseReadAloudService.shouldApplySpeechProgressToVisibleReader(
                        isSpeechPlaying = BaseReadAloudService.isPlay()
                    )
                ) {
                    ReadBook.curTextChapter?.let { textChapter ->
                        ReadBook.durChapterPos = chapterStart
                        val pageIndex = ReadBook.durPageIndex
                        val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
                        val page = textChapter.getPage(pageIndex)
                        page?.upPageAloudSpan(aloudSpanStart)
                        if (readView.isTouching && readView.curPage.textPage === page) {
                            // Same-page speech ranges must not cancel the user's pending swipe.
                            readView.curPage.invalidateContentView()
                            readView.submitRenderTask()
                        } else {
                            upContent()
                        }
                    }
                }
            }
        }
        observeEvent<Boolean>(PreferKey.keepLight) {
            upScreenTimeOut()
        }
        observeEvent<Boolean>(PreferKey.textSelectAble) {
            readView.curPage.upSelectAble(it)
        }
        observeEvent<String>(PreferKey.showBrightnessView) {
            readMenu.upBrightnessState()
        }
        observeEvent<List<SearchResult>>(EventBus.SEARCH_RESULT) {
            viewModel.searchResultList = it
        }
        observeEvent<Boolean>(EventBus.UPDATE_READ_ACTION_BAR) {
            readMenu.reset()
        }
        observeEvent<Boolean>(EventBus.UP_SEEK_BAR) {
            readMenu.upSeekBar()
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_CONTENT) { // 书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshDurChapter()
            }
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) { // 书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                ReadBook.book?.let {
                    loadChapterList(it)
                }
            }
        }
    }

    private fun upScreenTimeOut() {
        val keepLightPrefer = getPrefString(PreferKey.keepLight)?.toInt() ?: 0
        screenTimeOut = keepLightPrefer * 1000L
        screenOffTimerStart()
    }

    /** 重置黑屏时间 */
    override fun screenOffTimerStart() {
        handler.post {
            if (screenTimeOut < 0) {
                keepScreenOn(true)
                return@post
            }
            val t = screenTimeOut - sysScreenOffTime
            if (t > 0) {
                keepScreenOn(true)
                handler.removeCallbacks(screenOffRunnable)
                handler.postDelayed(screenOffRunnable, screenTimeOut)
            } else {
                keepScreenOn(false)
            }
        }
    }

    companion object {
        private const val REVIEW_DIALOG_REQUEST_COOLDOWN_MS = 1500L
        const val RESULT_DELETED = 100
        private const val ACTION_READER_ITEM_PREFIX = "readerItem:"
        private const val ACTION_READER_MORE = "readerMore"
        private const val ACTION_READER_CONFIG = "readerConfig"
        private const val ACTION_HIGHLIGHT_STYLE = "highlightStyle"
        private const val ACTION_HIGHLIGHT_NOTE = "highlightNote"
        private const val ACTION_HIGHLIGHT_CREATE_RULE = "highlightCreateRule"
        private const val ACTION_HIGHLIGHT_COPY = "highlightCopy"
        private const val ACTION_HIGHLIGHT_DELETE = "highlightDelete"
        private const val ACTION_HIGHLIGHT_RULE_EDIT = "highlightRuleEdit"
        private const val ACTION_HIGHLIGHT_RULE_MANAGE = "highlightRuleManage"
        private const val ACTION_HIGHLIGHT_RULE_DISABLE = "highlightRuleDisable"
        private const val STATE_EDITING_HIGHLIGHT = "editingHighlightTime"
        private const val STATE_EDITING_HIGHLIGHT_OWNER = "editingHighlightOwner"
    }
}

internal fun visibleHighlightStyle(style: HighlightStyle?): HighlightStyle {
    return style?.takeUnless { it.isEmpty } ?: HighlightStyles.presets.first()
}
