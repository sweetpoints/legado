package io.legado.app.ui.login

import io.legado.app.utils.resizeForIme

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class SourceLoginDialog : BaseComposeDialogFragment(), SourceLoginJsExtensions.Callback {
    private val sourceModel by activityViewModels<SourceLoginViewModel>()
    private val viewModel by
        viewModels<SourceLoginFormViewModel> {
            viewModelFactory {
                initializer {
                    SourceLoginFormViewModel(
                        PendingSourceLoginFormRepository(
                            sourceModel,
                            Intent(requireActivity().intent),
                        ),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    // Both the activity and callback are weak references in the existing JS bridge.
    private val javascript
        get() =
            SourceLoginJsExtensions(
                activity as? AppCompatActivity,
                sourceModel.source,
                sourceModel.bookType,
                viewModel,
            )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            resizeForIme()
        }
    }

    @Composable
    override fun Content() {
        SourceLoginFormRoute(
            viewModel,
            { isAdded && !childFragmentManager.isStateSaved },
            ::handle,
            { dismissAllowingStateLoss() },
            Modifier.fillMaxSize(),
        )
    }

    private fun handle(event: SourceLoginFormEffect) {
        when (event.action) {
            SourceLoginFormAction.Legacy -> viewModel.runLegacy(event, javascript)
            SourceLoginFormAction.Login -> viewModel.login(event, javascript)
            SourceLoginFormAction.OpenUrl -> requireContext().openUrl(event.text)
            SourceLoginFormAction.Copy -> requireContext().sendToClip(event.text)
            SourceLoginFormAction.Log -> showDialogFragment(AppLogDialog())
            SourceLoginFormAction.Toast ->
                if (event.resource != 0) toastOnUi(event.resource) else toastOnUi(event.text)
        }
    }

    override fun upUiData(data: Map<String, Any?>?) {
        viewModel.upUiData(data)
    }

    override fun reUiView(deltaUp: Boolean) {
        viewModel.reUiView(deltaUp)
    }

    override fun dismiss() {
        viewModel.close()
    }

    override fun onDismiss(dialog: DialogInterface) {
        val host = activity
        super.onDismiss(dialog)
        // View destruction during recreation must neither persist draft input nor finish the host.
        if (host?.isChangingConfigurations == false && viewModel.state.value.finished) host.finish()
    }
}
