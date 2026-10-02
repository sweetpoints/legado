package io.legado.app.ui.main.bookshelf.style1.books

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.RoomBookshelfPageRepository
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook

/** Fragment API retained for the group pager; all book content and scrolling are Compose. */
class BooksFragment() : BaseFragment(0) {
    constructor(position: Int, group: BookGroup) : this() {
        arguments = Bundle().apply {
            putInt("position", position); putLong("groupId", group.groupId); putInt("bookSort", group.getRealBookSort())
            putBoolean("enableRefresh", group.enableRefresh); putBoolean("onlyUpdateRead", group.onlyUpdateRead)
        }
    }
    private val activityViewModel by activityViewModels<MainViewModel>()
    private val viewModel by viewModels<BookshelfPageViewModel> {
        viewModelFactory { initializer { BookshelfPageViewModel(RoomBookshelfPageRepository(requireContext()), createSavedStateHandle()) } }
    }
    val position: Int get() = viewModel.state.value.parameters.position
    val groupId: Long get() = viewModel.state.value.parameters.groupId
    val bookSort: Int get() = viewModel.state.value.parameters.sort
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { LegadoComposeTheme {
                BookshelfPageRoute(viewModel, {
                    if (viewModel.state.value.canRefresh) activityViewModel.upToc(getBooks(), viewModel.state.value.parameters.onlyUpdateRead)
                }, { key -> viewModel.getBook(key)?.let { startActivityForBook(it) } }, { key ->
                    viewModel.getBook(key)?.let { book -> startActivity<BookInfoActivity> { putExtra("name", book.name); putExtra("author", book.author) } }
                }, { keys -> viewModel.replaceUpdating(keys.filter { activityViewModel.isUpdate(it) }.toSet()) })
            } }
        }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit
    fun upBookSort(sort: Int) { arguments?.putInt("bookSort", sort); viewModel.upSort(sort) }
    fun setEnableRefresh(enable: Boolean) { arguments?.putBoolean("enableRefresh", enable); viewModel.setEnableRefresh(enable) }
    fun setOnlyUpdateRead(enable: Boolean) { arguments?.putBoolean("onlyUpdateRead", enable); viewModel.setOnlyUpdateRead(enable) }
    fun getBooks(): List<Book> = viewModel.getBooks()
    fun getBooksCount(): Int = viewModel.state.value.entries.size
    fun gotoTop() = viewModel.gotoTop()
    override fun observeLiveBus() {
        observeEvent<String>(EventBus.UP_BOOKSHELF) { key -> viewModel.setUpdating(key, activityViewModel.isUpdate(key)); viewModel.refreshTimeLabels() }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) { viewModel.replaceUpdating(getBooks().filter { activityViewModel.isUpdate(it.bookUrl) }.map { it.bookUrl }.toSet()); viewModel.refreshTimeLabels() }
    }
    override fun onDestroyView() { viewModel.stop(); super.onDestroyView() }
}
