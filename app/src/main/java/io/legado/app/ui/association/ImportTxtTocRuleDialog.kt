package io.legado.app.ui.association

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppTxtTocRuleImportRepository
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.showDialogFragment

class ImportTxtTocRuleDialog() : BaseComposeDialogFragment(), CodeDialog.Callback {
    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply { putString("source", source); putBoolean("finishOnDismiss", finishOnDismiss) }
    }
    private val viewModel by viewModels<ImportTxtTocRuleViewModel> {
        viewModelFactory { initializer { ImportTxtTocRuleViewModel(AppTxtTocRuleImportRepository(requireContext()),
            createSavedStateHandle(), arguments?.getString("source").orEmpty()) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); isCancelable = false }
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    @Composable override fun Content() {
        ImportTxtTocRuleRoute(viewModel, { isAdded && !childFragmentManager.isStateSaved }, { event ->
            showDialogFragment(CodeDialog(event.json, disableEdit = false, requestId = event.key))
        }, { dismissAllowingStateLoss() })
    }
    override fun onCodeSave(code: String, requestId: String?) { viewModel.edit(code, requestId) }
    override fun dismiss() { viewModel.cancel() }
}
