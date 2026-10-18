package io.legado.app.ui.rss.source.debug

import androidx.activity.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.lifecycleScope
import io.legado.app.ui.source.debug.SourceDebuggerActivity
import io.legado.app.ui.source.debug.SourceDebuggerNativeCoordinator
import io.legado.app.utils.openUrl
import io.legado.app.data.repository.AppRssSourceDebugRepository
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.showDialogFragment

class RssSourceDebugActivity : SourceDebuggerActivity() {
    val viewModel by
        viewModels<RssSourceDebugViewModel> {
            viewModelFactory {
                initializer {
                    RssSourceDebugViewModel(
                        AppRssSourceDebugRepository(applicationContext),
                        createSavedStateHandle(),
                        intent.getStringExtra("key"),
                    )
                }
            }
        }

    override val debuggerCoordinator by lazy {
        SourceDebuggerNativeCoordinator.rss(
            viewModel, lifecycle, lifecycleScope, ::canHandleDebuggerEffect, ::completeDebugger,
            { showDialogFragment(TextDialog("Html", it)) },
            { openUrl(it) },
        )
    }
}
