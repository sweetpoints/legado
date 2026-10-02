package io.legado.app.ui.association

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppHighlightImportRepository
import io.legado.app.model.ReadBook

class ImportHighlightRuleDialog() : BaseComposeDialogFragment() {
    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply { putString("source", source); putBoolean("finishOnDismiss", finishOnDismiss) }
    }
    private val viewModel by viewModels<ImportHighlightRuleViewModel> {
        viewModelFactory { initializer { ImportHighlightRuleViewModel(AppHighlightImportRepository(requireContext()),
            createSavedStateHandle(), arguments?.getString("source").orEmpty()) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); isCancelable = false }
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    @Composable override fun Content() {
        ImportHighlightRuleRoute(viewModel, { isAdded && !parentFragmentManager.isStateSaved },
            { ReadBook.upHighlightRules() }, { dismissAllowingStateLoss() })
    }
    override fun dismiss() { viewModel.cancel() }
}

internal fun AppCompatActivity.showImportHighlightRuleDialog(source: String, finishOnDismiss: Boolean = false) {
    val tag = ImportHighlightRuleDialog::class.simpleName
    if (supportFragmentManager.findFragmentByTag(tag) == null) {
        ImportHighlightRuleDialog(source, finishOnDismiss).show(supportFragmentManager, tag)
    }
}
