package io.legado.app.ui.autoTask

import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.FileAutoTaskImportRepository
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.setLayout
import splitties.init.appCtx

class ImportAutoTaskDialog() : BaseComposeDialogFragment(), CodeDialog.Callback {
    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("sessionId", FileAutoTaskImportRepository.stage(appCtx, source))
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val legacy = arguments
        if (legacy?.containsKey("sessionId") != true) {
            arguments = Bundle().apply {
                putString("sessionId", FileAutoTaskImportRepository.stage(appCtx, legacy?.getString("source").orEmpty()))
                putBoolean("finishOnDismiss", legacy?.getBoolean("finishOnDismiss") == true)
            }
        }
    }
    internal val model by viewModels<AutoTaskImportViewModel> {
        viewModelFactory { initializer {
            AutoTaskImportViewModel(FileAutoTaskImportRepository(requireContext()), createSavedStateHandle(), requireArguments().getString("sessionId")!!)
        } }
    }
    override fun onStart() { super.onStart(); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
    @Composable override fun Content() {
        AutoTaskImportRoute(model, { isAdded && !childFragmentManager.isStateSaved && !parentFragmentManager.isStateSaved },
            ::openEditor, ::dismissAllowingStateLoss, { isCancelable = it })
    }
    private fun openEditor(json: String, requestId: String) {
        val tag = "auto-task-editor:$requestId"
        if (childFragmentManager.findFragmentByTag(tag) == null) CodeDialog(json, disableEdit = false, requestId = requestId).show(childFragmentManager, tag)
    }
    override fun onCodeSave(code: String, requestId: String?) { model.codeSaved(code, requestId) }
    override fun onCancel(dialog: DialogInterface) { model.cancel(); super.onCancel(dialog) }
}
