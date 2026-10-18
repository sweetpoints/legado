package io.legado.app.ui.book.source.debug

import androidx.activity.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.lifecycleScope
import io.legado.app.ui.source.debug.SourceDebuggerActivity
import io.legado.app.ui.source.debug.SourceDebuggerNativeCoordinator
import io.legado.app.utils.openUrl
import io.legado.app.data.repository.AppBookSourceDebugRepository
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.launch
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp

class BookSourceDebugActivity : SourceDebuggerActivity() {
    val viewModel by
        viewModels<BookSourceDebugViewModel> {
            viewModelFactory {
                initializer {
                    BookSourceDebugViewModel(
                        AppBookSourceDebugRepository(applicationContext),
                        createSavedStateHandle().apply { remove<String>("key") },
                        intent.getStringExtra("key"),
                    )
                }
            }
        }
    private val qrCodeResult =
        registerForActivityResult(QrCodeResult()) { result -> result?.let(viewModel::run) }

    override val debuggerCoordinator by lazy {
        SourceDebuggerNativeCoordinator.book(
            viewModel, lifecycle, lifecycleScope, ::canHandleDebuggerEffect, ::completeDebugger,
            { showDialogFragment(TextDialog("html", it)) },
            { qrCodeResult.launch() },
            { showHelp("debugHelp") },
            { openUrl(it) },
        )
    }
}
