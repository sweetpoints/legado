package io.legado.app.ui.main.bookshelf.style1

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.RoomBookshelfHomeRepository
import io.legado.app.data.repository.RoomBookshelfPageRepository
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.books.BooksFragment
import io.legado.app.ui.main.bookshelf.style1.books.BookshelfPageParameters
import io.legado.app.ui.main.bookshelf.style1.books.BookshelfPageViewModel
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/** Each group owns a saved page model; HorizontalPager owns its Compose scroll state. */
class BookshelfFragment1() : BaseBookshelfFragment(0) {
    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    private val repository by lazy { RoomBookshelfHomeRepository(requireContext()) }
    private val homeModel by
        viewModels<BookshelfHomeViewModel> {
            viewModelFactory {
                initializer { BookshelfHomeViewModel(repository, createSavedStateHandle()) }
            }
        }
    private val pageModels = mutableMapOf<Long, BookshelfPageViewModel>()
    override val groupId: Long
        get() = homeModel.state.value.selectedGroup?.id ?: BookGroup.IdAll

    override val books: List<Book>
        get() = pageModels[groupId]?.getBooks().orEmpty()

    override var onlyUpdateRead: Boolean
        get() = homeModel.state.value.selectedGroup?.onlyRead ?: false
        set(value) {
            pageModels[groupId]?.setOnlyUpdateRead(value)
        }

    private fun pageModel(group: BookshelfHomeGroup, index: Int): BookshelfPageViewModel =
        pageModels.getOrPut(group.id) {
            ViewModelProvider(
                this,
                viewModelFactory {
                    initializer {
                        BookshelfPageViewModel(
                                RoomBookshelfPageRepository(requireContext()),
                                createSavedStateHandle(),
                            )
                            .also {
                                it.configure(
                                    BookshelfPageParameters(
                                        index,
                                        group.id,
                                        group.sort,
                                        group.refresh,
                                        group.onlyRead,
                                    )
                                )
                            }
                    }
                },
            )["bookshelf.page.${group.id}", BookshelfPageViewModel::class.java]
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) removeLegacyBookshelfPages(childFragmentManager)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    BookshelfHomeRoute(
                        homeModel,
                        { id ->
                            homeModel.state.value.groups
                                .find { it.id == id }
                                ?.let { group ->
                                    toastOnUi(
                                        "${group.name}(${pageModels[id]?.state?.value?.entries?.size ?: 0})"
                                    )
                                }
                        },
                        { id ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                repository.group(id)?.let {
                                    showDialogFragment(GroupEditDialog(it))
                                }
                            }
                        },
                        ::handleBookshelfMenu,
                        { openRecent(false) },
                        { openRecent(true) },
                    ) { group, index, active, modifier ->
                        val model = pageModel(group, index)
                        SideEffect {
                            model.configure(
                                BookshelfPageParameters(
                                    index,
                                    group.id,
                                    group.sort,
                                    group.refresh,
                                    group.onlyRead,
                                )
                            )
                        }
                        BookshelfGroupPageRoute(
                            model,
                            active,
                            {
                                if (model.state.value.canRefresh)
                                    activityViewModel.upToc(model.getBooks(), group.onlyRead)
                            },
                            { key -> model.getBook(key)?.let { startActivityForBook(it) } },
                            { key ->
                                model.getBook(key)?.let { book ->
                                    startActivity<BookInfoActivity> {
                                        putExtra("name", book.name)
                                        putExtra("author", book.author)
                                    }
                                }
                            },
                            { keys ->
                                model.replaceUpdating(
                                    keys.filter { activityViewModel.isUpdate(it) }.toSet()
                                )
                            },
                            modifier,
                        )
                    }
                }
            }
        }

    private fun openRecent(info: Boolean) {
        val key = homeModel.state.value.header.recent?.key ?: return
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

    override fun upGroup(data: List<BookGroup>) =
        Unit // Groups are observed by the home repository.

    override fun upSort() {
        homeModel.refresh()
    }

    override fun gotoTop() {
        pageModels[groupId]?.gotoTop()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) { key ->
            pageModels.values.forEach {
                it.setUpdating(key, activityViewModel.isUpdate(key))
                it.refreshTimeLabels()
            }
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            pageModels.values.forEach { model ->
                model.replaceUpdating(
                    model
                        .getBooks()
                        .filter { activityViewModel.isUpdate(it.bookUrl) }
                        .map { it.bookUrl }
                        .toSet()
                )
                model.refreshTimeLabels()
            }
        }
    }

    override fun onDestroyView() {
        homeModel.stop()
        pageModels.values.forEach { it.stop() }
        super.onDestroyView()
    }
}

/**
 * Old pager children must be removed before FragmentManager tries to find the retired container.
 */
internal fun removeLegacyBookshelfPages(manager: FragmentManager) {
    val pages = manager.fragments.filterIsInstance<BooksFragment>()
    if (pages.isEmpty()) return
    manager.beginTransaction().apply { pages.forEach(::remove) }.commitNow()
}
