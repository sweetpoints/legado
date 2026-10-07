package io.legado.app.ui.book.info

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.withResumed
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Theme
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.AppBookDetailServicesRepository
import io.legado.app.data.repository.BookDetailBook
import io.legado.app.data.repository.BookDetailChapter
import io.legado.app.data.repository.BookDetailChildKind
import io.legado.app.data.repository.BookDetailChildOwner
import io.legado.app.data.repository.BookDetailChildResult
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.data.repository.BookDetailNativeKind
import io.legado.app.data.repository.BookDetailNativePayload
import io.legado.app.data.repository.BookDetailNativeTexts
import io.legado.app.data.repository.BookDetailNetworkResult
import io.legado.app.data.repository.BookDetailPosition
import io.legado.app.data.repository.BookDetailPrompt
import io.legado.app.data.repository.BookDetailPromptKind
import io.legado.app.data.repository.BookDetailServiceKind
import io.legado.app.data.repository.BookDetailSource
import io.legado.app.data.repository.DefaultBookDetailServiceSessionRepository
import io.legado.app.data.repository.EngineBookDetailNetworkRepository
import io.legado.app.data.repository.FileBookDetailChildRepository
import io.legado.app.data.repository.FileBookDetailSessionRepository
import io.legado.app.data.repository.RoomBookDetailChildServicesRepository
import io.legado.app.data.repository.RoomBookDetailNativeRepository
import io.legado.app.data.repository.RoomBookDetailNetworkStorageRepository
import io.legado.app.data.repository.RoomBookDetailRepository
import io.legado.app.data.repository.RoomBookDetailStorageRepository
import io.legado.app.data.repository.materializeBook
import io.legado.app.data.repository.materializeSource
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.model.sourceEngine.SourceUiScriptRunner
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.detail.BookDetailCommandTexts
import io.legado.app.ui.book.info.detail.BookDetailCommands
import io.legado.app.ui.book.info.detail.BookDetailNativeHost
import io.legado.app.ui.book.info.detail.BookDetailNativeLaunchers
import io.legado.app.ui.book.info.detail.BookDetailPreferencesViewModel
import io.legado.app.ui.book.info.detail.BookDetailPromptActions
import io.legado.app.ui.book.info.detail.BookDetailPromptScreen
import io.legado.app.ui.book.info.detail.BookDetailRoute
import io.legado.app.ui.book.info.detail.BookDetailViewModel
import io.legado.app.ui.book.info.edit.BookInfoEditActivity
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.dict.DictionaryResultAction
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openUrl
import io.legado.app.utils.toastOnUi
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose page and immutable state, with only platform result launchers and source callbacks in the
 * host.
 */
