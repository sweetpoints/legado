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
import io.legado.app.utils.resizeForIme
import io.legado.app.utils.setLayout
import java.util.UUID

/** Independent UI engine/nonce: the editor binding and native style sheet remain intact underneath. */
class HighlightColorPickerDialog : BaseDialogFragment(0) {
    internal val viewModel by viewModels<HighlightColorPickerViewModel> {
        viewModelFactory { initializer {
            val args = requireArguments()
            val config = HighlightRuleEditDialog.colorPickerConfig(args.getInt("channel"), args.getInt("color"), args.getBoolean("alpha"))
            HighlightColorPickerViewModel(createSavedStateHandle(), config.color, config.withAlpha,
                checkNotNull(args.getString("nonce")), config.presets.toList())
        } }
    }
    internal val coordinator by lazy {
        HighlightColorPickerNativeCoordinator(viewModel, lifecycle, lifecycleScope,
            { isAdded && !parentFragmentManager.isStateSaved && !childFragmentManager.isStateSaved && parentFragment is HighlightRuleEditDialog },
            mapOf("heading" to getString(R.string.highlight_style), "save" to getString(R.string.ok), "cancel" to getString(R.string.cancel))) { save, value ->
                val editor = requireParentFragment() as HighlightRuleEditDialog
                if (save) editor.onColorSelected(requireArguments().getInt("channel"), value)
                editor.viewModel.closeColor()
                dismissAllowingStateLoss()
            }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setStyle(STYLE_NO_TITLE, 0); isCancelable = false }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = R.id.highlight_color_flutter_content }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(Color.TRANSPARENT); coordinator
        if (childFragmentManager.findFragmentByTag("color-ui") == null)
            childFragmentManager.beginTransaction().add(R.id.highlight_color_flutter_content, HighlightColorPickerFlutterFragment(), "color-ui").commitNow()
    }
    override fun onStart() {
        super.onStart(); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.apply { setBackgroundDrawableResource(R.color.transparent); resizeForIme() }
        dialog?.setOnKeyListener { _, key, event -> if (key == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) requestBackFromHost(); true
        } else false }
    }
    override fun onResume() {
        super.onResume()
        view?.post { if (isResumed) {
            (childFragmentManager.findFragmentByTag("color-ui") as? HighlightColorPickerFlutterFragment)?.onPostResume()
            coordinator.onHostReady()
        } }
    }
    internal fun requestBackFromHost() {
        val flutter = childFragmentManager.findFragmentByTag("color-ui") as? HighlightColorPickerFlutterFragment
        if (flutter?.flutterEngine != null) flutter.onBackPressed() else coordinator.cancel()
    }
    override fun dismiss() { requestBackFromHost() }
    override fun onDestroy() { coordinator.close(); super.onDestroy() }
    companion object {
        internal fun create(draft: HighlightRuleColorDraft) = HighlightColorPickerDialog().apply {
            arguments = Bundle().apply {
                putInt("channel", draft.channel); putInt("color", draft.color); putBoolean("alpha", draft.withAlpha)
                putString("nonce", UUID.randomUUID().toString())
            }
        }
    }
}
