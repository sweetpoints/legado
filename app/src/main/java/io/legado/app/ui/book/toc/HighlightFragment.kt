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
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.repository.RoomTocHighlightsRepository
import io.legado.app.data.repository.TocHighlightsParameters
import io.legado.app.data.repository.TocHighlightTarget
import io.legado.app.model.book.tocHighlightAnchorText
import io.legado.app.model.book.tocHighlightChapterIndex
import io.legado.app.model.book.tocHighlightBodyPosition
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isImage
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.HighlightNoteDialog
import io.legado.app.ui.theme.LegadoComposeTheme

/** Search and book ownership stay in the existing TOC host; only this tab owns its Room collector. */
class HighlightFragment : VMBaseFragment<TocViewModel>(0), TocViewModel.HighlightCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    internal val model by viewModels<TocHighlightsViewModel> {
        viewModelFactory { initializer { TocHighlightsViewModel(RoomTocHighlightsRepository(), createSavedStateHandle()) } }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { LegadoComposeTheme {
                TocHighlightsRoute(model, { isAdded && !parentFragmentManager.isStateSaved }, { row, edit ->
                    if (edit) onLongClick(row.highlight) else navigate(row)
                })
            } }
        }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.highlightCallBack = this
        viewModel.bookData.observe(viewLifecycleOwner) { upHighlight(viewModel.searchKey) }
    }
    override fun upHighlight(searchKey: String?) {
        val book = viewModel.bookData.value ?: return
        model.bind(TocHighlightsParameters(book.bookUrl, searchKey, book.durChapterIndex, supportsHighlightPosition(book)))
    }
    override fun onDestroyView() {
        model.unbind(); viewModel.highlightCallBack = clearCallbackIfOwned(viewModel.highlightCallBack, this)
        super.onDestroyView()
    }
    private fun navigate(target: TocHighlightTarget) {
        val book = viewModel.bookData.value ?: return
        if (!supportsHighlightPosition(book) || target.highlight.bookUrl != book.bookUrl) return
        val result = highlightResultIntent(target) ?: return
        activity?.run {
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }
    fun onLongClick(highlight: BookHighlight) {
        HighlightNoteDialog(highlight).show(parentFragmentManager, "toc-highlight-editor")
    }
    private fun supportsHighlightPosition(book: Book): Boolean = !book.isAudio && !book.isVideo &&
        (book.isLocal || !book.isImage || !AppConfig.showMangaUi)
}

// Existing callers/tests retain these pure contracts; data-layer projection uses the model helpers.
internal fun resolveHighlightChapterIndex(highlight: BookHighlight, chapterIndexes: Map<String, Int>): Int? =
    tocHighlightChapterIndex(highlight, chapterIndexes)
internal fun highlightBodyPosition(highlight: BookHighlight): Int = tocHighlightBodyPosition(highlight)

internal fun highlightResultIntent(target: TocHighlightTarget): Intent? {
    val index = target.chapterIndex ?: return null
    val highlight = target.highlight
    return Intent().apply {
        putExtra("index", index)
        putExtra("chapterPos", highlight.chapterPos)
        putExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, highlight.layoutTitleLength)
        putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, tocHighlightAnchorText(highlight))
    }
}
