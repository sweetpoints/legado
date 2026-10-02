package io.legado.app.ui.about

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.FileCrashLogsRepository
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.setLayout

class CrashLogsDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<CrashLogsViewModel> {
        viewModelFactory {
            initializer { CrashLogsViewModel(FileCrashLogsRepository(requireContext())) }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f
        CrashLogsRoute(
            viewModel = viewModel,
            onShowLog = { log ->
                if (isAdded && !childFragmentManager.isStateSaved) {
                    val tag = TextDialog::class.simpleName
                    try {
                        if (childFragmentManager.findFragmentByTag(tag) == null) {
                            TextDialog(log.entry.name, log.text).showNow(childFragmentManager, tag)
                        }
                        true
                    } catch (_: IllegalStateException) {
                        // Keep the pending log for the next resumed lifecycle if transactions are busy.
                        false
                    }
                } else false
            },
            onClose = ::dismiss,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
        )
    }
}
