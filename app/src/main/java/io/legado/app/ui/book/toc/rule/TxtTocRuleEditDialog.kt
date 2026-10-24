package io.legado.app.ui.book.toc.rule

import io.legado.app.utils.resizeForIme

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.repository.RoomTxtTocRuleEditorRepository
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi

class TxtTocRuleEditDialog() : BaseDialogFragment(0) {
    constructor(id: Long?) : this() {
        if (id != null) arguments = Bundle().apply { putLong("id", id) }
    }

    val viewModel by
        viewModels<TxtTocRuleEditorViewModel> {
            viewModelFactory {
                initializer {
                    TxtTocRuleEditorViewModel(
                        RoomTxtTocRuleEditorRepository(),
                        createSavedStateHandle(),
                        arguments?.takeIf { it.containsKey("id") }?.getLong("id"),
                    )
                }
            }
        }
    private val textEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                if (
                    !viewModel.codeResult(
                        result.data?.getStringExtra("text"),
                        result.data?.getIntExtra("cursorPosition", 0),
                    )
                )
                    toastOnUi(R.string.focus_lost_on_textbox)
            } else viewModel.codeCancelled()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
        isCancelable = false
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            resizeForIme()
        }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) requestEditorClose()
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        view?.setBackgroundColor(Color.TRANSPARENT)
        view?.post {
            if (isResumed) {
                (childFragmentManager.findFragmentByTag("txt-toc-editor-flutter") as? TxtTocRuleEditorFlutterFragment)?.onPostResume()
                coordinator.onHostReady()
            }
        }
    }

    override fun dismiss() { requestEditorClose() }

    private fun requestEditorClose() {
        val fragment = childFragmentManager.findFragmentByTag("txt-toc-editor-flutter") as? TxtTocRuleEditorFlutterFragment
        // Let the owning Dart page include pending/rejected local input in its dirty-close request.
        // Before an engine starts there is no editable Dart state to lose.
        if (fragment?.flutterEngine?.dartExecutor?.isExecutingDart == true) fragment.onBackPressed()
        else coordinator.requestClose()
    }

    internal val coordinator by lazy {
        TxtTocRuleEditorNativeCoordinator(viewModel, lifecycle, lifecycleScope,
            { isAdded && !parentFragmentManager.isStateSaved && !childFragmentManager.isStateSaved },
            { request ->
                val title = when (request.field) {
                    TxtTocEditorField.Name -> R.string.name
                    TxtTocEditorField.Regex -> R.string.regex
                    TxtTocEditorField.Replacement -> R.string.replace_to_js
                    TxtTocEditorField.Example -> R.string.example
                }
                textEditLauncher.launch(Intent(requireActivity(), CodeEditActivity::class.java).apply {
                    putExtra("text", request.text)
                    putExtra("title", getString(title))
                    putExtra("cursorPosition", request.cursor)
                })
            }, { requireContext().sendToClip(it) }, { requireContext().getClipText() },
            { toastOnUi(R.string.please_focus_cursor_on_textbox) },
            { txtTocRuleEditorCallback(parentFragment, activity)?.saveTxtTocRule(it.entity()) },
            { toastOnUi(it.localizedMessage ?: "ERROR") }, ::dismissAllowingStateLoss)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = R.id.txt_toc_rule_editor_flutter_content }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(Color.TRANSPARENT)
        coordinator
        if (childFragmentManager.findFragmentByTag("txt-toc-editor-flutter") == null)
            childFragmentManager.beginTransaction().add(R.id.txt_toc_rule_editor_flutter_content,
                TxtTocRuleEditorFlutterFragment(), "txt-toc-editor-flutter").commitNow()
    }

    override fun onDestroy() { coordinator.close(); super.onDestroy() }
    interface Callback { fun saveTxtTocRule(txtTocRule: io.legado.app.data.entities.TxtTocRule) }
}

internal fun txtTocRuleEditorCallback(parent: Any?, activity: Any?): TxtTocRuleEditDialog.Callback? =
    (parent as? TxtTocRuleEditDialog.Callback) ?: activity as? TxtTocRuleEditDialog.Callback
