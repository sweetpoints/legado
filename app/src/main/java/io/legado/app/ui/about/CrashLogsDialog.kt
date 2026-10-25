package io.legado.app.ui.about

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.data.repository.FileCrashLogsRepository
import io.legado.app.ui.widget.dialog.TextDialog
import kotlinx.coroutines.launch

class CrashLogsDialog : DiagnosticsDialog() {
    override val crash = true
    internal val model by viewModels<CrashLogsViewModel> { viewModelFactory { initializer {
        CrashLogsViewModel((requireActivity() as? DiagnosticsRepositoryOwner)?.crashLogsRepository() ?: FileCrashLogsRepository(requireContext()), createSavedStateHandle())
    } } }
    override fun rows() = model.state.value.logs.map { DiagnosticsRow(it.id, "", it.name, true) }
    override fun busy() = model.state.value.isBusy
    override fun failed() = model.state.value.error != null
    override fun notice(): String? = null
    override fun confirmation() = false
    override fun scroll() = model.scrollIndex to model.scrollOffset
    override fun rememberScroll(index: Int, offset: Double) = model.rememberScroll(index, offset)
    override fun act(op: String, id: String?) { when(op) {
        "open" -> model.openLog(requireNotNull(id))
        "clear" -> { model.clearLogs(); model.rememberScroll(0, 0.0) }
        "retry" -> model.refresh()
        "back" -> closePage()
        else -> error("Unknown operation")
    } }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        super.onFragmentCreated(view, savedInstanceState)
        lifecycleScope.launch { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { state -> state.openedLog?.let { log ->
                if (isAdded && !childFragmentManager.isStateSaved) {
                    val tag = TextDialog::class.simpleName
                    try {
                        if (childFragmentManager.findFragmentByTag(tag) == null)
                            TextDialog(log.entry.name, log.text).showNow(childFragmentManager, tag)
                        model.consumeOpenedLog(log)
                    } catch (_: IllegalStateException) { /* Retain native detail until delivery is possible. */ }
                }
            } }
        } }
    }
}
