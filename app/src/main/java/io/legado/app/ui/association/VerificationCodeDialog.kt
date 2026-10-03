package io.legado.app.ui.association

import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.setLayout

/** Request-scoped image verification, with lifecycle-safe cancellation and completion. */
class VerificationCodeDialog() : BaseComposeDialogFragment() {
    constructor(
        imageUrl: String,
        sourceOrigin: String? = null,
        sourceName: String? = null,
        sourceType: Int,
        verificationResultKey: String? = null,
    ) : this() {
        arguments =
            Bundle().apply {
                putString("imageUrl", imageUrl)
                putString("sourceOrigin", sourceOrigin)
                putString("sourceName", sourceName)
                putInt("sourceType", sourceType)
                putString("verificationResultKey", verificationResultKey)
            }
    }

    private val viewModel by
        viewModels<VerificationCodeViewModel> {
            viewModelFactory { initializer { VerificationCodeViewModel(createSavedStateHandle()) } }
        }
    private val verificationResultKey: String?
        get() = arguments?.getString("verificationResultKey")

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        VerificationCodeRoute(
            viewModel = viewModel,
            onClose = { dismiss() },
            onShowImage = { previewSrc ->
                if (!isAdded || childFragmentManager.isStateSaved) false
                else {
                    val tag = PhotoDialog::class.simpleName
                    try {
                        if (childFragmentManager.findFragmentByTag(tag) == null) {
                            PhotoDialog(previewSrc, viewModel.sourceOrigin)
                                .showNow(childFragmentManager, tag)
                        }
                        true
                    } catch (_: IllegalStateException) {
                        false
                    }
                }
            },
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        val host = activity
        super.onDismiss(dialog)
        if (host != null && !host.isChangingConfigurations) {
            SourceVerificationHelp.checkResult(verificationResultKey)
            host.finish()
        }
    }

    override fun onDestroy() {
        // A configuration change destroys this Fragment but keeps the request and draft alive.
        if (
            activity?.isChangingConfigurations != true &&
                (isRemoving || activity?.isFinishing == true)
        ) {
            finishVerification()
        }
        super.onDestroy()
    }

    private fun finishVerification() {
        SourceVerificationHelp.checkResult(verificationResultKey)
        activity?.finish()
    }
}
