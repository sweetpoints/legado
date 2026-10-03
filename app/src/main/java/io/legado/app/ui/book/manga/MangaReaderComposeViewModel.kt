package io.legado.app.ui.book.manga

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
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
import io.legado.app.data.repository.DefaultMangaReaderOperationsRepository
import io.legado.app.data.repository.FileMangaReaderSessionRepository
import io.legado.app.data.repository.MangaChapterRefreshRequest
import io.legado.app.data.repository.MangaImageSaveRequest
import io.legado.app.data.repository.MangaNativeKind
import io.legado.app.data.repository.MangaNativeRequest
import io.legado.app.data.repository.MangaReaderEngineRepository
import io.legado.app.data.repository.MangaReaderLaunch
import io.legado.app.data.repository.MangaReaderSessionController
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.removeType
import io.legado.app.model.ReadManga
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
        viewModelScope.launch {
            transition.withLock {
                if (requestedGeneration != generation) return@withLock
                ownerJob?.cancelAndJoin()
                callback?.let { ReadManga.unregister(it) }
                if (newIntent) session?.release()
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
            )
        checkpoint()
    }

    fun page(direction: Int) {
        if (state.value.loading || state.value.menuVisible) return
        mutableState.value =
            state.value.copy(scrollCommand = MangaScrollCommand.Page(++commandId, direction))
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
                    footer = footer,
                    colorFilter = filter,
                )
    }

    fun previewFooter(footer: MangaFooterDraft) {
        mutableState.value = state.value.copy(footer = footer)
    }

    fun previewColorFilter(filter: MangaColorFilterValues) {
        mutableState.value = state.value.copy(colorFilter = filter)
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
                title = if (kind == MangaNativeKind.BookInfo) book.name else current.chapterName,
                author = book.author,
                sourceOrigin = book.sourceOrigin,
                sourceName = book.sourceName,
                sourceType = book.sourceType,
                bookSnapshot =
                    if (kind == MangaNativeKind.ImageDirectory) GSON.toJson(ReadManga.book)
                    else null,
                sourceSnapshot =
                    if (kind == MangaNativeKind.ImageDirectory)
                        ReadManga.bookSource?.let { GSON.toJson(it) }
                    else null,
            )
        ownerScope?.launch { session?.enqueue(request) }
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

    fun saveSelectedImage(request: MangaNativeRequest, directoryUri: String) {
        val imageUrl = request.imageUrl ?: return
        val bookUrl = request.bookUrl ?: return
        val snapshot = request.bookSnapshot ?: return
        val captured =
            MangaImageSaveRequest(bookUrl, snapshot, request.sourceSnapshot, imageUrl, directoryUri)
        ownerScope?.launch {
            try {
                operations.saveImage(captured)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                AppLog.put("保存图片出错\n${error.localizedMessage}", error)
                notify("保存图片出错\n${error.localizedMessage}")
            }
        }
    }

    fun refreshChapter() {
        val book = ReadManga.book ?: return
        val owner = generation
        val request =
            MangaChapterRefreshRequest(
                book.bookUrl,
                ReadManga.durChapterIndex,
                ReadManga.durChapterPos,
            )
        ownerScope?.launch {
            if (
                operations.refreshChapter(request) &&
                    generation == owner &&
                    ReadManga.book === book &&
                    ReadManga.durChapterIndex == request.chapterIndex &&
                    ReadManga.durChapterPos == request.pageIndex
            ) {
                engine?.openChapter(request.chapterIndex, request.pageIndex)
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

    override fun onCleared() {
        callback?.let { ReadManga.unregister(it) }
        ownerJob?.cancel()
        val releasedSession = session
        // The VM scope is already cancelled; durable owner cleanup needs an independent IO job.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { releasedSession?.release() }
        super.onCleared()
    }

    private companion object {
        const val SESSION_KEY = "manga.reader.session"
    }
}
