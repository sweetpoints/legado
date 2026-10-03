package io.legado.app.ui.book.manga

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.preferences.AppMangaFooterSettingsRepository
import io.legado.app.data.preferences.AppMangaReaderSettingsRepository
import io.legado.app.data.preferences.MangaColorFilterValues
import io.legado.app.data.preferences.MangaFooterDraft
import io.legado.app.data.preferences.MangaReaderSetting
import io.legado.app.data.preferences.MangaReaderSettingsValues
import io.legado.app.data.preferences.PreferenceMangaColorFilterRepository
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.data.repository.DefaultMangaReaderOperationsRepository
import io.legado.app.data.repository.FileMangaReaderSessionRepository
import io.legado.app.data.repository.MangaChapterRefreshRequest
import io.legado.app.data.repository.MangaImageSaveRequest
import io.legado.app.data.repository.MangaNativeKind
import io.legado.app.data.repository.MangaNativePhase
import io.legado.app.data.repository.MangaNativeRequest
import io.legado.app.data.repository.MangaReaderEngineRepository
import io.legado.app.data.repository.MangaReaderLaunch
import io.legado.app.data.repository.MangaReaderSessionController
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadManga
import io.legado.app.model.localBook.PdfFile
import io.legado.app.ui.book.info.BookInfoNavigation
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class MangaReaderBookValues(
    val bookUrl: String,
    val name: String,
    val author: String,
    val sourceOrigin: String?,
    val sourceName: String?,
    val sourceType: Int?,
    val local: Boolean,
    val pdf: Boolean,
)

internal data class MangaReaderUiState(
    val sessionId: String = "",
    val book: MangaReaderBookValues? = null,
    val items: List<MangaReaderItem> = emptyList(),
    val anchorIndex: Int = 0,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val chapterCount: Int = 0,
    val imageCount: Int = 0,
    val chapterName: String = "",
    val chapterUrl: String? = null,
    val loading: Boolean = true,
    val nextLoading: Boolean = false,
    val error: String? = null,
    val retryAllowed: Boolean = true,
    val menuVisible: Boolean = false,
    val scrollCommand: MangaScrollCommand? = null,
    val nativeRequests: List<MangaNativeRequest> = emptyList(),
    val pendingCloudProgress: BookProgress? = null,
    val settings: MangaReaderSettingsValues = MangaReaderSettingsValues(),
    val autoPage: Boolean = false,
    val autoScroll: Boolean = false,
    val footer: MangaFooterDraft = MangaFooterDraft(),
    val colorFilter: MangaColorFilterValues = MangaColorFilterValues(),
    val footerPage: MangaReaderItem.Page? = null,
    val exitPrompt: Boolean = false,
    val finishRequested: Boolean = false,
    val shelfAdded: Boolean = false,
    val deletedResult: Boolean = false,
    val colorFilterPreviewRevision: Int = 0,
    val settingsLoaded: Boolean = false,
    val menuOverflowRevision: Long = 0,
)

/**
 * Detached UI values and private UUID checkpoints surround the unchanged singleton reader engine.
 */
