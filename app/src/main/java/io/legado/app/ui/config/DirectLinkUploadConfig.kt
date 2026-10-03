package io.legado.app.ui.config

import android.content.DialogInterface
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppDirectLinkConfigRepository
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout

class DirectLinkUploadConfig : BaseComposeDialogFragment() {
    internal val model by
        viewModels<DirectLinkConfigViewModel> {
            viewModelFactory {
                initializer {
                    DirectLinkConfigViewModel(
                        AppDirectLinkConfigRepository(requireContext()),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        DirectLinkConfigRoute(
            model,
            { isAdded && !parentFragmentManager.isStateSaved },
            ::dismiss,
            { isCancelable = it },
            { requireContext().getClipText()?.toString() },
            { requireContext().sendToClip(it) },
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (activity?.isChangingConfigurations != true) model.close()
        super.onDismiss(dialog)
    }
}
