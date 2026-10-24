package io.legado.app.ui.highlight.edit

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
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
import io.legado.app.utils.resizeForIme
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class HighlightRuleEditDialog : BaseDialogFragment(0), HighlightStyleDialog.StyleHost {
    internal val viewModel by viewModels<HighlightRuleEditorViewModel> {
        viewModelFactory { initializer {
            HighlightRuleEditorViewModel(DefaultHighlightRuleEditorRepository(AppHighlightRuleEditorStore(requireContext())),
                createSavedStateHandle(), arguments?.getLong(ARG_ID, -1) ?: -1, arguments?.getString(ARG_SEED))
        } }
    }
    private val preview by lazy { HighlightRuleFlutterPreview(resources) }
    internal val coordinator by lazy {
        HighlightRuleEditorNativeCoordinator(viewModel, lifecycle, lifecycleScope,
            { isAdded && !parentFragmentManager.isStateSaved && !childFragmentManager.isStateSaved },
            mapOf("heading" to getString(R.string.highlight_rule_edit_title), "name" to getString(R.string.name),
                "group" to getString(R.string.highlight_rule_group_hint), "pattern" to getString(R.string.highlight_rule_pattern),
                "regex" to getString(R.string.use_regex), "body" to getString(R.string.scope_content),
                "title" to getString(R.string.highlight_rule_apply_to_title), "scope" to getString(R.string.highlight_rule_scope_hint),
                "style" to getString(R.string.highlight_style), "cancel" to getString(R.string.cancel),
                "save" to getString(R.string.ok), "retry" to getString(R.string.retry)), preview::render, ::refreshStyleDialog,
            { event -> when (event) {
                HighlightRuleEditorEvent.Saved -> {
                    try { ReadBook.upHighlightRules() }
                    catch (error: Exception) { requireContext().toastOnUi(error.localizedMessage ?: error.javaClass.simpleName) }
                    finally { dismissAllowingStateLoss() }
                }
                HighlightRuleEditorEvent.Close -> dismissAllowingStateLoss()
                HighlightRuleEditorEvent.Missing -> { requireContext().toastOnUi(R.string.highlight_rule_not_found); dismissAllowingStateLoss() }
                HighlightRuleEditorEvent.Invalid -> requireContext().toastOnUi(getString(R.string.highlight_rule_invalid, viewModel.state.value.draft?.rule?.pattern.orEmpty()))
                HighlightRuleEditorEvent.Style -> if (childFragmentManager.findFragmentByTag(HighlightStyleDialog::class.simpleName) == null)
                    showDialogFragment<HighlightStyleDialog>()
            } },
            { color -> if (childFragmentManager.findFragmentByTag(COLOR_TAG) == null)
                HighlightColorPickerDialog.create(color).showNow(childFragmentManager, COLOR_TAG) },
            { error -> requireContext().toastOnUi(error.localizedMessage ?: error.javaClass.simpleName) })
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setStyle(STYLE_NO_TITLE, 0); isCancelable = false
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = R.id.highlight_editor_flutter_content }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(Color.TRANSPARENT); coordinator
        if (childFragmentManager.findFragmentByTag(EDITOR_TAG) == null)
            childFragmentManager.beginTransaction().add(R.id.highlight_editor_flutter_content, HighlightRuleEditorFlutterFragment(), EDITOR_TAG).commitNow()
    }
    override fun onStart() {
        super.onStart(); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.apply { setBackgroundDrawableResource(R.color.transparent); resizeForIme() }
        dialog?.setOnKeyListener { _, key, event -> if (key == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) requestBackFromHost(); true
        } else false }
    }
    override fun onResume() {
        super.onResume(); view?.setBackgroundColor(Color.TRANSPARENT)
        view?.post { if (isResumed) {
            (childFragmentManager.findFragmentByTag(EDITOR_TAG) as? HighlightRuleEditorFlutterFragment)?.onPostResume()
            coordinator.onHostReady()
        } }
    }
    override fun onStop() { viewModel.flush(); super.onStop() }
    override fun onDestroy() { coordinator.close(); super.onDestroy() }
    internal fun requestBackFromHost() {
        (childFragmentManager.findFragmentByTag(COLOR_TAG) as? HighlightColorPickerDialog)?.takeIf { it.isAdded }?.let {
            it.requestBackFromHost(); return
        }
        (childFragmentManager.findFragmentByTag(HighlightStyleDialog::class.simpleName) as? HighlightStyleDialog)?.takeIf { it.isAdded }?.let {
            it.dismissAllowingStateLoss(); return
        }
        val flutter = childFragmentManager.findFragmentByTag(EDITOR_TAG) as? HighlightRuleEditorFlutterFragment
        if (flutter?.flutterEngine != null) flutter.onBackPressed() else coordinator.cancel()
    }
    override fun dismiss() { requestBackFromHost() }
    override fun currentHighlightStyle(): HighlightStyle = viewModel.state.value.draft?.rule?.style ?: HighlightStyle()
    override fun onHighlightStyleChanged(style: HighlightStyle) { viewModel.style(style); refreshStyleDialog() }
    override fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean) { viewModel.color(dialogId, initial, withAlpha) }
    fun onColorSelected(dialogId: Int, color: Int) {
        viewModel.style(HighlightStyleDialog.applyChannelColor(currentHighlightStyle(), dialogId, color)); refreshStyleDialog()
    }
    fun onDialogDismissed(dialogId: Int) = Unit
    private fun refreshStyleDialog() {
        (childFragmentManager.findFragmentByTag(HighlightStyleDialog::class.simpleName) as? HighlightStyleDialog)?.refresh()
    }

    data class ColorPickerConfig(
        val dialogId: Int,
        val color: Int,
        val withAlpha: Boolean,
        val presets: IntArray,
    )

    companion object {
        private const val EDITOR_TAG = "highlight-editor-flutter"
        internal const val COLOR_TAG = "highlight-color-flutter"
        private const val ARG_ID = "id"
        private const val ARG_SEED = "seed"

        fun create(
            pattern: String,
            isRegex: Boolean = false,
            scope: String? = null,
            style: String? = null,
        ) =
            HighlightRuleEditDialog().apply {
                val draft =
                    HighlightRuleDraft(
                        name = pattern,
                        pattern = pattern,
                        isRegex = isRegex,
                        scope = scope,
                        style =
                            (GSON.fromJsonObject<HighlightStyle>(initialStyle(style)).getOrNull()
                                    ?: HighlightStyle())
                                .normalized(),
                    )
                arguments = Bundle().apply { putString(ARG_SEED, IntentData.put(draft)) }
            }

        fun edit(id: Long) =
            HighlightRuleEditDialog().apply { arguments = Bundle().apply { putLong(ARG_ID, id) } }

        fun initialStyle(style: String?): String =
            style ?: GSON.toJson(HighlightStyles.presets.first())

        fun colorPickerConfig(dialogId: Int, initial: Int, withAlpha: Boolean): ColorPickerConfig {
            val presets = if (withAlpha) HighlightColors.bg else HighlightColors.text
            return ColorPickerConfig(
                dialogId,
                initial.takeIf { it != 0 } ?: presets.first(),
                withAlpha,
                presets,
            )
        }
    }
}