internal class MangaReaderComposeViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = FileMangaReaderSessionRepository()
    private val footerRepository = AppMangaFooterSettingsRepository()
    private val colorFilterRepository = PreferenceMangaColorFilterRepository()
    private val settingsRepository = AppMangaReaderSettingsRepository()
    private val operations = DefaultMangaReaderOperationsRepository()
    private val transition = Mutex()
    private val mutableState = MutableStateFlow(MangaReaderUiState())
    val state = mutableState.asStateFlow()
    private val notificationChannel = Channel<String>(Channel.UNLIMITED)
    val notifications = notificationChannel.receiveAsFlow()
    private var generation = 0L
    private var commandId = 0L
    private var ownerJob: Job? = null
    private var ownerScope: CoroutineScope? = null
    private var engine: MangaReaderEngineRepository? = null
    private var callback: ReadManga.Callback? = null
    private var session: MangaReaderSessionController? = null
    private var initialized = false

    fun initialize(launch: MangaReaderLaunch, newIntent: Boolean = false) {
        if (initialized && !newIntent) return
        initialized = true
        val requestedGeneration = ++generation
        mutableState.value = MangaReaderUiState()
        viewModelScope.launch {
            transition.withLock {
                if (requestedGeneration != generation) return@withLock
                ownerJob?.cancelAndJoin()
                callback?.let { ReadManga.unregister(it) }
                if (newIntent) session?.let { releaseSession(it) }
                val restoredId = if (!newIntent) savedState.get<String>(SESSION_KEY) else null
                val id = restoredId ?: UUID.randomUUID().toString()
                savedState[SESSION_KEY] = id
                val controller = MangaReaderSessionController(id, repository)
                session = controller
                val checkpoint = controller.restore(launch)
                if (requestedGeneration != generation) return@withLock
                val job = SupervisorJob(viewModelScope.coroutineContext[Job])
                ownerJob = job
                val scope = CoroutineScope(viewModelScope.coroutineContext + job)
                ownerScope = scope
                mutableState.value =
                    MangaReaderUiState(
                        sessionId = id,
                        menuVisible = checkpoint.menuVisible,
                        nativeRequests = checkpoint.nativeRequests,
                        pendingCloudProgress = checkpoint.pendingCloudProgress,
                    )
                val listener = createCallback(requestedGeneration, scope, controller)
                callback = listener
                ReadManga.register(listener)
                val readerEngine =
                    MangaReaderEngineRepository(
                        scope = scope,
                        context = getApplication<Application>(),
                        notifyUser = { if (generation == requestedGeneration) notify(it) },
                    )
                engine = readerEngine
                scope.launch {
                    reloadSettings()
                    controller.state.collect { value ->
                        if (generation == requestedGeneration && value != null) {
                            mutableState.value =
                                mutableState.value.copy(
                                    menuVisible = value.menuVisible,
                                    nativeRequests = value.nativeRequests,
                                    pendingCloudProgress = value.pendingCloudProgress,
                                )
                        }
                    }
                }
                scope.launch {
                    try {
                        readerEngine.initialize(checkpoint.launch)
                        if (generation != requestedGeneration) return@launch
                        if (
                            restoredId != null &&
                                checkpoint.chapterIndex in 0 until ReadManga.chapterSize &&
                                (checkpoint.chapterIndex != ReadManga.durChapterIndex ||
                                    checkpoint.pageIndex != ReadManga.durChapterPos)
                        ) {
                            readerEngine.openChapter(checkpoint.chapterIndex, checkpoint.pageIndex)
                        }
                        publishContent()
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        AppLog.put("初始化数据失败\n${error.localizedMessage}", error)
                        mutableState.value =
                            mutableState.value.copy(error = "初始化数据失败\n${error.localizedMessage}")
                    } finally {
                        if (generation == requestedGeneration) ReadManga.saveRead()
                    }
                }
            }
        }
    }

    private fun createCallback(
        owner: Long,
        scope: CoroutineScope,
        controller: MangaReaderSessionController,
    ): ReadManga.Callback =
        object : ReadManga.Callback {
            override fun upContent() {
                scope.launch { if (owner == generation) publishContent() }
            }

            override fun loadFail(msg: String, retry: Boolean) {
                scope.launch {
                    if (owner == generation)
                        mutableState.value =
                            mutableState.value.copy(
                                error = msg,
                                retryAllowed = retry,
                                nextLoading = false,
                            )
                }
            }

            override fun sureNewProgress(progress: BookProgress) {
                scope.launch {
                    if (owner == generation) controller.checkpointCloudProgress(progress)
                }
            }

            override fun showLoading() {
                scope.launch {
                    if (owner == generation)
                        mutableState.value =
                            mutableState.value.copy(
                                loading = true,
                                error = null,
                                scrollCommand = null,
                            )
                }
            }

            override fun startLoad() {
                scope.launch {
                    if (owner == generation)
                        mutableState.value = mutableState.value.copy(nextLoading = true)
                }
            }
        }

    private fun publishContent() {
        synchronized(ReadManga) {
            val readingBook = ReadManga.book ?: return
            val source = ReadManga.bookSource
            val content = ReadManga.mangaContents
            val current = mutableState.value
            val chapter = ReadManga.curMangaChapter
            val restore = current.loading && content.curFinish && content.items.isNotEmpty()
            mutableState.value =
                current.copy(
                    book =
                        MangaReaderBookValues(
                            readingBook.bookUrl,
                            readingBook.name,
                            readingBook.author,
                            source?.bookSourceUrl,
                            source?.bookSourceName,
                            mangaBrowserSourceKind(source),
                            readingBook.isLocal,
                            readingBook.isPdf,
                        ),
                    items = snapshotMangaItems(content.items),
                    footerPage =
                        if (restore)
                            snapshotMangaItems(content.items).getOrNull(content.pos)
                                as? MangaReaderItem.Page ?: current.footerPage
                        else current.footerPage,
                    anchorIndex = content.pos,
                    chapterIndex = ReadManga.durChapterIndex,
                    pageIndex = ReadManga.durChapterPos,
                    chapterCount = ReadManga.simulatedChapterSize,
                    imageCount = chapter?.imageCount ?: 0,
                    chapterName = chapter?.chapter?.title.orEmpty(),
                    chapterUrl = chapter?.chapter?.url,
                    nextLoading =
                        content.curFinish && ReadManga.hasNextChapter && !content.nextFinish,
                    error = if (content.curFinish) null else current.error,
                    scrollCommand =
                        if (restore) MangaScrollCommand.Jump(++commandId, content.pos)
                        else current.scrollCommand,
                )
        }
    }

    fun commandHandled(id: Long) {
        val current = mutableState.value
        if (current.scrollCommand?.id != id) return
        mutableState.value =
            current.copy(
                scrollCommand = null,
                loading =
                    if (current.scrollCommand is MangaScrollCommand.Jump) false
                    else current.loading,
            )
    }

    fun currentItem(item: MangaReaderItem) {
        if (state.value.loading) return
        ReadManga.durChapterPos = item.readingPage()
        when {
            ReadManga.durChapterIndex < item.chapterIndex -> ReadManga.moveToNextChapter()
            ReadManga.durChapterIndex > item.chapterIndex -> ReadManga.moveToPrevChapter()
            else -> ReadManga.curPageChanged()
        }
        mutableState.value =
            state.value.copy(
                chapterIndex = ReadManga.durChapterIndex,
                pageIndex = ReadManga.durChapterPos,
                footerPage = (item as? MangaReaderItem.Page) ?: state.value.footerPage,
            )
        checkpoint()
    }

    fun page(direction: Int) {
        if (state.value.loading || state.value.menuVisible) return
        mutableState.value =
            state.value.copy(scrollCommand = MangaScrollCommand.Page(++commandId, direction))
    }

    fun hardwareMenu() {
        val current = state.value
        mutableState.value =
            current.copy(
                menuVisible = true,
                menuOverflowRevision =
                    current.menuOverflowRevision + if (current.menuVisible) 1 else 0,
            )
        checkpoint()
    }

    fun setMenu(visible: Boolean) {
        if (visible && state.value.loading) return
        mutableState.value = state.value.copy(menuVisible = visible)
        checkpoint()
    }

    private fun checkpoint() {
        val captured = state.value
        val controller = session ?: return
        ownerScope?.launch {
            controller.checkpoint(captured.menuVisible, captured.chapterIndex, captured.pageIndex)
        }
    }

    suspend fun reloadSettings() {
        val owner = generation
        val settings = settingsRepository.load()
        val footer = withContext(Dispatchers.IO) { footerRepository.load() }
        val filter = colorFilterRepository.load()
        if (generation == owner)
            mutableState.value =
                state.value.copy(
                    settings = settings,
                    settingsLoaded = true,
                    footer = footer,
                    colorFilter = filter,
                )
    }

    fun previewFooter(footer: MangaFooterDraft) {
        mutableState.value = state.value.copy(footer = footer)
    }

    fun previewColorFilter(filter: MangaColorFilterValues) {
        mutableState.value =
            state.value.copy(
                colorFilter = filter,
                colorFilterPreviewRevision = state.value.colorFilterPreviewRevision + 1,
            )
    }

    fun setSetting(setting: MangaReaderSetting, enabled: Boolean) {
        val owner = generation
        ownerScope?.launch {
            val settings = settingsRepository.set(setting, enabled)
            if (owner == generation) {
                mutableState.value = state.value.copy(settings = settings)
                if (setting == MangaReaderSetting.HideChapterTitle) ReadManga.loadContent()
            }
        }
    }

    fun setPreload(count: Int) {
        val owner = generation
        ownerScope?.launch {
            val settings = settingsRepository.setPreload(count)
            if (owner == generation) mutableState.value = state.value.copy(settings = settings)
        }
    }

    fun setAutoSpeed(speed: Int) {
        val owner = generation
        ownerScope?.launch {
            val settings = settingsRepository.setAutoSpeed(speed)
            if (owner == generation) mutableState.value = state.value.copy(settings = settings)
        }
    }

    fun setAutomaticPaging(page: Boolean) {
        mutableState.value =
            state.value.copy(
                autoPage = if (page) !state.value.autoPage else false,
                autoScroll = if (!page) !state.value.autoScroll else false,
            )
    }

    fun skipToPage(index: Int) {
        val current = state.value
        val itemIndex =
            current.items.indexOfFirst {
                it.chapterIndex == current.chapterIndex && it.pageIndex == index
            }
        if (itemIndex < 0) return
        ReadManga.durChapterPos = index
        mutableState.value =
            current.copy(
                pageIndex = index,
                scrollCommand = MangaScrollCommand.Jump(++commandId, itemIndex),
            )
        checkpoint()
    }

    fun openChapter(index: Int, page: Int = 0) = engine?.openChapter(index, page)

    fun chapter(direction: Int) {
        if (direction > 0) ReadManga.moveToNextChapter(true) else ReadManga.moveToPrevChapter(true)
    }

    fun volumePage(direction: Int) {
        if (state.value.loading) return
        mutableState.value =
            state.value.copy(scrollCommand = MangaScrollCommand.Page(++commandId, direction))
    }

    fun previewEpaperThreshold(value: Int) {
        mutableState.value =
            state.value.copy(settings = state.value.settings.copy(threshold = value))
    }

    fun retry() {
        mutableState.value = state.value.copy(error = null)
        ReadManga.loadOrUpContent()
    }

    fun resolveCloudProgress(accept: Boolean) {
        val progress = state.value.pendingCloudProgress ?: return
        if (accept) ReadManga.setProgress(progress)
        ownerScope?.launch { session?.checkpointCloudProgress(null) }
    }

    fun enqueueNative(kind: MangaNativeKind, imageUrl: String? = null) {
        val current = state.value
        val book = current.book ?: return
        val request =
            MangaNativeRequest(
                ticket = UUID.randomUUID().toString(),
                kind = kind,
                bookUrl = book.bookUrl,
                imageUrl =
                    imageUrl
                        ?: if (
                            kind == MangaNativeKind.ChapterBrowser ||
                                kind == MangaNativeKind.ExternalBrowser
                        )
                            current.chapterUrl
                        else null,
                title =
                    if (kind == MangaNativeKind.BookInfo || kind == MangaNativeKind.ChangeSource)
                        book.name
                    else current.chapterName,
                author = book.author,
                sourceOrigin = book.sourceOrigin,
                sourceName = book.sourceName,
                sourceType = book.sourceType,
                bookSnapshot = GSON.toJson(ReadManga.book),
                sourceSnapshot = ReadManga.bookSource?.let { GSON.toJson(it) },
            )
        val owner = generation
        val controller = session ?: return
        ownerScope?.launch {
            try {
                if (kind == MangaNativeKind.BookInfo) {
                    withContext(NonCancellable) {
                        val ticket =
                            BookInfoNavigation.prepare(
                                getApplication<Application>(),
                                BookDetailIdentity(book.name, book.author, book.bookUrl),
                            )
                        var transferred = false
                        try {
                            if (generation == owner && ownerJob?.isActive == true) {
                                controller.enqueue(request.copy(preparedTicket = ticket))
                                transferred = true
                            }
                        } finally {
                            // A cancelled preparation owns only its freshly created child ticket.
                            if (!transferred)
                                BookInfoNavigation.abandon(getApplication<Application>(), ticket)
                        }
                    }
                } else controller.enqueue(request)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                AppLog.put("打开漫画页面失败\n${error.localizedMessage}", error)
                if (generation == owner) notify("打开漫画页面失败\n${error.localizedMessage}")
            }
        }
    }

    fun nativeRequest(ticket: String?): MangaNativeRequest? =
        state.value.nativeRequests.firstOrNull {
            it.ticket == ticket && it.phase == MangaNativePhase.Claimed
        }

    fun saveImage(imageUrl: String) {
        val readingBook = ReadManga.book ?: return
        val captured =
            MangaImageSaveRequest(
                readingBook.bookUrl,
                GSON.toJson(readingBook),
                ReadManga.bookSource?.let { GSON.toJson(it) },
                imageUrl,
                "",
            )
        val owner = generation
        ownerScope?.launch {
            val directory =
                withContext(Dispatchers.IO) { ACache.get().getAsString(AppConst.imagePathKey) }
            if (owner != generation) return@launch
            if (directory.isNullOrEmpty()) {
                val request =
                    MangaNativeRequest(
                        ticket = UUID.randomUUID().toString(),
                        kind = MangaNativeKind.ImageDirectory,
                        bookUrl = captured.bookUrl,
                        imageUrl = captured.imageUrl,
                        bookSnapshot = captured.bookSnapshot,
                        sourceSnapshot = captured.sourceSnapshot,
                    )
                session?.enqueue(request)
            } else {
                try {
                    // Once a cached destination is accepted, a book switch cannot cancel its
                    // export.
                    viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            operations.saveImage(captured.copy(directoryUri = directory))
                        } catch (error: Exception) {
                            currentCoroutineContext().ensureActive()
                            AppLog.put("保存图片出错\n${error.localizedMessage}", error)
                            if (owner == generation) notify("保存图片出错\n${error.localizedMessage}")
                        }
                    }
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    AppLog.put("保存图片出错\n${error.localizedMessage}", error)
                    if (owner == generation) notify("保存图片出错\n${error.localizedMessage}")
                }
            }
        }
    }

    fun resumed() {
        ReadManga.readStartTime = System.currentTimeMillis()
    }

    fun paused() {
        ReadManga.upReadTime()
        if (ReadManga.inBookshelf) {
            ReadManga.saveRead()
            if (!BuildConfig.DEBUG) {
                if (AppConfig.syncBookProgressPlus) ReadManga.syncProgress()
                else ReadManga.uploadProgress()
            }
        }
        ReadManga.cancelPreDownloadTask()
    }

    fun networkAvailable() {
        if (AppConfig.syncBookProgressPlus && !state.value.loading && ReadManga.inBookshelf)
            syncProgress()
    }

    suspend fun claimNative(
        ticket: String,
        resumed: () -> Boolean,
        dispatch: (MangaNativeRequest) -> Unit,
    ) {
        session?.claimAndDispatch(ticket, resumed, dispatch)
    }

    fun completeNative(ticket: String, cancelled: Boolean = false) {
        ownerScope?.launch { session?.complete(ticket, cancelled) }
    }

    fun handleBookInfoResult(ticket: String?, deleted: Boolean) {
        acceptNativeResult(ticket) { _, controller, owner ->
            controller.complete(checkNotNull(ticket))
            if (owner == generation) {
                if (deleted) finishFromBookInfo() else ReadManga.loadOrUpContent()
            }
        }
    }

    fun handleCatalogResult(
        ticket: String?,
        chapterIndex: Int?,
        pageIndex: Int?,
        pdfPage: Int = -1,
    ) {
        acceptNativeResult(ticket) { request, controller, owner ->
            val pdf =
                withContext(Dispatchers.IO) {
                    request.bookSnapshot?.let {
                        GSON.fromJsonObject<Book>(it).getOrThrow().isPdf
                    } == true
                }
            val position = if (pdf && pdfPage >= 0) pdfPage % PdfFile.PAGE_SIZE else pageIndex
            if (chapterIndex != null && position != null) {
                controller.checkpoint(state.value.menuVisible, chapterIndex, position)
                if (owner == generation) engine?.openChapter(chapterIndex, position)
            }
            controller.complete(checkNotNull(ticket), cancelled = chapterIndex == null)
        }
    }

    fun handleImageDirectoryResult(ticket: String?, directoryUri: String?) {
        acceptNativeResult(ticket) { request, controller, owner ->
            controller.complete(
                checkNotNull(ticket),
                cancelled = directoryUri == null,
                directoryUri = directoryUri,
            )
            if (directoryUri != null) {
                withContext(Dispatchers.IO) {
                    ACache.get().put(AppConst.imagePathKey, directoryUri)
                }
                saveSelectedImage(request, directoryUri, owner)
            }
        }
    }

    private fun acceptNativeResult(
        ticket: String?,
        apply: suspend (MangaNativeRequest, MangaReaderSessionController, Long) -> Unit,
    ) {
        if (ticket == null) return
        viewModelScope.launch {
            state.filter { it.sessionId.isNotEmpty() }.first()
            transition.withLock {
                val request = nativeRequest(ticket) ?: return@withLock
                val controller = session ?: return@withLock
                // The platform has delivered this result; preserve its accepted receipt on
                // disposal.
                val owner = generation
                withContext(NonCancellable) { apply(request, controller, owner) }
            }
        }
    }

    private fun saveSelectedImage(request: MangaNativeRequest, directoryUri: String, owner: Long) {
        val imageUrl = request.imageUrl ?: return
        val bookUrl = request.bookUrl ?: return
        val snapshot = request.bookSnapshot ?: return
        val captured =
            MangaImageSaveRequest(bookUrl, snapshot, request.sourceSnapshot, imageUrl, directoryUri)
        // Accepted export work captures its full payload and survives switching the reader's book.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                operations.saveImage(captured)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                AppLog.put("保存图片出错\n${error.localizedMessage}", error)
                if (owner == generation) notify("保存图片出错\n${error.localizedMessage}")
            }
        }
    }

    fun refreshChapter() {
        val book = ReadManga.book ?: return
        mutableState.value = state.value.copy(loading = true, error = null)
        val owner = generation
        val request =
            MangaChapterRefreshRequest(
                book.bookUrl,
                ReadManga.durChapterIndex,
                ReadManga.durChapterPos,
            )
        ownerScope?.launch {
            try {
                val refreshed = operations.refreshChapter(request)
                if (
                    generation == owner &&
                        ReadManga.book === book &&
                        ReadManga.durChapterIndex == request.chapterIndex &&
                        ReadManga.durChapterPos == request.pageIndex
                ) {
                    if (refreshed) engine?.openChapter(request.chapterIndex, request.pageIndex)
                    else mutableState.value = state.value.copy(loading = false, error = "未找到漫画章节")
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                AppLog.put("刷新漫画章节失败", error)
                if (generation == owner && ReadManga.book === book) {
                    mutableState.value =
                        state.value.copy(
                            loading = false,
                            error = error.localizedMessage ?: "刷新漫画章节失败",
                        )
                }
            }
        }
    }

    fun bookSnapshot(): Book? = synchronized(ReadManga) { ReadManga.book?.copy() }

    fun changeSource(book: Book, toc: List<BookChapter>, onSuccess: () -> Unit) {
        mutableState.value = state.value.copy(loading = true, error = null)
        engine?.changeTo(book, toc, onSuccess)
    }

    fun requestExit() {
        if (ReadManga.book == null || ReadManga.inBookshelf) {
            mutableState.value = state.value.copy(finishRequested = true)
        } else if (state.value.settings.showAddToShelfAlert) {
            mutableState.value = state.value.copy(exitPrompt = true)
        } else resolveExit(addToShelf = false)
    }

    fun dismissExit() {
        mutableState.value = state.value.copy(exitPrompt = false)
    }

    fun resolveExit(addToShelf: Boolean) {
        val capturedBook = ReadManga.book ?: return requestExit()
        val owner = generation
        mutableState.value = state.value.copy(exitPrompt = false)
        ownerScope?.launch {
            if (addToShelf) {
                val added = operations.addToBookshelf(capturedBook.bookUrl)
                if (owner == generation && ReadManga.book === capturedBook) {
                    if (added) {
                        capturedBook.removeType(BookType.notShelf)
                        ReadManga.inBookshelf = true
                        mutableState.value = state.value.copy(shelfAdded = true)
                    } else notify("未找到漫画书籍")
                }
            } else {
                operations.removeFromBookshelf(capturedBook.bookUrl)
                if (owner == generation)
                    mutableState.value = state.value.copy(finishRequested = true)
            }
        }
    }

    fun finishFromBookInfo() {
        mutableState.value = state.value.copy(deletedResult = true, finishRequested = true)
    }

    fun consumeShelfAdded() {
        mutableState.value = state.value.copy(shelfAdded = false)
    }

    fun reloadContent() = ReadManga.loadOrUpContent()

    fun syncProgress() {
        if (ReadManga.inBookshelf) ReadManga.syncProgress({ callback?.sureNewProgress(it) })
    }

    private fun notify(message: String) {
        notificationChannel.trySend(message)
    }

    private suspend fun releaseSession(controller: MangaReaderSessionController) {
        withContext(NonCancellable) {
            controller.state.value?.nativeRequests?.forEach { request ->
                val ticket = request.preparedTicket
                if (
                    ticket != null &&
                        (request.phase == MangaNativePhase.Pending ||
                            request.phase == MangaNativePhase.Cancelled)
                ) {
                    BookInfoNavigation.abandon(getApplication<Application>(), ticket)
                }
            }
            controller.release()
        }
    }

    override fun onCleared() {
        callback?.let { ReadManga.unregister(it) }
        ownerJob?.cancel()
        val releasedSession = session
        // The VM scope is already cancelled; durable owner cleanup needs an independent IO job.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            releasedSession?.let { releaseSession(it) }
        }
        super.onCleared()
    }

    private companion object {
        const val SESSION_KEY = "manga.reader.session"
    }
}
