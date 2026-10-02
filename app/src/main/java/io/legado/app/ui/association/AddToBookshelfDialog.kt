package io.legado.app.ui.association

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AppAddBookLinkStore
import io.legado.app.data.repository.DefaultAddBookLinkRepository
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

/** URL options, enabled base URL, then ordered enabled detail-pattern sources. */
class AddToBookshelfDialog() : BaseComposeDialogFragment() {
    constructor(bookUrl: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply { putString("bookUrl", bookUrl); putBoolean("finishOnDismiss", finishOnDismiss) }
    }
    private val viewModel by viewModels<AddBookLinkViewModel> {
        viewModelFactory { initializer { AddBookLinkViewModel(DefaultAddBookLinkRepository(AppAddBookLinkStore(requireContext())),
            createSavedStateHandle(), arguments?.getString("bookUrl").orEmpty()) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); isCancelable = false }
    override fun onStart() { super.onStart(); setLayout(.9f, ViewGroup.LayoutParams.WRAP_CONTENT) }
    @Composable override fun Content() {
        AddBookLinkRoute(viewModel, { isAdded && !parentFragmentManager.isStateSaved }, { target ->
            startActivity<BookInfoActivity> { putExtra("name", target.name); putExtra("author", target.author); putExtra("bookUrl", target.bookUrl) }
        }, { message -> AppLog.put("添加书籍出错: $message"); toastOnUi(message) }, { dismissAllowingStateLoss() })
    }
    override fun dismiss() { viewModel.cancel() }
}
