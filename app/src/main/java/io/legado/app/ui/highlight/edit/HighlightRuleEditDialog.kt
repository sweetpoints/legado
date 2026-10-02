package io.legado.app.ui.highlight.edit

import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppHighlightRuleEditorStore
import io.legado.app.data.repository.DefaultHighlightRuleEditorRepository
import io.legado.app.data.repository.HighlightRuleDraft
import io.legado.app.help.HighlightColors
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyles
import io.legado.app.help.IntentData
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class HighlightRuleEditDialog : BaseComposeDialogFragment(), HighlightStyleDialog.StyleHost {
    private val model by viewModels<HighlightRuleEditorViewModel> {
        viewModelFactory { initializer { HighlightRuleEditorViewModel(DefaultHighlightRuleEditorRepository(AppHighlightRuleEditorStore(requireContext())),
            createSavedStateHandle(), arguments?.getLong(ARG_ID, -1) ?: -1, arguments?.getString(ARG_SEED)) } }
    }
    override fun onStart() {
        super.onStart(); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    override fun onStop() { model.flush(); super.onStop() }
    override fun onCancel(dialog: DialogInterface) { model.cancel(); super.onCancel(dialog) }
    @Composable override fun Content() {
        val state by model.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.draft?.rule?.style) { refreshStyleDialog() }
        HighlightRuleEditorRoute(model, { isAdded && !parentFragmentManager.isStateSaved && !childFragmentManager.isStateSaved }, { event ->
            when (event) {
                HighlightRuleEditorEvent.Saved -> { ReadBook.upHighlightRules(); dismissAllowingStateLoss() }
                HighlightRuleEditorEvent.Close -> dismissAllowingStateLoss()
                HighlightRuleEditorEvent.Missing -> { requireContext().toastOnUi(R.string.highlight_rule_not_found); dismissAllowingStateLoss() }
                HighlightRuleEditorEvent.Invalid -> requireContext().toastOnUi(getString(R.string.highlight_rule_invalid, model.state.value.draft?.rule?.pattern.orEmpty()))
                HighlightRuleEditorEvent.Style -> if (childFragmentManager.findFragmentByTag(HighlightStyleDialog::class.simpleName) == null) showDialogFragment<HighlightStyleDialog>()
            }
        }, ::onColorSelected, Modifier.fillMaxSize())
    }
    override fun currentHighlightStyle(): HighlightStyle = model.state.value.draft?.rule?.style ?: HighlightStyle()
    override fun onHighlightStyleChanged(style: HighlightStyle) { model.style(style) }
    override fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean) { model.color(dialogId, initial, withAlpha) }
    fun onColorSelected(dialogId: Int, color: Int) {
        model.style(HighlightStyleDialog.applyChannelColor(currentHighlightStyle(), dialogId, color))
        refreshStyleDialog()
    }
    fun onDialogDismissed(dialogId: Int) = Unit
    private fun refreshStyleDialog() {
        (childFragmentManager.findFragmentByTag(HighlightStyleDialog::class.simpleName) as? HighlightStyleDialog)?.refresh()
    }
    data class ColorPickerConfig(val dialogId: Int, val color: Int, val withAlpha: Boolean, val presets: IntArray)
    companion object {
        private const val ARG_ID = "id"
        private const val ARG_SEED = "seed"
        fun create(pattern: String, isRegex: Boolean = false, scope: String? = null, style: String? = null) = HighlightRuleEditDialog().apply {
            val draft = HighlightRuleDraft(name = pattern, pattern = pattern, isRegex = isRegex, scope = scope,
                style = (GSON.fromJsonObject<HighlightStyle>(initialStyle(style)).getOrNull() ?: HighlightStyle()).normalized())
            arguments = Bundle().apply { putString(ARG_SEED, IntentData.put(draft)) }
        }
        fun edit(id: Long) = HighlightRuleEditDialog().apply { arguments = Bundle().apply { putLong(ARG_ID, id) } }
        fun initialStyle(style: String?): String = style ?: GSON.toJson(HighlightStyles.presets.first())
        fun colorPickerConfig(dialogId: Int, initial: Int, withAlpha: Boolean): ColorPickerConfig {
            val presets = if (withAlpha) HighlightColors.bg else HighlightColors.text
            return ColorPickerConfig(dialogId, initial.takeIf { it != 0 } ?: presets.first(), withAlpha, presets)
        }
    }
}
