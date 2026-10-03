package io.legado.app.ui.main.bookshelf.style2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.RoomBookshelfFolderRepository
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.launch

class BookshelfFragment2() : BaseBookshelfFragment(0) {
    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    private val repository by lazy { RoomBookshelfFolderRepository(requireContext()) }
    private val folderModel by
        viewModels<BookshelfFolderViewModel> {
            viewModelFactory {
                initializer { BookshelfFolderViewModel(repository, createSavedStateHandle()) }
            }
        }

    internal fun captureHostNavigation() = folderModel.captureHostNavigation()

    override var groupId: Long
        get() = folderModel.state.value.groupId
        set(value) {
            if (value == BookGroup.IdRoot) folderModel.back() else folderModel.openGroup(value)
        }

    override val books: List<Book>
        get() = folderModel.getBooks()

    override var onlyUpdateRead: Boolean
        get() = folderModel.state.value.onlyRead
        set(value) {
            folderModel.setOnlyRead(value)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        if (restoreWithoutPageView) return null
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    BookshelfFolderRoute(
                        folderModel,
                        { id ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                repository.group(id)?.let {
                                    showDialogFragment(GroupEditDialog(it))
                                }
                            }
                        },
                        { key -> folderModel.getBook(key)?.let { startActivityForBook(it) } },
                        { key ->
                            folderModel.getBook(key)?.let { book ->
                                startActivity<BookInfoActivity> {
                                    putExtra("name", book.name)
                                    putExtra("author", book.author)
                                }
                            }
                        },
                        ::handleBookshelfMenu,
                        {
                            if (folderModel.state.value.canRefresh)
                                activityViewModel.upToc(books, onlyUpdateRead)
                        },
                        { openRecent(false) },
                        { openRecent(true) },
                        { keys ->
                            folderModel.replaceUpdating(
                                keys.filter { activityViewModel.isUpdate(it) }.toSet()
                            )
                        },
                    )
                }
            }
        }
    }

    private fun openRecent(info: Boolean) {
        val key = folderModel.state.value.header.recent?.key ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            repository.book(key)?.let { book ->
                if (info)
                    startActivity<BookInfoActivity> {
                        putExtra("name", book.name)
                        putExtra("author", book.author)
                    }
                else startActivityForBook(book)
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    override fun upGroup(data: List<BookGroup>) = Unit

    override fun upSort() {
        folderModel.refresh()
    }

    override fun gotoTop() {
        folderModel.gotoTop()
    }

    fun back(): Boolean = folderModel.back()

    fun getItemCount(): Int = folderModel.state.value.itemCount

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) { key ->
            folderModel.setUpdating(key, activityViewModel.isUpdate(key))
            folderModel.refreshTimes()
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            folderModel.replaceUpdating(
                books.filter { activityViewModel.isUpdate(it.bookUrl) }.map { it.bookUrl }.toSet()
            )
            folderModel.refreshTimes()
        }
    }

    override fun onDestroyView() {
        folderModel.stop()
        super.onDestroyView()
    }
}

internal fun adjacentBookshelfGroupId(
    groups: List<BookGroup>,
    currentGroupId: Long,
    offset: Int,
): Long? {
    val index = groups.indexOfFirst { it.groupId == currentGroupId }
    return if (index < 0) null else groups.getOrNull(index + offset)?.groupId
}
