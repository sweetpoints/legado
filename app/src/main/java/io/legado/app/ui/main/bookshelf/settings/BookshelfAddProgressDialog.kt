package io.legado.app.ui.main.bookshelf.settings

import android.content.DialogInterface
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.ui.main.bookshelf.BookshelfViewModel
import io.legado.app.utils.setLayout

class BookshelfAddProgressDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<BookshelfViewModel>({ requireParentFragment() })
    override fun onStart() { super.onStart(); setLayout(.8f, ViewGroup.LayoutParams.WRAP_CONTENT) }
    @Composable override fun Content() {
        val progress by viewModel.transfer.addProgress.collectAsStateWithLifecycle()
        LaunchedEffect(progress) { if (progress < 0) dismissAllowingStateLoss() }
        BookshelfAddProgressScreen(progress) { viewModel.cancelAdd(); dismiss() }
    }
    override fun onCancel(dialog: DialogInterface) { viewModel.cancelAdd(); super.onCancel(dialog) }
}
