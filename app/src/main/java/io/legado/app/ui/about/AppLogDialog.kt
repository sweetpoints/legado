package io.legado.app.ui.about

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.core.content.FileProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.repository.AppLogDetail
import io.legado.app.data.repository.AppLogExport
import io.legado.app.data.repository.DefaultAppLogsRepository
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.openUrl
import kotlinx.coroutines.launch

class AppLogDialog : DiagnosticsDialog() {
    override val crash = false
    internal val model by viewModels<AppLogsViewModel> { viewModelFactory { initializer {
        AppLogsViewModel((requireActivity() as? DiagnosticsRepositoryOwner)?.appLogsRepository() ?: DefaultAppLogsRepository(requireContext().cacheDir), createSavedStateHandle())
    } } }
    override fun rows() = model.state.value.logs.map { DiagnosticsRow(it.id.toString(), it.time, it.message, it.hasDetails, links(it.message)) }
    private fun links(text: String): List<String> {
        val matcher = Patterns.WEB_URL.matcher(text.take(512))
        return buildList { while(matcher.find()) add(matcher.group().orEmpty()) }
    }
    override fun openLink(id: String, index: Int) {
        val row = model.state.value.logs.singleOrNull { it.id.toString() == id } ?: error("Retired log")
        val link = links(row.message).getOrNull(index) ?: error("Retired link")
        requireContext().openUrl(if(link.contains("://")) link else "https://$link")
    }
    override fun busy() = model.state.value.isBusy
    override fun failed() = model.state.value.error != null
    override fun notice() = model.state.value.notice?.name
    override fun confirmation() = model.state.value.showClearConfirmation
    override fun scroll() = model.scrollIndex to model.scrollOffset
    override fun rememberScroll(index: Int, offset: Double) = model.rememberScroll(index, offset)
    override fun act(op: String, id: String?) { when(op) {
        "open" -> model.openLog(requireNotNull(id).toLong())
        "clear" -> model.requestClear()
        "confirmClear" -> { model.confirmClear(); model.rememberScroll(0, 0.0) }
        "cancelClear" -> model.dismissClearConfirmation()
        "export" -> model.prepareExport()
        "retry" -> model.refresh()
        "back" -> closePage()
        else -> error("Unknown operation")
    } }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        super.onFragmentCreated(view, savedInstanceState)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.startObserving()
                try {
                    model.state.collect { state ->
                        state.openedLog?.let { if (showLog(it)) model.consumeOpenedLog(it) }
                        state.export?.let { if (shareLogs(it)) model.consumeExport(it) }
                    }
                } finally { model.stopObserving() }
            }
        }
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
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log))
                    when (export) {
                        is AppLogExport.Text -> putExtra(Intent.EXTRA_TEXT, export.text)
                        is AppLogExport.Document -> {
                            val uri =
                                FileProvider.getUriForFile(context, AppConst.authority, export.file)
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(getString(R.string.log), uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                }
            startActivity(Intent.createChooser(intent, getString(R.string.log)))
        } catch (_: Exception) {
            model.shareFailed(export)
        }
        return true
    }
}
