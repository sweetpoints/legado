package io.legado.app.ui.about

import android.content.ClipData
import android.content.Intent
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppConst
import io.legado.app.data.repository.AppLogDetail
import io.legado.app.data.repository.AppLogExport
import io.legado.app.data.repository.DefaultAppLogsRepository
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.setLayout

class AppLogDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<AppLogsViewModel> {
        viewModelFactory {
            initializer {
                AppLogsViewModel(DefaultAppLogsRepository(requireContext().cacheDir), createSavedStateHandle())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        AppLogsRoute(
            viewModel = viewModel,
            onShowLog = ::showLog,
            onShare = ::shareLogs,
            onClose = ::dismiss,
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f),
        )
    }

    private fun showLog(log: AppLogDetail): Boolean {
        if (!isAdded || childFragmentManager.isStateSaved) return false
        val tag = TextDialog::class.simpleName
        return try {
            if (childFragmentManager.findFragmentByTag(tag) == null) {
                TextDialog(log.title, log.text).showNow(childFragmentManager, tag)
            }
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun shareLogs(export: AppLogExport): Boolean {
        if (!isAdded || parentFragmentManager.isStateSaved) return false
        try {
            val context = requireContext()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log))
                when (export) {
                    is AppLogExport.Text -> putExtra(Intent.EXTRA_TEXT, export.text)
                    is AppLogExport.Document -> {
                        val uri = FileProvider.getUriForFile(context, AppConst.authority, export.file)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(getString(R.string.log), uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
            startActivity(Intent.createChooser(intent, getString(R.string.log)))
        } catch (_: Exception) {
            viewModel.shareFailed(export)
        }
        return true
    }
}
