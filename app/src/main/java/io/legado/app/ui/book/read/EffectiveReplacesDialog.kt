package io.legado.app.ui.book.read

import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppEffectiveReplacementRepository
import io.legado.app.data.repository.EffectiveReplacementRow
import io.legado.app.model.ReadBook
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.utils.setLayout

class EffectiveReplacesDialog() : BaseComposeDialogFragment() {
    constructor(ids: List<Long>) : this() { arguments = Bundle().apply { putLongArray("sourceRuleIds", ids.toLongArray()) } }
    interface Callback { fun onEffectiveSourceRulesChanged() }
    private val sourceReplacement get() = arguments?.containsKey("sourceRuleIds") == true
    private val reader by activityViewModels<ReadBookViewModel>()
    private val model by viewModels<EffectiveReplacementViewModel> {
        viewModelFactory { initializer { EffectiveReplacementViewModel(AppEffectiveReplacementRepository(), createSavedStateHandle(),
            arguments?.getLongArray("sourceRuleIds")?.toList(),
            ReadBook.curTextChapter?.effectiveReplaceRules.orEmpty().map { EffectiveReplacementRow(it.id, it.name) }, "繁简转换") } }
    }
    private val edit = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == AppCompatActivity.RESULT_OK) model.edited()
    }
    override fun onStart() { super.onStart(); setLayout(.9f, ViewGroup.LayoutParams.WRAP_CONTENT) }
    @Composable override fun Content() {
        val state by model.state.collectAsStateWithLifecycle()
        SideEffect { isCancelable = !state.busy }
        EffectiveReplacementRoute(model, { isAdded && !parentFragmentManager.isStateSaved },
            { edit.launch(ReplaceEditActivity.startIntent(requireContext(), it)) }, ::refresh, ::dismissAllowingStateLoss)
    }
    private fun refresh() {
        if (sourceReplacement) (parentFragment as? Callback)?.onEffectiveSourceRulesChanged()
        else reader.replaceRuleChanged()
    }
    override fun dismiss() { model.close() }
    override fun onDismiss(dialog: DialogInterface) {
        if (activity?.isChangingConfigurations != true) { model.close(); if (model.consumeRefresh()) refresh() }
        super.onDismiss(dialog)
    }
}
