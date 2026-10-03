package io.legado.app.ui.book.audio.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.model.AudioPlay
import io.legado.app.utils.setLayout
import java.lang.ref.WeakReference

class AudioSkipCredits : BaseComposeDialogFragment() {
    private var liveBook: WeakReference<Book>? = null
    companion object {
        fun newInstance(book: Book) = AudioSkipCredits().apply {
            arguments = Bundle().apply { putString("bookUrl", book.bookUrl) }
            liveBook = WeakReference(book)
        }
    }
    private val model by viewModels<AudioSkipCreditsViewModel> {
        viewModelFactory { initializer {
            val id = arguments?.getString("bookUrl").orEmpty()
            val book = liveBook?.get()?.takeIf { it.bookUrl == id } ?: AudioPlay.book?.takeIf { it.bookUrl == id }
            AudioSkipCreditsViewModel(DefaultAudioSkipCreditsRepository(AppAudioSkipCreditsStore(id, book)), createSavedStateHandle())
        } }
    }
    override fun onStart() { super.onStart(); dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
    @Composable override fun Content() {
        AudioSkipCreditsRoute(model, { isAdded && !parentFragmentManager.isStateSaved }, { draft ->
            val id = arguments?.getString("bookUrl")
            val book = liveBook?.get()?.takeIf { it.bookUrl == id } ?: AudioPlay.book?.takeIf { it.bookUrl == id }
            book?.config?.apply { useGlobalAudioSkip = draft.useGlobal; openCredits = draft.bookOpen; closeCredits = draft.bookClose }
        }, ::dismissAllowingStateLoss)
    }
    override fun onDismiss(dialog: DialogInterface) {
        if (activity?.isChangingConfigurations != true && !model.state.value.finished) model.requestClose()
        super.onDismiss(dialog)
    }
}
