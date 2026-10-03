package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.repository.ReaderHighlightNoteRepository
import io.legado.app.ui.book.read.highlightnote.HighlightNoteRoute
import io.legado.app.ui.book.read.highlightnote.HighlightNoteViewModel
import io.legado.app.utils.setLayout

class HighlightNoteDialog() : BaseComposeDialogFragment() {
    constructor(highlight: BookHighlight) : this() {
        arguments = Bundle().apply { putParcelable("highlight", highlight.copy()) }
    }

    private val viewModel by
        viewModels<HighlightNoteViewModel> {
            viewModelFactory {
                initializer {
                    HighlightNoteViewModel(
                        ReaderHighlightNoteRepository(),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Composable
    override fun Content() {
        HighlightNoteRoute(viewModel, ::dismissAllowingStateLoss)
    }
}
