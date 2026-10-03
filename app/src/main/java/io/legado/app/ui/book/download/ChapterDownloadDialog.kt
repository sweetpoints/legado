package io.legado.app.ui.book.download

import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.FileChapterDownloadSessionRepository
import io.legado.app.model.CacheBook
import io.legado.app.model.download.ChapterDownloadMode
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ChapterDownloadDialog : BaseComposeDialogFragment() {
    interface AudioHost {
        fun downloadAudioRange(bookUrl: String, start: Int, endInclusive: Int)
    }

    private val model by
        viewModels<ChapterDownloadViewModel> {
            viewModelFactory {
                initializer {
                    ChapterDownloadViewModel(
                        createSavedStateHandle(),
                        FileChapterDownloadSessionRepository(requireContext()),
                        requireArguments().getString(TICKET)!!,
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        val state by model.state.collectAsStateWithLifecycle()
        SideEffect { isCancelable = !state.busy }
        ChapterDownloadRoute(
            model,
            { delivery ->
                when (delivery.mode) {
                    ChapterDownloadMode.Book ->
                        CacheBook.start(
                            requireContext(),
                            delivery.book,
                            delivery.range.start,
                            delivery.range.endInclusive,
                        )
                    ChapterDownloadMode.Audio ->
                        checkNotNull(activity as? AudioHost) { "Audio download host missing" }
                            .downloadAudioRange(
                                delivery.book.bookUrl,
                                delivery.range.start,
                                delivery.range.endInclusive,
                            )
                }
            },
            { dismiss() },
            { requireContext().toastOnUi(it.localizedMessage ?: "Error") },
            { isAdded && activity?.isFinishing != true && !parentFragmentManager.isStateSaved },
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (activity?.isChangingConfigurations != true) model.close()
        super.onDismiss(dialog)
    }

    companion object {
        private const val TICKET = "chapter-download-ticket"

        fun newInstance(ticket: String) =
            ChapterDownloadDialog().apply {
                arguments = Bundle().apply { putString(TICKET, ticket) }
            }
    }
}

fun Context.showChapterDownloadDialog(
    book: Book,
    mode: ChapterDownloadMode = ChapterDownloadMode.Book,
    initialChapter: Int = book.durChapterIndex + 1,
    chapterCount: Int = book.totalChapterNum,
) {
    var context: Context = this
    while (context is ContextWrapper && context !is FragmentActivity) context = context.baseContext
    val host = context as? FragmentActivity ?: return
    val sessions = FileChapterDownloadSessionRepository(host.applicationContext)
    val snapshot = book.copy()
    host.lifecycleScope.launch {
        var ticket: String? = null
        var shown = false
        try {
            ticket = sessions.create(snapshot, mode, initialChapter, chapterCount)
            host.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            currentCoroutineContext().ensureActive()
            if (
                !host.isFinishing &&
                    !host.supportFragmentManager.isStateSaved &&
                    host.supportFragmentManager.findFragmentByTag("chapter-download") == null
            ) {
                ChapterDownloadDialog.newInstance(ticket)
                    .showNow(host.supportFragmentManager, "chapter-download")
                shown = true
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            host.toastOnUi(error.localizedMessage ?: "Error")
        } finally {
            if (!shown) ticket?.let { withContext(NonCancellable) { sessions.release(it) } }
        }
    }
}
