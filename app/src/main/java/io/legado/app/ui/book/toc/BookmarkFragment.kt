package io.legado.app.ui.book.toc

import android.app.Activity
import android.content.Intent
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
import io.legado.app.base.VMBaseFragment
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.RoomTocBookmarksRepository
import io.legado.app.data.repository.TocBookmarksParameters
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.theme.LegadoComposeTheme

/** Search and book ownership stay in the existing TOC host; only this tab owns its Room collector. */
class BookmarkFragment : VMBaseFragment<TocViewModel>(0), TocViewModel.BookmarkCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    internal val model by viewModels<TocBookmarksViewModel> {
        viewModelFactory { initializer { TocBookmarksViewModel(RoomTocBookmarksRepository(), createSavedStateHandle()) } }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { LegadoComposeTheme {
                TocBookmarksRoute(model, { isAdded && !parentFragmentManager.isStateSaved }, { row, edit, position ->
                    if (edit) onLongClick(row, position) else onClick(row)
                })
            } }
        }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.bookMarkCallBack = this
        viewModel.bookData.observe(viewLifecycleOwner) { upBookmark(viewModel.searchKey) }
    }
    override fun upBookmark(searchKey: String?) {
        val book = viewModel.bookData.value ?: return
        model.bind(TocBookmarksParameters(book.name, book.author, searchKey, book.durChapterIndex))
    }
    override fun onDestroyView() {
        model.unbind(); viewModel.bookMarkCallBack = clearCallbackIfOwned(viewModel.bookMarkCallBack, this)
        super.onDestroyView()
    }
    fun onClick(bookmark: Bookmark) {
        activity?.run {
            setResult(Activity.RESULT_OK, Intent().putExtra("index", bookmark.chapterIndex).putExtra("chapterPos", bookmark.chapterPos))
            finish()
        }
    }
    fun onLongClick(bookmark: Bookmark, pos: Int) {
        BookmarkDialog(bookmark, pos).show(parentFragmentManager, "toc-bookmark-editor")
    }
}
