package io.legado.app.ui.main.bookshelf

import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.preferences.BookshelfSettingsEffects
import io.legado.app.data.preferences.dispatchEvents
import io.legado.app.help.DirectLinkUpload
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.import.remote.RemoteBookActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.bookshelf.settings.BookshelfAddProgressDialog
import io.legado.app.ui.main.bookshelf.settings.BookshelfInputDialog
import io.legado.app.ui.main.bookshelf.settings.BookshelfInputResult
import io.legado.app.ui.main.bookshelf.settings.BookshelfSettingsDialog
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

abstract class BaseBookshelfFragment(layoutId: Int) :
    VMBaseFragment<BookshelfViewModel>(layoutId), MainFragmentInterface {

    internal data class TransferOwnerState(
        val operations: List<BookshelfTransferOperation> = emptyList(),
        val progress: Int? = null,
        val hasPendingFileImport: Boolean = false,
        val hasPendingExport: Boolean = false,
        val hasPendingResultPicker: Boolean = false,
    ) {
        val needsFileImportRecovery: Boolean
            get() = hasPendingFileImport && operations.none { it.label == "正在导入书单" }
    }

    override val position: Int?
        get() = arguments?.getInt("position")

    val activityViewModel by activityViewModels<MainViewModel>()
    override val viewModel by
        viewModels<BookshelfViewModel> {
            viewModelFactory {
                initializer {
                    BookshelfViewModel(
                        requireActivity().application,
                        createSavedStateHandle(),
                        resumePendingFileImport = !restoreWithoutPageView,
                    )
                }
            }
        }

    private val mutableTransferOwnerState = MutableStateFlow(TransferOwnerState())
    internal val transferOwnerState = mutableTransferOwnerState.asStateFlow()

    internal fun captureTransferOwnerState(): TransferOwnerState = transferOwnerSnapshot()

    private fun transferOwnerSnapshot(): TransferOwnerState {
        val transfer = viewModel.transfer
        return TransferOwnerState(
            operations = viewModel.operations.value,
            progress = transfer.addProgress.value.takeIf { it >= 0 },
            hasPendingFileImport = transfer.pendingFileImport.value != null,
            hasPendingExport = transfer.pendingExport.value != null,
            hasPendingResultPicker =
                transfer.launchedImportRequestId != null || transfer.exportPickerInFlight,
        )
    }

    internal fun retryPendingHostFileImport() = viewModel.retryPendingFileImport()

    internal fun launchPendingHostExportPicker() {
        viewModel.transfer.pendingExport.value?.let { launchPendingExportPicker(it) }
    }

    private val importBookshelf =
        registerForActivityResult(HandleFileContract()) { result ->
            val requestId =
                importRequestId
                    ?: viewModel.transfer.launchedImportRequestId
                    ?: return@registerForActivityResult
            val targetGroup = viewModel.transfer.importReturned(requestId)
            val host = activity as? MainBookshelfHost
            if (host != null) {
                host.acceptLegacyImportResult(this, requestId, result.uri?.toString(), targetGroup)
            } else {
                val uri = result.uri ?: return@registerForActivityResult
                if (targetGroup != null) viewModel.importBookshelfFile(uri.toString(), targetGroup)
            }
        }
    private val exportResult =
        registerForActivityResult(HandleFileContract()) { result ->
            val transfer = viewModel.transfer
            val requestId =
                exportRequestId
                    ?: transfer.launchedExportRequestId
                    ?: return@registerForActivityResult
            val path = transfer.launchedExportPath
            if (path != null) transfer.exportReturned(path, requestId)
            val host = activity as? MainBookshelfHost
            if (host != null)
                host.acceptLegacyExportResult(this, requestId, path, result.uri?.toString())
            else result.uri?.let { showExportLinkDialog(it) }
        }
    private var importRequestId: String? = null
    private var exportRequestId: String? = null

    internal var resultBridgeOnly: Boolean = false
        private set

    internal var pendingResultBridge: Boolean = false
        private set

    internal var restoreWithoutPageView: Boolean = false

    internal fun retainForPendingResult() {
        resultBridgeOnly = true
        pendingResultBridge = true
    }

    internal fun retainForPendingTransfer() {
        resultBridgeOnly = true
    }

    internal fun finishPendingResultBridge() {
        pendingResultBridge = false
        mutableTransferOwnerState.value = transferOwnerSnapshot()
    }

    abstract val groupId: Long
    abstract val books: List<Book>
    abstract var onlyUpdateRead: Boolean

    abstract fun gotoTop()

    protected fun handleBookshelfMenu(itemId: Int) {
        when (itemId) {
            R.id.menu_remote -> startActivity<RemoteBookActivity>()
            R.id.menu_search -> startActivity<SearchActivity>()
            R.id.menu_update_toc -> activityViewModel.upToc(books, onlyUpdateRead)
            R.id.menu_bookshelf_layout -> configBookshelf()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_add_local -> startActivity<ImportBookActivity>()
            R.id.menu_add_url -> showAddBookByUrlAlert()
            R.id.menu_bookshelf_manage ->
                startActivity<BookshelfManageActivity> {
                    putExtra("groupId", groupId)
                }

            R.id.menu_download ->
                startActivity<CacheActivity> {
                    putExtra("groupId", groupId)
                }

            R.id.menu_export_bookshelf -> viewModel.exportBookshelf(books)

            R.id.menu_import_bookshelf -> importBookshelfAlert(groupId)
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
    }

    abstract fun upGroup(data: List<BookGroup>)

    abstract fun upSort()

    override fun observeLiveBus() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    viewModel.transfer.addProgress.collect { count ->
                        if (count < 0) return@collect
                        // Resume after the host FragmentManager has completed its lifecycle
                        // transaction.
                        kotlinx.coroutines.yield()
                        if (
                            viewModel.transfer.addProgress.value >= 0 &&
                                !childFragmentManager.isStateSaved &&
                                childFragmentManager.findFragmentByTag(
                                    "BookshelfAddProgressDialog"
                                ) == null
                        ) {
                            BookshelfAddProgressDialog()
                                .showNow(childFragmentManager, "BookshelfAddProgressDialog")
                        }
                    }
                }
                launch {
                    viewModel.transfer.pendingExport.collect { path ->
                        if (path == null) return@collect
                        launchPendingExportPicker(path)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mutableTransferOwnerState.value = transferOwnerSnapshot()
        lifecycleScope.launch {
            combine(
                    viewModel.operations,
                    viewModel.transfer.addProgress,
                    viewModel.transfer.pendingFileImport,
                    viewModel.transfer.pendingExport,
                ) { _, _, _, _ ->
                    transferOwnerSnapshot()
                }
                .collect { mutableTransferOwnerState.value = it }
        }
    }

    fun showAddBookByUrlAlert() {
        showDialogFragment(BookshelfInputDialog.create(0, groupId))
    }

    fun configBookshelf() {
        showDialogFragment<BookshelfSettingsDialog>()
    }

    private fun importBookshelfAlert(groupId: Long) {
        showDialogFragment(BookshelfInputDialog.create(1, groupId))
    }

    internal fun submitShelfInput(kind: Int, result: BookshelfInputResult) {
        when (kind) {
            0 -> viewModel.addBookByUrl(result.text, result.groupId)
            1 -> viewModel.importBookshelf(result.text, result.groupId)
        }
    }

    internal fun selectBookshelfImportFile(groupId: Long) {
        importRequestId = viewModel.transfer.importRequested(groupId)
        viewModel.transfer.importLaunched(requireNotNull(importRequestId))
        importBookshelf.launch {
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("txt", "json")
        }
    }

    internal fun launchPendingExportPicker(path: String) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (parentFragmentManager.isStateSaved) return
        val transfer = viewModel.transfer
        val requestId = transfer.pendingExportRequestId ?: return
        if (transfer.exportPickerInFlight) return
        val file = java.io.File(path)
        transfer.exportLaunched(path, requestId)
        if (file.exists()) {
            exportRequestId = requestId
            exportResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData("bookshelf.json", file, "application/json")
            }
        } else {
            toastOnUi(getString(R.string.error))
            transfer.exportReturned(path, requestId)
        }
    }

    private fun showExportLinkDialog(value: String) {
        showDialogFragment(
            BookshelfInputDialog.create(
                2,
                value = value,
                summary = if (value.isAbsUrl()) DirectLinkUpload.getSummary() else "",
            )
        )
    }

    internal fun applySettingsEffects(effects: BookshelfSettingsEffects) {
        if (effects.updateWaitCount) activityViewModel.postUpBooksLiveData(true)
        if (effects.updateSort) upSort()
        effects.changedLayout?.let { layout ->
            if (layout < 2) activityViewModel.booksGridRecycledViewPool.clear()
            else activityViewModel.booksListRecycledViewPool.clear()
        }
        effects.dispatchEvents()
    }
}
