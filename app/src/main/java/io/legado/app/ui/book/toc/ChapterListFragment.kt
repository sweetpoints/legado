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
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.AppTocChapterRepository
import io.legado.app.data.repository.TocChapterNavigation
import io.legado.app.data.repository.TocChapterParameters
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent

/**
 * The existing TOC host owns the book/search contract; this tab owns parsing, cache and UI jobs.
 */
class ChapterListFragment : VMBaseFragment<TocViewModel>(0), TocViewModel.ChapterListCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    internal val model by
        viewModels<TocChapterViewModel> {
            viewModelFactory {
                initializer {
                    TocChapterViewModel(AppTocChapterRepository(), createSavedStateHandle())
                }
            }
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
                    TocChapterRoute(
                        model,
                        { isAdded && !parentFragmentManager.isStateSaved },
                        { delivery ->
                            delivery.title?.let { title ->
                                requireContext()
                                    .longToastOnUi(
                                        title.ifBlank {
                                            if (model.state.value.pdf)
                                                getString(R.string.pdf_outline_untitled)
                                            else ""
                                        }
                                    )
                            }
                            delivery.navigation?.let { navigation ->
                                activity?.run {
                                    setResult(Activity.RESULT_OK, chapterResultIntent(navigation))
                                    finish()
                                }
                            }
                        },
                    )
                }
            }
        }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.chapterListCallBack = this
        viewModel.bookData.observe(viewLifecycleOwner) { book ->
            model.bind(TocChapterParameters(book, viewModel.searchKey, AppConfig.tocCountWords))
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            model.contentSaved(book.bookUrl, chapter)
        }
        observeEvent<AudioCacheStateChanged>(EventBus.AUDIO_CACHE_CHANGED) { event ->
            model.audioChanged(event, AppConfig.audioCacheTreeUri)
        }
    }

    override fun upChapterList(searchKey: String?, resetCollapse: Boolean, replaceAll: Boolean) {
        val book = viewModel.bookData.value ?: return
        model.update(
            TocChapterParameters(book, searchKey, AppConfig.tocCountWords),
            resetCollapse,
            replaceAll,
        )
    }

    override fun clearDisplayTitle() = model.clearTitles()

    override fun upAdapter() = model.countWords(AppConfig.tocCountWords)

    override fun onDestroyView() {
        model.unbind()
        viewModel.chapterListCallBack = clearCallbackIfOwned(viewModel.chapterListCallBack, this)
        super.onDestroyView()
    }
}

internal fun chapterResultIntent(value: TocChapterNavigation): Intent =
    Intent().apply {
        putExtra("index", value.index)
        putExtra("chapterChanged", value.changed)
        value.pdfPage?.let { putExtra(TocActivityResult.EXTRA_PDF_PAGE_INDEX, it) }
        value.volumeIndex?.let { putExtra("durVolumeIndex", it) }
        value.chapterInVolume?.let { putExtra("chapterInVolumeIndex", it) }
    }
