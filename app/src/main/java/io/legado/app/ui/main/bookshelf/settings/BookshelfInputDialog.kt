package io.legado.app.ui.main.bookshelf.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout

class BookshelfInputDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<BookshelfInputViewModel> {
        viewModelFactory { initializer { BookshelfInputViewModel(createSavedStateHandle()) } }
    }
    override fun onStart() { super.onStart(); setLayout(.9f, ViewGroup.LayoutParams.WRAP_CONTENT) }
    @Composable override fun Content() {
        BookshelfInputRoute(viewModel, { kind, result ->
            if (kind == 2) requireContext().sendToClip(result.text)
            else (parentFragment as? BaseBookshelfFragment)?.submitShelfInput(kind, result)
        }, { group -> (parentFragment as? BaseBookshelfFragment)?.selectBookshelfImportFile(group) }, ::dismiss)
    }
    companion object {
        fun create(kind: Int, groupId: Long = -1L, value: String = "", summary: String = "") = BookshelfInputDialog().apply {
            arguments = Bundle().apply { putInt("kind", kind); putLong("groupId", groupId); putString("value", value); putString("summary", summary) }
        }
    }
}
