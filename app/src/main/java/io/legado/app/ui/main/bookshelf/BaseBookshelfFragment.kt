package io.legado.app.ui.main.bookshelf

import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.preferences.BookshelfSettingsEffects
import io.legado.app.data.preferences.dispatchEvents
import io.legado.app.databinding.ViewBookshelfHeaderBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.import.remote.RemoteBookActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.bookshelf.settings.BookshelfAddProgressDialog
import io.legado.app.ui.main.bookshelf.settings.BookshelfInputDialog
import io.legado.app.ui.main.bookshelf.settings.BookshelfInputResult
import io.legado.app.ui.main.bookshelf.settings.BookshelfSettingsDialog
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.gone
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseBookshelfFragment(layoutId: Int) : VMBaseFragment<BookshelfViewModel>(layoutId),
    MainFragmentInterface {

    override val position: Int? get() = arguments?.getInt("position")

    val activityViewModel by activityViewModels<MainViewModel>()
    override val viewModel by viewModels<BookshelfViewModel>()

    private val importBookshelf = registerForActivityResult(HandleFileContract()) { result ->
        val targetGroup = viewModel.transfer.importReturned() ?: return@registerForActivityResult
        val uri = result.uri ?: return@registerForActivityResult
        viewModel.importBookshelfFile(uri.toString(), targetGroup)
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri -> showDialogFragment(BookshelfInputDialog.create(2, value = uri.toString(),
            summary = if (uri.toString().isAbsUrl()) DirectLinkUpload.getSummary() else "")) }
    }
    abstract val groupId: Long
    abstract val books: List<Book>
    abstract var onlyUpdateRead: Boolean
    private var groupsLiveData: LiveData<List<BookGroup>>? = null
    private var shelfHeaderBinding: ViewBookshelfHeaderBinding? = null
    private var continueBook: Book? = null
    private var shelfHeaderFlowJob: Job? = null

    override fun onDestroyView() {
        shelfHeaderFlowJob?.cancel()
        shelfHeaderFlowJob = null
        shelfHeaderBinding = null
        continueBook = null
        super.onDestroyView()
    }

    fun bindShelfHeader(header: ViewBookshelfHeaderBinding) {
        shelfHeaderBinding = header
        header.continueReading.setOnClickListener {
            continueBook?.let { startActivityForBook(it) }
        }
        header.continueReading.setOnLongClickListener {
            continueBook?.let { book ->
                startActivity(
                    Intent(requireContext(), BookInfoActivity::class.java).apply {
                        putExtra("name", book.name)
                        putExtra("author", book.author)
                    }
                )
                true
            } ?: false
        }
        subscribeShelfHeaderRefresh()
    }

    private fun subscribeShelfHeaderRefresh() {
        shelfHeaderFlowJob?.cancel()
        shelfHeaderFlowJob = null
        continueBook = null
        val header = shelfHeaderBinding ?: return
        val showRecentReading = AppConfig.showBookshelfRecentReading
        val showBookshelfStats = AppConfig.showBookshelfStats
        header.tvShelfStats.visibility = if (showBookshelfStats) View.VISIBLE else View.GONE
        header.continueReading.gone()
        header.root.visibility = if (showBookshelfStats) View.VISIBLE else View.GONE
        if (!showRecentReading && !showBookshelfStats) return

        shelfHeaderFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowShelfBookCount()
                .flowWithLifecycleAndDatabaseChangeFirst(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_TABLE_NAME
                )
                .catch { AppLog.put("书架头部刷新出错", it) }
                .conflate()
                .flowOn(Dispatchers.Default)
                .collect { bookCount ->
                    val currentHeader = shelfHeaderBinding ?: return@collect
                    val (book, readingCount) = withContext(Dispatchers.IO) {
                        val book = if (showRecentReading) {
                            appDb.bookDao.lastReadBookOnShelf
                        } else {
                            null
                        }
                        val readingCount = if (showBookshelfStats) {
                            appDb.bookDao.readingCount
                        } else {
                            0
                        }
                        book to readingCount
                    }
                    if (shelfHeaderBinding !== currentHeader) return@collect
                    continueBook = book
                    if (showBookshelfStats) {
                        currentHeader.tvShelfStats.text =
                            getString(R.string.bookshelf_stats, bookCount, readingCount)
                    }
                    currentHeader.root.visibility =
                        if (showBookshelfStats || book != null) View.VISIBLE else View.GONE
                    if (book == null) {
                        currentHeader.continueReading.gone()
                        return@collect
                    }
                    currentHeader.continueReading.visibility = View.VISIBLE
                    currentHeader.tvContinueName.text = book.name
                    currentHeader.tvContinueChapter.text = book.durChapterTitle
                        .takeIf { it?.isNotBlank() == true }
                        ?: getString(R.string.read_not_started)
                    currentHeader.tvContinuePercent.text =
                        "${((book.readProgress() ?: 0f) * 100).roundToInt().coerceIn(0, 100)}%"
                }
        }
    }

    abstract fun gotoTop()

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_bookshelf, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        handleBookshelfMenu(item.itemId)
    }

    protected fun handleBookshelfMenu(itemId: Int) {
        when (itemId) {
            R.id.menu_remote -> startActivity<RemoteBookActivity>()
            R.id.menu_search -> startActivity<SearchActivity>()
            R.id.menu_update_toc -> activityViewModel.upToc(books, onlyUpdateRead)
            R.id.menu_bookshelf_layout -> configBookshelf()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_add_local -> startActivity<ImportBookActivity>()
            R.id.menu_add_url -> showAddBookByUrlAlert()
            R.id.menu_bookshelf_manage -> startActivity<BookshelfManageActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_download -> startActivity<CacheActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_export_bookshelf -> viewModel.exportBookshelf(books)

            R.id.menu_import_bookshelf -> importBookshelfAlert(groupId)
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
    }

    protected fun initBookGroupData() {
        groupsLiveData?.removeObservers(viewLifecycleOwner)
        groupsLiveData = appDb.bookGroupDao.show.apply {
            observe(viewLifecycleOwner) {
                upGroup(it)
            }
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
                        // Resume after the host FragmentManager has completed its lifecycle transaction.
                        kotlinx.coroutines.yield()
                        if (viewModel.transfer.addProgress.value >= 0 && !childFragmentManager.isStateSaved &&
                            childFragmentManager.findFragmentByTag("BookshelfAddProgressDialog") == null) {
                            BookshelfAddProgressDialog().showNow(childFragmentManager, "BookshelfAddProgressDialog")
                        }
                    }
                }
                launch {
                    viewModel.transfer.pendingExport.collect { path ->
                        if (path == null) return@collect
                        val file = java.io.File(path)
                        if (file.exists()) {
                            exportResult.launch {
                                mode = HandleFileContract.EXPORT
                                fileData = HandleFileContract.FileData("bookshelf.json", file, "application/json")
                            }
                        } else toastOnUi(getString(R.string.error))
                        viewModel.transfer.exportLaunched(path)
                    }
                }
            }
        }
    }

    fun showAddBookByUrlAlert() { showDialogFragment(BookshelfInputDialog.create(0, groupId)) }
    fun configBookshelf() { showDialogFragment<BookshelfSettingsDialog>() }
    private fun importBookshelfAlert(groupId: Long) { showDialogFragment(BookshelfInputDialog.create(1, groupId)) }
    internal fun submitShelfInput(kind: Int, result: BookshelfInputResult) {
        when (kind) {
            0 -> viewModel.addBookByUrl(result.text, result.groupId)
            1 -> viewModel.importBookshelf(result.text, result.groupId)
        }
    }
    internal fun selectBookshelfImportFile(groupId: Long) {
        viewModel.transfer.importRequested(groupId)
        importBookshelf.launch { mode = HandleFileContract.FILE; allowExtensions = arrayOf("txt", "json") }
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