class BookInfoActivity :
    BaseComposeActivity(toolBarTheme = Theme.Dark, showOpenMenuIcon = false),
    GroupSelectDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeCoverDialog.CallBack,
    VariableDialog.Callback {
    private val childResultsRepository by lazy { FileBookDetailChildRepository(applicationContext) }
    private val serviceRepository by lazy { AppBookDetailServicesRepository() }
    private val detailRepository by lazy { RoomBookDetailRepository() }
    private val nativeRepository by lazy {
        RoomBookDetailNativeRepository(services = serviceRepository)
    }
    @Volatile private var compatibilityBook: Book? = null
    private val launchedChildTokens = mutableMapOf<BookDetailChildKind, String>()
    val viewModel by
        viewModels<BookDetailViewModel> {
            viewModelFactory {
                initializer {
                    val saved =
                        createSavedStateHandle().apply {
                            remove<String>("name")
                            remove<String>("author")
                            remove<String>("bookUrl")
                        }

                    val preparedTicket = intent.getStringExtra(BookInfoNavigation.PREPARED_TICKET)
                    if (!saved.contains("book.detail.ticket") && preparedTicket != null) {
                        saved["book.detail.ticket"] = preparedTicket
                    }
                    val storage = RoomBookDetailStorageRepository()
                    val networkStorage = RoomBookDetailNetworkStorageRepository()
                    val sessions =
                        FileBookDetailSessionRepository(
                            applicationContext,
                            storage,
                            detailRepository,
                            networkStorage,
                        )
                    val network = EngineBookDetailNetworkRepository()
                    BookDetailViewModel(
                        saved,
                        detailRepository,
                        sessions,
                        network,
                        if (preparedTicket == null) {
                            BookDetailIdentity(
                                intent.getStringExtra("name").orEmpty(),
                                intent.getStringExtra("author").orEmpty(),
                                intent.getStringExtra("bookUrl").orEmpty(),
                            )
                        } else null,
                        applicationWork,
                        childResultsRepository,
                        serviceRepository,
                        RoomBookDetailChildServicesRepository(),
                        DefaultBookDetailServiceSessionRepository(
                            serviceRepository,
                            detailRepository,
                            sessions,
                            network,
                        ),
                    )
                }
            }
        }
    val preferences by
        viewModels<BookDetailPreferencesViewModel> {
            viewModelFactory {
                initializer {
                    BookDetailPreferencesViewModel(
                        createSavedStateHandle().apply {
                            remove<String>("name")
                            remove<String>("author")
                            remove<String>("bookUrl")
                        },
                        serviceRepository,
                    )
                }
            }
        }
    private val tocResult =
        registerForActivityResult(TocActivityResult()) { values ->
            receive(BookDetailChildKind.Toc) { owner ->
                if (values == null) BookDetailChildResult(owner, canceled = true)
                else
                    BookDetailChildResult(
                        owner,
                        position =
                            BookDetailPosition(
                                values[0] as Int,
                                values[1] as Int,
                                values[3] as Int,
                                values[4] as Int,
                            ),
                        chapterChanged = values[2] as Boolean,
                        highlightTitleLength =
                            (values[TocActivityResult.HIGHLIGHT_LAYOUT_TITLE_LENGTH_INDEX] as Int)
                                .takeUnless {
                                    it == TocActivityResult.NO_HIGHLIGHT_LAYOUT_TITLE_LENGTH
                                },
                        highlightAnchor =
                            (values[TocActivityResult.HIGHLIGHT_ANCHOR_TEXT_INDEX] as String)
                                .takeIf { it.isNotEmpty() },
                    )
            }
        }
    private val readerResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            receive(BookDetailChildKind.Reader) {
                BookDetailChildResult(
                    it,
                    canceled = result.resultCode == RESULT_CANCELED,
                    resultCode = result.resultCode,
                )
            }
        }
    private val infoResult =
        registerForActivityResult(StartActivityContract(BookInfoEditActivity::class.java)) { result
            ->
            receive(BookDetailChildKind.InfoEditor) {
                BookDetailChildResult(
                    it,
                    canceled = result.resultCode != RESULT_OK,
                    resultCode = result.resultCode,
                )
            }
        }
    private val sourceResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            result ->
            receive(BookDetailChildKind.SourceEditor) {
                BookDetailChildResult(
                    it,
                    canceled = result.resultCode == RESULT_CANCELED,
                    resultCode = result.resultCode,
                )
            }
        }
    private val folderResult =
        registerForActivityResult(HandleFileContract()) { result ->
            receive(BookDetailChildKind.Folder) {
                BookDetailChildResult(
                    it,
                    canceled = result.uri == null,
                    value = result.uri?.toString(),
                )
            }
        }
    private val nativeHost by lazy {
        BookDetailNativeHost(
            this,
            BookDetailNativeLaunchers(
                { tocResult.launch(checkNotNull(it.book).bookUrl) },
                ::launchReader,
                { payload ->
                    infoResult.launch { putExtra("bookUrl", checkNotNull(payload.book).bookUrl) }
                },
                { payload ->
                    sourceResult.launch {
                        putExtra("sourceUrl", checkNotNull(payload.source).bookSourceUrl)
                    }
                },
                { folderResult.launch { title = getString(R.string.select_book_folder) } },
            ),
            { compatibilityBook = it },
            {
                setResult(RESULT_OK)
                finish()
            },
            { payload ->
                lifecycleScope.launch {
                    lifecycle.withResumed { commands.clearCache(payload.effect) }
                }
            },
        )
    }
    private val commands by lazy {
        BookDetailCommands(
            viewModel,
            preferences,
            lifecycleScope,
            { toastOnUi(it) },
            viewModel::navigate,
            ::requestClearCache,
            BookDetailCommandTexts(
                getString(R.string.webdav_not_configured),
                getString(R.string.chapter_list_empty),
                "Unexpected webFileData",
                getString(R.string.need_more_time_load_content),
            ),
        )
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        BookDetailChildKind.entries.forEach { kind ->
            savedInstanceState?.getString("book.detail.launch." + kind.name)?.let {
                launchedChildTokens[kind] = it
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        launchedChildTokens.forEach { (kind, token) ->
            outState.putString("book.detail.launch." + kind.name, token)
        }
        super.onSaveInstanceState(outState)
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val preferenceState by preferences.state.collectAsStateWithLifecycle()
        val lifecycle = LocalLifecycleOwner.current
        LaunchedEffect(preferences, lifecycle) {
            lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                preferences.refresh()
                awaitCancellation()
            }
        }
        LaunchedEffect(state.data?.book) {
            val snapshot = state.data?.book
            val prepared = withContext(Dispatchers.IO) { snapshot?.materializeBook() }
            ensureActive()
            compatibilityBook = prepared
        }
        Column(Modifier.fillMaxSize()) {
            preferenceState.error?.let { message ->
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(preferences::retry) { Text(getString(R.string.retry)) }
                }
            }
            Box(Modifier.weight(1f)) {
                BookDetailRoute(
                    viewModel,
                    preferenceState.values,
                    nativeRepository,
                    BookDetailNativeTexts(
                        "源变量可在js中通过source.getVariable()获取",
                        """书籍变量可在js中通过book.getVariable("custom")获取""",
                        getString(
                            R.string.auto_task_book_update_name,
                            state.data?.book?.name.orEmpty(),
                        ),
                    ),
                    { super.finish() },
                    ::deliver,
                    commands::action,
                    commands::click,
                    ::introAction,
                    ::introLink,
                    { viewModel.queue(BookDetailNativeKind.IntroImage, it) },
                    { toastOnUi(it) },
                    { !supportFragmentManager.isStateSaved },
                )
            }
        }
        BookDetailPromptScreen(
            state.session?.prompt,
            state.data?.book,
            state.session?.webFiles.orEmpty(),
            preferenceState.values,
            state.canInteract && preferenceState.loaded,
            BookDetailPromptActions(
                commands::dismiss,
                commands::confirm,
                commands::deleteOriginal,
                commands::deleteRemote,
                commands::uploadImported,
            ),
        )
    }

    private fun deliver(payload: BookDetailNativePayload) {
        val kind =
            when (payload.effect.kind) {
                BookDetailNativeKind.Toc -> BookDetailChildKind.Toc
                BookDetailNativeKind.Reader -> BookDetailChildKind.Reader
                BookDetailNativeKind.EditInfo -> BookDetailChildKind.InfoEditor
                BookDetailNativeKind.EditSource -> BookDetailChildKind.SourceEditor
                BookDetailNativeKind.ChooseFolder -> BookDetailChildKind.Folder
                BookDetailNativeKind.ChangeCover -> BookDetailChildKind.Cover
                BookDetailNativeKind.Group -> BookDetailChildKind.Group
                BookDetailNativeKind.SourceVariable,
                BookDetailNativeKind.BookVariable -> BookDetailChildKind.Variable
                BookDetailNativeKind.ChangeSource -> BookDetailChildKind.Source
                else -> null
            }
        kind?.let { launchedChildTokens[it] = payload.effect.token }
        nativeHost.deliver(payload)
    }

    private fun launchReader(payload: BookDetailNativePayload) {
        val book = checkNotNull(payload.book)
        val snapshot = checkNotNull(payload.effect.book)
        val target =
            when {
                snapshot.isAudio -> AudioPlayActivity::class.java
                snapshot.isVideo -> VideoPlayerActivity::class.java
                !snapshot.isLocal && snapshot.isImage && AppConfig.showMangaUi ->
                    ReadMangaActivity::class.java
                else -> ReadBookActivity::class.java
            }
        readerResult.launch(
            Intent(this, target).apply {
                putExtra("bookUrl", book.bookUrl)
                putExtra("inBookshelf", viewModel.state.value.data?.inBookshelf == true)
                putExtra("chapterChanged", payload.effect.flag)
                payload.effect.highlightTitleLength?.let { length ->
                    payload.effect.position?.let { position ->
                        putExtra("index", position.index)
                        putExtra("chapterPos", position.pos)
                        putExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, length)
                        payload.effect.highlightAnchor?.let {
                            putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, it)
                        }
                    }
                }
            }
        )
    }

    private fun receive(
        kind: BookDetailChildKind,
        build: (BookDetailChildOwner) -> BookDetailChildResult,
    ) {
        val model = viewModel
        val ledger = childResultsRepository
        val expected = launchedChildTokens[kind]
        applicationWork.launch {
            try {
                if (model.state.value.closed) return@launch
                val owner =
                    ledger.read(model.ticket).owners.firstOrNull {
                        it.kind == kind && (expected == null || it.token == expected)
                    } ?: return@launch
                val result = build(owner)
                withContext(NonCancellable) { ledger.result(model.ticket, result) }
                withContext(Dispatchers.Main.immediate) {
                    if (!model.state.value.closed) model.processChildren()
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (!isDestroyed && !model.state.value.closed) toastOnUi(error.localizedMessage)
                }
            }
        }
    }

    override val oldBook: Book?
        get() = compatibilityBook

    override fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit,
    ) {
        val model = viewModel
        val ledger = childResultsRepository
        val expected = launchedChildTokens[BookDetailChildKind.Source]
        applicationWork.launch {
            try {
                val before = ledger.read(model.ticket)
                val owner =
                    before.owners.firstOrNull {
                        it.kind == BookDetailChildKind.Source &&
                            (expected == null || expected == it.token)
                    }
                if (owner == null) {
                    if (expected !in before.completed) return@launch
                    val latest = detailRepository.reload(book.bookUrl) ?: return@launch
                    if (latest.book.origin != source.bookSourceUrl) return@launch
                } else {
                    val result =
                        BookDetailChildResult(
                            owner,
                            source = BookDetailSource.from(source),
                            network =
                                BookDetailNetworkResult(
                                    BookDetailBook.from(book),
                                    toc.map(BookDetailChapter::from),
                                    emptyList(),
                                ),
                        )
                    withContext(NonCancellable) { ledger.result(model.ticket, result) }
                    withContext(Dispatchers.Main.immediate) { model.processChildren() }
                    model.state.first {
                        it.closed || it.error != null || (it.loaded && !it.busy && !it.childPending)
                    }
                    if (
                        model.state.value.closed ||
                            model.state.value.error != null ||
                            owner.token !in ledger.read(model.ticket).completed
                    )
                        return@launch
                }
                deliverSourceCompletion(
                    checkNotNull(expected ?: owner?.token),
                    book,
                    source,
                    onSuccess,
                )
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (!isDestroyed && !model.state.value.closed) toastOnUi(error.localizedMessage)
                }
            }
        }
    }

    private suspend fun deliverSourceCompletion(
        token: String,
        changedBook: Book,
        changedSource: BookSource,
        onSuccess: () -> Unit,
    ) {
        while (!viewModel.state.value.closed && !isDestroyed) {
            lifecycle.withResumed {}
            var delivered = false
            // The IO claim may finish during pause. Its result belongs to this application job,
            // and must be rolled back unless the same resumed owner invokes the callback on Main.
            val claimed =
                withContext(NonCancellable) {
                    childResultsRepository.claimCallback(viewModel.ticket, token)
                }
            if (!claimed) return
            try {
                withContext(Dispatchers.Main.immediate) {
                    val data = viewModel.state.value.data
                    if (
                        !viewModel.state.value.closed &&
                            !isDestroyed &&
                            lifecycle.currentState == Lifecycle.State.RESUMED &&
                            data != null &&
                            data.book.bookUrl == changedBook.bookUrl &&
                            data.book.origin == changedSource.bookSourceUrl &&
                            data.source?.url == changedSource.bookSourceUrl
                    ) {
                        delivered = true
                        onSuccess()
                    }
                }
            } finally {
                if (!delivered) {
                    withContext(NonCancellable) {
                        childResultsRepository.rollbackCallback(viewModel.ticket, token)
                    }
                }
            }
            if (delivered) return
            val current = viewModel.state.value.data
            if (
                current == null ||
                    current.book.bookUrl != changedBook.bookUrl ||
                    current.book.origin != changedSource.bookSourceUrl ||
                    current.source?.url != changedSource.bookSourceUrl
            )
                return
        }
    }

    override fun coverChangeTo(coverUrl: String) {
        receive(BookDetailChildKind.Cover) { BookDetailChildResult(it, value = coverUrl) }
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        receive(BookDetailChildKind.Group) { BookDetailChildResult(it, number = groupId) }
    }

    override fun setVariable(key: String, variable: String?) {
        receive(BookDetailChildKind.Variable) { owner ->
            BookDetailChildResult(
                owner,
                canceled = key != (owner.sourceUrl ?: owner.bookUrl),
                value = variable,
                number = if (owner.sourceUrl != null) 1 else 0,
            )
        }
    }

    private fun requestClearCache() {
        viewModel.queue(BookDetailNativeKind.ClearCacheRequest)
    }

    private fun introAction(action: DictionaryResultAction) {
        val snapshot = viewModel.state.value.data ?: return
        lifecycleScope.launch {
            try {
                val prepared =
                    withContext(Dispatchers.IO) {
                        snapshot.source?.materializeSource()?.let { source ->
                            source to snapshot.book.materializeBook()
                        }
                    } ?: return@launch
                ensureActive()
                val active = viewModel.state.value.data
                if (
                    viewModel.state.value.closed ||
                        active?.book?.bookUrl != snapshot.book.bookUrl ||
                        active.book.intro != snapshot.book.intro ||
                        active.source != snapshot.source
                )
                    return@launch
                withContext(Dispatchers.IO) {
                    ensureActive()
                    val (source, book) = prepared
                    SourceUiScriptRunner.evaluate(
                        source,
                        action.script,
                        mapOf("result" to null, "book" to book),
                        SourceLoginJsExtensions(this@BookInfoActivity, source),
                    )
                }
            } catch (error: Exception) {
                ensureActive()
                AppLog.put("${snapshot.source?.name}: ${error.localizedMessage}", error)
                toastOnUi("${action.name} click error\n${error.localizedMessage}")
            }
        }
    }

    private fun introLink(url: String) {
        when {
            url.startsWith("legado://") || url.startsWith("yuedu://") ->
                startActivity(
                    Intent(this, OnLineImportActivity::class.java)
                        .setData(android.net.Uri.parse(url))
                )
            viewModel.state.value.data?.book?.intro?.startsWith("<useweb>") == true ->
                viewModel.prompt(BookDetailPrompt(BookDetailPromptKind.ExternalLink, value = url))
            else -> openUrl(url)
        }
    }

    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_INFO) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                viewModel.service(BookDetailServiceKind.Refresh)
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.refreshToc()
        }
    }

    override fun finish() {
        viewModel.close()
        super.finish()
    }

    companion object {
        private val applicationWork = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

internal fun resolveBookInfoTocTitle(
    storedTitle: String?,
    currentIndex: Int,
    chapters: List<BookChapter>,
): String? =
    storedTitle?.takeIf { it.isNotBlank() }
        ?: (chapters.getOrNull(currentIndex) ?: chapters.lastOrNull())
            ?.getDisplayTitle(chineseConvert = false)
            ?.takeIf { it.isNotBlank() }

internal fun resolveBookInfoReadProgress(book: Book): Int? =
    if (book.totalChapterNum <= 1) null else book.readProgress()?.let { (it * 100).roundToInt() }
