package io.legado.app.ui.book.manga

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bumptech.glide.Glide
import io.legado.app.BuildConfig
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.preferences.MangaColorFilterValues
import io.legado.app.data.preferences.MangaFooterDraft
import io.legado.app.data.repository.MangaNativeKind
import io.legado.app.data.repository.MangaNativeRequest
import io.legado.app.data.repository.MangaReaderLaunch
import io.legado.app.help.book.isImage
import io.legado.app.help.storage.Backup
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.info.BookInfoNavigation
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaEpaperDialog
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.read.showBookDownloadDialog
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/** Compose owns reader controls; this Host only performs window and native platform effects. */
class ReadMangaActivity :
    BaseComposeActivity(),
    ChangeBookSourceDialog.CallBack,
    MangaColorFilterDialog.Callback,
    MangaEpaperDialog.Callback {
    internal val viewModel by
        viewModels<MangaReaderComposeViewModel> {
            viewModelFactory {
                initializer {
                    val saved = createSavedStateHandle()
                    // Public launch data is captured into a private UUID session, never SavedState.
                    intent.extras?.keySet()?.forEach { key -> saved.remove<Any?>(key) }
                    MangaReaderComposeViewModel(application, saved)
                }
            }
        }
    private val networkListener by lazy { NetworkChangedListener(this) }
    private val nativeResults by lazy { MangaNativeResultRegistry(activityResultRegistry) }
    private val bookInfoTickets = linkedSetOf<String>()
    private val catalogTickets = linkedSetOf<String>()
    private val imageTickets = linkedSetOf<String>()
    private var sourceTicket: String? = null
    private var menuKeyPressed = false
    private var appliedMenuVisibility: Boolean? = null
    private var appliedFilterRevision = 0

    private fun bookInfoLauncher(ticket: String) =
        nativeResults.launcher(
            "bookInfo",
            ticket,
            ActivityResultContracts.StartActivityForResult(),
        ) { capturedTicket, result ->
            bookInfoTickets.remove(capturedTicket)
            viewModel.handleBookInfoResult(capturedTicket, result.resultCode == RESULT_OK)
        }

    private fun catalogLauncher(ticket: String) =
        nativeResults.launcher(
            "catalog",
            ticket,
            TocActivityResult(),
        ) { capturedTicket, result ->
            catalogTickets.remove(capturedTicket)
            val pdfPage = result?.get(TocActivityResult.PDF_PAGE_INDEX) as? Int ?: -1
            viewModel.handleCatalogResult(
                capturedTicket,
                result?.get(0) as? Int,
                result?.get(1) as? Int,
                pdfPage,
            )
        }

    private fun imageDirectoryLauncher(ticket: String) =
        nativeResults.launcher(
            "imageDirectory",
            ticket,
            HandleFileContract(),
        ) { capturedTicket, result ->
            imageTickets.remove(capturedTicket)
            viewModel.handleImageDirectoryResult(capturedTicket, result.uri?.toString())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        }
        super.onCreate(savedInstanceState)
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        bookInfoTickets.addAll(savedInstanceState?.getStringArrayList(BOOK_INFO_TICKET).orEmpty())
        catalogTickets.addAll(savedInstanceState?.getStringArrayList(CATALOG_TICKET).orEmpty())
        imageTickets.addAll(savedInstanceState?.getStringArrayList(IMAGE_TICKET).orEmpty())
        // Register every still-launched UUID, including an older external Activity's late result.
        bookInfoTickets.toList().forEach { bookInfoLauncher(it) }
        catalogTickets.toList().forEach { catalogLauncher(it) }
        imageTickets.toList().forEach { imageDirectoryLauncher(it) }
        sourceTicket = savedInstanceState?.getString(SOURCE_TICKET)
        onBackPressedDispatcher.addCallback(this) { viewModel.requestExit() }
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentDetached(manager: FragmentManager, fragment: Fragment) {
                    if (fragment is ChangeBookSourceDialog) {
                        val ticket = fragment.arguments?.getString(SOURCE_DIALOG_TICKET)
                        ticket?.let { viewModel.completeNative(it, cancelled = true) }
                        if (sourceTicket == ticket) sourceTicket = null
                    }
                }
            },
            false,
        )
        viewModel.initialize(captureLaunch(intent))
    }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        MangaReaderRoute(
            viewModel,
            MangaReaderHostActions(
                ready = ::nativeReady,
                dispatch = ::dispatchNative,
                applyWindow = ::applyWindow,
                finish = ::finishPlatform,
                shelfAdded = { setResult(RESULT_OK) },
                notify = { toastOnUi(it) },
            ),
        )
    }

    private fun captureLaunch(intent: Intent) =
        MangaReaderLaunch(
            bookUrl = intent.getStringExtra("bookUrl"),
            inBookshelf = intent.getBooleanExtra("inBookshelf", true),
            chapterChanged = intent.getBooleanExtra("chapterChanged", false),
        )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.initialize(captureLaunch(intent), newIntent = true)
        // A new reader session retires only its old source dialog; its late receipt is ignored.
        supportFragmentManager.fragments.filterIsInstance<ChangeBookSourceDialog>().forEach {
            it.dismissAllowingStateLoss()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(BOOK_INFO_TICKET, ArrayList(bookInfoTickets))
        outState.putStringArrayList(CATALOG_TICKET, ArrayList(catalogTickets))
        outState.putStringArrayList(IMAGE_TICKET, ArrayList(imageTickets))
        outState.putString(SOURCE_TICKET, sourceTicket)
        super.onSaveInstanceState(outState)
    }

    private fun nativeReady() =
        !isFinishing &&
            !isDestroyed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !supportFragmentManager.isStateSaved

    private fun dispatchNative(request: MangaNativeRequest) {
        when (request.kind) {
            MangaNativeKind.BookInfo -> {
                val ticket = checkNotNull(request.preparedTicket)
                bookInfoTickets.add(request.ticket)
                try {
                    bookInfoLauncher(request.ticket).launch(BookInfoNavigation.intent(this, ticket))
                } catch (error: Throwable) {
                    bookInfoTickets.remove(request.ticket)
                    nativeResults.unregister("bookInfo", request.ticket)
                    val context = applicationContext
                    // Failed launch still owns this child ticket; late cleanup does not touch
                    // others.
                    lifecycleScope.launch(NonCancellable) {
                        BookInfoNavigation.abandon(context, ticket)
                    }
                    throw error
                }
            }
            MangaNativeKind.Catalog -> {
                catalogTickets.add(request.ticket)
                catalogLauncher(request.ticket).launch(checkNotNull(request.bookUrl))
            }
            MangaNativeKind.ImageDirectory -> {
                imageTickets.add(request.ticket)
                // The contract's returned value is only an operation UUID; image URLs stay private.
                imageDirectoryLauncher(request.ticket).launch { value = request.ticket }
            }
            MangaNativeKind.ChapterBrowser -> {
                startActivity(mangaChapterBrowserIntent(this, request))
                viewModel.completeNative(request.ticket)
            }
            MangaNativeKind.ExternalBrowser -> {
                openUrl(checkNotNull(request.imageUrl).substringBefore(",{"))
                viewModel.completeNative(request.ticket)
            }
            MangaNativeKind.ChangeSource -> {
                sourceTicket = request.ticket
                showDialogFragment(
                    ChangeBookSourceDialog(request.title.orEmpty(), request.author.orEmpty())
                        .apply {
                            arguments =
                                (arguments ?: Bundle()).apply {
                                    putString(SOURCE_DIALOG_TICKET, request.ticket)
                                }
                        }
                )
            }
            MangaNativeKind.Download -> {
                request.bookSnapshot
                    ?.let { GSON.fromJsonObject<Book>(it).getOrThrow() }
                    ?.let { showBookDownloadDialog(it) }
                viewModel.completeNative(request.ticket)
            }
            MangaNativeKind.FooterSettings -> {
                showDialogFragment(MangaFooterSettingDialog())
                viewModel.completeNative(request.ticket)
            }
            MangaNativeKind.ColorFilter -> {
                showDialogFragment(MangaColorFilterDialog())
                viewModel.completeNative(request.ticket)
            }
            MangaNativeKind.EpaperSettings -> {
                showDialogFragment(MangaEpaperDialog())
                viewModel.completeNative(request.ticket)
            }
        }
    }

    private fun applyWindow(state: MangaReaderUiState) {
        state.book?.name?.let(::setTitle)
        if (appliedMenuVisibility != state.menuVisible) {
            toggleSystemBar(state.menuVisible)
            appliedMenuVisibility = state.menuVisible
        }
        if (state.colorFilterPreviewRevision > appliedFilterRevision) {
            appliedFilterRevision = state.colorFilterPreviewRevision
            updateWindowBrightness(state.colorFilter.brightness)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumed()
        networkListener.register()
        networkListener.onNetworkChanged = {
            if (NetworkUtils.isAvailable()) viewModel.networkAvailable()
        }
        lifecycleScope.launch { viewModel.reloadSettings() }
    }

    override fun onPause() {
        viewModel.paused()
        networkListener.unRegister()
        if (!BuildConfig.DEBUG) Backup.autoBack(this)
        super.onPause()
    }

    override fun onDestroy() {
        nativeResults.close()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Glide.get(this).clearMemory()
    }

    override fun observeLiveBus() {
        observeEvent<MangaFooterConfig>(EventBus.UP_MANGA_CONFIG) { value ->
            viewModel.previewFooter(
                MangaFooterDraft(
                    value.hideChapterLabel,
                    value.hideChapter,
                    value.hidePageNumberLabel,
                    value.hidePageNumber,
                    value.hideProgressRatioLabel,
                    value.hideProgressRatio,
                    value.footerOrientation,
                    value.hideFooter,
                    value.hideChapterName,
                )
            )
        }
    }

    override val oldBook: Book?
        get() = viewModel.bookSnapshot()

    override fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit,
    ) {
        val ticket = sourceTicket
        if (viewModel.nativeRequest(ticket) == null) return
        if (!book.isImage) {
            toastOnUi("所选择的源不是漫画源")
            return
        }
        viewModel.changeSource(book, toc) {
            ticket?.let { viewModel.completeNative(it) }
            onSuccess()
        }
    }

    override fun updateColorFilter(config: MangaColorFilterConfig) {
        viewModel.previewColorFilter(
            MangaColorFilterValues(config.l, config.r, config.g, config.b, config.a)
        )
    }

    override fun updateEepaper(value: Int) = viewModel.previewEpaperThreshold(value)

    fun updateWindowBrightness(brightness: Int) {
        window.attributes =
            window.attributes.apply { screenBrightness = (brightness / 255f).coerceIn(0f, 1f) }
        window.decorView.postInvalidate()
    }

    fun skipToPage(index: Int) = viewModel.skipToPage(index)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && !menuKeyPressed) {
                menuKeyPressed = true
                viewModel.hardwareMenu()
            }
            if (event.action == KeyEvent.ACTION_UP) menuKeyPressed = false
            return true
        }
        val state = viewModel.state.value
        if (!state.menuVisible && !state.loading) {
            val rtl = state.settings.horizontal && state.settings.rightToLeft
            val direction =
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (rtl) 1 else -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl) -1 else 1
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_PAGE_UP -> -1
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_PAGE_DOWN -> 1
                    KeyEvent.KEYCODE_SPACE -> if (event.isShiftPressed) -1 else 1
                    else -> 0
                }
            if (direction != 0) {
                if (event.action == KeyEvent.ACTION_DOWN) viewModel.page(direction)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> viewModel.volumePage(-1)
            KeyEvent.KEYCODE_VOLUME_DOWN -> viewModel.volumePage(1)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun finish() = viewModel.requestExit()

    private fun finishPlatform(deleted: Boolean) {
        if (deleted) setResult(RESULT_DELETED)
        super.finish()
    }

    private companion object {
        const val BOOK_INFO_TICKET = "manga.native.bookInfo"
        const val CATALOG_TICKET = "manga.native.catalog"
        const val IMAGE_TICKET = "manga.native.image"
        const val SOURCE_TICKET = "manga.native.source"
        const val SOURCE_DIALOG_TICKET = "manga.native.sourceDialog"
    }
}
