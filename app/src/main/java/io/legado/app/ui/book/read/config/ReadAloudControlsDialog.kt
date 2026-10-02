package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.preferences.PreferenceReadAloudControlsSettingsRepository
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.setLayout

class ReadAloudControlsDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<ReadAloudControlsSettingsViewModel> {
        viewModelFactory { initializer { ReadAloudControlsSettingsViewModel(PreferenceReadAloudControlsSettingsRepository(requireContext()), createSavedStateHandle()) } }
    }
    private var countedActivity: ReadBookActivity? = null
    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(.9f, .8f)
    }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        releaseCounter()
        (activity as? ReadBookActivity)?.let { host -> host.bottomDialog++; countedActivity = host }
    }
    @Composable override fun Content() {
        ReadAloudControlsSettingsRoute(viewModel, Color(requireContext().backgroundColor), ::handleAction)
    }
    private fun handleAction(action: ReadAloudControlsAction): Boolean {
        if (!isAdded || parentFragmentManager.isStateSaved) return false
        (activity as? ReadBookActivity)?.showReadAloudControls(resetPosition = action == ReadAloudControlsAction.ResetPosition)
        return true
    }
    override fun onDestroyView() { viewModel.flush(); releaseCounter(); super.onDestroyView() }
    override fun onDismiss(dialog: DialogInterface) { viewModel.flush(); releaseCounter(); super.onDismiss(dialog) }
    private fun releaseCounter() {
        val owner = countedActivity ?: return
        countedActivity = null
        owner.bottomDialog = (owner.bottomDialog - 1).coerceAtLeast(0)
    }
}
