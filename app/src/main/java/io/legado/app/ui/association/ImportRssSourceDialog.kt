package io.legado.app.ui.association

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppRssImportRequestRepository
import io.legado.app.data.repository.AppRssImportStore
import io.legado.app.data.repository.DefaultRssImportRepository
import io.legado.app.ui.book.read.EffectiveReplacesDialog
import io.legado.app.ui.book.read.ManualReplaceRulesDialog
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class ImportRssSourceDialog() : BaseComposeDialogFragment(), CodeDialog.Callback,
    ManualReplaceRulesDialog.Callback, EffectiveReplacesDialog.Callback {
    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply { putString("source", source); putBoolean("finishOnDismiss", finishOnDismiss) }
    }
    private val viewModel by viewModels<RssImportViewModel> {
        viewModelFactory { initializer { RssImportViewModel(DefaultRssImportRepository(AppRssImportStore(requireContext())),
            AppRssImportRequestRepository(requireContext()), createSavedStateHandle(), arguments?.getString("source").orEmpty(),
            RssImportSearchLabels(getString(R.string.enabled), getString(R.string.disabled),
                getString(R.string.need_login), getString(R.string.no_group))) } }
    }
    private val replaceRuleActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) refreshFromOpenCode()
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); isCancelable = false }
    override fun onStart() {
        super.onStart(); dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    @Composable override fun Content() {
        RssImportRoute(viewModel, { isAdded && !childFragmentManager.isStateSaved }, ::handle,
            { pending -> openCodeDialog()?.setReplaceRuleRefreshPending(pending) }, { dismissAllowingStateLoss() })
    }
    private fun handle(event: RssImportEffect) {
        when (event.action) {
            RssImportAction.Code -> viewModel.state.value.items.find { it.key == event.key }?.let { item ->
                showDialogFragment(CodeDialog(item.originalJson, disableEdit = false, requestId = item.key,
                    alternateCode = item.replacedJson, showAlternate = viewModel.state.value.useReplacement, showReplaceRules = true))
            }
            RssImportAction.ReplaceRules -> replaceRuleActivity.launch(Intent(requireContext(), ReplaceRuleActivity::class.java))
            RssImportAction.Manual -> showDialogFragment(ManualReplaceRulesDialog(event.ids, event.key))
            RssImportAction.Effective -> showDialogFragment(EffectiveReplacesDialog(event.ids))
            RssImportAction.SyncCode -> openCodeDialog()?.let { dialog ->
                dialog.setReplaceRuleRefreshPending(false)
                if (event.text == "clear") dialog.clearAlternateCode() else dialog.refreshAlternateCode()
            }
            RssImportAction.Toast -> toastOnUi(if (event.text == "格式不对") getString(R.string.wrong_format) else event.text)
        }
    }
    override fun onCodeSave(code: String, requestId: String?) {
        if (requestId != null) viewModel.refresh(requestId, code)
    }
    override fun onOpenReplaceRules() { viewModel.replaceRules() }
    override fun onShowSourceReplacements(code: String, requestId: String?, manual: Boolean) {
        if (requestId != null) viewModel.refresh(requestId, code, openManual = manual)
    }
    override fun onManualSourceRulesSelected(ids: List<Long>, requestId: String?) {
        val key = requestId?.takeUnless { it == "-1" }
        val dialog = openCodeDialog()?.takeIf { it.requestId == key }
        viewModel.refresh(key, dialog?.currentOriginalCode(), ids)
    }
    override fun onEffectiveSourceRulesChanged() { refreshFromOpenCode() }
    private fun refreshFromOpenCode() {
        val code = openCodeDialog()
        viewModel.refresh(code?.requestId, code?.currentOriginalCode())
    }
    private fun openCodeDialog() = childFragmentManager.findFragmentByTag(CodeDialog::class.simpleName) as? CodeDialog
    override fun isReplaceRuleRefreshPending() = viewModel.state.value.loading || viewModel.state.value.busy || viewModel.state.value.pendingRefresh
    override fun isManualSourceReplacementEnabled() = !viewModel.state.value.automatic && !viewModel.state.value.loading
    override fun getCodeAlternate(requestId: String?) = viewModel.alternate(requestId)
    override fun dismiss() { viewModel.cancel() }
}
